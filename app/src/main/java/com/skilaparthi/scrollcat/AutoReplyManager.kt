package com.skilaparthi.scrollcat

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * Keyword-triggered auto-reply rules, persisted as JSON in SharedPreferences.
 * When an incoming DM contains any trigger keyword of an enabled rule, the
 * cat sends the rule's reply automatically without user interaction.
 */
object AutoReplyManager {

    private const val TAG = "ScrollCat"
    private const val PREFS_NAME = "scrollcat_auto_reply"
    private const val KEY_RULES = "rules_json"
    private const val KEY_TRACKER = "tracker_json"
    const val MAX_RULES = 10
    private const val TRACKER_RETENTION_MS = 14L * 24 * 60 * 60 * 1000

    data class Rule(
        // Comma-separated keywords as typed by the user, e.g. "price, cost, rate"
        var triggers: String,
        var reply: String,
        var enabled: Boolean
    ) {
        fun triggerList(): List<String> = triggers.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        /** Both keyword (≥1 char) and reply must be present to fire on messages. */
        fun isQualified(): Boolean =
            triggerList().isNotEmpty() && reply.trim().isNotEmpty()
    }

    data class Match(
        val rule: Rule,
        val matchedKeyword: String
    )

    data class TrackerEntry(
        val id: String,
        val packageName: String,
        val timestamp: Long,
        val sender: String,
        /** The auto-reply message text that was actually sent. */
        val message: String,
        /** Keyword that triggered the rule (empty for legacy entries). */
        val matchedKeyword: String = "",
        val cleared: Boolean = false
    )

    fun getRules(context: Context): MutableList<Rule> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RULES, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(json)
            val rules = mutableListOf<Rule>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                rules.add(
                    Rule(
                        triggers = obj.optString("triggers", ""),
                        reply = obj.optString("reply", ""),
                        enabled = obj.optBoolean("enabled", true)
                    )
                )
            }
            rules
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse auto-reply rules: ${e.message}")
            mutableListOf()
        }
    }

    fun saveRules(context: Context, rules: List<Rule>) {
        val array = JSONArray()
        rules.take(MAX_RULES).forEach { rule ->
            array.put(JSONObject().apply {
                put("triggers", rule.triggers)
                put("reply", rule.reply)
                put("enabled", rule.enabled)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_RULES, array.toString()).apply()
    }

    /**
     * Returns the first enabled **qualified** rule whose trigger keywords appear in
     * [messageText], plus the specific keyword that matched, or null.
     * Incomplete drafts (missing keyword or reply) are ignored.
     */
    fun findMatch(context: Context, messageText: String, senderName: String? = null): Match? {
        // Never auto-reply to our own sent messages
        if (senderName == "You" || senderName.isNullOrBlank()) return null

        val text = messageText.lowercase()
        for (rule in getRules(context)) {
            if (!rule.enabled || !rule.isQualified()) continue
            val keyword = rule.triggerList().firstOrNull { text.contains(it) } ?: continue
            return Match(rule = rule, matchedKeyword = keyword)
        }
        return null
    }

    /** All tracker entries after purging anything older than 14 days. */
    fun getTrackerEntries(context: Context): List<TrackerEntry> {
        val entries = loadTrackerEntries(context)
        val purged = purgeExpired(entries)
        if (purged.size != entries.size) saveTrackerEntries(context, purged)
        return purged
    }

    /** Uncleared entries only (default tracker list / dashboard badge). */
    fun getPendingTrackerEntries(context: Context): List<TrackerEntry> =
        getTrackerEntries(context).filter { !it.cleared }

    /** Count of auto-replies logged with a timestamp on the current calendar day. */
    fun getAutoRepliesTriggeredToday(context: Context): Int {
        val now = System.currentTimeMillis()
        return getTrackerEntries(context).count { isSameCalendarDay(it.timestamp, now) }
    }

    private fun isSameCalendarDay(aMs: Long, bMs: Long): Boolean {
        val calA = Calendar.getInstance().apply { timeInMillis = aMs }
        val calB = Calendar.getInstance().apply { timeInMillis = bMs }
        return calA.get(Calendar.YEAR) == calB.get(Calendar.YEAR) &&
            calA.get(Calendar.DAY_OF_YEAR) == calB.get(Calendar.DAY_OF_YEAR)
    }
    fun logTrackerEntry(
        context: Context,
        packageName: String,
        sender: String,
        message: String,
        matchedKeyword: String = "",
        timestamp: Long = System.currentTimeMillis()
    ) {
        val entries = purgeExpired(loadTrackerEntries(context)).toMutableList()
        val entry = TrackerEntry(
            id = UUID.randomUUID().toString(),
            packageName = packageName,
            timestamp = timestamp,
            sender = sender,
            message = message,
            matchedKeyword = matchedKeyword,
            cleared = false
        )
        entries.add(0, entry)
        saveTrackerEntries(context, entries)
        AutoReplyAlertNotifier.notify(
            context = context,
            sender = sender,
            matchedKeyword = matchedKeyword,
            replyText = message
        )
    }

    fun clearTrackerEntry(context: Context, entryId: String) {
        val entries = purgeExpired(loadTrackerEntries(context)).map { entry ->
            if (entry.id == entryId) entry.copy(cleared = true) else entry
        }
        saveTrackerEntries(context, entries)
    }

    private fun loadTrackerEntries(context: Context): List<TrackerEntry> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TRACKER, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            val entries = mutableListOf<TrackerEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                // Prefer "message"; fall back to legacy "keyword" field if present
                // (legacy "keyword" stored the reply text, not the trigger keyword).
                val message = obj.optString("message")
                    .ifBlank { obj.optString("keyword", "") }
                entries.add(
                    TrackerEntry(
                        id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                        packageName = obj.optString("packageName", ""),
                        timestamp = obj.optLong("timestamp", 0L),
                        sender = obj.optString("sender", ""),
                        message = message,
                        matchedKeyword = obj.optString("matchedKeyword", ""),
                        cleared = obj.optBoolean("cleared", obj.optBoolean("handled", false))
                    )
                )
            }
            entries
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse auto-reply tracker: ${e.message}")
            emptyList()
        }
    }

    private fun purgeExpired(entries: List<TrackerEntry>): List<TrackerEntry> {
        val cutoff = System.currentTimeMillis() - TRACKER_RETENTION_MS
        return entries.filter { it.timestamp >= cutoff }
    }

    private fun saveTrackerEntries(context: Context, entries: List<TrackerEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("packageName", entry.packageName)
                put("timestamp", entry.timestamp)
                put("sender", entry.sender)
                put("message", entry.message)
                put("matchedKeyword", entry.matchedKeyword)
                put("cleared", entry.cleared)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TRACKER, array.toString()).apply()
    }
}
