package com.example.scrollcat

import android.content.Context
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class CatNotificationListener : NotificationListenerService() {

    companion object {
        var instance: CatNotificationListener? = null
            private set
        private const val TAG = "ScrollCat"
        private val OWN_PACKAGES = setOf(
            "com.example.scrollcat",
            "com.example.scrollcat.debug"
        )
    }

    private val lastNotificationTime = mutableMapOf<String, Long>()
    private val processedKeys = mutableMapOf<String, Long>()
    private val DEBOUNCE_MS = 2000L // ignore same app/key within 2 seconds

    private fun normalizeSenderName(rawName: String): String {
        return rawName.replace(Regex("\\s*\\(\\d+\\s*messages?\\)", RegexOption.IGNORE_CASE), "").trim()
    }

    private fun buildSenderKey(packageName: String, notificationId: Int, senderName: String): String {
        return if (packageName == "com.whatsapp") {
            "$packageName:${normalizeSenderName(senderName)}"
        } else {
            "$packageName:$notificationId"
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        Logger.d("Notification received from: ${sbn.packageName}")
        val pkg = sbn.packageName ?: return
        val notificationKey = sbn.key

        // Don't process notifications if cat overlay is not active
        if (OverlayService.instance == null) return

        // Ignore our own app (release + debug) and system packages
        if (pkg in OWN_PACKAGES) return
        if (pkg == "android") return
        if (pkg == "com.android.systemui") return

        // Prevent duplicate processing of the same notification update
        val now = System.currentTimeMillis()
        val lastKeyTime = processedKeys[notificationKey] ?: 0L
        if (now - lastKeyTime < DEBOUNCE_MS) return
        processedKeys[notificationKey] = now
        pruneProcessedKeys(now)

        // Capture replyable messages (DMs with a RemoteInput reply action)
        // BEFORE app-level debouncing, so a second message from a different
        // person in the same app is never lost.
        val replyable = ReplyStore.capture(sbn)
        if (replyable != null) {
            val senderName = replyable.sender
            if (!replyable.hasRemoteInput) {
                Logger.d("Skipping non-replyable notification from $senderName via $pkg (no RemoteInput)")
                return
            }

            Log.d(TAG, "Replyable message from ${replyable.sender} via $pkg")

            // Auto-reply rules: if an enabled rule's keywords match, the cat
            // answers immediately without user interaction.
            val rule = AutoReplyManager.findMatch(this, replyable.message, replyable.sender)
            if (rule != null) {
                Log.i(TAG, "Auto-reply rule matched for ${replyable.sender}: ${rule.triggers}")
                val sent = ReplySender.send(this, replyable, rule.reply)
                if (sent) {
                    ReplyStore.remove(replyable.notificationKey)
                    StatsTracker.recordAutoReply(this)
                    OverlayService.instance?.showCatMessage("Auto-replied to ${replyable.sender} ✓")
                    return // handled — no badge needed
                }
            }

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val senderKey = buildSenderKey(pkg, sbn.id, senderName)
            Logger.d("senderKey=$senderKey (raw title was: $senderName)")
            val messageText = replyable.message

            if (powerManager.isInteractive) {
                val pending = ReplyStore.getAndClearBuffer(senderKey)
                val combinedText = if (pending.isNotEmpty()) {
                    (pending.map { it.text } + messageText).joinToString("\n")
                } else {
                    messageText
                }
                AiReplyGenerator.generateReplies(this, pkg, senderName, combinedText) { replies ->
                    ReplyStore.storeReplies(senderKey, replies)
                }
            } else {
                ReplyStore.bufferMessage(senderKey, messageText)
            }
        }

        // Debounce badge/filter processing per app
        val lastTime = lastNotificationTime[pkg] ?: 0L
        if (now - lastTime < DEBOUNCE_MS) return
        lastNotificationTime[pkg] = now

        // Extract full notification text
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        // Combine all text for matching
        val fullText = "$title $text $bigText".trim()

        Log.d(TAG, "Full notification from $pkg: $fullText | keywords: ${SettingsManager.getWatchedKeywords(this)}")

        // Badge on filter match, or on any message the cat can reply to
        if (replyable != null || SettingsManager.shouldNotify(this, pkg, fullText)) {
            OverlayService.instance?.incrementBadge()
        }
    }

    fun getPregeneratedReplies(
        packageName: String,
        notificationId: Int,
        senderName: String,
        messageText: String
    ): List<String>? {
        val senderKey = buildSenderKey(packageName, notificationId, senderName)
        return ReplyStore.getStoredReplies(senderKey)
    }

    fun clearPregeneratedReplies(
        packageName: String,
        notificationId: Int,
        senderName: String,
        messageText: String
    ) {
        val senderKey = buildSenderKey(packageName, notificationId, senderName)
        ReplyStore.clearStoredReplies(senderKey)
    }

    private fun pruneProcessedKeys(now: Long) {
        if (processedKeys.size <= 100) return
        processedKeys.entries.removeAll { now - it.value > DEBOUNCE_MS * 5 }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: run {
            super.onNotificationRemoved(sbn)
            return
        }
        val entry = ReplyStore.getByKey(sbn.key)
        if (entry != null) {
            clearPregeneratedReplies(entry.packageName, entry.notificationId, entry.sender, entry.message)
        }
        ReplyStore.remove(sbn.key)
        processedKeys.remove(sbn.key)
        super.onNotificationRemoved(sbn)
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }
}
