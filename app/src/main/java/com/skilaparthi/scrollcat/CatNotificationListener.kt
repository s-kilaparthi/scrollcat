package com.skilaparthi.scrollcat

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
            "com.skilaparthi.scrollcat",
            "com.skilaparthi.scrollcat.debug"
        )
        private const val GMAIL_PACKAGE = "com.google.android.gm"
        // Hardcoded permanent block — never process banking/payment/finance apps,
        // regardless of the user's Apps filter settings. Best-effort, not exhaustive.
        private val EXCLUDED_FINANCE_APPS = setOf(
            "com.chase.sig.android",
            "com.infonow.bofa",
            "com.wf.wellsfargomobile",
            "com.citi.citimobile",
            "com.usbank.mobilebanking",
            "com.paypal.android.p2pmobile",
            "com.venmo",
            "com.squareup.cash",
            "com.coinbase.android",
            "com.binance.dev",
            "com.capitalone.mobile",
            "com.chime.mobile",
            "com.robinhood.android",
            "com.fidelity.android",
            "com.schwab.mobile",
            "com.zellepay.zelle"
        )
    }

    private val lastNotificationTime = mutableMapOf<String, Long>()
    private val processedKeys = mutableMapOf<String, Long>()
    /** Exact posted events already handled, keyed by notification key/id/post time. */
    private val processedNotificationEvents = mutableMapOf<String, Long>()
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

    private fun isGmail(packageName: String): Boolean =
        packageName.equals(GMAIL_PACKAGE, ignoreCase = true)

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

        // Permanent finance/banking exclusion — runs before Apps filter so it
        // cannot be bypassed by an empty allow-list or a mistaken include.
        if (EXCLUDED_FINANCE_APPS.any { it.equals(pkg, ignoreCase = true) }) {
            Log.d(TAG, "Skipping excluded finance app: $pkg")
            return
        }

        // Apps filter: empty list = watch everything; otherwise only selected packages
        val selectedApps = SettingsManager.getWatchedApps(this)
        val isAllowed = selectedApps.isEmpty() ||
            selectedApps.any { it.equals(pkg, ignoreCase = true) }
        Logger.d("App filter check for $pkg - selectedApps: $selectedApps, allowed: $isAllowed")
        if (!isAllowed) return

        // Capture replyable messages first so same-notification updates (new DM text
        // on the same sbn.key) can append to the per-sender queue. Key debounce only
        // applies to the non-replyable badge path below.
        val now = System.currentTimeMillis()
        pruneProcessedKeys(now)

        // Android may deliver the exact same posted event more than once. The existing
        // key-only debounce intentionally allows replyable updates because messaging apps
        // reuse one notification key for new messages. This identity includes postTime, so
        // a genuinely new update still proceeds while a duplicate callback cannot send twice.
        val notificationEventKey = "$notificationKey:${sbn.id}:${sbn.postTime}"
        val lastProcessedAt = processedNotificationEvents[notificationEventKey] ?: 0L
        val wasRecentlyProcessed = now - lastProcessedAt < DEBOUNCE_MS
        Log.d(
            TAG,
            "Auto-reply check - notification key=$notificationKey, " +
                "already processed recently=$wasRecentlyProcessed, " +
                "proceeding=${!wasRecentlyProcessed}"
        )
        if (wasRecentlyProcessed) return
        processedNotificationEvents[notificationEventKey] = now

        val replyable = ReplyStore.capture(sbn)
        if (replyable != null) {
            processedKeys[notificationKey] = now
            val senderName = replyable.sender
            // Gmail: keep for panel preview (Reply in app only) even without RemoteInput.
            // Other apps without RemoteInput can't be replied to from the panel — drop them.
            if (!replyable.hasRemoteInput && !isGmail(pkg)) {
                Logger.d("Skipping non-replyable notification from $senderName via $pkg (no RemoteInput)")
                ReplyStore.removeEntry(replyable.entryId)
                return
            }
            if (isIgnoredChat(senderName)) {
                Log.d(TAG, "Skipping ignored chat: $senderName")
                ReplyStore.removeEntry(replyable.entryId)
                return
            }

            Log.d(TAG, "Replyable message from ${replyable.sender} via $pkg")
            // Proactive on-device warmup if engine was idle-unloaded
            if (!OnDeviceAiEngine.isReady()) {
                Thread {
                    OnDeviceAiEngine.ensureInitialized(this@CatNotificationListener)
                }.start()
            }
            val messageText = replyable.message
            val priority = SettingsManager.matchesPersonOrKeyword(this, pkg, "$senderName $messageText")
            ReplyStore.markPriority(replyable.notificationKey, priority)

            // Gmail: never auto-reply and never generate AI suggestions / burn Groq calls.
            // Still badge so the user can open the panel and use Reply in app / Ignore.
            if (isGmail(pkg)) {
                Log.d(TAG, "Gmail — skipping AI reply generation and auto-reply for $senderName")
                updateReplyableBadge(buildSenderKey(pkg, sbn.id, senderName))
                return
            }

            // Auto-reply rules: if an enabled rule's keywords match, the cat
            // answers immediately without user interaction.
            val match = AutoReplyManager.findMatch(this, messageText, replyable.sender)
            if (match != null) {
                Log.i(
                    TAG,
                    "Auto-reply rule matched for ${replyable.sender}: ${match.matchedKeyword}"
                )
                val sent = ReplySender.send(
                    this,
                    replyable,
                    match.rule.reply,
                    recordAsAiReply = false
                )
                if (sent) {
                    AutoReplyManager.logTrackerEntry(
                        context = this,
                        packageName = pkg,
                        sender = replyable.sender,
                        message = match.rule.reply,
                        matchedKeyword = match.matchedKeyword
                    )
                    ReplyStore.removeEntry(replyable.entryId)
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
                handleInteractiveMessage(
                    packageName = pkg,
                    senderName = senderName,
                    senderKey = senderKey,
                    entryId = replyable.entryId,
                    conversationKey = replyable.conversationKey,
                    messageText = combinedText,
                    now = now
                )
            } else {
                ReplyStore.bufferMessage(senderKey, messageText)
            }
            return
        }

        // Prevent duplicate processing of the same non-replyable notification update
        val lastKeyTime = processedKeys[notificationKey] ?: 0L
        if (now - lastKeyTime < DEBOUNCE_MS) return
        processedKeys[notificationKey] = now

        // Debounce badge/filter processing per app
        val lastTime = lastNotificationTime[pkg] ?: 0L
        if (now - lastTime < DEBOUNCE_MS) return
        lastNotificationTime[pkg] = now

        // Extract full notification text (prefer expanded body over short EXTRA_TEXT)
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = ReplyStore.extractMessageBody(extras)

        // Combine title + best available body for keyword / person matching
        val fullText = "$title $body".trim()

        Log.d(
            TAG,
            "Full notification from $pkg: chars=${fullText.length} " +
                "keywordCount=${SettingsManager.getWatchedKeywords(this).size}"
        )

        // Badge on filter match for non-replyable notifications
        if (SettingsManager.shouldNotify(this, pkg, fullText)) {
            OverlayService.instance?.incrementBadge()
        }
    }

    fun getPregeneratedReplies(
        packageName: String,
        notificationId: Int,
        senderName: String,
        messageText: String,
        entryId: String
    ): List<String>? {
        val senderKey = buildSenderKey(packageName, notificationId, senderName)
        replyPanelOpenedAt[senderKey] = System.currentTimeMillis()
        return ReplyStore.getStoredReplies(entryId)
    }

    /**
     * Clears chips for one queue entry. Call **after** [ReplyStore.removeEntry] so
     * conversation emptiness is accurate. Leaves panelSeen state intact when more
     * messages remain for this sender.
     */
    fun clearPregeneratedReplies(
        packageName: String,
        notificationId: Int,
        senderName: String,
        messageText: String,
        entryId: String,
        conversationKey: String
    ) {
        val senderKey = buildSenderKey(packageName, notificationId, senderName)
        ReplyStore.clearStoredReplies(entryId)
        if (ReplyStore.countForConversation(conversationKey) == 0) {
            clearInteractiveState(senderKey)
        }
    }

    private fun updateReplyableBadge(senderKey: String) {
        pendingBadgeSenderKeys.add(senderKey)
        // Badge = total pending messages (not unique senders)
        val totalMessages = ReplyStore.count()
        OverlayService.instance?.setBadgeCount(totalMessages)
        OverlayService.instance?.onReplyablesChanged()
        android.util.Log.d(
            "ScrollCat",
            "Badge count after update for $senderKey: $totalMessages pending message(s)"
        )
    }

    private fun pruneProcessedKeys(now: Long) {
        if (processedKeys.size > 100) {
            processedKeys.entries.removeAll { now - it.value > DEBOUNCE_MS * 5 }
        }
        if (processedNotificationEvents.size > 100) {
            processedNotificationEvents.entries.removeAll {
                now - it.value > DEBOUNCE_MS * 5
            }
        }
    }

    private fun handleInteractiveMessage(
        packageName: String,
        senderName: String,
        senderKey: String,
        entryId: String,
        conversationKey: String,
        messageText: String,
        now: Long
    ) {
        val panelAlreadyOpened = replyPanelOpenedAt.containsKey(senderKey)
        android.util.Log.d(
            "ScrollCat",
            "Interactive merge check for $senderKey - panelSeen: $panelAlreadyOpened entryId=$entryId"
        )

        if (!panelAlreadyOpened) {
            android.util.Log.d("ScrollCat", "Interactive merge: accumulating messages for $senderKey")
            val messages = interactiveMessageBuffers.getOrPut(senderKey) { mutableListOf() }
            messages.add(ReplyStore.BufferedMessage(messageText, now))
            cancelActiveInteractiveGenerations(senderKey)

            val mergedMessages = messages.toList()
            val mergedText = AiReplyGenerator.mergeMessageTexts(mergedMessages)
            Logger.d(
                "Interactive merged generation for $senderKey with ${mergedMessages.size} message(s)"
            )
            // Store on all queued entries for this conversation (pre-panel merge)
            startInteractiveGeneration(
                packageName = packageName,
                senderName = senderName,
                senderKey = senderKey,
                entryId = entryId,
                conversationKey = conversationKey,
                messageText = mergedText,
                storeForWholeConversation = true
            )
            return
        }

        // Panel already open for this sender: generate for this message alone and
        // APPEND as its own queue entry (already done in ReplyStore.capture) —
        // do not clear/overwrite chips for earlier unviewed entries.
        android.util.Log.d(
            "ScrollCat",
            "Interactive merge: generating separately for $senderKey entryId=$entryId (panel already seen)"
        )
        interactiveMessageBuffers[senderKey] =
            mutableListOf(ReplyStore.BufferedMessage(messageText, now))
        startInteractiveGeneration(
            packageName = packageName,
            senderName = senderName,
            senderKey = senderKey,
            entryId = entryId,
            conversationKey = conversationKey,
            messageText = messageText,
            storeForWholeConversation = false
        )
    }

    private fun startInteractiveGeneration(
        packageName: String,
        senderName: String,
        senderKey: String,
        entryId: String,
        conversationKey: String,
        messageText: String,
        storeForWholeConversation: Boolean
    ) {
        if (packageName.equals(GMAIL_PACKAGE, ignoreCase = true)) {
            Log.d(TAG, "Gmail — refusing AI reply generation for $senderKey")
            return
        }
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
            // Full replacement of chips for this entry / conversation — never merge
            val fullReplies = replies.toList()
            android.util.Log.d(
                "ScrollCat",
                "Interactive gen storing ${fullReplies.size} replies for entryId=$entryId"
            )
            if (storeForWholeConversation) {
                ReplyStore.storeRepliesForConversation(conversationKey, fullReplies)
            } else {
                ReplyStore.storeReplies(entryId, fullReplies)
            }
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
        val totalMessages = ReplyStore.count()
        OverlayService.instance?.setBadgeCount(totalMessages)
        android.util.Log.d(
            "ScrollCat",
            "Badge count after clearInteractiveState for $senderKey: $totalMessages pending message(s)"
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: run {
            super.onNotificationRemoved(sbn)
            return
        }
        val key = sbn.key
        val panelOpen = OverlayService.instance?.isReplyPanelShowing() == true
        val stillQueuedForKey = ReplyStore.countForNotificationKey(key) > 0

        // Replying (or Ignore) often cancels the OS notification. Multiple queue
        // entries can share one notificationKey (e.g. WhatsApp chat updates).
        // While the reply panel is open, do not wipe remaining unhandled entries.
        if (panelOpen && stillQueuedForKey) {
            Log.d(
                TAG,
                "Notification removed ($key) but preserving " +
                    "${ReplyStore.countForNotificationKey(key)} queued entr(y/ies) — panel open"
            )
            OverlayService.instance?.setBadgeCount(ReplyStore.count())
            OverlayService.instance?.onReplyablesChanged()
            processedKeys.remove(key)
            super.onNotificationRemoved(sbn)
            return
        }

        val entry = ReplyStore.getByKey(key)
        ReplyStore.remove(key)
        if (entry != null) {
            val senderKey = buildSenderKey(entry.packageName, entry.notificationId, entry.sender)
            if (ReplyStore.countForConversation(entry.conversationKey) == 0) {
                clearInteractiveState(senderKey)
            } else {
                OverlayService.instance?.setBadgeCount(ReplyStore.count())
            }
        }
        processedKeys.remove(key)
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
