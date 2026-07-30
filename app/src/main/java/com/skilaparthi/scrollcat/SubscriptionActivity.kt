package com.skilaparthi.scrollcat

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * ScrollCat Pro paywall — Free / Creator / Business tier cards with
 * Google Play subscription purchase and restore.
 */
class SubscriptionActivity : Activity() {

    companion object {
        private const val ACCENT = 0xFF4A90D9.toInt()
        private const val TERMS_URL = "https://scrollcat.app/terms"
        private const val PRIVACY_URL = "https://scrollcat.app/privacy"
    }

    private lateinit var billing: BillingManager
    private lateinit var statusText: TextView
    private lateinit var cardsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        billing = BillingManager.getInstance(this)
        billing.startConnection()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 56, 32, 32)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

        // Toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        toolbar.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            setOnClickListener { finish() }
        })
        toolbar.addView(TextView(this).apply {
            text = "  ScrollCat Pro"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(toolbar)

        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 16)
        }
        root.addView(statusText)

        cardsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(cardsContainer)

        // Restore purchases
        root.addView(UiKit.tonalButton(this, "Restore purchases") {
                billing.queryPurchases()
                Toast.makeText(this@SubscriptionActivity, "Checking your purchases…", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 24, 0, 0) })

        // Legal links
        val legalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }
        legalRow.addView(TextView(this).apply {
            text = "Terms of Service"
            textSize = 12f
            setTextColor(ACCENT)
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TERMS_URL)))
                } catch (e: Exception) { }
            }
        })
        legalRow.addView(TextView(this).apply {
            text = "·"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        })
        legalRow.addView(TextView(this).apply {
            text = "Privacy Policy"
            textSize = 12f
            setTextColor(ACCENT)
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                startActivity(Intent(this@SubscriptionActivity, PrivacyPolicyActivity::class.java))
            }
        })
        root.addView(legalRow)

        val scrollView = ScrollView(this)
        scrollView.addView(root)
        setContentView(scrollView)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(32, bars.top + 16, 32, 32 + bars.bottom)
            insets
        }

        billing.onEntitlementsChanged = { runOnUiThread { render() } }
        render()
    }

    override fun onResume() {
        super.onResume()
        billing.queryPurchases()
        render()
    }

    override fun onDestroy() {
        if (billing.onEntitlementsChanged != null) billing.onEntitlementsChanged = null
        super.onDestroy()
    }

    private fun render() {
        val isPro = billing.isPro()
        val isBusiness = billing.isBusinessTier()

        statusText.text = when {
            isBusiness -> "Current plan: Business 🏢 — thank you!"
            isPro -> "Current plan: Creator 😸 — thank you!"
            else -> "Current plan: Free 🐱"
        }

        cardsContainer.removeAllViews()

        addTierCard(
            emoji = "🐱",
            name = "Free",
            price = "$0",
            features = "10 AI replies/day · Basic gestures · Default cat",
            highlighted = false,
            buttonLabel = if (!isPro) "Current Plan" else null,
            buttonEnabled = false,
            onClick = { }
        )

        addTierCard(
            emoji = "😸",
            name = "Creator",
            price = billing.getPrice(BillingManager.PRODUCT_CREATOR) ?: "$4.99/mo",
            features = "Unlimited AI replies · All gestures · Custom tone · Priority support",
            highlighted = true,
            buttonLabel = if (isPro && !isBusiness) "Current Plan" else "Subscribe",
            buttonEnabled = !isPro,
            onClick = { subscribe(BillingManager.PRODUCT_CREATOR) }
        )

        addTierCard(
            emoji = "🏢",
            name = "Business",
            price = billing.getPrice(BillingManager.PRODUCT_BUSINESS) ?: "$9.99/mo",
            features = "Everything in Creator · Auto-reply rules · Business tone AI · Analytics",
            highlighted = false,
            buttonLabel = if (isBusiness) "Current Plan" else "Subscribe",
            buttonEnabled = !isBusiness,
            onClick = { subscribe(BillingManager.PRODUCT_BUSINESS) }
        )
    }

    private fun subscribe(productId: String) {
        val started = billing.launchPurchaseFlow(this, productId)
        if (!started) {
            Toast.makeText(
                this,
                "Store not ready yet — check your connection and try again",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun addTierCard(
        emoji: String,
        name: String,
        price: String,
        features: String,
        highlighted: Boolean,
        buttonLabel: String?,
        buttonEnabled: Boolean,
        onClick: () -> Unit
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 28)
            background = GradientDrawable().apply {
                setColor(if (highlighted) 0xFFF0F6FF.toInt() else 0xFFF7F7FA.toInt())
                cornerRadius = 28f
                setStroke(if (highlighted) 4 else 2, if (highlighted) ACCENT else 0xFFE0E0E8.toInt())
            }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(this).apply {
            text = "$emoji $name"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF222233.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(TextView(this).apply {
            text = price
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ACCENT)
        })
        card.addView(headerRow)

        card.addView(TextView(this).apply {
            text = features
            textSize = 13f
            setTextColor(0xFF666677.toInt())
            setPadding(0, 10, 0, 14)
        })

        if (buttonLabel != null) {
            card.addView(UiKit.primaryButton(this, buttonLabel) {
                if (buttonEnabled) onClick()
            }.apply {
                isEnabled = buttonEnabled
            })
        }

        cardsContainer.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 10, 0, 10) })
    }
}
