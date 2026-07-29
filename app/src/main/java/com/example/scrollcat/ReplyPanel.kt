package com.example.scrollcat

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
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
        /** Compact footprint for the listening-only dictation bubble. */
        private const val DICTATION_PANEL_WIDTH = 220
        private const val CONFIRMATION_MS = 3000L
        /** Fade-out / fade-in duration when switching between queued messages. */
        private const val MESSAGE_CROSSFADE_MS = 130L
        private const val ACCENT = 0xFFB39DDB.toInt()
        private const val PANEL_BG = 0xF21E1E28.toInt()
        private const val CHIP_BG = 0xFF2A2A36.toInt()
        private const val VOICE_CHIP_BG = 0xFF3D3555.toInt()
        private const val VOICE_CHIP_STROKE = 0xFFB39DDB.toInt()
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

    private var panelView: ViewGroup? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    // Routes to Claude when an API key is set, on-device Gemini Nano otherwise
    private val generator = ClaudeReplyGenerator(context)
    private var pending: MutableList<ReplyStore.ReplyableMessage> = mutableListOf()
    private var currentEntry: ReplyStore.ReplyableMessage? = null
    private var currentIndex = 0
    /** Invalidates in-flight message crossfades when a newer navigation starts. */
    private var messageCrossfadeToken = 0
    private var navRow: LinearLayout? = null
    private var pendingCountView: TextView? = null
    private var newSenderArrow: TextView? = null
    /** Variable content above the fixed footer (header → chips). */
    private var messageBodyColumn: LinearLayout? = null
    /** Combined fixed footer: button row + overflow/demo + nav row. */
    private var messageFooterBlock: LinearLayout? = null
    /** Re-run single-message panel sizing (set while a message panel is showing). */
    private var messagePanelRelayout: (() -> Unit)? = null
    /** Notification entryIds the user has already viewed in this open panel session. */
    private val viewedKeys = mutableSetOf<String>()
    /**
     * Entry IDs that already existed when this panel session opened (or that the
     * user has since viewed via the ↓ arrow). Arrow only for arrivals after this —
     * including a second message from the **same** sender.
     */
    private val knownEntryIdsAtOpen = mutableSetOf<String>()
    private var showingSenderList = false
    var onDismissed: (() -> Unit)? = null
    /** Fired when the onboarding demo reply panel first opens (message + chips). */
    var onDemoPanelShown: (() -> Unit)? = null
    /** Fired after the onboarding demo shows its Sent confirmation (not a real send). */
    var onDemoReplySent: (() -> Unit)? = null
    private var demoInstructionsDismissed = false
    private var demoPanelShownNotified = false
    private var voiceListening = false
    private var micPulseAnimator: ObjectAnimator? = null
    private val voiceTranslator by lazy { ScreenTranslator(context) }
    /** True while showing the minimal no-pending voice-dictation panel. */
    private var dictationMode = false
    private var dictationStatusLabel: TextView? = null
    private var dictationMicIcon: ImageView? = null
    private var dictationRetryHint: TextView? = null

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
        knownEntryIdsAtOpen.clear()
        dismiss()
        isShowing = true

        val panel = ConstraintLayout(context).apply {
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

    /** Start invisible so [softFadeInFromScreenWake] can gently reveal after unlock. */
    fun prepareScreenWakeFadeIn() {
        val panel: View = panelView ?: return
        if (!isShowing) return
        panel.animate().cancel()
        panel.alpha = 0f
    }

    fun softFadeInFromScreenWake(durationMs: Long) {
        val panel: View = panelView ?: return
        if (!isShowing) return
        panel.animate().cancel()
        if (panel.alpha >= 0.99f) {
            panel.alpha = 0f
        }
        panel.animate()
            .alpha(1f)
            .setDuration(durationMs)
            .start()
    }

    /**
     * Compact voice-dictation bubble (chip-sized): ✕, listening pulse, translate toggle,
     * and a Smart Voice helper link. Auto-starts listening; on success inserts at the
     * focused field's cursor via [CatAccessibilityService] and dismisses.
     */
    fun showVoiceDictation(catX: Int, catY: Int, catSize: Int) {
        dismiss()
        isShowing = true
        dictationMode = true
        dictationStatusLabel = null
        dictationMicIcon = null
        dictationRetryHint = null

        val panelW = dp(DICTATION_PANEL_WIDTH)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(PANEL_BG)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0x33FFFFFF)
            }
        }

        val dm = context.resources.displayMetrics
        val x = (catX + catSize / 2 - panelW / 2)
            .coerceIn(dp(8), (dm.widthPixels - panelW - dp(8)).coerceAtLeast(dp(8)))
        val y = (catY - dp(100)).coerceAtLeast(dp(48))

        val params = WindowManager.LayoutParams(
            panelW,
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
            android.util.Log.w("ScrollCat", "Voice dictation panel addView failed: ${e.message}")
            isShowing = false
            dictationMode = false
            return
        }
        panelView = panel
        panelParams = params

        // Baseline text at panel open — used at insertion to decide REPLACE vs SPLICE.
        clearDictationTargetRefs()
        val a11y = CatAccessibilityService.instance
        dictationOriginalNode = a11y?.findFocusedEditableNode()
        dictationTargetSnapshot = a11y?.captureEditableTarget()
        android.util.Log.d(
            "ScrollCat",
            "Voice dictation panel open — baseline='${dictationTargetSnapshot?.textBefore}' " +
                "snapshot=${dictationTargetSnapshot != null}"
        )

        // Top row: mic + status | translate | ✕
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val micIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_mic)
            imageTintList = android.content.res.ColorStateList.valueOf(TIP_ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        dictationMicIcon = micIcon
        topRow.addView(micIcon)

        val status = TextView(context).apply {
            text = "Listening…"
            textSize = 12f
            setTextColor(TIP_ACCENT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        dictationStatusLabel = status
        topRow.addView(status)

        topRow.addView(buildVoiceTranslateToggle(dp(32)).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(2)
            }
        })
        topRow.addView(buildVoiceRomanizeToggle(dp(32)).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(2)
            }
        })

        topRow.addView(TextView(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(MUTED_TEXT)
            setPadding(dp(6), dp(2), dp(2), dp(2))
            setOnClickListener {
                android.util.Log.d("ScrollCat", "Voice dictation cancelled via ✕")
                dismiss()
            }
        })
        panel.addView(topRow)

        val retryHint = TextView(context).apply {
            text = ""
            textSize = 11f
            setTextColor(DANGER)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
            setOnClickListener {
                if (!voiceListening) startDictationListening()
            }
        }
        dictationRetryHint = retryHint
        panel.addView(retryHint)

        panel.addView(TextView(context).apply {
            text = "Choose translation voice in Smart Voice"
            textSize = 10f
            setTextColor(MUTED_TEXT)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            setOnClickListener {
                try {
                    context.startActivity(
                        Intent(context, SmartVoiceActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    android.util.Log.w("ScrollCat", "Open SmartVoice failed: ${e.message}")
                }
            }
        })

        // Tap panel body (status area) to retry after an error
        status.isClickable = true
        status.setOnClickListener {
            if (!voiceListening) startDictationListening()
        }

        android.util.Log.d("ScrollCat", "Voice dictation panel shown — auto-starting listen")
        startDictationListening()
    }

    /**
     * Compact card (same footprint as voice-dictation) explaining why Accessibility
     * is needed for voice-to-text, with Grant Access → system Accessibility settings.
     */
    fun showAccessibilityExplanation(catX: Int, catY: Int, catSize: Int) {
        dismiss()
        isShowing = true
        dictationMode = false

        val panelW = dp(DICTATION_PANEL_WIDTH)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(PANEL_BG)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0x33FFFFFF)
            }
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(context).apply {
            text = "Voice in any app"
            textSize = 12f
            setTextColor(TIP_ACCENT)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(TextView(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(MUTED_TEXT)
            setPadding(dp(6), dp(2), dp(2), dp(2))
            setOnClickListener {
                android.util.Log.d("ScrollCat", "Accessibility explanation dismissed via ✕")
                dismiss()
            }
        })
        panel.addView(topRow)

        panel.addView(TextView(context).apply {
            text = "You can use voice-to-text in any app"
            textSize = 12f
            setTextColor(0xFFF5F3F7.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(10))
        })

        panel.addView(TextView(context).apply {
            text = "Grant Access"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = dp(16).toFloat()
            }
            setOnClickListener {
                android.util.Log.d("ScrollCat", "Accessibility explanation — opening settings")
                try {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    android.util.Log.w(
                        "ScrollCat",
                        "Open Accessibility settings failed: ${e.message}"
                    )
                }
                dismiss()
            }
        })

        val dm = context.resources.displayMetrics
        val x = (catX + catSize / 2 - panelW / 2)
            .coerceIn(dp(8), (dm.widthPixels - panelW - dp(8)).coerceAtLeast(dp(8)))
        val y = (catY - dp(120)).coerceAtLeast(dp(48))

        val params = WindowManager.LayoutParams(
            panelW,
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
            android.util.Log.w(
                "ScrollCat",
                "Accessibility explanation panel addView failed: ${e.message}"
            )
            isShowing = false
            return
        }
        panelView = panel
        panelParams = params
        android.util.Log.d("ScrollCat", "Accessibility explanation card shown")
    }

    private fun setDictationListeningUi() {
        voiceListening = true
        dictationRetryHint?.visibility = View.GONE
        dictationStatusLabel?.apply {
            text = "Listening…"
            setTextColor(TIP_ACCENT)
        }
        dictationMicIcon?.imageTintList =
            android.content.res.ColorStateList.valueOf(TIP_ACCENT)
        micPulseAnimator?.cancel()
        dictationMicIcon?.let { icon ->
            micPulseAnimator = ObjectAnimator.ofFloat(icon, View.ALPHA, 1f, 0.35f).apply {
                duration = 650L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun setDictationErrorUi(message: String) {
        voiceListening = false
        micPulseAnimator?.cancel()
        micPulseAnimator = null
        dictationMicIcon?.alpha = 1f
        dictationMicIcon?.imageTintList =
            android.content.res.ColorStateList.valueOf(DANGER)
        dictationStatusLabel?.apply {
            text = message.ifBlank { "Didn't catch that, try again" }
            setTextColor(DANGER)
        }
        dictationRetryHint?.apply {
            text = "Tap to try again"
            visibility = View.VISIBLE
        }
    }

    /** Snapshot of the focused field, taken before recognition starts. */
    private var dictationTargetSnapshot:
        CatAccessibilityService.EditableTargetSnapshot? = null
    /** Pre-recognition node kept only for stale-vs-fresh diagnostic logging. */
    private var dictationOriginalNode: android.view.accessibility.AccessibilityNodeInfo? = null

    private fun startDictationListening() {
        if (!dictationMode || panelView == null) return
        if (!RecordAudioPermissionActivity.isSpeechRecognitionAvailable(context)) {
            setDictationErrorUi("Speech not available")
            return
        }

        // Capture target only if missing (e.g. first listen). Retries keep the panel-open baseline
        // so "unchanged since open" still means REPLACE for placeholders.
        if (dictationTargetSnapshot == null) {
            clearDictationTargetRefs()
            val a11y = CatAccessibilityService.instance
            dictationOriginalNode = a11y?.findFocusedEditableNode()
            dictationTargetSnapshot = a11y?.captureEditableTarget()
            android.util.Log.d(
                "ScrollCat",
                "Voice dictation target captured snapshot=${dictationTargetSnapshot != null} " +
                    "originalNode=${dictationOriginalNode != null} " +
                    "baseline='${dictationTargetSnapshot?.textBefore}'"
            )
        }

        setDictationListeningUi()
        RecordAudioPermissionActivity.start(
            context,
            object : RecordAudioPermissionActivity.Callback {
                override fun onListening() {
                    handler.post { if (dictationMode) setDictationListeningUi() }
                }

                override fun onTranscript(text: String) {
                    handler.post {
                        if (!dictationMode) return@post
                        voiceListening = false
                        micPulseAnimator?.cancel()
                        dictationStatusLabel?.text = "Inserting…"
                        resolveVoiceTranscript(text) { resolved ->
                            // Brief delay so the transparent Activity can finish and focus can settle,
                            // then ALWAYS re-resolve a fresh node (never reuse dictationOriginalNode).
                            handler.postDelayed({
                                if (!dictationMode) return@postDelayed
                                val ok = CatAccessibilityService.instance?.insertTextAtCursor(
                                    text = resolved,
                                    snapshot = dictationTargetSnapshot,
                                    originalNodeForLog = dictationOriginalNode
                                ) == true
                                android.util.Log.d(
                                    "ScrollCat",
                                    "Voice dictation insert success=$ok textLen=${resolved.length}"
                                )
                                clearDictationTargetRefs()
                                if (ok) {
                                    dismiss()
                                } else {
                                    setDictationErrorUi(
                                        "Couldn't insert — is the field still focused?"
                                    )
                                }
                            }, 200L)
                        }
                    }
                }

                override fun onError(message: String) {
                    handler.post {
                        clearDictationTargetRefs()
                        if (dictationMode) setDictationErrorUi(message)
                    }
                }

                override fun onCancelled() {
                    handler.post { clearDictationTargetRefs() }
                }
            },
            source = "dictation"
        )
    }

    private fun clearDictationTargetRefs() {
        try {
            dictationOriginalNode?.recycle()
        } catch (_: Exception) {
        }
        dictationOriginalNode = null
        dictationTargetSnapshot = null
    }

    private fun showSenderList() {
        showingSenderList = true
        currentEntry = null
        navRow = null
        pendingCountView = null
        messageBodyColumn = null
        messageFooterBlock = null
        messagePanelRelayout = null
        val panel: ViewGroup = panelView ?: return
        panel.removeAllViews()
        resetPanelHeightToWrap()
        navRow = null
        pendingCountView = null
        newSenderArrow = null

        val listLp: ViewGroup.LayoutParams = if (panel is ConstraintLayout) {
            ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
        } else {
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = listLp
        }

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
        list.addView(header)

        pending.forEach { entry ->
            list.addView(buildSenderListRow(entry))
        }
        panel.addView(list)
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
            currentIndex = pending.indexOfFirst { it.entryId == entry.entryId }
                .coerceAtLeast(0)
            showMessage(entry, captureOpenSnapshot = true)
        }
        card.addView(row)
        return card
    }

    private fun dismissSenderFromList(entry: ReplyStore.ReplyableMessage) {
        cancelShadeNotification(entry)
        ReplyStore.removeEntry(entry.entryId)
        clearPregeneratedReplies(entry)
        ReplyStore.getAndClearBuffer(senderKeyFor(entry))
        pending.removeAll { it.entryId == entry.entryId }
        OverlayService.instance?.updateBadgeAfterReply()
        Logger.d("Message ignored from list: ${entry.sender}")
        when {
            pending.isEmpty() -> dismiss()
            pending.size == 1 -> showMessage(pending.first(), captureOpenSnapshot = true)
            else -> showSenderList()
        }
    }

    private fun snapshotKnownOthersAtOpen(current: ReplyStore.ReplyableMessage) {
        knownEntryIdsAtOpen.clear()
        // Snapshot ALL pending entry IDs at open (including the one being viewed),
        // so ←/→ between already-known messages never looks like a "new" arrival.
        pending.forEach { entry ->
            knownEntryIdsAtOpen.add(entry.entryId)
        }
        android.util.Log.d(
            "ScrollCat",
            "Panel opened for ${current.notificationKey} - known entryIds at open: " +
                "$knownEntryIdsAtOpen (including current ${current.entryId})"
        )
    }

    private fun showMessage(
        message: ReplyStore.ReplyableMessage,
        captureOpenSnapshot: Boolean = false,
        animateTransition: Boolean = false
    ) {
        val panel = panelView as? ConstraintLayout ?: return
        if (animateTransition && panel.childCount > 0) {
            val token = ++messageCrossfadeToken
            panel.animate().cancel()
            panel.animate()
                .alpha(0f)
                .setDuration(MESSAGE_CROSSFADE_MS)
                .withEndAction {
                    if (token != messageCrossfadeToken || !isShowing || panelView !== panel) {
                        return@withEndAction
                    }
                    showMessage(
                        message,
                        captureOpenSnapshot = captureOpenSnapshot,
                        animateTransition = false
                    )
                    panel.alpha = 0f
                    panel.animate()
                        .alpha(1f)
                        .setDuration(MESSAGE_CROSSFADE_MS)
                        .start()
                }
                .start()
            return
        }

        panel.animate().cancel()
        panel.alpha = 1f
        showingSenderList = false
        pending.indexOfFirst { it.entryId == message.entryId }
            .takeIf { it >= 0 }
            ?.let { currentIndex = it }
        currentEntry = message
        if (captureOpenSnapshot) {
            snapshotKnownOthersAtOpen(message)
        }
        viewedKeys.add(message.entryId)
        knownEntryIdsAtOpen.add(message.entryId)
        panel.removeAllViews()
        navRow = null
        pendingCountView = null
        newSenderArrow = null
        messagePanelRelayout = null
        messageFooterBlock = null

        val contentId = View.generateViewId()
        val footerId = View.generateViewId()

        // Variable content above the footer (header → chips / new-sender arrow).
        val body = LinearLayout(context).apply {
            id = contentId
            orientation = LinearLayout.VERTICAL
            layoutParams = ConstraintLayout.LayoutParams(0, 0).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToTop = footerId
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        messageBodyColumn = body

        // Combined fixed footer: buttons + overflow/demo + nav — never overlaps content.
        val footer = LinearLayout(context).apply {
            id = footerId
            orientation = LinearLayout.VERTICAL
            layoutParams = ConstraintLayout.LayoutParams(
                0,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        messageFooterBlock = footer

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
        body.addView(header)

        // ── Incoming message preview (full text; scrollable if panel is height-capped) ──
        val replyTextSp = SettingsManager.getReplyTextSizeSp(context)
        val fullIncomingMessage = message.message
        val messagePreview = TextView(context).apply {
            text = fullIncomingMessage
            setTextSize(TypedValue.COMPLEX_UNIT_SP, replyTextSp)
            setTextColor(SOFT_TEXT)
            // Trailing padding so long lines don't sit under the copy icon
            setPadding(0, dp(10), dp(28), dp(16))
        }
        val messageScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(
                messagePreview,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        // Existing spacing between message box and chips (was messageArea.bottomMargin).
        val messageChipGapPx = dp(14)
        val scrollChevronOuter = messageChipGapPx // fits entirely in the gap — never covers text
        val scrollChevronIcon = dp(10)
        val scrollChevron = FrameLayout(context).apply {
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CHIP_BG)
                setStroke(dp(1), 0x44FFFFFF)
            }
            layoutParams = FrameLayout.LayoutParams(scrollChevronOuter, scrollChevronOuter).apply {
                gravity = Gravity.CENTER
            }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_expand_more)
                imageTintList = android.content.res.ColorStateList.valueOf(SOFT_TEXT)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = "Scroll for more"
                layoutParams = FrameLayout.LayoutParams(scrollChevronIcon, scrollChevronIcon).apply {
                    gravity = Gravity.CENTER
                }
            })
        }
        val messageChipGap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                messageChipGapPx
            )
            addView(scrollChevron)
        }
        var lastChevronLog: Triple<Int, Int, Boolean>? = null
        fun updateMessageScrollChevron() {
            val layout = messagePreview.layout
            val contentHeight = if (layout != null && messagePreview.lineCount > 0) {
                layout.getLineTop(messagePreview.lineCount) +
                    messagePreview.paddingTop +
                    messagePreview.paddingBottom
            } else {
                messagePreview.measuredHeight
            }
            // Viewport = ScrollView height after auto-grow sizing (not TextView wrap height).
            val visibleHeight = messageScroll.height
            val isOverflowing = contentHeight > visibleHeight
            scrollChevron.visibility = if (isOverflowing) View.VISIBLE else View.GONE
            val state = Triple(contentHeight, visibleHeight, isOverflowing)
            if (state != lastChevronLog) {
                lastChevronLog = state
                android.util.Log.d(
                    "ScrollCat",
                    "Chevron check - contentHeight=$contentHeight, visibleHeight=$visibleHeight, " +
                        "isOverflowing=$isOverflowing, position=in existing gap below message box"
                )
            }
        }
        val copyFeedback = TextView(context).apply {
            text = "Copied!"
            textSize = 11f
            setTextColor(ACCENT)
            visibility = View.GONE
            setPadding(dp(2), 0, dp(2), 0)
        }
        val copyIconHit = dp(32)
        val copyBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_content_copy)
            imageTintList = android.content.res.ColorStateList.valueOf(MUTED_TEXT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Copy message"
            // ~16–18dp glyph inside a slightly larger tap target
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = FrameLayout.LayoutParams(copyIconHit, copyIconHit).apply {
                gravity = Gravity.TOP or Gravity.END
            }
            setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("message", fullIncomingMessage)
                )
                (copyFeedback.tag as? Runnable)?.let { handler.removeCallbacks(it) }
                copyFeedback.animate().cancel()
                copyFeedback.alpha = 1f
                copyFeedback.visibility = View.VISIBLE
                val hide = Runnable {
                    if (copyFeedback.visibility != View.VISIBLE) return@Runnable
                    copyFeedback.animate()
                        .alpha(0f)
                        .setDuration(200L)
                        .withEndAction {
                            copyFeedback.visibility = View.GONE
                            copyFeedback.alpha = 1f
                        }
                        .start()
                }
                copyFeedback.tag = hide
                handler.postDelayed(hide, 1600L)
            }
        }
        val messageArea = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(messageScroll)
            addView(copyBtn)
            addView(copyFeedback, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(28)
                marginEnd = dp(2)
            })
        }
        body.addView(messageArea)
        // Chevron lives in the same visual gap that used to be messageArea.bottomMargin.
        body.addView(messageChipGap)

        // ── Suggestions container ──
        val chipsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
            }
        }
        body.addView(chipsContainer)

        val isGmailMessage = message.packageName.equals("com.google.android.gm", ignoreCase = true)
        // Gmail: preview + Reply in app / Ignore only — no AI chips, mic, or Send.
        if (isGmailMessage) {
            chipsContainer.visibility = View.GONE
        }

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
            // Clear this message only; continue to next queued if any
            ReplyStore.removeEntry(entry.entryId)
            clearPregeneratedReplies(entry)
            OverlayService.instance?.updateBadgeAfterReply()
            continueAfterHandling(entry)
        }

        // Ignore click - dismiss this queue entry (not the whole sender queue)
        ignoreBtn.setOnClickListener {
            val entry = currentEntry ?: return@setOnClickListener
            ReplyStore.removeEntry(entry.entryId)
            clearPregeneratedReplies(entry)
            // Only clear the shade when nothing else still references this notification
            if (ReplyStore.countForNotificationKey(entry.notificationKey) == 0) {
                cancelShadeNotification(entry)
            }
            OverlayService.instance?.updateBadgeAfterReply()
            Logger.d("Message ignored: ${entry.sender}")
            continueAfterHandling(entry)
        }

        bottomRow.addView(replyInAppBtn)
        bottomRow.addView(ignoreBtn)
        bottomRow.addView(moreBtn)
        footer.addView(bottomRow)

        // Content below the button row (demo instructions + inline overflow menu).
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
        footer.addView(belowButtons)

        // Content constrained above footer; footer pinned to panel bottom.
        panel.addView(body)
        panel.addView(footer)

        // Nav row appends inside the footer block (under buttons), not over content.
        updatePendingFooter()
        updateNewSenderIndicator()

        fun logFooterLayout() {
            panel.post {
                val buttonRowBottom = bottomRow.bottom
                val navRowBottom = navRow?.bottom ?: -1
                val footerTop = footer.top
                val contentAreaBottom = body.bottom
                android.util.Log.d(
                    "ScrollCat",
                    "Footer layout - button row bottom=$buttonRowBottom, " +
                        "nav row bottom=$navRowBottom, footer top=$footerTop, " +
                        "content area bottom=$contentAreaBottom"
                )
            }
        }

        fun sizePanelForContent() {
            panel.post {
                applyAutoPanelHeight(
                    header,
                    messageArea,
                    messageScroll,
                    messagePreview,
                    chipsContainer,
                    bottomRow,
                    belowButtons,
                    messageGap = messageChipGap,
                    footerBlock = footer
                )
                // Overflow depends on the allotted viewport after auto-grow.
                messageScroll.post { updateMessageScrollChevron() }
                logFooterLayout()
            }
        }
        messagePanelRelayout = { sizePanelForContent() }

        moreBtn.setOnClickListener {
            val expanding = overflowMenu.visibility != View.VISIBLE
            overflowMenu.visibility = if (expanding) View.VISIBLE else View.GONE
            sizePanelForContent()
        }

        fun showThinkingState() {
            releaseSpeechRecognizer()
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
        /** Once the user expands "AI Replies" (or opens edit), keep chips visible for this message. */
        var aiRepliesExpanded = false

        fun showEditInput(
            initialText: String,
            suggestions: List<String>,
            engine: String,
            openKeyboard: Boolean = true
        ) {
            aiRepliesExpanded = true
            releaseSpeechRecognizer()
            chipsContainer.removeAllViews()
            setPanelFocusable(true)
            if (!openKeyboard) {
                // Focusable so the user can tap the field later, but don't force the IME up.
                panelParams?.let { params ->
                    params.softInputMode =
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                    try {
                        val host: View = panelView ?: return@let
                        windowManager.updateViewLayout(host, params)
                    } catch (_: Exception) { }
                }
            }

            val editColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val input = android.widget.EditText(context).apply {
                setText(initialText)
                setSelection(text.length)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, replyTextSp)
                setTextColor(0xFFF5F3F7.toInt())
                setHintTextColor(MUTED_TEXT)
                setSingleLine(false)
                minLines = 1
                maxLines = 5
                setPadding(18, 14, 18, 14)
                background = GradientDrawable().apply {
                    setColor(INPUT_BG)
                    cornerRadius = 20f
                    setStroke(1, 0x44FFFFFF)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val actionsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            val rowHeight = dp(52)
            val dividerColor = 0x446B6578
            fun toolbarDivider(): View = View(context).apply {
                setBackgroundColor(dividerColor)
                layoutParams = LinearLayout.LayoutParams(dp(1), rowHeight - dp(12)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }
            }

            // ── Cancel (flat text) ──
            val cancelBtn = TextView(context).apply {
                text = "Cancel"
                textSize = 14f
                setTextColor(ACCENT)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
                background = null
                layoutParams = LinearLayout.LayoutParams(0, rowHeight, 1f)
                setOnClickListener {
                    setPanelFocusable(false)
                    releaseSpeechRecognizer()
                    renderReplies(suggestions, engine)
                }
            }

            // ── Continue (mic above + label below, flat) ──
            val continueMic = ImageView(context).apply {
                setImageResource(R.drawable.ic_mic)
                imageTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            val continueLabel = TextView(context).apply {
                text = "Continue"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ACCENT)
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 1
                isSingleLine = true
                setPadding(0, dp(2), 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            }
            val continueBtn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = null
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(0, rowHeight, 1f)
                addView(continueMic)
                addView(continueLabel)
            }

            fun setContinueIdle(error: String? = null) {
                voiceListening = false
                micPulseAnimator?.cancel()
                micPulseAnimator = null
                continueMic.alpha = 1f
                continueMic.imageTintList =
                    android.content.res.ColorStateList.valueOf(ACCENT)
                continueLabel.text = "Continue"
                continueLabel.setTextColor(ACCENT)
                if (error != null) {
                    android.widget.Toast.makeText(
                        context,
                        error,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }

            fun setContinueListening() {
                voiceListening = true
                continueLabel.text = "Listening"
                continueLabel.setTextColor(TIP_ACCENT)
                continueMic.imageTintList =
                    android.content.res.ColorStateList.valueOf(TIP_ACCENT)
                micPulseAnimator?.cancel()
                micPulseAnimator = ObjectAnimator.ofFloat(continueMic, View.ALPHA, 1f, 0.35f).apply {
                    duration = 650L
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
            }

            continueBtn.setOnClickListener {
                if (voiceListening || RecordAudioPermissionActivity.isActive()) {
                    releaseSpeechRecognizer()
                    setContinueIdle()
                    return@setOnClickListener
                }
                if (!RecordAudioPermissionActivity.isSpeechRecognitionAvailable(context)) {
                    setContinueIdle("Speech not available")
                    return@setOnClickListener
                }
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(input.windowToken, 0)
                setContinueListening()
                RecordAudioPermissionActivity.start(
                    context,
                    object : RecordAudioPermissionActivity.Callback {
                        override fun onListening() {
                            handler.post { setContinueListening() }
                        }

                        override fun onTranscript(text: String) {
                            handler.post {
                                setContinueIdle()
                                resolveVoiceTranscript(text) { resolved ->
                                    appendTranscriptToEdit(input, resolved)
                                    // Do not auto-open keyboard after Continue voice append.
                                    val again = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                                        as android.view.inputmethod.InputMethodManager
                                    again.hideSoftInputFromWindow(input.windowToken, 0)
                                }
                            }
                        }

                        override fun onError(message: String) {
                            handler.post { setContinueIdle(message) }
                        }

                        override fun onCancelled() {
                            handler.post { setContinueIdle() }
                        }
                    },
                    source = "continue"
                )
            }

            // ── Send (filled primary segment — Connect-style ACCENT fill + light icon) ──
            val sendIconPad = dp(14)
            val sendBtn = ImageView(context).apply {
                setImageResource(R.drawable.ic_send)
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFF5F3F7.toInt())
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = "Send"
                setPadding(sendIconPad, sendIconPad, sendIconPad, sendIconPad)
                background = GradientDrawable().apply {
                    setColor(ACCENT)
                    cornerRadius = dp(10).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(0, rowHeight, 1f)
                setOnClickListener {
                    val entry = currentEntry ?: return@setOnClickListener
                    val edited = input.text.toString().trim()
                    if (edited.isEmpty()) return@setOnClickListener
                    setPanelFocusable(false)
                    releaseSpeechRecognizer()
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

            actionsRow.addView(cancelBtn)
            actionsRow.addView(toolbarDivider())
            actionsRow.addView(continueBtn)
            actionsRow.addView(toolbarDivider())
            actionsRow.addView(sendBtn)
            editColumn.addView(input)
            editColumn.addView(actionsRow)
            chipsContainer.addView(editColumn)

            if (openKeyboard) {
                input.post {
                    input.requestFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            } else {
                input.clearFocus()
                input.post {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(input.windowToken, 0)
                }
            }
            sizePanelForContent()
        }

        fun showReplies(suggestions: List<String>, engine: String = "Pre-generated") {
            if (!isShowing || currentEntry != message) return
            setPanelFocusable(false)
            releaseSpeechRecognizer()
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

            fun addAllAiChips() {
                if (currentEntry?.hasRemoteInput != true) {
                    chipsContainer.addView(TextView(context).apply {
                        text = "💡 Tap a suggestion to copy it, or tap ✎ to edit first"
                        textSize = 12f
                        setTextColor(MUTED_TEXT)
                        setPadding(16, 8, 16, 8)
                    })
                }
                suggestions.forEach { suggestion ->
                    addEditableChip(suggestion)
                }
            }

            fun addAiRepliesCollapsedButton() {
                chipsContainer.addView(TextView(context).apply {
                    text = "AI Replies"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, replyTextSp)
                    setTextColor(0xFFF5F3F7.toInt())
                    gravity = Gravity.CENTER
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
                    setOnClickListener {
                        aiRepliesExpanded = true
                        showReplies(suggestions, engine)
                    }
                })
            }

            val lineCount = measurePreviewLineCount(messagePreview)
            val showCollapsed = lineCount > 2 && !aiRepliesExpanded
            android.util.Log.d(
                "ScrollCat",
                "Message line count: $lineCount - showing: " +
                    if (showCollapsed) "AI Replies collapsed button" else "3 chips directly"
            )

            if (showCollapsed) {
                addAiRepliesCollapsedButton()
            } else {
                addAllAiChips()
            }
            if (RecordAudioPermissionActivity.isSpeechRecognitionAvailable(context)) {
                chipsContainer.addView(
                    buildVoiceToTextChip(
                        onVoiceTranscript = { text ->
                            resolveVoiceTranscript(text) { resolved ->
                                showEditInput(resolved, suggestions, engine, openKeyboard = false)
                            }
                        },
                        onContentChanged = { sizePanelForContent() }
                    )
                )
            }
            sizePanelForContent()
        }

        renderReplies = ::showReplies

        // Gmail: no reply chips / mic / Groq — message + button row only.
        if (isGmailMessage) {
            Logger.d("Gmail message — skipping reply chips and AI generation")
            sizePanelForContent()
            return
        }

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
            message.message,
            message.entryId
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
                    ReplyStore.storeReplies(message.entryId, replies)
                    showReplies(replies, engine)
                }
            }
        }
    }

    /**
     * Shared Voice→edit pipeline: optionally romanize (L1 script → Latin) or
     * ML-Kit translate Language 1 → Language 2. Modes are mutually exclusive.
     */
    private fun resolveVoiceTranscript(raw: String, onReady: (String) -> Unit) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            onReady(raw)
            return
        }
        fun deliverVoiceTranscript(text: String) {
            // Outgoing Smart Voice output must not trigger incoming screen auto-translate.
            CatAccessibilityService.suppressIncomingScreenTranslate()
            onReady(text)
        }
        val romanizeOn = SettingsManager.isVoiceRomanizeEnabled(context)
        val translateOn = SettingsManager.isVoiceTranslateEnabled(context)
        val lang1 = SettingsManager.getVoiceLanguage1(context)
        val lang2 = SettingsManager.getVoiceLanguage2(context)

        if (romanizeOn) {
            android.util.Log.d("ScrollCat", "Voice transcript romanizing lang1=$lang1")
            val romanized = TransliterationHelper.romanizeText(trimmed, lang1)
            if (romanized.isNullOrBlank()) {
                android.widget.Toast.makeText(
                    context,
                    "Romanize unavailable — using original",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                deliverVoiceTranscript(trimmed)
            } else {
                deliverVoiceTranscript(romanized)
            }
            return
        }

        if (!translateOn || lang1.equals(lang2, ignoreCase = true)) {
            android.util.Log.d(
                "ScrollCat",
                "Voice transcript passthrough (translate=$translateOn lang1=$lang1 lang2=$lang2)"
            )
            deliverVoiceTranscript(trimmed)
            return
        }
        android.util.Log.d(
            "ScrollCat",
            "Voice transcript translating $lang1 → $lang2"
        )
        voiceTranslator.translateBetween(trimmed, lang1, lang2) { translated ->
            handler.post {
                if (translated.isNullOrBlank()) {
                    android.widget.Toast.makeText(
                        context,
                        "Translation unavailable — using original",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    deliverVoiceTranscript(trimmed)
                } else {
                    deliverVoiceTranscript(translated)
                }
            }
        }
    }

    private val voiceTranslatePainters = mutableListOf<() -> Unit>()
    private val voiceRomanizePainters = mutableListOf<() -> Unit>()

    private fun refreshVoiceModeToggles() {
        voiceTranslatePainters.toList().forEach { it.invoke() }
        voiceRomanizePainters.toList().forEach { it.invoke() }
    }

    /** @deprecated Use [refreshVoiceModeToggles] — kept name for call-site clarity. */
    private fun refreshVoiceTranslateToggles() = refreshVoiceModeToggles()

    /** Compact on/off translate control shared by Voice-to-text, Continue, and dictation. */
    private fun buildVoiceTranslateToggle(heightPx: Int = dp(40)): ImageView {
        val toggle = ImageView(context).apply {
            setImageResource(R.drawable.ic_translate)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Translate voice"
            isClickable = true
            isFocusable = true
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(heightPx, heightPx).apply {
                marginStart = dp(4)
            }
        }
        fun paint() {
            val lang1 = SettingsManager.getVoiceLanguage1(context)
            val lang2 = SettingsManager.getVoiceLanguage2(context)
            val sameLang = lang1.equals(lang2, ignoreCase = true)
            if (sameLang && SettingsManager.isVoiceTranslateEnabled(context)) {
                SettingsManager.setVoiceTranslateEnabled(context, false)
            }
            val on = !sameLang && SettingsManager.isVoiceTranslateEnabled(context)
            toggle.isEnabled = !sameLang
            toggle.isClickable = !sameLang
            toggle.isFocusable = !sameLang
            toggle.imageTintList = android.content.res.ColorStateList.valueOf(
                when {
                    sameLang -> MUTED_TEXT
                    on -> TIP_ACCENT
                    else -> MUTED_TEXT
                }
            )
            toggle.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (on) VOICE_CHIP_BG else 0x00000000)
                if (on) setStroke(dp(1), ACCENT) else setStroke(0, 0)
            }
            toggle.alpha = when {
                sameLang -> 0.35f
                on -> 1f
                else -> 0.75f
            }
            toggle.contentDescription = when {
                sameLang -> "Translate unavailable (same language)"
                on -> "Translate voice on"
                else -> "Translate voice off"
            }
        }
        val painter: () -> Unit = { paint() }
        voiceTranslatePainters.add(painter)
        toggle.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                paint()
            }
            override fun onViewDetachedFromWindow(v: View) {
                voiceTranslatePainters.remove(painter)
            }
        })
        paint()
        toggle.setOnClickListener {
            if (!toggle.isEnabled) return@setOnClickListener
            val next = !SettingsManager.isVoiceTranslateEnabled(context)
            SettingsManager.setVoiceTranslateEnabled(context, next)
            refreshVoiceModeToggles()
            android.widget.Toast.makeText(
                context,
                if (next) "Voice translate on" else "Voice translate off",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        return toggle
    }

    /** Compact on/off romanize control (native script → Latin letters). */
    private fun buildVoiceRomanizeToggle(heightPx: Int = dp(40)): ImageView {
        val toggle = ImageView(context).apply {
            setImageResource(R.drawable.ic_romanize)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Romanize voice"
            isClickable = true
            isFocusable = true
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(heightPx, heightPx).apply {
                marginStart = dp(4)
            }
        }
        fun paint() {
            val on = SettingsManager.isVoiceRomanizeEnabled(context)
            toggle.imageTintList = android.content.res.ColorStateList.valueOf(
                if (on) TIP_ACCENT else MUTED_TEXT
            )
            toggle.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (on) VOICE_CHIP_BG else 0x00000000)
                if (on) setStroke(dp(1), ACCENT) else setStroke(0, 0)
            }
            toggle.alpha = if (on) 1f else 0.75f
            toggle.contentDescription = if (on) "Romanize voice on" else "Romanize voice off"
        }
        val painter: () -> Unit = { paint() }
        voiceRomanizePainters.add(painter)
        toggle.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                paint()
            }
            override fun onViewDetachedFromWindow(v: View) {
                voiceRomanizePainters.remove(painter)
            }
        })
        paint()
        toggle.setOnClickListener {
            val next = !SettingsManager.isVoiceRomanizeEnabled(context)
            SettingsManager.setVoiceRomanizeEnabled(context, next)
            refreshVoiceModeToggles()
            android.widget.Toast.makeText(
                context,
                if (next) "Voice romanize on" else "Voice romanize off",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        return toggle
    }

    private fun releaseSpeechRecognizer() {
        voiceListening = false
        micPulseAnimator?.cancel()
        micPulseAnimator = null
        RecordAudioPermissionActivity.cancelActive()
    }

    /** Append [newText] to the edit field, inserting a space when needed; cursor at end. */
    private fun appendTranscriptToEdit(input: android.widget.EditText, newText: String) {
        val trimmedNew = newText.trim()
        if (trimmedNew.isEmpty()) return
        val existing = input.text?.toString().orEmpty()
        val combined = when {
            existing.isEmpty() -> trimmedNew
            existing.last().isWhitespace() -> existing + trimmedNew
            else -> "$existing $trimmedNew"
        }
        input.setText(combined)
        input.setSelection(combined.length)
    }

    /**
     * Action chip (not an AI suggestion): mic + "Voice to text". Hosts listening
     * in a fully transparent Activity; chip shows listening/error states inline.
     */
    private fun buildVoiceToTextChip(
        onVoiceTranscript: (String) -> Unit,
        onContentChanged: () -> Unit
    ): LinearLayout {
        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(VOICE_CHIP_BG)
                cornerRadius = 24f
                setStroke(dp(1), VOICE_CHIP_STROKE)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, dp(4)) }
            // ~18% shorter than previous 12dp vertical padding
            setPadding(dp(14), dp(8), dp(10), dp(8))
        }

        val micIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_mic)
            imageTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                marginEnd = dp(8)
            }
        }
        val label = TextView(context).apply {
            text = "Voice to text"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ACCENT)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val voiceDivider = View(context).apply {
            setBackgroundColor(0x446B6578)
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(22)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(4)
                marginEnd = dp(2)
            }
        }
        chip.addView(micIcon)
        chip.addView(label)
        chip.addView(voiceDivider)
        chip.addView(buildVoiceTranslateToggle(dp(32)))
        chip.addView(buildVoiceRomanizeToggle(dp(32)))

        // Refresh disable/enable when this chip is shown (languages may have changed)
        refreshVoiceModeToggles()

        fun setIdleState(errorMessage: String? = null) {
            voiceListening = false
            micPulseAnimator?.cancel()
            micPulseAnimator = null
            micIcon.alpha = 1f
            micIcon.scaleX = 1f
            micIcon.scaleY = 1f
            micIcon.imageTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            label.text = errorMessage ?: "Voice to text"
            label.setTextColor(if (errorMessage != null) DANGER else ACCENT)
            onContentChanged()
            if (errorMessage != null) {
                handler.postDelayed({
                    if (!voiceListening && label.text == errorMessage) {
                        label.text = "Voice to text"
                        label.setTextColor(ACCENT)
                        onContentChanged()
                    }
                }, 2200L)
            }
        }

        fun setListeningState() {
            voiceListening = true
            label.text = "Listening..."
            label.setTextColor(TIP_ACCENT)
            micIcon.imageTintList = android.content.res.ColorStateList.valueOf(TIP_ACCENT)
            micPulseAnimator?.cancel()
            micPulseAnimator = ObjectAnimator.ofFloat(micIcon, View.ALPHA, 1f, 0.35f).apply {
                duration = 650L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            onContentChanged()
        }

        fun startListening() {
            if (!RecordAudioPermissionActivity.isSpeechRecognitionAvailable(context)) {
                setIdleState("Speech not available")
                return
            }
            setListeningState()
            RecordAudioPermissionActivity.start(
                context,
                object : RecordAudioPermissionActivity.Callback {
                    override fun onListening() {
                        handler.post { setListeningState() }
                    }

                    override fun onTranscript(text: String) {
                        handler.post {
                            setIdleState()
                            onVoiceTranscript(text)
                        }
                    }

                    override fun onError(message: String) {
                        handler.post { setIdleState(message) }
                    }

                    override fun onCancelled() {
                        handler.post { setIdleState() }
                    }
                },
                source = "voice-to-text"
            )
        }

        chip.setOnClickListener {
            if (voiceListening || RecordAudioPermissionActivity.isActive()) {
                releaseSpeechRecognizer()
                setIdleState()
                return@setOnClickListener
            }
            startListening()
        }
        return chip
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
    private fun measurePreviewLineCount(messagePreview: TextView): Int {
        val text = messagePreview.text ?: return 0
        if (text.isEmpty()) return 0
        // Prefer live layout when the preview already has a real width.
        if (messagePreview.width > 0 && messagePreview.layout != null) {
            return messagePreview.lineCount
        }
        val panel: View? = panelView
        val contentWidth = if (panel != null) {
            (PANEL_WIDTH - panel.paddingLeft - panel.paddingRight).coerceAtLeast(1)
        } else {
            PANEL_WIDTH
        }
        val textWidth = (contentWidth - messagePreview.paddingLeft - messagePreview.paddingRight)
            .coerceAtLeast(1)
        val staticLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, messagePreview.paint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(messagePreview.lineSpacingExtra, messagePreview.lineSpacingMultiplier)
            .setIncludePad(messagePreview.includeFontPadding)
            .build()
        return staticLayout.lineCount
    }

    private fun applyAutoPanelHeight(
        header: View,
        messageArea: View,
        messageScroll: ScrollView,
        messagePreview: TextView,
        chipsContainer: View,
        bottomRow: View,
        extraBelowButtons: View? = null,
        messageGap: View? = null,
        footerBlock: View? = null
    ) {
        val panel: ViewGroup = panelView ?: return
        val params = panelParams ?: return
        if (showingSenderList || !isShowing) return

        val contentWidth = (PANEL_WIDTH - panel.paddingLeft - panel.paddingRight).coerceAtLeast(1)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY)
        val heightUnspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        header.measure(widthSpec, heightUnspec)
        messagePreview.measure(widthSpec, heightUnspec)
        chipsContainer.measure(widthSpec, heightUnspec)
        messageGap?.measure(widthSpec, heightUnspec)

        fun verticalMargins(view: View): Int {
            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return 0
            return lp.topMargin + lp.bottomMargin
        }

        // Prefer measuring the combined footer as one block (buttons + overflow + nav).
        val footer: View? = footerBlock ?: messageFooterBlock
        val footerHeight: Int = if (footer != null) {
            footer.measure(widthSpec, heightUnspec)
            footer.measuredHeight + verticalMargins(footer)
        } else {
            bottomRow.measure(widthSpec, heightUnspec)
            extraBelowButtons?.measure(widthSpec, heightUnspec)
            val nav: View? = navRow
            nav?.measure(widthSpec, heightUnspec)
            val extraH: Int = extraBelowButtons?.let { v: View ->
                v.measuredHeight + verticalMargins(v)
            } ?: 0
            val navH: Int = nav?.let { v: View ->
                v.measuredHeight + verticalMargins(v)
            } ?: 0
            bottomRow.measuredHeight + verticalMargins(bottomRow) + extraH + navH
        }

        val arrow: View? = newSenderArrow
        val arrowHeight: Int = arrow?.let { v: View ->
            v.measure(widthSpec, heightUnspec)
            v.measuredHeight + verticalMargins(v)
        } ?: 0

        val chipsHeight = if (chipsContainer.visibility == View.GONE) {
            0
        } else {
            chipsContainer.measuredHeight + verticalMargins(chipsContainer)
        }
        val gapHeight: Int = messageGap?.let { v: View ->
            v.measuredHeight + verticalMargins(v)
        } ?: 0
        val fixedChrome = panel.paddingTop + panel.paddingBottom +
            header.measuredHeight + verticalMargins(header) +
            gapHeight +
            chipsHeight +
            footerHeight +
            arrowHeight +
            verticalMargins(messageArea)

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

        messageArea.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            messageHeight
        )
        messageScroll.isVerticalScrollBarEnabled = naturalMessageHeight > messageHeight

        // Exact fit: content (incl. capped message) + combined footer.
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
            ReplyStore.clearDemo()
            clearPregeneratedReplies(message)
            pending.removeAll { it.entryId == message.entryId }
            currentIndex = currentIndex.coerceAtMost((pending.size - 1).coerceAtLeast(0))
            OverlayService.instance?.updateBadgeAfterReply()
            SettingsManager.setOnboardingDemoCompleted(context, true)
            android.util.Log.d(
                "ScrollCat",
                "Demo completion trigger fired ONCE - proceeding to revealDemoContinueUi"
            )
            // Dispatch NOW (same frame) — do not bury this inside showConfirmation,
            // which can return early if panelView is null and skip the handler entirely.
            try {
                OverlayService.instance?.dispatchOnboardingDemoCompleted()
                    ?: onDemoReplySent?.invoke()
                    ?: android.util.Log.w(
                        "ScrollCat",
                        "Demo completion: no OverlayService handler and no panel callback"
                    )
            } catch (e: Exception) {
                android.util.Log.e("ScrollCat", "Exception in demo completion handling", e)
            }
            showConfirmation("Sent! ✓", notifyDemoSent = false, handled = message)
            return
        }

        // Remove this entry first so sibling detection / notification cancel is accurate
        ReplyStore.removeEntry(message.entryId)
        clearPregeneratedReplies(message)
        pending.removeAll { it.entryId == message.entryId }
        currentIndex = currentIndex.coerceAtMost((pending.size - 1).coerceAtLeast(0))
        OverlayService.instance?.updateBadgeAfterReply()

        val sent = ReplySender.send(context, message, replyText)
        if (sent) {
            // Auto-advance immediately when more remain for this sender (no full close)
            if (advanceToNextSameSenderOrNull(message) != null) {
                return
            }
            showConfirmation("Sent to ${message.sender} ✓", handled = message)
        } else {
            ReplySender.openApp(context, message)
            continueAfterHandling(message)
        }
    }

    /**
     * After handling one queue entry: if the same sender still has queued messages,
     * keep the panel open and show the next one. Otherwise close/dock as today.
     */
    private fun continueAfterHandling(handled: ReplyStore.ReplyableMessage) {
        if (advanceToNextSameSenderOrNull(handled) != null) return

        pending = ReplyStore.getAll().toMutableList()
        val senderKey = senderKeyFor(handled)
        android.util.Log.d(
            "ScrollCat",
            "Post-send check for $senderKey - remaining queue entries: 0, auto-advancing: false"
        )
        when {
            pending.isEmpty() -> dismiss()
            else -> {
                currentIndex = currentIndex.coerceIn(0, pending.size - 1)
                showMessage(pending[currentIndex], animateTransition = true)
            }
        }
    }

    /**
     * If [handled]'s conversation still has queue entries, show the oldest remaining
     * one in-place (panel stays open, dock timer stays paused). Returns that entry,
     * or null if the same-sender queue is empty.
     */
    private fun advanceToNextSameSenderOrNull(
        handled: ReplyStore.ReplyableMessage
    ): ReplyStore.ReplyableMessage? {
        pending = ReplyStore.getAll().toMutableList()
        val senderKey = senderKeyFor(handled)
        val sameSender = pending.filter { it.conversationKey == handled.conversationKey }
        val remaining = sameSender.size
        val nextSame = sameSender.minByOrNull { it.timestamp }
        val autoAdvance = nextSame != null
        android.util.Log.d(
            "ScrollCat",
            "Post-send check for $senderKey - remaining queue entries: $remaining, " +
                "auto-advancing: $autoAdvance"
        )
        if (nextSame == null) return null

        android.util.Log.d(
            "ScrollCat",
            "Same-sender queue: showing next entry ${nextSame.entryId} for ${handled.sender}"
        )
        knownEntryIdsAtOpen.add(nextSame.entryId)
        showQueuedEntry(nextSame)
        return nextSame
    }

    private fun showConfirmation(
        text: String,
        notifyDemoSent: Boolean = false,
        handled: ReplyStore.ReplyableMessage? = null
    ) {
        val panel = panelView ?: return
        panel.removeAllViews()
        messageBodyColumn = null
        messageFooterBlock = null
        messagePanelRelayout = null
        navRow = null
        pendingCountView = null
        resetPanelHeightToWrap()
        val label = TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFFF5F3F7.toInt())
            typeface = UiKit.headingTypeface(context)
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 28)
            layoutParams = if (panel is ConstraintLayout) {
                ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
            } else {
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        }
        panel.addView(label)
        if (notifyDemoSent) {
            // Legacy path — prefer synchronous dispatch from sendReply(demo).
            handler.post {
                try {
                    OverlayService.instance?.dispatchOnboardingDemoCompleted()
                        ?: onDemoReplySent?.invoke()
                } catch (e: Exception) {
                    android.util.Log.e("ScrollCat", "Exception in demo completion handling", e)
                }
            }
        }
        handler.postDelayed({
            if (!isShowing) return@postDelayed
            if (handled != null) {
                continueAfterHandling(handled)
            } else if (pending.isNotEmpty()) {
                currentIndex = currentIndex.coerceIn(0, pending.size - 1)
                showMessage(pending[currentIndex], animateTransition = true)
            } else {
                dismiss()
            }
        }, CONFIRMATION_MS)
    }

    private fun advance() {
        if (pending.size <= 1) return
        currentIndex = (currentIndex + 1) % pending.size
        showMessage(pending[currentIndex], animateTransition = true)
    }

    private fun previous() {
        if (pending.size <= 1) return
        currentIndex = if (currentIndex == 0) pending.size - 1 else currentIndex - 1
        showMessage(pending[currentIndex], animateTransition = true)
    }

    /**
     * Jump to the next newly arrived queue entry (same or different sender).
     * Same-sender arrivals reuse [showQueuedEntry] — the same reveal path as
     * post-Send auto-advance.
     */
    private fun jumpToNextUnviewedSender() {
        val current = currentEntry
        // Prefer a new same-sender entry (not yet in the open-time snapshot)
        if (current != null) {
            val nextSameNew = pending
                .filter {
                    it.conversationKey == current.conversationKey &&
                        it.entryId !in knownEntryIdsAtOpen
                }
                .minByOrNull { it.timestamp }
            if (nextSameNew != null) {
                knownEntryIdsAtOpen.add(nextSameNew.entryId)
                showQueuedEntry(nextSameNew)
                return
            }
        }
        if (pending.size <= 1) {
            updateNewSenderIndicator()
            return
        }
        for (offset in 1..pending.size) {
            val idx = (currentIndex + offset) % pending.size
            val entry = pending[idx]
            if (entry.entryId !in knownEntryIdsAtOpen) {
                knownEntryIdsAtOpen.add(entry.entryId)
                showQueuedEntry(entry)
                return
            }
        }
        updateNewSenderIndicator()
    }

    /** Show a queued entry in-place (panel stays open). Shared by auto-advance and ↓ arrow. */
    private fun showQueuedEntry(entry: ReplyStore.ReplyableMessage) {
        currentIndex = pending.indexOfFirst { it.entryId == entry.entryId }.coerceAtLeast(0)
        showMessage(entry, animateTransition = true)
    }

    fun refreshPendingFromStore() {
        android.util.Log.d(
            "ScrollCat",
            "refreshPendingFromStore called - pending senders: ${ReplyStore.count()} " +
                "isShowing=$isShowing showingSenderList=$showingSenderList"
        )
        if (!isShowing) return

        val currentKey = currentEntry?.entryId
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
        val liveIndex = pending.indexOfFirst { it.entryId == currentKey }
        if (liveIndex >= 0) {
            currentIndex = liveIndex
            updatePendingFooter()
            val unviewed = hasNewlyArrivedMessages()
            val currentPendingSet = pending.map { it.entryId }.toSet()
            android.util.Log.d(
                "ScrollCat",
                "Arrow check - knownAtOpen: $knownEntryIdsAtOpen, currentPending: $currentPendingSet, " +
                    "showing arrow: $unviewed"
            )
            android.util.Log.d(
                "ScrollCat",
                "refreshPendingFromStore - kept current entry, " +
                    "unviewedNew=$unviewed total=${pending.size} knownAtOpen=$knownEntryIdsAtOpen"
            )
            if (unviewed) {
                showNewSenderArrowLiveOnOpenPanel(currentKey)
            } else {
                removeNewSenderArrow()
                relayoutOpenPanelHeight()
            }
        } else {
            currentIndex = currentIndex.coerceIn(0, pending.size - 1)
            showMessage(pending[currentIndex], animateTransition = true)
        }
    }

    /**
     * Immediately attach/show the ↓ indicator on the already-visible single-sender panel
     * (does not wait for the next showMessage). No-op for sender-list mode.
     */
    private fun showNewSenderArrowLiveOnOpenPanel(currentSenderKey: String?) {
        if (!isShowing || showingSenderList) return
        val panel = panelView ?: return
        if (!hasNewlyArrivedMessages()) {
            removeNewSenderArrow()
            return
        }

        val unviewedCount = pending.count {
            it.entryId !in knownEntryIdsAtOpen
        }

        // Prefer the arrow already attached to the body column; otherwise create and attach it.
        val body = messageBodyColumn ?: panel
        var arrow = newSenderArrow
        if (arrow == null || arrow.parent != body) {
            arrow = (body.findViewWithTag<TextView>(TAG_NEW_SENDER_ARROW))
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
            if (arrow.parent != body) {
                try {
                    (arrow.parent as? android.view.ViewGroup)?.removeView(arrow)
                } catch (_: Exception) { }
                body.addView(arrow)
            }
            newSenderArrow = arrow
        }

        arrow.text = if (unviewedCount > 1) "↓  $unviewedCount new" else "↓  New message"
        arrow.visibility = View.VISIBLE
        android.util.Log.d(
            "ScrollCat",
            "Arrow shown live on open panel for current sender, while viewing: $currentSenderKey"
        )

        // Re-run auto height so the weighted body shrinks and the nav footer stays pinned.
        messagePanelRelayout?.invoke() ?: relayoutOpenPanelHeight()
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

    /**
     * True when any pending entry arrived after this panel's open snapshot —
     * including another message from the sender currently being viewed.
     */
    private fun hasNewlyArrivedMessages(): Boolean {
        return pending.any { entry ->
            entry.entryId !in knownEntryIdsAtOpen
        }
    }

    private fun removeNewSenderArrow() {
        newSenderArrow?.let { arrow ->
            try { (arrow.parent as? android.view.ViewGroup)?.removeView(arrow) } catch (_: Exception) { }
        }
        messageBodyColumn?.findViewWithTag<View>(TAG_NEW_SENDER_ARROW)?.let { tagged ->
            try { (tagged.parent as? android.view.ViewGroup)?.removeView(tagged) } catch (_: Exception) { }
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
        if (!hasNewlyArrivedMessages()) {
            removeNewSenderArrow()
            return
        }
        showNewSenderArrowLiveOnOpenPanel(currentEntry?.notificationKey)
    }

    private fun updatePendingFooter() {
        val footer = messageFooterBlock ?: return
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
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
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
        }
        val row = navRow ?: return
        // Always last child of the combined footer block (under the button row).
        try {
            (row.parent as? android.view.ViewGroup)?.removeView(row)
        } catch (_: Exception) { }
        footer.addView(row)

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
            ReplyStore.removeEntry(entry.entryId)
            ReplyStore.getAndClearBuffer(senderKeyFor(entry))
            clearPregeneratedReplies(entry)
        }
        pending.removeAll(ignored.toSet())
        currentIndex = currentIndex.coerceAtMost((pending.size - 1).coerceAtLeast(0))
        OverlayService.instance?.updateBadgeAfterReply()
        if (pending.isEmpty()) {
            dismiss()
        } else {
            showMessage(pending[currentIndex], animateTransition = true)
        }
    }

    private fun clearAllPendingReplies() {
        val allPending = pending.toList()
        CatNotificationListener.instance?.cancelSystemNotifications(
            allPending.map { it.notificationKey }
        )
        allPending.forEach { entry ->
            ReplyStore.removeEntry(entry.entryId)
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
            message.message,
            message.entryId,
            message.conversationKey
        ) ?: ReplyStore.clearStoredReplies(message.entryId)
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
        messageCrossfadeToken++
        handler.removeCallbacksAndMessages(null)
        releaseSpeechRecognizer()
        setPanelFocusable(false)
        panelView?.let {
            it.animate().cancel()
            try { windowManager.removeView(it) } catch (e: Exception) { }
        }
        panelView = null
        panelParams = null
        currentEntry = null
        navRow = null
        pendingCountView = null
        newSenderArrow = null
        messageBodyColumn = null
        messageFooterBlock = null
        messagePanelRelayout = null
        viewedKeys.clear()
        knownEntryIdsAtOpen.clear()
        showingSenderList = false
        dictationMode = false
        dictationStatusLabel = null
        dictationMicIcon = null
        dictationRetryHint = null
        clearDictationTargetRefs()
        micPulseAnimator?.cancel()
        micPulseAnimator = null
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
