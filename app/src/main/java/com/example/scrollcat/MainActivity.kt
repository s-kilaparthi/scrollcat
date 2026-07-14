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
    private lateinit var usageText: TextView
    private var btnUpgrade: Button? = null
    private var btnShare: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // First launch → run onboarding instead
        if (!SettingsManager.isOnboardingComplete(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        CrashReportingHelper.showCrashDialogIfNeeded(this)
        RateUsManager.recordAppOpen(this)
        // Refresh subscription entitlements from Play on launch
        BillingManager.getInstance(this).startConnection()

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

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

        // Pro upgrade — hidden once subscribed (visibility updated in onResume)
        btnUpgrade = Button(this).apply {
            text = "⭐ Upgrade to Pro"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SubscriptionActivity::class.java))
            }
        }
        layout.addView(btnUpgrade)

        // Share stats — appears once the cat has sent at least 5 replies
        btnShare = Button(this).apply {
            text = "📤 Share my stats"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ShareCardActivity::class.java))
            }
        }
        layout.addView(btnShare)

        status = TextView(this).apply { textSize = 16f }
        layout.addView(status)

        usageText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            setPadding(48, 0, 48, 8)
        }
        layout.addView(usageText)
        updateUsage()

        // Footer: privacy policy link + version
        layout.addView(TextView(this).apply {
            text = "Privacy Policy"
            textSize = 13f
            setTextColor(0xFF4A90D9.toInt())
            setPadding(0, 32, 0, 4)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, PrivacyPolicyActivity::class.java))
            }
        })
        layout.addView(TextView(this).apply {
            text = "ScrollCat v1.0"
            textSize = 12f
            setTextColor(0xFF999999.toInt())
        })

        setContentView(layout)
        ViewCompat.setOnApplyWindowInsetsListener(layout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(48 + bars.left, 96 + bars.top, 48 + bars.right, 48 + bars.bottom)
            insets
        }
    }

    private fun updateUsage() {
        val usage = AiReplyGenerator.getDailyUsage(this)
        val limit = AiReplyGenerator.getDailyLimit(this)
        val hasOwnKey = SettingsManager.getActiveAiKey(this).isNotEmpty()

        usageText.text = when {
            hasOwnKey -> "AI: Using your own API key (unlimited)"
            limit == Int.MAX_VALUE -> "AI: Unlimited (Business plan)"
            else -> "AI replies today: $usage/$limit"
        }
    }

    override fun onResume() {
        super.onResume()
        // status is only initialized when onboarding is complete
        if (!::status.isInitialized) return

        val overlayOk = Settings.canDrawOverlays(this)
        val a11yOk = CatAccessibilityService.instance != null
        val notifOk = CatNotificationListener.instance != null
        val isPro = BillingManager.getInstance(this).isPro()
        status.text = buildString {
            append(if (overlayOk) "Overlay: granted\n" else "Overlay: NOT granted\n")
            append(if (a11yOk) "Accessibility: enabled\n" else "Accessibility: NOT enabled\n")
            append(if (notifOk) "Notification access: enabled\n" else "Notification access: NOT enabled\n")
            append(if (isPro) "Plan: Pro ⭐" else "Plan: Free (10 AI replies/day)")
        }
        btnUpgrade?.visibility = if (isPro) android.view.View.GONE else android.view.View.VISIBLE
        btnShare?.visibility =
            if (StatsTracker.getTotalRepliesSent(this) >= 5) android.view.View.VISIBLE
            else android.view.View.GONE

        if (::usageText.isInitialized) updateUsage()

        RateUsManager.maybeShowRatePrompt(this)
    }
}
