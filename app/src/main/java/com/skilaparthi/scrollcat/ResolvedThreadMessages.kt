package com.skilaparthi.scrollcat

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists incoming messages that were resolved in a thread view (chip send / Ignore)
 * so their bubbles can reappear after the panel closes — separate from [ReplyStore]
 * pending FIFO and [SentReplyLog] outgoing history.
 *
 * 14-day retention matches [SentReplyLog].
 */
object ResolvedThreadMessages {

    private const val TAG = "ScrollCat"
    private const val PREFS_NAME = "scrollcat_resolved_thread"
    private const val KEY_ENTRIES = "resolved_thread_json"
    private const val RETENTION_MS = 14L * 24 * 60 * 60 * 1000

    enum class ResolutionType {
        REPLIED,
        IGNORED;

        companion object {
            fun fromStorage(raw: String?): ResolutionType =
                when (raw?.trim()?.uppercase()) {
                    "IGNORED" -> IGNORED
                    else -> REPLIED
                }
        }
    }

    data class ResolvedEntry(
        val conversationKey: String,
        val entryId: String,
        /** Incoming message body (text from the sender). */
        val text: String,
        val timestamp: Long,
        val resolution: ResolutionType
    )

    /** Record a resolved incoming message; purges entries older than 14 days on write. */
    fun record(
        context: Context,
        conversationKey: String,
        entryId: String,
        text: String,
        timestamp: Long,
        resolution: ResolutionType
    ) {
        val key = conversationKey.trim()
        val id = entryId.trim()
        val body = text.trim()
        if (key.isEmpty() || id.isEmpty() || body.isEmpty()) return

        val entries = purgeExpired(loadEntries(context)).toMutableList()
        entries.removeAll { it.entryId == id }
        entries.add(
            ResolvedEntry(
                conversationKey = key,
                entryId = id,
                text = body,
                timestamp = timestamp,
                resolution = resolution
            )
        )
        saveEntries(context, entries)
        Log.d(
            TAG,
            "ResolvedThreadMessages record key=$key entryId=$id resolution=$resolution"
        )
    }

    /** Resolved incoming for one conversation, oldest → newest. */
    fun getForConversation(
        context: Context,
        conversationKey: String
    ): List<ResolvedEntry> {
        val key = conversationKey.trim()
        if (key.isEmpty()) return emptyList()
        val loaded = loadEntries(context)
        val purged = purgeExpired(loaded)
        if (purged.size != loaded.size) {
            saveEntries(context, purged)
        }
        return purged
            .filter { it.conversationKey == key }
            .sortedBy { it.timestamp }
    }

    /**
     * Permanently removes every resolved entry for [conversationKey]
     * (used when discarding a thread — does not wait for 14-day retention).
     */
    fun clearConversation(context: Context, conversationKey: String) {
        val key = conversationKey.trim()
        if (key.isEmpty()) return
        val before = loadEntries(context)
        val after = before.filter { it.conversationKey != key }
        if (after.size == before.size) {
            Log.d(TAG, "ResolvedThreadMessages clearConversation — nothing for $key")
            return
        }
        saveEntries(context, after)
        Log.d(
            TAG,
            "ResolvedThreadMessages clearConversation key=$key " +
                "removed=${before.size - after.size}"
        )
    }

    private fun purgeExpired(entries: List<ResolvedEntry>): List<ResolvedEntry> {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        return entries.filter { it.timestamp >= cutoff }
    }

    private fun loadEntries(context: Context): List<ResolvedEntry> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            val entries = mutableListOf<ResolvedEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val conversationKey = obj.optString("conversationKey", "")
                val entryId = obj.optString("entryId", "")
                val text = obj.optString("text", "")
                if (conversationKey.isBlank() || entryId.isBlank() || text.isBlank()) continue
                entries.add(
                    ResolvedEntry(
                        conversationKey = conversationKey,
                        entryId = entryId,
                        text = text,
                        timestamp = obj.optLong("timestamp", 0L),
                        resolution = ResolutionType.fromStorage(
                            obj.optString("resolution", "REPLIED")
                        )
                    )
                )
            }
            entries
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ResolvedThreadMessages: ${e.message}")
            emptyList()
        }
    }

    private fun saveEntries(context: Context, entries: List<ResolvedEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("conversationKey", entry.conversationKey)
                    put("entryId", entry.entryId)
                    put("text", entry.text)
                    put("timestamp", entry.timestamp)
                    put("resolution", entry.resolution.name)
                }
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENTRIES, array.toString()).apply()
    }
}
