package com.skilaparthi.scrollcat

import android.content.Context
import android.util.Log

class GeminiNanoHelper(private val context: Context) {

    companion object {
        private const val TAG = "ScrollCat"
    }

    fun isAvailable(): Boolean {
        return try {
            // Check if Google Play Services AI is available
            val clazz = Class.forName("com.google.android.gms.ai.AppIntelligenceClient")
            clazz != null
        } catch (e: Exception) {
            Log.d(TAG, "Google Play Services AI not available: ${e.message}")
            false
        }
    }

    fun generateReplies(
        message: String,
        systemPrompt: String,
        callback: (List<String>?) -> Unit
    ) {
        try {
            // Try new Google Play Services AI API
            val clientClass = Class.forName(
                "com.google.android.gms.ai.AppIntelligenceClient"
            )
            val getInstanceMethod = clientClass.getMethod(
                "getInstance",
                Context::class.java
            )
            val client = getInstanceMethod.invoke(null, context)

            val promptClass = Class.forName(
                "com.google.android.gms.ai.GenerativeModelFutures"
            )

            Log.d(TAG, "Google Play Services AI client created successfully")
            callback(null) // Will implement fully once we confirm API works

        } catch (e: Exception) {
            Log.e(TAG, "Google Play Services AI failed: ${e.message}")
            callback(null)
        }
    }

    fun checkStatus(callback: (String) -> Unit) {
        // Check AICore version and capabilities
        try {
            val pm = context.packageManager

            // Check Google AI Core
            val googleAiCore = try {
                val info = pm.getPackageInfo("com.google.android.aicore", 0)
                "✅ Google AI Core: ${info.versionName}"
            } catch (e: Exception) {
                "❌ Google AI Core: Not installed"
            }

            // Check Samsung AI Core
            val samsungAiCore = try {
                val info = pm.getPackageInfo("com.samsung.android.aicore", 0)
                "✅ Samsung AI Core: ${info.versionName}"
            } catch (e: Exception) {
                "❌ Samsung AI Core: Not installed"
            }

            // Check Google Play Services AI
            val gmsAi = try {
                Class.forName("com.google.android.gms.ai.AppIntelligenceClient")
                "✅ Google Play Services AI: Available"
            } catch (e: Exception) {
                "❌ Google Play Services AI: Not available (${e.message})"
            }

            callback("$googleAiCore\n$samsungAiCore\n$gmsAi")

        } catch (e: Exception) {
            callback("Error checking AI status: ${e.message}")
        }
    }
}
