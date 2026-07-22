package com.example.scrollcat

import android.content.Context

object SettingsManager {

    private const val PREFS_NAME = "scrollcat_prefs"
    private const val KEY_WATCHED_APPS = "watched_apps"
    private const val KEY_WATCHED_PEOPLE = "watched_people"
    private const val KEY_WATCHED_KEYWORDS = "watched_keywords"
    private const val KEY_IGNORED_CHATS = "ignored_chats"

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
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("music_dance_enabled", true)
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

    // ── Onboarding / user profile ──

    fun isOnboardingComplete(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("onboarding_complete", false)
    }

    fun setOnboardingComplete(context: Context, complete: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_complete", complete).apply()
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

    fun setActiveAiProvider(context: Context, endpoint: String, model: String, apiKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("active_ai_endpoint", endpoint)
            .putString("active_ai_model", model)
            .putString("active_ai_key", apiKey)
            .apply()
    }

    fun getActiveAiEndpoint(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("active_ai_endpoint", "https://api.groq.com/openai/v1/chat/completions")
            ?: "https://api.groq.com/openai/v1/chat/completions"

    fun getActiveAiModel(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("active_ai_model", "llama-3.1-8b-instant") ?: "llama-3.1-8b-instant"

    fun getActiveAiKey(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("active_ai_key", "") ?: ""
}
