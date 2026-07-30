package com.skilaparthi.scrollcat

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Production wrapper around LiteRT-LM for on-device reply generation.
 *
 * ALL lifecycle paths (warmup / initialize / generate / idle release / shutdown)
 * share one companion [lifecycleMutex] so concurrent screen-on + message-arrival
 * warmups cannot build two engines.
 */
class OnDeviceAiEngine(private val context: Context) {

    companion object {
        private const val TAG = "ScrollCat"
        const val MODEL_FILE_NAME = DeviceCapabilityChecker.MODEL_E2B_FILE
        const val MODEL_270M_FILE_NAME = DeviceCapabilityChecker.MODEL_270M_FILE
        /** Caps KV cache (prompt + response). 512 was too tight for our system prompt. */
        private const val MAX_NUM_TOKENS = 1024
        /** Wait after close() so native GPU teardown can drain before next Engine init. */
        private const val POST_CLOSE_SETTLE_MS = 500L

        /** Single lock for the shared engine — never create a per-instance Mutex. */
        private val lifecycleMutex = Mutex()

        @Volatile
        private var shared: OnDeviceAiEngine? = null

        fun defaultModelPath(context: Context): String =
            DeviceCapabilityChecker.modelPathFor(context, MODEL_FILE_NAME)

        fun modelPath(context: Context, fileName: String): String =
            DeviceCapabilityChecker.modelPathFor(context, fileName)

        fun isReady(): Boolean = shared?.engine != null

        fun isGenerating(): Boolean = shared?.isGenerating == true

        private fun instance(context: Context): OnDeviceAiEngine {
            synchronized(this) {
                return shared ?: OnDeviceAiEngine(context.applicationContext).also { shared = it }
            }
        }

        private fun <T> withLifecycleLock(block: suspend () -> T): T = runBlocking {
            lifecycleMutex.withLock { block() }
        }

        /**
         * Load [tier]. Pass [forceReload]=true after a manual model switch.
         * Concurrent warmups skip when already ready or init is in progress.
         */
        fun initialize(
            context: Context,
            tier: DeviceCapabilityChecker.OnDeviceModelTier,
            forceReload: Boolean = false
        ): Boolean {
            if (DeviceCapabilityChecker.shouldSkipOnDeviceForDoze(context)) return false
            return withLifecycleLock {
                instance(context).initializeLocked(tier, forceReload)
            }
        }

        fun initialize(context: Context, modelPath: String): Boolean {
            if (DeviceCapabilityChecker.shouldSkipOnDeviceForDoze(context)) return false
            val tier = DeviceCapabilityChecker.selectModelTier(context)
            val resolved = when {
                tier != null && tier.modelPath == modelPath -> tier
                tier != null -> tier.copy(modelPath = modelPath)
                else -> DeviceCapabilityChecker.OnDeviceModelTier(
                    tierLabel = DeviceCapabilityChecker.TIER_LABEL_LEGACY_CPU,
                    modelFileName = File(modelPath).name,
                    modelPath = modelPath,
                    preferredBackend = Backend.CPU(threadCount = 4),
                    attemptNpu = false,
                    requiredHeadroomMb = DeviceCapabilityChecker.SMALL_MODEL_REQUIRED_HEADROOM_MB
                )
            }
            return initialize(context, resolved, forceReload = true)
        }

        /**
         * Warmup / re-init. Safe to call from many threads — duplicates are skipped
         * inside [lifecycleMutex]. Skips entirely during Deep Doze (no CPU handoff attempt).
         */
        fun ensureInitialized(context: Context): Boolean {
            if (!SettingsManager.isOnDeviceAiEnabled(context)) {
                Log.d(TAG, "OnDeviceAiEngine: skipped warmup — on-device AI disabled in settings")
                return false
            }
            if (DeviceCapabilityChecker.shouldSkipOnDeviceForDoze(context)) return false
            return withLifecycleLock {
                val eng = instance(context)
                if (eng.engine != null) {
                    Log.d(
                        TAG,
                        "OnDeviceAiEngine: skipping duplicate warmup — already initializing/initialized"
                    )
                    return@withLifecycleLock true
                }
                if (eng.isInitializing) {
                    Log.d(
                        TAG,
                        "OnDeviceAiEngine: skipping duplicate warmup — already initializing/initialized"
                    )
                    return@withLifecycleLock false
                }
                if (!DeviceCapabilityChecker.checkOnDeviceAiViability(context)) {
                    return@withLifecycleLock false
                }
                val tier = DeviceCapabilityChecker.resolveModelTier(context)
                    ?: return@withLifecycleLock false
                if (!File(tier.modelPath).isFile) {
                    Log.d(TAG, "OnDeviceAiEngine warmup skipped: model missing at ${tier.modelPath}")
                    return@withLifecycleLock false
                }
                Log.d(TAG, "OnDeviceAiEngine: warmup initialize (tier='${tier.tierLabel}')")
                eng.initializeLocked(tier, forceReload = false)
            }
        }

        fun generateReply(prompt: String): String? = shared?.generateReply(prompt)

        fun generateReply(systemPrompt: String, userMessage: String): String? {
            // Even if an engine is still loaded, never generate during Deep Doze —
            // GPU→CPU switching is broken; fall straight through to Groq.
            DeviceIdleMonitor.refresh()
            if (DeviceIdleMonitor.isDeepDoze()) {
                Log.d(
                    TAG,
                    "Doze active — skipping on-device attempt entirely, using Groq directly"
                )
                return null
            }
            return shared?.generateReply(systemPrompt, userMessage)
        }

        fun releaseForIdle(): Boolean {
            val eng = shared ?: return true
            return withLifecycleLock { eng.releaseForIdleLocked() }
        }

        fun shutdown() {
            withLifecycleLock {
                shared?.shutdownLocked()
            }
            synchronized(this) {
                shared = null
            }
        }
    }

    @Volatile
    private var isGenerating = false
    @Volatile
    private var isInitializing = false
    private var engine: Engine? = null
    private var modelPath: String? = null
    private var activeBackend: Backend? = null
    /** ElapsedRealtime when the last engine.close() returned (Kotlin-level). */
    @Volatile
    private var lastCloseReturnedElapsedMs: Long = 0L
    /** Backend that was closed most recently (for comparing GPU→CPU handoff). */
    @Volatile
    private var lastClosedBackendName: String? = null
    /** Identity hash of the last EngineConfig used to successfully initialize. */
    @Volatile
    private var lastInitConfigIdentity: Int = 0

    fun isReady(): Boolean = engine != null

    /**
     * Must only be called while holding [lifecycleMutex].
     */
    private suspend fun initializeLocked(
        tier: DeviceCapabilityChecker.OnDeviceModelTier,
        forceReload: Boolean
    ): Boolean {
        if (!forceReload && engine != null) {
            Log.d(
                TAG,
                "OnDeviceAiEngine: skipping duplicate warmup — already initializing/initialized"
            )
            return true
        }
        if (isInitializing) {
            Log.d(
                TAG,
                "OnDeviceAiEngine: skipping duplicate warmup — already initializing/initialized"
            )
            return false
        }

        val path = tier.modelPath
        val file = File(path)
        if (!file.isFile || !file.canRead()) {
            Log.e(TAG, "OnDeviceAiEngine: model not readable: $path")
            return false
        }

        if (!DeviceCapabilityChecker.canSafelyLoadModel(context, tier.requiredHeadroomMb)) {
            Log.d(TAG, "On-device AI skipped: insufficient available memory")
            return false
        }

        isInitializing = true
        try {
            val msSinceClose = if (lastCloseReturnedElapsedMs == 0L) {
                -1L
            } else {
                android.os.SystemClock.elapsedRealtime() - lastCloseReturnedElapsedMs
            }
            Log.d(
                TAG,
                "OnDeviceAiEngine: init begin tier='${tier.tierLabel}' " +
                    "forceReload=$forceReload engineCurrentlyNull=${engine == null} " +
                    "msSinceLastCloseReturned=$msSinceClose " +
                    "lastClosedBackend=$lastClosedBackendName"
            )

            shutdownLocked()
            this.modelPath = path
            val nativeLibDir = context.applicationInfo.nativeLibraryDir

            suspend fun tryInit(label: String, backend: Backend): Boolean {
                Log.d(
                    TAG,
                    "OnDeviceAiEngine: attempting $label backend (tier='${tier.tierLabel}')"
                )
                return try {
                    val cacheDir = cacheDirFor(backend)
                    val backendType = backend::class.simpleName ?: label
                    Log.d(
                        TAG,
                        "EngineConfig cacheDir: ${cacheDir.absolutePath} for backend=$backendType"
                    )
                    val config = EngineConfig(
                        modelPath = path,
                        backend = backend,
                        maxNumTokens = MAX_NUM_TOKENS,
                        cacheDir = cacheDir.absolutePath
                    )
                    val configId = System.identityHashCode(config)
                    Log.d(
                        TAG,
                        "OnDeviceAiEngine: fresh EngineConfig identity=$configId " +
                            "(priorInitConfigIdentity=$lastInitConfigIdentity " +
                            "sameObjectAsPrior=${configId == lastInitConfigIdentity && lastInitConfigIdentity != 0}) " +
                            "modelPath=${config.modelPath} " +
                            "backend=${config.backend::class.simpleName}/${config.backend} " +
                            "maxNumTokens=${config.maxNumTokens} " +
                            "cacheDir=${config.cacheDir}"
                    )
                    val eng = Engine(config)
                    val engId = System.identityHashCode(eng)
                    Log.d(
                        TAG,
                        "OnDeviceAiEngine: fresh Engine identity=$engId — calling initialize()"
                    )
                    eng.initialize()
                    engine = eng
                    activeBackend = backend
                    lastInitConfigIdentity = configId
                    Log.d(
                        TAG,
                        "OnDeviceAiEngine: $label backend ready " +
                            "(engineId=$engId, ${backend::class.simpleName}, " +
                            "maxNumTokens=$MAX_NUM_TOKENS, tier='${tier.tierLabel}', " +
                            "cacheDir=${cacheDir.absolutePath})"
                    )
                    true
                } catch (e: Throwable) {
                    Log.d(TAG, "OnDeviceAiEngine: $label failed: ${e.message}")
                    Log.e(TAG, "OnDeviceAiEngine: $label failure detail", e)
                    false
                }
            }

            if (!tier.attemptNpu) {
                Log.d(
                    TAG,
                    "OnDeviceAiEngine: tier='${tier.tierLabel}' — skipping NPU/GPU, using CPU " +
                        "(backend-specific cacheDir isolates GPU artifacts)"
                )
                if (tryInit("CPU", tier.preferredBackend)) return true
                Log.e(TAG, "OnDeviceAiEngine: CPU init failed for $path")
                this.modelPath = null
                return false
            }

            if (tryInit("NPU", Backend.NPU(nativeLibraryDir = nativeLibDir))) return true

            if (tier.preferredBackend is Backend.GPU) {
                Log.d(TAG, "OnDeviceAiEngine: NPU failed, falling back to GPU (E2B-GPU tier)")
                if (tryInit("GPU", Backend.GPU())) return true

                Log.d(TAG, "OnDeviceAiEngine: GPU failed, falling back to CPU")
                if (tryInit("CPU", Backend.CPU(threadCount = cpuThreadCount()))) return true
            } else {
                Log.d(TAG, "OnDeviceAiEngine: NPU failed, using preferred CPU backend")
                if (tryInit("CPU", tier.preferredBackend)) return true
            }

            Log.e(TAG, "OnDeviceAiEngine: all backends failed for $path")
            this.modelPath = null
            return false
        } finally {
            isInitializing = false
        }
    }

    fun generateReply(prompt: String): String? =
        generateReply(systemPrompt = "", userMessage = prompt)

    fun generateReply(systemPrompt: String, userMessage: String): String? =
        withLifecycleLock {
            val eng = engine
            if (eng == null) {
                Log.d(TAG, "OnDeviceAiEngine: generateReply skipped — not initialized")
                return@withLifecycleLock null
            }
            if (userMessage.isBlank()) return@withLifecycleLock null

            isGenerating = true
            try {
                return@withLifecycleLock generateWith(eng, systemPrompt, userMessage)
            } catch (e: Throwable) {
                val backendName = activeBackend?.let { it::class.simpleName } ?: "unknown"
                Log.d(
                    TAG,
                    "OnDeviceAiEngine: generation failed on $backendName (${e.message}); " +
                        "retrying with fresh CPU engine"
                )
                Log.e(TAG, "OnDeviceAiEngine: generation failure detail", e)

                val path = modelPath ?: return@withLifecycleLock null

                try {
                    val msSinceClose = if (lastCloseReturnedElapsedMs == 0L) {
                        -1L
                    } else {
                        android.os.SystemClock.elapsedRealtime() - lastCloseReturnedElapsedMs
                    }
                    Log.d(
                        TAG,
                        "OnDeviceAiEngine: CPU-retry build after generate fail — " +
                            "msSinceLastCloseReturned=$msSinceClose " +
                            "lastClosedBackend=$lastClosedBackendName"
                    )
                    shutdownLocked()
                    val cpuBackend = Backend.CPU(threadCount = cpuThreadCount())
                    val cacheDir = cacheDirFor(cpuBackend)
                    Log.d(
                        TAG,
                        "EngineConfig cacheDir: ${cacheDir.absolutePath} for backend=CPU"
                    )
                    val config = EngineConfig(
                        modelPath = path,
                        backend = cpuBackend,
                        maxNumTokens = MAX_NUM_TOKENS,
                        cacheDir = cacheDir.absolutePath
                    )
                    val configId = System.identityHashCode(config)
                    Log.d(
                        TAG,
                        "OnDeviceAiEngine: fresh EngineConfig identity=$configId " +
                            "(CPU-retry) modelPath=${config.modelPath} " +
                            "backend=${config.backend::class.simpleName}/${config.backend} " +
                            "maxNumTokens=${config.maxNumTokens} cacheDir=${config.cacheDir}"
                    )
                    val cpuEng = Engine(config)
                    cpuEng.initialize()
                    engine = cpuEng
                    activeBackend = cpuBackend
                    modelPath = path
                    lastInitConfigIdentity = configId
                    Log.d(TAG, "OnDeviceAiEngine: CPU retry engine ready")
                    return@withLifecycleLock generateWith(cpuEng, systemPrompt, userMessage)
                } catch (retry: Throwable) {
                    Log.e(TAG, "OnDeviceAiEngine: CPU retry failed: ${retry.message}", retry)
                    shutdownLocked()
                    return@withLifecycleLock null
                }
            } finally {
                isGenerating = false
            }
        }

    private suspend fun releaseForIdleLocked(): Boolean {
        if (isGenerating || isInitializing) {
            Log.d(
                TAG,
                "OnDeviceAiEngine: idle teardown deferred — generation/init in progress"
            )
            return false
        }
        if (engine == null) return true
        val closingBackend = activeBackend?.let { "${it::class.simpleName}/${it}" } ?: "unknown"
        Log.d(
            TAG,
            "OnDeviceAiEngine: idle teardown starting — closing backend=$closingBackend"
        )
        shutdownLocked()
        Log.d(
            TAG,
            "OnDeviceAiEngine: idle teardown complete after settle " +
                "(lastCloseReturnedElapsedMs=$lastCloseReturnedElapsedMs)"
        )
        return true
    }

    /** Per-backend cache so GPU compiled artifacts never poison a CPU Engine (or vice versa). */
    private fun cacheDirFor(backend: Backend): File {
        val subdir = when (backend) {
            is Backend.GPU -> "litertlm_gpu"
            is Backend.CPU -> "litertlm_cpu"
            is Backend.NPU -> "litertlm_npu"
        }
        val dir = File(context.cacheDir, subdir)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private suspend fun generateWith(
        eng: Engine,
        systemPrompt: String,
        userMessage: String
    ): String {
        val sampler = SamplerConfig(
            topK = 40,
            topP = 0.95,
            temperature = 0.4,
            seed = 0
        )
        val conversationConfig = if (systemPrompt.isBlank()) {
            ConversationConfig(samplerConfig = sampler)
        } else {
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                samplerConfig = sampler
            )
        }
        val engId = System.identityHashCode(eng)
        Log.d(
            TAG,
            "OnDeviceAiEngine: generateWith engineId=$engId " +
                "backend=${activeBackend?.let { "${it::class.simpleName}/${it}" }} " +
                "systemChars=${systemPrompt.length} userChars=${userMessage.length} " +
                "cap=$MAX_NUM_TOKENS freshConversation=true"
        )
        return eng.createConversation(conversationConfig).use { conversation ->
            val message = conversation.sendMessage(userMessage)
            extractMessageText(message)
        }
    }

    private fun extractMessageText(message: com.google.ai.edge.litertlm.Message): String {
        val texts = message.contents.contents.mapNotNull { content ->
            (content as? com.google.ai.edge.litertlm.Content.Text)?.text?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
        if (texts.isNotEmpty()) return texts.joinToString("\n")
        return message.toString()
    }

    private fun cpuThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores - 1).coerceAtLeast(1).also {
            Log.d(TAG, "OnDeviceAiEngine: CPU cores=$cores, threadCount=$it")
        }
    }

    /**
     * Closes the live engine (if any), then settles [POST_CLOSE_SETTLE_MS] while still
     * holding [lifecycleMutex] so the next initialize cannot race native GPU teardown.
     */
    private suspend fun shutdownLocked() {
        val closing = engine
        val closingBackend = activeBackend
        if (closing == null) {
            engine = null
            activeBackend = null
            modelPath = null
            return
        }
        val closeStart = android.os.SystemClock.elapsedRealtime()
        lastClosedBackendName = closingBackend?.let { "${it::class.simpleName}/${it}" }
        Log.d(
            TAG,
            "OnDeviceAiEngine: engine.close() begin backend=$lastClosedBackendName " +
                "engineId=${System.identityHashCode(closing)}"
        )
        try {
            closing.close()
        } catch (e: Exception) {
            Log.w(TAG, "OnDeviceAiEngine: engine close error: ${e.message}")
        }
        val closeEnd = android.os.SystemClock.elapsedRealtime()
        lastCloseReturnedElapsedMs = closeEnd
        Log.d(
            TAG,
            "OnDeviceAiEngine: engine.close() returned in ${closeEnd - closeStart}ms " +
                "backend=$lastClosedBackendName — settling ${POST_CLOSE_SETTLE_MS}ms"
        )
        engine = null
        activeBackend = null
        modelPath = null
        delay(POST_CLOSE_SETTLE_MS)
        lastCloseReturnedElapsedMs = android.os.SystemClock.elapsedRealtime()
        Log.d(TAG, "Post-close settle delay complete (500ms)")
    }
}
