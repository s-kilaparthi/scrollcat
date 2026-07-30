package com.skilaparthi.scrollcat

import android.app.ActivityManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.util.Random

class CatAnimator(
    private val context: Context,
    private val imageView: ImageView
) {
    companion object {
        const val TICK_MS = 16L
        const val IDLE_INTERVAL_MS = 6000L // play idle animation every 6 seconds

        fun isLowEndDevice(context: Context): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return activityManager.isLowRamDevice
        }
    }

    private val animations = mapOf(
        "idle" to AnimDef(frames = (75..117).toList(), loop = false, ticksPerFrame = 6),
        "sleeping" to AnimDef(frames = (68..75).reversed().toList(), loop = false, ticksPerFrame = 8),
        "waking" to AnimDef(frames = (58..67).reversed().toList(), loop = false, ticksPerFrame = 6),
        "awake" to AnimDef(frames = (72..100).toList(), loop = false, ticksPerFrame = 6),
        "tap" to AnimDef(frames = (58..76).toList(), loop = false, ticksPerFrame = 6),
        "scroll" to AnimDef(frames = (48..52).toList(), loop = false, ticksPerFrame = 6),
        "walk" to AnimDef(frames = (1..20).toList(), loop = true, ticksPerFrame = 4),
        "music" to AnimDef(frames = (58..76).toList(), loop = true, ticksPerFrame = 12)
    )

    data class AnimDef(
        val frames: List<Int>,
        val loop: Boolean,
        val ticksPerFrame: Int,
        val flipX: Boolean = false
    )

    private val handler = Handler(Looper.getMainLooper())
    var currentAnim: String? = null
        private set
    private var frameIndex = 0
    private var tickCount = 0
    private var isRunning = false
    private var onAnimComplete: (() -> Unit)? = null
    private val MAX_CACHE_SIZE = 30 // max frames in memory at once
    private val bitmapCache = object : LinkedHashMap<Int, BitmapDrawable>(
        MAX_CACHE_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, BitmapDrawable>
        ): Boolean {
            if (size > MAX_CACHE_SIZE) {
                Logger.d("Evicting frame ${eldest.key} from cache")
                return true // Let GC handle bitmap cleanup - don't manually recycle
            }
            return false
        }
    }
    private val random = Random()
    private var idleTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isIdleSleeping = false
    private var idleSleepEnabled = true
    private val IDLE_TIMEOUT_MS = 10_000L // 10 seconds

    // Show static frame — no animation loop
    fun showStatic() {
        stopAnim()
        setFrame(75)
        resetIdleTimeout()
    }

    fun showFrame(frameIndex: Int) {
        stopAnim()
        setFrame(frameIndex)
    }

    /** When false (edge-docking mode), cat never fades into sleep opacity. */
    fun setIdleSleepEnabled(enabled: Boolean) {
        idleSleepEnabled = enabled
        if (!enabled) {
            cancelIdleTimeout()
            if (isIdleSleeping) {
                isIdleSleeping = false
                imageView.animate().cancel()
                imageView.alpha = 1.0f
                setFrame(75)
            }
        } else {
            resetIdleTimeout()
        }
    }

    private fun resetIdleTimeout() {
        idleTimeoutHandler.removeCallbacksAndMessages(null)
        if (!idleSleepEnabled) return
        idleTimeoutHandler.postDelayed({
            Logger.d("Idle timeout fired - entering sleep")
            enterIdleSleep()
        }, IDLE_TIMEOUT_MS)
    }

    fun cancelIdleTimeout() {
        idleTimeoutHandler.removeCallbacksAndMessages(null)
        Logger.d("Idle timeout cancelled - screen off")
    }

    private fun enterIdleSleep() {
        isIdleSleeping = true
        handler.removeCallbacksAndMessages(null)
        stopAnim()
        Logger.d("Entering idle sleep mode")
        setFrame(68)
        val opacity = SettingsManager.getSleepOpacity(context)
        imageView.alpha = opacity
        Logger.d("Sleep opacity: $opacity")
    }

    fun wakeUp() {
        if (!isIdleSleeping) return
        isIdleSleeping = false
        idleTimeoutHandler.removeCallbacksAndMessages(null)
        stopAnim()
        imageView.animate().cancel()
        imageView.alpha = 1.0f
        play("waking") {
            play("awake") {
                setFrame(75)
                resetIdleTimeout()
            }
        }
    }

    fun playMusic() {
        if (currentAnim == "music") return // already playing
        idleTimeoutHandler.removeCallbacksAndMessages(null)
        stopAnim()
        currentAnim = "music"
        frameIndex = 0
        tickCount = 0
        isRunning = true
        setFrame(animations["music"]!!.frames[0])
        handler.postDelayed(tickRunnable, TICK_MS)
        Logger.d("Music mode started")
    }

    fun stopMusic() {
        if (currentAnim != "music") return
        Logger.d("Music mode stopped")
        showStatic()
    }

    fun isAsleep(): Boolean = isIdleSleeping

    fun play(animKey: String, onComplete: (() -> Unit)? = null) {
        val anim = animations[animKey] ?: return
        stopAnim()
        currentAnim = animKey
        frameIndex = 0
        tickCount = 0
        onAnimComplete = onComplete
        isRunning = true

        // Show first frame immediately
        setFrame(anim.frames[0])
        Logger.d("Started anim: $animKey")
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    private fun stopAnim() {
        isRunning = false
        handler.removeCallbacks(tickRunnable)
        currentAnim = null
    }

    fun stop() {
        stopAnim()
    }

    fun stopAll() {
        stop()
        handler.removeCallbacksAndMessages(null)
        idleTimeoutHandler.removeCallbacksAndMessages(null)
        clearBitmapCache()
    }

    fun onLowMemory() {
        android.util.Log.w("ScrollCat", "Low memory - clearing animation cache")
        stopAnim()
        bitmapCache.clear() // Let GC recycle bitmaps - don't manually recycle
        Logger.d("Animation cache cleared")
    }

    private fun clearBitmapCache() {
        stopAnim()
        bitmapCache.clear()
    }

    private fun setFrame(spriteIndex: Int) {
        try {
            val drawable = loadFrame(spriteIndex)
            // Safety check - don't draw recycled bitmap
            if (drawable.bitmap?.isRecycled == true) {
                android.util.Log.w("ScrollCat", "Bitmap recycled, reloading frame $spriteIndex")
                bitmapCache.remove(spriteIndex)
                val fresh = loadFrame(spriteIndex)
                imageView.setImageDrawable(fresh)
            } else {
                imageView.setImageDrawable(drawable)
            }
        } catch (e: Exception) {
            Logger.e("Frame load error: ${e.message}")
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val anim = animations[currentAnim ?: return] ?: return
            tickCount++

            if (tickCount >= anim.ticksPerFrame) {
                tickCount = 0
                frameIndex++

                if (frameIndex >= anim.frames.size) {
                    if (anim.loop) {
                        frameIndex = 0
                    } else {
                        isRunning = false
                        val callback = onAnimComplete
                        onAnimComplete = null
                        callback?.invoke()
                        return
                    }
                }

                setFrame(anim.frames[frameIndex])
                Logger.d("Frame: ${anim.frames[frameIndex]}")
            }

            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun loadFrame(spriteIndex: Int): BitmapDrawable {
        return bitmapCache.getOrPut(spriteIndex) {
            val filename = "sprites/%04d.webp".format(spriteIndex)
            val stream = context.assets.open(filename)
            val bitmap = BitmapFactory.decodeStream(stream)
            stream.close()
            BitmapDrawable(context.resources, bitmap)
        }
    }

    fun preloadFrames(animKey: String) {
        val anim = animations[animKey] ?: return
        Thread {
            anim.frames.forEach { loadFrame(it) }
        }.start()
    }
}
