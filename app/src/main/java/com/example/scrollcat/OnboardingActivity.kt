package com.example.scrollcat

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * First-launch onboarding: intro → user type → profile → permissions → done.
 * Saves userType/userName/userNiche/rateCardMessage and sets
 * onboarding_complete when finished.
 */
class OnboardingActivity : Activity() {

    companion object {
        private const val ACCENT = 0xFF4A90D9.toInt()

        private const val CREATOR_RATE_CARD =
            "Hey! Thanks for reaching out 💕 For collabs and pricing, send me your brief and I'll share my rate card within 24h!"
        private const val BUSINESS_DEFAULT_REPLY =
            "Thanks for contacting us! We'll get back to you shortly — please share a few details about what you need."

        private val CREATOR_NICHES = listOf(
            "Fashion", "Food", "Tech", "Fitness", "Travel", "Beauty", "Gaming", "Other"
        )
        private val BUSINESS_TYPES = listOf(
            "Plumbing", "Salon", "Restaurant", "Coaching", "Retail", "Other"
        )
    }

    private var userType = "personal"
    private var currentScreen = 1
    private lateinit var container: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = ScrollView(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        setContentView(container)
        showScreen1()
    }

    override fun onResume() {
        super.onResume()
        // Refresh live permission status when returning from system settings
        if (currentScreen == 4) showScreen4()
    }

    // ── Layout helpers ──

    private fun screenRoot(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
        }
        container.removeAllViews()
        container.addView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(48, bars.top + 72, 48, 48 + bars.bottom)
            insets
        }
        return root
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFF222233.toInt())
        setPadding(0, 0, 0, 16)
    }

    private fun subtitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(0xFF666677.toInt())
        setPadding(0, 0, 0, 32)
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(ACCENT)
            cornerRadius = 32f
        }
        setPadding(32, 28, 32, 28)
        setOnClickListener { onClick() }
    }

    private fun fieldLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 24, 0, 8)
    }

    // ── Screen 1: intro ──

    private fun showScreen1() {
        currentScreen = 1
        val root = screenRoot()
        root.addView(TextView(this).apply {
            text = "🐱"
            textSize = 72f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })
        root.addView(title("Meet ScrollCat 🐱"))
        root.addView(subtitle(
            "Your floating cat companion. It scrolls for you, watches your " +
                "notifications, and writes AI-powered replies to your DMs across " +
                "WhatsApp, Instagram, Telegram and more — without opening the apps."
        ))
        root.addView(primaryButton("Next") { showScreen2() })
    }

    // ── Screen 2: user type ──

    private fun showScreen2() {
        currentScreen = 2
        val root = screenRoot()
        root.addView(title("What best describes you?"))
        root.addView(subtitle("The cat tunes its replies to how you use your DMs."))

        listOf(
            Triple("📱", "Content Creator", "creator"),
            Triple("🏢", "Business Owner", "business"),
            Triple("👤", "Personal Use", "personal")
        ).forEach { (emoji, label, type) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(36, 40, 36, 40)
                background = GradientDrawable().apply {
                    setColor(0xFFF5F7FB.toInt())
                    cornerRadius = 28f
                    setStroke(2, 0xFFDDE3F0.toInt())
                }
                setOnClickListener {
                    userType = type
                    SettingsManager.setUserType(this@OnboardingActivity, type)
                    when (type) {
                        "creator" -> showScreen3Creator()
                        "business" -> showScreen3Business()
                        else -> showScreen4() // personal skips profile
                    }
                }
            }
            card.addView(TextView(this).apply {
                text = emoji
                textSize = 32f
                setPadding(0, 0, 28, 0)
            })
            card.addView(TextView(this).apply {
                text = label
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF222233.toInt())
            })
            root.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 12) })
        }
    }

    // ── Screen 3 (creator) ──

    private fun showScreen3Creator() {
        currentScreen = 3
        val root = screenRoot()
        root.addView(title("Set up your creator profile"))
        root.addView(subtitle("Used so AI replies sound like you."))

        root.addView(fieldLabel("Your name"))
        val nameInput = EditText(this).apply {
            hint = "e.g. Priya"
            textSize = 15f
            setText(SettingsManager.getUserName(this@OnboardingActivity))
            setSingleLine(true)
        }
        root.addView(nameInput)

        root.addView(fieldLabel("Your niche"))
        val nicheSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@OnboardingActivity,
                android.R.layout.simple_spinner_dropdown_item,
                CREATOR_NICHES
            )
        }
        root.addView(nicheSpinner)

        root.addView(fieldLabel("Rate card message"))
        val rateInput = EditText(this).apply {
            textSize = 15f
            minLines = 3
            setText(
                SettingsManager.getRateCardMessage(this@OnboardingActivity)
                    .ifBlank { CREATOR_RATE_CARD }
            )
        }
        root.addView(rateInput)

        root.addView(primaryButton("Next") {
            SettingsManager.setUserName(this, nameInput.text.toString().trim())
            SettingsManager.setUserNiche(this, nicheSpinner.selectedItem?.toString() ?: "Other")
            SettingsManager.setRateCardMessage(this, rateInput.text.toString().trim())
            showScreen4()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 32, 0, 0) })
    }

    // ── Screen 3 (business) ──

    private fun showScreen3Business() {
        currentScreen = 3
        val root = screenRoot()
        root.addView(title("Set up your business profile"))
        root.addView(subtitle("Used so AI replies represent your business well."))

        root.addView(fieldLabel("Business name"))
        val nameInput = EditText(this).apply {
            hint = "e.g. Sunrise Salon"
            textSize = 15f
            setText(SettingsManager.getUserName(this@OnboardingActivity))
            setSingleLine(true)
        }
        root.addView(nameInput)

        root.addView(fieldLabel("Service type"))
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@OnboardingActivity,
                android.R.layout.simple_spinner_dropdown_item,
                BUSINESS_TYPES
            )
        }
        root.addView(typeSpinner)

        root.addView(fieldLabel("Default reply"))
        val replyInput = EditText(this).apply {
            textSize = 15f
            minLines = 3
            setText(
                SettingsManager.getRateCardMessage(this@OnboardingActivity)
                    .ifBlank { BUSINESS_DEFAULT_REPLY }
            )
        }
        root.addView(replyInput)

        root.addView(primaryButton("Next") {
            SettingsManager.setUserName(this, nameInput.text.toString().trim())
            SettingsManager.setUserNiche(this, typeSpinner.selectedItem?.toString() ?: "Other")
            SettingsManager.setRateCardMessage(this, replyInput.text.toString().trim())
            showScreen4()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 32, 0, 0) })
    }

    // ── Screen 4: permissions ──

    private fun showScreen4() {
        currentScreen = 4
        val root = screenRoot()
        root.addView(title("Grant Permissions"))
        root.addView(subtitle("The cat needs these three to float, scroll and read your DMs."))

        val overlayOk = Settings.canDrawOverlays(this)
        val a11yOk = CatAccessibilityService.instance != null
        val notifOk = CatNotificationListener.instance != null

        fun permissionRow(label: String, granted: Boolean, onClick: () -> Unit): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(28, 28, 28, 28)
                background = GradientDrawable().apply {
                    setColor(if (granted) 0xFFEFFAF0.toInt() else 0xFFF5F7FB.toInt())
                    cornerRadius = 24f
                    setStroke(2, if (granted) 0xFF9ADBA5.toInt() else 0xFFDDE3F0.toInt())
                }
                setOnClickListener { if (!granted) onClick() }
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(0xFF222233.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = if (granted) "✓ Granted" else "Grant →"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (granted) 0xFF2E9E44.toInt() else ACCENT)
            })
            return row
        }

        val rows = listOf(
            permissionRow("🪟 Display over other apps", overlayOk) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            },
            permissionRow("♿ Accessibility (gestures)", a11yOk) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            permissionRow("🔔 Notification access (DMs)", notifOk) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        )
        rows.forEach { row ->
            root.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 10, 0, 10) })
        }

        root.addView(primaryButton(
            if (overlayOk && a11yOk && notifOk) "Continue" else "Continue anyway"
        ) { showScreen5() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 32, 0, 0) })
    }

    // ── Screen 5: done ──

    private fun showScreen5() {
        currentScreen = 5
        val root = screenRoot()
        root.addView(TextView(this).apply {
            text = "🎉"
            textSize = 72f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })
        root.addView(title("You're all set! 🎉"))
        root.addView(subtitle(
            "Tap below to summon your cat. When a DM arrives, a badge appears " +
                "on the cat — tap it for AI reply suggestions."
        ))
        root.addView(primaryButton("Summon Cat 🐱") {
            SettingsManager.setOnboardingComplete(this, true)
            if (Settings.canDrawOverlays(this)) {
                startForegroundService(Intent(this, OverlayService::class.java))
            }
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        })
    }
}
