package com.skilaparthi.scrollcat

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Append-only log of replies the user actually sent via [ReplySender] (chips, custom/
 * edited text, and any other RemoteInput send that goes through that path).
 *
 * Separate from [ReplyStore]'s pending FIFO and from [AutoReplyManager]'s tracker —
 * for threaded conversation views.
 */
object SentReplyLog {

    private const val TAG = "ScrollCat"
    private const val PREFS_NAME = "scrollcat_sent_replies"
    private const val KEY_ENTRIES = "sent_replies_json"
    private const val RETENTION_MS = 14L * 24 * 60 * 60 * 1000

    data class SentReplyEntry(
        val id: String,
        val conversationKey: String,
        val text: String,
        val timestamp: Long,
        /** Incoming [ReplyStore.ReplyableMessage.entryId] this reply answered, if known. */
        val replyToEntryId: String? = null
    )

    /**
     * Append one sent reply; purges entries older than 14 days on write.
     * @return the new entry id, or null if skipped
     */
    fun append(
        context: Context,
        conversationKey: String,
        text: String,
        timestamp: Long = System.currentTimeMillis(),
        replyToEntryId: String? = null
    ): String? {
        val trimmedKey = conversationKey.trim()
        val trimmedText = text.trim()
        if (trimmedKey.isEmpty() || trimmedText.isEmpty()) return null

        val id = UUID.randomUUID().toString()
        val entries = purgeExpired(loadEntries(context)).toMutableList()
        entries.add(
            SentReplyEntry(
                id = id,
                conversationKey = trimmedKey,
                text = trimmedText,
                timestamp = timestamp,
                replyToEntryId = replyToEntryId?.trim()?.takeIf { it.isNotEmpty() }
            )
        )
        saveEntries(context, entries)
        Log.d(
            TAG,
            "SentReplyLog append conversationKey=$trimmedKey chars=${trimmedText.length} " +
                "replyTo=${replyToEntryId ?: "-"}"
        )
        return id
    }

    /** Sent replies for one conversation, oldest → newest. */
    fun getSentRepliesForConversation(
        context: Context,
        conversationKey: String
    ): List<SentReplyEntry> {
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
     * Permanently removes every stored sent-reply for [conversationKey]
     * (used when discarding a thread — does not wait for 14-day retention).
     */
    fun clearConversation(context: Context, conversationKey: String) {
        val key = conversationKey.trim()
        if (key.isEmpty()) return
        val before = loadEntries(context)
        val after = before.filter { it.conversationKey != key }
        if (after.size == before.size) {
            Log.d(TAG, "SentReplyLog clearConversation — nothing for $key")
            return
        }
        saveEntries(context, after)
        Log.d(
            TAG,
            "SentReplyLog clearConversation key=$key removed=${before.size - after.size}"
        )
    }

    private fun purgeExpired(entries: List<SentReplyEntry>): List<SentReplyEntry> {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        return entries.filter { it.timestamp >= cutoff }
    }

    private fun loadEntries(context: Context): List<SentReplyEntry> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            val entries = mutableListOf<SentReplyEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val conversationKey = obj.optString("conversationKey", "")
                val text = obj.optString("text", "")
                if (conversationKey.isBlank() || text.isBlank()) continue
                val replyTo = obj.optString("replyToEntryId", "").trim()
                    .takeIf { it.isNotEmpty() }
                entries.add(
                    SentReplyEntry(
                        id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                        conversationKey = conversationKey,
                        text = text,
                        timestamp = obj.optLong("timestamp", 0L),
                        replyToEntryId = replyTo
                    )
                )
            }
            entries
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SentReplyLog: ${e.message}")
            emptyList()
        }
    }

    private fun saveEntries(context: Context, entries: List<SentReplyEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("conversationKey", entry.conversationKey)
                    put("text", entry.text)
                    put("timestamp", entry.timestamp)
                    entry.replyToEntryId?.let { put("replyToEntryId", it) }
                }
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENTRIES, array.toString()).apply()
    }
}
