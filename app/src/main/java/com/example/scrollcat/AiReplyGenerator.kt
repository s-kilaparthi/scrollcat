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
        val tone = SettingsManager.getReplyTone(context)
        val prompt = buildPrompt(sender, message, tone)
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

    private fun buildPrompt(sender: String, message: String, tone: String): String {
        val toneInstruction = when (tone) {
            "professional" -> "Keep the tone professional and polite, suitable for a business owner replying to a customer."
            "friendly" -> "Keep the tone warm, friendly and personal, with light emoji use."
            else -> "Keep the tone casual and natural, like texting a friend."
        }
        return """You suggest short chat replies. $sender sent this message:
"$message"

Write exactly 3 different short replies the user could send back. $toneInstruction
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
                mainHandler.post { onResult(suggestions, "Smart Reply") }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Smart Reply failed: ${e.message}")
                mainHandler.post { onResult(emptyList(), "Smart Reply") }
            }
    }

    fun close() {
        try { smartReplyClient.close() } catch (e: Exception) { }
    }
}
