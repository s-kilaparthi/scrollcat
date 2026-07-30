package com.example.scrollcat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pro-tier reply generation via the Claude API (claude-haiku-4-5).
 * Used when the user has entered an API key in Settings; falls back to the
 * on-device AiReplyGenerator when the network call fails for any reason.
 */
class ClaudeReplyGenerator(private val context: Context) {

    companion object {
        private const val TAG = "ScrollCat"
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-haiku-4-5"
        private const val MAX_TOKENS = 150
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val onDeviceFallback = AiReplyGenerator(context)

    /**
     * Generates 3 suggestions. [onResult] is always invoked on the main thread.
     * Routes to Claude when an API key is set, otherwise straight to the
     * on-device generator.
     */
    fun generateReplies(
        sender: String,
        message: String,
        onResult: (suggestions: List<String>, engine: String) -> Unit
    ) {
        val apiKey = ApiKeyStore.getClaudeApiKey(context)
        if (apiKey.isBlank()) {
            onDeviceFallback.generateReplies(sender, message, onResult)
            return
        }

        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", MAX_TOKENS)
            put("system", buildSystemPrompt(message))
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put(
                    "content",
                    """$sender sent this message:
"$message"

Write exactly 3 different short replies the user could send back.
Each reply must be under 15 words. Output only the 3 replies, one per line, numbered 1. 2. 3."""
                )
            }))
        }

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Claude API call failed: ${e.message}")
                fallback(sender, message, onResult)
            }

            override fun onResponse(call: Call, response: Response) {
                val suggestions = try {
                    response.use { parseResponse(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "Claude response parse failed: ${e.message}")
                    emptyList()
                }
                if (suggestions.isNotEmpty()) {
                    mainHandler.post { onResult(suggestions, "Claude") }
                } else {
                    fallback(sender, message, onResult)
                }
            }
        })
    }

    private fun buildSystemPrompt(message: String): String {
        return UserProfileBuilder.buildSystemPrompt(context, message)
    }

    private fun parseResponse(response: Response): List<String> {
        val bodyString = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e(TAG, "Claude API HTTP ${response.code}: bodyChars=${bodyString.length}")
            return emptyList()
        }
        val content = JSONObject(bodyString).optJSONArray("content") ?: return emptyList()
        val text = buildString {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }
        return text.lines()
            .map { line ->
                line.trim()
                    .removePrefix("-").trim()
                    .replace(Regex("^\\d+[.)]\\s*"), "")
                    .trim('"', ' ')
            }
            .filter { it.isNotEmpty() && it.length <= 120 }
            .distinct()
            .take(AiReplyGenerator.SUGGESTION_COUNT)
    }

    private fun fallback(
        sender: String,
        message: String,
        onResult: (List<String>, String) -> Unit
    ) {
        mainHandler.post { onDeviceFallback.generateReplies(sender, message, onResult) }
    }

    fun close() {
        onDeviceFallback.close()
    }
}
