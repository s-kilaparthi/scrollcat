package com.skilaparthi.scrollcat

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

- Notification content. To offer smart replies, ScrollCat reads the notifications you allow it to access (sender name and message text from messaging apps). This content is processed to generate reply suggestions and is never persisted — held in memory only long enough to generate a suggestion, then discarded.

- Your profile settings, auto-reply rules, and app preferences. Stored only in local storage on your device.

- Anonymous usage analytics. ScrollCat uses Firebase Analytics to understand how the app is used, so we can improve it. This is on by default but can be turned off anytime in Privacy & Security in the app.

- Crash reports. If the app crashes, technical crash data (via Firebase Crashlytics) helps us fix bugs. This never includes your messages or personal content.

2. WHAT WE DO NOT COLLECT

- Your messages are never stored on our servers — ScrollCat has no backend of its own.

- We never read or interact with password fields, in any app, under any circumstance.

- ScrollCat never reads notifications from banking or payment apps.

- We do not collect contacts, location, or browsing history.

3. AI PROCESSING — ON-DEVICE OR CLOUD, YOUR CHOICE

- On-device (default on capable devices). Replies are generated entirely on your phone using an on-device AI model (downloaded once, verified for integrity, and never leaves your device). Nothing about your messages is sent anywhere.

- Cloud providers (optional, user-provided key). If you choose to connect Groq, Anthropic Claude, OpenAI, or another provider with your own API key, the message you're replying to is sent to that provider to generate a suggestion, under that provider's own privacy policy. This is entirely opt-in. Your API key is stored encrypted on your device and is only ever sent to the provider you configured it for.

- ML Kit (on-device). Basic reply suggestions, language translation, and script romanization can also run via Google's on-device ML Kit, entirely on your phone.

4. PERMISSIONS

- Notification access — reads incoming messages so ScrollCat can suggest replies, sent through each app's own reply mechanism.

- Display over other apps — shows the floating cat.

- Accessibility (optional) — lets you dictate directly into any app's text field, and lets the cat scroll Reels/Shorts on your behalf. ScrollCat never reads or interacts with password fields, and accessibility data is never logged or transmitted.

- Microphone — only active while you're actively using voice-to-text; never active in the background.

5. YOUR CHOICES

You can revoke any permission anytime in Android Settings, and turn off analytics anytime in Privacy & Security within the app. Uninstalling ScrollCat deletes all locally stored data — we don't keep anything on a server.

6. CHILDREN

ScrollCat is not directed at children under 13.

7. CHANGES

We'll update this policy here and in the app if our practices change.

8. CONTACT

Questions or concerns: shipproof.app@gmail.com
        """.trimIndent()
    }
}
