package com.example.scrollcat

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class CatNotificationListener : NotificationListenerService() {

    companion object {
        var instance: CatNotificationListener? = null
            private set
        private const val TAG = "ScrollCat"
    }

    private val lastNotificationTime = mutableMapOf<String, Long>()
    private val DEBOUNCE_MS = 2000L // ignore same app within 2 seconds

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return

        // Don't process notifications if cat overlay is not active
        if (OverlayService.instance == null) return

        // Ignore our own app and system
        if (pkg == "com.example.scrollcat") return
        if (pkg == "android") return
        if (pkg == "com.android.systemui") return

        // Capture replyable messages (DMs with a RemoteInput reply action)
        // BEFORE debouncing, so a second message from a different person in
        // the same app is never lost.
        val replyable = ReplyStore.capture(sbn)
        if (replyable != null) {
            Log.d(TAG, "Replyable message from ${replyable.sender} via $pkg")

            // Auto-reply rules: if an enabled rule's keywords match, the cat
            // answers immediately without user interaction.
            val rule = AutoReplyManager.findMatch(this, replyable.message)
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
        }

        // Debounce — ignore if same app notified within 2 seconds
        val now = System.currentTimeMillis()
        val lastTime = lastNotificationTime[pkg] ?: 0L
        if (now - lastTime < DEBOUNCE_MS) return
        lastNotificationTime[pkg] = now

        // Extract full notification text
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""

        // Combine all text for matching
        val fullText = "$title $text $bigText".trim()

        Log.d(TAG, "Full notification from $pkg: $fullText | keywords: ${SettingsManager.getWatchedKeywords(this)}")

        // Badge on filter match, or on any message the cat can reply to
        if (replyable != null || SettingsManager.shouldNotify(this, pkg, fullText)) {
            OverlayService.instance?.incrementBadge()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // If the user handled the conversation elsewhere, drop the stale entry
        sbn?.key?.let { ReplyStore.remove(it) }
        super.onNotificationRemoved(sbn)
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }
}
