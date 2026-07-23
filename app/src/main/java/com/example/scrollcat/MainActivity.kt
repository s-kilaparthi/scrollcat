package com.example.scrollcat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
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

    private var btnUpgrade: MaterialButton? = null
    private var btnShare: MaterialButton? = null
    private var aiKeyBanner: MaterialCardView? = null
    private var dashboardCatAnimator: CatAnimator? = null

    companion object {
        private const val DASHBOARD_CAT_SIZE_DP = 148
        private const val DASHBOARD_REST_FRAME = 104
    }

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

        aiKeyBanner = MaterialCardView(this).apply {
            radius = UiKit.dp(this@MainActivity, 14).toFloat()
            cardElevation = UiKit.dp(this@MainActivity, 2).toFloat()
            strokeWidth = 1
            strokeColor = 0xFFB39DDB.toInt()
            setCardBackgroundColor(0xFF2E2A3A.toInt())
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AiProviderActivity::class.java))
            }
            addView(TextView(this@MainActivity).apply {
                text = "Add your free AI key to unlock Smart Replies"
                textSize = 14f
                setTextColor(0xFFF5F3F7.toInt())
                setPadding(
                    UiKit.dp(this@MainActivity, 18),
                    UiKit.dp(this@MainActivity, 16),
                    UiKit.dp(this@MainActivity, 18),
                    UiKit.dp(this@MainActivity, 16)
                )
            })
        }
        root.addView(aiKeyBanner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, UiKit.dp(this@MainActivity, 16)) })
        refreshAiKeyBanner()

        root.addView(dashboardCatIllustration())

        val catControlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, UiKit.dp(this@MainActivity, 4), 0, UiKit.dp(this@MainActivity, 12))
        }
        catControlsRow.addView(
            UiKit.primaryButton(this, "Summon the Cat") {
                val running = OverlayService.instance != null
                android.util.Log.d(
                    "ScrollCat",
                    "Summon called - current state: overlayOk=${Settings.canDrawOverlays(this@MainActivity)} " +
                        "serviceInstance=$running containerAttached=${OverlayService.instance != null}"
                )
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startForegroundService(
                        Intent(this@MainActivity, OverlayService::class.java)
                            .setAction(OverlayService.ACTION_SUMMON)
                    )
                } else {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }.apply {
                minimumHeight = UiKit.dp(this@MainActivity, 52)
                insetTop = UiKit.dp(this@MainActivity, 6)
                insetBottom = UiKit.dp(this@MainActivity, 6)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = UiKit.dp(this@MainActivity, 6)
            }
        )
        catControlsRow.addView(
            UiKit.tonalButton(this, "Dismiss the Cat") {
                android.util.Log.d(
                    "ScrollCat",
                    "Dismiss called - current state: serviceInstance=${OverlayService.instance != null}"
                )
                if (OverlayService.instance != null) {
                    startService(
                        Intent(this@MainActivity, OverlayService::class.java)
                            .setAction(OverlayService.ACTION_DISMISS)
                    )
                } else {
                    stopService(Intent(this@MainActivity, OverlayService::class.java))
                }
            }.apply {
                minimumHeight = UiKit.dp(this@MainActivity, 52)
                insetTop = UiKit.dp(this@MainActivity, 6)
                insetBottom = UiKit.dp(this@MainActivity, 6)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = UiKit.dp(this@MainActivity, 6)
            }
        )
        root.addView(catControlsRow)

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
            clipToPadding = false
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
        root.addView(UiKit.sectionTitle(this, title))
        val card = MaterialCardView(this).apply {
            radius = UiKit.dp(this@MainActivity, 16).toFloat()
            cardElevation = UiKit.dp(this@MainActivity, 2).toFloat()
            strokeWidth = 1
            strokeColor = 0x556B6578
            setCardBackgroundColor(0xFF25252C.toInt())
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

    private fun dashboardCatIllustration(): LinearLayout {
        val size = UiKit.dp(this, DASHBOARD_CAT_SIZE_DP)
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, UiKit.dp(this@MainActivity, 4), 0, UiKit.dp(this@MainActivity, 8))
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "Cat Picture"
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener { playDashboardTapAnimation() }
        }
        dashboardCatAnimator = CatAnimator(this, image).apply {
            showFrame(DASHBOARD_REST_FRAME)
        }
        wrapper.addView(image)
        return wrapper
    }

    private fun playDashboardTapAnimation() {
        val animator = dashboardCatAnimator ?: return
        if (animator.currentAnim != null) return
        animator.play("tap") {
            animator.showFrame(DASHBOARD_REST_FRAME)
        }
    }

    private fun refreshAiKeyBanner() {
        val banner = aiKeyBanner ?: return
        val hasKey = SettingsManager.getActiveAiKey(this).isNotBlank()
        banner.visibility = if (hasKey) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        dashboardCatAnimator?.showFrame(DASHBOARD_REST_FRAME)

        val isPro = BillingManager.getInstance(this).isPro()
        btnUpgrade?.visibility = if (isPro) android.view.View.GONE else android.view.View.VISIBLE
        btnShare?.visibility =
            if (StatsTracker.getTotalRepliesSent(this) >= 5) android.view.View.VISIBLE
            else android.view.View.GONE
        refreshAiKeyBanner()
        RateUsManager.maybeShowRatePrompt(this)
    }

    override fun onPause() {
        dashboardCatAnimator?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        dashboardCatAnimator?.stopAll()
        dashboardCatAnimator = null
        super.onDestroy()
    }
}
