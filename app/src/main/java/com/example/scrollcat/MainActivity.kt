package com.example.scrollcat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Minimal onboarding: grant overlay permission, enable the accessibility
 * service, then summon the cat.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // First launch → run onboarding instead
        if (!SettingsManager.isOnboardingComplete(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        status = TextView(this).apply { textSize = 16f }

        val btnOverlay = Button(this).apply {
            text = "1. Grant overlay permission"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        val btnAccessibility = Button(this).apply {
            text = "2. Enable accessibility service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnStart = Button(this).apply {
            text = "3. Summon the cat \uD83D\uDC31"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    status.text = "Overlay permission missing — do step 1 first."
                } else {
                    startForegroundService(
                        Intent(this@MainActivity, OverlayService::class.java)
                    )
                    status.text = "Cat is out! Open Instagram and flick it."
                }
            }
        }

        val btnStop = Button(this).apply {
            text = "Dismiss the cat"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                status.text = "Cat dismissed."
            }
        }

        val btnNotifSettings = Button(this).apply {
            text = "Notification settings \uD83D\uDD14"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, NotificationSettingsActivity::class.java))
            }
        }

        val btnNotifListener = Button(this).apply {
            text = "4. Enable notification access"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        layout.addView(btnOverlay)
        layout.addView(btnAccessibility)
        layout.addView(btnStart)
        layout.addView(btnStop)
        layout.addView(btnNotifSettings)
        layout.addView(btnNotifListener)
        val btnAutoReply = Button(this).apply {
            text = "📋 Auto-Reply Rules"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AutoReplyActivity::class.java))
            }
        }
        layout.addView(btnAutoReply)
        val btnSettings = Button(this).apply {
            text = "⚙️ Settings"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        layout.addView(btnSettings)
        layout.addView(status)
        setContentView(layout)
        ViewCompat.setOnApplyWindowInsetsListener(layout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(48 + bars.left, 96 + bars.top, 48 + bars.right, 48 + bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        val overlayOk = Settings.canDrawOverlays(this)
        val a11yOk = CatAccessibilityService.instance != null
        val notifOk = CatNotificationListener.instance != null
        status.text = buildString {
            append(if (overlayOk) "Overlay: granted\n" else "Overlay: NOT granted\n")
            append(if (a11yOk) "Accessibility: enabled\n" else "Accessibility: NOT enabled\n")
            append(if (notifOk) "Notification access: enabled" else "Notification access: NOT enabled")
        }
    }
}
