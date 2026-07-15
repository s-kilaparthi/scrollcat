package com.example.scrollcat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Minimal onboarding: grant overlay permission, enable the accessibility
 * service, then summon the cat.
 */
class MainActivity : Activity() {

    private lateinit var overlayBadge: TextView
    private lateinit var accessibilityBadge: TextView
    private lateinit var notificationBadge: TextView
    private var btnUpgrade: MaterialButton? = null
    private var btnShare: MaterialButton? = null

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

        val root = UiKit.pageRoot(this)
        root.addView(UiKit.headline(this, "ScrollCat Dashboard"))
        root.addView(UiKit.body(this, "Set up permissions, summon the cat, and manage your smart replies.", muted = true))

        dashboardSection(root, "Cat Controls") {
            UiKit.addButton(this, UiKit.primaryButton(this@MainActivity, "Summon the Cat \uD83D\uDC31") {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
                } else {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            })
            UiKit.addButton(this, UiKit.tonalButton(this@MainActivity, "Dismiss the Cat") {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
            })
        }

        dashboardSection(root, "Setup Steps") {
            val overlayButton = setupButton("Grant Overlay Permission") {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            overlayBadge = overlayButton.second
            addView(overlayButton.first)

            val accessibilityButton = setupButton("Enable Accessibility Service") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            accessibilityBadge = accessibilityButton.second
            addView(accessibilityButton.first)

            val notificationButton = setupButton("Enable Notification Access") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            notificationBadge = notificationButton.second
            addView(notificationButton.first)
        }

        dashboardSection(root, "Smart Replies") {
            UiKit.addButton(this, UiKit.tonalButton(this@MainActivity, "\uD83D\uDCCB Auto-Reply Rules") {
                startActivity(Intent(this@MainActivity, AutoReplyActivity::class.java))
            })
            UiKit.addButton(this, UiKit.tonalButton(this@MainActivity, "My AI Settings") {
                startActivity(Intent(this@MainActivity, AiSettingsActivity::class.java))
            })
            UiKit.addButton(this, UiKit.tonalButton(this@MainActivity, "Smart Notifications") {
                startActivity(Intent(this@MainActivity, NotificationSettingsActivity::class.java))
            })
            UiKit.addButton(this, UiKit.tonalButton(this@MainActivity, "\u2699\uFE0F Settings") {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            })
        }

        dashboardSection(root, "Plan & Sharing") {
            btnUpgrade = UiKit.tonalButton(this@MainActivity, "\u2B50 Upgrade to Pro") {
                startActivity(Intent(this@MainActivity, SubscriptionActivity::class.java))
            }
            UiKit.addButton(this, btnUpgrade!!)
            btnShare = UiKit.tonalButton(this@MainActivity, "\uD83D\uDCE4 Share my stats") {
                startActivity(Intent(this@MainActivity, ShareCardActivity::class.java))
            }
            UiKit.addButton(this, btnShare!!)
        }

        // Footer: privacy policy link + version
        root.addView(TextView(this).apply {
            text = "Privacy Policy"
            textSize = 13f
            setTextColor(UiKit.primaryColor(this@MainActivity))
            setPadding(0, UiKit.dp(this@MainActivity, 12), 0, UiKit.dp(this@MainActivity, 4))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, PrivacyPolicyActivity::class.java))
            }
        })
        root.addView(TextView(this).apply {
            text = "ScrollCat v1.0"
            textSize = 12f
            setTextColor(UiKit.mutedColor(this@MainActivity))
        })

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(UiKit.surfaceColor(this@MainActivity))
            addView(root)
        }
        setContentView(scrollView)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                UiKit.dp(this, 24) + bars.left,
                UiKit.dp(this, 32) + bars.top,
                UiKit.dp(this, 24) + bars.right,
                UiKit.dp(this, 24) + bars.bottom
            )
            insets
        }
    }

    private fun dashboardSection(
        root: LinearLayout,
        title: String,
        build: LinearLayout.() -> Unit
    ) {
        root.addView(UiKit.label(this, title))
        val card = MaterialCardView(this).apply {
            radius = UiKit.dp(this@MainActivity, 16).toFloat()
            cardElevation = UiKit.dp(this@MainActivity, 2).toFloat()
            strokeWidth = 1
            strokeColor = 0x338C7A68
            setCardBackgroundColor(UiKit.surfaceColor(this@MainActivity))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(this@MainActivity, 20),
                UiKit.dp(this@MainActivity, 16),
                UiKit.dp(this@MainActivity, 20),
                UiKit.dp(this@MainActivity, 16)
            )
        }
        content.build()
        card.addView(content)
        root.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, UiKit.dp(this@MainActivity, 12)) })
    }

    private fun setupButton(label: String, onClick: () -> Unit): Pair<FrameLayout, TextView> {
        val frame = FrameLayout(this)
        val button = UiKit.primaryButton(this, label, onClick).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(
                UiKit.dp(this@MainActivity, 18),
                paddingTop,
                UiKit.dp(this@MainActivity, 112),
                paddingBottom
            )
        }
        frame.addView(button, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        val badge = TextView(this).apply {
            text = "Enabled"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(
                UiKit.dp(this@MainActivity, 10),
                UiKit.dp(this@MainActivity, 4),
                UiKit.dp(this@MainActivity, 10),
                UiKit.dp(this@MainActivity, 4)
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF15803D.toInt())
                cornerRadius = UiKit.dp(this@MainActivity, 16).toFloat()
            }
            visibility = View.GONE
        }
        frame.addView(badge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL
        ).apply { marginEnd = UiKit.dp(this@MainActivity, 14) })
        frame.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, UiKit.dp(this@MainActivity, 6), 0, UiKit.dp(this@MainActivity, 6)) }
        return frame to badge
    }

    override fun onResume() {
        super.onResume()
        if (!::overlayBadge.isInitialized) return

        val overlayOk = Settings.canDrawOverlays(this)
        val a11yOk = CatAccessibilityService.instance != null
        val notifOk = CatNotificationListener.instance != null
        val isPro = BillingManager.getInstance(this).isPro()
        overlayBadge.visibility = if (overlayOk) View.VISIBLE else View.GONE
        accessibilityBadge.visibility = if (a11yOk) View.VISIBLE else View.GONE
        notificationBadge.visibility = if (notifOk) View.VISIBLE else View.GONE
        btnUpgrade?.visibility = if (isPro) android.view.View.GONE else android.view.View.VISIBLE
        btnShare?.visibility =
            if (StatsTracker.getTotalRepliesSent(this) >= 5) android.view.View.VISIBLE
            else android.view.View.GONE
        RateUsManager.maybeShowRatePrompt(this)
    }
}
