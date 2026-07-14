package com.example.scrollcat

import android.animation.AnimatorSet
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
        const val FLING_VELOCITY_THRESHOLD = 700 // px/sec, tune on device
        const val DISTANCE_TRIGGER_THRESHOLD = 70 // px for up/down scroll
        const val HORIZONTAL_TRIGGER_THRESHOLD = 180 // px for left/right — much bigger to avoid accidental triggers
        const val LONG_PRESS_TIMEOUT_MS = 450L
        const val MOVE_CANCEL_SLOP = 60 // px of movement that cancels a pending long-press
        const val MODE_FEED = false
        const val MODE_REELS = true
        const val MOOD_INTERVAL_MS = 600_000L // 10 minutes
        const val MOOD_EXCITED = "\uD83D\uDE38"   // 😸
        const val MOOD_TIRED = "\uD83D\uDE10"     // 😐
        const val MOOD_GRUMPY = "\uD83D\uDE3E"   // 😾
        const val MOOD_NORMAL = "\uD83D\uDC31"   // 🐱
        var DISTANCE_TRIGGER_THRESHOLD_LIVE = 70
        var MOOD_INTERVAL_MS_LIVE = 600_000L
    }

    private lateinit var windowManager: WindowManager
    private var catView: ImageView? = null
    private var lottieView: ImageView? = null
    private var catAnimator: CatAnimator? = null
    private var scrollAnimPlaying = false
    private var isHiding = false
    private var currentReactionEmoji: android.widget.TextView? = null
    private var reactionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var musicDetector: MusicDetector? = null
    private var volumeControlView: android.widget.LinearLayout? = null
    private var volumeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var radialMenu: RadialMenu? = null
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
    private var originalX = 60
    private var originalY = 600
    private val batteryReceiver = BatteryReceiver()
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    pauseBackgroundWork()
                    android.util.Log.d("ScrollCat", "Screen off - background work paused")
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    resumeBackgroundWork()
                    android.util.Log.d("ScrollCat", "Screen on - background work resumed")
                }
            }
        }
    }
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isDestroyed = false
    private var isScreenOn = true
    private var batteryAnimator: android.animation.ValueAnimator? = null
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

    private fun safeUpdateViewLayout(view: View?, params: WindowManager.LayoutParams): Boolean {
        if (isDestroyed || view == null || view.parent == null) return false
        return try {
            windowManager.updateViewLayout(view, params)
            true
        } catch (e: Exception) {
            android.util.Log.w("ScrollCat", "updateViewLayout failed: ${e.message}")
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

    private fun cancelBatteryAnimation() {
        batteryAnimator?.cancel()
        batteryAnimator = null
    }

    private fun pauseBackgroundWork() {
        catAnimator?.stop()
        catAnimator?.cancelIdleTimeout()
        moodHandler.removeCallbacksAndMessages(null)
        musicDetector?.pause()
        cancelBatteryAnimation()
    }

    private fun resumeBackgroundWork() {
        if (isDestroyed) return
        catAnimator?.showStatic()
        musicDetector?.resume()
        if (isScreenOn) startMoodTracking()
    }

    private var badgeCount = 0
    private var badgeView: android.widget.TextView? = null
    private var containerView: FrameLayout? = null

    private var scrollingStartTime = 0L
    private var moodHandler = Handler(Looper.getMainLooper())
    private var currentMood = MOOD_NORMAL
    private var breakMessageShown = false

    var isReelsMode = false
        private set

    private var handleView: View? = null
    private var handleParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        addCatView()
        startMoodTracking()
        radialMenu = RadialMenu(this, windowManager)
        replyPanel = ReplyPanel(this, windowManager)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
        val batteryFilter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, batteryFilter)
        musicDetector = MusicDetector(this)
        musicDetector?.start()
        screenTranslator = ScreenTranslator(this)
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
                .setContentTitle("ScrollCat is on screen")
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
        catAnimator = CatAnimator(this, cat)

        // Preload common animations in background
        catAnimator?.preloadFrames("idle")
        catAnimator?.preloadFrames("tap")
        catAnimator?.preloadFrames("scroll")

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

        val params = WindowManager.LayoutParams(
            240,
            240,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 600
        }

        container.setOnTouchListener(CatTouchListener(params).also { catTouchListener = it })
        safeAddView(container, params)
        cat.post {
            catAnimator?.showStatic()
        }
        startIdleAnimation()
        catView = cat
        badgeView = badge
        containerView = container
        layoutParams = params
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
        private var moveModePending = false

        private val handler = Handler(Looper.getMainLooper())
        private val longPressRunnable = Runnable {
            if (layoutParams == null) return@Runnable
            val p = layoutParams ?: return@Runnable
            val catSize = SettingsManager.getCatSize(this@OverlayService)
            radialMenu?.show(p.x, p.y, catSize)
            android.util.Log.d("ScrollCat", "Radial menu shown")
        }

        fun cleanup() {
            handler.removeCallbacksAndMessages(null)
        }

        private val gestureDetector = GestureDetector(
            this@OverlayService,
            object : SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    // If cat is sleeping — wake up but DON'T clear badge or open reply panel
                    // User needs to tap again after cat wakes up to see replies
                    if (catAnimator?.isAsleep() == true) {
                        wakeFromSleep()
                        // Don't clear badge, don't open reply panel
                        // Badge stays visible so user can tap again
                        return true
                    }

                    // Cat is awake — handle badge tap normally
                    if (badgeCount > 0) {
                        // Show reply panel if there are replyable messages
                        val pending = ReplyStore.getAll()
                        if (pending.isNotEmpty()) {
                            showReplyPanel()
                        } else {
                            android.util.Log.d("ScrollCat", "clearBadge called from: onSingleTapConfirmed - badge tap with no pending replies")
                            clearBadge()
                        }
                        return true
                    }

                    // Normal tap = scroll
                    if (catAnimator?.currentAnim == "music") {
                        catAnimator?.stopMusic()
                        return true
                    }

                    CatAccessibilityService.instance?.performSwipe(up = true, long = isReelsMode)
                        ?: showNoAccessibilityToast()
                    animateTap()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    isReelsMode = !isReelsMode
                    // When toggling modes, show correct emoji for current mood state
                    // mood emoji replaced by Lottie animation
                    android.widget.Toast.makeText(
                        this@OverlayService,
                        if (isReelsMode) "Reels mode \uD83D\uDE38" else "Feed mode \uD83D\uDC31",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return true
                }
            }
        )

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (catAnimator?.isAsleep() == true || isWakingUp) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    if (catAnimator?.isAsleep() == true) {
                        android.util.Log.d("ScrollCat", "Cat sleeping - waking up, keeping badge: $badgeCount")
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

            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (moveModePending) {
                        moveModePending = false
                        isDragMode = true
                        homeX = params.x
                        homeY = params.y
                        pressStartTouchX = event.rawX
                        pressStartTouchY = event.rawY
                        android.util.Log.d("ScrollCat", "Move mode started")
                        return true
                    }
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
                    handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    // If sleeping, block all movement
                    if (catAnimator?.isAsleep() == true) return true

                    // If radial menu showing, only update highlight
                    if (radialMenu?.isShowing == true) {
                        val p = layoutParams ?: return true
                        val catSize = SettingsManager.getCatSize(this@OverlayService)
                        radialMenu?.updateHighlight(event.rawX, event.rawY, p.x, p.y, catSize)
                        return true
                    }

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
                                android.util.Log.d("ScrollCat", "Volume up")
                            } else {
                                // Drag down = volume down
                                audioManager.adjustStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    AudioManager.ADJUST_LOWER,
                                    AudioManager.FLAG_SHOW_UI
                                )
                                android.util.Log.d("ScrollCat", "Volume down")
                            }
                            lastVolumeY = event.rawY
                            resetVolumeTimeout()
                        }
                        return true
                    }

                    // If drag/move mode — move the cat
                    if (isDragMode) {
                        val dx = event.rawX - pressStartTouchX
                        val dy = event.rawY - pressStartTouchY
                        params.x = homeX + dx.toInt()
                        params.y = homeY + dy.toInt()
                        safeUpdateViewLayout(containerView, params)
                        moveDragHandle(params)
                        return true
                    }

                    // Cancel long press if finger moved too much
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
                    safeUpdateViewLayout(containerView, params)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (catAnimator?.isAsleep() == true) return true
                    handler.removeCallbacks(longPressRunnable)

                    // Handle radial menu selection
                    if (radialMenu?.isShowing == true) {
                        val action = radialMenu?.getHighlightedAction()
                        radialMenu?.dismiss()
                        android.util.Log.d("ScrollCat", "Radial action selected: $action")
                        when (action) {
                            "move" -> {
                                moveModePending = true
                                showDragHandle(params)
                                android.util.Log.d("ScrollCat", "Move mode pending")
                            }
                            "ai" -> executeRadialAction("ai")
                        }
                        return true
                    }

                    // Handle volume mode — don't let evaluatePush fire
                    if (isVolumeMode) {
                        hideVolumeControls()
                        params.x = homeX
                        params.y = homeY
                        safeUpdateViewLayout(containerView, params)
                        return true
                    }

                    // Handle drag/move mode
                    if (isDragMode) {
                        hideDragHandle()
                        isDragMode = false
                        catAnimator?.showStatic()
                        return true
                    }

                    // Normal gesture
                    evaluatePush(event)
                    params.x = homeX
                    params.y = homeY
                    safeUpdateViewLayout(containerView, params)
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
                        android.util.Log.d("ScrollCat", "Playing scroll anim, flag was: $scrollAnimPlaying")
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
                        android.util.Log.d("ScrollCat", "Playing scroll anim, flag was: $scrollAnimPlaying")
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

    private fun startMoodTracking() {
        scrollingStartTime = System.currentTimeMillis()
        breakMessageShown = false
        moodHandler.removeCallbacksAndMessages(null)
        scheduleMoodCheck()
    }

    private fun scheduleMoodCheck() {
        if (!isScreenOn || isDestroyed) return
        moodHandler.postDelayed({
            if (!isScreenOn || isDestroyed) return@postDelayed
            val elapsed = System.currentTimeMillis() - scrollingStartTime
            val minutes = elapsed / MOOD_INTERVAL_MS_LIVE

            val newMood = when {
                minutes >= 3 -> MOOD_GRUMPY
                minutes >= 2 -> MOOD_TIRED
                minutes >= 1 -> MOOD_EXCITED
                else -> MOOD_NORMAL
            }

            if (newMood != currentMood) {
                currentMood = newMood
                if (newMood == MOOD_GRUMPY) {
                    animateGrumpy()
                }
                // Update emoji regardless of mode — but respect reels toggle emoji
                // mood emoji replaced by Lottie animation

                if (newMood == MOOD_GRUMPY && !breakMessageShown) {
                    breakMessageShown = true
                    showBreakMessage()

                    // Reset after 5 seconds and start count again
                    moodHandler.postDelayed({
                        currentMood = MOOD_NORMAL
                        stopMoodAnimation()
                        breakMessageShown = false
                        scrollingStartTime = System.currentTimeMillis()
                        // mood emoji replaced by Lottie animation
                        scheduleMoodCheck()
                    }, 120_000L)
                    return@postDelayed
                }
            }

            scheduleMoodCheck()
        }, MOOD_INTERVAL_MS_LIVE)
    }

    private fun showBreakMessage() {
        val message = android.widget.Toast.makeText(
            this,
            "Hey, maybe take a break? \uD83D\uDC40",
            android.widget.Toast.LENGTH_LONG
        )
        message.show()
    }

    fun startIdleAnimation() {
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

    fun animateGrumpy() {
        catView?.let { view ->
            ObjectAnimator.ofFloat(view, "rotation", 0f, -8f, 8f, -8f, 8f, 0f).apply {
                duration = 1000
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    fun stopMoodAnimation() {
        catView?.let { view ->
            view.animate().rotation(0f).setDuration(200).start()
            startIdleAnimation()
        }
    }

    fun wakeFromSleep() {
        android.util.Log.d("ScrollCat", "wakeFromSleep called - badge count: $badgeCount")
        catAnimator?.wakeUp()
        // DO NOT clear badge here
    }

    fun onBatteryLow() {
        if (isHiding) return
        isHiding = true
        val params = layoutParams ?: return
        val view = containerView ?: return
        originalX = params.x
        originalY = params.y

        // Get screen width
        val dm = resources.displayMetrics
        val screenWidth = dm.widthPixels

        // Slide to nearest edge
        val targetX = if (params.x > screenWidth / 2) {
            screenWidth - 40 // right edge, just ears showing
        } else {
            -params.width + 40 // left edge, just ears showing
        }

        // Play walk animation while sliding
        catAnimator?.play("walk")

        // Animate sliding to edge
        val startX = params.x
        cancelBatteryAnimation()
        batteryAnimator = android.animation.ValueAnimator.ofInt(startX, targetX).apply {
            duration = 1000
            addUpdateListener { anim ->
                if (isDestroyed) return@addUpdateListener
                params.x = anim.animatedValue as Int
                view.post { safeUpdateViewLayout(view, params) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    batteryAnimator = null
                    catAnimator?.showStatic()
                    android.util.Log.d("ScrollCat", "Cat hiding at edge - battery low")
                }
            })
        }
        batteryAnimator?.start()
    }

    fun onBatteryCritical() {
        if (!isHiding) onBatteryLow()
        // Show warning toast once
        android.widget.Toast.makeText(
            this,
            "⚡ Battery critical! ScrollCat is hiding...",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    fun onBatteryCharging() {
        if (!isHiding) return
        isHiding = false
        val params = layoutParams ?: return
        val view = containerView ?: return

        // Slide back to original position
        catAnimator?.play("walk")
        val startX = params.x
        cancelBatteryAnimation()
        batteryAnimator = android.animation.ValueAnimator.ofInt(startX, originalX).apply {
            duration = 1000
            addUpdateListener { anim ->
                if (isDestroyed) return@addUpdateListener
                params.x = anim.animatedValue as Int
                view.post { safeUpdateViewLayout(view, params) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    batteryAnimator = null
                    params.y = originalY
                    view.post { safeUpdateViewLayout(view, params) }
                    catAnimator?.showStatic()
                    android.util.Log.d("ScrollCat", "Cat came back - charging")
                }
            })
        }
        batteryAnimator?.start()
    }

    fun onBatteryNormal() {
        if (!isHiding) return
        onBatteryCharging() // same behavior - come back out
    }

    fun reactToApp(packageName: String) {
        musicDetector?.updateForegroundApp(packageName)
        val config = AppReactionManager.getReaction(this, packageName) ?: return
        android.util.Log.d("ScrollCat", "App reaction: ${config.emoji} for $packageName")

        // Play animation
        when (config.animation) {
            "tap" -> catAnimator?.play("tap") { catAnimator?.showStatic() }
            "awake" -> catAnimator?.play("waking") { catAnimator?.play("awake") { catAnimator?.showStatic() } }
            "sleeping" -> catAnimator?.play("sleeping") { catAnimator?.setFrame(68) }
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
        if (catAnimator?.isAsleep() == true) {
            catView?.alpha = opacity
            android.util.Log.d("ScrollCat", "Sleep opacity updated in real time: $opacity")
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
        android.util.Log.d("ScrollCat", "Volume mode started")
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

    fun executeRadialAction(action: String) {
        when (action) {
            "ai" -> {
                android.util.Log.d("ScrollCat", "AI mode activated")
                // AI feature coming soon
                android.widget.Toast.makeText(
                    this,
                    "🤖 AI coming soon!",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            "move" -> {
                // handled directly in touch listener
            }
        }
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

        android.util.Log.d("ScrollCat", "Translation bubble shown: $translated")
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
        params.width = size
        params.height = size
        view.post { safeUpdateViewLayout(view, params) }
    }

    fun updateSensitivity(value: Int) {
        DISTANCE_TRIGGER_THRESHOLD_LIVE = value
    }

    fun updateBreakInterval(minutes: Int) {
        moodHandler.removeCallbacksAndMessages(null)
        scrollingStartTime = System.currentTimeMillis()
        MOOD_INTERVAL_MS_LIVE = minutes * 60_000L
        scheduleMoodCheck()
    }

    fun incrementBadge() {
        if (isDestroyed) return
        catView?.post { animateNotification() }
        badgeCount++
        updateBadge()
    }

    fun clearBadge() {
        badgeCount = 0
        updateBadge()
    }

    fun updateBadgeAfterReply() {
        val remaining = ReplyStore.getAll().size
        if (remaining == 0) {
            clearBadge()
        } else {
            badgeCount = remaining
            updateBadge()
        }
        android.util.Log.d("ScrollCat", "Badge updated after reply - remaining: $remaining")
    }

    fun showReplyPanel() {
        val params = layoutParams ?: return
        val catSize = SettingsManager.getCatSize(this)
        animateTap()
        replyPanel?.show(params.x, params.y, catSize)
        android.util.Log.d("ScrollCat", "Reply panel shown (${ReplyStore.count()} pending)")
    }

    private fun updateBadge() {
        badgeView?.post {
            if (badgeCount > 0) {
                badgeView?.text = if (badgeCount > 99) "99+" else badgeCount.toString()
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

    override fun onDestroy() {
        isDestroyed = true
        cancelBatteryAnimation()
        catTouchListener?.cleanup()
        catTouchListener = null
        hideDragHandle()
        radialMenu?.destroy()
        radialMenu = null
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
        moodHandler.removeCallbacksAndMessages(null)
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
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) { }
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
