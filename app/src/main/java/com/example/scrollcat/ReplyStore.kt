package com.example.scrollcat

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.service.notification.StatusBarNotification

/**
 * In-memory store of the last replyable messages (max 20).
 * One entry per conversation (package + sender), always holding the newest
 * message and the data needed to send a RemoteInput reply.
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
    private val storedReplies = mutableMapOf<String, List<String>>()

    @Synchronized
    fun bufferMessage(senderKey: String, text: String) {
        val list = screenOffBuffer.getOrPut(senderKey) { mutableListOf() }
        list.add(BufferedMessage(text, System.currentTimeMillis()))
        Logger.d("Buffered message for $senderKey (buffer size now ${list.size}): $text")
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
    fun storeReplies(senderKey: String, replies: List<String>) {
        android.util.Log.d("ScrollCat", "storeReplies called for key: $senderKey")
        storedReplies[senderKey] = replies
    }

    @Synchronized
    fun getStoredReplies(senderKey: String): List<String>? {
        val result = storedReplies[senderKey]
        android.util.Log.d("ScrollCat", "getStoredReplies looking for key: $senderKey, found: ${result != null}")
        return result
    }

    @Synchronized
    fun clearStoredReplies(senderKey: String) {
        storedReplies.remove(senderKey)
    }

    // conversationKey -> newest message, insertion order = oldest first
    private val messages = LinkedHashMap<String, ReplyableMessage>()
    private val recentlySentReplies = mutableListOf<String>()

    /**
     * Extracts a replyable message from a posted notification and stores it.
     * Returns null if the app isn't a messaging/email app or the notification
     * lacks usable message content.
     */
    @Synchronized
    fun capture(sbn: StatusBarNotification): ReplyableMessage? {
        val packageName = sbn.packageName

        // Ignore notifications from our own app
        if (packageName == "com.example.scrollcat" ||
            packageName == "com.example.scrollcat.debug") return null

        if (packageName !in MESSAGING_APPS) return null
        val notification = sbn.notification ?: return null
        // Group summaries duplicate the child messages and often lack a usable reply
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        val remoteInputAction = findReplyAction(notification)
        val extras = notification.extras
        val senderName = normalizeSenderName(
            extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        )
        val messageText = (extras?.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()

        // Ignore our own sent messages
        // Instagram format: "karthik_.siva: Karthik Siva 🍂" for others
        // But sent messages show as the message content matching what we just sent
        if (senderName.isNullOrBlank()) return null
        if (senderName == "You") return null
        if (senderName == "you") return null

        // For Instagram specifically - if the message text exactly matches
        // something we recently sent, ignore it
        val recentlySent = getRecentlySentReplies()
        if (recentlySent.contains(messageText.trim())) return null

        // Ignore if message is empty
        if (messageText.isNullOrBlank()) return null

        Logger.d("Capturing notification from: $packageName sender: $senderName message: $messageText")
        val hasRemoteInput = remoteInputAction != null
        // Store anyway — for no-RemoteInput notifications, "Reply in app" will be the only send option
        Logger.d("Has RemoteInput: $hasRemoteInput")

        val contentIntent = sbn.notification?.contentIntent
        val actionIntent = remoteInputAction?.actionIntent
        val remoteInputs = remoteInputAction?.remoteInputs ?: emptyArray()

        val msg = ReplyableMessage(
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
        // Re-insert so this conversation moves to the newest position
        messages.remove(msg.conversationKey)
        messages[msg.conversationKey] = msg

        // Evict oldest entries beyond the cap
        while (messages.size > MAX_ENTRIES) {
            val oldestKey = messages.keys.firstOrNull() ?: break
            messages.remove(oldestKey)
        }
        return msg
    }

    @Synchronized
    fun markPriority(notificationKey: String, priority: Boolean) {
        val entry = messages.entries.firstOrNull { it.value.notificationKey == notificationKey } ?: return
        entry.setValue(entry.value.copy(priority = priority))
    }

    private fun findReplyAction(notification: Notification): Notification.Action? {
        notification.actions?.forEach { action ->
            if (action.remoteInputs?.any { it.allowFreeFormInput } == true) return action
        }
        // Some apps only expose the reply action via the wearable extender
        Notification.WearableExtender(notification).actions.forEach { action ->
            if (action.remoteInputs?.any { it.allowFreeFormInput } == true) return action
        }
        return null
    }

    /** Most recent replyable entry, or null when nothing is pending. */
    @Synchronized
    fun getLatest(): ReplyableMessage? = getAll().firstOrNull()

    /** All pending messages, newest first. */
    @Synchronized
    fun getAll(): List<ReplyableMessage> {
        return messages.values
            .toList()
            .asReversed()
            .sortedByDescending { it.priority }
    }

    @Synchronized
    fun count(): Int = messages.size

    @Synchronized
    fun hasPriorityPending(): Boolean = messages.values.any { it.priority }

    /** Lookup by StatusBarNotification key before removal. */
    @Synchronized
    fun getByKey(notificationKey: String): ReplyableMessage? {
        return messages.values.firstOrNull { it.notificationKey == notificationKey }
    }

    /** Remove after a reply was sent (or the notification went away). */
    @Synchronized
    fun remove(notificationKey: String) {
        val iterator = messages.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.notificationKey == notificationKey) iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        messages.clear()
        recentlySentReplies.clear()
    }

    /** Package used only for the scripted onboarding demo (not a real app). */
    const val DEMO_PACKAGE = "com.example.scrollcat.demo"
    const val DEMO_NOTIFICATION_KEY = "scrollcat_onboarding_demo"
    const val DEMO_SENDER = "Demo"
    const val DEMO_MESSAGE = "Hey, how's your day today?"

    val DEMO_REPLIES = listOf(
        "Pretty good, thanks!",
        "Busy but good!",
        "Can't complain!"
    )

    /** Inserts a fake replyable message for the onboarding demo (no real notification). */
    @Synchronized
    fun putDemo(): ReplyableMessage {
        remove(DEMO_NOTIFICATION_KEY)
        messages.remove("$DEMO_PACKAGE|$DEMO_SENDER")
        val msg = ReplyableMessage(
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
        messages[msg.conversationKey] = msg
        return msg
    }

    @Synchronized
    fun clearDemo() {
        remove(DEMO_NOTIFICATION_KEY)
        messages.remove("$DEMO_PACKAGE|$DEMO_SENDER")
    }

    fun isDemoMessage(message: ReplyableMessage): Boolean =
        message.packageName == DEMO_PACKAGE || message.notificationKey == DEMO_NOTIFICATION_KEY

    fun trackSentReply(replyText: String) {
        recentlySentReplies.add(replyText.trim())
        // Keep only last 10 sent replies
        if (recentlySentReplies.size > 10) {
            recentlySentReplies.removeAt(0)
        }
    }

    fun getRecentlySentReplies(): List<String> = recentlySentReplies
}
