package com.example.scrollcat

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keyword-triggered auto-reply rules, persisted as JSON in SharedPreferences.
 * When an incoming DM contains any trigger keyword of an enabled rule, the
 * cat sends the rule's reply automatically without user interaction.
 */
object AutoReplyManager {

    private const val TAG = "ScrollCat"
    private const val PREFS_NAME = "scrollcat_auto_reply"
    private const val KEY_RULES = "rules_json"
    const val MAX_RULES = 10

    data class Rule(
        // Comma-separated keywords as typed by the user, e.g. "price, cost, rate"
        var triggers: String,
        var reply: String,
        var enabled: Boolean
    ) {
        fun triggerList(): List<String> = triggers.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
    }

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
     * Returns the first enabled rule whose trigger keywords appear in
     * [messageText], or null if nothing matches.
     */
    fun findMatch(context: Context, messageText: String): Rule? {
        val text = messageText.lowercase()
        return getRules(context).firstOrNull { rule ->
            rule.enabled &&
                rule.reply.isNotBlank() &&
                rule.triggerList().any { keyword -> text.contains(keyword) }
        }
    }
}
