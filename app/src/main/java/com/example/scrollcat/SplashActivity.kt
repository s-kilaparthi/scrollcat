package com.example.scrollcat

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Launcher activity: shows the cat for 2 seconds, then routes to
 * onboarding (first run) or the main screen.
 */
class SplashActivity : Activity() {

    companion object {
        private const val SPLASH_DURATION_MS = 2000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val advance = Runnable {
        val next = if (SettingsManager.isOnboardingComplete(this)) {
            MainActivity::class.java
        } else {
            OnboardingActivity::class.java
        }
        startActivity(Intent(this, next))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Painted by the window before the first layout pass, so the splash
        // appears instantly on cold start
        window.setBackgroundDrawableResource(R.drawable.splash_screen)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF1A1A1E.toInt())
        }

        root.addView(TextView(this).apply {
            text = "🐱"
            textSize = 82f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "ScrollCat"
            textSize = 26f
            typeface = UiKit.headingTypeface(this@SplashActivity)
            setTextColor(0xFFF5F3F7.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        })

        root.addView(TextView(this).apply {
            text = "Your AI companion that never sleeps"
            textSize = 13f
            setTextColor(0xFFA39BB0.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        })

        setContentView(root)
        handler.postDelayed(advance, SPLASH_DURATION_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(advance)
        super.onDestroy()
    }
}
