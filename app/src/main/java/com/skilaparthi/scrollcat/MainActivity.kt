package com.skilaparthi.scrollcat

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Minimal onboarding: grant overlay permission, enable the accessibility
 * service, then summon the cat.
 */
class MainActivity : Activity() {

    private var aiKeyBanner: MaterialCardView? = null
    private var accessibilityBanner: MaterialCardView? = null
    private var accessibilityRepairBanner: MaterialCardView? = null
    /** In-memory only — ✕ hides for this dashboard visit; resets on next app open. */
    private var accessibilityBannerDismissedThisSession = false
    private var accessibilityRepairBannerDismissedThisSession = false
    private var dashboardCatAnimator: CatAnimator? = null
    private var repliesTodayCountView: TextView? = null
    private var autoRepliesTodayCountView: TextView? = null

    companion object {
        private const val DASHBOARD_CAT_SIZE_DP = 148
        private const val DASHBOARD_REST_FRAME = 104
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Diagnostic: log Telugu-related ICU Transliterator IDs available on this device
        SleepManager.logTeluguTransliterators()

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
                val hasGroqKey = ApiKeyStore.hasGroqApiKey(this@MainActivity)
                val hasModelFile = ModelDownloadManager.modelFileExists(this@MainActivity)
                val showing = AiSetupActivity.resolveBannerDestination(this@MainActivity)
                android.util.Log.d(
                    "ScrollCat",
                    "Add AI banner tapped - groq configured: $hasGroqKey, " +
                        "on-device downloaded: $hasModelFile - showing: $showing"
                )
                startActivity(Intent(this@MainActivity, AiSetupActivity::class.java))
            }
            addView(TextView(this@MainActivity).apply {
                text = "Add your AI to unlock Smart Replies"
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

        accessibilityBanner = buildAccessibilityBanner()
        root.addView(accessibilityBanner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, UiKit.dp(this@MainActivity, 16)) })
        accessibilityRepairBanner = buildAccessibilityRepairBanner()
        root.addView(accessibilityRepairBanner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, UiKit.dp(this@MainActivity, 16)) })
        refreshAccessibilityBanner()

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
            UiKit.addButton(this, dashboardIconButton("Auto-Reply Rules", R.drawable.ic_clipboard_list) {
                startActivity(Intent(this@MainActivity, AutoReplyActivity::class.java))
            })
            UiKit.addButton(this, dashboardIconButton("Smart Voice", R.drawable.ic_mic) {
                startActivity(Intent(this@MainActivity, SmartVoiceActivity::class.java))
            })
            UiKit.addButton(this, dashboardIconButton("My AI Settings", R.drawable.ic_auto_awesome) {
                startActivity(Intent(this@MainActivity, AiSettingsActivity::class.java))
            })
            UiKit.addButton(this, dashboardIconButton("Smart Notifications", R.drawable.ic_notifications) {
                startActivity(Intent(this@MainActivity, NotificationSettingsActivity::class.java))
            })
            UiKit.addButton(this, dashboardIconButton("Settings", R.drawable.ic_settings) {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            })
        }

        dashboardSection(root, "Smart Tracker") {
            addTodayStatsRow(this)
        }

        root.addView(TextView(this).apply {
            text = "Enjoying ScrollCat? We'd love your feedback \uD83D\uDC3E"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFF5F3F7.toInt())
            setPadding(
                0,
                UiKit.dp(this@MainActivity, 8),
                0,
                UiKit.dp(this@MainActivity, 4)
            )
        })
        UiKit.addButton(
            root,
            UiKit.tonalButton(this, "Give Feedback") {
                startActivity(Intent(this@MainActivity, FeedbackActivity::class.java))
            }
        )
        UiKit.addButton(
            root,
            UiKit.tonalButton(this, "Privacy & Security") {
                startActivity(Intent(this@MainActivity, PrivacySecurityActivity::class.java))
            }.apply {
                icon = ContextCompat.getDrawable(
                    this@MainActivity,
                    R.drawable.ic_shield
                )
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = UiKit.dp(this@MainActivity, 10)
                iconSize = UiKit.dp(this@MainActivity, 20)
                iconTint = ColorStateList.valueOf(
                    UiKit.primaryColor(this@MainActivity)
                )
            }
        )

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

    private fun dashboardIconButton(
        label: String,
        iconRes: Int,
        onClick: () -> Unit
    ): MaterialButton {
        return UiKit.tonalButton(this, label, onClick).apply {
            icon = ContextCompat.getDrawable(this@MainActivity, iconRes)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = UiKit.dp(this@MainActivity, 10)
            iconSize = UiKit.dp(this@MainActivity, 20)
            iconTint = ColorStateList.valueOf(UiKit.primaryColor(this@MainActivity))
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

    private fun addTodayStatsRow(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, UiKit.dp(this@MainActivity, 8), 0, UiKit.dp(this@MainActivity, 4))
            }
        }

        val repliesBox = buildTodayStatBox(
            topLabel = "AI cat replied to",
            bottomLabel = "messages today",
            onClick = {
                startActivity(Intent(this@MainActivity, ShareCardActivity::class.java))
            }
        )
        repliesTodayCountView = repliesBox.second
        row.addView(
            repliesBox.first,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = UiKit.dp(this@MainActivity, 6)
            }
        )

        val autoBox = buildTodayStatBox(
            topLabel = "AI cat auto-replied",
            bottomLabel = "messages today",
            onClick = {
                startActivity(Intent(this@MainActivity, AutoReplyTrackerActivity::class.java))
            }
        )
        autoRepliesTodayCountView = autoBox.second
        row.addView(
            autoBox.first,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = UiKit.dp(this@MainActivity, 6)
            }
        )

        parent.addView(row)
        refreshTodayStats()
    }

    private fun buildTodayStatBox(
        topLabel: String,
        bottomLabel: String,
        onClick: () -> Unit
    ): Pair<MaterialCardView, TextView> {
        val card = MaterialCardView(this).apply {
            radius = UiKit.dp(this@MainActivity, 16).toFloat()
            cardElevation = UiKit.dp(this@MainActivity, 2).toFloat()
            strokeWidth = 1
            strokeColor = 0x556B6578
            setCardBackgroundColor(0xFF25252C.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                UiKit.dp(this@MainActivity, 12),
                UiKit.dp(this@MainActivity, 16),
                UiKit.dp(this@MainActivity, 12),
                UiKit.dp(this@MainActivity, 16)
            )
        }
        content.addView(TextView(this).apply {
            text = topLabel
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFF5F3F7.toInt())
        })
        val countView = TextView(this).apply {
            text = "0"
            textSize = 32f
            gravity = Gravity.CENTER
            typeface = UiKit.headingTypeface(this@MainActivity)
            setTextColor(UiKit.primaryColor(this@MainActivity))
            setPadding(0, UiKit.dp(this@MainActivity, 6), 0, UiKit.dp(this@MainActivity, 6))
        }
        content.addView(countView)
        content.addView(TextView(this).apply {
            text = bottomLabel
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFF5F3F7.toInt())
        })
        card.addView(content)
        return card to countView
    }

    private fun refreshTodayStats() {
        // AI replies only — auto-replies are tracked separately via Auto Reply Tracker.
        repliesTodayCountView?.text = StatsTracker.getRepliesSentToday(this).toString()
        autoRepliesTodayCountView?.text =
            AutoReplyManager.getAutoRepliesTriggeredToday(this).toString()
    }

    private fun buildAccessibilityBanner(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = UiKit.dp(this@MainActivity, 14).toFloat()
            cardElevation = UiKit.dp(this@MainActivity, 2).toFloat()
            strokeWidth = 1
            strokeColor = 0xFFB39DDB.toInt()
            setCardBackgroundColor(0xFF2E2A3A.toInt())
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(this@MainActivity, 18),
                UiKit.dp(this@MainActivity, 14),
                UiKit.dp(this@MainActivity, 12),
                UiKit.dp(this@MainActivity, 14)
            )
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "Use voice-to-text in any app — grant one more permission"
            textSize = 14f
            setTextColor(0xFFF5F3F7.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(0xFFA39BB0.toInt())
            setPadding(
                UiKit.dp(this@MainActivity, 10),
                UiKit.dp(this@MainActivity, 4),
                UiKit.dp(this@MainActivity, 4),
                UiKit.dp(this@MainActivity, 4)
            )
            setOnClickListener {
                accessibilityBannerDismissedThisSession = true
                refreshAccessibilityBanner()
            }
        })
        row.addView(top)
        row.addView(TextView(this).apply {
            text = "Grant Access"
            textSize = 13f
            setTextColor(0xFFB39DDB.toInt())
            setPadding(0, UiKit.dp(this@MainActivity, 10), 0, 0)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })
        card.addView(row)
        return card
    }

    /** Shown when Accessibility was granted before but is now off (regression / OS reset). */
    private fun buildAccessibilityRepairBanner(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = UiKit.dp(this@MainActivity, 14).toFloat()
            cardElevation = UiKit.dp(this@MainActivity, 2).toFloat()
            strokeWidth = 2
            strokeColor = 0xFFF59E0B.toInt()
            setCardBackgroundColor(0xFF3A2E1A.toInt())
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(this@MainActivity, 18),
                UiKit.dp(this@MainActivity, 14),
                UiKit.dp(this@MainActivity, 12),
                UiKit.dp(this@MainActivity, 14)
            )
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "Accessibility got turned off — voice dictation and gestures won't work until it's re-enabled"
            textSize = 14f
            setTextColor(0xFFFFF7E6.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(0xFFFBBF24.toInt())
            setPadding(
                UiKit.dp(this@MainActivity, 10),
                UiKit.dp(this@MainActivity, 4),
                UiKit.dp(this@MainActivity, 4),
                UiKit.dp(this@MainActivity, 4)
            )
            setOnClickListener {
                accessibilityRepairBannerDismissedThisSession = true
                refreshAccessibilityBanner()
            }
        })
        row.addView(top)
        row.addView(TextView(this).apply {
            text = "Re-enable Accessibility"
            textSize = 13f
            setTextColor(0xFFFBBF24.toInt())
            setPadding(0, UiKit.dp(this@MainActivity, 10), 0, 0)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })
        card.addView(row)
        return card
    }

    private fun isAiConfigured(): Boolean =
        AiSetupActivity.hasCompletedAiSetup(this) ||
            SettingsManager.getActiveAiKey(this).isNotBlank()

    private fun refreshAiKeyBanner() {
        val banner = aiKeyBanner ?: return
        // Hide once Groq key or on-device model is present (same gate as a11y banner).
        banner.visibility = if (isAiConfigured()) View.GONE else View.VISIBLE
    }

    private fun refreshAccessibilityBanner() {
        val firstTime = accessibilityBanner
        val repair = accessibilityRepairBanner
        val a11yEnabled = CatAccessibilityService.instance != null
        if (a11yEnabled) {
            SettingsManager.setAccessibilityWasEverEnabled(this, true)
        }
        val wasEver = SettingsManager.wasAccessibilityEverEnabled(this)
        val showFirst = isAiConfigured() &&
            !a11yEnabled &&
            !wasEver &&
            !accessibilityBannerDismissedThisSession
        val showRepair = isAiConfigured() &&
            !a11yEnabled &&
            wasEver &&
            !accessibilityRepairBannerDismissedThisSession
        firstTime?.visibility = if (showFirst) View.VISIBLE else View.GONE
        repair?.visibility = if (showRepair) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        dashboardCatAnimator?.showFrame(DASHBOARD_REST_FRAME)
        refreshAiKeyBanner()
        refreshAccessibilityBanner()
        refreshTodayStats()
        RateUsManager.maybeShowRatePrompt(this)
        DailyDigestNotifier.maybeShow(this)
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
