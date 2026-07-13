package com.example.scrollcat

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * In-memory store of messages the user can reply to.
 * One entry per conversation (package + sender), always holding the newest
 * message and its RemoteInput reply action from the notification.
 */
object ReplyStore {

    data class ReplyableMessage(
        val notificationKey: String,
        val packageName: String,
        val sender: String,
        val message: String,
        val timestamp: Long,
        val replyAction: Notification.Action
    ) {
        val conversationKey: String get() = "$packageName|$sender"
    }

    // conversationKey -> newest message, insertion order = oldest first
    private val messages = LinkedHashMap<String, ReplyableMessage>()

    /**
     * Extracts a replyable message from a posted notification.
     * Returns null if the notification has no free-form reply action.
     */
    @Synchronized
    fun capture(sbn: StatusBarNotification): ReplyableMessage? {
        val notification = sbn.notification ?: return null
        // Group summaries duplicate the child messages and often lack a usable reply
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

        val action = findReplyAction(notification) ?: return null

        val extras = notification.extras
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
            replyAction = action
        )
        // Re-insert so this conversation moves to the newest position
        messages.remove(msg.conversationKey)
        messages[msg.conversationKey] = msg
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

    /** All pending messages, newest first. */
    @Synchronized
    fun all(): List<ReplyableMessage> = messages.values.toList().asReversed()

    @Synchronized
    fun count(): Int = messages.size

    @Synchronized
    fun remove(message: ReplyableMessage) {
        messages.remove(message.conversationKey)
    }

    @Synchronized
    fun removeByNotificationKey(key: String) {
        val iterator = messages.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.notificationKey == key) iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        messages.clear()
    }
}
