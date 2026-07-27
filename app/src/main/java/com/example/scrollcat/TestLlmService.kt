package com.example.scrollcat

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking

/**
 * STANDALONE LiteRT-LM feasibility test — not wired into production flows.
 * Safe to delete this file + its manifest entry + the litertlm-android dependency after testing.
 *
 * Trigger:
 *   adb shell am start-foreground-service -n com.example.scrollcat/.TestLlmService
 *   adb shell am start-foreground-service -n com.example.scrollcat/.TestLlmService \
 *     --es model_path /data/local/tmp/gemma-4-e2b-it.litertlm
 */
class TestLlmService : Service() {

    companion object {
        private const val TAG = "ScrollCat"
        private const val CHANNEL_ID = "scrollcat_test_llm"
        private const val NOTIFICATION_ID = 9901
        private const val HOLD_MS = 60_000L
        private const val MEMORY_INTERVAL_MS = 15_000L
        private const val SAMPLE_MESSAGE = "hey, you free later?"
        private const val LIB_VERSION = "litertlm-android:latest.release"

        /** Preferred adb-push location (readable by any app). */
        private val CANDIDATE_PATHS = listOf(
            "/data/local/tmp/gemma-4-e2b-it.litertlm",
            "/data/local/tmp/llm/gemma-4-e2b-it.litertlm",
            "/data/local/tmp/llm/gemma-4-e2b-it.task",
            "/data/local/tmp/gemma-4-e2b-it.task",
            "/data/local/tmp/llm/model.litertlm"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: Engine? = null
    private var worker: Thread? = null
    private var memoryTick = 0

    private val memoryRunnable = object : Runnable {
        override fun run() {
            memoryTick++
            logMemory("Hold tick #$memoryTick (${memoryTick * 15}s)")
            if (memoryTick * MEMORY_INTERVAL_MS < HOLD_MS) {
                mainHandler.postDelayed(this, MEMORY_INTERVAL_MS)
            } else {
                Log.d(TAG, "TEST: Hold complete — stopping TestLlmService")
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val overridePath = intent?.getStringExtra("model_path")
        if (worker?.isAlive == true) {
            Log.d(TAG, "TEST: Already running — ignoring duplicate start")
            return START_NOT_STICKY
        }
        worker = Thread({
            runFeasibilityTest(overridePath)
        }, "TestLlmWorker").also { it.start() }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "ScrollCat LLM test",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notification: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ScrollCat LLM feasibility test")
                .setContentText("Loading / generating on-device (LiteRT-LM)…")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun runFeasibilityTest(overridePath: String?) {
        try {
            val modelPath = resolveModelPath(overridePath)
            if (modelPath == null) {
                Log.e(
                    TAG,
                    "TEST: No model file found. Push a .litertlm model, e.g.:\n" +
                        "  adb push gemma-4-e2b-it.litertlm /data/local/tmp/gemma-4-e2b-it.litertlm"
                )
                stopSelfSafely()
                return
            }
            Log.d(TAG, "TEST: Using model path: $modelPath (${File(modelPath).length()} bytes)")
            Log.d(TAG, "TEST: library=$LIB_VERSION (LiteRT-LM, not MediaPipe tasks-genai)")

            logMemory("Before model load")

            // Force CPU for feasibility (avoids GPU delegate quirks on some devices).
            val backend = Backend.CPU()
            val cacheDir = cacheDir.absolutePath
            Log.d(
                TAG,
                "TEST: EngineConfig before initialize — " +
                    "modelPath=$modelPath " +
                    "backend=$backend " +
                    "cacheDir=$cacheDir"
            )

            val loadMs = measureTimeMillis {
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    cacheDir = cacheDir
                )
                Log.d(
                    TAG,
                    "TEST: Built EngineConfig — modelPath=${config.modelPath} " +
                        "backend=${config.backend} cacheDir=${config.cacheDir}"
                )
                val eng = Engine(config)
                eng.initialize()
                engine = eng
            }
            Log.d(TAG, "TEST: Model load took ${loadMs}ms")
            logMemory("After model load")

            val prompt =
                "Write a short, friendly text-message reply (1 sentence) to: \"$SAMPLE_MESSAGE\""
            var responseText = ""
            val genMs = measureTimeMillis {
                runBlocking {
                    val eng = engine ?: error("Engine null after load")
                    eng.createConversation().use { conversation ->
                        val response = conversation.sendMessage(prompt)
                        responseText = response.toString()
                    }
                }
            }
            Log.d(TAG, "TEST: Generated in ${genMs}ms: $responseText")
            logMemory("After generation")

            Log.d(TAG, "TEST: Holding foreground for ${HOLD_MS}ms (memory every ${MEMORY_INTERVAL_MS}ms)")
            mainHandler.post {
                memoryTick = 0
                mainHandler.postDelayed(memoryRunnable, MEMORY_INTERVAL_MS)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "TEST: Feasibility run failed: ${t.message}", t)
            stopSelfSafely()
        }
    }

    private fun resolveModelPath(overridePath: String?): String? {
        if (!overridePath.isNullOrBlank()) {
            val f = File(overridePath)
            if (f.isFile && f.canRead()) return f.absolutePath
            Log.e(TAG, "TEST: Override model_path not readable: $overridePath")
        }
        for (path in CANDIDATE_PATHS) {
            val f = File(path)
            if (f.isFile && f.canRead()) return f.absolutePath
        }
        val galleryRoot = File(
            "/storage/emulated/0/Android/data/com.google.ai.edge.gallery/files"
        )
        if (galleryRoot.isDirectory) {
            galleryRoot.walkTopDown()
                .maxDepth(6)
                .firstOrNull { file ->
                    file.isFile &&
                        file.name.endsWith(".litertlm", ignoreCase = true) &&
                        file.canRead() &&
                        file.length() > 1_000_000L
                }?.let { return it.absolutePath }
            Log.d(
                TAG,
                "TEST: Gallery dir exists but no readable .litertlm under ${galleryRoot.absolutePath}"
            )
        } else {
            Log.d(TAG, "TEST: Gallery files dir not accessible: ${galleryRoot.absolutePath}")
        }
        filesDir.listFiles()
            ?.firstOrNull {
                it.isFile && it.name.endsWith(".litertlm") && it.length() > 1_000_000L
            }
            ?.let { return it.absolutePath }
        return null
    }

    private fun logMemory(label: String) {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val sys = ActivityManager.MemoryInfo()
        am.getMemoryInfo(sys)
        val availMb = sys.availMem / (1024 * 1024)
        val totalMb = sys.totalMem / (1024 * 1024)
        val low = sys.lowMemory

        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        val pssMb = mi.totalPss / 1024f

        Log.d(
            TAG,
            "TEST: $label - availRAM=${availMb}MB totalRAM=${totalMb}MB lowMemory=$low " +
                "processPss=${"%.1f".format(pssMb)}MB pid=${Process.myPid()}"
        )
    }

    private fun stopSelfSafely() {
        mainHandler.post {
            mainHandler.removeCallbacks(memoryRunnable)
            stopSelf()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(memoryRunnable)
        try {
            engine?.close()
        } catch (_: Exception) {
        }
        engine = null
        Log.d(TAG, "TEST: TestLlmService destroyed")
        super.onDestroy()
    }
}
