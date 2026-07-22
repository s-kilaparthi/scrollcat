package com.example.scrollcat

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.example.scrollcat.CatAnimator
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        var instance: OverlayService? = null
            private set
        const val CHANNEL_ID = "scrollcat_overlay"
        const val ACTION_SUMMON = "com.example.scrollcat.ACTION_SUMMON"
        const val ACTION_DISMISS = "com.example.scrollcat.ACTION_DISMISS"
        const val FLING_VELOCITY_THRESHOLD = 700 // px/sec, tune on device
        const val DISTANCE_TRIGGER_THRESHOLD = 70 // px for up/down scroll
        const val HORIZONTAL_TRIGGER_THRESHOLD = 180 // px for left/right — much bigger to avoid accidental triggers
        const val LONG_PRESS_TIMEOUT_MS = 450L
        const val MOVE_CANCEL_SLOP = 60 // px of movement that cancels a pending long-press
        const val MODE_FEED = false
        const val MODE_REELS = true
        const val DOCK_VISIBILITY_MS = 10_000L
        const val INITIAL_SETTLE_DOCK_MS = 10_000L
        const val MOVE_MODE_TIMEOUT_MS = 10_000L
        var DISTANCE_TRIGGER_THRESHOLD_LIVE = 70
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
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    pauseBackgroundWork()
                    Logger.d("Screen off - background work paused")
                }
                Intent.ACTION_SCREEN_ON -> {
                    resumeBackgroundWork()
                    Logger.d("Screen on - background work resumed")
                }
            }
        }
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
        catAnimator?.showStatic()
        musicDetector?.resume()
    }

    private var badgeCount = 0
    private var badgeView: android.widget.TextView? = null
    private var containerView: FrameLayout? = null

    // Edge-docking state (only used when display mode is edge_docking)
    private var isEdgeDocked = false
    /** True while waiting for the post-summon settle-into-dock (not the post-message re-dock timer). */
    private var awaitingInitialDock = false
    private var dockAnimator: ValueAnimator? = null
    private val dockHandler = Handler(Looper.getMainLooper())
    private val dockVisibilityRunnable = Runnable {
        if (SettingsManager.isEdgeDockingMode(this) && !isEdgeDocked) {
            dockToEdge(animate = true)
        }
    }
    private val initialSettleDockRunnable = Runnable {
        if (SettingsManager.isEdgeDockingMode(this) && awaitingInitialDock && !isEdgeDocked) {
            awaitingInitialDock = false
            dockToEdge(animate = true)
            Logger.d("Initial settle timer elapsed — docking")
        }
    }

    var isReelsMode = false
        private set

    private var handleView: View? = null
    private var handleParams: WindowManager.LayoutParams? = null

    private val screenStateReceiver = ScreenStateReceiver(
        onScreenOn = { AiReplyGenerator.flushAllBuffers(applicationContext) }
    )

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d(
            "ScrollCat",
            "Summon/onCreate - isDestroyed=$isDestroyed instance=${instance != null} " +
                "containerAttached=${isViewAttached(containerView)} catView=$catView " +
                "isEdgeDocked=$isEdgeDocked"
        )
        val screenStateFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, screenStateFilter)
        instance = this
        isDestroyed = false
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        addCatView()
        replyPanel = ReplyPanel(this, windowManager)
        if (SettingsManager.isEdgeDockingMode(this)) {
            // Summon at full float + full opacity; dock after settle timer if untouched
            catAnimator?.setIdleSleepEnabled(false)
            isEdgeDocked = false
            catView?.alpha = 1f
            containerView?.visibility = View.VISIBLE
            awaitingInitialDock = true
            startInitialSettleTimer()
        } else {
            catAnimator?.setIdleSleepEnabled(true)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
        musicDetector = MusicDetector(this)
        musicDetector?.start()
        screenTranslator = ScreenTranslator(this)
        android.util.Log.d(
            "ScrollCat",
            "Summon/onCreate done - containerAttached=${isViewAttached(containerView)} " +
                "alpha=${catView?.alpha} visibility=${containerView?.visibility}"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        android.util.Log.d(
            "ScrollCat",
            "Summon called - current state: action=$action isDestroyed=$isDestroyed " +
                "containerAttached=${isViewAttached(containerView)} catView=$catView " +
                "isEdgeDocked=$isEdgeDocked alpha=${catView?.alpha} startId=$startId"
        )
        if (action == ACTION_DISMISS) {
            android.util.Log.d(
                "ScrollCat",
                "Dismiss called - current state: isDestroyed=$isDestroyed " +
                    "containerAttached=${isViewAttached(containerView)} catView=$catView " +
                    "isEdgeDocked=$isEdgeDocked alpha=${catView?.alpha}"
            )
            dismissAndStop()
            return START_NOT_STICKY
        }
        // Re-summon while service still alive: re-attach cat if the view was lost
        ensureCatOnScreen()
        return START_STICKY
    }

    /** Tear down overlay and stop the service (used by Dismiss). */
    private fun dismissAndStop() {
        android.util.Log.d(
            "ScrollCat",
            "dismissAndStop - removing cat from WindowManager, stopForeground+stopSelf"
        )
        cancelDockAnimator()
        cancelDockVisibilityTimer()
        cancelInitialSettleTimer()
        replyPanel?.dismiss()
        safeRemoveView(containerView)
        containerView = null
        catView = null
        badgeView = null
        layoutParams = null
        isEdgeDocked = false
        awaitingInitialDock = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Ensures the cat overlay is attached and visible. Handles the case where
     * Summon is called while the service is still running but the view was removed.
     */
    private fun ensureCatOnScreen() {
        if (isDestroyed) return
        val attached = isViewAttached(containerView)
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
                replyPanel = ReplyPanel(this, windowManager)
            }
            if (SettingsManager.isEdgeDockingMode(this)) {
                catAnimator?.setIdleSleepEnabled(false)
                isEdgeDocked = false
                catView?.alpha = 1f
                awaitingInitialDock = true
                startInitialSettleTimer()
            } else {
                catAnimator?.setIdleSleepEnabled(true)
            }
        } else {
            // Already on screen — wake to full float visibility
            isEdgeDocked = false
            catView?.alpha = 1f
            containerView?.visibility = View.VISIBLE
            catAnimator?.play("idle")
            cancelDockVisibilityTimer()
            if (SettingsManager.isEdgeDockingMode(this)) {
                awaitingInitialDock = true
                startInitialSettleTimer()
            }
        }
        android.util.Log.d(
            "ScrollCat",
            "ensureCatOnScreen done - attached=${isViewAttached(containerView)} " +
                "alpha=${catView?.alpha} size=${layoutParams?.width}"
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

        val catSize = SettingsManager.getCatSize(this)
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

        private val handler = Handler(Looper.getMainLooper())
        private val moveModeTimeoutRunnable = Runnable {
            if (!isDragMode || moveDragStarted) return@Runnable
            isDragMode = false
            suppressTapGestures = false
            hideDragHandle()
            catAnimator?.showStatic()
            Logger.d("Move mode cancelled — timeout")
            // Resume normal idle/dock timing (single 10s window, not stacked with Move timeout)
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
            showDragHandle(p)
            cancelDockVisibilityTimer()
            cancelInitialSettleTimer()
            handler.removeCallbacks(moveModeTimeoutRunnable)
            handler.postDelayed(moveModeTimeoutRunnable, MOVE_MODE_TIMEOUT_MS)
            Logger.d("Move mode started (continuous long-press)")
        }

        private fun cancelMoveModeTimeout() {
            handler.removeCallbacks(moveModeTimeoutRunnable)
        }

        fun cleanup() {
            cancelMoveModeTimeout()
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

                    // Continuous long-press drag — same finger, no lift required
                    if (isDragMode) {
                        if (!moveDragStarted) {
                            moveDragStarted = true
                            cancelMoveModeTimeout()
                        }
                        val dx = event.rawX - pressStartTouchX
                        val dy = event.rawY - pressStartTouchY
                        params.x = homeX + dx.toInt()
                        params.y = homeY + dy.toInt()
                        safeUpdateViewLayout(containerView, params, fromTouch = true)
                        moveDragHandle(params)
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
                        hideDragHandle()
                        isDragMode = false
                        cancelMoveModeTimeout()
                        suppressTapGestures = false
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
                // Horizontal — voice assistant (left swipe, within 20° of horizontal)
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
            "Enable ScrollCat in Accessibility settings first",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun handleAwakeCatTap() {
        noteCatInteraction()
        if (badgeCount > 0) {
            val pending = ReplyStore.getAll()
            if (pending.isNotEmpty()) {
                showReplyPanel()
            } else {
                Logger.d("clearBadge called from: handleAwakeCatTap - badge tap with no pending replies")
                clearBadge()
            }
            return
        }

        if (catAnimator?.currentAnim == "music") {
            catAnimator?.stopMusic()
            return
        }

        CatAccessibilityService.instance?.performSwipe(up = true, long = isReelsMode)
            ?: showNoAccessibilityToast()
        animateTap()
    }

    /** Reset the edge-dock visibility timer on any undocked interaction. */
    private fun noteCatInteraction() {
        if (awaitingInitialDock) {
            startInitialSettleTimer()
            return
        }
        if (SettingsManager.isEdgeDockingMode(this) && !isEdgeDocked) {
            resetDockVisibilityTimer()
        }
    }

    private fun persistFloatPositionAndDockSide(x: Int, y: Int) {
        SettingsManager.setCatFloatPosition(this, x, y)
        val screenWidth = resources.displayMetrics.widthPixels
        val catSize = SettingsManager.getCatSize(this)
        val centerX = x + catSize / 2
        val side = if (centerX < screenWidth / 2) "left" else "right"
        SettingsManager.setCatDockSide(this, side)
        Logger.d("Saved float position ($x,$y) dock side=$side")
    }

    private fun dockedIconSize(): Int {
        return (SettingsManager.getCatSize(this) * 0.55f).toInt().coerceAtLeast(72)
    }

    private fun dockedEdgeX(dockSize: Int): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        return if (SettingsManager.getCatDockSide(this) == "left") {
            -dockSize / 2
        } else {
            screenWidth - dockSize / 2
        }
    }

    private fun cancelDockAnimator() {
        dockAnimator?.cancel()
        dockAnimator = null
    }

    private fun cancelDockVisibilityTimer() {
        dockHandler.removeCallbacks(dockVisibilityRunnable)
    }

    private fun cancelInitialSettleTimer() {
        dockHandler.removeCallbacks(initialSettleDockRunnable)
    }

    private fun startInitialSettleTimer() {
        cancelInitialSettleTimer()
        cancelDockVisibilityTimer()
        if (!SettingsManager.isEdgeDockingMode(this) || isEdgeDocked) return
        awaitingInitialDock = true
        dockHandler.postDelayed(initialSettleDockRunnable, INITIAL_SETTLE_DOCK_MS)
        Logger.d("Initial settle dock timer started (${INITIAL_SETTLE_DOCK_MS}ms)")
    }

    private fun resetDockVisibilityTimer() {
        cancelDockVisibilityTimer()
        cancelInitialSettleTimer()
        awaitingInitialDock = false
        if (!SettingsManager.isEdgeDockingMode(this) || isEdgeDocked) return
        dockHandler.postDelayed(dockVisibilityRunnable, DOCK_VISIBILITY_MS)
        Logger.d("Edge-dock visibility timer reset (${DOCK_VISIBILITY_MS}ms)")
    }

    fun applyCatDisplayMode() {
        if (isDestroyed) return
        if (SettingsManager.isEdgeDockingMode(this)) {
            catAnimator?.setIdleSleepEnabled(false)
            cancelInitialSettleTimer()
            awaitingInitialDock = false
            if (!isEdgeDocked) {
                dockToEdge(animate = true)
            } else {
                applyDockedOpacity()
            }
        } else {
            cancelInitialSettleTimer()
            cancelDockVisibilityTimer()
            awaitingInitialDock = false
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
        val targetX = dockedEdgeX(dockSize)
        val targetY = SettingsManager.getCatFloatY(this)
            .coerceIn(40, (resources.displayMetrics.heightPixels - dockSize - 40).coerceAtLeast(40))
        val startX = params.x
        val startY = params.y
        val startW = params.width
        val startH = params.height
        val startAlpha = catView?.alpha ?: 1f
        val targetAlpha = SettingsManager.getSleepOpacity(this)

        isEdgeDocked = true
        catAnimator?.showStatic()

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

        dockAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                if (isDestroyed) return@addUpdateListener
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

        if (!isEdgeDocked) {
            if (startVisibilityTimer && SettingsManager.isEdgeDockingMode(this)) {
                resetDockVisibilityTimer()
            }
            onComplete?.invoke()
            return
        }

        cancelDockAnimator()
        val fullSize = SettingsManager.getCatSize(this)
        val targetX = SettingsManager.getCatFloatX(this)
        val targetY = SettingsManager.getCatFloatY(this)
        val startX = params.x
        val startY = params.y
        val startW = params.width
        val startH = params.height

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
                resetDockVisibilityTimer()
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

        dockAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                if (isDestroyed) return@addUpdateListener
                val t = anim.animatedValue as Float
                params.width = (startW + (fullSize - startW) * t).toInt()
                params.height = (startH + (fullSize - startH) * t).toInt()
                params.x = (startX + (targetX - startX) * t).toInt()
                params.y = (startY + (targetY - startY) * t).toInt()
                safeUpdateViewLayout(view, params)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
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
        if (isEdgeDocked) {
            undockToFloat(animate = true, startVisibilityTimer = true)
        } else {
            resetDockVisibilityTimer()
        }
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
        val catSize = SettingsManager.getCatSize(this)

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
        val catSize = SettingsManager.getCatSize(this)

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
                setColor(0xCC1A1A1A.toInt())
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

        Logger.d("Translation bubble shown: $translated")
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
                setColor(0xE61A1A2E.toInt())
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

    fun updateCatSize(size: Int) {
        val params = layoutParams ?: return
        val view = containerView ?: return
        if (isEdgeDocked && SettingsManager.isEdgeDockingMode(this)) {
            val dockSize = (size * 0.55f).toInt().coerceAtLeast(72)
            params.width = dockSize
            params.height = dockSize
            params.x = dockedEdgeX(dockSize)
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

    fun clearBadge() {
        badgeCount = 0
        updateBadge()
    }

    fun setBadgeCount(count: Int) {
        val previous = badgeCount
        badgeCount = count.coerceAtLeast(0)
        updateBadge()
        if (badgeCount > previous) {
            onReplyableBadgeIncreased()
        }
        onReplyablesChanged()
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

    fun updateBadgeAfterReply() {
        val remaining = ReplyStore.getAll().size
        if (remaining == 0) {
            clearBadge()
        } else {
            badgeCount = remaining
            updateBadge()
        }
        Logger.d("Badge updated after reply - remaining: $remaining")
    }

    fun showReplyPanel() {
        val params = layoutParams ?: return
        val catSize = SettingsManager.getCatSize(this)
        animateTap()
        replyPanel?.show(params.x, params.y, catSize)
        Logger.d("Reply panel shown (${ReplyStore.count()} pending)")
    }

    /**
     * Seeds the scripted onboarding demo: fake message + badge on the cat.
     * Tapping the badge opens the real ReplyPanel with hardcoded demo data.
     */
    fun seedOnboardingDemo(
        onDemoPanelShown: (() -> Unit)? = null,
        onDemoReplySent: (() -> Unit)? = null
    ) {
        ReplyStore.putDemo()
        replyPanel?.resetOnboardingDemoState()
        replyPanel?.onDemoPanelShown = onDemoPanelShown
        replyPanel?.onDemoReplySent = onDemoReplySent
        setBadgeCount(ReplyStore.count().coerceAtLeast(1))
        Logger.d("Onboarding demo seeded (badge=$badgeCount)")
    }

    fun clearOnboardingDemoCallback() {
        replyPanel?.onDemoPanelShown = null
        replyPanel?.onDemoReplySent = null
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
        val catSize = SettingsManager.getCatSize(this)
        val size = catSize + 60 // slightly bigger than cat

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
            x = catParams.x - 30
            y = catParams.y - 30
        }

        safeAddView(circle, params)
        handleView = circle
        handleParams = params
    }

    private fun moveDragHandle(catParams: WindowManager.LayoutParams) {
        val view = handleView ?: return
        val params = handleParams ?: return
        params.x = catParams.x - 30
        params.y = catParams.y - 30
        safeUpdateViewLayout(view, params)
    }

    private fun hideDragHandle() {
        safeRemoveView(handleView)
        handleView = null
        handleParams = null
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
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            android.util.Log.w("ScrollCat", "Trim memory level: $level")
            catAnimator?.onLowMemory()
        }
    }

    override fun onDestroy() {
        android.util.Log.d(
            "ScrollCat",
            "Dismiss/onDestroy - containerAttached=${isViewAttached(containerView)} " +
                "catView=$catView isEdgeDocked=$isEdgeDocked"
        )
        isDestroyed = true
        cancelDockAnimator()
        cancelDockVisibilityTimer()
        cancelInitialSettleTimer()
        catTouchListener?.cleanup()
        catTouchListener = null
        hideDragHandle()
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
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) { }
        try { unregisterReceiver(screenStateReceiver) } catch (e: Exception) { }
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
