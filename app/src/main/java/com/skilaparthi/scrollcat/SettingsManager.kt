package com.skilaparthi.scrollcat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object SettingsManager {

    private const val PREFS_NAME = "scrollcat_prefs"
    private const val KEY_WATCHED_APPS = "watched_apps"
    private const val KEY_WATCHED_PEOPLE = "watched_people"
    private const val KEY_WATCHED_KEYWORDS = "watched_keywords"
    private const val KEY_IGNORED_CHATS = "ignored_chats"
    private const val KEY_FEEDBACK_ENTRIES = "feedback_entries"

    fun getWatchedApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Copy: SharedPreferences StringSet is a live reference and can go stale
        return HashSet(prefs.getStringSet(KEY_WATCHED_APPS, emptySet()) ?: emptySet())
    }

    fun setWatchedApps(context: Context, values: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_WATCHED_APPS, HashSet(values)).apply()
    }

    fun getWatchedPeople(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_WATCHED_PEOPLE, emptySet()) ?: emptySet()
    }

    fun setWatchedPeople(context: Context, values: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_WATCHED_PEOPLE, values).apply()
    }

    fun getWatchedKeywords(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_WATCHED_KEYWORDS, emptySet()) ?: emptySet()
    }

    fun setWatchedKeywords(context: Context, values: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_WATCHED_KEYWORDS, values).apply()
    }

    fun getIgnoredChats(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_IGNORED_CHATS, emptySet()) ?: emptySet()
    }

    fun setIgnoredChats(context: Context, values: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_IGNORED_CHATS, values).apply()
    }

    // Parse comma-separated input into a clean set
    fun parseInput(input: String): Set<String> {
        return input.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    // Check if a notification should trigger the badge
    fun shouldNotify(context: Context, packageName: String, notificationText: String): Boolean {
        val apps = getWatchedApps(context)
        val people = getWatchedPeople(context)
        val keywords = getWatchedKeywords(context)

        // If nothing is selected, notify for nothing
        if (apps.isEmpty() && people.isEmpty() && keywords.isEmpty()) return false

        val text = notificationText.lowercase().trim()
        val pkg = packageName.lowercase()

        // App match
        val appMatch = apps.isEmpty() || apps.any { pkg.contains(it.lowercase()) }

        val personMatch = matchesPerson(context, text)
        val keywordMatch = matchesKeyword(context, pkg, text)

        return when {
            // Only apps selected
            people.isEmpty() && keywords.isEmpty() -> appMatch
            // Only people selected
            apps.isEmpty() && keywords.isEmpty() -> personMatch
            // Only keywords selected
            apps.isEmpty() && people.isEmpty() -> keywordMatch
            // Apps + people
            keywords.isEmpty() -> appMatch && personMatch
            // Apps + keywords
            people.isEmpty() -> appMatch && keywordMatch
            // All three
            else -> appMatch && (personMatch || keywordMatch)
        }
    }

    fun matchesPersonOrKeyword(context: Context, packageName: String, notificationText: String): Boolean {
        val text = notificationText.lowercase().trim()
        val pkg = packageName.lowercase()
        return matchesPerson(context, text) || matchesKeyword(context, pkg, text)
    }

    private fun matchesPerson(context: Context, normalizedText: String): Boolean {
        return getWatchedPeople(context).isNotEmpty() && getWatchedPeople(context).any {
            normalizedText.contains(it.lowercase().trim())
        }
    }

    private fun matchesKeyword(context: Context, normalizedPackageName: String, normalizedText: String): Boolean {
        return getWatchedKeywords(context).isNotEmpty() && getWatchedKeywords(context).any { kw ->
            val k = kw.lowercase().trim()
            normalizedText.contains(k) || normalizedPackageName.contains(k)
        }
    }

    // Cat size (default 210px)
    fun getCatSize(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("cat_size", 210)
    }
    fun setCatSize(context: Context, size: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("cat_size", size).apply()
    }

    // Cat display mode: "always_visible" (default) | "edge_docking"
    private const val KEY_CAT_DISPLAY_MODE = "cat_display_mode"
    private const val KEY_CAT_DOCK_SIDE = "cat_dock_side"
    private const val KEY_CAT_FLOAT_X = "cat_float_x"
    private const val KEY_CAT_FLOAT_Y = "cat_float_y"
    const val DISPLAY_MODE_ALWAYS_VISIBLE = "always_visible"
    const val DISPLAY_MODE_EDGE_DOCKING = "edge_docking"

    fun getCatDisplayMode(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CAT_DISPLAY_MODE, DISPLAY_MODE_EDGE_DOCKING)
            ?: DISPLAY_MODE_EDGE_DOCKING
    }

    fun setCatDisplayMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CAT_DISPLAY_MODE, mode).apply()
    }

    fun isEdgeDockingMode(context: Context): Boolean {
        return getCatDisplayMode(context) == DISPLAY_MODE_EDGE_DOCKING
    }

    /** "left" or "right" — last Move-side preference for edge docking. */
    fun getCatDockSide(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CAT_DOCK_SIDE, "right") ?: "right"
    }

    fun setCatDockSide(context: Context, side: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CAT_DOCK_SIDE, side).apply()
    }

    fun getCatFloatX(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CAT_FLOAT_X, 60)
    }

    fun getCatFloatY(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CAT_FLOAT_Y, 600)
    }

    fun setCatFloatPosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CAT_FLOAT_X, x)
            .putInt(KEY_CAT_FLOAT_Y, y)
            .apply()
    }

    // Scroll sensitivity / distance threshold (default 70px)
    fun getSensitivity(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("sensitivity", 70)
    }
    fun setSensitivity(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("sensitivity", value).apply()
    }

    // Gesture toggles (all default true)
    fun getGestureEnabled(context: Context, gesture: String): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("gesture_$gesture", true)
    }
    fun setGestureEnabled(context: Context, gesture: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("gesture_$gesture", enabled).apply()
    }

    // Left swipe behavior: "recent" = switch to previous app, "recents" = open recents screen
    fun getLeftSwipeBehavior(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("left_swipe_behavior", "recent") ?: "recent"
    }

    fun setLeftSwipeBehavior(context: Context, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("left_swipe_behavior", value).apply()
    }

    // Sleep opacity (default 50%)
    fun getSleepOpacity(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat("sleep_opacity", 0.5f)
    }

    fun setSleepOpacity(context: Context, value: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat("sleep_opacity", value).apply()
    }

    // Reply panel text size: "small" | "normal" | "large" | "xlarge"
    // Normal (14sp) matches the previous default chip text size.
    private const val KEY_REPLY_TEXT_SIZE = "reply_text_size"

    fun getReplyTextSizeOption(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_REPLY_TEXT_SIZE, null)
        if (saved != null) return saved
        return defaultReplyTextSizeOption(context)
    }

    fun hasExplicitReplyTextSize(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .contains(KEY_REPLY_TEXT_SIZE)
    }

    fun setReplyTextSizeOption(context: Context, option: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_REPLY_TEXT_SIZE, option).apply()
    }

    /** Resolved sp size for message preview + reply chips. */
    fun getReplyTextSizeSp(context: Context): Float {
        return when (getReplyTextSizeOption(context)) {
            "small" -> 12f
            "large" -> 17f
            "xlarge" -> 20f
            else -> 14f // normal — matches prior chip default
        }
    }

    private fun defaultReplyTextSizeOption(context: Context): String {
        val scale = context.resources.configuration.fontScale
        return when {
            scale >= 1.3f -> "xlarge"
            scale > 1.0f -> "large"
            else -> "normal"
        }
    }

    // AI reply tone: "casual", "friendly" or "professional"
    fun getReplyTone(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("reply_tone", "friendly") ?: "friendly"
    }

    fun setReplyTone(context: Context, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("reply_tone", value).apply()
    }

    fun isMusicDanceEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains("music_dance_enabled")) {
            return false
        }
        return prefs.getBoolean("music_dance_enabled", false)
    }

    fun setMusicDanceEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("music_dance_enabled", enabled).apply()
    }

    fun isAppReactionsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("app_reactions_enabled", false)
    }

    fun setAppReactionsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("app_reactions_enabled", enabled).apply()
    }

    fun getPrimaryLanguage(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("primary_language", "English") ?: "English"
    }

    fun setPrimaryLanguage(context: Context, language: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("primary_language", language).apply()
    }

    fun getPrimaryCustomLanguages(context: Context): List<String> =
        readVoiceCustomLanguages(context, "primary_custom_languages")

    fun setPrimaryCustomLanguages(context: Context, languages: List<String>) {
        writeVoiceCustomLanguages(context, "primary_custom_languages", languages)
    }

    fun addPrimaryCustomLanguage(context: Context, language: String) {
        addVoiceCustomLanguage(context, "primary_custom_languages", language)
    }

    /**
     * BCP-47 tag for [SpeechRecognizer] / [RecognizerIntent.EXTRA_LANGUAGE],
     * based on Smart Voice "Language 1 (You speak)". Falls back to the device
     * locale when unset, "Other" without a usable name, or an unknown custom name.
     */
    fun getSpeechRecognitionLanguageTag(context: Context): String {
        val spoken = getVoiceLanguage1(context).trim()
        if (spoken.isEmpty() || spoken.equals("Other", ignoreCase = true)) {
            return java.util.Locale.getDefault().toLanguageTag()
        }
        return languageNameToBcp47(spoken)
            ?: java.util.Locale.getDefault().toLanguageTag()
    }

    /** Best-effort map from display name (chips + free-text "Other") → BCP-47. */
    fun languageNameToBcp47(languageName: String): String? {
        val key = languageName.trim().lowercase()
            .replace('_', '-')
            .replace(Regex("\\s+"), " ")
        // Already a language tag like "te-IN" or "te"
        if (key.matches(Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})*$"))) {
            return languageName.trim().replace('_', '-')
        }
        return when (key) {
            "english", "en", "eng" -> "en-US"
            "hindi", "hi", "hin" -> "hi-IN"
            "spanish", "español", "espanol", "es" -> "es-ES"
            "arabic", "عربي", "ar" -> "ar-SA"
            "french", "français", "francais", "fr" -> "fr-FR"
            "telugu", "te", "తెలుగు" -> "te-IN"
            "tamil", "ta" -> "ta-IN"
            "kannada", "kn" -> "kn-IN"
            "malayalam", "ml" -> "ml-IN"
            "marathi", "mr" -> "mr-IN"
            "bengali", "bangla", "bn" -> "bn-IN"
            "gujarati", "gu" -> "gu-IN"
            "punjabi", "pa" -> "pa-IN"
            "urdu", "ur" -> "ur-PK"
            "german", "deutsch", "de" -> "de-DE"
            "portuguese", "português", "portugues", "pt" -> "pt-BR"
            "italian", "italiano", "it" -> "it-IT"
            "japanese", "日本語", "ja" -> "ja-JP"
            "korean", "한국어", "ko" -> "ko-KR"
            "chinese", "mandarin", "中文", "zh" -> "zh-CN"
            "russian", "ru" -> "ru-RU"
            "dutch", "nederlands", "nl" -> "nl-NL"
            "turkish", "tr" -> "tr-TR"
            "vietnamese", "vi" -> "vi-VN"
            "thai", "th" -> "th-TH"
            "indonesian", "bahasa", "id" -> "id-ID"
            else -> null
        }
    }

    /** ML Kit TranslateLanguage base tag (e.g. "te") from a display name or BCP-47. */
    fun languageNameToMlKitTag(languageName: String): String? {
        val bcp = languageNameToBcp47(languageName) ?: return null
        return bcp.substringBefore('-').lowercase()
    }

    // ── Smart Voice (speak / appear-as languages + translate toggle) ──

    private fun ensureVoiceLanguagesMigrated(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains("voice_language_1")) return
        val seed = getPrimaryLanguage(context).ifBlank { "English" }
        prefs.edit()
            .putString("voice_language_1", seed)
            .putString("voice_language_2", seed)
            .apply()
    }

    fun getVoiceLanguage1(context: Context): String {
        ensureVoiceLanguagesMigrated(context)
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("voice_language_1", "English") ?: "English"
    }

    fun setVoiceLanguage1(context: Context, language: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("voice_language_1", language.trim().ifBlank { "English" }).apply()
    }

    fun getVoiceLanguage2(context: Context): String {
        ensureVoiceLanguagesMigrated(context)
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("voice_language_2", "English") ?: "English"
    }

    fun setVoiceLanguage2(context: Context, language: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("voice_language_2", language.trim().ifBlank { "English" }).apply()
    }

    fun isVoiceTranslateEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("voice_translate_enabled", false)
    }

    fun setVoiceTranslateEnabled(context: Context, enabled: Boolean) {
        val edit = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("voice_translate_enabled", enabled)
        // Translate and Romanize are mutually exclusive output modes.
        if (enabled) edit.putBoolean("voice_romanize_enabled", false)
        edit.apply()
    }

    fun isVoiceRomanizeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("voice_romanize_enabled", false)
    }

    fun setVoiceRomanizeEnabled(context: Context, enabled: Boolean) {
        val edit = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("voice_romanize_enabled", enabled)
        // Translate and Romanize are mutually exclusive output modes.
        if (enabled) edit.putBoolean("voice_translate_enabled", false)
        edit.apply()
    }

    // ── On-device model manual override (testing) ──

    /** Stored values: [OVERRIDE_E2B], [OVERRIDE_270M], or null = automatic RAM tier. */
    const val OVERRIDE_E2B = "e2b"
    const val OVERRIDE_270M = "270m"

    fun getOnDeviceModelOverride(context: Context): String? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("on_device_model_override", null)
            ?.trim()
            ?.lowercase()
        return raw?.takeIf { it.isNotEmpty() }
    }

    fun setOnDeviceModelOverride(context: Context, override: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        val normalized = override?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (normalized == null) {
            prefs.remove("on_device_model_override")
        } else {
            prefs.putString("on_device_model_override", normalized)
        }
        prefs.apply()
    }

    /**
     * When false, on-device AI must not warm up or generate — callers fall through to cloud.
     * Synced with [getPrimaryAiProvider]: true only when primary is on-device.
     */
    fun isOnDeviceAiEnabled(context: Context): Boolean {
        return getPrimaryAiProvider(context) == PRIMARY_AI_ON_DEVICE
    }

    fun setOnDeviceAiEnabled(context: Context, enabled: Boolean) {
        // Kept for call-site compatibility; prefer setPrimaryAiProvider.
        if (enabled) {
            setPrimaryAiProvider(context, PRIMARY_AI_ON_DEVICE)
        } else if (getPrimaryAiProvider(context) == PRIMARY_AI_ON_DEVICE) {
            val fallback = defaultCloudPrimaryKey(context)
            setPrimaryAiProvider(context, fallback)
        }
        android.util.Log.d("ScrollCat", "Settings: on_device_ai_enabled → $enabled")
    }

    /** Explicit primary reply engine: [PRIMARY_AI_ON_DEVICE], groq, claude, openai, custom, or "". */
    const val PRIMARY_AI_ON_DEVICE = "on_device"
    const val PRIMARY_AI_GROQ = "groq"
    const val PRIMARY_AI_CLAUDE = "claude"
    const val PRIMARY_AI_OPENAI = "openai"
    const val PRIMARY_AI_CUSTOM = "custom"

    fun getPrimaryAiProvider(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString("primary_ai_provider", null)?.trim()?.lowercase()
        if (!stored.isNullOrEmpty()) {
            if (stored == PRIMARY_AI_ON_DEVICE && !ModelDownloadManager.modelFileExists(context)) {
                // Model was deleted — fall through to cloud default.
            } else {
                return stored
            }
        }
        return resolveDefaultPrimaryAiProvider(context)
    }

    fun setPrimaryAiProvider(context: Context, primary: String) {
        val normalized = primary.trim().lowercase()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("primary_ai_provider", normalized).apply()
        // Mirror legacy boolean so older call sites stay consistent.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("on_device_ai_enabled", normalized == PRIMARY_AI_ON_DEVICE).apply()
        android.util.Log.d("ScrollCat", "Settings: primary_ai_provider → $normalized")
    }

    /** Default when no explicit primary is stored. */
    fun resolveDefaultPrimaryAiProvider(context: Context): String {
        val hasModel = ModelDownloadManager.modelFileExists(context)
        val cloudKey = defaultCloudPrimaryKey(context)
        return when {
            hasModel -> PRIMARY_AI_ON_DEVICE
            cloudKey.isNotEmpty() -> cloudKey
            else -> ""
        }
    }

    fun primaryKeyForCloudProvider(name: String, endpoint: String): String {
        val n = name.lowercase()
        val e = endpoint.lowercase()
        return when {
            n.contains("groq") || e.contains("groq") -> PRIMARY_AI_GROQ
            n.contains("claude") || e.contains("anthropic") -> PRIMARY_AI_CLAUDE
            n.contains("openai") || e.contains("openai.com") -> PRIMARY_AI_OPENAI
            else -> PRIMARY_AI_CUSTOM
        }
    }

    private fun defaultCloudPrimaryKey(context: Context): String {
        val providers = try {
            AiProviderActivity.loadProviderList(context)
        } catch (_: Exception) {
            emptyList()
        }
        val active = providers.firstOrNull { it.isActive } ?: providers.firstOrNull()
        return if (active != null) {
            primaryKeyForCloudProvider(active.name, active.endpoint)
        } else {
            ""
        }
    }

    /** User-added language chips for Smart Voice Language 1 (persisted across sessions). */
    fun getVoiceCustomLanguages1(context: Context): List<String> =
        readVoiceCustomLanguages(context, "voice_custom_languages_1")

    fun addVoiceCustomLanguage1(context: Context, language: String) {
        addVoiceCustomLanguage(context, "voice_custom_languages_1", language)
    }

    /** User-added language chips for Smart Voice Language 2 (persisted across sessions). */
    fun getVoiceCustomLanguages2(context: Context): List<String> =
        readVoiceCustomLanguages(context, "voice_custom_languages_2")

    fun addVoiceCustomLanguage2(context: Context, language: String) {
        addVoiceCustomLanguage(context, "voice_custom_languages_2", language)
    }

    fun setVoiceCustomLanguages1(context: Context, languages: List<String>) {
        writeVoiceCustomLanguages(context, "voice_custom_languages_1", languages)
    }

    fun setVoiceCustomLanguages2(context: Context, languages: List<String>) {
        writeVoiceCustomLanguages(context, "voice_custom_languages_2", languages)
    }

    private fun readVoiceCustomLanguages(context: Context, key: String): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split('\u001f')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
    }

    private fun writeVoiceCustomLanguages(context: Context, key: String, languages: List<String>) {
        val cleaned = languages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(key, cleaned.joinToString("\u001f")).apply()
    }

    private fun addVoiceCustomLanguage(context: Context, key: String, language: String) {
        val name = language.trim()
        if (name.isEmpty()) return
        val existing = readVoiceCustomLanguages(context, key).toMutableList()
        val idx = existing.indexOfFirst { it.equals(name, ignoreCase = true) }
        if (idx >= 0) {
            existing[idx] = name
        } else {
            existing.add(name)
        }
        writeVoiceCustomLanguages(context, key, existing)
    }

    fun isMatchLanguageEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("match_language", true)
    }

    fun setMatchLanguageEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("match_language", enabled).apply()
    }

    // ── Onboarding / user profile ──

    fun isOnboardingComplete(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("onboarding_complete", false)
    }

    fun setOnboardingComplete(context: Context, complete: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_complete", complete).apply()
        if (complete) {
            setOnboardingDemoCompleted(context, false)
        }
    }

    fun isOnboardingDemoCompleted(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("onboarding_demo_completed", false)
    }

    fun setOnboardingDemoCompleted(context: Context, completed: Boolean) {
        // commit() so onboarding UI can read the flag immediately after demo reply
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_demo_completed", completed).commit()
    }

    // "creator", "business" or "personal"
    fun getUserType(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("user_type", "personal") ?: "personal"
    }

    fun setUserType(context: Context, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("user_type", value).apply()
    }

    // Creator: display name. Business: business name.
    fun getUserName(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("user_name", "") ?: ""
    }

    fun setUserName(context: Context, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("user_name", value).apply()
    }

    // Creator: niche (Fashion/Food/...). Business: service type (Plumbing/Salon/...).
    fun getUserNiche(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("user_niche", "") ?: ""
    }

    fun setUserNiche(context: Context, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("user_niche", value).apply()
    }

    // Creator rate card / business default reply message
    fun getRateCardMessage(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("rate_card_message", "") ?: ""
    }

    fun setRateCardMessage(context: Context, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("rate_card_message", value).apply()
    }

    private const val LEGACY_ACTIVE_AI_KEY = "active_ai_key"

    @Volatile
    private var activeKeyMigrationDone = false

    /**
     * Moves the pre-encryption plaintext `active_ai_key` into [ApiKeyStore] and deletes
     * it from scrollcat_prefs. Runs once per process.
     */
    fun migrateLegacyActiveAiKey(context: Context) {
        if (activeKeyMigrationDone) return
        synchronized(this) {
            if (activeKeyMigrationDone) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains(LEGACY_ACTIVE_AI_KEY)) {
                val legacy = prefs.getString(LEGACY_ACTIVE_AI_KEY, "").orEmpty()
                if (legacy.isNotBlank()) {
                    ApiKeyStore.setActiveApiKey(context, legacy)
                    Logger.d("Migrated 1 plaintext active AI key to encrypted storage")
                }
                prefs.edit().remove(LEGACY_ACTIVE_AI_KEY).commit()
            }
            activeKeyMigrationDone = true
        }
    }

    fun setActiveAiProvider(context: Context, endpoint: String, model: String, apiKey: String) {
        migrateLegacyActiveAiKey(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("active_ai_endpoint", endpoint)
            .putString("active_ai_model", model)
            .remove(LEGACY_ACTIVE_AI_KEY)
            .apply()
        ApiKeyStore.setActiveApiKey(context, apiKey)
    }

    fun getActiveAiEndpoint(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("active_ai_endpoint", "https://api.groq.com/openai/v1/chat/completions")
            ?: "https://api.groq.com/openai/v1/chat/completions"

    fun getActiveAiModel(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("active_ai_model", "llama-3.1-8b-instant") ?: "llama-3.1-8b-instant"

    fun getActiveAiKey(context: Context): String {
        migrateLegacyActiveAiKey(context)
        return ApiKeyStore.getActiveApiKey(context)
    }

    data class FeedbackEntry(val text: String, val timestamp: Long)

    fun addFeedback(context: Context, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString(KEY_FEEDBACK_ENTRIES, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        arr.put(
            JSONObject()
                .put("text", trimmed)
                .put("timestamp", System.currentTimeMillis())
        )
        prefs.edit().putString(KEY_FEEDBACK_ENTRIES, arr.toString()).apply()
    }

    fun getFeedbackEntries(context: Context): List<FeedbackEntry> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FEEDBACK_ENTRIES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val entryText = obj.optString("text", "").trim()
                    if (entryText.isEmpty()) continue
                    add(FeedbackEntry(entryText, obj.optLong("timestamp", 0L)))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
