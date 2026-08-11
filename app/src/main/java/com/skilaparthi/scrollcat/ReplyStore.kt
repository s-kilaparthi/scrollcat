package com.skilaparthi.scrollcat

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.os.Bundle
import android.service.notification.StatusBarNotification
import java.util.Locale
import java.util.UUID

/**
 * In-memory store of pending replyable messages (max [MAX_ENTRIES] total).
 * Each sender/conversation holds a **queue** of individual message entries — new
 * arrivals APPEND rather than overwrite. Each entry keeps its own text, RemoteInput
 * action, and (once ready) generated reply chips keyed by [ReplyableMessage.entryId].
 *
 * IMPORTANT: We store only PendingIntent + RemoteInput arrays extracted from
 * the notification at capture time — never the full StatusBarNotification or
 * Notification.Action, which would leak binder references.
 */
object ReplyStore {

    private const val TAG = "ScrollCat"
    private const val MAX_ENTRIES = 20

    /** Apps whose DMs/emails the cat offers smart replies for. */
    val MESSAGING_APPS = setOf(
        "com.whatsapp",                      // WhatsApp
        "com.whatsapp.w4b",                  // WhatsApp Business
        "com.instagram.android",             // Instagram
        "org.telegram.messenger",            // Telegram
        "com.discord",                       // Discord
        "com.facebook.orca",                 // Messenger
        "com.samsung.android.messaging",     // Samsung Messages
        "com.google.android.apps.messaging", // Google Messages
        "com.google.android.gm",             // Gmail
        "com.microsoft.office.outlook"       // Outlook
    )

    /**
     * Strip notification-title decorations that churn between updates for the same
     * chat (WhatsApp "(2 messages)", unread counts, etc.) so the same person maps
     * to one stable conversation identity. Display still uses the cleaned string
     * with original casing; [buildConversationKey] lowercases for matching.
     */
    fun normalizeSenderName(rawName: String): String {
        var s = rawName.trim()
        // WhatsApp / similar — ANYWHERE in the title (group chats put the count
        // mid-string: "Links (2 messages): Karthik USA").
        s = s.replace(
            Regex("""\s*\(\d+\s*messages?\)""", RegexOption.IGNORE_CASE),
            ""
        )
        // Bare counts anywhere: "Name (3)", "Name [2]", "Name (3): Alice"
        s = s.replace(Regex("""\s*[\(\[]\d+[\)\]]"""), "")
        // Trailing only: "Name · 2 new", "Name - 3 unread", "Name — 1 new message"
        s = s.replace(
            Regex(
                """\s*[·•\-–—|:]\s*\d+\s*(new\s*)?(messages?|unread)?\s*$""",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
        // Trailing "new messages" / "unread" without numbers
        s = s.replace(
            Regex("""\s*[·•\-–—|:]\s*(new\s+messages?|unread)\s*$""", RegexOption.IGNORE_CASE),
            ""
        )
        s = s.replace(Regex("""\s+"""), " ").trim()
        return s
    }

    /** Stable conversation identity: lowercased package + normalized sender. */
    fun buildConversationKey(packageName: String, sender: String): String {
        val pkg = packageName.trim().lowercase(Locale.US)
        val who = normalizeSenderName(sender).lowercase(Locale.US)
        return "$pkg|$who"
    }

    data class ReplyableMessage(
        val entryId: String,
        val notificationKey: String,
        val notificationId: Int,
        val packageName: String,
        val sender: String,
        val message: String,
        val timestamp: Long,
        val hasRemoteInput: Boolean,
        val actionIntent: PendingIntent?,
        val remoteInputs: Array<RemoteInput>,
        val contentIntent: PendingIntent?,
        val priority: Boolean = false
    ) {
        val conversationKey: String get() = buildConversationKey(packageName, sender)
    }

    data class BufferedMessage(val text: String, val timestamp: Long)

    private val screenOffBuffer = mutableMapOf<String, MutableList<BufferedMessage>>()
    /** Generated chips keyed by [ReplyableMessage.entryId]. */
    private val storedReplies = mutableMapOf<String, List<String>>()
    /**
     * Shared interactive-merge generation batch keyed by [ReplyableMessage.entryId].
     * Entries that received chips via [storeRepliesForConversation] (or WhereEmpty)
     * share one batchId; per-entry [storeReplies] clears membership so live post-open
     * generations resolve independently.
     */
    private val generationBatchId = mutableMapOf<String, String>()

    @Synchronized
    fun bufferMessage(senderKey: String, text: String) {
        val list = screenOffBuffer.getOrPut(senderKey) { mutableListOf() }
        list.add(BufferedMessage(text, System.currentTimeMillis()))
        Logger.d("Buffered message for $senderKey (buffer size now ${list.size})")
    }

    @Synchronized
    fun getAndClearBuffer(senderKey: String): List<BufferedMessage> {
        val list = screenOffBuffer[senderKey]?.toList() ?: emptyList()
        screenOffBuffer.remove(senderKey)
        return list
    }

    @Synchronized
    fun allBufferedSenders(): Set<String> = screenOffBuffer.keys.toSet()

    @Synchronized
    fun storeReplies(entryId: String, replies: List<String>) {
        // Always replace with a fresh immutable copy — never mutate or partially
        // merge into a previously stored list (shared refs caused stale chips).
        val repliesArray = replies.map { it }.toList()
        android.util.Log.d(
            "ScrollCat",
            "Storing replies for $entryId: count=${repliesArray.size}"
        )
        storedReplies[entryId] = repliesArray
        // Per-entry generation — leave any prior shared merge batch.
        generationBatchId.remove(entryId)
    }

    /** Attach the same reply set to every queued entry in a conversation (pre-panel merge). */
    @Synchronized
    fun storeRepliesForConversation(conversationKey: String, replies: List<String>) {
        val queue = messagesForConversation(conversationKey)
        if (queue.isEmpty()) return
        val batchId = UUID.randomUUID().toString()
        // Fresh copy per entryId so entries never share a mutable backing list
        for (entry in queue) {
            val repliesArray = replies.map { it }.toList()
            android.util.Log.d(
                TAG,
                "Storing replies for ${entry.entryId}: count=${repliesArray.size} batch=$batchId"
            )
            storedReplies[entry.entryId] = repliesArray
            generationBatchId[entry.entryId] = batchId
        }
        android.util.Log.d(
            TAG,
            "storeRepliesForConversation $conversationKey → ${queue.size} entr(y/ies) " +
                "batch=$batchId"
        )
    }

    /**
     * Pre-panel merge completed after the panel opened: fill only entries that still
     * have no chips. Never overwrite entryIds that already got (or will keep) their
     * own per-message generation results.
     */
    @Synchronized
    fun storeRepliesForConversationWhereEmpty(conversationKey: String, replies: List<String>) {
        val queue = messagesForConversation(conversationKey)
        if (queue.isEmpty()) return
        val batchId = UUID.randomUUID().toString()
        var filled = 0
        for (entry in queue) {
            val existing = storedReplies[entry.entryId]
            if (!existing.isNullOrEmpty()) continue
            val repliesArray = replies.map { it }.toList()
            storedReplies[entry.entryId] = repliesArray
            generationBatchId[entry.entryId] = batchId
            filled++
            android.util.Log.d(
                TAG,
                "Storing replies (whereEmpty) for ${entry.entryId}: " +
                    "count=${repliesArray.size} batch=$batchId"
            )
        }
        android.util.Log.d(
            TAG,
            "storeRepliesForConversationWhereEmpty $conversationKey → " +
                "filled=$filled / queue=${queue.size} batch=$batchId"
        )
    }

    /** All pending entries whose [ReplyableMessage.conversationKey] matches [conversationKey]. */
    @Synchronized
    fun messagesForConversation(conversationKey: String): List<ReplyableMessage> {
        val key = conversationKey.trim()
        if (key.isEmpty()) return emptyList()
        return messages.values.flatten().filter { it.conversationKey == key }
    }

    @Synchronized
    fun getStoredReplies(entryId: String): List<String>? {
        val result = storedReplies[entryId]?.toList()
        android.util.Log.d(
            "ScrollCat",
            "getStoredReplies looking for entryId: $entryId, found: ${result != null}, " +
                "count: ${result?.size ?: 0}"
        )
        return result
    }

    @Synchronized
    fun clearStoredReplies(entryId: String) {
        storedReplies.remove(entryId)
        generationBatchId.remove(entryId)
    }

    /**
     * Pending entries that share [entryId]'s interactive-merge [generationBatchId].
     * Returns only [entryId]'s message (or empty) when it has no shared batch, or when
     * the batch has a single member — callers treat size &lt; 2 as individual resolve.
     */
    @Synchronized
    fun getSharedGenerationBatch(entryId: String): List<ReplyableMessage> {
        val self = getByEntryId(entryId) ?: return emptyList()
        val batchId = generationBatchId[entryId] ?: return listOf(self)
        val mates = mutableListOf<ReplyableMessage>()
        for (queue in messages.values) {
            for (msg in queue) {
                if (generationBatchId[msg.entryId] == batchId) {
                    mates.add(msg)
                }
            }
        }
        return if (mates.size >= 2) mates else listOf(self)
    }

    // conversationKey -> FIFO queue of messages (oldest first); LinkedHashMap order = recency
    private val messages = LinkedHashMap<String, MutableList<ReplyableMessage>>()
    private val recentlySentReplies = mutableListOf<String>()

    /**
     * Prefer the most complete body text the notification exposes:
     * EXTRA_BIG_TEXT → EXTRA_TEXT_LINES → EXTRA_TEXT.
     * Title/subject stays in EXTRA_TITLE and is read separately.
     */
    fun extractMessageBody(extras: Bundle?): String {
        if (extras == null) return ""

        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?.toString()?.trim().orEmpty()
        if (bigText.isNotEmpty()) return bigText

        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (lines != null && lines.isNotEmpty()) {
            val joined = lines
                .mapNotNull { it?.toString()?.trim()?.takeIf { line -> line.isNotEmpty() } }
                .joinToString("\n")
            if (joined.isNotEmpty()) return joined
        }

        return extras.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()?.trim().orEmpty()
    }

    /**
     * Extracts a replyable message from a posted notification and **appends** it
     * to that conversation's queue (does not overwrite prior unviewed entries).
     */
    @Synchronized
    fun capture(sbn: StatusBarNotification): ReplyableMessage? {
        val packageName = sbn.packageName

        if (packageName == "com.skilaparthi.scrollcat" ||
            packageName == "com.skilaparthi.scrollcat.debug") return null

        if (packageName !in MESSAGING_APPS) return null
        val notification = sbn.notification ?: return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        val remoteInputAction = findReplyAction(notification)
        val extras = notification.extras
        val rawTitle = extras?.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim().orEmpty()
        val senderName = normalizeSenderName(rawTitle)
        val messageText = extractMessageBody(extras)

        if (senderName.isBlank()) return null
        if (senderName == "You") return null
        if (senderName == "you") return null

        val recentlySent = getRecentlySentReplies()
        if (recentlySent.contains(messageText.trim())) return null

        if (messageText.isBlank()) return null

        Logger.d(
            "Capturing notification from: $packageName senderPresent=${senderName.isNotBlank()} " +
                "messageChars=${messageText.length}"
        )
        val hasRemoteInput = remoteInputAction != null
        Logger.d("Has RemoteInput: $hasRemoteInput")

        val contentIntent = sbn.notification?.contentIntent
        val actionIntent = remoteInputAction?.actionIntent
        val remoteInputs = remoteInputAction?.remoteInputs ?: emptyArray()

        val msg = ReplyableMessage(
            entryId = UUID.randomUUID().toString(),
            notificationKey = sbn.key,
            notificationId = sbn.id,
            packageName = packageName,
            sender = senderName,
            message = messageText,
            timestamp = sbn.postTime,
            hasRemoteInput = hasRemoteInput,
            actionIntent = actionIntent,
            remoteInputs = remoteInputs,
            contentIntent = contentIntent
        )

        // TEMP DIAG: compare storage-time key vs raw title / normalized sender
        android.util.Log.e(
            TAG,
            "###CONV_KEY_STORE_DEBUG### rawTitle='$rawTitle' " +
                "normalizedSender='$senderName' " +
                "conversationKey='${msg.conversationKey}' " +
                "pkg='$packageName' notifKey='${sbn.key}' " +
                "msgChars=${messageText.length}"
        )

        val queue = takeAndMergeQueueForConversation(msg.conversationKey)
        val previous = queue.lastOrNull()
        if (previous != null &&
            previous.message == msg.message &&
            previous.notificationKey == msg.notificationKey
        ) {
            // Identical re-post of the same notification body — keep existing entry
            messages[msg.conversationKey] = queue
            android.util.Log.d(
                TAG,
                "ReplyStore skip duplicate for ${msg.conversationKey}"
            )
            return null
        }
        queue.add(msg)
        messages[msg.conversationKey] = queue
        val liveCount = countForConversation(msg.conversationKey)
        val allKeys = getAll().map { it.conversationKey }
        android.util.Log.e(
            TAG,
            "###CONV_KEY_STORE_DEBUG### APPEND key='${msg.conversationKey}' " +
                "queueSize=${queue.size} liveCountForKey=$liveCount total=${count()} " +
                "allPendingKeys=$allKeys distinctKeys=${allKeys.distinct()}"
        )
        android.util.Log.d(
            TAG,
            "ReplyStore append for ${msg.conversationKey}: queue size=${queue.size}, total=${count()}"
        )

        while (count() > MAX_ENTRIES) {
            evictOldestEntry()
        }
        return msg
    }

    /**
     * Pull every map bucket that belongs to [key] (including legacy map keys from before
     * stronger normalization) into one FIFO list, removing those buckets from [messages].
     */
    private fun takeAndMergeQueueForConversation(key: String): MutableList<ReplyableMessage> {
        val merged = mutableListOf<ReplyableMessage>()
        val removedMapKeys = mutableListOf<String>()
        val iterator = messages.entries.iterator()
        while (iterator.hasNext()) {
            val (mapKey, queue) = iterator.next()
            if (mapKey == key || queue.any { it.conversationKey == key }) {
                if (mapKey != key) removedMapKeys.add(mapKey)
                merged.addAll(queue)
                iterator.remove()
            }
        }
        if (removedMapKeys.isNotEmpty()) {
            android.util.Log.e(
                TAG,
                "###CONV_KEY_MERGE_DEBUG### merged alias mapKeys=$removedMapKeys into key=$key " +
                    "combinedSize=${merged.size} — title/key fragmentation was splitting one chat"
            )
        }
        // Preserve chronological order within the conversation.
        merged.sortBy { it.timestamp }
        return merged
    }

    private fun evictOldestEntry() {
        val oldestConv = messages.keys.firstOrNull() ?: return
        val queue = messages[oldestConv] ?: return
        if (queue.isEmpty()) {
            messages.remove(oldestConv)
            return
        }
        val removed = queue.removeAt(0)
        storedReplies.remove(removed.entryId)
        if (queue.isEmpty()) messages.remove(oldestConv)
    }

    @Synchronized
    fun markPriority(notificationKey: String, priority: Boolean) {
        for (queue in messages.values) {
            for (i in queue.indices) {
                if (queue[i].notificationKey == notificationKey) {
                    queue[i] = queue[i].copy(priority = priority)
                }
            }
        }
    }

    private fun findReplyAction(notification: Notification): Notification.Action? {
        notification.actions?.forEach { action ->
            if (action.remoteInputs?.any { it.allowFreeFormInput } == true) return action
        }
        Notification.WearableExtender(notification).actions.forEach { action ->
            if (action.remoteInputs?.any { it.allowFreeFormInput } == true) return action
        }
        return null
    }

    @Synchronized
    fun getLatest(): ReplyableMessage? = getAll().firstOrNull()

    /**
     * All pending messages (flattened queues), newest first within priority groups.
     * Multiple entries from the same sender appear as separate items.
     */
    @Synchronized
    fun getAll(): List<ReplyableMessage> {
        return messages.values
            .flatten()
            .sortedWith(
                compareByDescending<ReplyableMessage> { it.priority }
                    .thenByDescending { it.timestamp }
            )
    }

    /** Total pending **messages** across all senders. */
    @Synchronized
    fun count(): Int = messages.values.sumOf { it.size }

    @Synchronized
    fun countForConversation(conversationKey: String): Int {
        val key = conversationKey.trim()
        if (key.isEmpty()) return 0
        return messages.values.sumOf { queue -> queue.count { it.conversationKey == key } }
    }

    @Synchronized
    fun countForNotificationKey(notificationKey: String): Int {
        var n = 0
        for (queue in messages.values) {
            n += queue.count { it.notificationKey == notificationKey }
        }
        return n
    }

    @Synchronized
    fun hasPriorityPending(): Boolean = messages.values.any { queue -> queue.any { it.priority } }

    @Synchronized
    fun getByKey(notificationKey: String): ReplyableMessage? {
        for (queue in messages.values) {
            queue.firstOrNull { it.notificationKey == notificationKey }?.let { return it }
        }
        return null
    }

    @Synchronized
    fun getByEntryId(entryId: String): ReplyableMessage? {
        for (queue in messages.values) {
            queue.firstOrNull { it.entryId == entryId }?.let { return it }
        }
        return null
    }

    /** Remove a single queue entry by its unique [entryId]. */
    @Synchronized
    fun removeEntry(entryId: String) {
        val iterator = messages.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val removed = entry.value.removeAll { it.entryId == entryId }
            if (removed) {
                storedReplies.remove(entryId)
                generationBatchId.remove(entryId)
                if (entry.value.isEmpty()) iterator.remove()
                return
            }
        }
    }

    /**
     * Remove every queue entry that shares this StatusBarNotification key
     * (e.g. when the OS notification is dismissed).
     */
    @Synchronized
    fun remove(notificationKey: String) {
        val iterator = messages.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val toClear = entry.value.filter { it.notificationKey == notificationKey }
            entry.value.removeAll { it.notificationKey == notificationKey }
            toClear.forEach {
                storedReplies.remove(it.entryId)
                generationBatchId.remove(it.entryId)
            }
            if (entry.value.isEmpty()) iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        messages.clear()
        storedReplies.clear()
        generationBatchId.clear()
        recentlySentReplies.clear()
    }

    const val DEMO_PACKAGE = "com.skilaparthi.scrollcat.demo"
    const val DEMO_NOTIFICATION_KEY = "scrollcat_onboarding_demo"
    const val DEMO_SENDER = "Demo"
    const val DEMO_MESSAGE = "Hey, how's your day today?"

    val DEMO_REPLIES = listOf(
        "Pretty good, thanks!",
        "Busy but good!",
        "Can't complain!"
    )

    @Synchronized
    fun putDemo(): ReplyableMessage {
        remove(DEMO_NOTIFICATION_KEY)
        messages.remove("$DEMO_PACKAGE|$DEMO_SENDER")
        val msg = ReplyableMessage(
            entryId = "demo-entry",
            notificationKey = DEMO_NOTIFICATION_KEY,
            notificationId = -4242,
            packageName = DEMO_PACKAGE,
            sender = DEMO_SENDER,
            message = DEMO_MESSAGE,
            timestamp = System.currentTimeMillis(),
            hasRemoteInput = true,
            actionIntent = null,
            remoteInputs = emptyArray(),
            contentIntent = null,
            priority = false
        )
        messages[msg.conversationKey] = mutableListOf(msg)
        return msg
    }

    @Synchronized
    fun clearDemo() {
        remove(DEMO_NOTIFICATION_KEY)
        messages.remove("$DEMO_PACKAGE|$DEMO_SENDER")
        storedReplies.remove("demo-entry")
    }

    fun isDemoMessage(message: ReplyableMessage): Boolean =
        message.packageName == DEMO_PACKAGE || message.notificationKey == DEMO_NOTIFICATION_KEY

    fun trackSentReply(replyText: String) {
        recentlySentReplies.add(replyText.trim())
        if (recentlySentReplies.size > 10) {
            recentlySentReplies.removeAt(0)
        }
    }

    fun getRecentlySentReplies(): List<String> = recentlySentReplies
}
