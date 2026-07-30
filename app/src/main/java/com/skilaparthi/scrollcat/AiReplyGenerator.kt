package com.skilaparthi.scrollcat

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures
import com.google.mlkit.nl.smartreply.SmartReply
import com.google.mlkit.nl.smartreply.SmartReplySuggestionResult
import com.google.mlkit.nl.smartreply.TextMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Generates 3 reply suggestions for an incoming message.
 *
 * AI chain:
 * 1. On-device LiteRT-LM (if OnDeviceAiEngine.isReady())
 * 2. Groq/Llama3 (user key only — skipped when no key is configured)
 * 3. Claude API (if user has key, after Groq failure)
 * 4. ML Kit Smart Reply
 * 5. Hardcoded fallback
 */
class AiReplyGenerator(private val context: Context) {

    companion object {
        private const val TAG = "ScrollCat"
        const val SUGGESTION_COUNT = 3
        private const val GENERATION_TIMEOUT_MS = 10_000L

        private const val BUNDLED_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        private const val BUNDLED_MODEL = "llama-3.1-8b-instant"
        private const val CLAUDE_ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val CLAUDE_MODEL = "claude-haiku-4-5"
        private const val PROVIDER_MESSAGE_CHAR_LIMIT = 800
        private const val MERGED_MESSAGE_CHAR_LIMIT = 1500

        private val httpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        /** Last-resort suggestions when every AI engine fails. */
        val HARDCODED_FALLBACK = listOf(
            "Sure!",
            "On my way!",
            "Let me check and get back to you"
        )

        private val singleWordReplies = mapOf(
            "hi" to listOf("Hey!", "Hi there!", "Hello!"),
            "hii" to listOf("Hey!", "Hi there!", "Hello!"),
            "hiii" to listOf("Hey!", "Hi there!", "Hello!"),
            "hello" to listOf("Hey there!", "Hello!", "Hi!"),
            "hey" to listOf("Hey!", "What's up!", "Hi there!"),
            "ok" to listOf("Sounds good!", "Got it!", "Sure thing!"),
            "okay" to listOf("Sounds good!", "Got it!", "Sure!"),
            "sure" to listOf("Of course!", "Absolutely!", "Sure thing!"),
            "done" to listOf("Great!", "Awesome!", "Perfect!"),
            "bye" to listOf("Bye!", "Take care!", "See you soon!"),
            "goodbye" to listOf("Bye!", "Take care!", "See you!"),
            "thanks" to listOf("You're welcome!", "Anytime!", "Happy to help!"),
            "thank" to listOf("You're welcome!", "Anytime!", "No problem!"),
            "thankyou" to listOf("You're welcome!", "Anytime!", "Happy to help!"),
            "yes" to listOf("Yes!", "Absolutely!", "Of course!"),
            "no" to listOf("No worries!", "Maybe next time!", "That's okay!"),
            "wow" to listOf("Right?!", "I know!", "Haha yes!"),
            "lol" to listOf("Haha!", "Right?!", "Too funny!"),
            "nice" to listOf("Thank you!", "Glad you think so!", "Appreciate it!"),
            "cool" to listOf("Thanks!", "Glad you like it!", "Appreciate it!"),
            "good" to listOf("Thank you!", "Glad to hear!", "That's great!"),
            "great" to listOf("Thank you!", "Appreciate it!", "Glad you think so!"),
            "k" to listOf("Got it!", "Sounds good!", "Sure!"),
            "np" to listOf("Anytime!", "Of course!", "No problem!"),
            "gm" to listOf("Good morning!", "Morning!", "Good morning to you!"),
            "gn" to listOf("Good night!", "Sleep well!", "Night!"),
            "morning" to listOf("Good morning!", "Morning!", "Hey, good morning!"),
            "night" to listOf("Good night!", "Sleep well!", "Night!")
        )

        fun generateReplies(
            context: Context,
            packageName: String,
            senderName: String,
            message: String,
            onResult: (List<String>) -> Unit
        ) {
            AiReplyGenerator(context).generateReplies(senderName, message) { replies, _ ->
                onResult(replies)
            }
        }

        fun mergeMessageTexts(
            messages: List<ReplyStore.BufferedMessage>,
            latestText: String? = null
        ): String {
            val texts = messages.map { it.text } + listOfNotNull(latestText)
            val merged = texts.joinToString(separator = "\n")
            return if (merged.length > MERGED_MESSAGE_CHAR_LIMIT) {
                merged.take(MERGED_MESSAGE_CHAR_LIMIT) + "... (earlier messages truncated)"
            } else {
                merged
            }
        }

        fun flushAllBuffers(context: Context) {
            val senders = ReplyStore.allBufferedSenders()
            Logger.d("flushAllBuffers triggered, ${senders.size} sender(s) to flush")
            for (senderKey in senders) {
                val messages = ReplyStore.getAndClearBuffer(senderKey)
                if (messages.isEmpty()) continue

                val mergedText = mergeMessageTexts(messages)
                Logger.d("Flushing $senderKey with ${messages.size} message(s)")
                val parts = senderKey.split(":", limit = 2)
                val packageName = parts.getOrElse(0) { "" }
                val senderName = parts.getOrElse(1) { "" }

                generateReplies(context, packageName, senderName, mergedText) { replies ->
                    Logger.d("Groq replies stored for $senderKey: count=${replies.size}")
                    val targets = ReplyStore.getAll().filter { msg ->
                        msg.packageName == packageName && (
                            if (packageName == "com.whatsapp") {
                                msg.sender.equals(senderName, ignoreCase = true)
                            } else {
                                msg.notificationId.toString() == senderName
                            }
                        )
                    }
                    targets.map { it.conversationKey }.distinct().forEach { conversationKey ->
                        ReplyStore.storeRepliesForConversation(conversationKey, replies)
                    }
                }
            }
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }

    private val generativeModel by lazy { GenerativeModelFutures.from(Generation.getClient()) }
    private val smartReplyClient by lazy { SmartReply.getClient() }

    /** null until first checked; cached so we only probe AICore once per session */
    private var geminiNanoAvailable: Boolean? = null

    /**
     * Generates up to [SUGGESTION_COUNT] suggestions.
     * [onResult] is always invoked on the main thread. Hardcoded suggestions
     * are returned when every dynamic engine fails.
     */
    fun generateReplies(
        sender: String,
        message: String,
        onResult: (suggestions: List<String>, engine: String) -> Unit
    ) {
        Log.d(TAG, "=== generateReplies called ===")
        Log.d(TAG, "Active endpoint: ${SettingsManager.getActiveAiEndpoint(context)}")
        Log.d(TAG, "Active model: ${SettingsManager.getActiveAiModel(context)}")
        Log.d(TAG, "Active key empty: ${SettingsManager.getActiveAiKey(context).isEmpty()}")

        val normalizedMessage = message.trim().lowercase()
            .removeSuffix("!")
            .removeSuffix(".")
            .removeSuffix("?")
        singleWordReplies[normalizedMessage]?.let { quickReplies ->
            mainHandler.post { onResult(quickReplies, "Quick Reply") }
            return
        }

        val activeKey = SettingsManager.getActiveAiKey(context)
        val activeEndpoint = SettingsManager.getActiveAiEndpoint(context)
        val activeModel = SettingsManager.getActiveAiModel(context)

        // No bundled key: an unconfigured provider must fail straight through to the
        // existing "no API key" handling rather than issue a doomed request.
        val apiKey = activeKey
        val endpoint = if (activeEndpoint.isNotEmpty()) activeEndpoint else BUNDLED_ENDPOINT
        val model = if (activeModel.isNotEmpty()) activeModel else BUNDLED_MODEL

        Logger.d("Active API key configured: ${activeKey.isNotEmpty()}")

        val providerMessage = truncateForProviderPrompt(message)
        val userMessage = """Message to reply to:
"$providerMessage"

Generate 3 short reply options."""
        val providerName = providerNameFor(endpoint)
        val primary = SettingsManager.getPrimaryAiProvider(context)
        val primaryIsOnDevice = primary == SettingsManager.PRIMARY_AI_ON_DEVICE &&
            ModelDownloadManager.modelFileExists(context)

        fun tryLightweightFallback() {
            Log.d(TAG, "Falling through to Smart Reply / hardcoded (no on-device re-enable)")
            generateWithSmartReply(sender, message, onResult)
        }

        fun tryCloudProviders() {
            generateWithGroq(userMessage, endpoint, model, apiKey) { replies, error ->
                if (replies.isNotEmpty()) {
                    val via = when (providerName) {
                        "Groq" -> "groq"
                        "Claude" -> "claude"
                        else -> providerName.lowercase()
                    }
                    Log.d(TAG, "Reply generated via: $via")
                    onResult(replies.take(SUGGESTION_COUNT), "AI Provider")
                } else {
                    if (primaryIsOnDevice && providerName == "Groq") {
                        // On-device was primary and failed earlier; cloud also failed —
                        // Claude secondary then Smart Reply (legacy when primary is on-device).
                        Log.e(TAG, "Groq failed after on-device: ${error?.message ?: "empty response"}")
                        fallbackAfterGroqFailure(userMessage, sender, message, onResult)
                    } else {
                        // Explicit cloud primary (or non-Groq): never re-enable on-device.
                        Log.e(
                            TAG,
                            "$providerName failed, lightweight fallback: ${error?.message ?: "empty response"}"
                        )
                        tryLightweightFallback()
                    }
                }
            }
        }

        fun tryOnDeviceThenCloud() {
            if (DeviceCapabilityChecker.shouldSkipOnDeviceForDoze(context)) {
                Log.d(TAG, "Doze — skipping on-device primary, using cloud")
                tryCloudProviders()
                return
            }
            if (OnDeviceAiEngine.isReady()) {
                Thread {
                    try {
                        val systemPrompt =
                            UserProfileBuilder.buildSystemPrompt(context, userMessage)
                        val languageLock =
                            UserProfileBuilder.languageMatchInstruction(context, userMessage)
                                .trimEnd()
                        val raw = OnDeviceAiEngine.generateReply(
                            systemPrompt = "$languageLock\n\n$systemPrompt\n\n$languageLock",
                            userMessage = userMessage
                        )
                        val replies = if (raw != null) parseReplies(raw) else emptyList()
                        if (replies.isNotEmpty()) {
                            val fullReplies = replies.take(SUGGESTION_COUNT).toList()
                            mainHandler.post {
                                Log.d(TAG, "Reply generated via: on-device")
                                onResult(fullReplies, "On-Device")
                            }
                        } else {
                            Log.d(TAG, "On-device AI returned null/empty, falling through to cloud")
                            tryCloudProviders()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "On-device AI failed, falling through to cloud: ${e.message}")
                        tryCloudProviders()
                    }
                }.start()
            } else {
                Thread {
                    try {
                        OnDeviceAiEngine.ensureInitialized(context)
                        if (OnDeviceAiEngine.isReady()) {
                            val systemPrompt =
                                UserProfileBuilder.buildSystemPrompt(context, userMessage)
                            val languageLock =
                                UserProfileBuilder.languageMatchInstruction(context, userMessage)
                                    .trimEnd()
                            val raw = OnDeviceAiEngine.generateReply(
                                systemPrompt = "$languageLock\n\n$systemPrompt\n\n$languageLock",
                                userMessage = userMessage
                            )
                            val replies = if (raw != null) parseReplies(raw) else emptyList()
                            if (replies.isNotEmpty()) {
                                val fullReplies = replies.take(SUGGESTION_COUNT).toList()
                                mainHandler.post {
                                    Log.d(TAG, "Reply generated via: on-device (after warmup)")
                                    onResult(fullReplies, "On-Device")
                                }
                                return@Thread
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "On-device warmup/generate failed: ${e.message}")
                    }
                    tryCloudProviders()
                }.start()
            }
        }

        if (primaryIsOnDevice) {
            tryOnDeviceThenCloud()
        } else {
            // Cloud (or nothing) is primary — never auto-start on-device.
            Log.d(TAG, "Primary AI provider=$primary — cloud path only")
            tryCloudProviders()
        }
    }

    private fun providerNameFor(endpoint: String): String {
        return when {
            endpoint.contains("groq.com", ignoreCase = true) -> "Groq"
            endpoint.contains("anthropic.com", ignoreCase = true) -> "Claude"
            else -> "AI provider"
        }
    }

    private fun truncateForProviderPrompt(message: String): String {
        return if (message.length > PROVIDER_MESSAGE_CHAR_LIMIT) {
            message.take(PROVIDER_MESSAGE_CHAR_LIMIT) + "... (message truncated)"
        } else {
            message
        }
    }

    private fun fallbackAfterGroqFailure(
        userMessage: String,
        sender: String,
        message: String,
        onResult: (suggestions: List<String>, engine: String) -> Unit
    ) {
        val claudeKey = ApiKeyStore.getClaudeApiKey(context)
        if (claudeKey.isNotBlank()) {
            Log.d(TAG, "Using Claude fallback after Groq failure")
            generateWithGroq(userMessage, CLAUDE_ENDPOINT, CLAUDE_MODEL, claudeKey) { replies, error ->
                if (replies.isNotEmpty()) {
                    Log.d(TAG, "Reply generated via: claude")
                    onResult(replies.take(SUGGESTION_COUNT), "Claude")
                } else {
                    Log.e(TAG, "Claude fallback failed, using Smart Reply: ${error?.message ?: "empty response"}")
                    generateWithSmartReply(sender, message, onResult)
                }
            }
        } else {
            Log.d(TAG, "Using Smart Reply fallback after Groq failure")
            generateWithSmartReply(sender, message, onResult)
        }
    }

    private fun generateWithOnDeviceChain(
        sender: String,
        message: String,
        onResult: (suggestions: List<String>, engine: String) -> Unit
    ) {
        val cached = geminiNanoAvailable
        if (cached == false) {
            generateWithSmartReply(sender, message, onResult)
            return
        }

        val statusFuture = generativeModel.checkStatus()
        statusFuture.addListener({
            val status = try {
                statusFuture.get()
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Nano status check failed: ${e.message}")
                FeatureStatus.UNAVAILABLE
            }
            Log.d(TAG, "Gemini Nano status: $status")
            when (status) {
                FeatureStatus.AVAILABLE -> {
                    geminiNanoAvailable = true
                    generateWithGeminiNano(sender, message, onResult)
                }
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                    // Kick off (or continue) the model download for next time,
                    // but answer this request with Smart Reply immediately.
                    startModelDownload()
                    generateWithSmartReply(sender, message, onResult)
                }
                else -> {
                    geminiNanoAvailable = false
                    generateWithSmartReply(sender, message, onResult)
                }
            }
        }, mainExecutor)
    }

    private fun startModelDownload() {
        try {
            generativeModel.download(object : DownloadCallback {
                override fun onDownloadStarted(bytesToDownload: Long) {
                    Log.i(TAG, "Gemini Nano download started: $bytesToDownload bytes")
                }
                override fun onDownloadProgress(totalBytesDownloaded: Long) {}
                override fun onDownloadCompleted() {
                    Log.i(TAG, "Gemini Nano download completed")
                    geminiNanoAvailable = true
                }
                override fun onDownloadFailed(e: GenAiException) {
                    Log.w(TAG, "Gemini Nano download failed: ${e.message}")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Gemini Nano download error: ${e.message}")
        }
    }

    private fun generateWithGeminiNano(
        sender: String,
        message: String,
        onResult: (List<String>, String) -> Unit
    ) {
        val prompt = buildPrompt(sender, message)
        val request = GenerateContentRequest.Builder(TextPart(prompt)).apply {
            temperature = 0.7f
            maxOutputTokens = 120
        }.build()

        var finished = false
        fun finish(suggestions: List<String>, engine: String) {
            if (finished) return
            finished = true
            onResult(suggestions, engine)
        }

        // Nano can be slow on first inference; don't leave the user hanging
        mainHandler.postDelayed({
            if (!finished) {
                Log.w(TAG, "Gemini Nano timed out, falling back to Smart Reply")
                generateWithSmartReply(sender, message) { s, e -> finish(s, e) }
            }
        }, GENERATION_TIMEOUT_MS)

        val future = generativeModel.generateContent(request)
        future.addListener({
            val suggestions = try {
                val text = future.get().candidates.firstOrNull()?.text.orEmpty()
                parseSuggestions(text)
            } catch (e: Exception) {
                Log.e(TAG, "Gemini Nano generation failed: ${e.message}")
                emptyList()
            }
            if (suggestions.isNotEmpty()) {
                finish(suggestions, "Gemini Nano")
            } else {
                generateWithSmartReply(sender, message) { s, e -> finish(s, e) }
            }
        }, mainExecutor)
    }

    private fun buildPrompt(sender: String, message: String): String {
        return """${UserProfileBuilder.buildSystemPrompt(context, message)}

$sender sent this message:
"$message"

Write exactly 3 different short replies the user could send back.
Each reply must be under 15 words. Output only the 3 replies, one per line, numbered 1. 2. 3."""
    }

    private fun parseSuggestions(raw: String): List<String> {
        return raw.lines()
            .map { line ->
                line.trim()
                    .removePrefix("-").trim()
                    .replace(Regex("^\\d+[.)]\\s*"), "")
                    .trim('"', ' ')
            }
            .filter { it.isNotEmpty() && it.length <= 120 }
            .distinct()
            .take(SUGGESTION_COUNT)
            .let { sanitizeParsedReplies(it) }
    }

    private fun parseReplies(content: String): List<String> {
        val cleanedResponse = stripMarkdownCodeFences(content)
        android.util.Log.d(
            "ScrollCat",
            "Cleaned on-device response before parsing: chars=${cleanedResponse.length}"
        )

        val jsonCandidate = extractJsonArrayCandidate(cleanedResponse)
        if (jsonCandidate == null) {
            Log.d(
                TAG,
                "parseReplies: no JSON array after fence strip — treating as failed generation"
            )
            return emptyList()
        }

        return try {
            val arr = JSONArray(jsonCandidate)
            val replies = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.get(i)
                when (item) {
                    is String -> {
                        if (item.isNotBlank() &&
                            !item.startsWith("{") &&
                            item.length > 2
                        ) {
                            replies.add(item.trim())
                        }
                    }
                    is JSONObject -> {
                        val text = when {
                            item.has("message") -> item.getString("message")
                            item.has("text") -> item.getString("text")
                            item.has("reply") -> item.getString("reply")
                            item.has("content") -> item.getString("content")
                            else -> item.toString()
                        }
                        if (text.isNotBlank()) replies.add(text.trim())
                    }
                }
            }
            val parsed = replies.filter { it.isNotEmpty() && it.length > 2 }.take(3)
            if (parsed.isEmpty()) {
                Log.d(TAG, "parseReplies: JSON array had no usable reply strings")
                emptyList()
            } else {
                sanitizeParsedReplies(parsed)
            }
        } catch (e: Exception) {
            Log.d(
                TAG,
                "parseReplies: invalid JSON after fence strip (${e.message}) — " +
                    "failing generation for Groq fallback"
            )
            emptyList()
        }
    }

    /**
     * Removes leading/trailing markdown fences such as ```json / ```python / ```.
     */
    private fun stripMarkdownCodeFences(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return text

        // Opening fence on first line: ``` or ```json / ```python / etc.
        val openFence = Regex(
            "^```[a-zA-Z0-9_+.-]*\\s*\\r?\\n?",
            setOf(RegexOption.IGNORE_CASE)
        )
        text = text.replaceFirst(openFence, "")

        // Closing fence at end (optional preceding newline)
        val closeFence = Regex("\\r?\\n?```[ \\t]*$")
        text = text.replace(closeFence, "")

        return text.trim()
    }

    /**
     * Returns a string that looks like a JSON array, or null if none can be found.
     * Does not invent replies from free-form / Python garbage.
     */
    private fun extractJsonArrayCandidate(cleaned: String): String? {
        val trimmed = cleaned.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("[")) {
            return trimmed
        }
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val slice = trimmed.substring(start, end + 1).trim()
        // Reject slices that clearly aren't JSON arrays of strings/objects
        // (e.g. Python list-like garbage without quotes is still attempted by JSONArray
        // and will fail in the caller — that's fine).
        return slice.ifEmpty { null }
    }

    private val danglingEndWords = setOf(
        "to", "a", "an", "the", "is", "of", "for", "and", "or", "but",
        "with", "at", "in", "on", "my", "be", "am", "are", "was", "were",
        "will", "would", "could", "should", "going", "from", "by", "as"
    )

    /** Strong fragment endings (cut off mid-thought) regardless of length. */
    private val hardFragmentEndings = setOf(
        "to", "a", "an", "the", "of", "for", "and", "or", "with", "by", "as", "from", "going"
    )

    private val fallbackReplies = listOf(
        "Sounds good!",
        "Got it!",
        "Sure thing!"
    )

    /** Drop incomplete sentence fragments; pad with generic acknowledgments up to 3. */
    private fun sanitizeParsedReplies(replies: List<String>): List<String> {
        val valid = mutableListOf<String>()
        for (reply in replies) {
            if (isIncompleteFragment(reply)) {
                android.util.Log.d("ScrollCat", "Filtered malformed reply: chars=${reply.length}")
            } else {
                valid.add(reply)
            }
        }
        // Never pad non-Latin (e.g. Telugu) results with English fallbacks — that looked
        // like "only chip 0 updated, chips 1–2 still stale English from before".
        val sourceHasNonLatin = replies.any { reply -> reply.any { it.code > 0x7F } }
        if (sourceHasNonLatin) {
            if (valid.isEmpty()) {
                // Keep originals rather than inventing English chips
                return replies.filter { it.isNotBlank() }.take(SUGGESTION_COUNT)
            }
            return valid.take(SUGGESTION_COUNT)
        }
        var fallbackIndex = 0
        while (valid.size < SUGGESTION_COUNT && fallbackIndex < fallbackReplies.size) {
            val fallback = fallbackReplies[fallbackIndex++]
            if (fallback !in valid) valid.add(fallback)
        }
        return valid.take(SUGGESTION_COUNT)
    }

    private fun isIncompleteFragment(reply: String): Boolean {
        val trimmed = reply.trim()
        if (trimmed.isEmpty()) return true

        // Non-Latin scripts (Telugu, Hindi, etc.): don't apply English fragment heuristics
        if (trimmed.any { it.code > 0x7F }) {
            return trimmed.length < 2
        }

        val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true

        val lastWord = words.last()
            .lowercase()
            .trimEnd('.', '!', '?', ',', ';', ':', '"', '\'', '…', '।', '॥')

        // e.g. "to", "a plan" — too short and ends on a function word
        if (words.size < 3 && lastWord in danglingEndWords) return true
        // e.g. "plan is to", "going to be" (last token is a hard fragment ending)
        if (lastWord in hardFragmentEndings) return true
        if (words.size <= 4 && lastWord in setOf("be", "is")) return true

        val lastChar = trimmed.last()
        val endsLikeSentence = lastChar.isLetterOrDigit() ||
            lastChar.isLetter() ||
            lastChar in ".!?…।॥"
        return !endsLikeSentence
    }

    private fun getFallbackReplies(): List<String> = HARDCODED_FALLBACK

    private fun generateWithGroq(
        message: String,
        endpoint: String,
        model: String,
        apiKey: String,
        callback: (List<String>, Exception?) -> Unit
    ) {
        Logger.d("Final endpoint: $endpoint")
        Logger.d("Final model: $model")

        Log.d(TAG, "Using API key: ${if (apiKey.isEmpty()) "EMPTY - will fallback" else "SET"}")

        if (apiKey.isEmpty()) {
            Log.d(TAG, "No API key found - falling back")
            mainHandler.post { callback(emptyList(), IllegalStateException("No API key found")) }
            return
        }

        val systemPrompt = UserProfileBuilder.buildSystemPrompt(context, message)
        android.util.Log.d(
            "ScrollCat",
            "System prompt tokens ~${systemPrompt.length / 4}, message tokens ~${message.length / 4}"
        )

        val isClaudeApi = endpoint.contains("anthropic.com")

        Thread {
            try {
                val requestBody = if (isClaudeApi) {
                    JSONObject().apply {
                        put("model", model)
                        put("max_tokens", 150)
                        put("temperature", 0.4)
                        put("system", systemPrompt)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", message)
                            })
                        })
                    }
                } else {
                    JSONObject().apply {
                        put("model", model)
                        put("max_tokens", 150)
                        put("temperature", 0.4)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "system")
                                put("content", systemPrompt)
                            })
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", message)
                            })
                        })
                    }
                }

                val requestBuilder = Request.Builder()
                    .url(endpoint)
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))

                if (isClaudeApi) {
                    requestBuilder
                        .addHeader("x-api-key", apiKey)
                        .addHeader("anthropic-version", "2023-06-01")
                } else {
                    requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                }

                val response = httpClient.newCall(requestBuilder.build()).execute()
                val responseBody = response.body?.string().orEmpty()
                val httpOk = response.isSuccessful

                if (!httpOk) {
                    Logger.d("AI provider response: success=false http=${response.code} bodyChars=${responseBody.length}")
                    throw Exception("HTTP ${response.code}")
                }

                val json = JSONObject(responseBody)
                val content = if (isClaudeApi) {
                    val blocks = json.optJSONArray("content") ?: JSONArray()
                    buildString {
                        for (i in 0 until blocks.length()) {
                            val block = blocks.optJSONObject(i) ?: continue
                            if (block.optString("type") == "text") {
                                append(block.optString("text"))
                            }
                        }
                    }
                } else {
                    json
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                }

                val replies = parseReplies(content)

                Logger.d(
                    "AI provider response: success=true http=${response.code} replyCount=${replies.size}"
                )

                if (replies.isEmpty()) {
                    throw Exception("No replies parsed from provider response")
                }

                mainHandler.post {
                    callback(replies.filter { it.isNotBlank() }, null)
                }
            } catch (e: Exception) {
                Logger.e("AI provider error: ${e.message}")
                mainHandler.post {
                    callback(emptyList(), e)
                }
            }
        }.start()
    }

    private fun generateWithSmartReply(
        sender: String,
        message: String,
        onResult: (List<String>, String) -> Unit
    ) {
        val conversation = listOf(
            TextMessage.createForRemoteUser(message, System.currentTimeMillis(), sender)
        )
        smartReplyClient.suggestReplies(conversation)
            .addOnSuccessListener { result ->
                val suggestions =
                    if (result.status == SmartReplySuggestionResult.STATUS_SUCCESS) {
                        result.suggestions.map { it.text }.take(SUGGESTION_COUNT)
                    } else {
                        Log.w(TAG, "Smart Reply status: ${result.status}")
                        emptyList()
                    }
                mainHandler.post {
                    if (suggestions.isNotEmpty()) {
                        Log.d(TAG, "Reply generated via: mlkit")
                        onResult(suggestions, "Smart Reply")
                    } else {
                        Log.d(TAG, "Reply generated via: hardcoded")
                        onResult(HARDCODED_FALLBACK, "Fallback")
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Smart Reply failed: ${e.message}")
                mainHandler.post {
                    Log.d(TAG, "Reply generated via: hardcoded")
                    onResult(HARDCODED_FALLBACK, "Fallback")
                }
            }
    }

    fun checkAiStatus(callback: (String) -> Unit) {
        val hasClaudeKey = ApiKeyStore.getClaudeApiKey(context).isNotEmpty()
        val hasActiveProvider = SettingsManager.getActiveAiKey(context).isNotEmpty()
        val hasGroqKey = ApiKeyStore.getGroqApiKey(context)?.isNotEmpty() == true

        val nanoHelper = GeminiNanoHelper(context)
        nanoHelper.checkStatus { nanoStatus ->
            val status = buildString {
                appendLine("🤖 ScrollCat AI Status")
                appendLine("─────────────────────")
                appendLine(
                    if (hasClaudeKey) "✅ Claude API: Connected"
                    else "⬜ Claude API: Not configured"
                )
                appendLine(
                    if (hasGroqKey || hasActiveProvider) "✅ Groq API: Connected (free)"
                    else "⬜ Groq API: Not configured"
                )
                appendLine()
                appendLine("📱 On-Device AI:")
                appendLine(nanoStatus)
                appendLine()
                appendLine("📡 Active engine:")
                val primary = SettingsManager.getPrimaryAiProvider(context)
                append(
                    when {
                        primary == SettingsManager.PRIMARY_AI_ON_DEVICE ->
                            "→ On-Device AI (Gemma 4)"
                        primary == SettingsManager.PRIMARY_AI_GROQ -> "→ Groq"
                        primary == SettingsManager.PRIMARY_AI_CLAUDE -> "→ Claude"
                        primary == SettingsManager.PRIMARY_AI_OPENAI -> "→ OpenAI"
                        primary == SettingsManager.PRIMARY_AI_CUSTOM ->
                            "→ ${SettingsManager.getActiveAiModel(context)} (custom)"
                        hasClaudeKey -> "→ Claude API (premium)"
                        hasActiveProvider ->
                            "→ ${SettingsManager.getActiveAiModel(context)} (configured)"
                        hasGroqKey -> "→ Groq/Llama3 (free)"
                        else -> "→ Fallback replies"
                    }
                )
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback(status)
            }
        }
    }

    fun close() {
        try { smartReplyClient.close() } catch (e: Exception) { }
    }
}
