package com.example.scrollcat

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
 * AI chain (via ClaudeReplyGenerator entry point):
 * 1. Claude API (if user has key)
 * 2. Groq/Llama3 (if user has Groq key)
 * 3. Gemini Nano (on-device, if AICore available)
 * 4. ML Kit Smart Reply
 * 5. Hardcoded fallback
 */
class AiReplyGenerator(private val context: Context) {

    companion object {
        private const val TAG = "ScrollCat"
        const val SUGGESTION_COUNT = 3
        private const val GENERATION_TIMEOUT_MS = 10_000L

        private const val BUNDLED_KEY_ENCODED = "Z3NrX1lPVVJfQUNUVUFMX0dST1FfS0VZX0hFUkU="
        private const val BUNDLED_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        private const val BUNDLED_MODEL = "llama-3.1-8b-instant"
        private const val CLAUDE_ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val CLAUDE_MODEL = "claude-haiku-4-5"

        private fun getBundledKey(): String {
            return String(android.util.Base64.decode(BUNDLED_KEY_ENCODED, android.util.Base64.DEFAULT))
        }

        // Free tier: 10 AI replies per day, reset at midnight
        const val FREE_DAILY_LIMIT = 10
        const val ENGINE_LIMIT_REACHED = "limit_reached"
        const val UPGRADE_MESSAGE =
            "You've used your 10 free AI replies today. Upgrade to Creator for unlimited! ⭐"
        private const val USAGE_PREFS = "usage_prefs"

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

        /** Remaining free generations today (Int.MAX_VALUE for Pro users). */
        fun remainingFreeReplies(context: Context): Int {
            val limit = getDailyLimit(context)
            if (limit == Int.MAX_VALUE) return Int.MAX_VALUE
            return (limit - getDailyUsage(context)).coerceAtLeast(0)
        }

        fun getDailyLimit(context: Context): Int {
            val hasOwnKey = SettingsManager.getActiveAiKey(context).isNotEmpty()
            if (hasOwnKey) return Int.MAX_VALUE

            return when (BillingManager.getSubscriptionTier(context)) {
                "business" -> Int.MAX_VALUE
                "creator" -> 200
                else -> 10
            }
        }

        fun getDailyUsage(context: Context): Int {
            val prefs = context.getSharedPreferences(USAGE_PREFS, Context.MODE_PRIVATE)
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())
            val savedDate = prefs.getString("usage_date", "")

            return if (savedDate == today) {
                prefs.getInt("daily_usage", 0)
            } else {
                prefs.edit()
                    .putString("usage_date", today)
                    .putInt("daily_usage", 0)
                    .apply()
                0
            }
        }

        fun incrementDailyUsage(context: Context) {
            val prefs = context.getSharedPreferences(USAGE_PREFS, Context.MODE_PRIVATE)
            val current = getDailyUsage(context)
            prefs.edit().putInt("daily_usage", current + 1).apply()
        }

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
            return texts.joinToString(separator = "\n")
        }

        fun flushAllBuffers(context: Context) {
            val senders = ReplyStore.allBufferedSenders()
            Logger.d("flushAllBuffers triggered, ${senders.size} sender(s) to flush")
            for (senderKey in senders) {
                val messages = ReplyStore.getAndClearBuffer(senderKey)
                if (messages.isEmpty()) continue

                val mergedText = mergeMessageTexts(messages)
                Logger.d("Flushing $senderKey with ${messages.size} message(s): $mergedText")
                val parts = senderKey.split(":", limit = 2)
                val packageName = parts.getOrElse(0) { "" }
                val senderName = parts.getOrElse(1) { "" }

                generateReplies(context, packageName, senderName, mergedText) { replies ->
                    Logger.d("Groq replies stored for $senderKey: $replies")
                    ReplyStore.storeReplies(senderKey, replies)
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

        val apiKey = if (activeKey.isNotEmpty()) activeKey else getBundledKey()
        val endpoint = if (activeEndpoint.isNotEmpty()) activeEndpoint else BUNDLED_ENDPOINT
        val model = if (activeModel.isNotEmpty()) activeModel else BUNDLED_MODEL

        Logger.d("Using ${if (activeKey.isNotEmpty()) "user" else "bundled"} API key")

        val usage = getDailyUsage(context)
        val limit = getDailyLimit(context)

        if (usage >= limit) {
            Logger.d("Daily limit reached: $usage/$limit")
            mainHandler.post {
                OverlayService.instance?.showCatMessage(
                    if (limit == 10)
                        "You've used your 10 free AI replies today. Upgrade to Pro for 200 replies!"
                    else
                        "Daily limit reached. Upgrade to Business for unlimited replies!"
                )
                onResult(
                    listOf("Upgrade to Pro for more AI replies", "Sure!", "Let me check"),
                    ENGINE_LIMIT_REACHED
                )
            }
            return
        }

        incrementDailyUsage(context)

        val userMessage = """Message to reply to:
"$message"

Generate 3 short reply options."""
        val providerName = providerNameFor(endpoint)
        generateWithGroq(userMessage, endpoint, model, apiKey) { replies, error ->
            if (replies.isNotEmpty()) {
                onResult(replies.take(SUGGESTION_COUNT), "AI Provider")
            } else {
                if (providerName == "Groq") {
                    Log.e(TAG, "Groq failed, falling back: ${error?.message ?: "empty response"}")
                    fallbackAfterGroqFailure(userMessage, sender, message, onResult)
                } else {
                    Log.e(TAG, "$providerName failed, falling back: ${error?.message ?: "empty response"}")
                    generateWithSmartReply(sender, message, onResult)
                }
            }
        }
    }

    private fun providerNameFor(endpoint: String): String {
        return when {
            endpoint.contains("groq.com", ignoreCase = true) -> "Groq"
            endpoint.contains("anthropic.com", ignoreCase = true) -> "Claude"
            else -> "AI provider"
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
        return """${UserProfileBuilder.buildSystemPrompt(context, message.length)}

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
    }

    private fun parseReplies(content: String): List<String> {
        val cleaned = content
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            val arr = JSONArray(cleaned)
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
            replies.filter { it.isNotEmpty() && it.length > 2 }.take(3)
        } catch (e: Exception) {
            cleaned.split("\n")
                .map { it.trim()
                    .removePrefix("-")
                    .removePrefix("•")
                    .removePrefix("1.").removePrefix("2.").removePrefix("3.")
                    .removeSurrounding("\"")
                    .trim()
                }
                .filter { it.isNotEmpty() && it.length > 3 && !it.startsWith("{") }
                .take(3)
        }
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

        Log.d(TAG, "Using API key: ${if (apiKey.isEmpty()) "EMPTY - will fallback" else "SET (${apiKey.take(8)}...)"}")

        if (apiKey.isEmpty()) {
            Log.d(TAG, "No API key found - falling back")
            mainHandler.post { callback(emptyList(), IllegalStateException("No API key found")) }
            return
        }

        val systemPrompt = UserProfileBuilder.buildSystemPrompt(context, message.length)
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

                Logger.d("AI provider response: $responseBody")

                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}: ${responseBody.take(200)}")
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

                if (replies.isEmpty()) {
                    throw Exception("No replies parsed from provider response")
                }

                Logger.d("Replies from AI provider: $replies")

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
                        onResult(suggestions, "Smart Reply")
                    } else {
                        onResult(HARDCODED_FALLBACK, "Fallback")
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Smart Reply failed: ${e.message}")
                mainHandler.post { onResult(HARDCODED_FALLBACK, "Fallback") }
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
                append(
                    when {
                        hasClaudeKey -> "→ Claude API (premium)"
                        hasActiveProvider -> "→ ${SettingsManager.getActiveAiModel(context)} (configured)"
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
