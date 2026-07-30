package com.example.scrollcat

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.os.Bundle
import android.service.notification.StatusBarNotification
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

    private fun normalizeSenderName(rawName: String): String {
        return rawName.replace(Regex("\\s*\\(\\d+\\s*messages?\\)", RegexOption.IGNORE_CASE), "").trim()
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
        val conversationKey: String get() = "$packageName|$sender"
    }

    data class BufferedMessage(val text: String, val timestamp: Long)

    private val screenOffBuffer = mutableMapOf<String, MutableList<BufferedMessage>>()
    /** Generated chips keyed by [ReplyableMessage.entryId]. */
    private val storedReplies = mutableMapOf<String, List<String>>()

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
    }

    /** Attach the same reply set to every queued entry in a conversation (pre-panel merge). */
    @Synchronized
    fun storeRepliesForConversation(conversationKey: String, replies: List<String>) {
        val queue = messages[conversationKey] ?: return
        // Fresh copy per entryId so entries never share a mutable backing list
        for (entry in queue) {
            val repliesArray = replies.map { it }.toList()
            android.util.Log.d(
                "ScrollCat",
                "Storing replies for ${entry.entryId}: count=${repliesArray.size}"
            )
            storedReplies[entry.entryId] = repliesArray
        }
        android.util.Log.d(
            "ScrollCat",
            "storeRepliesForConversation $conversationKey → ${queue.size} entr(y/ies)"
        )
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

        if (packageName == "com.example.scrollcat" ||
            packageName == "com.example.scrollcat.debug") return null

        if (packageName !in MESSAGING_APPS) return null
        val notification = sbn.notification ?: return null
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        val remoteInputAction = findReplyAction(notification)
        val extras = notification.extras
        val senderName = normalizeSenderName(
            extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        )
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

        val queue = messages.remove(msg.conversationKey) ?: mutableListOf()
        val previous = queue.lastOrNull()
        if (previous != null &&
            previous.message == msg.message &&
            previous.notificationKey == msg.notificationKey
        ) {
            // Identical re-post of the same notification body — keep existing entry
            messages[msg.conversationKey] = queue
            android.util.Log.d(
                "ScrollCat",
                "ReplyStore skip duplicate for ${msg.conversationKey}"
            )
            return null
        }
        queue.add(msg)
        messages[msg.conversationKey] = queue
        android.util.Log.d(
            "ScrollCat",
            "ReplyStore append for ${msg.conversationKey}: queue size=${queue.size}, total=${count()}"
        )

        while (count() > MAX_ENTRIES) {
            evictOldestEntry()
        }
        return msg
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
    fun countForConversation(conversationKey: String): Int =
        messages[conversationKey]?.size ?: 0

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
            toClear.forEach { storedReplies.remove(it.entryId) }
            if (entry.value.isEmpty()) iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        messages.clear()
        storedReplies.clear()
        recentlySentReplies.clear()
    }

    const val DEMO_PACKAGE = "com.example.scrollcat.demo"
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
