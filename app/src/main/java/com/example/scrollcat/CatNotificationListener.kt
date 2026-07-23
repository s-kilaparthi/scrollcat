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
    private val interactiveMessageBuffers = mutableMapOf<String, MutableList<ReplyStore.BufferedMessage>>()
    private val replyPanelOpenedAt = mutableMapOf<String, Long>()
    private val pendingBadgeSenderKeys = mutableSetOf<String>()
    private val activeInteractiveGenerationTokens = mutableMapOf<String, MutableSet<Long>>()
    private val cancelledInteractiveGenerationTokens = mutableSetOf<Long>()
    private var nextInteractiveGenerationToken = 0L

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

    private fun isIgnoredChat(senderName: String): Boolean {
        val normalizedSender = normalizeSenderName(senderName).lowercase()
        return SettingsManager.getIgnoredChats(this).any { ignored ->
            val normalizedIgnored = normalizeSenderName(ignored).lowercase()
            normalizedIgnored.isNotBlank() &&
                (normalizedSender == normalizedIgnored ||
                    normalizedSender.contains(normalizedIgnored) ||
                    normalizedIgnored.contains(normalizedSender))
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

        // Apps filter: empty list = watch everything; otherwise only selected packages
        val selectedApps = SettingsManager.getWatchedApps(this)
        val isAllowed = selectedApps.isEmpty() ||
            selectedApps.any { it.equals(pkg, ignoreCase = true) }
        Logger.d("App filter check for $pkg - selectedApps: $selectedApps, allowed: $isAllowed")
        if (!isAllowed) return

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
                ReplyStore.remove(replyable.notificationKey)
                return
            }
            if (isIgnoredChat(senderName)) {
                Log.d(TAG, "Skipping ignored chat: $senderName")
                ReplyStore.remove(replyable.notificationKey)
                return
            }

            Log.d(TAG, "Replyable message from ${replyable.sender} via $pkg")
            val messageText = replyable.message
            val priority = SettingsManager.matchesPersonOrKeyword(this, pkg, "$senderName $messageText")
            ReplyStore.markPriority(replyable.notificationKey, priority)

            // Auto-reply rules: if an enabled rule's keywords match, the cat
            // answers immediately without user interaction.
            val rule = AutoReplyManager.findMatch(this, messageText, replyable.sender)
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

            updateReplyableBadge(senderKey)
            if (powerManager.isInteractive) {
                val pending = ReplyStore.getAndClearBuffer(senderKey)
                val combinedText = AiReplyGenerator.mergeMessageTexts(pending, messageText)
                handleInteractiveMessage(pkg, senderName, senderKey, combinedText, now)
            } else {
                ReplyStore.bufferMessage(senderKey, messageText)
            }
            return
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

        // Badge on filter match for non-replyable notifications
        if (SettingsManager.shouldNotify(this, pkg, fullText)) {
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
        replyPanelOpenedAt[senderKey] = System.currentTimeMillis()
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
        clearInteractiveState(senderKey)
    }

    private fun updateReplyableBadge(senderKey: String) {
        val isNewPendingSender = pendingBadgeSenderKeys.add(senderKey)
        if (isNewPendingSender) {
            OverlayService.instance?.incrementBadge()
        }
        // Always sync an open reply panel — badge may not increment for an already-tracked
        // senderKey, but ReplyStore still changed and other senders may need the ↓ indicator.
        OverlayService.instance?.onReplyablesChanged()
        android.util.Log.d(
            "ScrollCat",
            "Badge count after update for $senderKey: ${pendingBadgeSenderKeys.size}, total pending senders: ${pendingBadgeSenderKeys.size}"
        )
    }

    private fun pruneProcessedKeys(now: Long) {
        if (processedKeys.size <= 100) return
        processedKeys.entries.removeAll { now - it.value > DEBOUNCE_MS * 5 }
    }

    private fun handleInteractiveMessage(
        packageName: String,
        senderName: String,
        senderKey: String,
        messageText: String,
        now: Long
    ) {
        val panelAlreadyOpened = replyPanelOpenedAt.containsKey(senderKey)
        android.util.Log.d(
            "ScrollCat",
            "Interactive merge check for $senderKey - panelSeen: $panelAlreadyOpened"
        )

        if (!panelAlreadyOpened) {
            android.util.Log.d("ScrollCat", "Interactive merge: accumulating messages for $senderKey")
            val messages = interactiveMessageBuffers.getOrPut(senderKey) { mutableListOf() }
            messages.add(ReplyStore.BufferedMessage(messageText, now))
            cancelActiveInteractiveGenerations(senderKey)
            ReplyStore.clearStoredReplies(senderKey)

            val mergedMessages = messages.toList()
            val mergedText = AiReplyGenerator.mergeMessageTexts(mergedMessages)
            Logger.d("Interactive merged generation for $senderKey with ${mergedMessages.size} message(s): $mergedText")
            startInteractiveGeneration(packageName, senderName, senderKey, mergedText)
            return
        }

        android.util.Log.d("ScrollCat", "Interactive merge: generating separately for $senderKey (panel already seen)")
        cancelActiveInteractiveGenerations(senderKey)
        ReplyStore.clearStoredReplies(senderKey)
        interactiveMessageBuffers[senderKey] = mutableListOf(ReplyStore.BufferedMessage(messageText, now))
        startInteractiveGeneration(packageName, senderName, senderKey, messageText)
    }

    private fun startInteractiveGeneration(
        packageName: String,
        senderName: String,
        senderKey: String,
        messageText: String
    ) {
        val token = ++nextInteractiveGenerationToken
        activeInteractiveGenerationTokens.getOrPut(senderKey) { mutableSetOf() }.add(token)

        AiReplyGenerator.generateReplies(this, packageName, senderName, messageText) { replies ->
            activeInteractiveGenerationTokens[senderKey]?.let { tokens ->
                tokens.remove(token)
                if (tokens.isEmpty()) activeInteractiveGenerationTokens.remove(senderKey)
            }
            if (cancelledInteractiveGenerationTokens.remove(token)) {
                Logger.d("Ignoring cancelled interactive reply generation for $senderKey")
                return@generateReplies
            }
            ReplyStore.storeReplies(senderKey, replies)
        }
    }

    private fun cancelActiveInteractiveGenerations(senderKey: String) {
        activeInteractiveGenerationTokens[senderKey]?.forEach { token ->
            cancelledInteractiveGenerationTokens.add(token)
        }
    }

    private fun clearInteractiveState(senderKey: String) {
        cancelActiveInteractiveGenerations(senderKey)
        interactiveMessageBuffers.remove(senderKey)
        replyPanelOpenedAt.remove(senderKey)
        activeInteractiveGenerationTokens.remove(senderKey)
        pendingBadgeSenderKeys.remove(senderKey)
        OverlayService.instance?.setBadgeCount(pendingBadgeSenderKeys.size)
        android.util.Log.d(
            "ScrollCat",
            "Badge count after update for $senderKey: ${pendingBadgeSenderKeys.size}, total pending senders: ${pendingBadgeSenderKeys.size}"
        )
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

    /**
     * Removes the OS shade entry for a captured notification key (sbn.key).
     * Does not mark the chat as read inside WhatsApp/Telegram/etc. — only clears
     * the system notification. No-ops for demo keys or if the listener is unbound.
     */
    fun cancelSystemNotification(notificationKey: String) {
        if (notificationKey.isBlank()) return
        if (notificationKey == ReplyStore.DEMO_NOTIFICATION_KEY ||
            notificationKey.startsWith("scrollcat_")
        ) {
            return
        }
        try {
            cancelNotification(notificationKey)
            Log.d(TAG, "cancelNotification called for key=$notificationKey")
        } catch (e: Exception) {
            Log.w(TAG, "cancelNotification failed for $notificationKey: ${e.message}")
        }
    }

    fun cancelSystemNotifications(keys: Collection<String>) {
        keys.forEach { cancelSystemNotification(it) }
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }
}
