package com.example.scrollcat

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores Claude / Groq API keys in EncryptedSharedPreferences so they never sit
 * on disk in plain text.
 *
 * If the Android Keystore / EncryptedSharedPreferences path fails, we retry once.
 * If the retry also fails, keys are held in an in-process map for this session only
 * — never written to disk. A later process start will try encrypted storage again.
 */
object ApiKeyStore {

    private const val PREFS_NAME = "scrollcat_secure"
    private const val LEGACY_PLAINTEXT_FALLBACK = "scrollcat_secure_fallback"
    private const val KEY_CLAUDE = "claude_api_key"
    private const val KEY_GROQ = "groq_api_key"
    private const val KEY_ACTIVE = "active_ai_key"
    private const val PROVIDER_KEY_PREFIX = "provider_key_"

    @Volatile
    private var encryptedPrefs: SharedPreferences? = null

    @Volatile
    private var usingInMemoryFallback = false

    @Volatile
    private var initialized = false

    /** One-shot flag so UI can show a single non-blocking warning per process. */
    @Volatile
    private var pendingInMemoryWarning = false

    private val memoryKeys = mutableMapOf<String, String>()

    /** True when this process is using the in-memory store (nothing written to disk). */
    fun isUsingInMemoryFallback(): Boolean {
        // Callers may check before any get/set — ensure we have probed storage.
        return usingInMemoryFallback
    }

    /**
     * Returns true once if secure storage failed and the user should be told that
     * keys will not survive a restart. Subsequent calls return false until the
     * next process that again falls back to memory.
     */
    fun consumeInMemoryFallbackWarning(context: Context): Boolean {
        ensureInitialized(context)
        if (!pendingInMemoryWarning) return false
        pendingInMemoryWarning = false
        return true
    }

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            // Never leave a legacy plaintext fallback file on disk.
            purgeLegacyPlaintextFallback(appContext)

            val firstAttempt = createEncryptedPrefs(appContext)
            if (firstAttempt != null) {
                encryptedPrefs = firstAttempt
                usingInMemoryFallback = false
                initialized = true
                return
            }

            Logger.e("EncryptedSharedPreferences init failed — retrying once")
            val retryAttempt = createEncryptedPrefs(appContext)
            if (retryAttempt != null) {
                encryptedPrefs = retryAttempt
                usingInMemoryFallback = false
                initialized = true
                Logger.d("EncryptedSharedPreferences recovered on retry")
                return
            }

            // Never fall back to plaintext SharedPreferences.
            encryptedPrefs = null
            usingInMemoryFallback = true
            pendingInMemoryWarning = true
            initialized = true
            Logger.e(
                "Secure storage unavailable — using in-memory API key store for this session only"
            )
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Logger.e("EncryptedSharedPreferences create failed: ${e.message}", e)
            null
        }
    }

    private fun purgeLegacyPlaintextFallback(context: Context) {
        try {
            context.deleteSharedPreferences(LEGACY_PLAINTEXT_FALLBACK)
        } catch (e: Exception) {
            Logger.e("Could not delete legacy plaintext API key prefs: ${e.message}")
        }
    }

    private fun readKey(context: Context, key: String): String {
        ensureInitialized(context)
        if (usingInMemoryFallback) {
            synchronized(this) {
                return memoryKeys[key].orEmpty()
            }
        }
        return encryptedPrefs?.getString(key, "").orEmpty()
    }

    private fun removeKey(context: Context, key: String) {
        ensureInitialized(context)
        if (usingInMemoryFallback) {
            synchronized(this) { memoryKeys.remove(key) }
            return
        }
        try {
            encryptedPrefs?.edit()?.remove(key)?.apply()
        } catch (e: Exception) {
            Logger.e("Encrypted remove failed for $key: ${e.message}")
        }
    }

    private fun writeKey(context: Context, key: String, value: String) {
        ensureInitialized(context)
        val trimmed = value.trim()
        if (usingInMemoryFallback) {
            synchronized(this) {
                if (trimmed.isEmpty()) memoryKeys.remove(key) else memoryKeys[key] = trimmed
            }
            return
        }
        try {
            encryptedPrefs?.edit()?.putString(key, trimmed)?.apply()
        } catch (e: Exception) {
            // Mid-session write failure: keep the key usable in memory, never write plaintext.
            Logger.e(
                "Encrypted write failed — switching to in-memory API key store: ${e.message}",
                e
            )
            synchronized(this) {
                encryptedPrefs = null
                usingInMemoryFallback = true
                pendingInMemoryWarning = true
                if (trimmed.isEmpty()) memoryKeys.remove(key) else memoryKeys[key] = trimmed
            }
        }
    }

    fun getClaudeApiKey(context: Context): String = readKey(context, KEY_CLAUDE)

    fun setClaudeApiKey(context: Context, key: String) {
        writeKey(context, KEY_CLAUDE, key)
    }

    fun hasClaudeApiKey(context: Context): Boolean = getClaudeApiKey(context).isNotBlank()

    fun getGroqApiKey(context: Context): String? {
        return readKey(context, KEY_GROQ).takeIf { it.isNotBlank() }
    }

    fun setGroqApiKey(context: Context, key: String) {
        writeKey(context, KEY_GROQ, key)
    }

    fun hasGroqApiKey(context: Context): Boolean = getGroqApiKey(context) != null

    /**
     * Key for the currently active AI provider, used by the reply generators.
     * Replaces the former plaintext `active_ai_key` entry in scrollcat_prefs.
     */
    fun getActiveApiKey(context: Context): String = readKey(context, KEY_ACTIVE)

    fun setActiveApiKey(context: Context, key: String) {
        writeKey(context, KEY_ACTIVE, key)
    }

    /** Per-provider key storage, addressed by the provider-list entry's id. */
    fun getProviderApiKey(context: Context, providerId: String): String {
        if (providerId.isBlank()) return ""
        return readKey(context, PROVIDER_KEY_PREFIX + providerId)
    }

    fun setProviderApiKey(context: Context, providerId: String, key: String) {
        if (providerId.isBlank()) return
        writeKey(context, PROVIDER_KEY_PREFIX + providerId, key)
    }

    fun removeProviderApiKey(context: Context, providerId: String) {
        if (providerId.isBlank()) return
        removeKey(context, PROVIDER_KEY_PREFIX + providerId)
    }
}
