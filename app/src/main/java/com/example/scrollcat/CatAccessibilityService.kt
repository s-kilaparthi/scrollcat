package com.example.scrollcat

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Injects real swipe gestures into the app underneath the cat.
 * The user must enable this manually in Settings > Accessibility.
 */
class CatAccessibilityService : AccessibilityService() {

    companion object {
        var instance: CatAccessibilityService? = null
            private set
        private const val TAG = "ScrollCat"
        private const val SWIPE_DURATION_MS = 350L
        private const val SWIPE_DURATION_LONG_MS = 80L
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isGestureInProgress = false
    private var lastTranslatedText = ""
    private val translationCooldownMs = 5000L
    private var lastTranslationTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    fun performSwipe(up: Boolean, long: Boolean = false) {
        // Don't start a new gesture if one is already running
        if (isGestureInProgress) return

        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.5f
        val startY: Float
        val endY: Float
        val duration: Long

        if (long) {
            startY = if (up) dm.heightPixels * 0.70f else dm.heightPixels * 0.30f
            endY   = if (up) dm.heightPixels * 0.30f else dm.heightPixels * 0.70f
            duration = SWIPE_DURATION_LONG_MS
        } else {
            startY = if (up) dm.heightPixels * 0.70f else dm.heightPixels * 0.30f
            endY   = if (up) dm.heightPixels * 0.30f else dm.heightPixels * 0.70f
            duration = SWIPE_DURATION_MS
        }

        val path = android.graphics.Path().apply {
            moveTo(x, startY)
            val mid1Y = startY + (endY - startY) * 0.15f
            val mid2Y = startY + (endY - startY) * 0.85f
            cubicTo(x, mid1Y, x, mid2Y, x, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        isGestureInProgress = true
        OverlayService.instance?.setTouchable(false)

        handler.postDelayed({
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    handler.postDelayed({
                        isGestureInProgress = false
                        OverlayService.instance?.setTouchable(true)
                    }, 50)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesture cancelled")
                    handler.postDelayed({
                        isGestureInProgress = false
                        OverlayService.instance?.setTouchable(true)
                    }, 50)
                }
            }, null)

            if (!dispatched) {
                Log.e(TAG, "dispatchGesture returned false")
                isGestureInProgress = false
                OverlayService.instance?.setTouchable(true)
            }
        }, 32) // small delay to let overlay become non-touchable before gesture fires
    }

    fun performBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performRecentApps() {
        val behavior = SettingsManager.getLeftSwipeBehavior(this)
        android.util.Log.d("ScrollCat", "Left swipe: $behavior")
        when (behavior) {
            "screenshot" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            else -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: return
                // Ignore system UI and our own app
                if (packageName == "com.example.scrollcat") return
                if (packageName == "com.android.systemui") return
                if (packageName == "android") return

                android.util.Log.d("ScrollCat", "App opened: $packageName")
                OverlayService.instance?.reactToApp(packageName)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastTranslationTime < translationCooldownMs) return

                // Get text from event
                val rawText = event.text?.joinToString(" ")?.trim() ?: return

                // Split into sentences and take the longest meaningful one
                val sentences = rawText.split(".", "!", "?", "\n")
                    .map { it.trim() }
                    .filter { it.length > 15 }
                    .sortedByDescending { it.length }

                val text = sentences.firstOrNull() ?: return
                if (text.length < 10) return
                if (text == lastTranslatedText) return

                lastTranslatedText = text
                lastTranslationTime = now

                val overlay = OverlayService.instance ?: return
                overlay.screenTranslator?.detectAndTranslate(text) { original, translated, language ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        overlay.showTranslationBubble(original, translated, language)
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        instance = null
        super.onDestroy()
    }
}
