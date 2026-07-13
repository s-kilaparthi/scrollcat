package com.example.scrollcat

import android.content.Context

object SettingsManager {

    private const val PREFS_NAME = "scrollcat_prefs"
    private const val KEY_WATCHED_APPS = "watched_apps"
    private const val KEY_WATCHED_PEOPLE = "watched_people"
    private const val KEY_WATCHED_KEYWORDS = "watched_keywords"

    fun getWatchedApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_WATCHED_APPS, emptySet()) ?: emptySet()
    }

    fun setWatchedApps(context: Context, values: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_WATCHED_APPS, values).apply()
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

        // Person match
        val personMatch = people.isNotEmpty() && people.any { 
            text.contains(it.lowercase().trim()) 
        }

        // Keyword match — also check package name in case keyword is app name
        val keywordMatch = keywords.isNotEmpty() && keywords.any { kw ->
            val k = kw.lowercase().trim()
            text.contains(k) || pkg.contains(k)
        }

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

    // Cat size (default 240px)
    fun getCatSize(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("cat_size", 240)
    }
    fun setCatSize(context: Context, size: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("cat_size", size).apply()
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

    // Break timer interval in minutes (default 10)
    fun getBreakInterval(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("break_interval", 10)
    }
    fun setBreakInterval(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt("break_interval", value).apply()
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
}
