package com.example.scrollcat

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating panel shown above the cat with 3 AI reply suggestions for the
 * newest replyable message. Tapping a suggestion sends it via RemoteInput
 * and shows a "Sent to [Name] ✓" confirmation — no app opening needed.
 */
class ReplyPanel(
    private val context: Context,
    private val windowManager: WindowManager
) {

    companion object {
        private const val PANEL_WIDTH = 680
        private const val CONFIRMATION_MS = 3000L
        private const val ACCENT = 0xFF4A90D9.toInt()
        private const val PANEL_BG = 0xF21A1A2E.toInt()
        private const val CHIP_BG = 0xFF2A2A45.toInt()
    }

    var isShowing = false
        private set

    private var panelView: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    // Routes to Claude when an API key is set, on-device Gemini Nano otherwise
    private val generator = ClaudeReplyGenerator(context)
    private var pending: MutableList<ReplyStore.ReplyableMessage> = mutableListOf()
    private var currentEntry: ReplyStore.ReplyableMessage? = null
    private var currentIndex = 0
    private var navRow: LinearLayout? = null
    private var pendingCountView: TextView? = null
    var onDismissed: (() -> Unit)? = null

    fun show(catX: Int, catY: Int, catSize: Int) {
        pending = ReplyStore.getAll().toMutableList()
        if (pending.isEmpty()) return
        currentIndex = 0
        dismiss()
        isShowing = true

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            background = GradientDrawable().apply {
                setColor(PANEL_BG)
                cornerRadius = 36f
                setStroke(2, 0x33FFFFFF)
            }
        }

        val dm = context.resources.displayMetrics
        val x = (catX + catSize / 2 - PANEL_WIDTH / 2)
            .coerceIn(16, (dm.widthPixels - PANEL_WIDTH - 16).coerceAtLeast(16))
        // Rough panel height; final height wraps content
        val y = (catY - 520).coerceAtLeast(60)

        val params = WindowManager.LayoutParams(
            PANEL_WIDTH,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        try {
            if (panel.parent == null) windowManager.addView(panel, params)
        } catch (e: Exception) {
            android.util.Log.w("ScrollCat", "ReplyPanel addView failed: ${e.message}")
            isShowing = false
            return
        }
        panelView = panel
        panelParams = params

        showMessage(pending.first())
    }

    private fun showMessage(message: ReplyStore.ReplyableMessage) {
        pending.indexOfFirst { it.notificationKey == message.notificationKey }
            .takeIf { it >= 0 }
            ?.let { currentIndex = it }
        currentEntry = message
        val panel = panelView ?: return
        panel.removeAllViews()
        navRow = null
        pendingCountView = null

        // ── Header: sender + app + close ──
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "💬 ${message.sender}"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(context).apply {
            text = appLabel(message.packageName)
            textSize = 11f
            setTextColor(0xFF9999BB.toInt())
            setPadding(0, 0, 16, 0)
        })
        header.addView(TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(0xFF9999BB.toInt())
            setPadding(12, 4, 4, 4)
            setOnClickListener { dismiss() }
        })
        panel.addView(header)

        // ── Incoming message preview ──
        panel.addView(TextView(context).apply {
            text = if (message.message.length > 140) message.message.take(140) + "…" else message.message
            textSize = 13f
            setTextColor(0xFFCCCCDD.toInt())
            setPadding(0, 10, 0, 16)
        })

        // ── Suggestions container ──
        val chipsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        panel.addView(chipsContainer)

        // ── Bottom row: Reply in app + Ignore + more ──
        val bottomRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        }

        val replyInAppBtn = android.widget.TextView(context).apply {
            text = "↗ Reply in app"
            textSize = 13f
            setTextColor(0xFF4A90D9.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(16, 20, 16, 20)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF2a2a2a.toInt())
                cornerRadius = 24f
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(0, 0, 6, 0) }
        }

        val ignoreBtn = android.widget.TextView(context).apply {
            text = "✕ Ignore"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(16, 20, 16, 20)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF2a2a2a.toInt())
                cornerRadius = 24f
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(6, 0, 6, 0) }
        }

        val moreBtn = android.widget.TextView(context).apply {
            text = "⋮"
            textSize = 18f
            setTextColor(0xFFBBBBCC.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(18, 18, 18, 18)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF2a2a2a.toInt())
                cornerRadius = 24f
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = 6 }
        }

        // Reply in app click
        replyInAppBtn.setOnClickListener {
            Logger.d("Reply in app button clicked - entry: ${currentEntry?.packageName} contentIntent: ${currentEntry?.contentIntent}")
            val entry = currentEntry ?: return@setOnClickListener
            try {
                if (entry.contentIntent != null) {
                    val options = android.app.ActivityOptions.makeBasic().apply {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            setPendingIntentBackgroundActivityStartMode(
                                android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                            )
                        }
                    }
                    entry.contentIntent.send(context, 0, null, null, null, null, options.toBundle())
                } else {
                    val launchIntent = context.packageManager
                        .getLaunchIntentForPackage(entry.packageName)?.apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                    launchIntent?.let { context.startActivity(it) }
                }
            } catch (e: Exception) {
                try {
                    val launchIntent = context.packageManager
                        .getLaunchIntentForPackage(entry.packageName)?.apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    launchIntent?.let { context.startActivity(it) }
                } catch (e2: Exception) {
                    Logger.e("Failed to open app: ${e2.message}")
                }
            }
            // Clear badge and remove from store after opening app
            clearPregeneratedReplies(entry)
            ReplyStore.remove(entry.notificationKey)
            OverlayService.instance?.updateBadgeAfterReply()
            dismiss()
        }

        // Ignore click - just dismiss and remove from store
        ignoreBtn.setOnClickListener {
            val entry = currentEntry ?: return@setOnClickListener
            clearPregeneratedReplies(entry)
            ReplyStore.remove(entry.notificationKey)
            OverlayService.instance?.updateBadgeAfterReply()
            dismiss()
            Logger.d("Message ignored: ${entry.sender}")
        }

        bottomRow.addView(replyInAppBtn)
        bottomRow.addView(ignoreBtn)
        bottomRow.addView(moreBtn)
        panel.addView(bottomRow)

        val menuContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        panel.addView(menuContainer)

        moreBtn.setOnClickListener {
            if (menuContainer.childCount > 0) {
                menuContainer.removeAllViews()
                return@setOnClickListener
            }
            menuContainer.addView(TextView(context).apply {
                val entry = currentEntry
                text = if (entry != null) "Ignore ${entry.sender} forever" else "Ignore this user forever"
                textSize = 13f
                setTextColor(0xFFFF9999.toInt())
                gravity = Gravity.END
                setPadding(16, 12, 16, 12)
                background = GradientDrawable().apply {
                    setColor(0xFF2a2a2a.toInt())
                    cornerRadius = 18f
                }
                setOnClickListener {
                    val entry = currentEntry ?: return@setOnClickListener
                    showIgnoreUserConfirmation(entry)
                }
            })
            menuContainer.addView(View(context).apply {
                setBackgroundColor(0xFF444455.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1
                ).apply { setMargins(12, 6, 12, 6) }
            })
            menuContainer.addView(TextView(context).apply {
                text = "Clear all pending replies"
                textSize = 13f
                setTextColor(0xFFBBBBCC.toInt())
                gravity = Gravity.END
                setPadding(16, 12, 16, 12)
                background = GradientDrawable().apply {
                    setColor(0xFF2a2a2a.toInt())
                    cornerRadius = 18f
                }
                setOnClickListener {
                    clearAllPendingReplies()
                }
            })
        }

        // ── Footer: remaining conversations ──
        updatePendingFooter()

        fun showThinkingState() {
            chipsContainer.removeAllViews()
            chipsContainer.addView(TextView(context).apply {
                text = "🐾 Cat is thinking…"
                textSize = 13f
                setTextColor(0xFF9999BB.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 12, 0, 12)
            })
        }

        lateinit var renderReplies: (List<String>, String) -> Unit

        fun showEditInput(initialText: String, suggestions: List<String>, engine: String) {
            chipsContainer.removeAllViews()
            setPanelFocusable(true)

            val editRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val input = android.widget.EditText(context).apply {
                setText(initialText)
                setSelection(text.length)
                textSize = 14f
                setTextColor(Color.WHITE)
                setHintTextColor(0xFF777788.toInt())
                setSingleLine(false)
                minLines = 1
                maxLines = 3
                setPadding(18, 14, 18, 14)
                background = GradientDrawable().apply {
                    setColor(0xFF202038.toInt())
                    cornerRadius = 20f
                    setStroke(1, 0x44FFFFFF)
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = 8 }
            }
            val sendBtn = TextView(context).apply {
                text = "Send"
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(18, 16, 18, 16)
                background = GradientDrawable().apply {
                    setColor(ACCENT)
                    cornerRadius = 20f
                }
                setOnClickListener {
                    val entry = currentEntry ?: return@setOnClickListener
                    val edited = input.text.toString().trim()
                    if (edited.isEmpty()) return@setOnClickListener
                    setPanelFocusable(false)
                    if (entry.hasRemoteInput) {
                        sendReply(entry, edited)
                    } else {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("reply", edited)
                        )
                        android.widget.Toast.makeText(
                            context,
                            "Copied! Opening app...",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        replyInAppBtn.performClick()
                    }
                }
            }
            editRow.addView(input)
            editRow.addView(sendBtn)
            chipsContainer.addView(editRow)
            chipsContainer.addView(TextView(context).apply {
                text = "Cancel"
                textSize = 12f
                setTextColor(0xFF9999BB.toInt())
                gravity = Gravity.END
                setPadding(0, 10, 6, 0)
                setOnClickListener {
                    setPanelFocusable(false)
                    renderReplies(suggestions, engine)
                }
            })

            input.post {
                input.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        fun showReplies(suggestions: List<String>, engine: String = "Pre-generated") {
            if (!isShowing || currentEntry != message) return
            setPanelFocusable(false)
            chipsContainer.removeAllViews()
            if (engine == AiReplyGenerator.ENGINE_LIMIT_REACHED) {
                chipsContainer.addView(TextView(context).apply {
                    text = AiReplyGenerator.UPGRADE_MESSAGE
                    textSize = 13f
                    setTextColor(0xFFFFD37A.toInt())
                    gravity = Gravity.CENTER
                    setPadding(8, 12, 8, 12)
                    setOnClickListener {
                        val intent = android.content.Intent(context, SubscriptionActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        dismiss()
                    }
                })
                return
            }
            if (suggestions.isEmpty()) {
                chipsContainer.addView(TextView(context).apply {
                    text = "😿 Couldn't think of a reply"
                    textSize = 13f
                    setTextColor(0xFF9999BB.toInt())
                    gravity = Gravity.CENTER
                    setPadding(0, 12, 0, 12)
                })
                return
            }
            Logger.d("Replies from $engine: $suggestions")
            fun sendOrCopyReply(replyText: String) {
                val entry = currentEntry ?: return
                if (entry.hasRemoteInput) {
                    sendReply(entry, replyText)
                } else {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("reply", replyText)
                    )
                    android.widget.Toast.makeText(
                        context,
                        "Copied! Opening app...",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    replyInAppBtn.performClick()
                }
            }

            fun addEditableChip(suggestion: String) {
                val chipRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        setColor(CHIP_BG)
                        cornerRadius = 28f
                        setStroke(1, 0x44FFFFFF)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 6, 0, 6) }
                }
                chipRow.addView(TextView(context).apply {
                    text = suggestion
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(24, 18, 12, 18)
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                    setOnClickListener { sendOrCopyReply(suggestion) }
                })
                chipRow.addView(TextView(context).apply {
                    text = "✎"
                    textSize = 16f
                    setTextColor(0xFFBBBBCC.toInt())
                    gravity = Gravity.CENTER
                    setPadding(18, 18, 24, 18)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                    setOnClickListener { showEditInput(suggestion, suggestions, engine) }
                })
                chipsContainer.addView(chipRow)
            }
            if (currentEntry?.hasRemoteInput != true) {
                chipsContainer.addView(TextView(context).apply {
                    text = "💡 Tap a suggestion to copy it, or tap ✎ to edit first"
                    textSize = 12f
                    setTextColor(0xFF888888.toInt())
                    setPadding(16, 8, 16, 8)
                })
                suggestions.forEach { suggestion ->
                    addEditableChip(suggestion)
                }
            } else {
                suggestions.forEach { suggestion ->
                    addEditableChip(suggestion)
                }
            }
        }

        renderReplies = ::showReplies

        // Check for pre-generated replies first
        val pregenerated = CatNotificationListener.instance?.getPregeneratedReplies(
            message.packageName,
            message.notificationId,
            message.sender,
            message.message
        )

        if (pregenerated != null && pregenerated.isNotEmpty()) {
            Logger.d("Using pre-generated replies - INSTANT!")
            showReplies(pregenerated)
        } else {
            Logger.d("No pre-generated replies - generating now")
            showThinkingState()
            val aiGenerator = AiReplyGenerator(context)
            aiGenerator.generateReplies(message.sender, message.message) { replies, engine ->
                handler.post {
                    ReplyStore.storeReplies(senderKeyFor(message), replies)
                    showReplies(replies, engine)
                }
            }
        }
    }

    private fun suggestionChip(text: String, message: ReplyStore.ReplyableMessage): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(24, 18, 24, 18)
            background = GradientDrawable().apply {
                setColor(CHIP_BG)
                cornerRadius = 28f
                setStroke(1, 0x44FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 6, 0, 6) }
            setOnClickListener { sendReply(message, text) }
        }
    }

    private fun sendReply(message: ReplyStore.ReplyableMessage, replyText: String) {
        val sent = ReplySender.send(context, message, replyText)
        clearPregeneratedReplies(message)
        ReplyStore.remove(message.notificationKey)
        pending.remove(message)
        currentIndex = currentIndex.coerceAtMost((pending.size - 1).coerceAtLeast(0))
        OverlayService.instance?.updateBadgeAfterReply()
        if (sent) {
            showConfirmation("Sent to ${message.sender} ✓")
        } else {
            // RemoteInput unusable — fall back to opening the conversation
            ReplySender.openApp(context, message)
            dismiss()
        }
    }

    private fun showConfirmation(text: String) {
        val panel = panelView ?: return
        panel.removeAllViews()
        panel.addView(TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 28)
        })
        handler.postDelayed({
            if (!isShowing) return@postDelayed
            if (pending.isNotEmpty()) {
                currentIndex = currentIndex.coerceIn(0, pending.size - 1)
                showMessage(pending[currentIndex])
            } else {
                dismiss()
            }
        }, CONFIRMATION_MS)
    }

    private fun advance() {
        if (pending.size <= 1) return
        currentIndex = (currentIndex + 1) % pending.size
        showMessage(pending[currentIndex])
    }

    private fun previous() {
        if (pending.size <= 1) return
        currentIndex = if (currentIndex == 0) pending.size - 1 else currentIndex - 1
        showMessage(pending[currentIndex])
    }

    fun refreshPendingFromStore() {
        if (!isShowing) return

        val currentKey = currentEntry?.notificationKey
        val livePending = ReplyStore.getAll().toMutableList()
        if (livePending.isEmpty()) {
            dismiss()
            return
        }

        pending = livePending
        val liveIndex = pending.indexOfFirst { it.notificationKey == currentKey }
        if (liveIndex >= 0) {
            currentIndex = liveIndex
            updatePendingFooter()
        } else {
            currentIndex = currentIndex.coerceIn(0, pending.size - 1)
            showMessage(pending[currentIndex])
        }
    }

    private fun updatePendingFooter() {
        val panel = panelView ?: return
        if (pending.size <= 1) {
            navRow?.let { row ->
                try { panel.removeView(row) } catch (e: Exception) { }
            }
            navRow = null
            pendingCountView = null
            return
        }

        if (navRow == null) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 12, 0, 0)
            }
            row.addView(TextView(context).apply {
                text = "←"
                textSize = 18f
                setTextColor(0xFF9999FF.toInt())
                gravity = Gravity.CENTER
                setPadding(24, 8, 24, 8)
                setOnClickListener { previous() }
            })
            pendingCountView = TextView(context).apply {
                textSize = 11f
                setTextColor(0xFF7777AA.toInt())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(pendingCountView)
            row.addView(TextView(context).apply {
                text = "→"
                textSize = 18f
                setTextColor(0xFF9999FF.toInt())
                gravity = Gravity.CENTER
                setPadding(24, 8, 24, 8)
                setOnClickListener { advance() }
            })
            navRow = row
            panel.addView(row)
        }

        pendingCountView?.text = "${currentIndex + 1} / ${pending.size} waiting"
    }

    private fun showIgnoreUserConfirmation(message: ReplyStore.ReplyableMessage) {
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle("Ignore ${message.sender} forever?")
            .setMessage("You won't see their messages again.")
            .setPositiveButton("Confirm") { _, _ ->
                ignoreUser(message)
            }
            .setNegativeButton("Cancel", null)
            .create()

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            }
            dialog.show()
        } catch (e: Exception) {
            Logger.e("Failed to show ignore confirmation: ${e.message}")
        }
    }

    private fun ignoreUser(message: ReplyStore.ReplyableMessage) {
        val normalizedSender = message.sender.trim().lowercase()
        if (normalizedSender.isNotEmpty()) {
            val ignoredChats = SettingsManager.getIgnoredChats(context).toMutableSet()
            ignoredChats.add(normalizedSender)
            SettingsManager.setIgnoredChats(context, ignoredChats)
            Logger.d("Added ${message.sender} to ignored chats")
        }

        val ignored = pending.filter {
            it.packageName == message.packageName &&
                it.sender.equals(message.sender, ignoreCase = true)
        }
        ignored.forEach { entry ->
            ReplyStore.remove(entry.notificationKey)
            ReplyStore.getAndClearBuffer(senderKeyFor(entry))
            clearPregeneratedReplies(entry)
        }
        pending.removeAll(ignored.toSet())
        currentIndex = currentIndex.coerceAtMost((pending.size - 1).coerceAtLeast(0))
        OverlayService.instance?.updateBadgeAfterReply()
        if (pending.isEmpty()) {
            dismiss()
        } else {
            showMessage(pending[currentIndex])
        }
    }

    private fun clearAllPendingReplies() {
        val allPending = pending.toList()
        allPending.forEach { entry ->
            ReplyStore.remove(entry.notificationKey)
            ReplyStore.getAndClearBuffer(senderKeyFor(entry))
            clearPregeneratedReplies(entry)
        }
        pending.clear()
        currentIndex = 0
        OverlayService.instance?.clearBadge()
        dismiss()
    }

    private fun senderKeyFor(message: ReplyStore.ReplyableMessage): String {
        return if (message.packageName == "com.whatsapp") {
            "${message.packageName}:${message.sender.replace(Regex("\\s*\\(\\d+\\s*messages?\\)", RegexOption.IGNORE_CASE), "").trim()}"
        } else {
            "${message.packageName}:${message.notificationId}"
        }
    }

    private fun clearPregeneratedReplies(message: ReplyStore.ReplyableMessage) {
        CatNotificationListener.instance?.clearPregeneratedReplies(
            message.packageName,
            message.notificationId,
            message.sender,
            message.message
        ) ?: ReplyStore.clearStoredReplies(senderKeyFor(message))
    }

    private fun appLabel(packageName: String): String {
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    private fun setPanelFocusable(focusable: Boolean) {
        val panel = panelView ?: return
        val params = panelParams ?: return
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(panel.windowToken, 0)
        }
        try {
            windowManager.updateViewLayout(panel, params)
        } catch (e: Exception) {
            Logger.e("Failed to update reply panel focus: ${e.message}")
        }
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        setPanelFocusable(false)
        panelView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { }
        }
        panelView = null
        panelParams = null
        currentEntry = null
        if (isShowing) {
            isShowing = false
            onDismissed?.invoke()
        }
    }

    fun destroy() {
        dismiss()
        generator.close()
    }
}
