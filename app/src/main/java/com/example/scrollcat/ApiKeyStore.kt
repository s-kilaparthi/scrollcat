package com.example.scrollcat

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the Claude API key in EncryptedSharedPreferences so it never sits
 * on disk in plain text.
 */
object ApiKeyStore {

    private const val TAG = "ScrollCat"
    private const val PREFS_NAME = "scrollcat_secure"
    private const val KEY_CLAUDE = "claude_api_key"
    private const val KEY_GROQ = "groq_api_key"

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val created = try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // Keystore can be corrupted on some devices; degrade gracefully
                // rather than crash — the key just won't be encrypted at rest.
                Log.e(TAG, "EncryptedSharedPreferences unavailable: ${e.message}")
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME + "_fallback", Context.MODE_PRIVATE)
            }
            prefs = created
            return created
        }
    }

    fun getClaudeApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_CLAUDE, "") ?: ""
    }

    fun setClaudeApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_CLAUDE, key.trim()).apply()
    }

    fun hasClaudeApiKey(context: Context): Boolean = getClaudeApiKey(context).isNotBlank()

    fun getGroqApiKey(context: Context): String? {
        return try {
            getPrefs(context).getString(KEY_GROQ, null)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    fun setGroqApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_GROQ, key.trim()).apply()
    }

    fun hasGroqApiKey(context: Context): Boolean = getGroqApiKey(context) != null
}
