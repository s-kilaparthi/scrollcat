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

        // Debounce — ignore if same app notified within 2 seconds
        val now = System.currentTimeMillis()
        val lastTime = lastNotificationTime[pkg] ?: 0L
        if (now - lastTime < DEBOUNCE_MS) return
        lastNotificationTime[pkg] = now

        // Ignore our own app and system
        if (pkg == "com.example.scrollcat") return
        if (pkg == "android") return
        if (pkg == "com.android.systemui") return

        // Extract full notification text
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""

        // Combine all text for matching
        val fullText = "$title $text $bigText".trim()

        Log.d(TAG, "Full notification from $pkg: $fullText | keywords: ${SettingsManager.getWatchedKeywords(this)}")

        if (SettingsManager.shouldNotify(this, pkg, fullText)) {
            OverlayService.instance?.incrementBadge()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }
}
