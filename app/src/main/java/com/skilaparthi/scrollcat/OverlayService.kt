package com.skilaparthi.scrollcat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

class OverlayService : Service() {

    companion object {
        var instance: OverlayService? = null
            private set
        const val CHANNEL_ID = "scrollcat_overlay"
        const val ACTION_SUMMON = "com.skilaparthi.scrollcat.ACTION_SUMMON"
        const val ACTION_DISMISS = "com.skilaparthi.scrollcat.ACTION_DISMISS"
        const val FLING_VELOCITY_THRESHOLD = 700 // px/sec, tune on device
        const val DISTANCE_TRIGGER_THRESHOLD = 70 // px for up/down scroll
        const val HORIZONTAL_TRIGGER_THRESHOLD = 180 // px for left/right — much bigger to avoid accidental triggers
        const val LONG_PRESS_TIMEOUT_MS = 450L
        const val MOVE_CANCEL_SLOP = 60 // px of movement that cancels a pending long-press
        const val MODE_FEED = false
        const val MODE_REELS = true
        const val DOCK_VISIBILITY_MS = 3_000L
        const val INITIAL_SETTLE_DOCK_MS = 10_000L
        const val MOVE_MODE_TIMEOUT_MS = 10_000L
        /** Edge dock / off-screen hide slide duration (matches dockToEdge / undock). */
        const val DOCK_SLIDE_MS = 320L
        /** Debounce before docking after an editable field loses focus (avoids form-tab flicker). */
        const val TEXT_FOCUS_HIDE_DEBOUNCE_MS = 400L
        var DISTANCE_TRIGGER_THRESHOLD_LIVE = 70
        /** Continuous screen-off before on-device engine idle teardown. */
        private const val ON_DEVICE_IDLE_TEARDOWN_MS = 5 * 60 * 1000L
        /** Soft fade for an already-open reply panel on screen wake (cat is instant). */
        private const val SCREEN_WAKE_PANEL_FADE_MS = 350L
        /** Ignore stacked summon/dismiss taps that cause FGS start/stop races. */
        private const val SUMMON_DISMISS_DEBOUNCE_MS = 400L
    }

    private lateinit var windowManager: WindowManager
    private var catView: ImageView? = null
    private var lottieView: ImageView? = null
    private var catAnimator: CatAnimator? = null
    private var scrollAnimPlaying = false
    private var currentReactionEmoji: android.widget.TextView? = null
    private var reactionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var musicDetector: MusicDetector? = null
    private var volumeControlView: android.widget.LinearLayout? = null
    private var volumeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var replyPanel: ReplyPanel? = null
    var screenTranslator: ScreenTranslator? = null
    private var translationBubble: android.widget.TextView? = null
    private val translationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var catMessageBubble: android.widget.TextView? = null
    private val catMessageHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var volumeUIShown = false
    private var isVolumeMode = false
    private var lastVolumeY = 0f
    private val VOLUME_STEP_PX = 30f // px to drag per volume step
    private val idleHandler = Handler(Looper.getMainLooper())
    @Volatile private var screenOffSinceElapsedMs = 0L
    private val idleTeardownRunnable = Runnable { maybeIdleTeardownOnDeviceAi() }

    private val screenStateReceiver = ScreenStateReceiver(
        onScreenOn = {
            cancelOnDeviceIdleTimer()
            AiReplyGenerator.flushAllBuffers(applicationContext)
            resumeBackgroundWork()
            Logger.d("Screen on - background work resumed")
            // Cat appears instantly (no container fade). Panel-only soft fade if already open.
            if (replyPanel?.isShowing == true) {
                replyPanel?.prepareScreenWakeFadeIn()
                replyPanel?.softFadeInFromScreenWake(SCREEN_WAKE_PANEL_FADE_MS)
            }
            maybeInitOnDeviceAi()
        },
        onScreenOff = {
            pauseBackgroundWork()
            Logger.d("Screen off - background work paused")
            scheduleOnDeviceIdleTeardown()
        }
    )

    private fun scheduleOnDeviceIdleTeardown() {
        screenOffSinceElapsedMs = android.os.SystemClock.elapsedRealtime()
        idleHandler.removeCallbacks(idleTeardownRunnable)
        idleHandler.postDelayed(idleTeardownRunnable, ON_DEVICE_IDLE_TEARDOWN_MS)
    }

    private fun cancelOnDeviceIdleTimer() {
        screenOffSinceElapsedMs = 0L
        idleHandler.removeCallbacks(idleTeardownRunnable)
    }

    private fun maybeIdleTeardownOnDeviceAi() {
        val since = screenOffSinceElapsedMs
        val elapsed = if (since <= 0L) 0L else android.os.SystemClock.elapsedRealtime() - since
        val minutes = elapsed / 60_000L
        val pendingCount = ReplyStore.count()
        val stillOff = since > 0L &&
            !(getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
        val tearingDown = stillOff &&
            elapsed >= ON_DEVICE_IDLE_TEARDOWN_MS &&
            pendingCount == 0 &&
            !OnDeviceAiEngine.isGenerating() &&
            OnDeviceAiEngine.isReady()
        android.util.Log.d(
            "ScrollCat",
            "Idle timer: screen off for ${minutes}min, pending=$pendingCount, " +
                "tearing down: $tearingDown"
        )
        if (tearingDown) {
            OnDeviceAiEngine.releaseForIdle()
        }
    }

    private fun releaseOnDeviceAiForMemoryPressure(reason: String) {
        if (OnDeviceAiEngine.isGenerating()) {
            android.util.Log.d(
                "ScrollCat",
                "onTrimMemory($reason): skip engine teardown — generation in progress"
            )
            return
        }
        if (!OnDeviceAiEngine.isReady()) return
        android.util.Log.d(
            "ScrollCat",
            "onTrimMemory($reason): releasing on-device engine"
        )
        OnDeviceAiEngine.releaseForIdle()
    }

    private var layoutParams: WindowManager.LayoutParams? = null
    private var isDestroyed = false
    private var catTouchListener: CatTouchListener? = null

    private fun safeAddView(view: View, params: WindowManager.LayoutParams): Boolean {
        if (isDestroyed) return false
        if (view.parent != null) return true
        return try {
            windowManager.addView(view, params)
            true
        } catch (e: Exception) {
            android.util.Log.w("ScrollCat", "addView failed: ${e.message}")
            false
        }
    }

    private fun isViewAttached(view: android.view.View?): Boolean {
        return view?.windowToken != null
    }

    private fun safeUpdateViewLayout(
        view: View?,
        params: WindowManager.LayoutParams,
        fromTouch: Boolean = false
    ): Boolean {
        if (isDestroyed || view == null) return false
        if (!isViewAttached(view)) return false
        return try {
            windowManager.updateViewLayout(view, params)
            true
        } catch (e: IllegalArgumentException) {
            if (fromTouch) {
                android.util.Log.w("ScrollCat", "View not attached during touch: ${e.message}")
            } else {
                android.util.Log.w("ScrollCat", "Failed to update view layout: ${e.message}")
            }
            false
        } catch (e: Exception) {
            android.util.Log.w("ScrollCat", "Failed to update view layout: ${e.message}")
            false
        }
    }

    private fun safeRemoveView(view: View?) {
        if (view == null) return
        try {
            if (view.parent != null) windowManager.removeView(view)
        } catch (e: Exception) {
            android.util.Log.w("ScrollCat", "removeView failed: ${e.message}")
        }
    }

    private fun pauseBackgroundWork() {
        catAnimator?.stop()
        catAnimator?.cancelIdleTimeout()
        musicDetector?.pause()
    }

    private fun resumeBackgroundWork() {
        if (isDestroyed) return
        // Don't replace the edge-dock sleep pose with the idle static frame.
        if (SettingsManager.isEdgeDockingMode(this) && isEdgeDocked) {
            catAnimator?.showFrame(69)
            applyDockedOpacity()
        } else {
            catAnimator?.showStatic()
        }
        // Ensure no leftover wake-fade left the overlay dimmed.
        containerView?.animate()?.cancel()
        containerView?.alpha = 1f
        musicDetector?.resume()
    }

    private var badgeCount = 0
    private var badgeView: android.widget.TextView? = null
    private var containerView: FrameLayout? = null

    // Edge-docking state (only used when display mode is edge_docking)
    private var isEdgeDocked = false
    /**
     * Zero pending + not held by text focus: cat is fully off-screen (View.GONE),
     * not the peeking dock pose. Revealed by new notifications or editable focus.
     */
    private var isCatFullyHidden = false
    /** True while waiting for the post-summon settle-into-dock (not the post-message re-dock timer). */
    private var awaitingInitialDock = false
    /**
     * Cat is held undocked because a focused editable field is active (Accessibility
     * focus-tracking). No dock visibility timer while this is true — stays visible until focus loss.
     */
    private var heldByTextFocus = false
    private var isDismissing = false
    /**
     * Latest [onStartCommand] startId. Used with [dismissAtStartId] so a fade-complete
     * [stopSelf] cannot kill a service that already received a newer SUMMON.
     */
    private var currentStartId = 0
    /** startId of the [ACTION_DISMISS] that began the in-flight dismiss teardown. */
    private var dismissAtStartId = -1
    /**
     * Bumped to cancel an in-flight dismiss fade / posted finish when a new SUMMON
     * arrives mid-shutdown — prevents stopForeground/stopSelf racing a re-start.
     */
    private var dismissGeneration = 0
    private var dismissFadeAnimator: Animator? = null
    /** Invoked once after the dismiss fade finishes (or immediately if fade is skipped). */
    private var onDismissFadeCompleted: (() -> Unit)? = null
    /** Debounce rapid Summon/Dismiss taps that stack startForegroundService / stop races. */
    private var lastSummonElapsedMs = 0L
    private var lastDismissElapsedMs = 0L
    private var dockAnimator: ValueAnimator? = null
    /** Bumped to ignore stale hide/reveal animator end callbacks after cancel. */
    private var dockAnimToken = 0
    /**
     * When true, [dockVisibilityRunnable] hides in notify-only mode even if ReplyStore still
     * has pending items. Set only for a fresh service show (first launch / post-dismiss Summon);
     * cleared for continuous-running re-arms (new notification, panel close, etc.).
     */
    private var idleHideIgnoresPending = false
    private val dockHandler = Handler(Looper.getMainLooper())
    private val dockVisibilityRunnable = Runnable {
        // Keep fully visible while the reply panel is open; timer resumes on dismiss.
        if (replyPanel?.isShowing == true) {
            Logger.d("Dock visibility timer fired but reply panel is open — skipping re-dock")
            logDismissResummonDebug("dockVisibility_skip_panel_open")
            return@Runnable
        }
        // Focus-held visibility wins over the notification dock timer — but only when
        // there are no pending replies (pending always takes priority).
        if (isHeldByEditableFocus()) {
            Logger.d("Dock visibility timer fired but text field focused — keeping undocked")
            logDismissResummonDebug("dockVisibility_skip_text_focus")
            return@Runnable
        }
        if (!SettingsManager.isEdgeDockingMode(this)) return@Runnable
        val hasPending = ReplyStore.count() > 0 || badgeCount > 0
        // Consume fresh-start flag for this fire only.
        val hideDespitePending = idleHideIgnoresPending
        idleHideIgnoresPending = false
        logDismissResummonDebug(
            "dockVisibility_fire hasPending=$hasPending hideDespitePending=$hideDespitePending " +
                "hideWhenIdle=${SettingsManager.isHideWhenIdleMode(this)}"
        )
        if (SettingsManager.isHideWhenIdleMode(this) && (hideDespitePending || !hasPending)) {
            // Fresh summon / first launch: hide even when ReplyStore still has stale pending.
            // Continuous empty-queue: hide as usual.
            hideCatFullyOffScreen(animate = true)
        } else if (hasPending) {
            if (!isEdgeDocked) {
                dockToEdge(animate = true)
            }
        } else if (!isEdgeDocked) {
            // Classic edge docking: always return to the peeking dock.
            dockToEdge(animate = true)
        }
    }
    private val textFocusHideRunnable = Runnable { completeTextFocusHide() }
    private val initialSettleDockRunnable = Runnable {
        if (replyPanel?.isShowing == true) {
            Logger.d("Initial settle dock skipped — reply panel open")
            return@Runnable
        }
        if (isHeldByEditableFocus()) {
            awaitingInitialDock = false
            Logger.d("Initial settle dock skipped — text field focused")
            return@Runnable
        }
        if (SettingsManager.isEdgeDockingMode(this) && awaitingInitialDock && !isEdgeDocked) {
            awaitingInitialDock = false
            val hasPending = ReplyStore.count() > 0 || badgeCount > 0
            if (hasPending || !SettingsManager.isHideWhenIdleMode(this)) {
                dockToEdge(animate = true)
                Logger.d("Initial settle timer elapsed — docking")
            } else {
                hideCatFullyOffScreen(animate = true)
                Logger.d("Initial settle timer elapsed — hiding off-screen (notify-only, empty)")
            }
        }
    }

    var isReelsMode = false
        private set

    private var handleView: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var closeZoneView: View? = null
    private var closeZoneParams: WindowManager.LayoutParams? = null
    private var closeZoneHighlighted = false
    /** Onboarding Screen 3: single completion handler (survives ReplyPanel rebuilds). */
    private var onboardingDemoReplySentHandler: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        // FIRST: satisfy startForegroundService() before any other work — Android kills
        // the app if startForeground() is delayed across summon/dismiss churn.
        startAsForeground()
        android.util.Log.d(
            "ScrollCat",
            "Summon/onCreate - isDestroyed=$isDestroyed instance=${instance != null} " +
                "containerAttached=${isViewAttached(containerView)} catView=$catView " +
                "isEdgeDocked=$isEdgeDocked"
        )
        DeviceIdleMonitor.register(this)
        val screenStateFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, screenStateFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenStateReceiver, screenStateFilter)
        }
        instance = this
        isDestroyed = false
        isDismissing = false
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        DailyDigestNotifier.maybeShow(this)
        addCatView()
        replyPanel = ReplyPanel(this, windowManager).also { wireReplyPanel(it) }
        if (SettingsManager.isEdgeDockingMode(this)) {
            // Summon at full float + full opacity; idle-hide / settle armed from shared path.
            catAnimator?.setIdleSleepEnabled(false)
            isEdgeDocked = false
            catView?.alpha = 1f
            containerView?.visibility = View.VISIBLE
            scheduleIdleVisibilityCountdown("onCreate", freshStartIgnorePending = true)
        } else {
            catAnimator?.setIdleSleepEnabled(true)
        }
        musicDetector = MusicDetector(this)
        musicDetector?.start()
        screenTranslator = ScreenTranslator(this)
        maybeInitOnDeviceAi()
        android.util.Log.d(
            "ScrollCat",
            "Summon/onCreate done - containerAttached=${isViewAttached(containerView)} " +
                "alpha=${catView?.alpha} visibility=${containerView?.visibility}"
        )
    }

    /** Non-blocking: load on-device model if viable and already downloaded for this RAM tier. */
    private fun maybeInitOnDeviceAi() {
        Thread {
            try {
                val ok = OnDeviceAiEngine.ensureInitialized(this)
                android.util.Log.d(
                    "ScrollCat",
                    if (ok) "On-device AI engine ready (warmup/init)"
                    else "On-device AI engine not loaded (missing model, tier, or memory)"
                )
            } catch (e: Exception) {
                android.util.Log.e("ScrollCat", "On-device AI init error: ${e.message}", e)
            }
        }.start()
    }

    /** First-install dismiss→resummon disappearance bug — filter logcat: DISMISS_RESUMMON_DEBUG */
    private fun logDismissResummonDebug(action: String) {
        android.util.Log.e(
            "ScrollCat",
            "###DISMISS_RESUMMON_DEBUG### action=$action, isCatFullyHidden=$isCatFullyHidden, " +
                "isEdgeDocked=$isEdgeDocked, view.visibility=${catView?.visibility}, " +
                "alpha=${catView?.alpha}, pendingCount=${ReplyStore.count()}"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val now = SystemClock.elapsedRealtime()
        currentStartId = startId
        android.util.Log.d(
            "ScrollCat",
            "Summon called - current state: action=$action isDestroyed=$isDestroyed " +
                "isDismissing=$isDismissing containerAttached=${isViewAttached(containerView)} " +
                "catView=$catView isEdgeDocked=$isEdgeDocked alpha=${catView?.alpha} startId=$startId"
        )
        if (action == ACTION_DISMISS) {
            logDismissResummonDebug("ACTION_DISMISS_enter")
            if (now - lastDismissElapsedMs < SUMMON_DISMISS_DEBOUNCE_MS) {
                logDismissResummonDebug("ACTION_DISMISS_debounced")
                android.util.Log.d("ScrollCat", "Dismiss debounced — ignoring rapid repeat")
                return START_NOT_STICKY
            }
            lastDismissElapsedMs = now
            dismissAtStartId = startId
            android.util.Log.d(
                "ScrollCat",
                "Dismiss called - current state: isDestroyed=$isDestroyed " +
                    "containerAttached=${isViewAttached(containerView)} catView=$catView " +
                    "isEdgeDocked=$isEdgeDocked alpha=${catView?.alpha}"
            )
            dismissAndStop(animated = true)
            logDismissResummonDebug("ACTION_DISMISS_after_dismissAndStop")
            return START_NOT_STICKY
        }

        // Each startForegroundService() requires startForeground() within the FGS timeout.
        // Re-assert immediately — covers sticky restart AND summon while mid-dismiss after
        // stopForeground may have already run (or is about to).
        startAsForeground()

        if (action == ACTION_SUMMON) {
            logDismissResummonDebug("ACTION_SUMMON_enter")
            val attached = isViewAttached(containerView)
            if (!isDismissing && attached &&
                now - lastSummonElapsedMs < SUMMON_DISMISS_DEBOUNCE_MS
            ) {
                logDismissResummonDebug("ACTION_SUMMON_debounced")
                android.util.Log.d("ScrollCat", "Summon debounced — already on screen")
                // Continuously running + debounced: do not treat as fresh-start (pending still wins).
                scheduleIdleVisibilityCountdown(
                    "ACTION_SUMMON_debounced",
                    freshStartIgnorePending = false
                )
                return START_STICKY
            }
            lastSummonElapsedMs = now
        }

        // Mid-fade dismiss + new summon: cancel stopForeground/stopSelf so they don't
        // race the newly asserted foreground state.
        if (isDismissing) {
            abortDismissForResummon()
        }

        if (action == ACTION_SUMMON) {
            logDismissResummonDebug("ACTION_SUMMON_before_ensureCatOnScreen")
        }
        ensureCatOnScreen()
        // Fresh service show (Summon / sticky restart after Dismiss, or true first launch):
        // idle-hide must fire even if ReplyStore still has pending from before Dismiss.
        val freshStart = action == ACTION_SUMMON || action == null
        scheduleIdleVisibilityCountdown(
            if (action == ACTION_SUMMON) "ACTION_SUMMON" else "onStartCommand_${action ?: "null"}",
            freshStartIgnorePending = freshStart
        )
        if (action == ACTION_SUMMON) {
            logDismissResummonDebug("ACTION_SUMMON_after_ensureCatOnScreen")
        }
        return START_STICKY
    }

    /**
     * Fade the cat out, then remove it / stop the service. [onComplete] runs on the
     * main thread after the fade's onAnimationEnd (same moment as removeView) — use
     * this to advance onboarding only once the dismiss is visually done.
     */
    fun dismissAnimated(onComplete: (() -> Unit)? = null) {
        if (onComplete != null) {
            onDismissFadeCompleted = onComplete
        }
        if (isDismissing) {
            // Fade already running; onComplete will fire when it finishes.
            return
        }
        dismissAndStop(animated = true)
    }

    /** Tear down overlay and stop the service (used by Dismiss). */
    private fun dismissAndStop(animated: Boolean = true) {
        logDismissResummonDebug("dismissAndStop_enter animated=$animated isDismissing=$isDismissing")
        if (isDismissing) {
            logDismissResummonDebug("dismissAndStop_already_dismissing")
            return
        }
        isDismissing = true
        // Close-zone / onboarding dismiss may not go through ACTION_DISMISS — bind stopSelf to
        // the latest startId so a concurrent SUMMON can supersede the teardown.
        if (dismissAtStartId < 0) {
            dismissAtStartId = currentStartId
        }
        val token = ++dismissGeneration
        android.util.Log.d(
            "ScrollCat",
            "dismissAndStop - animated=$animated token=$token " +
                "(fade will remove view only after animation end)"
        )
        cancelDockAnimator()
        cancelDockVisibilityTimer()
        cancelInitialSettleTimer()
        hideDragHandle()
        hideCloseZone()
        // Prevent panel-dismiss from restarting the edge-dock timer mid-fade.
        replyPanel?.onDismissed = null
        replyPanel?.dismiss()

        val container = containerView
        val canFade = animated && container != null && isViewAttached(container)
        if (!canFade) {
            android.util.Log.d(
                "ScrollCat",
                "Fade skipped - animated=$animated attached=${isViewAttached(container)} container=$container"
            )
            logDismissResummonDebug("dismissAndStop_fade_skipped")
            if (token == dismissGeneration && isDismissing) finishDismissAndStop()
            return
        }

        // Cancel prior dismiss fade + any in-flight reveal/dock slide that owns catView.alpha.
        // cancelDockAnimator already ran above; drop listeners/cancel again for a prior fade.
        val priorFade = dismissFadeAnimator
        dismissFadeAnimator = null
        priorFade?.removeAllListeners()
        priorFade?.cancel()
        container!!.animate().cancel()
        catView?.animate()?.cancel()
        catAnimator?.showStatic()

        // Force a visible starting point — fade-out must go 1.0 → 0.0 on BOTH views.
        // Reveal/hide animate catView.alpha; fading only container fought mid-entrance state.
        container.alpha = 1f
        catView?.alpha = 1f
        container.visibility = View.VISIBLE
        catView?.visibility = View.VISIBLE

        android.util.Log.d(
            "ScrollCat",
            "Fade pre-start — container.alpha=${container.alpha} cat.alpha=${catView?.alpha}"
        )
        logDismissResummonDebug("dismissFade_pre_start token=$token")

        // Post so a full-opacity frame can paint before the fade begins.
        container.post {
            if (token != dismissGeneration || !isDismissing || isDestroyed ||
                containerView !== container || !isViewAttached(container)
            ) {
                android.util.Log.d(
                    "ScrollCat",
                    "Fade aborted before start — tokenMatch=${token == dismissGeneration} " +
                        "isDismissing=$isDismissing"
                )
                logDismissResummonDebug("dismissFade_aborted_before_start")
                return@post
            }
            // Re-invalidate dock/reveal in case anything restarted between cancel and this frame.
            cancelDockAnimator()
            container.animate().cancel()
            catView?.animate()?.cancel()
            container.alpha = 1f
            catView?.alpha = 1f
            android.util.Log.d("ScrollCat", "Fade animation started")
            logDismissResummonDebug("dismissFade_started")
            // Same ValueAnimator + token pattern as reveal/hide — drive both alphas together.
            dismissFadeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 250L
                var fadeCanceled = false
                addUpdateListener { animator ->
                    if (token != dismissGeneration || !isDismissing || isDestroyed) {
                        return@addUpdateListener
                    }
                    val a = animator.animatedValue as Float
                    container.alpha = a
                    catView?.alpha = a
                    android.util.Log.d(
                        "ScrollCat",
                        "Fade alpha value: container=${container.alpha} cat=${catView?.alpha} " +
                            "(animatedValue=$a)"
                    )
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: Animator) {
                        fadeCanceled = true
                        logDismissResummonDebug("dismissFade_onAnimationCancel")
                        if (dismissFadeAnimator === animation) {
                            dismissFadeAnimator = null
                        }
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        android.util.Log.d(
                            "ScrollCat",
                            "Fade animation completed fadeCanceled=$fadeCanceled"
                        )
                        logDismissResummonDebug(
                            "dismissFade_onAnimationEnd fadeCanceled=$fadeCanceled"
                        )
                        if (dismissFadeAnimator === animation) {
                            dismissFadeAnimator = null
                        }
                        // Cancel still delivers onAnimationEnd — do not tear down on cancel
                        // (e.g. mid-fight with a reveal that we aborted); abortDismiss handles that.
                        if (fadeCanceled) return
                        if (token == dismissGeneration && isDismissing && !isDestroyed) {
                            finishDismissAndStop()
                        } else {
                            logDismissResummonDebug("dismissFade_end_ignored_aborted")
                            android.util.Log.d(
                                "ScrollCat",
                                "Fade end ignored — dismiss was aborted by summon " +
                                    "(tokenMatch=${token == dismissGeneration} isDismissing=$isDismissing)"
                            )
                        }
                    }
                })
                start()
            }
        }
    }

    /**
     * Cancel an in-progress dismiss so a new SUMMON can keep the service alive without
     * a delayed stopForeground/stopSelf from the previous fade.
     */
    private fun abortDismissForResummon() {
        logDismissResummonDebug("abortDismissForResummon_enter")
        android.util.Log.d("ScrollCat", "abortDismissForResummon — cancelling mid-fade dismiss")
        dismissGeneration++
        isDismissing = false
        onDismissFadeCompleted = null
        val priorFade = dismissFadeAnimator
        dismissFadeAnimator = null
        priorFade?.removeAllListeners()
        priorFade?.cancel()
        cancelDockAnimator()
        containerView?.animate()?.cancel()
        catView?.animate()?.cancel()
        containerView?.alpha = 1f
        catView?.alpha = 1f
        containerView?.visibility = View.VISIBLE
        catView?.visibility = View.VISIBLE
        logDismissResummonDebug("abortDismissForResummon_done")
    }

    private fun finishDismissAndStop() {
        logDismissResummonDebug("finishDismissAndStop_enter")
        // A newer onStartCommand (resummon) owns the service — do not tear down its cat
        // or cancel the idle-hide timer it just armed.
        if (currentStartId != dismissAtStartId) {
            logDismissResummonDebug(
                "finishDismissAndStop_skipped_superseded " +
                    "currentStartId=$currentStartId dismissAtStartId=$dismissAtStartId"
            )
            isDismissing = false
            onDismissFadeCompleted = null
            dismissAtStartId = -1
            return
        }
        val priorFade = dismissFadeAnimator
        dismissFadeAnimator = null
        priorFade?.removeAllListeners()
        priorFade?.cancel()
        cancelDockAnimator()
        cancelDockVisibilityTimer()
        cancelInitialSettleTimer()
        containerView?.animate()?.cancel()
        catView?.animate()?.cancel()
        // Sole removeView for the cat overlay in the dismiss flow.
        safeRemoveView(containerView)
        containerView = null
        catView = null
        badgeView = null
        layoutParams = null
        isEdgeDocked = false
        isCatFullyHidden = false
        awaitingInitialDock = false
        isDismissing = false

        val completed = onDismissFadeCompleted
        onDismissFadeCompleted = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        // Only stop if no newer start arrived — avoids onDestroy cancelling a resummon's timer.
        stopSelf(dismissAtStartId)
        logDismissResummonDebug("finishDismissAndStop_after_stopSelf dismissAtStartId=$dismissAtStartId")
        dismissAtStartId = -1

        // Notify after removeView so onboarding can advance once the fade is done.
        if (completed != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                completed.invoke()
            }
        }
    }

    /**
     * Ensures the cat overlay is attached and visible. Handles the case where
     * Summon is called while the service is still running but the view was removed.
     * Idle-hide / settle timers are armed by [scheduleIdleVisibilityCountdown] from the
     * shared call sites (onCreate / onStartCommand) — not duplicated here.
     */
    private fun ensureCatOnScreen() {
        if (isDestroyed) return
        val attached = isViewAttached(containerView)
        logDismissResummonDebug("ensureCatOnScreen_enter attached=$attached")
        android.util.Log.d(
            "ScrollCat",
            "ensureCatOnScreen - attached=$attached container=$containerView"
        )
        if (!attached) {
            safeRemoveView(containerView)
            containerView = null
            catView = null
            badgeView = null
            layoutParams = null
            catTouchListener?.cleanup()
            catTouchListener = null
            catAnimator?.stopAll()
            catAnimator = null
            addCatView()
            if (replyPanel == null) {
                replyPanel = ReplyPanel(this, windowManager).also { wireReplyPanel(it) }
            }
            if (SettingsManager.isEdgeDockingMode(this)) {
                catAnimator?.setIdleSleepEnabled(false)
                isEdgeDocked = false
                isCatFullyHidden = false
                catView?.alpha = 1f
                containerView?.visibility = View.VISIBLE
            } else {
                catAnimator?.setIdleSleepEnabled(true)
            }
        } else {
            // Already on screen — wake to full float visibility
            isEdgeDocked = false
            isCatFullyHidden = false
            catView?.alpha = 1f
            containerView?.visibility = View.VISIBLE
            catAnimator?.play("idle")
            if (SettingsManager.isEdgeDockingMode(this)) {
                catAnimator?.setIdleSleepEnabled(false)
            }
        }
        android.util.Log.d(
            "ScrollCat",
            "ensureCatOnScreen done - attached=${isViewAttached(containerView)} " +
                "alpha=${catView?.alpha} size=${layoutParams?.width}"
        )
        logDismissResummonDebug(
            "ensureCatOnScreen_done recreated=${!attached} " +
                "mode=${SettingsManager.getCatDisplayMode(this)}"
        )
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "ScrollCat overlay",
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
        val notification: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Cat is on screen and ready to reply")
                .setSmallIcon(android.R.drawable.star_on)
                .build()
        startForeground(1, notification)
    }

    private fun addCatView() {
        val container = FrameLayout(this)

        val cat = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            // Set first frame immediately so cat is visible before animator starts
            try {
                val stream = assets.open("sprites/0000.webp")
                val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                stream.close()
                setImageBitmap(bitmap)
            } catch (e: Exception) {
                // fallback to emoji if frame missing
                setImageBitmap(null)
            }
        }
        try {
            catAnimator = CatAnimator(this, cat)
            catAnimator?.preloadFrames("idle")
            catAnimator?.preloadFrames("tap")
            catAnimator?.preloadFrames("scroll")
        } catch (e: Exception) {
            Logger.e("Failed to create animator: ${e.message}")
            cat.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        lottieView = cat
        catView = cat

        val badge = android.widget.TextView(this).apply {
            visibility = android.view.View.GONE
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.RED)
            }
            gravity = android.view.Gravity.CENTER
            val size = 40
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
            }
            setPadding(0, 0, 0, 0)
        }

        container.addView(cat)
        container.addView(badge)

        val catSize = SettingsManager.getCatSizePx(this)
        val params = WindowManager.LayoutParams(
            catSize,
            catSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = SettingsManager.getCatFloatX(this@OverlayService)
            y = SettingsManager.getCatFloatY(this@OverlayService)
        }

        container.setOnTouchListener(CatTouchListener(params).also { catTouchListener = it })
        try {
            windowManager.addView(container, params)
            cat.post {
                catAnimator?.play("idle")
            }
            badgeView = badge
            containerView = container
            layoutParams = params
            android.util.Log.d(
                "ScrollCat",
                "Cat view added to WindowManager - attached=${container.windowToken != null} " +
                    "x=${params.x} y=${params.y} size=$catSize"
            )
        } catch (e: Exception) {
            Logger.e("Failed to add cat view: ${e.message}")
            android.util.Log.e("ScrollCat", "Cat view NOT added to WindowManager: ${e.message}")
        }
    }

    private inner class CatTouchListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {

        private var homeX = 0
        private var homeY = 0
        private var pressStartTouchX = 0f
        private var pressStartTouchY = 0f

        private var upProgressY = 0f
        private var downProgressY = 0f
        private var leftProgressX = 0f
        private var rightProgressX = 0f

        private var isDragMode = false
        private var isWakingUp = false
        /** After long-press activates Move, suppress tap/double-tap for this gesture. */
        private var suppressTapGestures = false
        private var lastTouchRawX = 0f
        private var lastTouchRawY = 0f
        private var moveDragStarted = false

        // Frame-paced drag: touch only updates target; Choreographer applies layouts 1×/vsync.
        private var dragTargetX = 0
        private var dragTargetY = 0
        private var hasPendingDragTarget = false
        private var dragFrameCallbackScheduled = false
        private val dragFrameCallback = Choreographer.FrameCallback {
            dragFrameCallbackScheduled = false
            applyPendingDragLayout()
        }

        private val handler = Handler(Looper.getMainLooper())
        private val moveModeTimeoutRunnable = Runnable {
            if (!isDragMode || moveDragStarted) return@Runnable
            cancelScheduledDragFrame()
            hasPendingDragTarget = false
            isDragMode = false
            suppressTapGestures = false
            hideDragHandle()
            resetCatCloseZoneScale()
            hideCloseZone(animated = true)
            catAnimator?.showStatic()
            Logger.d("Move mode cancelled — timeout")
            // Resume normal idle/dock timing (single visibility window, not stacked with Move timeout)
            noteCatInteraction()
        }
        private val longPressRunnable = Runnable {
            if (layoutParams == null) return@Runnable
            val p = layoutParams ?: return@Runnable
            // Enter drag immediately on the same finger-down — no lift required
            suppressTapGestures = true
            isDragMode = true
            moveDragStarted = false
            // Anchor drag to current finger + current cat position (may already be slightly offset)
            homeX = p.x
            homeY = p.y
            pressStartTouchX = lastTouchRawX
            pressStartTouchY = lastTouchRawY
            dragTargetX = p.x
            dragTargetY = p.y
            hasPendingDragTarget = false
            showDragHandle(p)
            showCloseZone()
            cancelDockVisibilityTimer()
            cancelInitialSettleTimer()
            handler.removeCallbacks(moveModeTimeoutRunnable)
            handler.postDelayed(moveModeTimeoutRunnable, MOVE_MODE_TIMEOUT_MS)
            Logger.d("Move mode started (continuous long-press)")
        }

        private fun cancelMoveModeTimeout() {
            handler.removeCallbacks(moveModeTimeoutRunnable)
        }

        private fun cancelScheduledDragFrame() {
            if (dragFrameCallbackScheduled) {
                Choreographer.getInstance().removeFrameCallback(dragFrameCallback)
                dragFrameCallbackScheduled = false
            }
        }

        private fun scheduleDragFrame() {
            if (dragFrameCallbackScheduled) return
            dragFrameCallbackScheduled = true
            Choreographer.getInstance().postFrameCallback(dragFrameCallback)
        }

        /** Apply latest drag target → magnet → cat + handle windows (once per frame). */
        private fun applyPendingDragLayout() {
            if (!hasPendingDragTarget) return
            hasPendingDragTarget = false
            params.x = dragTargetX
            params.y = dragTargetY
            applyCloseZoneMagnet(params)
            safeUpdateViewLayout(containerView, params, fromTouch = true)
            moveDragHandle(params)
            updateCloseZoneHighlight(params)
        }

        /** Flush any pending frame so UP/CANCEL sees the true final position. */
        private fun flushPendingDragLayout() {
            cancelScheduledDragFrame()
            if (hasPendingDragTarget) {
                applyPendingDragLayout()
            }
        }

        fun cleanup() {
            cancelMoveModeTimeout()
            cancelScheduledDragFrame()
            hasPendingDragTarget = false
            handler.removeCallbacksAndMessages(null)
        }

        private val gestureDetector = GestureDetector(
            this@OverlayService,
            object : SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    // Edge-docked: undock then run panel logic if pending; otherwise just undock
                    if (SettingsManager.isEdgeDockingMode(this@OverlayService) && isEdgeDocked) {
                        undockToFloat(animate = true, startVisibilityTimer = true) {
                            if (badgeCount > 0) {
                                val pending = ReplyStore.getAll()
                                if (pending.isNotEmpty()) {
                                    showReplyPanel()
                                } else {
                                    clearBadge()
                                }
                            } else if (!maybeShowAccessibilityExplanation() &&
                                !maybeShowVoiceDictation()
                            ) {
                                // No pending + a11y on + no focused field — undock only
                            }
                        }
                        return true
                    }

                    // If cat is sleeping — wake up but DON'T clear badge or open reply panel
                    // User needs to tap again after cat wakes up to see replies
                    if (catAnimator?.isAsleep() == true) {
                        wakeFromSleep()
                        // Don't clear badge, don't open reply panel
                        // Badge stays visible so user can tap again
                        return true
                    }

                    handleAwakeCatTap()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (isEdgeDocked) return true
                    isReelsMode = !isReelsMode
                    // When toggling modes, show correct emoji for current mood state
                    // mood emoji replaced by Lottie animation
                    android.widget.Toast.makeText(
                        this@OverlayService,
                        if (isReelsMode) "Reels mode \uD83D\uDE38" else "Feed mode \uD83D\uDC31",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    noteCatInteraction()
                    return true
                }
            }
        )

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            // Docked: only tap-to-undock — ignore scroll/drag/long-press gestures
            if (isEdgeDocked) {
                gestureDetector.onTouchEvent(event)
                return true
            }

            if (catAnimator?.isAsleep() == true || isWakingUp) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    if (catAnimator?.isAsleep() == true) {
                        Logger.d("Cat sleeping - waking up, keeping badge: $badgeCount")
                        wakeFromSleep()
                        isWakingUp = true
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    isWakingUp = false
                }
                return true
            }

            lastTouchRawX = event.rawX
            lastTouchRawY = event.rawY
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                suppressTapGestures = false
            }
            // Skip once long-press Move is active so tap/double-tap don't fire after Move
            if (!suppressTapGestures) {
                gestureDetector.onTouchEvent(event)
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    noteCatInteraction()
                    moveDragStarted = false
                    homeX = params.x
                    homeY = params.y
                    pressStartTouchX = event.rawX
                    pressStartTouchY = event.rawY
                    lastVolumeY = event.rawY
                    upProgressY = pressStartTouchY
                    downProgressY = pressStartTouchY
                    leftProgressX = pressStartTouchX
                    rightProgressX = pressStartTouchX
                    isDragMode = false
                    cancelMoveModeTimeout()
                    handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    // If sleeping, block all movement
                    if (catAnimator?.isAsleep() == true) return true

                    // If volume mode — only adjust volume, don't move cat
                    if (isVolumeMode) {
                        val dy = event.rawY - lastVolumeY
                        if (Math.abs(dy) > VOLUME_STEP_PX) {
                            if (dy < 0) {
                                // Drag up = volume up
                                audioManager.adjustStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    AudioManager.ADJUST_RAISE,
                                    AudioManager.FLAG_SHOW_UI
                                )
                                Logger.d("Volume up")
                            } else {
                                // Drag down = volume down
                                audioManager.adjustStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    AudioManager.ADJUST_LOWER,
                                    AudioManager.FLAG_SHOW_UI
                                )
                                Logger.d("Volume down")
                            }
                            lastVolumeY = event.rawY
                            resetVolumeTimeout()
                        }
                        return true
                    }

                    // Continuous long-press drag — frame-paced (Choreographer), not per touch sample
                    if (isDragMode) {
                        if (!moveDragStarted) {
                            moveDragStarted = true
                            cancelMoveModeTimeout()
                        }
                        val dx = event.rawX - pressStartTouchX
                        val dy = event.rawY - pressStartTouchY
                        dragTargetX = homeX + dx.toInt()
                        dragTargetY = homeY + dy.toInt()
                        hasPendingDragTarget = true
                        scheduleDragFrame()
                        return true
                    }

                    // Cancel long press if finger moved too much before threshold
                    val slopDx = event.rawX - pressStartTouchX
                    val slopDy = event.rawY - pressStartTouchY
                    if (abs(slopDx) > MOVE_CANCEL_SLOP || abs(slopDy) > MOVE_CANCEL_SLOP) {
                        handler.removeCallbacks(longPressRunnable)
                    }

                    // Normal push gesture — evaluate for scroll/back/voice
                    evaluatePush(event)

                    // Move cat visually with finger
                    params.x = homeX + slopDx.toInt()
                    params.y = homeY + slopDy.toInt()
                    safeUpdateViewLayout(containerView, params, fromTouch = true)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (catAnimator?.isAsleep() == true) return true
                    handler.removeCallbacks(longPressRunnable)

                    // Handle volume mode — don't let evaluatePush fire
                    if (isVolumeMode) {
                        hideVolumeControls()
                        params.x = homeX
                        params.y = homeY
                        safeUpdateViewLayout(containerView, params, fromTouch = true)
                        return true
                    }

                    // Finish continuous Move drag
                    if (isDragMode) {
                        // Apply last target before close-zone / persist so we don't drop a frame.
                        flushPendingDragLayout()
                        val dismissNow = isCatClearlyInCloseZone(params)
                        hideDragHandle()
                        isDragMode = false
                        cancelMoveModeTimeout()
                        suppressTapGestures = false
                        if (dismissNow) {
                            Logger.d("Cat dropped on close zone — dismissing")
                            animateDismissIntoCloseZone {
                                // Already faded/shrunk — skip a second fade.
                                dismissAndStop(animated = false)
                            }
                            return true
                        }
                        resetCatCloseZoneScale()
                        hideCloseZone(animated = true)
                        persistFloatPositionAndDockSide(params.x, params.y)
                        catAnimator?.showStatic()
                        noteCatInteraction()
                        return true
                    }

                    // Normal gesture
                    evaluatePush(event)
                    params.x = homeX
                    params.y = homeY
                    safeUpdateViewLayout(containerView, params, fromTouch = true)
                    return true
                }
            }
            return false
        }

        private fun evaluatePush(event: MotionEvent) {
            val y = event.rawY
            val x = event.rawX

            if (y >= pressStartTouchY) upProgressY = pressStartTouchY
            if (y <= pressStartTouchY) downProgressY = pressStartTouchY
            if (x <= pressStartTouchX) rightProgressX = pressStartTouchX
            if (x >= pressStartTouchX) leftProgressX = pressStartTouchX

            val upDistance = upProgressY - y
            val downDistance = y - downProgressY
            val rightDistance = x - rightProgressX
            val leftDistance = leftProgressX - x

            // Calculate angle of movement from starting point
            val totalDx = x - pressStartTouchX
            val totalDy = y - pressStartTouchY
            val angleDegrees = Math.toDegrees(
                Math.atan2(Math.abs(totalDy).toDouble(), Math.abs(totalDx).toDouble())
            ).toFloat()

            // angleDegrees is 0° = perfectly horizontal, 90° = perfectly vertical
            // Back/voice only fires if movement is within 20° of horizontal
            val isHorizontal = angleDegrees <= 20f
            val isVertical = !isHorizontal

            when {
                // Vertical gestures — scroll up
                isVertical && upDistance > DISTANCE_TRIGGER_THRESHOLD_LIVE && upDistance >= downDistance -> {
                    CatAccessibilityService.instance?.performSwipe(up = true, long = isReelsMode) ?: showNoAccessibilityToast()
                    upProgressY = y
                    if (!scrollAnimPlaying) {
                        Logger.d("Playing scroll anim, flag was: $scrollAnimPlaying")
                        scrollAnimPlaying = true
                        catAnimator?.play("scroll") {
                            scrollAnimPlaying = false
                            catAnimator?.showStatic()
                        }
                    }
                }
                // Vertical gestures — scroll down
                isVertical && downDistance > DISTANCE_TRIGGER_THRESHOLD_LIVE && downDistance > upDistance -> {
                    CatAccessibilityService.instance?.performSwipe(up = false, long = isReelsMode) ?: showNoAccessibilityToast()
                    downProgressY = y
                    if (!scrollAnimPlaying) {
                        Logger.d("Playing scroll anim, flag was: $scrollAnimPlaying")
                        scrollAnimPlaying = true
                        catAnimator?.play("scroll") {
                            scrollAnimPlaying = false
                            catAnimator?.showStatic()
                        }
                    }
                }
                // Horizontal — back button (right swipe, within 20° of horizontal)
                isHorizontal && rightDistance > HORIZONTAL_TRIGGER_THRESHOLD && rightDistance >= leftDistance -> {
                    CatAccessibilityService.instance?.performBack()
                        ?: showNoAccessibilityToast()
                    rightProgressX = x
                }
                // Horizontal — Recent apps (left swipe, within 20° of horizontal)
                isHorizontal && leftDistance > HORIZONTAL_TRIGGER_THRESHOLD && leftDistance > rightDistance -> {
                    CatAccessibilityService.instance?.performRecentApps()
                        ?: showNoAccessibilityToast()
                    leftProgressX = x
                }
            }
        }
    }

    private fun showNoAccessibilityToast() {
        android.widget.Toast.makeText(
            this,
            "Long-press cat to move across the screen",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun handleAwakeCatTap() {
        noteCatInteraction()
        // Pending messages always win over voice dictation.
        val pending = ReplyStore.getAll()
        if (pending.isNotEmpty()) {
            showReplyPanel()
            return
        }
        if (badgeCount > 0) {
            Logger.d("clearBadge called from: handleAwakeCatTap - badge tap with no pending replies")
            clearBadge()
            return
        }

        if (catAnimator?.currentAnim == "music") {
            catAnimator?.stopMusic()
            return
        }

        // No pending: if Accessibility is off, explain voice-to-text before normal idle tap.
        if (maybeShowAccessibilityExplanation()) return

        // No pending: optional voice dictation into a focused editable field (Accessibility only).
        if (maybeShowVoiceDictation()) return

        CatAccessibilityService.instance?.performSwipe(up = true, long = isReelsMode)
            ?: showNoAccessibilityToast()
        animateTap()
    }

    /**
     * Idle tap with Accessibility disabled: show a compact explanation card
     * (same footprint as voice-dictation) instead of dock/undock/swipe. Returns true
     * when that card was shown.
     */
    private fun maybeShowAccessibilityExplanation(): Boolean {
        val isEnabled = CatAccessibilityService.instance != null
        if (isEnabled) {
            android.util.Log.d(
                "ScrollCat",
                "Cat tapped while idle - Accessibility enabled: true, showing: normal-behavior"
            )
            return false
        }
        android.util.Log.d(
            "ScrollCat",
            "Cat tapped while idle - Accessibility enabled: false, " +
                "showing: accessibility-explanation-card"
        )
        showAccessibilityExplanationCard()
        return true
    }

    private fun showAccessibilityExplanationCard() {
        val params = layoutParams ?: return
        val catSize = SettingsManager.getCatSizePx(this)
        animateTap()
        cancelDockVisibilityTimer()
        cancelInitialSettleTimer()
        awaitingInitialDock = false
        replyPanel?.showAccessibilityExplanation(params.x, params.y, catSize)
        Logger.d("Accessibility explanation card shown — dock timer paused")
    }

    /**
     * If Accessibility is enabled and a focused editable field exists, open the
     * minimal auto-listening dictation panel. Returns true when that panel was shown.
     */
    private fun maybeShowVoiceDictation(): Boolean {
        val a11y = CatAccessibilityService.instance ?: return false
        if (!a11y.hasFocusedEditableField()) return false
        if (!RecordAudioPermissionActivity.isSpeechRecognitionAvailable(this)) {
            Logger.d("Voice dictation skipped — speech recognition unavailable")
            return false
        }
        showVoiceDictationPanel()
        return true
    }

    fun showVoiceDictationPanel() {
        val params = layoutParams ?: return
        val catSize = SettingsManager.getCatSizePx(this)
        animateTap()
        cancelDockVisibilityTimer()
        cancelInitialSettleTimer()
        awaitingInitialDock = false
        replyPanel?.showVoiceDictation(params.x, params.y, catSize)
        Logger.d("Voice dictation panel shown — dock timer paused")
    }

    /** Reset the edge-dock visibility timer on any undocked interaction. */
    private fun noteCatInteraction() {
        if (awaitingInitialDock) {
            startInitialSettleTimer()
            return
        }
        // Focus-held: stay visible with no dock countdown.
        if (heldByTextFocus) {
            cancelDockVisibilityTimer()
            return
        }
        if (SettingsManager.isEdgeDockingMode(this) && !isEdgeDocked) {
            resetDockVisibilityTimer()
        }
    }

    /**
     * Called from [CatAccessibilityService] when a real editable field gains or loses focus.
     * Only active when Accessibility is connected (caller is the a11y service) and edge-docking
     * mode is on. Pending ReplyStore messages always take priority over focus-triggered show.
     */
    fun onEditableFocusChanged(focused: Boolean) {
        if (isDestroyed) return
        dockHandler.post {
            if (isDestroyed) return@post
            if (!SettingsManager.isEdgeDockingMode(this)) return@post

            val pendingCount = ReplyStore.count()

            if (focused) {
                cancelTextFocusHide()
                if (pendingCount > 0) {
                    android.util.Log.d(
                        "ScrollCat",
                        "Focus-triggered cat visibility: field focused=true, pending messages=$pendingCount, action=skip-has-pending"
                    )
                    return@post
                }
                heldByTextFocus = true
                cancelInitialSettleTimer()
                awaitingInitialDock = false
                when {
                    needsEntranceRevealFromHidden() -> {
                        // Same robust GONE/flag/alpha check as notification reveal.
                        revealCatFromOffScreen(
                            toFloat = true,
                            startVisibilityTimer = false
                        )
                    }
                    isEdgeDocked -> {
                        // Same pop-out as new-message arrivals — but no dock visibility timer.
                        undockToFloat(animate = true, startVisibilityTimer = false)
                    }
                    else -> {
                        // Already undocked (e.g. recent notification) — extend visibility, no re-anim.
                        cancelDockVisibilityTimer()
                    }
                }
                android.util.Log.d(
                    "ScrollCat",
                    "Focus-triggered cat visibility: field focused=true, pending messages=$pendingCount, action=show"
                )
            } else {
                // Debounce hide so tabbing between nearby fields does not flicker.
                dockHandler.removeCallbacks(textFocusHideRunnable)
                dockHandler.postDelayed(textFocusHideRunnable, TEXT_FOCUS_HIDE_DEBOUNCE_MS)
            }
        }
    }

    private fun cancelTextFocusHide() {
        dockHandler.removeCallbacks(textFocusHideRunnable)
    }

    private fun isHeldByEditableFocus(): Boolean {
        // Pending replies continuously win over focus-hold (not only at focus-gain).
        if (ReplyStore.count() > 0) {
            if (heldByTextFocus) {
                heldByTextFocus = false
            }
            return false
        }
        if (heldByTextFocus) return true
        if (CatAccessibilityService.instance?.hasFocusedEditableField() == true) {
            heldByTextFocus = true
            return true
        }
        return false
    }

    /** After debounce: dock if focus is still gone and nothing else should keep the cat out. */
    private fun completeTextFocusHide() {
        if (isDestroyed) return
        val stillFocused = CatAccessibilityService.instance?.hasFocusedEditableField() == true
        val pendingCount = ReplyStore.count()

        if (stillFocused) {
            heldByTextFocus = true
            cancelDockVisibilityTimer()
            android.util.Log.d(
                "ScrollCat",
                "Focus-triggered cat visibility: field focused=true, pending messages=$pendingCount, action=show"
            )
            return
        }

        heldByTextFocus = false

        if (pendingCount > 0) {
            android.util.Log.d(
                "ScrollCat",
                "Focus-triggered cat visibility: field focused=false, pending messages=$pendingCount, action=skip-has-pending"
            )
            // Notification/pending owns visibility — resume normal dock timer.
            if (!isEdgeDocked) resetDockVisibilityTimer()
            return
        }

        if (replyPanel?.isShowing == true) return

        if (SettingsManager.isEdgeDockingMode(this)) {
            if (SettingsManager.isHideWhenIdleMode(this)) {
                // Notify-only: fully off-screen when focus ends with an empty queue.
                hideCatFullyOffScreen(animate = true)
            } else if (!isEdgeDocked) {
                // Classic edge docking: return to peeking dock.
                dockToEdge(animate = true)
            }
        }
        android.util.Log.d(
            "ScrollCat",
            "Focus-triggered cat visibility: field focused=false, pending messages=$pendingCount, action=hide"
        )
    }

    private fun persistFloatPositionAndDockSide(x: Int, y: Int) {
        SettingsManager.setCatFloatPosition(this, x, y)
        val screenWidth = resources.displayMetrics.widthPixels
        val catSize = SettingsManager.getCatSizePx(this)
        val centerX = x + catSize / 2
        val side = if (centerX < screenWidth / 2) "left" else "right"
        SettingsManager.setCatDockSide(this, side)
        Logger.d("Saved float position ($x,$y) dock side=$side")
    }

    private fun dockedIconSize(): Int {
        val dockDp = (SettingsManager.getCatSizeDp(this) * 0.55f).toInt()
            .coerceAtLeast(24)
        return dp(dockDp)
    }

    private fun dockedEdgeX(
        dockSize: Int,
        screenWidth: Int = resources.displayMetrics.widthPixels,
    ): Int {
        return if (SettingsManager.getCatDockSide(this) == "left") {
            -dockSize / 2
        } else {
            screenWidth - dockSize / 2
        }
    }

    /** Fully past the edge — no peek visible when the queue is empty. */
    private fun fullyHiddenEdgeX(
        dockSize: Int,
        screenWidth: Int = resources.displayMetrics.widthPixels,
    ): Int {
        return if (SettingsManager.getCatDockSide(this) == "left") {
            -dockSize - dp(8)
        } else {
            screenWidth + dp(8)
        }
    }

    /**
     * Slide the cat fully off the dock edge and hide it ([View.GONE]).
     * Same 320ms DecelerateInterpolator motion language as [dockToEdge] / [undockToFloat].
     */
    private fun hideCatFullyOffScreen(animate: Boolean) {
        logDismissResummonDebug("hideCatFullyOffScreen_enter animate=$animate")
        if (!SettingsManager.isHideWhenIdleMode(this)) {
            logDismissResummonDebug("hideCatFullyOffScreen_skip_not_hide_when_idle")
            return
        }
        val params = layoutParams
        if (params == null) {
            logDismissResummonDebug("hideCatFullyOffScreen_skip_null_params")
            return
        }
        val view = containerView
        if (view == null) {
            logDismissResummonDebug("hideCatFullyOffScreen_skip_null_container")
            return
        }
        if (isCatFullyHidden && view.visibility != View.VISIBLE) {
            logDismissResummonDebug("hideCatFullyOffScreen_skip_already_hidden")
            return
        }

        cancelDockVisibilityTimer()
        cancelInitialSettleTimer()
        awaitingInitialDock = false
        cancelDockAnimator()

        if (!isEdgeDocked) {
            persistFloatPositionAndDockSide(params.x, params.y)
        }

        val dockSize = dockedIconSize()
        val (screenWidth, screenHeight) = currentDisplaySize()
        val targetX = fullyHiddenEdgeX(dockSize, screenWidth)
        val targetY = SettingsManager.getCatFloatY(this)
            .coerceIn(40, (screenHeight - dockSize - 40).coerceAtLeast(40))
        val startX = params.x
        val startY = params.y
        val startW = params.width
        val startH = params.height
        val startAlpha = catView?.alpha ?: 1f

        isEdgeDocked = true
        catAnimator?.showFrame(69)

        fun finishHidden() {
            params.width = dockSize
            params.height = dockSize
            params.x = targetX
            params.y = targetY
            safeUpdateViewLayout(view, params)
            catView?.alpha = 0f
            view.visibility = View.GONE
            isCatFullyHidden = true
            // Match clearBadge / empty-queue paths: reset so the next setBadgeCount(realTotal)
            // always satisfies badgeCount > previous and can trigger reveal. ReplyStore may
            // still hold pending; the next sync re-applies the true count onto the badge.
            badgeCount = 0
            updateBadge()
            Logger.d("Cat fully hidden off-screen x=$targetX (badgeCount reset)")
            logDismissResummonDebug("hideCatFullyOffScreen_finishHidden")
        }

        if (!animate || view.visibility != View.VISIBLE) {
            logDismissResummonDebug(
                "hideCatFullyOffScreen_immediate " +
                    "animate=$animate containerVis=${view.visibility}"
            )
            finishHidden()
            return
        }

        val token = ++dockAnimToken
        dockAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DOCK_SLIDE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                if (isDestroyed || token != dockAnimToken) return@addUpdateListener
                val t = anim.animatedValue as Float
                params.width = (startW + (dockSize - startW) * t).toInt()
                params.height = (startH + (dockSize - startH) * t).toInt()
                params.x = (startX + (targetX - startX) * t).toInt()
                params.y = (startY + (targetY - startY) * t).toInt()
                catView?.alpha = startAlpha * (1f - t)
                safeUpdateViewLayout(view, params)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (token != dockAnimToken) return
                    dockAnimator = null
                    finishHidden()
                }
            })
        }
        dockAnimator?.start()
    }

    /**
     * Reveal the cat by sliding in from fully off-screen (mirrored hide).
     * [toFloat]=true lands at the saved float spot (notification pop-out / dictation).
     */
    private fun revealCatFromOffScreen(
        toFloat: Boolean,
        startVisibilityTimer: Boolean,
        onComplete: (() -> Unit)? = null
    ) {
        if (!SettingsManager.isEdgeDockingMode(this)) return
        val params = layoutParams ?: return
        val view = containerView ?: return

        cancelDockAnimator()
        cancelDockVisibilityTimer()
        cancelInitialSettleTimer()
        awaitingInitialDock = false

        val dockSize = dockedIconSize()
        val fullSize = SettingsManager.getCatSizePx(this)
        val (screenWidth, screenHeight) = currentDisplaySize()
        val startX = fullyHiddenEdgeX(dockSize, screenWidth)
        val targetX: Int
        val targetY: Int
        val targetW: Int
        val targetH: Int
        val targetAlpha: Float
        if (toFloat) {
            targetX = SettingsManager.getCatFloatX(this)
            targetY = SettingsManager.getCatFloatY(this)
            targetW = fullSize
            targetH = fullSize
            targetAlpha = 1f
        } else {
            targetX = dockedEdgeX(dockSize, screenWidth)
            targetY = SettingsManager.getCatFloatY(this)
                .coerceIn(40, (screenHeight - dockSize - 40).coerceAtLeast(40))
            targetW = dockSize
            targetH = dockSize
            targetAlpha = SettingsManager.getSleepOpacity(this)
        }

        isCatFullyHidden = false
        view.visibility = View.VISIBLE
        catView?.visibility = View.VISIBLE
        params.width = dockSize
        params.height = dockSize
        params.x = startX
        params.y = targetY
        catView?.alpha = 0f
        safeUpdateViewLayout(view, params)
        catAnimator?.showFrame(69)

        fun finishReveal() {
            params.width = targetW
            params.height = targetH
            params.x = targetX
            params.y = targetY
            safeUpdateViewLayout(view, params)
            catView?.alpha = targetAlpha
            isEdgeDocked = !toFloat
            if (toFloat) {
                catAnimator?.showStatic()
                if (startVisibilityTimer) {
                    resetDockVisibilityTimer(forceDespiteTextFocus = true)
                }
            } else {
                applyDockedOpacity()
            }
            Logger.d(
                "Cat revealed from off-screen toFloat=$toFloat " +
                    "pos=($targetX,$targetY) timer=$startVisibilityTimer"
            )
            onComplete?.invoke()
        }

        val token = ++dockAnimToken
        dockAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DOCK_SLIDE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                if (isDestroyed || token != dockAnimToken) return@addUpdateListener
                val t = anim.animatedValue as Float
                params.width = (dockSize + (targetW - dockSize) * t).toInt()
                params.height = (dockSize + (targetH - dockSize) * t).toInt()
                params.x = (startX + (targetX - startX) * t).toInt()
                params.y = targetY
                catView?.alpha = targetAlpha * t
                safeUpdateViewLayout(view, params)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (token != dockAnimToken) return
                    dockAnimator = null
                    finishReveal()
                }
            })
        }
        dockAnimator?.start()
        android.util.Log.d(
            "ScrollCat",
            "###CAT_ENTRANCE_DEBUG### revealCatFromOffScreen started - " +
                "toFloat=$toFloat startX=$startX target=($targetX,$targetY) " +
                "token=$token thread=${Thread.currentThread().name}"
        )
    }

    /** Fresh display size for dock math (avoids stale portrait metrics after rotation). */
    private fun currentDisplaySize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    /**
     * Recompute left/right edge dock X (and clamp Y) against the *current* screen size.
     * Call after orientation / configuration changes so a portrait edge X is not reused
     * as a fixed pixel value in landscape.
     */
    private fun recalculateDockPositionForCurrentDisplay() {
        if (isDestroyed || !isEdgeDocked || !SettingsManager.isEdgeDockingMode(this)) return
        val params = layoutParams ?: return
        val view = containerView ?: return
        if (!::windowManager.isInitialized) return

        cancelDockAnimator()
        val (newWidth, newHeight) = currentDisplaySize()
        val dockSize = dockedIconSize()
        val newX = if (isCatFullyHidden) {
            fullyHiddenEdgeX(dockSize, newWidth)
        } else {
            dockedEdgeX(dockSize, newWidth)
        }
        val newY = params.y.coerceIn(40, (newHeight - dockSize - 40).coerceAtLeast(40))
        params.width = dockSize
        params.height = dockSize
        params.x = newX
        params.y = newY
        safeUpdateViewLayout(view, params)
        android.util.Log.d(
            "ScrollCat",
            "Orientation change detected - new width=$newWidth, new height=$newHeight, " +
                "recalculated dock position=($newX, $newY) fullyHidden=$isCatFullyHidden",
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recalculateDockPositionForCurrentDisplay()
    }

    /**
     * Invalidate any in-flight dock/reveal/hide slide. Remove listeners BEFORE cancel so
     * [AnimatorListenerAdapter.onAnimationEnd] (which Android still fires on cancel) cannot
     * run finishReveal / applyDockedOpacity / finishUndock and fight a dismiss fade over alpha.
     */
    private fun cancelDockAnimator() {
        dockAnimToken++
        val anim = dockAnimator
        dockAnimator = null
        anim?.removeAllListeners()
        anim?.cancel()
    }

    /** True when the cat is off-screen / not visible and a notification must play the entrance slide. */
    private fun needsEntranceRevealFromHidden(): Boolean {
        val container = containerView
        if (isCatFullyHidden) return true
        if (container == null) return true
        if (container.visibility != View.VISIBLE) return true
        // Fully off-screen / faded dock without the flag (e.g. cancelled hide mid-flight).
        val alpha = catView?.alpha ?: 1f
        if (isEdgeDocked && alpha < 0.05f) return true
        return false
    }

    private fun cancelDockVisibilityTimer() {
        dockHandler.removeCallbacks(dockVisibilityRunnable)
    }

    private fun cancelInitialSettleTimer() {
        dockHandler.removeCallbacks(initialSettleDockRunnable)
    }

    /**
     * Single source of truth for post-visibility idle countdown. Called whenever the cat
     * becomes (or stays) visible from onCreate, ACTION_SUMMON / ensureCatOnScreen, or a
     * sticky restart — so first-launch and dismiss→resummon cannot drift apart.
     *
     * - Visible-only-when-notified → [DOCK_VISIBILITY_MS] then [hideCatFullyOffScreen]
     *   via [dockVisibilityRunnable]. When [freshStartIgnorePending] is true (first launch /
     *   post-dismiss Summon), hide fires even if ReplyStore still has pending; continuous
     *   re-arms ([resetDockVisibilityTimer]) keep pending-aware hide/dock behavior.
     * - Classic edge docking → [INITIAL_SETTLE_DOCK_MS] settle-into-peek via
     *   [initialSettleDockRunnable]
     */
    private fun scheduleIdleVisibilityCountdown(
        reason: String,
        freshStartIgnorePending: Boolean = false
    ) {
        if (isDestroyed || isDismissing) {
            logDismissResummonDebug("scheduleIdle_skip_destroyed_or_dismissing reason=$reason")
            return
        }
        if (!SettingsManager.isEdgeDockingMode(this)) return
        if (isCatFullyHidden) {
            logDismissResummonDebug("scheduleIdle_skip_fully_hidden reason=$reason")
            return
        }
        if (replyPanel?.isShowing == true) {
            logDismissResummonDebug("scheduleIdle_skip_panel_open reason=$reason")
            return
        }
        if (isHeldByEditableFocus() || heldByTextFocus) {
            // Focus owns visibility; completeTextFocusHide will hide/dock on blur.
            cancelDockVisibilityTimer()
            cancelInitialSettleTimer()
            awaitingInitialDock = false
            idleHideIgnoresPending = false
            logDismissResummonDebug("scheduleIdle_skip_text_focus reason=$reason")
            return
        }

        cancelDockVisibilityTimer()
        cancelInitialSettleTimer()

        if (SettingsManager.isHideWhenIdleMode(this)) {
            awaitingInitialDock = false
            idleHideIgnoresPending = freshStartIgnorePending
            // Fresh start always floats briefly then hides — stale pending must not skip arming.
            isEdgeDocked = false
            dockHandler.postDelayed(dockVisibilityRunnable, DOCK_VISIBILITY_MS)
            logDismissResummonDebug(
                "scheduleIdle_armed hideWhenIdle freshStartIgnorePending=$freshStartIgnorePending " +
                    "pending=${ReplyStore.count()} reason=$reason"
            )
            Logger.d(
                "Idle visibility timer armed (${DOCK_VISIBILITY_MS}ms) " +
                    "hideWhenIdle=true freshStartIgnorePending=$freshStartIgnorePending reason=$reason"
            )
        } else {
            idleHideIgnoresPending = false
            if (isEdgeDocked) {
                logDismissResummonDebug("scheduleIdle_skip_already_docked reason=$reason")
                return
            }
            awaitingInitialDock = true
            dockHandler.postDelayed(initialSettleDockRunnable, INITIAL_SETTLE_DOCK_MS)
            logDismissResummonDebug("scheduleIdle_armed edge_settle reason=$reason")
            Logger.d(
                "Initial settle dock timer started (${INITIAL_SETTLE_DOCK_MS}ms) reason=$reason"
            )
        }
    }

    private fun startInitialSettleTimer() {
        // Interaction reset while awaiting classic edge settle — not a fresh-start summon.
        scheduleIdleVisibilityCountdown(
            "startInitialSettleTimer",
            freshStartIgnorePending = false
        )
    }

    private fun resetDockVisibilityTimer(forceDespiteTextFocus: Boolean = false) {
        cancelDockVisibilityTimer()
        cancelInitialSettleTimer()
        awaitingInitialDock = false
        // Continuous-running re-arm: pending must again control hide vs dock.
        idleHideIgnoresPending = false
        if (!SettingsManager.isEdgeDockingMode(this) || isEdgeDocked) return
        if (replyPanel?.isShowing == true) {
            Logger.d("Dock visibility timer not started — reply panel open")
            return
        }
        // Focus-held: no countdown — stay undocked until the field loses focus.
        // Notification pop-out may force a timer; dockVisibilityRunnable still respects live focus.
        if (heldByTextFocus && !forceDespiteTextFocus) {
            Logger.d("Dock visibility timer not started — held by text focus")
            return
        }
        dockHandler.postDelayed(dockVisibilityRunnable, DOCK_VISIBILITY_MS)
        Logger.d("Edge-dock visibility timer reset (${DOCK_VISIBILITY_MS}ms)")
    }

    /** Resume normal edge-dock countdown after the reply panel closes. */
    private fun onReplyPanelDismissed() {
        if (isDestroyed || isDismissing) return
        if (!SettingsManager.isEdgeDockingMode(this)) return
        if (isHeldByEditableFocus()) {
            cancelDockVisibilityTimer()
            Logger.d("Reply panel closed — kept undocked (text field focused)")
            return
        }
        val hasPending = ReplyStore.count() > 0 || badgeCount > 0
        if (hasPending) {
            if (!isEdgeDocked) {
                resetDockVisibilityTimer()
                Logger.d("Reply panel closed — dock visibility timer resumed")
            }
        } else if (SettingsManager.isHideWhenIdleMode(this) && !isCatFullyHidden) {
            // Notify-only: keep visible briefly, then slide off-screen.
            cancelDockVisibilityTimer()
            cancelInitialSettleTimer()
            awaitingInitialDock = false
            idleHideIgnoresPending = false
            dockHandler.postDelayed(dockVisibilityRunnable, DOCK_VISIBILITY_MS)
            Logger.d("Reply panel closed (empty) — hide-off-screen timer started")
        } else if (!isEdgeDocked) {
            resetDockVisibilityTimer()
            Logger.d("Reply panel closed (empty) — dock visibility timer resumed")
        }
    }

    private fun wireReplyPanel(panel: ReplyPanel) {
        panel.onDismissed = { onReplyPanelDismissed() }
    }

    fun applyCatDisplayMode() {
        if (isDestroyed) return
        heldByTextFocus = false
        cancelTextFocusHide()
        if (SettingsManager.isEdgeDockingMode(this)) {
            catAnimator?.setIdleSleepEnabled(false)
            cancelInitialSettleTimer()
            awaitingInitialDock = false
            val hasPending = ReplyStore.count() > 0 || badgeCount > 0
            when {
                hasPending && isCatFullyHidden ->
                    revealCatFromOffScreen(toFloat = false, startVisibilityTimer = false)
                hasPending && !isEdgeDocked ->
                    dockToEdge(animate = true)
                hasPending && isEdgeDocked ->
                    applyDockedOpacity()
                !hasPending && SettingsManager.isHideWhenIdleMode(this) ->
                    hideCatFullyOffScreen(animate = !isCatFullyHidden)
                !hasPending ->
                    dockToEdge(animate = true)
            }
        } else {
            cancelInitialSettleTimer()
            cancelDockVisibilityTimer()
            awaitingInitialDock = false
            isCatFullyHidden = false
            containerView?.visibility = View.VISIBLE
            catAnimator?.setIdleSleepEnabled(true)
            if (isEdgeDocked) {
                undockToFloat(animate = true, startVisibilityTimer = false)
            }
        }
        Logger.d("Applied cat display mode: ${SettingsManager.getCatDisplayMode(this)}")
    }

    private fun applyDockedOpacity() {
        val opacity = SettingsManager.getSleepOpacity(this)
        catView?.alpha = opacity
    }

    private fun dockToEdge(animate: Boolean) {
        val params = layoutParams ?: return
        val view = containerView ?: return
        if (!SettingsManager.isEdgeDockingMode(this)) return

        // Remember current float spot before docking (if undocked)
        if (!isEdgeDocked) {
            persistFloatPositionAndDockSide(params.x, params.y)
        }

        cancelDockVisibilityTimer()
        cancelInitialSettleTimer()
        awaitingInitialDock = false
        cancelDockAnimator()
        replyPanel?.dismiss()

        val dockSize = dockedIconSize()
        val (screenWidth, screenHeight) = currentDisplaySize()
        val targetX = dockedEdgeX(dockSize, screenWidth)
        val targetY = SettingsManager.getCatFloatY(this)
            .coerceIn(40, (screenHeight - dockSize - 40).coerceAtLeast(40))
        val startX = params.x
        val startY = params.y
        val startW = params.width
        val startH = params.height
        val startAlpha = catView?.alpha ?: 1f
        val targetAlpha = SettingsManager.getSleepOpacity(this)

        isCatFullyHidden = false
        view.visibility = View.VISIBLE
        catView?.visibility = View.VISIBLE
        isEdgeDocked = true
        catAnimator?.showFrame(69)

        if (!animate) {
            params.width = dockSize
            params.height = dockSize
            params.x = targetX
            params.y = targetY
            safeUpdateViewLayout(view, params)
            applyDockedOpacity()
            Logger.d("Cat docked at edge x=$targetX size=$dockSize opacity=$targetAlpha")
            return
        }

        val token = ++dockAnimToken
        dockAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DOCK_SLIDE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                if (isDestroyed || token != dockAnimToken) return@addUpdateListener
                val t = anim.animatedValue as Float
                params.width = (startW + (dockSize - startW) * t).toInt()
                params.height = (startH + (dockSize - startH) * t).toInt()
                params.x = (startX + (targetX - startX) * t).toInt()
                params.y = (startY + (targetY - startY) * t).toInt()
                catView?.alpha = startAlpha + (targetAlpha - startAlpha) * t
                safeUpdateViewLayout(view, params)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (token != dockAnimToken) return
                    dockAnimator = null
                    params.width = dockSize
                    params.height = dockSize
                    params.x = targetX
                    params.y = targetY
                    safeUpdateViewLayout(view, params)
                    applyDockedOpacity()
                    Logger.d("Cat docked at edge x=$targetX size=$dockSize opacity=$targetAlpha")
                }
            })
        }
        dockAnimator?.start()
    }

    private fun undockToFloat(
        animate: Boolean,
        startVisibilityTimer: Boolean,
        onComplete: (() -> Unit)? = null
    ) {
        val params = layoutParams ?: return
        val view = containerView ?: return

        if (isCatFullyHidden) {
            revealCatFromOffScreen(
                toFloat = true,
                startVisibilityTimer = startVisibilityTimer,
                onComplete = onComplete
            )
            return
        }

        if (!isEdgeDocked) {
            if (startVisibilityTimer && SettingsManager.isEdgeDockingMode(this)) {
                resetDockVisibilityTimer(forceDespiteTextFocus = true)
            }
            onComplete?.invoke()
            return
        }

        cancelDockAnimator()
        val fullSize = SettingsManager.getCatSizePx(this)
        val targetX = SettingsManager.getCatFloatX(this)
        val targetY = SettingsManager.getCatFloatY(this)
        val startX = params.x
        val startY = params.y
        val startW = params.width
        val startH = params.height

        isCatFullyHidden = false
        view.visibility = View.VISIBLE
        catView?.visibility = View.VISIBLE
        isEdgeDocked = false
        catView?.alpha = 1f
        awaitingInitialDock = false
        cancelInitialSettleTimer()

        fun finishUndock() {
            params.width = fullSize
            params.height = fullSize
            params.x = targetX
            params.y = targetY
            safeUpdateViewLayout(view, params)
            catAnimator?.showStatic()
            if (startVisibilityTimer && SettingsManager.isEdgeDockingMode(this)) {
                resetDockVisibilityTimer(forceDespiteTextFocus = true)
            } else {
                cancelDockVisibilityTimer()
            }
            Logger.d("Cat undocked to ($targetX,$targetY)")
            onComplete?.invoke()
        }

        if (!animate) {
            finishUndock()
            return
        }

        val token = ++dockAnimToken
        dockAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DOCK_SLIDE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                if (isDestroyed || token != dockAnimToken) return@addUpdateListener
                val t = anim.animatedValue as Float
                params.width = (startW + (fullSize - startW) * t).toInt()
                params.height = (startH + (fullSize - startH) * t).toInt()
                params.x = (startX + (targetX - startX) * t).toInt()
                params.y = (startY + (targetY - startY) * t).toInt()
                safeUpdateViewLayout(view, params)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (token != dockAnimToken) return
                    dockAnimator = null
                    finishUndock()
                }
            })
        }
        dockAnimator?.start()
    }

    private fun onReplyableBadgeIncreased() {
        if (!SettingsManager.isEdgeDockingMode(this)) return
        cancelInitialSettleTimer()
        awaitingInitialDock = false

        // Focus-triggered visibility only checks pending at focus-gain. Re-evaluate here so a
        // notification that arrives while already undocked for dictation takes over.
        if (heldByTextFocus) {
            android.util.Log.d(
                "ScrollCat",
                "New message arrived while in focus-triggered mode - switching to pending-message priority"
            )
            heldByTextFocus = false
            cancelTextFocusHide()
        }
        // Close an open dictation bubble so the badge/pending state is visible (same priority
        // as tap: pending always wins over voice dictation).
        if (replyPanel?.isDictationMode == true) {
            replyPanel?.dismiss()
        }

        val needsSlideIn = needsEntranceRevealFromHidden()
        android.util.Log.d(
            "ScrollCat",
            "###CAT_ENTRANCE_DEBUG### onReplyableBadgeIncreased - " +
                "isCatFullyHidden=$isCatFullyHidden isEdgeDocked=$isEdgeDocked " +
                "awaitingInitialDock=$awaitingInitialDock badgeCount=$badgeCount " +
                "containerVis=${containerView?.visibility} catAlpha=${catView?.alpha} " +
                "needsSlideIn=$needsSlideIn hasLayoutParams=${layoutParams != null} " +
                "thread=${Thread.currentThread().name}"
        )

        // Hidden / not visible (incl. fresh-install first reveal): always slide in from edge.
        if (needsSlideIn) {
            revealCatFromOffScreen(toFloat = true, startVisibilityTimer = true)
            return
        }

        // Peeking dock → pop out to float (existing notification attention).
        if (isEdgeDocked) {
            undockToFloat(animate = true, startVisibilityTimer = true)
            return
        }

        // Third state: already floating (post-summon / undocked) but neither hidden nor
        // docked — previously only reset a timer, so the first badge could land with no motion.
        // Play the same shake/pulse used for badge attention, then keep the dock timer.
        containerView?.visibility = View.VISIBLE
        catView?.visibility = View.VISIBLE
        if ((catView?.alpha ?: 0f) < 0.5f) {
            catView?.alpha = 1f
        }
        catView?.post { animateNotification() }
        resetDockVisibilityTimer(forceDespiteTextFocus = true)
        android.util.Log.d(
            "ScrollCat",
            "###CAT_ENTRANCE_DEBUG### floating-undocked branch — animateNotification shake + timer"
        )
    }

    private fun startIdleAnimation() {
        catAnimator?.showStatic()
    }

    fun animateTap() {
        catAnimator?.play("tap") {
            catAnimator?.showStatic()
        }
    }

    fun animateFlingUp() { }

    fun animateFlingDown() { }

    fun animateNotification() {
        catView?.let { view ->
            ObjectAnimator.ofFloat(view, "translationX", 0f, -20f, 20f, -15f, 15f, -10f, 10f, 0f).apply {
                duration = 400
                start()
            }
        }
    }

    fun wakeFromSleep() {
        Logger.d("wakeFromSleep called - badge count: $badgeCount")
        catAnimator?.wakeUp()
        // DO NOT clear badge here
    }

    fun reactToApp(packageName: String) {
        musicDetector?.updateForegroundApp(packageName)
        val config = AppReactionManager.getReaction(this, packageName) ?: return
        Logger.d("App reaction: ${config.emoji} for $packageName")

        // Play animation
        when (config.animation) {
            "tap" -> catAnimator?.play("tap") { catAnimator?.showStatic() }
            "awake" -> catAnimator?.play("waking") { catAnimator?.play("awake") { catAnimator?.showStatic() } }
            "sleeping" -> catAnimator?.play("sleeping")
            else -> catAnimator?.showStatic()
        }

        // Show emoji above cat if not empty
        if (config.emoji.isNotEmpty()) {
            showReactionEmoji(config.emoji)
        }
    }

    fun onMusicStarted() {
        catAnimator?.playMusic()
        // No emoji while dancing
    }

    fun onMusicStopped() {
        catAnimator?.stopMusic()
    }

    fun updateSleepOpacity(opacity: Float) {
        if (catAnimator?.isAsleep() == true || isEdgeDocked) {
            catView?.alpha = opacity
            Logger.d("Sleep/dock opacity updated in real time: $opacity")
        }
    }

    fun showVolumeControls() {
        hideVolumeControls()

        audioManager.adjustVolume(AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND)

        val catX = layoutParams?.x ?: 60
        val catY = layoutParams?.y ?: 600
        val catSize = SettingsManager.getCatSizePx(this)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(16, 16, 16, 16)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadius = 48f
            }
        }

        // Top indicator
        val topLabel = android.widget.TextView(this).apply {
            text = "🔊"
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setPadding(8, 8, 8, 8)
        }

        // Bottom indicator
        val bottomLabel = android.widget.TextView(this).apply {
            text = "🔇"
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setPadding(8, 8, 8, 8)
        }

        // Track line
        val track = android.view.View(this).apply {
            setBackgroundColor(0x88FFFFFF.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(4, 120)
        }

        layout.addView(topLabel)
        layout.addView(track)
        layout.addView(bottomLabel)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = catX - 20
            y = catY - 160
        }

        safeAddView(layout, params)
        volumeControlView = layout

        // Now wire the cat drag to volume
        resetVolumeTimeout()
        isVolumeMode = true
        Logger.d("Volume mode started")
    }

    private fun resetVolumeTimeout() {
        volumeHandler.removeCallbacksAndMessages(null)
        volumeHandler.postDelayed({ hideVolumeControls() }, 3000)
    }

    fun hideVolumeControls() {
        safeRemoveView(volumeControlView)
        volumeControlView = null
        volumeHandler.removeCallbacksAndMessages(null)
        isVolumeMode = false
        volumeUIShown = false
        hideDragHandle()
    }

    fun showTranslationBubble(original: String, translated: String, language: String) {
        if (isDestroyed) return
        hideTranslationBubble()

        val catX = layoutParams?.x ?: 60
        val catY = layoutParams?.y ?: 600
        val catSize = SettingsManager.getCatSizePx(this)

        val bubble = android.widget.TextView(this).apply {
            val preview = if (translated.length > 100)
                translated.take(100) + "... tap for more"
                else translated
            text = "🌐 $preview"
            textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
            maxWidth = 600
            setSingleLine(false)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xCC1E1E28.toInt())
                cornerRadius = 24f
                setStroke(1, 0x44FFFFFF.toInt())
            }
            setPadding(24, 16, 24, 16)
            setOnClickListener {
                // Show full translation on tap
                text = "🌐 $translated"
                translationHandler.removeCallbacksAndMessages(null)
                translationHandler.postDelayed({ hideTranslationBubble() }, 10000)
            }
        }

        val params = WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = catX
            y = catY - 160
        }

        safeAddView(bubble, params)
        translationBubble = bubble

        // Auto hide after 5 seconds
        translationHandler.removeCallbacksAndMessages(null)
        translationHandler.postDelayed({ hideTranslationBubble() }, 5000)

        Logger.d("Translation bubble shown: chars=${translated.length}")
    }

    fun hideTranslationBubble() {
        safeRemoveView(translationBubble)
        translationBubble = null
        translationHandler.removeCallbacksAndMessages(null)
    }

    /**
     * Short status bubble above the cat, e.g. "Auto-replied to Sam ✓".
     * Auto-hides after 3 seconds.
     */
    fun showCatMessage(text: String) {
        if (isDestroyed) return
        hideCatMessage()

        val catX = layoutParams?.x ?: 60
        val catY = layoutParams?.y ?: 600

        val bubble = android.widget.TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxWidth = 600
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE61E1E28.toInt())
                cornerRadius = 24f
                setStroke(1, 0x44FFFFFF)
            }
            setPadding(28, 16, 28, 16)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = catX
            y = (catY - 120).coerceAtLeast(0)
        }

        safeAddView(bubble, params)
        catMessageBubble = bubble
        catMessageHandler.removeCallbacksAndMessages(null)
        catMessageHandler.postDelayed({ hideCatMessage() }, 3000)
    }

    private fun hideCatMessage() {
        safeRemoveView(catMessageBubble)
        catMessageBubble = null
        catMessageHandler.removeCallbacksAndMessages(null)
    }

    private fun showReactionEmoji(emoji: String) {
        if (isDestroyed) return
        safeRemoveView(currentReactionEmoji)
        currentReactionEmoji = null

        val emojiView = android.widget.TextView(this).apply {
            text = emoji
            textSize = 28f
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = (layoutParams?.x ?: 60) + 60
            y = (layoutParams?.y ?: 600) - 80
        }

        if (!safeAddView(emojiView, params)) return
        currentReactionEmoji = emojiView

        // Auto remove after 2 seconds
        reactionHandler.removeCallbacksAndMessages(null)
        reactionHandler.postDelayed({
            safeRemoveView(emojiView)
            if (currentReactionEmoji === emojiView) currentReactionEmoji = null
        }, 2000)
    }

    fun updateCatSize(sizeDp: Int) {
        val params = layoutParams ?: return
        val view = containerView ?: return
        val size = dp(sizeDp.coerceIn(SettingsManager.CAT_SIZE_DP_MIN, SettingsManager.CAT_SIZE_DP_MAX))
        if (isEdgeDocked && SettingsManager.isEdgeDockingMode(this)) {
            val dockDp = (sizeDp * 0.55f).toInt().coerceAtLeast(24)
            val dockSize = dp(dockDp)
            params.width = dockSize
            params.height = dockSize
            params.x = dockedEdgeX(dockSize, currentDisplaySize().first)
            view.post { safeUpdateViewLayout(view, params) }
            return
        }
        params.width = size
        params.height = size
        view.post { safeUpdateViewLayout(view, params) }
    }

    fun updateSensitivity(value: Int) {
        DISTANCE_TRIGGER_THRESHOLD_LIVE = value
    }

    fun incrementBadge() {
        if (isDestroyed) return
        // Notification listener may call from a binder thread — UI/anim must be main.
        dockHandler.post {
            if (isDestroyed) return@post
            val panelOpen = replyPanel?.isShowing == true
            android.util.Log.d(
                "ScrollCat",
                "Incrementing badge, panel currently open: $panelOpen, current count before: $badgeCount"
            )
            catView?.post { animateNotification() }
            badgeCount++
            updateBadge()
            onReplyableBadgeIncreased()
            onReplyablesChanged()
        }
    }

    fun clearBadge() {
        if (isDestroyed) return
        dockHandler.post {
            if (isDestroyed) return@post
            badgeCount = 0
            updateBadge()
        }
    }

    fun setBadgeCount(count: Int) {
        if (isDestroyed) return
        // Notification listener may call from a binder thread — UI/anim must be main.
        dockHandler.post {
            if (isDestroyed) return@post
            val previous = badgeCount
            badgeCount = count.coerceAtLeast(0)
            android.util.Log.d(
                "ScrollCat",
                "###CAT_ENTRANCE_DEBUG### setBadgeCount - previous=$previous " +
                    "new=$badgeCount isCatFullyHidden=$isCatFullyHidden " +
                    "containerVis=${containerView?.visibility} " +
                    "thread=${Thread.currentThread().name}"
            )
            updateBadge()
            if (badgeCount > previous) {
                onReplyableBadgeIncreased()
            }
            onReplyablesChanged()
        }
    }

    /**
     * Called whenever ReplyStore pending replyables may have changed while the overlay
     * is alive. Refreshes the open ReplyPanel so ↓ new-sender UI can appear.
     */
    fun onReplyablesChanged() {
        if (isDestroyed) return
        val panel = replyPanel
        val open = panel?.isShowing == true
        android.util.Log.d(
            "ScrollCat",
            "onReplyablesChanged - panelOpen=$open storeCount=${ReplyStore.count()}"
        )
        if (!open || panel == null) return
        // Notification listener may not always be on the main thread — post UI work.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (isDestroyed || replyPanel?.isShowing != true) return@post
            replyPanel?.refreshPendingFromStore()
        }
    }

    fun isReplyPanelShowing(): Boolean = replyPanel?.isShowing == true

    fun updateBadgeAfterReply() {
        dockHandler.post {
            if (isDestroyed) return@post
            val remaining = ReplyStore.getAll().size
            if (remaining == 0) {
                badgeCount = 0
                updateBadge()
                // Empty queue (notify-only): keep visible for DOCK_VISIBILITY_MS, then hide.
                if (SettingsManager.isHideWhenIdleMode(this) &&
                    !isCatFullyHidden &&
                    !isHeldByEditableFocus() &&
                    replyPanel?.isShowing != true
                ) {
                    cancelDockVisibilityTimer()
                    cancelInitialSettleTimer()
                    awaitingInitialDock = false
                    idleHideIgnoresPending = false
                    dockHandler.postDelayed(dockVisibilityRunnable, DOCK_VISIBILITY_MS)
                    Logger.d("Empty queue — hide-off-screen timer started (${DOCK_VISIBILITY_MS}ms)")
                } else if (SettingsManager.isEdgeDockingMode(this) &&
                    !SettingsManager.isHideWhenIdleMode(this) &&
                    !isEdgeDocked &&
                    !isHeldByEditableFocus() &&
                    replyPanel?.isShowing != true
                ) {
                    resetDockVisibilityTimer()
                }
            } else {
                badgeCount = remaining
                updateBadge()
            }
            Logger.d("Badge updated after reply - remaining: $remaining")
        }
    }

    fun showReplyPanel() {
        val params = layoutParams ?: return
        val catSize = SettingsManager.getCatSizePx(this)
        animateTap()
        // Hold undocked/visible for the entire time the panel is open.
        cancelDockVisibilityTimer()
        cancelInitialSettleTimer()
        awaitingInitialDock = false
        replyPanel?.show(params.x, params.y, catSize)
        Logger.d("Reply panel shown (${ReplyStore.count()} pending) — dock timer paused")
    }

    /**
     * Seeds the scripted onboarding demo: fake message + badge on the cat.
     * Tapping the badge opens the real ReplyPanel with hardcoded demo data.
     *
     * [onDemoReplySent] is stored on the service (not only the panel) so the
     * completion path still fires if ReplyPanel is recreated before the reply.
     */
    fun seedOnboardingDemo(
        onDemoPanelShown: (() -> Unit)? = null,
        onDemoReplySent: (() -> Unit)? = null
    ) {
        ReplyStore.putDemo()
        // Ensure a panel exists before wiring callbacks.
        if (replyPanel == null) {
            replyPanel = ReplyPanel(this, windowManager).also { wireReplyPanel(it) }
        }
        onboardingDemoReplySentHandler = onDemoReplySent
        replyPanel?.resetOnboardingDemoState()
        replyPanel?.onDemoPanelShown = onDemoPanelShown
        replyPanel?.onDemoReplySent = onDemoReplySent
        setBadgeCount(ReplyStore.count().coerceAtLeast(1))
        Logger.d("Onboarding demo seeded (badge=$badgeCount, handler=${onDemoReplySent != null})")
    }

    /**
     * Invoked exactly once when the demo reply is sent. Clears the handler so a
     * double-fire cannot run Continue UI twice.
     */
    fun dispatchOnboardingDemoCompleted() {
        val handler = onboardingDemoReplySentHandler
        onboardingDemoReplySentHandler = null
        replyPanel?.onDemoReplySent = null
        android.util.Log.d(
            "ScrollCat",
            "dispatchOnboardingDemoCompleted - handlerRegistered=${handler != null}"
        )
        if (handler == null) {
            android.util.Log.w(
                "ScrollCat",
                "Demo completion dispatched but no onboarding handler was registered"
            )
            return
        }
        try {
            handler.invoke()
        } catch (e: Exception) {
            android.util.Log.e("ScrollCat", "Exception in demo completion handling", e)
        }
    }

    fun clearOnboardingDemoCallback() {
        onboardingDemoReplySentHandler = null
        replyPanel?.onDemoPanelShown = null
        replyPanel?.onDemoReplySent = null
        onDismissFadeCompleted = null
    }

    private fun updateBadge() {
        badgeView?.post {
            if (badgeCount > 0) {
                badgeView?.text = if (badgeCount > 99) "99+" else badgeCount.toString()
                badgeView?.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(
                        if (ReplyStore.hasPriorityPending()) {
                            0xFF16A34A.toInt()
                        } else {
                            Color.RED
                        }
                    )
                }
                badgeView?.visibility = android.view.View.VISIBLE
            } else {
                badgeView?.visibility = android.view.View.GONE
            }
        }
    }

    private fun showDragHandle(catParams: WindowManager.LayoutParams) {
        if (handleView != null) return
        val catSize = SettingsManager.getCatSizePx(this)
        val pad = dp(20)
        val size = catSize + pad * 2 // slightly bigger than cat

        val circle = android.view.View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x33FFFFFF)
                setStroke(3, 0x88FFFFFF.toInt())
            }
        }

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = catParams.x - pad
            y = catParams.y - pad
        }

        safeAddView(circle, params)
        handleView = circle
        handleParams = params
    }

    private fun moveDragHandle(catParams: WindowManager.LayoutParams) {
        val view = handleView ?: return
        val params = handleParams ?: return
        val pad = dp(20)
        params.x = catParams.x - pad
        params.y = catParams.y - pad
        safeUpdateViewLayout(view, params)
    }

    private fun hideDragHandle() {
        safeRemoveView(handleView)
        handleView = null
        handleParams = null
    }

    private fun closeZoneIdleSizePx(): Int = dp(64)

    private fun closeZoneActiveScale(): Float = 76f / 64f // ~76dp when highlighted

    private fun closeZoneBottomMarginPx(): Int = dp(96)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun closeZoneInterpolator() = AccelerateDecelerateInterpolator()

    private fun showCloseZone() {
        if (closeZoneView != null) return
        val size = closeZoneIdleSizePx()
        val dm = resources.displayMetrics

        // Outer host sized for idle circle; scale-up animates beyond layout bounds (overlay OK)
        val zone = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            alpha = 0f
            scaleX = 0.8f
            scaleY = 0.8f
            elevation = dp(8).toFloat()
        }

        // Soft outer glow ring (hidden until highlighted)
        val glow = View(this).apply {
            tag = "close_zone_glow"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(4), 0x66E53935)
            }
            alpha = 0f
            scaleX = 1.15f
            scaleY = 1.15f
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        }
        zone.addView(glow)

        // Main circle disk
        val disk = FrameLayout(this).apply {
            tag = "close_zone_disk"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC1E1E28.toInt())
            }
            elevation = dp(6).toFloat()
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            addView(ImageView(this@OverlayService).apply {
                setImageResource(R.drawable.ic_close_zone_x)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val icon = dp(24)
                layoutParams = FrameLayout.LayoutParams(icon, icon, Gravity.CENTER)
            })
        }
        zone.addView(disk)

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels - size) / 2
            y = dm.heightPixels - size - closeZoneBottomMarginPx()
        }
        if (safeAddView(zone, params)) {
            closeZoneView = zone
            closeZoneParams = params
            closeZoneHighlighted = false
            zone.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(150L)
                .setInterpolator(closeZoneInterpolator())
                .start()
            Logger.d("Close zone shown for Move mode")
        }
    }

    private fun hideCloseZone(animated: Boolean = false, onEnd: (() -> Unit)? = null) {
        val zone = closeZoneView
        if (zone == null) {
            onEnd?.invoke()
            return
        }
        zone.animate().cancel()
        if (!animated) {
            safeRemoveView(zone)
            closeZoneView = null
            closeZoneParams = null
            closeZoneHighlighted = false
            onEnd?.invoke()
            return
        }
        zone.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(150L)
            .setInterpolator(closeZoneInterpolator())
            .withEndAction {
                safeRemoveView(zone)
                if (closeZoneView === zone) {
                    closeZoneView = null
                    closeZoneParams = null
                    closeZoneHighlighted = false
                }
                onEnd?.invoke()
            }
            .start()
    }

    private fun animateDismissIntoCloseZone(onEnd: () -> Unit) {
        val zone = closeZoneView
        val cat = containerView
        val interp = closeZoneInterpolator()
        // Shrink + fade cat and zone together
        cat?.animate()?.cancel()
        zone?.animate()?.cancel()
        var pending = 0
        fun done() {
            pending--
            if (pending <= 0) onEnd()
        }
        if (cat != null) {
            pending++
            cat.pivotX = cat.width / 2f
            cat.pivotY = cat.height / 2f
            cat.animate()
                .scaleX(0.2f)
                .scaleY(0.2f)
                .alpha(0f)
                .setDuration(150L)
                .setInterpolator(interp)
                .withEndAction { done() }
                .start()
        }
        if (zone != null) {
            pending++
            hideCloseZone(animated = true) { done() }
        }
        if (pending == 0) onEnd()
    }

    private fun resetCatCloseZoneScale() {
        val cat = containerView ?: return
        cat.animate().cancel()
        cat.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(150L)
            .setInterpolator(closeZoneInterpolator())
            .start()
    }

    private fun catCenter(catParams: WindowManager.LayoutParams): Pair<Float, Float> {
        val cx = catParams.x + catParams.width / 2f
        val cy = catParams.y + catParams.height / 2f
        return cx to cy
    }

    private fun closeZoneCenter(): Pair<Float, Float>? {
        val zp = closeZoneParams ?: return null
        val size = closeZoneIdleSizePx()
        return (zp.x + size / 2f) to (zp.y + size / 2f)
    }

    /**
     * Clear overlap required for dismiss — roughly centers within ~55% of combined radii
     * so near-misses near the zone edge do not dismiss.
     */
    private fun isCatClearlyInCloseZone(catParams: WindowManager.LayoutParams): Boolean {
        val (cx, cy) = catCenter(catParams)
        val (zx, zy) = closeZoneCenter() ?: return false
        val dist = hypot((cx - zx).toDouble(), (cy - zy).toDouble()).toFloat()
        val zoneR = closeZoneIdleSizePx() / 2f * (if (closeZoneHighlighted) closeZoneActiveScale() else 1f)
        val combinedR = catParams.width / 2f + zoneR
        return dist <= combinedR * 0.55f
    }

    /** Slightly looser than dismiss — used to highlight / soft-magnet while dragging. */
    private fun isCatNearCloseZone(catParams: WindowManager.LayoutParams): Boolean {
        val (cx, cy) = catCenter(catParams)
        val (zx, zy) = closeZoneCenter() ?: return false
        val dist = hypot((cx - zx).toDouble(), (cy - zy).toDouble()).toFloat()
        val zoneR = closeZoneIdleSizePx() / 2f
        val combinedR = catParams.width / 2f + zoneR
        return dist <= combinedR * 0.85f
    }

    private fun applyCloseZoneMagnet(catParams: WindowManager.LayoutParams) {
        if (!isCatNearCloseZone(catParams)) return
        val (zx, zy) = closeZoneCenter() ?: return
        val (cx, cy) = catCenter(catParams)
        // Pull ~35% of the way toward zone center each move frame
        val targetX = (cx + (zx - cx) * 0.35f - catParams.width / 2f).toInt()
        val targetY = (cy + (zy - cy) * 0.35f - catParams.height / 2f).toInt()
        catParams.x = targetX
        catParams.y = targetY
    }

    private fun updateCloseZoneHighlight(catParams: WindowManager.LayoutParams) {
        val zone = closeZoneView ?: return
        val near = isCatNearCloseZone(catParams)
        val cat = containerView
        if (cat != null) {
            cat.pivotX = (cat.width.takeIf { it > 0 } ?: catParams.width).toFloat() / 2f
            cat.pivotY = (cat.height.takeIf { it > 0 } ?: catParams.height).toFloat() / 2f
            if (near) {
                // WhatsApp-style "about to delete" shrink
                if (cat.scaleX > 0.75f) {
                    cat.animate().cancel()
                    cat.animate()
                        .scaleX(0.7f)
                        .scaleY(0.7f)
                        .setDuration(160L)
                        .setInterpolator(closeZoneInterpolator())
                        .start()
                }
            } else if (closeZoneHighlighted && cat.scaleX < 0.95f) {
                cat.animate().cancel()
                cat.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(160L)
                    .setInterpolator(closeZoneInterpolator())
                    .start()
            }
        }
        if (near == closeZoneHighlighted) return
        closeZoneHighlighted = near

        val disk = zone.findViewWithTag<View>("close_zone_disk")
        val glow = zone.findViewWithTag<View>("close_zone_glow")
        val diskBg = disk?.background as? GradientDrawable
        val glowBg = glow?.background as? GradientDrawable
        zone.animate().cancel()
        if (near) {
            diskBg?.setColor(0xE6E53935.toInt()) // #E53935 ~90%
            glowBg?.setStroke(dp(5), 0x99FF8A80.toInt())
            glow?.animate()?.alpha(1f)?.setDuration(160L)?.setInterpolator(closeZoneInterpolator())?.start()
            zone.animate()
                .scaleX(closeZoneActiveScale())
                .scaleY(closeZoneActiveScale())
                .setDuration(180L)
                .setInterpolator(closeZoneInterpolator())
                .start()
            zone.elevation = dp(14).toFloat()
            disk?.elevation = dp(10).toFloat()
        } else {
            diskBg?.setColor(0xCC1E1E28.toInt())
            glow?.animate()?.alpha(0f)?.setDuration(160L)?.setInterpolator(closeZoneInterpolator())?.start()
            zone.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180L)
                .setInterpolator(closeZoneInterpolator())
                .start()
            zone.elevation = dp(8).toFloat()
            disk?.elevation = dp(6).toFloat()
        }
    }

    fun setTouchable(touchable: Boolean) {
        val params = layoutParams ?: return
        val view = catView ?: return
        if (touchable) {
            params.flags = params.flags and
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags = params.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        containerView?.post { safeUpdateViewLayout(containerView, params) }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        android.util.Log.w("ScrollCat", "System low memory warning")
        catAnimator?.onLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                android.util.Log.w("ScrollCat", "Trim memory level: $level")
                catAnimator?.onLowMemory()
                releaseOnDeviceAiForMemoryPressure(level.toString())
            }
            else -> {
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                    android.util.Log.w("ScrollCat", "Trim memory level: $level")
                    catAnimator?.onLowMemory()
                }
            }
        }
    }

    override fun onDestroy() {
        android.util.Log.d(
            "ScrollCat",
            "Dismiss/onDestroy - containerAttached=${isViewAttached(containerView)} " +
                "catView=$catView isEdgeDocked=$isEdgeDocked"
        )
        isDestroyed = true
        onboardingDemoReplySentHandler = null
        dismissFadeAnimator?.removeAllListeners()
        dismissFadeAnimator?.cancel()
        dismissFadeAnimator = null
        cancelDockAnimator()
        cancelDockVisibilityTimer()
        cancelInitialSettleTimer()
        cancelTextFocusHide()
        heldByTextFocus = false
        catTouchListener?.cleanup()
        catTouchListener = null
        hideDragHandle()
        hideCloseZone()
        replyPanel?.destroy()
        replyPanel = null
        hideVolumeControls()
        hideTranslationBubble()
        hideCatMessage()
        safeRemoveView(currentReactionEmoji)
        currentReactionEmoji = null
        safeRemoveView(containerView)
        containerView = null
        catView = null
        lottieView = null
        badgeView = null
        layoutParams = null
        isEdgeDocked = false
        isCatFullyHidden = false
        awaitingInitialDock = false
        reactionHandler.removeCallbacksAndMessages(null)
        volumeHandler.removeCallbacksAndMessages(null)
        translationHandler.removeCallbacksAndMessages(null)
        catMessageHandler.removeCallbacksAndMessages(null)
        catAnimator?.stopAll()
        catAnimator = null
        musicDetector?.stop()
        musicDetector = null
        screenTranslator?.close()
        screenTranslator = null
        try { unregisterReceiver(screenStateReceiver) } catch (e: Exception) { }
        cancelOnDeviceIdleTimer()
        DeviceIdleMonitor.unregister(this)
        OnDeviceAiEngine.shutdown()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
