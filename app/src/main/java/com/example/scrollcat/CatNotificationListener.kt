package com.example.scrollcat

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.*

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

    private val pregenScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pregeneratedReplies = mutableMapOf<String, List<String>>()
    private val pregenInProgress = mutableSetOf<String>()

    private val lastNotificationTime = mutableMapOf<String, Long>()
    private val processedKeys = mutableMapOf<String, Long>()
    private val DEBOUNCE_MS = 2000L // ignore same app/key within 2 seconds

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        android.util.Log.d("ScrollCat", "Notification received from: ${sbn.packageName}")
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
            Log.d(TAG, "Replyable message from ${replyable.sender} via $pkg")

            // Pre-generate replies immediately in background
            val entry = ReplyStore.getLatest() ?: replyable
            val cacheKey = "${entry.packageName}_${entry.sender}_${entry.message.take(50)}"

            if (cacheKey !in pregenInProgress && cacheKey !in pregeneratedReplies) {
                pregenInProgress.add(cacheKey)
                android.util.Log.d("ScrollCat", "Pre-generating replies for: ${entry.sender}")

                pregenScope.launch {
                    try {
                        val generator = AiReplyGenerator(this@CatNotificationListener)
                        generator.generateReplies(entry.sender, entry.message) { replies, _ ->
                            pregeneratedReplies[cacheKey] = replies
                            pregenInProgress.remove(cacheKey)
                            android.util.Log.d("ScrollCat", "Pre-generated replies ready for: ${entry.sender}")
                        }
                    } catch (e: Exception) {
                        pregenInProgress.remove(cacheKey)
                        android.util.Log.e("ScrollCat", "Pre-generation failed: ${e.message}")
                    }
                }
            }

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

    fun getPregeneratedReplies(packageName: String, senderName: String, messageText: String): List<String>? {
        val cacheKey = "${packageName}_${senderName}_${messageText.take(50)}"
        return pregeneratedReplies[cacheKey]
    }

    fun clearPregeneratedReplies(packageName: String, senderName: String, messageText: String) {
        val cacheKey = "${packageName}_${senderName}_${messageText.take(50)}"
        pregeneratedReplies.remove(cacheKey)
        pregenInProgress.remove(cacheKey)
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
            clearPregeneratedReplies(entry.packageName, entry.sender, entry.message)
        }
        ReplyStore.remove(sbn.key)
        processedKeys.remove(sbn.key)
        super.onNotificationRemoved(sbn)
    }

    override fun onDestroy() {
        pregenScope.cancel()
        pregeneratedReplies.clear()
        super.onDestroy()
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }
}
