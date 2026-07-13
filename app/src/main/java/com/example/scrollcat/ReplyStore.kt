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

    /** Apps whose DMs the cat offers smart replies for. */
    val MESSAGING_APPS = setOf(
        "com.whatsapp",                      // WhatsApp
        "com.whatsapp.w4b",                  // WhatsApp Business
        "com.instagram.android",             // Instagram
        "org.telegram.messenger",            // Telegram
        "com.discord",                       // Discord
        "com.facebook.orca",                 // Messenger
        "com.samsung.android.messaging",     // Samsung Messages
        "com.google.android.apps.messaging"  // Google Messages
    )

    data class ReplyableMessage(
        val notificationKey: String,
        val packageName: String,
        val sender: String,
        val message: String,
        val timestamp: Long,
        val actionIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>,
        val contentIntent: PendingIntent?
    ) {
        val conversationKey: String get() = "$packageName|$sender"
    }

    // conversationKey -> newest message, insertion order = oldest first
    private val messages = LinkedHashMap<String, ReplyableMessage>()

    /**
     * Extracts a replyable message from a posted notification and stores it.
     * Returns null if the app isn't a messaging app or the notification has
     * no free-form reply action.
     */
    @Synchronized
    fun capture(sbn: StatusBarNotification): ReplyableMessage? {
        if (sbn.packageName !in MESSAGING_APPS) return null
        val notification = sbn.notification ?: return null
        // Group summaries duplicate the child messages and often lack a usable reply
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        val action = findReplyAction(notification) ?: return null
        val actionIntent = action.actionIntent ?: return null
        val remoteInputs = action.remoteInputs
        if (remoteInputs.isNullOrEmpty()) return null

        val extras = notification.extras ?: return null
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
        if (sender.isEmpty() || text.isEmpty()) return null

        val msg = ReplyableMessage(
            notificationKey = sbn.key,
            packageName = sbn.packageName,
            sender = sender,
            message = text,
            timestamp = sbn.postTime,
            actionIntent = actionIntent,
            remoteInputs = remoteInputs,
            contentIntent = notification.contentIntent
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
    fun getLatest(): ReplyableMessage? = messages.values.lastOrNull()

    /** All pending messages, newest first. */
    @Synchronized
    fun getAll(): List<ReplyableMessage> = messages.values.toList().asReversed()

    @Synchronized
    fun count(): Int = messages.size

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
    }
}
