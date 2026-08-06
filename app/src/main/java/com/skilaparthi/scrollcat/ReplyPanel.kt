package com.skilaparthi.scrollcat

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
import kotlin.math.abs

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
        /** Reply panel width in dp (was raw `680` px before density fix ≈240dp on xxhdpi). */
        private const val PANEL_WIDTH_DP = 240
        /** Gap left between a pending-replies card and the panel edge, in dp. */
        private const val SENDER_LIST_EDGE_GAP_DP = 8
        /** Pending-replies cards cap their preview here; the chevron reveals the rest. */
        private const val SENDER_LIST_PREVIEW_MAX_LINES = 3
        /** Compact footprint for the listening-only dictation bubble. */
        private const val DICTATION_PANEL_WIDTH = 220
        private const val CONFIRMATION_MS = 3000L
        /** One leg of the directional content slide (out or in). */
        private const val MESSAGE_SLIDE_MS = 150L
        /** Live drag nav: commit if dragged this fraction of slide width, or flicked fast. */
        private const val SWIPE_COMMIT_FRACTION = 0.35f
        private const val SWIPE_MIN_VELOCITY_PX_S = 900f
        private const val SWIPE_HORIZONTAL_DOMINANCE = 1.5f
        /** Rubber-band factor when there's nowhere to navigate (single pending message). */
        private const val SWIPE_RUBBER_BAND = 0.28f
        private const val ACCENT = 0xFFB39DDB.toInt()
        /** ~85% opaque charcoal — glass shell with less see-through distraction (was ~65%). */
        private const val PANEL_BG = 0xD91A1A24.toInt()
        /** Soft second stop for the frosted backdrop gradient (API 31+ blur plate). */
        private const val PANEL_BG_SHEEN = 0xD31E1A2C.toInt()
        /** Slightly stronger glass edge than the old 0x33FFFFFF hairline. */
        private const val PANEL_STROKE = 0x66FFFFFF.toInt()
        private const val PANEL_STROKE_WIDTH_PX = 3
        private const val PANEL_CORNER_MAIN_PX = 36f
        /** ~94% — proportionate bump with PANEL_BG (readable glass chips). */
        private const val CHIP_BG = 0xEF35323F.toInt()
        private const val CHIP_STROKE = 0x55FFFFFF.toInt()
        private const val VOICE_CHIP_BG = 0xEF453A63.toInt()
        private const val VOICE_CHIP_STROKE = 0xFFB39DDB.toInt()
        private const val MAX_PANEL_HEIGHT_FRACTION = 0.4f
        private const val TAG_NEW_SENDER_ARROW = "scrollcat_new_sender_arrow"
        private const val MUTED_TEXT = 0xFFA39BB0.toInt()
        private const val SOFT_TEXT = 0xFFE8E4EF.toInt()
        private const val DANGER = 0xFFFCA5A5.toInt()
        /** Button / input fills — same proportional opacity bump as chips. */
        private const val BUTTON_BG = 0xEF3A3648.toInt()
        private const val INPUT_BG = 0xEF23222E.toInt()
        private const val PLACEHOLDER_BG = 0xE92E2C3A.toInt()
        private const val NAV_ACCENT = 0xFFC4B5E0.toInt()
        private const val TIP_ACCENT = 0xFFE9D5FF.toInt()
        /** RenderEffect blur radius for the static glass backdrop only (API 31+). */
        private const val GLASS_BLUR_RADIUS = 22f
        /** Poll after overlay Grant Access opens system Accessibility settings (~30s). */
        private const val A11Y_GRANT_POLL_INTERVAL_MS = 1_500L
        private const val A11Y_GRANT_POLL_MAX_ATTEMPTS = 20
        private const val A11Y_SUCCESS_CONFIRM_MS = 2_800L
    }

    var isShowing = false
        private set

    private var panelView: ViewGroup? = null
    private var panelParams: WindowManager.LayoutParams? = null
    /**
     * Static frosted plate behind panel content (API 31+ RenderEffect only).
     * Cleared while message slides animate; never runs after [dismiss].
     */
    private var glassBackdropView: View? = null
    private var glassCornerRadiusPx: Float = PANEL_CORNER_MAIN_PX
    private val handler = Handler(Looper.getMainLooper())
    // Routes to Claude when an API key is set, on-device Gemini Nano otherwise
    private val generator = ClaudeReplyGenerator(context)
    private var pending: MutableList<ReplyStore.ReplyableMessage> = mutableListOf()
    private var currentEntry: ReplyStore.ReplyableMessage? = null
    private var currentIndex = 0
    /** Invalidates in-flight message content slides when a newer navigation starts. */
    private var messageSlideToken = 0
    /** True while a ←/→ (or swipe) slide-out/slide-in sequence is running. */
    private var messageSlideInProgress = false
    private var navRow: LinearLayout? = null
    private var pendingCountView: TextView? = null
    private var swipeHintView: TextView? = null
    private var newSenderArrow: TextView? = null
    /** Variable content above the fixed footer (header → slide host). */
    private var messageBodyColumn: LinearLayout? = null
    /** Sliding middle content only: message + chevron + chips (not header/footer). */
    private var messageSlideColumn: LinearLayout? = null
    /** Combined fixed footer: button row + overflow/demo + nav row. */
    private var messageFooterBlock: LinearLayout? = null
    /** Re-run single-message panel sizing (set while a message panel is showing). */
    private var messagePanelRelayout: (() -> Unit)? = null
    /**
     * Same measurement as [messagePanelRelayout], but runs immediately (no panel.post).
     * Used by slide-in so window height is correct before the incoming animation starts.
     */
    private var messagePanelApplyHeightSync: (() -> Unit)? = null
    /** Notification entryIds the user has already viewed in this open panel session. */
    private val viewedKeys = mutableSetOf<String>()
    /**
     * Entry IDs that already existed when this panel session opened (or that the
     * user has since viewed via the ↓ arrow). Arrow only for arrivals after this —
     * including a second message from the **same** sender.
     */
    private val knownEntryIdsAtOpen = mutableSetOf<String>()
    private var showingSenderList = false
    /** Window geometry before the pending-list-only widening/repositioning. */
    private var senderListBaseWindowX: Int? = null
    private var senderListBaseWindowY: Int? = null
    private var senderListBaseWindowWidth: Int? = null
    /**
     * Ring of recent panel open/close timestamps (ms) for rapid open/close diagnostics.
     * Index 0 = oldest of the retained 3.
     */
    private val recentPanelOpenAtsMs = ArrayDeque<Long>(3)
    private val recentPanelCloseAtsMs = ArrayDeque<Long>(3)
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
    /** Message read-aloud engine; created on first speak, shut down in [dismiss]. */
    private var ttsManager: TtsManager? = null
    private var ttsSpeaking = false
    private var speakButtonPaint: (() -> Unit)? = null
    /** True while showing the minimal no-pending voice-dictation panel. */
    private var dictationMode = false
    private var dictationStatusLabel: TextView? = null
    private var dictationMicIcon: ImageView? = null
    private var dictationRetryHint: TextView? = null
    /** Poll after overlay "Grant Access" opens system Accessibility settings. */
    private var a11yGrantPollRunnable: Runnable? = null
    private var a11yGrantSuccessDismissRunnable: Runnable? = null
    private var a11yGrantPollAttempts = 0

    fun resetOnboardingDemoState() {
        demoInstructionsDismissed = false
        demoPanelShownNotified = false
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun panelWidthPx(): Int = dp(PANEL_WIDTH_DP)

    private fun senderListEdgeGapPx(): Int = dp(SENDER_LIST_EDGE_GAP_DP)

    private fun supportsGlassBlur(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Translucent tinted fill + glass-edge stroke for the panel shell (all API levels). */
    private fun glassShellDrawable(cornerRadiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(PANEL_BG)
            cornerRadius = cornerRadiusPx
            setStroke(PANEL_STROKE_WIDTH_PX, PANEL_STROKE)
        }

    /** Soft gradient plate under content — visible depth once blurred on API 31+. */
    private fun frostedBackdropDrawable(cornerRadiusPx: Float): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(PANEL_BG_SHEEN, PANEL_BG)
        ).apply {
            cornerRadius = cornerRadiusPx
        }

    private fun roundRectOutline(cornerRadiusPx: Float): ViewOutlineProvider =
        object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }

    private fun applyRoundedGlassClip(view: View, cornerRadiusPx: Float) {
        view.outlineProvider = roundRectOutline(cornerRadiusPx)
        view.clipToOutline = true
    }

    /**
     * Rebuilds content while re-attaching the static frosted backdrop (API 31+ only).
     * Call instead of bare [ViewGroup.removeAllViews] on glass panels.
     */
    private fun resetGlassPanelContent(panel: ViewGroup, cornerRadiusPx: Float = glassCornerRadiusPx) {
        clearGlassBackdropBlur()
        glassBackdropView = null
        panel.removeAllViews()
        attachGlassBackdrop(panel, cornerRadiusPx)
    }

    private fun attachGlassBackdrop(panel: ViewGroup, cornerRadiusPx: Float) {
        if (!supportsGlassBlur()) return
        // Compact FrameLayout overlays (dictation / a11y explainer) use WindowManager
        // WRAP_CONTENT height. A MATCH_PARENT-height backdrop inside that FrameLayout
        // measures to the full screen on many devices, stretching the overlay into a
        // full-height sheet. ConstraintLayout reply panels size correctly via constraints.
        if (panel is FrameLayout) {
            glassBackdropView = null
            return
        }
        glassCornerRadiusPx = cornerRadiusPx
        val backdrop = View(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = frostedBackdropDrawable(cornerRadiusPx)
            applyRoundedGlassClip(this, cornerRadiusPx)
        }
        glassBackdropView = backdrop
        when (panel) {
            is ConstraintLayout -> panel.addView(
                backdrop,
                0,
                ConstraintLayout.LayoutParams(0, 0).apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
            )
            else -> {
                glassBackdropView = null
                return
            }
        }
        applyGlassBackdropBlurIfAllowed()
    }

    /** Apply blur only when settled and the panel is still showing. */
    private fun applyGlassBackdropBlurIfAllowed() {
        if (!supportsGlassBlur()) return
        val backdrop = glassBackdropView ?: return
        if (!isShowing || messageSlideInProgress) {
            backdrop.setRenderEffect(null)
            return
        }
        backdrop.setRenderEffect(
            RenderEffect.createBlurEffect(
                GLASS_BLUR_RADIUS,
                GLASS_BLUR_RADIUS,
                Shader.TileMode.CLAMP
            )
        )
    }

    private fun clearGlassBackdropBlur() {
        if (!supportsGlassBlur()) return
        glassBackdropView?.setRenderEffect(null)
    }

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
        notePanelOpenForDebug()

        val panel = ConstraintLayout(context).apply {
            setPadding(28, 24, 28, 24)
            background = glassShellDrawable(PANEL_CORNER_MAIN_PX)
            applyRoundedGlassClip(this, PANEL_CORNER_MAIN_PX)
        }
        glassCornerRadiusPx = PANEL_CORNER_MAIN_PX

        val dm = context.resources.displayMetrics
        val panelW = panelWidthPx()
        val edge = dp(16)
        val x = (catX + catSize / 2 - panelW / 2)
            .coerceIn(edge, (dm.widthPixels - panelW - edge).coerceAtLeast(edge))
        // Rough panel height; final height wraps content
        val y = (catY - 520).coerceAtLeast(60)

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
        val corner = dp(20).toFloat()
        val panel = FrameLayout(context).apply {
            background = glassShellDrawable(corner)
            applyRoundedGlassClip(this, corner)
        }
        glassCornerRadiusPx = corner
        attachGlassBackdrop(panel, corner)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
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

        val languageOverflow = buildDictationLanguageOverflowMenu()

        topRow.addView(TextView(context).apply {
            text = "⋮"
            textSize = 18f
            setTextColor(MUTED_TEXT)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(2), dp(4), dp(2))
            contentDescription = "Language options"
            setOnClickListener {
                languageOverflow.visibility =
                    if (languageOverflow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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
        content.addView(topRow)

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
        content.addView(retryHint)

        content.addView(TextView(context).apply {
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

        content.addView(languageOverflow)

        panel.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

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
     * After Grant Access, polls until the service connects (or ~30s), then confirms success.
     */
    fun showAccessibilityExplanation(catX: Int, catY: Int, catSize: Int) {
        dismiss()
        isShowing = true
        dictationMode = false

        val panelW = dp(DICTATION_PANEL_WIDTH)
        val corner = dp(20).toFloat()
        val panel = FrameLayout(context).apply {
            background = glassShellDrawable(corner)
            applyRoundedGlassClip(this, corner)
        }
        glassCornerRadiusPx = corner
        // Shell-only glass for compact FrameLayout cards (no MATCH_PARENT blur plate).
        attachGlassBackdrop(panel, corner)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        val dm = context.resources.displayMetrics
        val x = (catX + catSize / 2 - panelW / 2)
            .coerceIn(dp(8), (dm.widthPixels - panelW - dp(8)).coerceAtLeast(dp(8)))
        // Same positioning pattern as [showVoiceDictation].
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
            android.util.Log.w(
                "ScrollCat",
                "Accessibility explanation panel addView failed: ${e.message}"
            )
            isShowing = false
            return
        }
        panelView = panel
        panelParams = params

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(context).apply {
            text = "Voice in any app"
            textSize = 12f
            setTextColor(TIP_ACCENT)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(titleView)
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
        content.addView(topRow)

        val bodyText = TextView(context).apply {
            text = "You can use voice-to-text in any app"
            textSize = 12f
            setTextColor(0xFFF5F3F7.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(6))
        }
        content.addView(bodyText)

        val tipText = TextView(context).apply {
            text = "Tip: tap 'Installed apps' (or 'Downloaded apps') in the Accessibility list to find ScrollCat — it's not shown at the top by default."
            textSize = 11f
            setTextColor(MUTED_TEXT)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        content.addView(tipText)

        val grantBtn = TextView(context).apply {
            text = "Grant Access"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(ACCENT)
                cornerRadius = dp(16).toFloat()
            }
        }
        grantBtn.setOnClickListener {
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
                return@setOnClickListener
            }
            // Keep card up and poll — don't require the user to re-tap the cat.
            bodyText.text = "Waiting for Accessibility…"
            grantBtn.isEnabled = false
            grantBtn.alpha = 0.45f
            startAccessibilityGrantPoll(panel) {
                if (panelView !== panel || !isShowing) return@startAccessibilityGrantPoll
                SettingsManager.setAccessibilityWasEverEnabled(context, true)
                titleView.text = "You're set"
                bodyText.text =
                    "Accessibility enabled — voice-to-text now works in any app!"
                tipText.visibility = View.GONE
                grantBtn.visibility = View.GONE
                android.util.Log.d(
                    "ScrollCat",
                    "Accessibility explanation — grant detected, showing success"
                )
                a11yGrantSuccessDismissRunnable?.let { handler.removeCallbacks(it) }
                val dismissLater = Runnable {
                    if (panelView === panel && isShowing) dismiss()
                }
                a11yGrantSuccessDismissRunnable = dismissLater
                handler.postDelayed(dismissLater, A11Y_SUCCESS_CONFIRM_MS)
            }
        }
        content.addView(grantBtn)
        panel.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // Clean compact entrance (same spirit as a soft fade — not a full-screen slide).
        panel.alpha = 0f
        panel.animate()
            .alpha(1f)
            .setDuration(160L)
            .start()

        android.util.Log.d("ScrollCat", "Accessibility explanation card shown")
    }

    private fun cancelAccessibilityGrantPoll() {
        a11yGrantPollRunnable?.let { handler.removeCallbacks(it) }
        a11yGrantPollRunnable = null
        a11yGrantSuccessDismissRunnable?.let { handler.removeCallbacks(it) }
        a11yGrantSuccessDismissRunnable = null
        a11yGrantPollAttempts = 0
    }

    private fun startAccessibilityGrantPoll(panel: View, onEnabled: () -> Unit) {
        cancelAccessibilityGrantPoll()
        a11yGrantPollAttempts = 0
        val poll = object : Runnable {
            override fun run() {
                if (!isShowing || panelView !== panel) {
                    a11yGrantPollRunnable = null
                    return
                }
                if (CatAccessibilityService.instance != null) {
                    a11yGrantPollRunnable = null
                    onEnabled()
                    return
                }
                a11yGrantPollAttempts++
                if (a11yGrantPollAttempts >= A11Y_GRANT_POLL_MAX_ATTEMPTS) {
                    android.util.Log.d(
                        "ScrollCat",
                        "Accessibility explanation — grant poll timed out"
                    )
                    a11yGrantPollRunnable = null
                    return
                }
                handler.postDelayed(this, A11Y_GRANT_POLL_INTERVAL_MS)
            }
        }
        a11yGrantPollRunnable = poll
        handler.postDelayed(poll, A11Y_GRANT_POLL_INTERVAL_MS)
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

    /** True while the compact voice-dictation bubble is showing (no pending replies). */
    val isDictationMode: Boolean
        get() = dictationMode

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
        swipeHintView = null
        messageBodyColumn = null
        messageFooterBlock = null
        messageSlideColumn = null
        messagePanelRelayout = null
        messagePanelApplyHeightSync = null
        val panel: ViewGroup = panelView ?: return
        panelParams?.let { params ->
            if (senderListBaseWindowX == null) {
                senderListBaseWindowX = params.x
                senderListBaseWindowY = params.y
                senderListBaseWindowWidth = params.width
            }
        }
        val edgeGap = senderListEdgeGapPx()
        val baseCardWidthPx = panelWidthPx() - (edgeGap * 2)
        val requestedCardWidthPx = baseCardWidthPx * 3 / 2
        val (_, widenedPanelWidthPx) = resizePanelWidthKeepingCenter(
            requestedCardWidthPx + (edgeGap * 2)
        )
        val actualCardWidthPx = widenedPanelWidthPx - (edgeGap * 2)
        val density = context.resources.displayMetrics.density
        val oldWidthDp = (baseCardWidthPx / density + 0.5f).toInt()
        val newWidthDp = (actualCardWidthPx / density + 0.5f).toInt()
        android.util.Log.e(
            "ScrollCat",
            "Pending reply card width changed from ${oldWidthDp}dp to ${newWidthDp}dp " +
                "(${baseCardWidthPx}px -> ${actualCardWidthPx}px)"
        )
        resetGlassPanelContent(panel)
        resetPanelHeightToWrap()
        navRow = null
        pendingCountView = null
        swipeHintView = null
        newSenderArrow = null

        // Sender-list only: let cards spill into the panel's side padding so they run
        // nearly edge to edge. Restored in showMessage() so the reply panel is untouched.
        panel.clipToPadding = false
        panel.clipChildren = false
        val listSideBleed = -(panel.paddingLeft - senderListEdgeGapPx()).coerceAtLeast(0)
        val listLp: ViewGroup.LayoutParams = if (panel is ConstraintLayout) {
            ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                marginStart = listSideBleed
                marginEnd = listSideBleed
            }
        } else {
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = listSideBleed
                marginEnd = listSideBleed
            }
        }
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = listLp
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(2), dp(8))
        }
        header.addView(TextView(context).apply {
            text = "Pending replies"
            textSize = 15f
            setTextColor(0xFFF5F3F7.toInt())
            typeface = UiKit.headingTypeface(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(36) }
        })
        header.addView(TextView(context).apply {
            text = "Ignore all"
            textSize = 13f
            setTextColor(DANGER)
            setPadding(0, dp(4), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(30) }
            setOnClickListener { clearAllPendingReplies() }
        })
        header.addView(TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(MUTED_TEXT)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { dismiss() }
        })
        // Trailing filler keeps both actions left-anchored instead of pinned to the right edge.
        header.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        list.addView(header)

        // Cards can grow taller with multi-line previews — scroll rather than clip, but
        // first expand to use all available screen space between the system bars.
        val cardsCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        pending.forEach { entry ->
            cardsCol.addView(buildSenderListRow(entry))
        }

        val listScrollChevronOuter = dp(14)
        val (screenHeight, availableHeight, topSafe, bottomSafe) = pendingListScreenMetrics()
        val contentWidth = (
            (panelParams?.width ?: panelWidthPx()) - panel.paddingLeft - panel.paddingRight
            ).coerceAtLeast(1)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY)
        val heightUnspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        header.measure(widthSpec, heightUnspec)
        val chevronGapHeight = listScrollChevronOuter + dp(4)
        val chromeOutsideScroll = panel.paddingTop + panel.paddingBottom +
            header.measuredHeight + chevronGapHeight
        val maxListHeight = (availableHeight - chromeOutsideScroll).coerceAtLeast(dp(120))
        val estimatedCardHeight = dp(96).coerceAtLeast(1)
        val estimatedCardsVisible = (maxListHeight / estimatedCardHeight).coerceAtLeast(1)
        android.util.Log.d(
            "ScrollCat",
            "Pending Replies sizing - screen height=$screenHeight, " +
                "calculated available height=$availableHeight, " +
                "cards visible without scroll=$estimatedCardsVisible"
        )

        val cardsScroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val capped = MeasureSpec.makeMeasureSpec(maxListHeight, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, capped)
            }
        }.apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        cardsScroll.addView(cardsCol)

        val listScrollChevronIcon = dp(10)
        val listScrollChevron = FrameLayout(context).apply {
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CHIP_BG)
                setStroke(dp(1), CHIP_STROKE)
            }
            layoutParams = FrameLayout.LayoutParams(
                listScrollChevronOuter,
                listScrollChevronOuter
            ).apply { gravity = Gravity.CENTER }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_expand_more)
                imageTintList = android.content.res.ColorStateList.valueOf(SOFT_TEXT)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = "Scroll for more"
                layoutParams = FrameLayout.LayoutParams(
                    listScrollChevronIcon,
                    listScrollChevronIcon
                ).apply { gravity = Gravity.CENTER }
            })
        }
        val listScrollChevronGap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                chevronGapHeight
            )
            addView(listScrollChevron)
        }
        fun updateListScrollChevron() {
            if (cardsScroll.height <= 0) {
                listScrollChevron.visibility = View.GONE
                return
            }
            val moreBelow = cardsScroll.canScrollVertically(1)
            listScrollChevron.visibility = if (moreBelow) View.VISIBLE else View.GONE
        }
        cardsScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            updateListScrollChevron()
        }
        cardsScroll.viewTreeObserver.addOnGlobalLayoutListener {
            updateListScrollChevron()
        }
        cardsScroll.post { updateListScrollChevron() }

        list.addView(cardsScroll)
        list.addView(listScrollChevronGap)
        panel.addView(list)
        // A tall list can otherwise extend past the bottom of the screen, leaving the last
        // cards unreachable even though the list itself scrolls.
        panel.post { keepSenderListPanelOnScreen(topSafe, bottomSafe) }
    }

    /**
     * Screen height and usable band between system bars (status + nav), with margins.
     * Returns (screenHeight, availableHeight, topSafeY, bottomSafeY).
     */
    private fun pendingListScreenMetrics(): Quadruple {
        val margin = dp(16)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val screenHeight = metrics.bounds.height()
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars()
            )
            val topSafe = insets.top + margin
            val bottomSafe = screenHeight - insets.bottom - margin
            val available = (bottomSafe - topSafe).coerceAtLeast(dp(200))
            Quadruple(screenHeight, available, topSafe, bottomSafe)
        } else {
            val dm = context.resources.displayMetrics
            val screenHeight = dm.heightPixels
            fun sysDimen(name: String): Int {
                val id = context.resources.getIdentifier(name, "dimen", "android")
                return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
            }
            val topSafe = sysDimen("status_bar_height") + margin
            val bottomSafe = screenHeight - sysDimen("navigation_bar_height") - margin
            val available = (bottomSafe - topSafe).coerceAtLeast(dp(200))
            Quadruple(screenHeight, available, topSafe, bottomSafe)
        }
    }

    /** Tiny tuple helper so pending-list sizing can return four ints without a Pair nest. */
    private data class Quadruple(
        val screenHeight: Int,
        val availableHeight: Int,
        val topSafe: Int,
        val bottomSafe: Int
    )

    /** Nudges the sender-list window so it stays inside the top/bottom safe band. */
    private fun keepSenderListPanelOnScreen(topSafe: Int, bottomSafe: Int) {
        if (!showingSenderList) return
        val panel = panelView ?: return
        val params = panelParams ?: return
        val panelHeight = panel.height.takeIf { it > 0 } ?: return
        val maxY = (bottomSafe - panelHeight).coerceAtLeast(topSafe)
        val clampedY = params.y.coerceIn(topSafe, maxY)
        if (clampedY != params.y) {
            params.y = clampedY
            try {
                windowManager.updateViewLayout(panel, params)
            } catch (_: Exception) { }
        }
    }

    private fun buildSenderListRow(entry: ReplyStore.ReplyableMessage): MaterialCardView {
        val themed = materialContext
        val card = MaterialCardView(themed).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(2).toFloat()
            strokeWidth = 1
            strokeColor = CHIP_STROKE
            setCardBackgroundColor(CHIP_BG)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, dp(6)) }
        }

        val row = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(10), dp(10), dp(4), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val iconSize = dp(36)
        row.addView(appIconView(entry.packageName, sizeDp = 36, viewContext = themed).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                setMargins(0, dp(2), dp(10), 0)
            }
        })

        val textCol = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val senderHeader = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        senderHeader.addView(TextView(themed).apply {
            text = entry.sender
            textSize = 14f
            setTextColor(0xFFF5F3F7.toInt())
            typeface = UiKit.headingTypeface(context)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        })
        val headerCollapseChevron = FrameLayout(themed).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CHIP_BG)
                setStroke(dp(1), CHIP_STROKE)
            }
            contentDescription = "Show less"
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                marginStart = dp(6)
                marginEnd = dp(4)
            }
            addView(ImageView(themed).apply {
                setImageResource(R.drawable.ic_expand_more)
                imageTintList = android.content.res.ColorStateList.valueOf(SOFT_TEXT)
                rotation = 180f
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                    gravity = Gravity.CENTER
                }
            })
        }
        senderHeader.addView(headerCollapseChevron)
        textCol.addView(senderHeader)
        val preview = TextView(themed).apply {
            text = entry.message
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                SettingsManager.getReplyTextSizeSp(context)
            )
            setTextColor(SOFT_TEXT)
            isSingleLine = false
            // setSingleLine(false) resets maxLines internally, so apply the 3-line cap after it.
            maxLines = SENDER_LIST_PREVIEW_MAX_LINES
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        textCol.addView(preview)
        textCol.addView(
            buildSenderListExpandChevron(themed, preview, headerCollapseChevron)
        )
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

        // Timestamp stacked directly above ✕ so it no longer competes with the preview text.
        val trailingCol = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4)
                topMargin = dp(2)
            }
        }
        trailingCol.addView(TextView(themed).apply {
            text = formatReceivedTime(entry.timestamp)
            textSize = 10f
            setTextColor(MUTED_TEXT)
            maxLines = 1
        })
        trailingCol.addView(TextView(themed).apply {
            text = "✕"
            textSize = 15f
            setTextColor(MUTED_TEXT)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener { dismissSenderFromList(entry) }
        })
        row.addView(trailingCol)

        row.setOnClickListener {
            // Per-card expand/collapse is local to this preview TextView — no ReplyPanel-level
            // shared expand flag. Capture before showMessage tears the list down.
            val wasExpandedInList =
                headerCollapseChevron.visibility == View.VISIBLE ||
                    preview.maxLines == Integer.MAX_VALUE
            currentIndex = pending.indexOfFirst { it.entryId == entry.entryId }
                .coerceAtLeast(0)
            showMessage(
                entry,
                captureOpenSnapshot = true,
                fromPendingList = true,
                wasExpandedInList = wasExpandedInList
            )
        }
        card.addView(row)
        return card
    }

    /**
     * Same expand affordance as the reply panel's overflow chevron: shown only when the
     * capped preview hides text, tapping it reveals the rest in place.
     */
    private fun buildSenderListExpandChevron(
        themed: Context,
        preview: TextView,
        headerCollapseChevron: View
    ): FrameLayout {
        // Every card starts collapsed; only the chevron may reveal the full message.
        preview.isSingleLine = false
        preview.maxLines = SENDER_LIST_PREVIEW_MAX_LINES
        preview.ellipsize = android.text.TextUtils.TruncateAt.END
        val outer = dp(14)
        val icon = dp(10)
        val chevronIcon = ImageView(themed).apply {
            setImageResource(R.drawable.ic_expand_more)
            imageTintList = android.content.res.ColorStateList.valueOf(SOFT_TEXT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Show full message"
            layoutParams = FrameLayout.LayoutParams(icon, icon).apply {
                gravity = Gravity.CENTER
            }
        }
        val chevron = FrameLayout(themed).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CHIP_BG)
                setStroke(dp(1), CHIP_STROKE)
            }
            layoutParams = LinearLayout.LayoutParams(outer, outer).apply {
                gravity = Gravity.START
                topMargin = dp(2)
            }
            addView(chevronIcon)
        }
        chevron.setOnClickListener {
            preview.maxLines = Integer.MAX_VALUE
            preview.ellipsize = null
            chevron.visibility = View.GONE
            headerCollapseChevron.visibility = View.VISIBLE
        }
        headerCollapseChevron.setOnClickListener {
            preview.maxLines = SENDER_LIST_PREVIEW_MAX_LINES
            preview.ellipsize = android.text.TextUtils.TruncateAt.END
            headerCollapseChevron.visibility = View.GONE
            chevron.visibility = View.VISIBLE
        }
        preview.post {
            val width = preview.width - preview.paddingLeft - preview.paddingRight
            if (width <= 0) return@post
            val text = preview.text ?: return@post
            val fullLines = StaticLayout.Builder
                .obtain(text, 0, text.length, preview.paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(preview.lineSpacingExtra, preview.lineSpacingMultiplier)
                .setIncludePad(preview.includeFontPadding)
                .build()
                .lineCount
            chevron.visibility = if (fullLines > SENDER_LIST_PREVIEW_MAX_LINES) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
        return chevron
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
            pending.size == 1 -> {
                val remainingCount = pending.size
                val panel = panelView
                val header = messageBodyColumn?.getChildAt(0)
                val body = messageBodyColumn
                val footer = messageFooterBlock
                android.util.Log.e(
                    "ScrollCat",
                    "###LIST_TO_SINGLE_DEBUG### closing card from list, " +
                        "remaining count=$remainingCount, " +
                        "panel.childCount=${panel?.childCount}, " +
                        "header.visibility=${header?.visibility}, " +
                        "body.visibility=${body?.visibility}, " +
                        "footer.visibility=${footer?.visibility}, " +
                        "body.height=${body?.height}, " +
                        "panelParams.height=${panelParams?.height}"
                )
                // Same showMessage rebuild as card-tap / open — NOT a separate shortcut UI.
                // fromPendingList=false here (unlike card tap). Height is NOT sync-applied
                // on this call site; normal msgs rely on posted sizePanelForContent() from
                // showThinking/showReplies (Gmail uses sync applyPanelHeightNow inside
                // showMessage). 3-dot overflow also calls sizePanelForContent() — same
                // relayout that makes content reappear when this bug hits.
                showMessage(pending.first(), captureOpenSnapshot = true)
                fun logListToSingle(phase: String) {
                    val p = panelView
                    val h = messageBodyColumn?.getChildAt(0)
                    val b = messageBodyColumn
                    val f = messageFooterBlock
                    android.util.Log.e(
                        "ScrollCat",
                        "###LIST_TO_SINGLE_DEBUG### $phase closing card from list, " +
                            "remaining count=$remainingCount, " +
                            "panel.childCount=${p?.childCount}, " +
                            "header.visibility=${h?.visibility}, " +
                            "body.visibility=${b?.visibility}, " +
                            "footer.visibility=${f?.visibility}, " +
                            "body.height=${b?.height}, " +
                            "body.measuredH=${b?.measuredHeight}, " +
                            "panelParams.height=${panelParams?.height}, " +
                            "hasApplyHeightSync=${messagePanelApplyHeightSync != null}"
                    )
                }
                logListToSingle("AFTER showMessage return")
                // Two posts: run after showMessage's posted sizePanelForContent apply.
                panelView?.post {
                    panelView?.post { logListToSingle("AFTER nested post (past sizePanelForContent?)") }
                }
            }
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

    private enum class MessageSlideDirection {
        NEXT,
        PREVIOUS
    }

    private fun showMessage(
        message: ReplyStore.ReplyableMessage,
        captureOpenSnapshot: Boolean = false,
        slideDirection: MessageSlideDirection? = null,
        fromPendingList: Boolean = false,
        wasExpandedInList: Boolean = false
    ) {
        val panel = panelView as? ConstraintLayout ?: return
        val existingSlide = messageSlideColumn
        if (slideDirection != null && existingSlide != null && existingSlide.parent != null) {
            val previousEntryId = currentEntry?.entryId ?: "none"
            val token = ++messageSlideToken
            messageSlideInProgress = true
            clearGlassBackdropBlur()
            val widthPx = existingSlide.width
                .takeIf { it > 0 }
                ?: (panelWidthPx() - panel.paddingLeft - panel.paddingRight).coerceAtLeast(1)
            val width = widthPx.toFloat()
            val outTo = if (slideDirection == MessageSlideDirection.NEXT) -width else width
            val inFrom = -outTo
            android.util.Log.d(
                "ScrollCat",
                "Message slide transition - direction=${slideDirection.name.lowercase()}, " +
                    "from=$previousEntryId, to=${message.entryId}"
            )
            existingSlide.animate().cancel()
            existingSlide.animate()
                .translationX(outTo)
                .setDuration(MESSAGE_SLIDE_MS)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    val tokenMatch = token == messageSlideToken
                    val panelViewMatch = panelView === panel
                    android.util.Log.e(
                        "ScrollCat",
                        "###NAV_BUG_DEBUG### withEndAction entered - " +
                            "tokenMatch=$tokenMatch, isShowing=$isShowing, " +
                            "panelViewMatch=$panelViewMatch"
                    )
                    if (!tokenMatch || !isShowing || !panelViewMatch) {
                        android.util.Log.e(
                            "ScrollCat",
                            "###NAV_BUG_DEBUG### EARLY RETURN HIT - rebuild skipped, " +
                                "but slide-in may still proceed with stale/missing content"
                        )
                        messageSlideInProgress = false
                        applyGlassBackdropBlurIfAllowed()
                        return@withEndAction
                    }
                    showMessage(
                        message,
                        captureOpenSnapshot = captureOpenSnapshot,
                        slideDirection = null,
                        fromPendingList = fromPendingList,
                        wasExpandedInList = wasExpandedInList
                    )
                    // Height must be applied before slide-in — sizePanelForContent() only
                    // posts applyAutoPanelHeight, which would race the animation.
                    val heightBefore = panelParams?.height
                    messagePanelApplyHeightSync?.invoke()
                    val heightAfter = panelParams?.height
                    android.util.Log.d(
                        "ScrollCat",
                        "Slide-in height sync - panelParams.height before=$heightBefore, " +
                            "after synchronous applyAutoPanelHeight=$heightAfter, " +
                            "starting slide-in now"
                    )
                    // Log major view state right after rebuild, before slide-in starts.
                    val headerView = messageBodyColumn?.getChildAt(0)
                    val messageAreaView = messageSlideColumn?.getChildAt(0)
                    val footerView = messageFooterBlock
                    android.util.Log.e(
                        "ScrollCat",
                        "###NAV_BUG_DEBUG### post-rebuild state - " +
                            "messageSlideColumn=${messageSlideColumn != null}, " +
                            "messageArea.visibility=${messageAreaView?.visibility}, " +
                            "header.visibility=${headerView?.visibility}, " +
                            "panel.childCount=${panel.childCount}, " +
                            "panel.background=${panel.background != null}, " +
                            "footer.visibility=${footerView?.visibility}"
                    )
                    val newSlide = messageSlideColumn
                    if (newSlide == null) {
                        messageSlideInProgress = false
                        applyGlassBackdropBlurIfAllowed()
                        return@withEndAction
                    }
                    newSlide.animate().cancel()
                    newSlide.translationX = inFrom
                    android.util.Log.e(
                        "ScrollCat",
                        "###NAV_BUG_DEBUG### slide-in starting - " +
                            "newSlide.visibility=${newSlide.visibility}, " +
                            "newSlide.alpha=${newSlide.alpha}, " +
                            "translationX=$inFrom, " +
                            "panel.childCount=${panel.childCount}, " +
                            "panel.background=${panel.background != null}"
                    )
                    newSlide.animate()
                        .translationX(0f)
                        .setDuration(MESSAGE_SLIDE_MS)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            messageSlideInProgress = false
                            applyGlassBackdropBlurIfAllowed()
                        }
                        .start()
                }
                .start()
            return
        }

        if (fromPendingList) {
            notePanelOpenForDebug()
            logPendingToPanelRapidOpenCloseIfNeeded()
            // Shared/stale state BEFORE list→message teardown. Per-card expand is not shared
            // across cards (only the wasExpandedInList snapshot from the tapped card).
            android.util.Log.e(
                "ScrollCat",
                "###PENDING_TO_PANEL_DEBUG### pre-rebuild shared/suspect state - " +
                    "PRIME_SUSPECT_FLAGS: " +
                    "showingSenderList=$showingSenderList, " +
                    "senderListBaseWindow=(x=$senderListBaseWindowX,y=$senderListBaseWindowY," +
                    "w=$senderListBaseWindowWidth), " +
                    "leftover_messageSlideColumn=${messageSlideColumn != null}, " +
                    "leftover_messageBodyColumn=${messageBodyColumn != null}, " +
                    "leftover_messageFooterBlock=${messageFooterBlock != null}, " +
                    "leftover_messagePanelRelayout=${messagePanelRelayout != null}, " +
                    "panel.clipToPadding=${panel.clipToPadding}, " +
                    "panel.clipChildren=${panel.clipChildren}, " +
                    "panel.alpha=${panel.alpha}, panel.translationX=${panel.translationX}, " +
                    "panel.childCount_before_clear=${panel.childCount}, " +
                    "no_shared_list_expand_flag=true " +
                    "(expand/collapse is per-card local TextView.maxLines only; " +
                    "wasExpandedInList=$wasExpandedInList)"
            )
        }

        panel.animate().cancel()
        panel.alpha = 1f
        restoreMessagePanelWindowGeometry()
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
        panel.clipToPadding = true
        panel.clipChildren = true
        resetGlassPanelContent(panel)
        navRow = null
        pendingCountView = null
        swipeHintView = null
        newSenderArrow = null
        messagePanelRelayout = null
        messagePanelApplyHeightSync = null
        messageFooterBlock = null
        messageSlideColumn = null

        val contentId = View.generateViewId()
        val footerId = View.generateViewId()

        // Variable content above the footer (header → chips / new-sender arrow).
        // Shared by all message types: MATCH_CONSTRAINT so body fills space above the
        // bottom-pinned footer (buttons/nav never clip when window height is short).
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
        // bottomToBottom keeps the button/nav row pinned to the window bottom (critical).
        val footer = LinearLayout(context).apply {
            id = footerId
            orientation = LinearLayout.VERTICAL
            layoutParams = ConstraintLayout.LayoutParams(
                0,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topToBottom = contentId
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
            // Trailing padding so long lines don't sit under the copy/edit icons
            setPadding(0, dp(10), dp(36), dp(16))
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
                setStroke(dp(1), CHIP_STROKE)
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
            val visibleHeight = messageScroll.height
            // Before layout, height is 0 — never treat that as overflow (false chevron).
            if (visibleHeight <= 0) {
                scrollChevron.visibility = View.GONE
                return
            }
            val layout = messagePreview.layout
            val contentHeight = if (layout != null && messagePreview.lineCount > 0) {
                layout.getLineTop(messagePreview.lineCount) +
                    messagePreview.paddingTop +
                    messagePreview.paddingBottom
            } else {
                messagePreview.measuredHeight
            }
            // Short messages (≤2 lines) show 3 chips directly and always fit the viewport.
            val lineCount = measurePreviewLineCount(messagePreview)
            val isOverflowing = lineCount > 2 && contentHeight > visibleHeight + 1
            scrollChevron.visibility = if (isOverflowing) View.VISIBLE else View.GONE
            val state = Triple(contentHeight, visibleHeight, isOverflowing)
            if (state != lastChevronLog) {
                lastChevronLog = state
                android.util.Log.d(
                    "ScrollCat",
                    "Chevron check - contentHeight=$contentHeight, visibleHeight=$visibleHeight, " +
                        "lineCount=$lineCount, isOverflowing=$isOverflowing, " +
                        "position=in existing gap below message box"
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
            layoutParams = LinearLayout.LayoutParams(copyIconHit, copyIconHit)
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
        val writeCustomBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_edit)
            imageTintList = android.content.res.ColorStateList.valueOf(MUTED_TEXT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Write custom message"
            // Same hit target / padding / tint as Copy (directly above)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(copyIconHit, copyIconHit)
        }
        val iconColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
            }
            addView(copyBtn)
            addView(writeCustomBtn)
        }
        val messageArea = FrameLayout(context).apply {
            // applyAutoPanelHeight assigns a fixed height from message text. Short
            // messages can be < 2 icons tall and clipped the pencil into a "dot".
            minimumHeight = copyIconHit * 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(messageScroll)
            addView(iconColumn)
            addView(copyFeedback, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(6)
                marginEnd = copyIconHit + dp(2)
            })
        }

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

        // Only message + chevron + chips slide; header/footer stay put.
        val slideColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(messageArea)
            addView(messageChipGap)
            addView(chipsContainer)
        }
        messageSlideColumn = slideColumn
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        // Live finger-follow horizontal drag on the same region that animates for ←/→.
        // Intercept only after a clearly horizontal move so vertical ScrollView still works.
        val slideClip = object : FrameLayout(context) {
            private var downX = 0f
            private var downY = 0f
            private var downRawX = 0f
            private var dragging = false
            private var velocityTracker: VelocityTracker? = null

            private fun recycleTracker() {
                velocityTracker?.recycle()
                velocityTracker = null
            }

            private fun track(ev: MotionEvent) {
                if (velocityTracker == null) {
                    velocityTracker = VelocityTracker.obtain()
                }
                velocityTracker?.addMovement(ev)
            }

            private fun applyFingerFollow(rawX: Float) {
                val dx = rawX - downRawX
                val width = slideColumn.width.takeIf { it > 0 }
                    ?: (panelWidthPx() - (panelView?.paddingLeft ?: 0) - (panelView?.paddingRight ?: 0))
                        .coerceAtLeast(1)
                slideColumn.translationX = if (pending.size <= 1) {
                    // Rubber-band: limited give, no navigation possible.
                    val capped = dx.coerceIn(-width.toFloat(), width.toFloat())
                    SWIPE_RUBBER_BAND * capped
                } else {
                    dx
                }
            }

            private fun endDrag(ev: MotionEvent) {
                if (!dragging) {
                    recycleTracker()
                    return
                }
                dragging = false
                track(ev)
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = velocityTracker?.xVelocity ?: 0f
                recycleTracker()

                val tx = slideColumn.translationX
                val width = slideColumn.width.takeIf { it > 0 }
                    ?: (panelWidthPx() - (panelView?.paddingLeft ?: 0) - (panelView?.paddingRight ?: 0))
                        .coerceAtLeast(1)
                val commitDist = width * SWIPE_COMMIT_FRACTION

                if (pending.size <= 1 || messageSlideInProgress) {
                    snapSlideBackToCenter(slideColumn)
                    return
                }

                val flickedNext = vx <= -SWIPE_MIN_VELOCITY_PX_S
                val flickedPrev = vx >= SWIPE_MIN_VELOCITY_PX_S
                val draggedNext = tx <= -commitDist
                val draggedPrev = tx >= commitDist

                when {
                    flickedNext || draggedNext -> commitSwipeNavigation { advance() }
                    flickedPrev || draggedPrev -> commitSwipeNavigation { previous() }
                    else -> snapSlideBackToCenter(slideColumn)
                }
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (messageSlideInProgress) return false
                track(ev)
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        downY = ev.y
                        downRawX = ev.rawX
                        dragging = false
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = abs(ev.x - downX)
                        val dy = abs(ev.y - downY)
                        if (dx > touchSlop && dx > dy * SWIPE_HORIZONTAL_DOMINANCE) {
                            parent?.requestDisallowInterceptTouchEvent(true)
                            dragging = true
                            slideColumn.animate().cancel()
                            // Capture current contact as drag origin so content doesn't jump.
                            downRawX = ev.rawX - slideColumn.translationX
                            applyFingerFollow(ev.rawX)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        recycleTracker()
                    }
                }
                return false
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (messageSlideInProgress && !dragging) return false
                track(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        downRawX = event.rawX
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragging) {
                            val dx = abs(event.x - downX)
                            val dy = abs(event.y - downY)
                            if (dx > touchSlop && dx > dy * SWIPE_HORIZONTAL_DOMINANCE) {
                                dragging = true
                                slideColumn.animate().cancel()
                                downRawX = event.rawX - slideColumn.translationX
                                parent?.requestDisallowInterceptTouchEvent(true)
                            }
                        }
                        if (dragging) {
                            applyFingerFollow(event.rawX)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        endDrag(event)
                        return true
                    }
                }
                return dragging || super.onTouchEvent(event)
            }
        }.apply {
            clipChildren = true
            clipToPadding = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(slideColumn)
        }
        body.addView(slideClip)

        val isGmailMessage = message.packageName.equals("com.google.android.gm", ignoreCase = true)
        // Gmail: preview + Reply in app / Ignore only — no AI chips, mic, or Send.
        if (isGmailMessage) {
            chipsContainer.visibility = View.GONE
        }

        // ── Bottom row: Reply in app + Ignore + more (equal width/height) ──
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }

        val actionBtnHeight = dp(56)
        val actionHPad = dp(8)
        val actionVPad = dp(8)
        val actionGap = dp(6)

        fun equalActionButtonParams(index: Int): LinearLayout.LayoutParams {
            return LinearLayout.LayoutParams(0, actionBtnHeight, 1f).apply {
                marginStart = if (index == 0) 0 else actionGap
                marginEnd = 0
            }
        }

        val replyInAppBtn = TextView(context).apply {
            text = "↗ Reply in app"
            textSize = 13f
            setTextColor(ACCENT)
            gravity = Gravity.CENTER
            setSingleLine(false)
            maxLines = 2
            setPadding(actionHPad, actionVPad, actionHPad, actionVPad)
            minHeight = actionBtnHeight
            background = GradientDrawable().apply {
                setColor(BUTTON_BG)
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = equalActionButtonParams(0)
        }

        val ignoreBtn = TextView(context).apply {
            text = "✕ Ignore"
            textSize = 13f
            setTextColor(MUTED_TEXT)
            gravity = Gravity.CENTER
            setPadding(actionHPad, actionVPad, actionHPad, actionVPad)
            minHeight = actionBtnHeight
            background = GradientDrawable().apply {
                setColor(BUTTON_BG)
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = equalActionButtonParams(1)
        }

        val moreBtn = TextView(context).apply {
            text = "⋮"
            textSize = 18f
            setTextColor(SOFT_TEXT)
            gravity = Gravity.CENTER
            setPadding(actionHPad, actionVPad, actionHPad, actionVPad)
            minHeight = actionBtnHeight
            background = GradientDrawable().apply {
                setColor(BUTTON_BG)
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = equalActionButtonParams(2)
        }

        // Reply in app: open the messaging app, then close the panel exactly like ✕
        // (does not remove the message from the pending queue).
        replyInAppBtn.setOnClickListener {
            Logger.d("Reply in app button clicked - entry: ${currentEntry?.packageName} contentIntent: ${currentEntry?.contentIntent}")
            val entry = currentEntry ?: return@setOnClickListener
            openMessagingAppForEntry(entry)
            dismiss()
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

        if (fromPendingList) {
            val messageAreaView = messageArea
            android.util.Log.e(
                "ScrollCat",
                "###PENDING_TO_PANEL_DEBUG### opening individual panel from list - " +
                    "entryId=${message.entryId}, wasExpandedInList=$wasExpandedInList, " +
                    "panel.childCount=${panel.childCount}, " +
                    "messageArea.visibility=${messageAreaView.visibility}, " +
                    "header.visibility=${header.visibility}, " +
                    "panel.background=${panel.background != null}"
            )
            // Constraint body uses height=0 with top/bottom constraints — if footer tops
            // out or height apply fails, content can be zero-height while buttons remain.
            android.util.Log.e(
                "ScrollCat",
                "###PENDING_TO_PANEL_DEBUG### post-addView geometry (pre-height) - " +
                    "PRIME_SUSPECT body ConstraintLayout.LayoutParams height=0 until measure, " +
                    "body.visibility=${body.visibility}, body.alpha=${body.alpha}, " +
                    "body.childCount=${body.childCount}, " +
                    "slideColumn.visibility=${slideColumn.visibility}, " +
                    "slideColumn.alpha=${slideColumn.alpha}, " +
                    "slideColumn.translationX=${slideColumn.translationX}, " +
                    "footer.visibility=${footer.visibility}, " +
                    "footer.childCount=${footer.childCount}, " +
                    "panelParams=(w=${panelParams?.width},h=${panelParams?.height}," +
                    "x=${panelParams?.x},y=${panelParams?.y}), " +
                    "senderListBaseCleared=" +
                    "${senderListBaseWindowX == null && senderListBaseWindowWidth == null}"
            )
        }

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

        fun applyPanelHeightNow() {
            // Nav first, then ↓ New message as the last footer child, then measure.
            updatePendingFooter()
            ensureNewMessageIndicatorAttached()
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
            if (fromPendingList) {
                android.util.Log.e(
                    "ScrollCat",
                    "###PENDING_TO_PANEL_DEBUG### after height apply - " +
                        "entryId=${message.entryId}, " +
                        "panel.childCount=${panel.childCount}, " +
                        "body.h=${body.height}, body.measuredH=${body.measuredHeight}, " +
                        "body.visibility=${body.visibility}, " +
                        "header.visibility=${header.visibility}, header.h=${header.height}, " +
                        "messageArea.visibility=${messageArea.visibility}, " +
                        "messageArea.h=${messageArea.height}, " +
                        "slideColumn.h=${slideColumn.height}, " +
                        "footer.h=${footer.height}, footer.top=${footer.top}, " +
                        "panelParams.h=${panelParams?.height}, " +
                        "panel.background=${panel.background != null}"
                )
            }
        }

        fun sizePanelForContent() {
            // Async path for thinking/replies/edit/voice/etc. — not used for slide-in timing.
            panel.post { applyPanelHeightNow() }
        }
        messagePanelRelayout = { sizePanelForContent() }
        messagePanelApplyHeightSync = { applyPanelHeightNow() }

        // After relayout hooks exist — restore ↓ New message if still unviewed.
        updateNewSenderIndicator()

        moreBtn.setOnClickListener {
            val expanding = overflowMenu.visibility != View.VISIBLE
            overflowMenu.visibility = if (expanding) View.VISIBLE else View.GONE
            android.util.Log.e(
                "ScrollCat",
                "###LIST_TO_SINGLE_DEBUG### 3-dot overflow tapped - expanding=$expanding, " +
                    "calling sizePanelForContent() (same relayout suspected to unstick blank panel)"
            )
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
        /** Snapshot for Cancel from custom-write / chip edit. */
        var latestEditSuggestions: List<String> = emptyList()
        var latestEditEngine: String = "Pre-generated"

        fun showEditInput(
            initialText: String,
            suggestions: List<String>,
            engine: String,
            openKeyboard: Boolean = true
        ) {
            aiRepliesExpanded = true
            releaseSpeechRecognizer()
            writeCustomBtn.visibility = View.GONE
            chipsContainer.visibility = View.VISIBLE
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
                hint = "Type your message..."
                setHintTextColor(MUTED_TEXT)
                setSingleLine(false)
                minLines = 1
                maxLines = 5
                // Prior starting height ≈ one text line + vertical padding (14+14).
                // Bump default/min box height by 50%; auto-grow to maxLines unchanged.
                val oneLinePx = (replyTextSp * context.resources.displayMetrics.density).toInt()
                    .coerceAtLeast(1)
                val previousMinPx = oneLinePx + 14 + 14
                minimumHeight = (previousMinPx * 3) / 2
                setPadding(18, 14, 18, 14)
                background = GradientDrawable().apply {
                    setColor(INPUT_BG)
                    cornerRadius = 20f
                    setStroke(1, CHIP_STROKE)
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
                    if (isGmailMessage) {
                        chipsContainer.removeAllViews()
                        chipsContainer.visibility = View.GONE
                        sizePanelForContent()
                    } else {
                        renderReplies(suggestions, engine)
                    }
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
                        // Copy+open is a completed action — remove from queue, then close.
                        ReplyStore.removeEntry(entry.entryId)
                        clearPregeneratedReplies(entry)
                        OverlayService.instance?.updateBadgeAfterReply()
                        openMessagingAppForEntry(entry)
                        dismiss()
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
            latestEditSuggestions = suggestions
            latestEditEngine = engine
            writeCustomBtn.visibility = View.VISIBLE
            setPanelFocusable(false)
            releaseSpeechRecognizer()
            chipsContainer.removeAllViews()
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
            Logger.d("Replies from $engine: count=${suggestions.size}")
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
                    // Copy+open is a completed action — remove from queue, then close.
                    ReplyStore.removeEntry(entry.entryId)
                    clearPregeneratedReplies(entry)
                    OverlayService.instance?.updateBadgeAfterReply()
                    openMessagingAppForEntry(entry)
                    dismiss()
                }
            }

            fun addEditableChip(suggestion: String) {
                val chipRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        setColor(CHIP_BG)
                        cornerRadius = 28f
                        setStroke(1, CHIP_STROKE)
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
                        setStroke(1, CHIP_STROKE)
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
                        messageText = message.message,
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
        writeCustomBtn.setOnClickListener {
            showEditInput("", latestEditSuggestions, latestEditEngine)
        }

        // Gmail: no reply chips / mic / Groq — message + button row only.
        // Timing fix (Gmail-only): sync applyPanelHeightNow() so the overlay window gets
        // its real height before MATCH_CONSTRAINT body measures against it. A WRAP_CONTENT
        // window + height-0 body collapses to 0; posting sizePanelForContent() was too late.
        if (isGmailMessage) {
            Logger.d("Gmail message — skipping reply chips and AI generation")
            android.util.Log.e(
                "ScrollCat",
                "###GMAIL_HEIGHT_DEBUG### isGmailMessage path - " +
                    "header exists=${true}, header included in measurement=yes " +
                    "(same applyAutoPanelHeight args as normal), " +
                    "body exists=${body != null}, messageArea exists=${messageArea != null}, " +
                    "chipsContainer.visibility=${chipsContainer.visibility}, " +
                    "body.lp.height=${(body.layoutParams as? ConstraintLayout.LayoutParams)?.height}, " +
                    "footer.lp.bottomToBottom=${(footer.layoutParams as? ConstraintLayout.LayoutParams)?.bottomToBottom}, " +
                    "panelParams.h=${panelParams?.height}, " +
                    "values BEFORE sync applyPanelHeightNow " +
                    "header.h=${header.height}, body.h=${body.height}, " +
                    "messageArea.h=${messageArea.height}, slideColumn.h=${slideColumn.height}"
            )
            applyPanelHeightNow()
            android.util.Log.e(
                "ScrollCat",
                "###GMAIL_HEIGHT_DEBUG### AFTER sync applyPanelHeightNow - " +
                    "header.h=${header.height}, body.h=${body.height}, " +
                    "messageArea.h=${messageArea.height}, slideColumn.h=${slideColumn.height}, " +
                    "footer.h=${footer.height}, panelParams.h=${panelParams?.height}"
            )
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
    private fun buildVoiceTranslateToggle(
        heightPx: Int = dp(40),
        iconPaddingPx: Int = dp(8),
        marginStartPx: Int = dp(4)
    ): ImageView {
        val toggle = ImageView(context).apply {
            setImageResource(R.drawable.ic_translate)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Translate voice"
            isClickable = true
            isFocusable = true
            setPadding(iconPaddingPx, iconPaddingPx, iconPaddingPx, iconPaddingPx)
            layoutParams = LinearLayout.LayoutParams(heightPx, heightPx).apply {
                marginStart = marginStartPx
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
                shape = GradientDrawable.RECTANGLE
                setCornerRadius(dp(10).toFloat())
                setColor(if (on) VOICE_CHIP_BG else 0x00000000)
                if (on) setStroke(dp(1), ACCENT) else setStroke(0, 0)
            }
            toggle.clipToOutline = false
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
    private fun buildVoiceRomanizeToggle(
        heightPx: Int = dp(40),
        iconPaddingPx: Int = dp(8),
        marginStartPx: Int = dp(4)
    ): ImageView {
        val toggle = ImageView(context).apply {
            setImageResource(R.drawable.ic_romanize)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Romanize voice"
            isClickable = true
            isFocusable = true
            setPadding(iconPaddingPx, iconPaddingPx, iconPaddingPx, iconPaddingPx)
            layoutParams = LinearLayout.LayoutParams(heightPx, heightPx).apply {
                marginStart = marginStartPx
            }
        }
        fun paint() {
            val on = SettingsManager.isVoiceRomanizeEnabled(context)
            toggle.imageTintList = android.content.res.ColorStateList.valueOf(
                if (on) TIP_ACCENT else MUTED_TEXT
            )
            toggle.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setCornerRadius(dp(10).toFloat())
                setColor(if (on) VOICE_CHIP_BG else 0x00000000)
                if (on) setStroke(dp(1), ACCENT) else setStroke(0, 0)
            }
            toggle.clipToOutline = false
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

    /**
     * Speaker control — same footprint as translate (28dp / 4dp pad on the voice chip).
     * Selected while TTS is speaking (same GradientDrawable highlight as active translate);
     * tap again stops via [TtsManager.stop].
     */
    private fun buildReadAloudButton(
        messageText: String,
        heightPx: Int = dp(40),
        iconPaddingPx: Int = dp(8),
        marginStartPx: Int = dp(4)
    ): ImageView {
        // Warm up TTS when the message panel shows the speaker control.
        ensureTtsManager()
        val button = ImageView(context).apply {
            setImageResource(R.drawable.ic_volume_up)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Read message aloud"
            isClickable = true
            isFocusable = true
            setPadding(iconPaddingPx, iconPaddingPx, iconPaddingPx, iconPaddingPx)
            layoutParams = LinearLayout.LayoutParams(heightPx, heightPx).apply {
                marginStart = marginStartPx
            }
            clipToOutline = false
        }
        fun paint() {
            val on = ttsSpeaking
            button.imageTintList = android.content.res.ColorStateList.valueOf(
                if (on) TIP_ACCENT else MUTED_TEXT
            )
            button.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setCornerRadius(dp(10).toFloat())
                setColor(if (on) VOICE_CHIP_BG else 0x00000000)
                if (on) setStroke(dp(1), ACCENT) else setStroke(0, 0)
            }
            button.alpha = if (on) 1f else 0.75f
            button.contentDescription =
                if (on) "Stop reading aloud" else "Read message aloud"
        }
        val painter: () -> Unit = { paint() }
        speakButtonPaint = painter
        button.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                paint()
            }
            override fun onViewDetachedFromWindow(v: View) {
                if (speakButtonPaint === painter) speakButtonPaint = null
            }
        })
        paint()
        button.setOnClickListener {
            if (ttsSpeaking) {
                ttsManager?.stop()
                return@setOnClickListener
            }
            if (messageText.isBlank()) {
                android.widget.Toast.makeText(
                    context,
                    "Nothing to read",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            speakIncomingMessage(messageText)
        }
        return button
    }

    private fun ensureTtsManager(): TtsManager {
        ttsManager?.let { return it }
        val created = TtsManager(context) { speaking ->
            handler.post {
                ttsSpeaking = speaking
                speakButtonPaint?.invoke()
            }
        }
        ttsManager = created
        return created
    }

    /**
     * Reads the incoming message aloud. Language from
     * [ScreenTranslator.identifyLanguageCode] (same ML Kit detector used for
     * screen translate / reply-matching language awareness).
     */
    private fun speakIncomingMessage(messageText: String) {
        val toSpeak = messageText.trim()
        if (toSpeak.isEmpty()) return
        val manager = ensureTtsManager()
        voiceTranslator.identifyLanguageCode(toSpeak) { detected ->
            manager.speak(toSpeak, detected)
        }
    }

    private fun releaseTextToSpeech() {
        ttsSpeaking = false
        speakButtonPaint = null
        ttsManager?.shutdown()
        ttsManager = null
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
        // Overlay height is a fixed px from applyAutoPanelHeight — remeasure so the
        // taller EditText (and Cancel/Continue/Send row) aren't clipped/overlapped.
        // Same path showEditInput uses for a long initial transcript.
        messagePanelRelayout?.invoke()
            ?: input.post { relayoutOpenPanelHeight() }
    }

    /**
     * Action chip (not an AI suggestion): mic + "Voice to text". Hosts listening
     * in a fully transparent Activity; chip shows listening/error states inline.
     */
    private fun buildVoiceToTextChip(
        messageText: String,
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
            setPadding(dp(14), dp(12), dp(10), dp(12))
            minimumHeight = dp(72)
            // Keep selected icon borders from being clipped into "(" ")" by the chip's rounded corners.
            clipChildren = false
            clipToPadding = false
        }

        val micIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_mic)
            imageTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                marginEnd = dp(8)
            }
        }
        val label = TextView(context).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ACCENT)
            maxLines = 2
            isSingleLine = false
            ellipsize = null
            setLineSpacing(0f, 1.0f)
            includeFontPadding = true
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        android.util.Log.e("ScrollCat", "Chip minimumHeight changed from 64dp to 72dp")
        label.text = "Voice\nto text"
        val voiceDivider = View(context).apply {
            setBackgroundColor(0x446B6578)
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(18)).apply {
                gravity = Gravity.CENTER_VERTICAL
                // Slight left pull vs original 8/4 — frees a bit of room for the speaker.
                marginStart = dp(6)
                marginEnd = dp(2)
            }
        }
        chip.addView(micIcon)
        chip.addView(label)
        chip.addView(voiceDivider)
        // Same icon sizes/padding — only a small left margin nudge from the prior 4dp / 8dp gaps.
        chip.addView(
            buildVoiceTranslateToggle(
                heightPx = dp(28),
                iconPaddingPx = dp(4),
                marginStartPx = dp(2)
            )
        )
        chip.addView(
            buildVoiceRomanizeToggle(
                heightPx = dp(28),
                iconPaddingPx = dp(4),
                marginStartPx = dp(6)
            )
        )
        chip.addView(
            buildReadAloudButton(
                messageText = messageText,
                heightPx = dp(28),
                iconPaddingPx = dp(4),
                marginStartPx = dp(4)
            )
        )

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
            label.text = errorMessage ?: "Voice\nto text"
            label.setTextColor(if (errorMessage != null) DANGER else ACCENT)
            onContentChanged()
            if (errorMessage != null) {
                handler.postDelayed({
                    if (!voiceListening && label.text == errorMessage) {
                        label.text = "Voice\nto text"
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

    /** Resizes the overlay around its current center and keeps it inside screen edges. */
    private fun resizePanelWidthKeepingCenter(requestedWidth: Int): Pair<Int, Int> {
        val panel = panelView ?: return panelWidthPx() to panelWidthPx()
        val params = panelParams ?: return panelWidthPx() to panelWidthPx()
        val dm = context.resources.displayMetrics
        val oldWidth = params.width.takeIf { it > 0 } ?: panelWidthPx()
        val newWidth = requestedWidth.coerceAtMost((dm.widthPixels - 32).coerceAtLeast(1))
        if (oldWidth != newWidth) {
            val centerX = params.x + oldWidth / 2
            params.width = newWidth
            params.x = (centerX - newWidth / 2)
                .coerceIn(16, (dm.widthPixels - newWidth - 16).coerceAtLeast(16))
            try {
                windowManager.updateViewLayout(panel, params)
            } catch (_: Exception) { }
        }
        return oldWidth to newWidth
    }

    private fun notePanelOpenForDebug() {
        val now = System.currentTimeMillis()
        if (recentPanelOpenAtsMs.size >= 3) recentPanelOpenAtsMs.removeFirst()
        recentPanelOpenAtsMs.addLast(now)
    }

    private fun notePanelCloseForDebug() {
        val now = System.currentTimeMillis()
        if (recentPanelCloseAtsMs.size >= 3) recentPanelCloseAtsMs.removeFirst()
        recentPanelCloseAtsMs.addLast(now)
    }

    private fun logPendingToPanelRapidOpenCloseIfNeeded() {
        val lastClose = recentPanelCloseAtsMs.lastOrNull() ?: return
        val msAgo = System.currentTimeMillis() - lastClose
        if (msAgo < 2_000L) {
            android.util.Log.e(
                "ScrollCat",
                "###PENDING_TO_PANEL_DEBUG### rapid open/close detected - " +
                    "lastCloseWasMsAgo=$msAgo"
            )
        }
        android.util.Log.e(
            "ScrollCat",
            "###PENDING_TO_PANEL_DEBUG### open/close ring - " +
                "opens=$recentPanelOpenAtsMs closes=$recentPanelCloseAtsMs " +
                "lastCloseWasMsAgo=$msAgo"
        )
    }

    /**
     * Uses the exact geometry captured before showing the pending list. This makes opening
     * a card identical to the direct single-message path instead of inheriting list geometry.
     */
    private fun restoreMessagePanelWindowGeometry() {
        val panel = panelView ?: return
        val params = panelParams ?: return
        val baseX = senderListBaseWindowX
        val baseY = senderListBaseWindowY
        val baseWidth = senderListBaseWindowWidth
        if (baseX != null && baseY != null && baseWidth != null) {
            params.x = baseX
            params.y = baseY
            params.width = baseWidth
            try {
                windowManager.updateViewLayout(panel, params)
            } catch (_: Exception) { }
        } else {
            resizePanelWidthKeepingCenter(panelWidthPx())
        }
        senderListBaseWindowX = null
        senderListBaseWindowY = null
        senderListBaseWindowWidth = null
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
            (panelWidthPx() - panel.paddingLeft - panel.paddingRight).coerceAtLeast(1)
        } else {
            panelWidthPx()
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

        val contentWidth = (panelWidthPx() - panel.paddingLeft - panel.paddingRight).coerceAtLeast(1)
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

        // Prefer measuring the combined footer as one block (buttons + overflow + nav +
        // optional ↓ New message row at the very bottom).
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

        val chipsHeight = if (chipsContainer.visibility == View.GONE) {
            0
        } else {
            chipsContainer.measuredHeight + verticalMargins(chipsContainer)
        }
        val gapHeight: Int = messageGap?.let { v: View ->
            v.measuredHeight + verticalMargins(v)
        } ?: 0
        // ↓ New message lives inside the footer (below nav); do not double-count it.
        val fixedChrome = panel.paddingTop + panel.paddingBottom +
            header.measuredHeight + verticalMargins(header) +
            gapHeight +
            chipsHeight +
            footerHeight +
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
        // Respect messageArea.minimumHeight (e.g. room for copy+pencil stacked at END)
        // so short messages don't clip the lower overlay icon into a sliver/dot.
        val messageHeight = preferredMessageHeight
            .coerceAtMost(messageBudget)
            .coerceAtLeast(messageArea.minimumHeight)

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
                setStroke(1, CHIP_STROKE)
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

            addView(overflowDivider())
            addLanguageQuickSwitchSection(this)
        }
    }

    private fun overflowDivider(): View =
        View(context).apply {
            setBackgroundColor(0x33FFFFFF)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply { setMargins(dp(10), 0, dp(10), 0) }
        }

    /**
     * Quick-switch for Smart Voice Language 1 — curated defaults + user customs.
     * Instant set; does not leave the current panel.
     */
    private fun addLanguageQuickSwitchSection(parent: LinearLayout) {
        val defaults = listOf(
            "English", "Spanish", "Hindi", "Telugu", "German", "French"
        )
        val customs = SettingsManager.getVoiceCustomLanguages1(context)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { custom ->
                defaults.none { it.equals(custom, ignoreCase = true) }
            }
            .distinctBy { it.lowercase() }
        // If Language 1 is a custom value not yet in the customs list, still show it.
        val active = SettingsManager.getVoiceLanguage1(context).trim()
        val activeExtra = if (
            active.isNotEmpty() &&
            !active.equals("Other", ignoreCase = true) &&
            defaults.none { it.equals(active, ignoreCase = true) } &&
            customs.none { it.equals(active, ignoreCase = true) }
        ) {
            listOf(active)
        } else {
            emptyList()
        }
        val languages = defaults + customs + activeExtra

        parent.addView(TextView(context).apply {
            text = "Language for Voice to Text"
            textSize = 11f
            setTextColor(MUTED_TEXT)
            setPadding(dp(14), dp(10), dp(14), dp(4))
        })

        val gridHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(6), dp(2), dp(6), dp(4))
        }
        parent.addView(gridHost)

        fun paintGrid() {
            gridHost.removeAllViews()
            val current = SettingsManager.getVoiceLanguage1(context)
            var index = 0
            while (index < languages.size) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                repeat(3) { col ->
                    val langIndex = index + col
                    if (langIndex >= languages.size) {
                        row.addView(View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                        })
                    } else {
                        val lang = languages[langIndex]
                        val selected = lang.equals(current, ignoreCase = true)
                        row.addView(TextView(context).apply {
                            text = if (selected) "✓ $lang" else lang
                            textSize = 12f
                            gravity = Gravity.CENTER
                            setTextColor(if (selected) TIP_ACCENT else SOFT_TEXT)
                            setPadding(dp(4), dp(10), dp(4), dp(10))
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = dp(10).toFloat()
                                setColor(if (selected) VOICE_CHIP_BG else 0x00000000)
                                if (selected) setStroke(dp(1), ACCENT) else setStroke(0, 0)
                            }
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                .apply {
                                    marginStart = if (col == 0) 0 else dp(4)
                                    marginEnd = if (col == 2) 0 else dp(4)
                                }
                            setOnClickListener {
                                SettingsManager.setVoiceLanguage1(context, lang)
                                refreshVoiceModeToggles()
                                paintGrid()
                            }
                        })
                    }
                }
                gridHost.addView(row)
                index += 3
            }
        }
        paintGrid()

        parent.addView(TextView(context).apply {
            text = "More languages? Check Smart Voice in the dashboard."
            textSize = 10f
            setTextColor(MUTED_TEXT)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(10))
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
    }

    /** Dictation-only overflow: Language quick-switch, nothing else. */
    private fun buildDictationLanguageOverflowMenu(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            background = GradientDrawable().apply {
                setColor(CHIP_BG)
                cornerRadius = dp(14).toFloat()
                setStroke(1, CHIP_STROKE)
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addLanguageQuickSwitchSection(this)
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
                setStroke(1, CHIP_STROKE)
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
                setPadding(0, 0, 0, dp(2))
            })
            addView(TextView(context).apply {
                text = "3. You can also speak your reply — and Smart Voice on the dashboard supports many languages, including translation."
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

    /** Opens the messaging app for [entry] via contentIntent or package launch. */
    private fun openMessagingAppForEntry(entry: ReplyStore.ReplyableMessage) {
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
                showMessage(pending[currentIndex], slideDirection = MessageSlideDirection.NEXT)
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
        resetGlassPanelContent(panel)
        messageBodyColumn = null
        messageSlideColumn = null
        messageFooterBlock = null
        messagePanelRelayout = null
        messagePanelApplyHeightSync = null
        navRow = null
        pendingCountView = null
        swipeHintView = null
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
                showMessage(pending[currentIndex], slideDirection = MessageSlideDirection.NEXT)
            } else {
                dismiss()
            }
        }, CONFIRMATION_MS)
    }

    private fun advance() {
        if (pending.size <= 1 || messageSlideInProgress) return
        currentIndex = (currentIndex + 1) % pending.size
        showMessage(pending[currentIndex], slideDirection = MessageSlideDirection.NEXT)
    }

    private fun previous() {
        if (pending.size <= 1 || messageSlideInProgress) return
        currentIndex = if (currentIndex == 0) pending.size - 1 else currentIndex - 1
        showMessage(pending[currentIndex], slideDirection = MessageSlideDirection.PREVIOUS)
    }

    /** Left-arrow: always return to the Pending Replies list (not previous message). */
    private fun backToPendingList() {
        if (messageSlideInProgress) return
        showSenderList()
    }

    /**
     * Swipe-committed navigation: count toward the one-time swipe hint, then navigate.
     * Arrow taps and other programmatic advances do not count.
     */
    private fun commitSwipeNavigation(navigate: () -> Unit) {
        if (pending.size <= 1 || messageSlideInProgress) return
        SettingsManager.recordReplyPanelSuccessfulSwipe(context)
        navigate()
    }

    /** Snap a mid-drag slide column back to rest without changing the queued message. */
    private fun snapSlideBackToCenter(slide: View) {
        slide.animate().cancel()
        slide.animate()
            .translationX(0f)
            .setDuration(MESSAGE_SLIDE_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
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
        showMessage(entry, slideDirection = MessageSlideDirection.NEXT)
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
            showMessage(pending[currentIndex], slideDirection = MessageSlideDirection.NEXT)
        }
    }

    /**
     * Immediately attach/show the ↓ indicator on the already-visible single-sender panel
     * (does not wait for the next showMessage). No-op for sender-list mode.
     */
    private fun showNewSenderArrowLiveOnOpenPanel(currentSenderKey: String?) {
        if (!isShowing || showingSenderList) return
        if (!hasNewlyArrivedMessages()) {
            removeNewSenderArrow()
            return
        }

        ensureNewMessageIndicatorAttached()
        val arrow = newSenderArrow ?: return
        val unviewedCount = pending.count {
            it.entryId !in knownEntryIdsAtOpen
        }
        arrow.text = if (unviewedCount > 1) "↓  $unviewedCount new" else "↓  New message"
        arrow.visibility = View.VISIBLE
        android.util.Log.d(
            "ScrollCat",
            "Arrow shown live on open panel for current sender, while viewing: $currentSenderKey"
        )

        // Re-run auto height so the indicator stays in the budget after every resize/nav.
        messagePanelApplyHeightSync?.invoke()
            ?: messagePanelRelayout?.invoke()
            ?: relayoutOpenPanelHeight()
    }

    /**
     * Attaches the ↓ New message row as the bottom-most footer child (below the
     * ← / waiting / → nav row). Does not affect message, chips, or Voice-to-text sizing.
     */
    private fun ensureNewMessageIndicatorAttached() {
        if (!isShowing || showingSenderList || !hasNewlyArrivedMessages()) return
        val footer = messageFooterBlock ?: return
        var arrow = newSenderArrow
        if (arrow == null) {
            arrow = TextView(context).apply {
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
            newSenderArrow = arrow
        }
        // Always last child of the footer — below nav when present.
        try {
            (arrow.parent as? ViewGroup)?.removeView(arrow)
        } catch (_: Exception) { }
        footer.addView(arrow)
        val unviewedCount = pending.count { it.entryId !in knownEntryIdsAtOpen }
        arrow.text = if (unviewedCount > 1) "↓  $unviewedCount new" else "↓  New message"
        arrow.visibility = View.VISIBLE
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
            try { (arrow.parent as? ViewGroup)?.removeView(arrow) } catch (_: Exception) { }
        }
        messageFooterBlock?.findViewWithTag<View>(TAG_NEW_SENDER_ARROW)?.let { tagged ->
            try { (tagged.parent as? ViewGroup)?.removeView(tagged) } catch (_: Exception) { }
        }
        messageBodyColumn?.findViewWithTag<View>(TAG_NEW_SENDER_ARROW)?.let { tagged ->
            try { (tagged.parent as? ViewGroup)?.removeView(tagged) } catch (_: Exception) { }
        }
        panelView?.findViewWithTag<View>(TAG_NEW_SENDER_ARROW)?.let { tagged ->
            try { (tagged.parent as? ViewGroup)?.removeView(tagged) } catch (_: Exception) { }
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
            swipeHintView = null
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
            // Left arrow only — always returns to Pending Replies. Sequential next/prev is swipe-only.
            row.addView(TextView(context).apply {
                text = "→"
                textSize = 18f
                setTextColor(NAV_ACCENT)
                gravity = Gravity.CENTER
                setPadding(24, 8, 24, 8)
                scaleX = -1f
                contentDescription = "Back to pending replies"
                setOnClickListener { backToPendingList() }
            })
            val centerCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            pendingCountView = TextView(context).apply {
                textSize = 11f
                setTextColor(MUTED_TEXT)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            centerCol.addView(pendingCountView)
            swipeHintView = TextView(context).apply {
                text = "Swipe to go to next message."
                textSize = 10f
                setTextColor(MUTED_TEXT)
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            centerCol.addView(swipeHintView)
            row.addView(centerCol)
            navRow = row
        }
        val row = navRow ?: return
        // Nav sits under the button row; ↓ New message (if any) stays last below nav.
        try {
            (row.parent as? android.view.ViewGroup)?.removeView(row)
        } catch (_: Exception) { }
        footer.addView(row)
        // Keep ↓ New message below nav whenever both are present.
        newSenderArrow?.takeIf { it.parent == footer }?.let { arrow ->
            try { footer.removeView(arrow) } catch (_: Exception) { }
            footer.addView(arrow)
        }

        pendingCountView?.text = "${currentIndex + 1} / ${pending.size} waiting"
        swipeHintView?.visibility =
            if (SettingsManager.shouldShowReplyPanelSwipeHint(context)) View.VISIBLE else View.GONE
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
            showMessage(pending[currentIndex], slideDirection = MessageSlideDirection.NEXT)
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
                packageName.startsWith("com.skilaparthi.scrollcat.demo")
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
        if (isShowing) notePanelCloseForDebug()
        cancelAccessibilityGrantPoll()
        messageSlideToken++
        messageSlideInProgress = false
        clearGlassBackdropBlur()
        glassBackdropView = null
        handler.removeCallbacksAndMessages(null)
        releaseSpeechRecognizer()
        releaseTextToSpeech()
        setPanelFocusable(false)
        messageSlideColumn?.animate()?.cancel()
        panelView?.let {
            it.animate().cancel()
            try { windowManager.removeView(it) } catch (e: Exception) { }
        }
        panelView = null
        panelParams = null
        currentEntry = null
        navRow = null
        pendingCountView = null
        swipeHintView = null
        messageBodyColumn = null
        messageSlideColumn = null
        messageFooterBlock = null
        messagePanelRelayout = null
        messagePanelApplyHeightSync = null
        viewedKeys.clear()
        knownEntryIdsAtOpen.clear()
        showingSenderList = false
        senderListBaseWindowX = null
        senderListBaseWindowY = null
        senderListBaseWindowWidth = null
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
