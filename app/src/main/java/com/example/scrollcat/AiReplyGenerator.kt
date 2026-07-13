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
import java.util.concurrent.Executor

/**
 * Generates 3 reply suggestions for an incoming message, fully on-device.
 *
 * Primary engine: Gemini Nano via the ML Kit GenAI Prompt API (free, on-device).
 * Fallback engine: ML Kit Smart Reply for devices without AICore/Gemini Nano
 * support, so suggestions work on every phone.
 */
class AiReplyGenerator(private val context: Context) {

    companion object {
        private const val TAG = "ScrollCat"
        const val SUGGESTION_COUNT = 3
        private const val GENERATION_TIMEOUT_MS = 10_000L

        // Free tier: 10 AI replies per day, reset at midnight
        const val FREE_DAILY_LIMIT = 10
        const val ENGINE_LIMIT_REACHED = "limit_reached"
        const val UPGRADE_MESSAGE =
            "You've used your 10 free AI replies today. Upgrade to Creator for unlimited! ⭐"
        private const val USAGE_PREFS = "scrollcat_usage"

        /** Last-resort suggestions when every AI engine fails. */
        val HARDCODED_FALLBACK = listOf(
            "Sure!",
            "On my way!",
            "Let me check and get back to you"
        )

        /** Remaining free generations today (Int.MAX_VALUE for Pro users). */
        fun remainingFreeReplies(context: Context): Int {
            if (BillingManager.getInstance(context).isPro()) return Int.MAX_VALUE
            val prefs = context.getSharedPreferences(USAGE_PREFS, Context.MODE_PRIVATE)
            val today = todayKey()
            val count = if (prefs.getString("date", "") == today) prefs.getInt("count", 0) else 0
            return (FREE_DAILY_LIMIT - count).coerceAtLeast(0)
        }

        private fun todayKey(): String {
            val cal = java.util.Calendar.getInstance()
            return "%04d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }

        /**
         * Persona instruction built from the onboarding profile.
         * Shared with ClaudeReplyGenerator as its system prompt.
         */
        fun buildProfilePrompt(context: Context): String {
            val tone = SettingsManager.getReplyTone(context)
            val name = SettingsManager.getUserName(context)
            val niche = SettingsManager.getUserNiche(context)
            return when (SettingsManager.getUserType(context)) {
                "creator" -> {
                    val who = if (name.isNotBlank()) name else "the user"
                    val what = if (niche.isNotBlank()) "$niche content creator" else "content creator"
                    "Reply as $who, a $what. Tone: $tone. Authentic and engaging."
                }
                "business" -> {
                    val who = if (name.isNotBlank()) name else "the user's business"
                    val what = if (niche.isNotBlank()) "$niche business" else "business"
                    "Reply as $who, a $what. Professional and helpful."
                }
                else -> "Generate 3 natural short replies. Tone: $tone."
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
     * [onResult] is always invoked on the main thread. The list is empty when
     * both engines fail.
     */
    fun generateReplies(
        sender: String,
        message: String,
        onResult: (suggestions: List<String>, engine: String) -> Unit
    ) {
        // Free tier daily cap — Pro (Creator/Business) is unlimited
        if (!consumeDailyQuota()) {
            Log.i(TAG, "Free daily reply limit reached")
            mainHandler.post { onResult(emptyList(), ENGINE_LIMIT_REACHED) }
            return
        }

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

    /**
     * Counts one generation against today's free quota.
     * Returns false when the free limit is exhausted (and the user isn't Pro).
     */
    private fun consumeDailyQuota(): Boolean {
        if (BillingManager.getInstance(context).isPro()) return true
        val prefs = context.getSharedPreferences(USAGE_PREFS, Context.MODE_PRIVATE)
        val today = todayKey()
        var count = if (prefs.getString("date", "") == today) prefs.getInt("count", 0) else 0
        if (count >= FREE_DAILY_LIMIT) return false
        count++
        prefs.edit().putString("date", today).putInt("count", count).apply()
        return true
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
        return """You suggest short chat replies. ${buildProfilePrompt(context)}

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

    fun close() {
        try { smartReplyClient.close() } catch (e: Exception) { }
    }
}
