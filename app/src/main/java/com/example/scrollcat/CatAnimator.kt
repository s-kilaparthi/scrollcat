package com.example.scrollcat

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
    private val bitmapCache = mutableMapOf<Int, BitmapDrawable>()
    private val random = Random()
    private var idleTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isIdleSleeping = false
    private val IDLE_TIMEOUT_MS = 30_000L // 30 seconds

    // Show static frame — no animation loop
    fun showStatic() {
        stopAnim()
        setFrame(75)
        resetIdleTimeout()
    }

    private fun resetIdleTimeout() {
        idleTimeoutHandler.removeCallbacksAndMessages(null)
        idleTimeoutHandler.postDelayed({
            android.util.Log.d("ScrollCat", "Idle timeout fired - entering sleep")
            enterIdleSleep()
        }, IDLE_TIMEOUT_MS)
    }

    fun cancelIdleTimeout() {
        idleTimeoutHandler.removeCallbacksAndMessages(null)
        android.util.Log.d("ScrollCat", "Idle timeout cancelled - screen off")
    }

    private fun enterIdleSleep() {
        isIdleSleeping = true
        handler.removeCallbacksAndMessages(null)
        stopAnim()
        android.util.Log.d("ScrollCat", "Entering idle sleep mode")
        setFrame(68)
        val opacity = SettingsManager.getSleepOpacity(context)
        imageView.alpha = opacity
        android.util.Log.d("ScrollCat", "Sleep opacity: $opacity")
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
        android.util.Log.d("ScrollCat", "Music mode started")
    }

    fun stopMusic() {
        if (currentAnim != "music") return
        android.util.Log.d("ScrollCat", "Music mode stopped")
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
        android.util.Log.d("ScrollCat", "Started anim: $animKey")
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
        idleTimeoutHandler.removeCallbacksAndMessages(null)
    }

    fun setFrame(spriteIndex: Int) {
        try {
            val drawable = loadFrame(spriteIndex)
            imageView.setImageDrawable(drawable)
        } catch (e: Exception) {
            android.util.Log.e("ScrollCat", "Frame load error: ${e.message}")
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
                android.util.Log.d("ScrollCat", "Frame: ${anim.frames[frameIndex]}")
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
