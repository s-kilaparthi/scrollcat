package com.example.scrollcat

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        private const val ACCENT = 0xFFB39DDB.toInt()
        private const val PANEL_BG = 0xF21E1E28.toInt()
        private const val CHIP_BG = 0xFF2A2A36.toInt()
        private const val MAX_PANEL_HEIGHT_FRACTION = 0.4f
        private const val TAG_NEW_SENDER_ARROW = "scrollcat_new_sender_arrow"
        private const val MUTED_TEXT = 0xFFA39BB0.toInt()
        private const val SOFT_TEXT = 0xFFE8E4EF.toInt()
        private const val DANGER = 0xFFFCA5A5.toInt()
        private const val BUTTON_BG = 0xFF2E2A3A.toInt()
        private const val INPUT_BG = 0xFF25252C.toInt()
        private const val PLACEHOLDER_BG = 0xFF3A3548.toInt()
        private const val NAV_ACCENT = 0xFFC4B5E0.toInt()
        private const val TIP_ACCENT = 0xFFE9D5FF.toInt()
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
    private var newSenderArrow: TextView? = null
    /** Notification keys the user has already viewed in this open panel session. */
    private val viewedKeys = mutableSetOf<String>()
    /**
     * Conversation keys of other pending senders that already existed when this
     * single-sender panel opened (direct or from list). Arrow only for arrivals after this.
     */
    private val knownOthersAtOpen = mutableSetOf<String>()
    private var showingSenderList = false
    var onDismissed: (() -> Unit)? = null
    /** Fired when the onboarding demo reply panel first opens (message + chips). */
    var onDemoPanelShown: (() -> Unit)? = null
    /** Fired after the onboarding demo shows its Sent confirmation (not a real send). */
    var onDemoReplySent: (() -> Unit)? = null
    private var demoInstructionsDismissed = false
    private var demoPanelShownNotified = false

    fun resetOnboardingDemoState() {
        demoInstructionsDismissed = false
        demoPanelShownNotified = false
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** Service/overlay context has no Material theme; wrap before constructing Material widgets. */
    private val materialContext: Context by lazy {
        ContextThemeWrapper(context, R.style.Theme_ScrollCat)
    }

    fun show(catX: Int, catY: Int, catSize: Int) {
        // Same priority-first ordering used for swipe navigation
        pending = ReplyStore.getAll().toMutableList()
        if (pending.isEmpty()) return
        currentIndex = 0
        viewedKeys.clear()
        knownOthersAtOpen.clear()
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

        if (pending.size == 1) {
            showMessage(pending.first(), captureOpenSnapshot = true)
        } else {
            showSenderList()
        }
    }

    private fun showSenderList() {
        showingSenderList = true
        currentEntry = null
        navRow = null
        pendingCountView = null
        val panel = panelView ?: return
        panel.removeAllViews()
        resetPanelHeightToWrap()
        navRow = null
        pendingCountView = null
        newSenderArrow = null

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        header.addView(TextView(context).apply {
            text = "Pending replies"
            textSize = 15f
            setTextColor(0xFFF5F3F7.toInt())
            typeface = UiKit.headingTypeface(context)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(context).apply {
            text = "Ignore all"
            textSize = 13f
            setTextColor(DANGER)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { clearAllPendingReplies() }
        })
        header.addView(TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(MUTED_TEXT)
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { dismiss() }
        })
        panel.addView(header)

        pending.forEach { entry ->
            panel.addView(buildSenderListRow(entry))
        }
    }

    private fun buildSenderListRow(entry: ReplyStore.ReplyableMessage): MaterialCardView {
        val themed = materialContext
        val card = MaterialCardView(themed).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(2).toFloat()
            strokeWidth = 1
            strokeColor = 0x33FFFFFF
            setCardBackgroundColor(CHIP_BG)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, dp(6)) }
        }

        val row = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
        }

        val iconSize = dp(36)
        row.addView(appIconView(entry.packageName, sizeDp = 36, viewContext = themed).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                setMargins(0, 0, dp(10), 0)
            }
        })

        val textCol = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(themed).apply {
            text = entry.sender
            textSize = 14f
            setTextColor(0xFFF5F3F7.toInt())
            typeface = UiKit.headingTypeface(context)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        textCol.addView(TextView(themed).apply {
            text = formatReceivedTime(entry.timestamp)
            textSize = 11f
            setTextColor(MUTED_TEXT)
        })
        val preview = entry.message.let {
            if (it.length > 48) it.take(48) + "…" else it
        }
        textCol.addView(TextView(themed).apply {
            text = preview
            textSize = 12f
            setTextColor(SOFT_TEXT)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(textCol)

        if (entry.priority) {
            row.addView(View(themed).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF16A34A.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                    setMargins(dp(6), 0, dp(6), 0)
                }
            })
        }

        row.addView(TextView(themed).apply {
            text = "✕"
            textSize = 15f
            setTextColor(MUTED_TEXT)
            setPadding(dp(10), dp(6), dp(6), dp(6))
            setOnClickListener { dismissSenderFromList(entry) }
        })

        row.setOnClickListener {
            currentIndex = pending.indexOfFirst { it.notificationKey == entry.notificationKey }
                .coerceAtLeast(0)
            showMessage(entry, captureOpenSnapshot = true)
        }
        card.addView(row)
        return card
    }

    private fun dismissSenderFromList(entry: ReplyStore.ReplyableMessage) {
        cancelShadeNotification(entry)
        clearPregeneratedReplies(entry)
        ReplyStore.remove(entry.notificationKey)
        ReplyStore.getAndClearBuffer(senderKeyFor(entry))
        pending.removeAll { it.notificationKey == entry.notificationKey }
        OverlayService.instance?.updateBadgeAfterReply()
        Logger.d("Message ignored from list: ${entry.sender}")
        when {
            pending.isEmpty() -> dismiss()
            pending.size == 1 -> showMessage(pending.first(), captureOpenSnapshot = true)
            else -> showSenderList()
        }
    }

    private fun snapshotKnownOthersAtOpen(current: ReplyStore.ReplyableMessage) {
        knownOthersAtOpen.clear()
        // Snapshot ALL pending conversations at open (including the one being viewed),
        // so ←/→ between already-known senders never looks like a "new" arrival.
        pending.forEach { entry ->
            knownOthersAtOpen.add(entry.conversationKey)
        }
        android.util.Log.d(
            "ScrollCat",
            "Panel opened for ${current.notificationKey} - known at open time: $knownOthersAtOpen " +
                "(including current ${current.conversationKey})"
        )
    }

    private fun showMessage(
        message: ReplyStore.ReplyableMessage,
        captureOpenSnapshot: Boolean = false
    ) {
        showingSenderList = false
        pending.indexOfFirst { it.notificationKey == message.notificationKey }
            .takeIf { it >= 0 }
            ?.let { currentIndex = it }
        currentEntry = message
        if (captureOpenSnapshot) {
            snapshotKnownOthersAtOpen(message)
        }
        viewedKeys.add(message.notificationKey)
        val panel = panelView ?: return
        panel.removeAllViews()
        navRow = null
        pendingCountView = null
        newSenderArrow = null

        if (ReplyStore.isDemoMessage(message) && !demoPanelShownNotified) {
            demoPanelShownNotified = true
            handler.post { onDemoPanelShown?.invoke() }
        }

        // ── Header: row1 time+icon+close, row2 sender name ──
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(context).apply {
            text = formatReceivedTime(message.timestamp)
            textSize = 11f
            setTextColor(MUTED_TEXT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        headerRow.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        headerRow.addView(appIconView(message.packageName, sizeDp = 22, viewContext = materialContext).apply {
            val iconSize = dp(22)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                setMargins(0, 0, dp(8), 0)
                gravity = Gravity.CENTER_VERTICAL
            }
        })
        headerRow.addView(TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(MUTED_TEXT)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { dismiss() }
        })
        header.addView(headerRow)
        header.addView(TextView(context).apply {
            text = message.sender
            textSize = 15f
            setTextColor(0xFFF5F3F7.toInt())
            typeface = UiKit.headingTypeface(context)
            setSingleLine(false)
            maxLines = Integer.MAX_VALUE
            ellipsize = null
            setPadding(0, dp(6), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        panel.addView(header)

        // ── Incoming message preview (full text; scrollable if panel is height-capped) ──
        val replyTextSp = SettingsManager.getReplyTextSizeSp(context)
        val messagePreview = TextView(context).apply {
            text = message.message
            setTextSize(TypedValue.COMPLEX_UNIT_SP, replyTextSp)
            setTextColor(SOFT_TEXT)
            setPadding(0, 10, 0, 16)
        }
        val messageScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(
                messagePreview,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        panel.addView(messageScroll)

        // ── Suggestions container ──
        val chipsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        panel.addView(chipsContainer)

        // ── Bottom row: Reply in app + Ignore + more ──
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        }

        val replyInAppBtn = TextView(context).apply {
            text = "↗ Reply in app"
            textSize = 13f
            setTextColor(ACCENT)
            gravity = Gravity.CENTER
            setPadding(16, 20, 16, 20)
            background = GradientDrawable().apply {
                setColor(BUTTON_BG)
                cornerRadius = 24f
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 6, 0)
            }
        }

        val ignoreBtn = TextView(context).apply {
            text = "✕ Ignore"
            textSize = 13f
            setTextColor(MUTED_TEXT)
            gravity = Gravity.CENTER
            setPadding(16, 20, 16, 20)
            background = GradientDrawable().apply {
                setColor(BUTTON_BG)
                cornerRadius = 24f
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(6, 0, 6, 0)
            }
        }

        val moreBtn = TextView(context).apply {
            text = "⋮"
            textSize = 18f
            setTextColor(SOFT_TEXT)
            gravity = Gravity.CENTER
            setPadding(18, 18, 18, 18)
            background = GradientDrawable().apply {
                setColor(BUTTON_BG)
                cornerRadius = 24f
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = 6
            }
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

        // Ignore click - dismiss panel entry and clear the OS shade notification
        ignoreBtn.setOnClickListener {
            val entry = currentEntry ?: return@setOnClickListener
            cancelShadeNotification(entry)
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

        // Content below the button row (demo instructions + inline overflow menu).
        // Lives in the panel hierarchy so height auto-sizing includes it.
        val belowButtons = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        if (ReplyStore.isDemoMessage(message) && !demoInstructionsDismissed) {
            belowButtons.addView(buildDemoInstructions())
        }

        val overflowMenu = buildInlineOverflowMenu().also {
            it.visibility = View.GONE
            belowButtons.addView(it)
        }
        panel.addView(belowButtons)

        // ── Footer: remaining conversations ──
        updatePendingFooter()
        updateNewSenderIndicator()

        fun sizePanelForContent() {
            panel.post {
                applyAutoPanelHeight(
                    header, messageScroll, messagePreview, chipsContainer, bottomRow, belowButtons
                )
            }
        }

        moreBtn.setOnClickListener {
            val expanding = overflowMenu.visibility != View.VISIBLE
            overflowMenu.visibility = if (expanding) View.VISIBLE else View.GONE
            sizePanelForContent()
        }

        fun showThinkingState() {
            chipsContainer.removeAllViews()
            chipsContainer.addView(TextView(context).apply {
                text = "🐾 Cat is thinking…"
                textSize = 13f
                setTextColor(MUTED_TEXT)
                gravity = Gravity.CENTER
                setPadding(0, 12, 0, 12)
            })
            sizePanelForContent()
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
                setTextColor(0xFFF5F3F7.toInt())
                setHintTextColor(MUTED_TEXT)
                setSingleLine(false)
                minLines = 1
                maxLines = 3
                setPadding(18, 14, 18, 14)
                background = GradientDrawable().apply {
                    setColor(INPUT_BG)
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
                setTextColor(0xFFF5F3F7.toInt())
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
                setTextColor(MUTED_TEXT)
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
                    setTextColor(TIP_ACCENT)
                    gravity = Gravity.CENTER
                    setPadding(8, 12, 8, 12)
                    setOnClickListener {
                        val intent = android.content.Intent(context, SubscriptionActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        dismiss()
                    }
                })
                sizePanelForContent()
                return
            }
            if (suggestions.isEmpty()) {
                chipsContainer.addView(TextView(context).apply {
                    text = "😿 Couldn't think of a reply"
                    textSize = 13f
                    setTextColor(MUTED_TEXT)
                    gravity = Gravity.CENTER
                    setPadding(0, 12, 0, 12)
                })
                sizePanelForContent()
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
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, replyTextSp)
                    setTextColor(0xFFF5F3F7.toInt())
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
                    setTextColor(SOFT_TEXT)
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
                    setTextColor(MUTED_TEXT)
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
            sizePanelForContent()
        }

        renderReplies = ::showReplies

        // Onboarding demo: hardcoded chips, no AI / no real notification
        if (ReplyStore.isDemoMessage(message)) {
            Logger.d("Using onboarding demo replies")
            showReplies(ReplyStore.DEMO_REPLIES, "Demo")
            return
        }

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

    private fun resetPanelHeightToWrap() {
        val panel = panelView ?: return
        val params = panelParams ?: return
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        try {
            windowManager.updateViewLayout(panel, params)
        } catch (_: Exception) { }
    }

    /**
     * Sizes the single-sender panel to exactly fit its children.
     * Message ScrollView grows with content up to (40% screen − header − chips − buttons);
     * no empty gap below the button row.
     */
    private fun applyAutoPanelHeight(
        header: View,
        messageScroll: ScrollView,
        messagePreview: TextView,
        chipsContainer: View,
        bottomRow: View,
        extraBelowButtons: View? = null
    ) {
        val panel = panelView ?: return
        val params = panelParams ?: return
        if (showingSenderList || !isShowing) return

        val contentWidth = (PANEL_WIDTH - panel.paddingLeft - panel.paddingRight).coerceAtLeast(1)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY)
        val heightUnspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        header.measure(widthSpec, heightUnspec)
        messagePreview.measure(widthSpec, heightUnspec)
        chipsContainer.measure(widthSpec, heightUnspec)
        bottomRow.measure(widthSpec, heightUnspec)
        extraBelowButtons?.measure(widthSpec, heightUnspec)

        fun verticalMargins(view: View): Int {
            val lp = view.layoutParams as? LinearLayout.LayoutParams ?: return 0
            return lp.topMargin + lp.bottomMargin
        }

        val nav = navRow
        val navHeight = nav?.let {
            it.measure(widthSpec, heightUnspec)
            it.measuredHeight
        } ?: 0
        val arrow = newSenderArrow
        val arrowHeight = arrow?.let {
            it.measure(widthSpec, heightUnspec)
            it.measuredHeight + verticalMargins(it)
        } ?: 0

        // Fixed chrome only — header / chips / buttons are not multiplied.
        val fixedChrome = panel.paddingTop + panel.paddingBottom +
            header.measuredHeight + verticalMargins(header) +
            chipsContainer.measuredHeight + verticalMargins(chipsContainer) +
            bottomRow.measuredHeight + verticalMargins(bottomRow) +
            (extraBelowButtons?.let { it.measuredHeight + verticalMargins(it) } ?: 0) +
            navHeight + (nav?.let { verticalMargins(it) } ?: 0) +
            arrowHeight +
            verticalMargins(messageScroll)

        // Message-area allotment boost (Large +25%, Extra Large +45%). Small/Normal unchanged.
        // The 40% screen fraction is the baseline ceiling; Large/XL scale that ceiling by the
        // same multiplier so the boosted message allotment can actually take effect on long
        // messages (otherwise min(natural×1.25, 40%−chrome) collapses back to 40%−chrome).
        val messageHeightMultiplier = when (SettingsManager.getReplyTextSizeOption(context)) {
            "large" -> 1.25f
            "xlarge" -> 1.45f
            else -> 1.0f
        }
        val baseMaxPanelHeight =
            (context.resources.displayMetrics.heightPixels * MAX_PANEL_HEIGHT_FRACTION).toInt()
        val maxPanelHeight = (baseMaxPanelHeight * messageHeightMultiplier).toInt()
        val messageBudget = (maxPanelHeight - fixedChrome).coerceAtLeast(dp(40))

        val naturalMessageHeight = messagePreview.measuredHeight
        val preferredMessageHeight = (naturalMessageHeight * messageHeightMultiplier).toInt()
        val messageHeight = preferredMessageHeight.coerceAtMost(messageBudget)

        messageScroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            messageHeight
        )
        messageScroll.isVerticalScrollBarEnabled = naturalMessageHeight > messageHeight

        // Exact fit: window height == sum of children
        params.height = fixedChrome + messageHeight
        try {
            windowManager.updateViewLayout(panel, params)
        } catch (e: Exception) {
            Logger.e("Failed to apply reply panel height: ${e.message}")
        }
        panel.requestLayout()
    }

    /**
     * Inline overflow section shown under the button row (not a PopupWindow).
     * Visibility is toggled by the ⋮ button; panel height recalculates via applyAutoPanelHeight.
     */
    private fun buildInlineOverflowMenu(): LinearLayout {
        val entry = currentEntry
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            background = GradientDrawable().apply {
                setColor(CHIP_BG)
                cornerRadius = dp(14).toFloat()
                setStroke(1, 0x33FFFFFF)
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))

            addView(TextView(context).apply {
                text = if (entry != null) {
                    "Ignore ${entry.sender} forever"
                } else {
                    "Ignore this user forever"
                }
                textSize = 13f
                setTextColor(DANGER)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener {
                    val current = currentEntry ?: return@setOnClickListener
                    showIgnoreUserConfirmation(current)
                }
            })

            addView(View(context).apply {
                setBackgroundColor(0x33FFFFFF)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1
                ).apply { setMargins(dp(10), 0, dp(10), 0) }
            })

            addView(TextView(context).apply {
                text = "Clear all pending replies"
                textSize = 13f
                setTextColor(SOFT_TEXT)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener { clearAllPendingReplies() }
            })
        }
    }

    private fun suggestionChip(text: String, message: ReplyStore.ReplyableMessage): TextView {
        val replyTextSp = SettingsManager.getReplyTextSizeSp(context)
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, replyTextSp)
            setTextColor(0xFFF5F3F7.toInt())
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

    private fun buildDemoInstructions(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(TextView(context).apply {
                text = "Instructions"
                textSize = 11f
                setTextColor(MUTED_TEXT)
                typeface = UiKit.headingTypeface(context)
                setPadding(0, 0, 0, dp(4))
            })
            addView(TextView(context).apply {
                text = "1. Tap the pencil to edit a reply and make it more personal."
                textSize = 11f
                setTextColor(MUTED_TEXT)
                setPadding(0, 0, 0, dp(2))
            })
            addView(TextView(context).apply {
                text = "2. Tap any option to send it as your reply."
                textSize = 11f
                setTextColor(MUTED_TEXT)
            })
        }
    }

    private fun sendReply(message: ReplyStore.ReplyableMessage, replyText: String) {
        if (ReplyStore.isDemoMessage(message)) {
            clearPregeneratedReplies(message)
            ReplyStore.clearDemo()
            pending.remove(message)
            currentIndex = currentIndex.coerceAtMost((pending.size - 1).coerceAtLeast(0))
            OverlayService.instance?.updateBadgeAfterReply()
            SettingsManager.setOnboardingDemoCompleted(context, true)
            android.util.Log.d(
                "ScrollCat",
                "Demo completed, notifying onboarding to show Grant Access"
            )
            showConfirmation("Sent! ✓", notifyDemoSent = true)
            return
        }

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

    private fun showConfirmation(text: String, notifyDemoSent: Boolean = false) {
        val panel = panelView ?: return
        panel.removeAllViews()
        resetPanelHeightToWrap()
        panel.addView(TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFFF5F3F7.toInt())
            typeface = UiKit.headingTypeface(context)
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 28)
        })
        if (notifyDemoSent) {
            handler.post { onDemoReplySent?.invoke() }
        }
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

    /**
     * Jump to the next pending sender the user hasn't viewed yet (priority-first order).
     * Reuses showMessage — same path as ←/→ navigation, including thinking state if replies
     * are still generating.
     */
    private fun jumpToNextUnviewedSender() {
        if (pending.size <= 1) return
        for (offset in 1..pending.size) {
            val idx = (currentIndex + offset) % pending.size
            val entry = pending[idx]
            // Only jump to senders that arrived after this panel opened
            if (entry.conversationKey !in knownOthersAtOpen) {
                currentIndex = idx
                showMessage(entry) // no new snapshot — still same open session
                return
            }
        }
        updateNewSenderIndicator()
    }

    fun refreshPendingFromStore() {
        android.util.Log.d(
            "ScrollCat",
            "refreshPendingFromStore called - pending senders: ${ReplyStore.count()} " +
                "isShowing=$isShowing showingSenderList=$showingSenderList"
        )
        if (!isShowing) return

        val currentKey = currentEntry?.notificationKey
        val livePending = ReplyStore.getAll().toMutableList()
        if (livePending.isEmpty()) {
            dismiss()
            return
        }

        pending = livePending

        // Sender list already shows everyone — never show the ↓ arrow here.
        if (showingSenderList) {
            removeNewSenderArrow()
            if (pending.size == 1) {
                showMessage(pending.first(), captureOpenSnapshot = true)
            } else {
                showSenderList()
            }
            return
        }

        // Single-sender panel (one message + reply chips) — update live while still open.
        val liveIndex = pending.indexOfFirst { it.notificationKey == currentKey }
        if (liveIndex >= 0) {
            currentIndex = liveIndex
            updatePendingFooter()
            val unviewed = hasNewlyArrivedOtherSenders()
            val currentPendingSet = pending.map { it.conversationKey }.toSet()
            android.util.Log.d(
                "ScrollCat",
                "Arrow check - knownAtOpen: $knownOthersAtOpen, currentPending: $currentPendingSet, " +
                    "showing arrow: $unviewed"
            )
            android.util.Log.d(
                "ScrollCat",
                "refreshPendingFromStore - kept current sender, " +
                    "unviewedOthers=$unviewed total=${pending.size} knownAtOpen=$knownOthersAtOpen"
            )
            if (unviewed) {
                showNewSenderArrowLiveOnOpenPanel(currentKey)
            } else {
                removeNewSenderArrow()
                relayoutOpenPanelHeight()
            }
        } else {
            currentIndex = currentIndex.coerceIn(0, pending.size - 1)
            showMessage(pending[currentIndex])
        }
    }

    /**
     * Immediately attach/show the ↓ indicator on the already-visible single-sender panel
     * (does not wait for the next showMessage). No-op for sender-list mode.
     */
    private fun showNewSenderArrowLiveOnOpenPanel(currentSenderKey: String?) {
        if (!isShowing || showingSenderList) return
        val panel = panelView ?: return
        if (!hasNewlyArrivedOtherSenders()) {
            removeNewSenderArrow()
            return
        }

        val unviewedCount = pending.count {
            it.conversationKey !in knownOthersAtOpen
        }

        // Prefer the arrow already attached to this panel; otherwise create and attach it.
        var arrow = newSenderArrow
        if (arrow == null || arrow.parent != panel) {
            arrow = (panel.findViewWithTag<TextView>(TAG_NEW_SENDER_ARROW))
                ?: TextView(context).apply {
                    tag = TAG_NEW_SENDER_ARROW
                    textSize = 20f
                    setTextColor(TIP_ACCENT)
                    gravity = Gravity.CENTER
                    setPadding(12, 10, 12, 4)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setOnClickListener { jumpToNextUnviewedSender() }
                }
            if (arrow.parent != panel) {
                try {
                    (arrow.parent as? android.view.ViewGroup)?.removeView(arrow)
                } catch (_: Exception) { }
                panel.addView(arrow)
            }
            newSenderArrow = arrow
        }

        arrow.text = if (unviewedCount > 1) "↓  $unviewedCount new" else "↓  New message"
        arrow.visibility = View.VISIBLE
        android.util.Log.d(
            "ScrollCat",
            "Arrow shown live on open panel for current sender, while viewing: $currentSenderKey"
        )

        // Fixed panel height from applyAutoPanelHeight would clip a newly added arrow —
        // expand to wrap so it becomes visible immediately.
        relayoutOpenPanelHeight()
    }

    private fun relayoutOpenPanelHeight() {
        val panel = panelView ?: return
        val params = panelParams ?: return
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        panel.requestLayout()
        try {
            windowManager.updateViewLayout(panel, params)
        } catch (_: Exception) { }
    }

    /** True only for senders that arrived after this single-sender panel opened. */
    private fun hasNewlyArrivedOtherSenders(): Boolean {
        return pending.any { entry ->
            entry.conversationKey !in knownOthersAtOpen
        }
    }

    private fun removeNewSenderArrow() {
        newSenderArrow?.let { arrow ->
            try { (arrow.parent as? android.view.ViewGroup)?.removeView(arrow) } catch (_: Exception) { }
        }
        panelView?.findViewWithTag<View>(TAG_NEW_SENDER_ARROW)?.let { tagged ->
            try { (tagged.parent as? android.view.ViewGroup)?.removeView(tagged) } catch (_: Exception) { }
        }
        newSenderArrow = null
    }

    /**
     * Down-arrow for single-sender panel only. Prefer showNewSenderArrowLiveOnOpenPanel
     * when refreshing an already-open panel.
     */
    private fun updateNewSenderIndicator() {
        if (!isShowing || showingSenderList) {
            removeNewSenderArrow()
            return
        }
        if (!hasNewlyArrivedOtherSenders()) {
            removeNewSenderArrow()
            return
        }
        showNewSenderArrowLiveOnOpenPanel(currentEntry?.notificationKey)
    }

    private fun updatePendingFooter() {
        val panel = panelView ?: return
        if (pending.size <= 1) {
            navRow?.let { row ->
                try { (row.parent as? android.view.ViewGroup)?.removeView(row) } catch (e: Exception) { }
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
                setTextColor(NAV_ACCENT)
                gravity = Gravity.CENTER
                setPadding(24, 8, 24, 8)
                setOnClickListener { previous() }
            })
            pendingCountView = TextView(context).apply {
                textSize = 11f
                setTextColor(MUTED_TEXT)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(pendingCountView)
            row.addView(TextView(context).apply {
                text = "→"
                textSize = 18f
                setTextColor(NAV_ACCENT)
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
            cancelShadeNotification(entry)
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
        CatNotificationListener.instance?.cancelSystemNotifications(
            allPending.map { it.notificationKey }
        )
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

    /** Clears the OS notification shade entry; does not mark the chat read in-app. */
    private fun cancelShadeNotification(entry: ReplyStore.ReplyableMessage) {
        if (ReplyStore.isDemoMessage(entry)) return
        CatNotificationListener.instance?.cancelSystemNotification(entry.notificationKey)
    }

    private fun senderKeyFor(message: ReplyStore.ReplyableMessage): String {
        return if (message.packageName == "com.whatsapp") {
            "${message.packageName}:${message.sender.replace(Regex("\\s*\\(\\d+\\s*messages?\\)", RegexOption.IGNORE_CASE), "").trim()}"
        } else {
            "${message.packageName}:${message.notificationId}"
        }
    }

    private fun formatReceivedTime(timestampMs: Long): String {
        if (timestampMs <= 0L) return ""
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMs))
    }

    private fun clearPregeneratedReplies(message: ReplyStore.ReplyableMessage) {
        CatNotificationListener.instance?.clearPregeneratedReplies(
            message.packageName,
            message.notificationId,
            message.sender,
            message.message
        ) ?: ReplyStore.clearStoredReplies(senderKeyFor(message))
    }

    /**
     * Builds a fresh ImageView for the sender's app icon.
     * ReplyPanel is fully programmatic (no XML layout / no android:src default).
     * Called on every showMessage / sender-list row — never recycled across messages.
     */
    private fun appIconView(
        packageName: String,
        sizeDp: Int,
        viewContext: Context = context
    ): ImageView {
        val size = dp(sizeDp)
        val pm = context.applicationContext.packageManager
        return ImageView(viewContext).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            // Material/theme tint can wash out package icons — keep the raw drawable.
            imageTintList = null
            clearColorFilter()
            tag = packageName

            // Clear any stale/default drawable before lookup (every message, every show).
            android.util.Log.d("ScrollCat", "Clearing app icon before loading for $packageName")
            setImageDrawable(null)
            background = null

            // Fake onboarding package is never installed — skip lookup/retry.
            if (packageName == ReplyStore.DEMO_PACKAGE ||
                packageName.startsWith("com.example.scrollcat.demo")
            ) {
                android.util.Log.d(
                    "ScrollCat",
                    "Setting app icon for $packageName - success: false, reason: demo package (skip lookup)"
                )
                setBackgroundColor(PLACEHOLDER_BG)
                return@apply
            }

            fun applyIconOrNull(): Exception? {
                return try {
                    val icon = pm.getApplicationIcon(packageName)
                    setImageDrawable(icon)
                    background = null
                    null
                } catch (e: Exception) {
                    e
                }
            }

            fun showNeutralPlaceholder() {
                setImageDrawable(null)
                setBackgroundColor(PLACEHOLDER_BG)
            }

            val firstError = applyIconOrNull()
            if (firstError == null && drawable != null) {
                android.util.Log.d(
                    "ScrollCat",
                    "Setting app icon for $packageName - success: true"
                )
            } else {
                val reason = firstError?.let {
                    "${it.javaClass.simpleName}: ${it.message}"
                } ?: "drawable was null after getApplicationIcon"
                android.util.Log.d(
                    "ScrollCat",
                    "Setting app icon for $packageName - success: false, reason: $reason"
                )
                showNeutralPlaceholder()

                // Inside ImageView.apply {}, bare `handler` is View.getHandler() (null until attached).
                // Always use an explicit main-looper Handler for the retry.
                val retryHandler = try {
                    Handler(Looper.getMainLooper())
                } catch (e: Exception) {
                    android.util.Log.w(
                        "ScrollCat",
                        "No Handler for icon retry ($packageName): ${e.message} — skipping retry"
                    )
                    null
                }
                if (retryHandler == null) return@apply

                retryHandler.postDelayed({
                    // Panel may have moved on; only apply if this view is still for the same package
                    if (tag != packageName) return@postDelayed
                    val retryError = applyIconOrNull()
                    if (retryError == null && drawable != null) {
                        android.util.Log.d(
                            "ScrollCat",
                            "Setting app icon for $packageName - retry success: true"
                        )
                    } else {
                        val retryReason = retryError?.let {
                            "${it.javaClass.simpleName}: ${it.message}"
                        } ?: "drawable was null after getApplicationIcon"
                        android.util.Log.d(
                            "ScrollCat",
                            "Setting app icon for $packageName - retry success: false, reason: $retryReason"
                        )
                    }
                }, 200L)
            }
        }
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
        navRow = null
        pendingCountView = null
        newSenderArrow = null
        viewedKeys.clear()
        knownOthersAtOpen.clear()
        showingSenderList = false
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
