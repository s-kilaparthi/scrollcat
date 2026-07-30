package com.example.scrollcat

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
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
import com.google.android.material.card.MaterialCardView

/**
 * Privacy & Security explainer: what ScrollCat never does, what each permission
 * does, how AI processing works for the user's current provider, and a link to
 * the full privacy policy.
 */
class PrivacySecurityActivity : Activity() {

    companion object {
        private const val CARD = 0xFF25252C.toInt()
        private const val STROKE = 0x556B6578
        private const val TEXT = 0xFFF5F3F7.toInt()
        private const val MUTED = 0xFFA39BB0.toInt()
        private const val LINK = 0xFFB39DDB.toInt()
    }

    private var refreshAiActiveUi: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = UiKit.pageRoot(this)
        addToolbar(root)
        root.addView(
            UiKit.body(
                this,
                "How ScrollCat protects your data — and what each permission is actually for.",
                muted = true
            )
        )

        addNeverDoSection(root)
        addPermissionsSection(root)
        addAiProcessingSection(root)
        addPrivacyPolicyLink(root)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(UiKit.surfaceColor(this@PrivacySecurityActivity))
            addView(root)
        }
        setContentView(scrollView)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                UiKit.dp(this, 24),
                bars.top + UiKit.dp(this, 16),
                UiKit.dp(this, 24),
                UiKit.dp(this, 24) + bars.bottom
            )
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAiActiveUi?.invoke()
    }

    private fun addToolbar(root: LinearLayout) {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, UiKit.dp(this@PrivacySecurityActivity, 16))
        }
        toolbar.addView(TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(UiKit.onSurfaceColor(this@PrivacySecurityActivity))
            setPadding(0, 0, UiKit.dp(this@PrivacySecurityActivity, 12), 0)
            setOnClickListener { finish() }
        })
        toolbar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_shield)
            imageTintList = ColorStateList.valueOf(UiKit.primaryColor(this@PrivacySecurityActivity))
            contentDescription = null
            layoutParams = LinearLayout.LayoutParams(
                UiKit.dp(this@PrivacySecurityActivity, 26),
                UiKit.dp(this@PrivacySecurityActivity, 26)
            ).apply { marginEnd = UiKit.dp(this@PrivacySecurityActivity, 10) }
        })
        toolbar.addView(UiKit.headline(this, "Privacy & Security").apply {
            setPadding(0, 0, 0, 0)
        })
        root.addView(toolbar)
    }

    private fun addNeverDoSection(root: LinearLayout) {
        UiKit.section(root, "What we never do") {
            addView(
                infoRow(
                    R.drawable.ic_shield,
                    "Never reads banking or payment app notifications — this can't be changed."
                )
            )
            addView(divider())
            addView(
                infoRow(
                    R.drawable.ic_lock,
                    "Never reads or interacts with password fields, anywhere."
                )
            )
            addView(divider())
            addView(
                infoRow(
                    R.drawable.ic_section_apps,
                    "Only reads notification content from apps you've allowed.",
                    linkLabel = "Manage apps →"
                ) {
                    startActivity(
                        Intent(
                            this@PrivacySecurityActivity,
                            NotificationSettingsActivity::class.java
                        )
                    )
                }
            )
            addView(divider())
            addView(
                infoRow(
                    R.drawable.ic_delete_outline,
                    "Voice recordings are processed and immediately discarded — never stored."
                )
            )
        }
    }

    private fun addPermissionsSection(root: LinearLayout) {
        UiKit.section(root, "What each permission actually does") {
            addView(
                infoRow(
                    R.drawable.ic_notifications,
                    "Notification Access — lets the cat see message content so it can suggest replies."
                )
            )
            addView(divider())
            addView(
                infoRow(
                    R.drawable.ic_layers,
                    "Display over other apps — lets the cat float on your screen."
                )
            )
            if (isAccessibilityEnabled()) {
                addView(divider())
                addView(
                    infoRow(
                        R.drawable.ic_accessibility,
                        "Accessibility — lets you dictate directly into any app's text field, and lets ScrollCat scroll Reels/Shorts for you."
                    )
                )
            }
            addView(divider())
            addView(
                infoRow(
                    R.drawable.ic_mic,
                    "Microphone — only active while actively using voice-to-text, never in the background."
                )
            )
        }
    }

    private fun addAiProcessingSection(root: LinearLayout) {
        UiKit.section(root, "AI processing") {
            val onDeviceCard = aiOptionCard(
                iconRes = R.drawable.ic_section_apps,
                title = "On-device AI",
                body = "Your replies are generated entirely on your phone — nothing is sent anywhere."
            )
            val cloudCard = aiOptionCard(
                iconRes = R.drawable.ic_cloud,
                title = "Cloud AI (Groq/Claude/Custom)",
                body = "Messages are sent to generate replies."
            )
            addView(onDeviceCard.first)
            addView(cloudCard.first.apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin =
                    UiKit.dp(this@PrivacySecurityActivity, 10)
            })

            fun applyActiveState() {
                val primary = SettingsManager.getPrimaryAiProvider(this@PrivacySecurityActivity)
                val onDeviceActive = primary == SettingsManager.PRIMARY_AI_ON_DEVICE
                val cloudActive = primary == SettingsManager.PRIMARY_AI_GROQ ||
                    primary == SettingsManager.PRIMARY_AI_CLAUDE ||
                    primary == SettingsManager.PRIMARY_AI_OPENAI ||
                    primary == SettingsManager.PRIMARY_AI_CUSTOM
                onDeviceCard.second(onDeviceActive)
                cloudCard.second(cloudActive)
            }
            refreshAiActiveUi = { applyActiveState() }
            applyActiveState()
        }
    }

    /**
     * Returns the option card plus a binder that toggles the Active highlight.
     */
    private fun aiOptionCard(
        iconRes: Int,
        title: String,
        body: String
    ): Pair<MaterialCardView, (Boolean) -> Unit> {
        val activeBg = 0xFF2E2A3A.toInt()
        val inactiveBg = 0xFF1F1F24.toInt()
        val activeStroke = UiKit.primaryColor(this)
        val inactiveStroke = STROKE

        val card = MaterialCardView(this).apply {
            radius = UiKit.dp(this@PrivacySecurityActivity, 14).toFloat()
            cardElevation = 0f
            strokeWidth = 1
            strokeColor = inactiveStroke
            setCardBackgroundColor(inactiveBg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(this@PrivacySecurityActivity, 14),
                UiKit.dp(this@PrivacySecurityActivity, 12),
                UiKit.dp(this@PrivacySecurityActivity, 14),
                UiKit.dp(this@PrivacySecurityActivity, 12)
            )
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(UiKit.primaryColor(this@PrivacySecurityActivity))
            contentDescription = null
            layoutParams = LinearLayout.LayoutParams(
                UiKit.dp(this@PrivacySecurityActivity, 22),
                UiKit.dp(this@PrivacySecurityActivity, 22)
            ).apply { marginEnd = UiKit.dp(this@PrivacySecurityActivity, 10) }
        })
        header.addView(TextView(this).apply {
            text = title
            textSize = 15f
            typeface = UiKit.headingTypeface(this@PrivacySecurityActivity)
            setTextColor(TEXT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val activeBadge = TextView(this).apply {
            text = "Active"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(
                UiKit.dp(this@PrivacySecurityActivity, 8),
                UiKit.dp(this@PrivacySecurityActivity, 3),
                UiKit.dp(this@PrivacySecurityActivity, 8),
                UiKit.dp(this@PrivacySecurityActivity, 3)
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF15803D.toInt())
                cornerRadius = UiKit.dp(this@PrivacySecurityActivity, 12).toFloat()
            }
            visibility = View.GONE
        }
        header.addView(activeBadge)
        content.addView(header)
        content.addView(TextView(this).apply {
            text = body
            textSize = 14f
            setTextColor(MUTED)
            setLineSpacing(3f, 1f)
            setPadding(0, UiKit.dp(this@PrivacySecurityActivity, 6), 0, 0)
        })
        card.addView(content)

        val bindActive: (Boolean) -> Unit = { active ->
            card.setCardBackgroundColor(if (active) activeBg else inactiveBg)
            card.strokeColor = if (active) activeStroke else inactiveStroke
            card.strokeWidth = if (active) 2 else 1
            activeBadge.visibility = if (active) View.VISIBLE else View.GONE
            content.alpha = if (active) 1f else 0.72f
        }
        return card to bindActive
    }

    private fun addPrivacyPolicyLink(root: LinearLayout) {
        val card = MaterialCardView(this).apply {
            radius = UiKit.dp(this@PrivacySecurityActivity, 16).toFloat()
            cardElevation = UiKit.dp(this@PrivacySecurityActivity, 2).toFloat()
            strokeWidth = 1
            strokeColor = STROKE
            setCardBackgroundColor(CARD)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(
                    Intent(this@PrivacySecurityActivity, PrivacyPolicyActivity::class.java)
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiKit.dp(this@PrivacySecurityActivity, 12) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                UiKit.dp(this@PrivacySecurityActivity, 20),
                UiKit.dp(this@PrivacySecurityActivity, 18),
                UiKit.dp(this@PrivacySecurityActivity, 20),
                UiKit.dp(this@PrivacySecurityActivity, 18)
            )
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_clipboard_list)
            imageTintList = ColorStateList.valueOf(UiKit.primaryColor(this@PrivacySecurityActivity))
            contentDescription = null
            layoutParams = LinearLayout.LayoutParams(
                UiKit.dp(this@PrivacySecurityActivity, 22),
                UiKit.dp(this@PrivacySecurityActivity, 22)
            ).apply { marginEnd = UiKit.dp(this@PrivacySecurityActivity, 12) }
        })
        row.addView(TextView(this).apply {
            text = "Read the full Privacy Policy"
            textSize = 15f
            typeface = UiKit.headingTypeface(this@PrivacySecurityActivity)
            setTextColor(LINK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = "→"
            textSize = 18f
            setTextColor(LINK)
        })
        card.addView(row)
        root.addView(card)
    }

    private fun infoRow(
        iconRes: Int,
        text: String,
        linkLabel: String? = null,
        onLinkClick: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, UiKit.dp(this@PrivacySecurityActivity, 4), 0, UiKit.dp(this@PrivacySecurityActivity, 4))
        }
        row.addView(ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@PrivacySecurityActivity, iconRes))
            imageTintList = ColorStateList.valueOf(UiKit.primaryColor(this@PrivacySecurityActivity))
            contentDescription = null
            layoutParams = LinearLayout.LayoutParams(
                UiKit.dp(this@PrivacySecurityActivity, 22),
                UiKit.dp(this@PrivacySecurityActivity, 22)
            ).apply {
                marginEnd = UiKit.dp(this@PrivacySecurityActivity, 12)
                topMargin = UiKit.dp(this@PrivacySecurityActivity, 2)
            }
        })
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(TEXT)
            setLineSpacing(4f, 1f)
        })
        if (linkLabel != null && onLinkClick != null) {
            textCol.addView(TextView(this).apply {
                this.text = linkLabel
                textSize = 14f
                setTextColor(LINK)
                setPadding(0, UiKit.dp(this@PrivacySecurityActivity, 6), 0, 0)
                setOnClickListener { onLinkClick() }
            })
        }
        row.addView(textCol)
        return row
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(STROKE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                topMargin = UiKit.dp(this@PrivacySecurityActivity, 10)
                bottomMargin = UiKit.dp(this@PrivacySecurityActivity, 10)
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (CatAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = "$packageName/${CatAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(component, ignoreCase = true) } ||
            (enabled.contains(packageName) && enabled.contains("CatAccessibilityService"))
    }
}
