package com.example.scrollcat

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class PrivacyPolicyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 56, 32, 32)
            setBackgroundColor(0xFF1A1A1E.toInt())
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        toolbar.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(0xFFF5F3F7.toInt())
            setOnClickListener { finish() }
        })
        toolbar.addView(TextView(this).apply {
            text = "  Privacy Policy"
            textSize = 18f
            typeface = UiKit.headingTypeface(this@PrivacyPolicyActivity)
            setTextColor(0xFFF5F3F7.toInt())
        })
        root.addView(toolbar)

        root.addView(TextView(this).apply {
            text = POLICY_TEXT
            textSize = 14f
            setTextColor(0xFFE8E4EF.toInt())
            setLineSpacing(6f, 1f)
        })

        val scrollView = ScrollView(this)
        scrollView.addView(root)
        setContentView(scrollView)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(32, bars.top + 16, 32, 32 + bars.bottom)
            insets
        }
    }

    companion object {
        private val POLICY_TEXT = """
ScrollCat Privacy Policy
Last updated: July 2026

ScrollCat is built to be private by design. This policy explains exactly what the app does with your data.

1. WHAT DATA WE COLLECT

• Notification content. To offer smart replies, ScrollCat reads the notifications you allow it to access (sender name and message text from messaging apps). This content is processed locally on your device to generate reply suggestions.

• Your profile settings. Your name, niche/business type, reply tone and auto-reply rules are stored only in the app's local storage on your device.

• Usage counters. The app keeps simple local counters (e.g. replies used today) to enforce free-tier limits and improve your experience. These never leave your device.

2. WHAT WE DO NOT COLLECT

• Your messages are NOT stored on our servers. ScrollCat has no server of its own — there is nothing for us to store, read or sell.

• We do not collect analytics, advertising identifiers, contacts, location, or browsing history.

• Notification content is never persisted; it is held in memory only long enough to generate a suggestion and is discarded afterwards.

3. THIRD-PARTY SERVICES

• Google ML Kit (on-device). Reply suggestions are generated on your device using Google ML Kit (Gemini Nano and Smart Reply). Message text does not leave your phone when these engines are used.

• Anthropic API (optional). If you choose to enter your own Claude API key, the message you are replying to is sent to Anthropic (api.anthropic.com) to generate suggestions, under Anthropic's own privacy policy. This is entirely opt-in — without an API key, everything stays on your device. Your API key is stored encrypted on your device and is only sent to Anthropic.

• Google Play Billing. Subscriptions are processed by Google Play. We never see your payment details.

4. PERMISSIONS

• Notification access — to read incoming messages and send replies through each app's own reply mechanism.
• Display over other apps — to show the floating cat.
• Accessibility — to perform scroll gestures on your behalf. Accessibility data is never logged or transmitted.

5. YOUR CHOICES

You can revoke any permission at any time in Android Settings. Uninstalling the app deletes all locally stored data.

6. CHILDREN

ScrollCat is not directed at children under 13.

7. CHANGES

We will update this policy inside the app if our practices change.

8. CONTACT

Questions or concerns: privacy@scrollcat.app
        """.trimIndent()
    }
}
