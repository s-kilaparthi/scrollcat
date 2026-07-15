package com.example.scrollcat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * First-launch onboarding: intro → user type → profile → extended AI profile → permissions → done.
 */
class OnboardingActivity : Activity() {

    companion object {
        private const val PREFS = "scrollcat_prefs"
        private const val ACCENT = 0xFFD97706.toInt()
        private const val BG = 0xFFFFF8F0.toInt()
        private const val CARD = 0xFFFFFFFF.toInt()
        private const val STROKE = 0x558C7A68
        private const val TEXT = 0xFF241A12.toInt()
        private const val MUTED = 0xFF6F5F50.toInt()
        private const val INPUT_BG = 0xFFFFFFFF.toInt()
        private const val HINT_COLOR = 0xFF8C7A68.toInt()

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
        private val STYLE_OPTIONS = listOf(
            Triple("Casual & Fun", "Relaxed, friendly tone", "casual"),
            Triple("Professional", "Formal and polished", "professional"),
            Triple("Short & Direct", "Straight to the point", "short"),
            Triple("Warm & Personal", "Caring and genuine", "warm")
        )
        private val EMOJI_OPTIONS = listOf(
            "Always use emojis" to "always",
            "Sometimes" to "sometimes",
            "Never use emojis" to "never"
        )
        private val LENGTH_OPTIONS = listOf(
            "Very short (1-2 lines)" to "short",
            "Medium length" to "medium",
            "Detailed replies" to "detailed"
        )
        private val LANGUAGE_OPTIONS = listOf(
            "English", "Hindi", "Spanish", "Arabic", "French", "Other"
        )
        private val PLATFORM_OPTIONS = listOf(
            "Instagram", "TikTok", "YouTube", "Twitter", "LinkedIn"
        )
    }

    private var userType = "personal"
    private var editMode = false
    private var currentScreen = 1
    private lateinit var container: ScrollView

    private fun prefs(): SharedPreferences =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getPrefString(key: String, default: String = ""): String =
        prefs().getString(key, default) ?: default

    private fun savePrefString(key: String, value: String) {
        prefs().edit().putString(key, value).apply()
    }

    private fun savePrefBool(key: String, value: Boolean) {
        prefs().edit().putBoolean(key, value).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editMode = intent.getBooleanExtra("edit_mode", false)
        container = ScrollView(this).apply { setBackgroundColor(BG) }
        setContentView(container)

        if (editMode) {
            userType = SettingsManager.getUserType(this)
            when (userType) {
                "creator" -> showScreen3Creator()
                "business" -> showScreen3Business()
                else -> showScreen3BPersonalStyle()
            }
            return
        }
        showScreen1()
    }

    override fun onResume() {
        super.onResume()
        if (currentScreen == 4 && !editMode) showScreen4()
    }

    private fun screenRoot(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
            setBackgroundColor(BG)
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

    private fun progressLabel(step: Int, total: Int) = TextView(this).apply {
        text = "Step $step of $total"
        textSize = 13f
        setTextColor(ACCENT)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, 16)
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(TEXT)
        setPadding(0, 0, 0, 16)
    }

    private fun subtitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(MUTED)
        setPadding(0, 0, 0, 32)
    }

    private fun fieldLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFFCCCCCC.toInt())
        setPadding(0, 24, 0, 8)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun fieldMarginParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(16) }

    private fun addFormField(root: LinearLayout, label: String, input: EditText) {
        root.addView(fieldLabel(label))
        root.addView(input, fieldMarginParams())
    }

    private fun darkEditText(hint: String, lines: Int = 1, value: String = "") = EditText(this).apply {
        this.hint = hint
        setHintTextColor(HINT_COLOR)
        setTextColor(Color.WHITE)
        textSize = 15f
        if (value.isNotEmpty()) setText(value)
        if (lines > 1) minLines = lines
        else setSingleLine(true)
        background = GradientDrawable().apply {
            setColor(INPUT_BG)
            cornerRadius = 12f
        }
        val pad = dp(24)
        setPadding(pad, pad, pad, pad)
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        cornerRadius = dp(24)
        minHeight = dp(52)
        insetTop = 0
        insetBottom = 0
        setPadding(32, 28, 32, 28)
        setOnClickListener { onClick() }
    }

    private fun secondaryButton(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(TEXT)
        backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFE0B2.toInt())
        cornerRadius = dp(24)
        minHeight = dp(52)
        insetTop = 0
        insetBottom = 0
        setPadding(24, 20, 24, 20)
        setOnClickListener { onClick() }
    }

    private fun skipLink(onSkip: () -> Unit) = TextView(this).apply {
        text = "Skip for now"
        textSize = 14f
        setTextColor(MUTED)
        gravity = Gravity.CENTER
        setPadding(0, 24, 0, 8)
        setOnClickListener { onSkip() }
    }

    private fun addNavRow(
        root: LinearLayout,
        onBack: (() -> Unit)?,
        onNext: () -> Unit,
        onSkip: () -> Unit
    ) {
        if (onBack != null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(secondaryButton("Back", onBack), LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = 8 })
            row.addView(primaryButton("Next", onNext), LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = 8 })
            root.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 32, 0, 0) })
        } else {
            root.addView(primaryButton("Next", onNext), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 32, 0, 0) })
        }
        root.addView(skipLink(onSkip))
    }

    private class StyleSelections {
        var writingStyle = "casual"
        var emojiUsage = "sometimes"
        var replyLength = "short"
        var primaryLanguage = "English"
        var matchLanguage = true
    }

    private fun addStyleCards(
        root: LinearLayout,
        selections: StyleSelections,
        styleViews: MutableList<MaterialCardView>
    ) {
        root.addView(fieldLabel("Writing style"))
        styleViews.clear()
        STYLE_OPTIONS.forEach { (label, desc, key) ->
            val card = MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = dp(2).toFloat()
                strokeWidth = if (selections.writingStyle == key) dp(2) else dp(1)
                strokeColor = if (selections.writingStyle == key) ACCENT else STROKE
                setCardBackgroundColor(CARD)
                setOnClickListener {
                    selections.writingStyle = key
                    styleViews.forEachIndexed { i, view ->
                        val k = STYLE_OPTIONS[i].third
                        view.strokeWidth = if (selections.writingStyle == k) dp(2) else dp(1)
                        view.strokeColor = if (selections.writingStyle == k) ACCENT else STROKE
                    }
                }
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 24, 28, 24)
            }
            content.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(TEXT)
                typeface = Typeface.DEFAULT_BOLD
            })
            content.addView(TextView(this).apply {
                text = desc
                textSize = 13f
                setTextColor(MUTED)
                setPadding(0, 4, 0, 0)
            })
            card.addView(content)
            styleViews.add(card)
            root.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) })
        }
    }

    private fun addChipGroup(
        root: LinearLayout,
        label: String,
        options: List<Pair<String, String>>,
        selectedKey: String,
        onSelect: (String) -> Unit
    ): MutableList<MaterialButton> {
        root.addView(fieldLabel(label))
        val chips = mutableListOf<MaterialButton>()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        options.forEach { (text, key) ->
            val chip = MaterialButton(this).apply {
                this.text = text
                textSize = 14f
                isAllCaps = false
                cornerRadius = dp(24)
                strokeWidth = dp(1)
                strokeColor = android.content.res.ColorStateList.valueOf(if (key == selectedKey) ACCENT else STROKE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(if (key == selectedKey) 0xFFFFE0B2.toInt() else CARD)
                setTextColor(if (key == selectedKey) TEXT else MUTED)
                setPadding(24, 18, 24, 18)
                setOnClickListener {
                    onSelect(key)
                    chips.forEachIndexed { i, c ->
                        val k = options[i].second
                        c.setTextColor(if (k == key) TEXT else MUTED)
                        c.backgroundTintList = android.content.res.ColorStateList.valueOf(if (k == key) 0xFFFFE0B2.toInt() else CARD)
                        c.strokeColor = android.content.res.ColorStateList.valueOf(if (k == key) ACCENT else STROKE)
                    }
                }
            }
            chips.add(chip)
            row.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) })
        }
        root.addView(row)
        return chips
    }

    private fun addLanguageSection(root: LinearLayout, selections: StyleSelections) {
        root.addView(fieldLabel("Primary language"))
        val langRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val langViews = mutableListOf<TextView>()
        LANGUAGE_OPTIONS.forEach { lang ->
            val chip = TextView(this).apply {
                text = lang
                textSize = 12f
                setTextColor(if (lang == selections.primaryLanguage) Color.WHITE else MUTED)
                gravity = Gravity.CENTER
                setPadding(16, 12, 16, 12)
                background = GradientDrawable().apply {
                    setColor(if (lang == selections.primaryLanguage) 0xFF0D1B2A.toInt() else CARD)
                    cornerRadius = 20f
                    setStroke(1, if (lang == selections.primaryLanguage) ACCENT else STROKE)
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins(4, 0, 4, 0) }
                setOnClickListener {
                    selections.primaryLanguage = lang
                    langViews.forEach { v ->
                        val l = v.text.toString()
                        v.setTextColor(if (l == lang) Color.WHITE else MUTED)
                        (v.background as GradientDrawable).apply {
                            setColor(if (l == lang) 0xFF0D1B2A.toInt() else CARD)
                            setStroke(1, if (l == lang) ACCENT else STROKE)
                        }
                    }
                }
            }
            langViews.add(chip)
            langRow.addView(chip)
        }
        root.addView(langRow)

        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 24, 0, 0)
        }
        toggleRow.addView(TextView(this).apply {
            text = "Reply in same language as the message"
            textSize = 14f
            setTextColor(TEXT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        toggleRow.addView(SwitchMaterial(this).apply {
            isChecked = selections.matchLanguage
            setOnCheckedChangeListener { _: CompoundButton, checked ->
                selections.matchLanguage = checked
            }
        })
        root.addView(toggleRow)
    }

    private fun saveStyleSelections(selections: StyleSelections) {
        savePrefString("writing_style", selections.writingStyle)
        savePrefString("emoji_usage", selections.emojiUsage)
        savePrefString("reply_length", selections.replyLength)
        savePrefString("primary_language", selections.primaryLanguage)
        savePrefBool("match_language", selections.matchLanguage)
    }

    private fun loadStyleSelections(): StyleSelections {
        return StyleSelections().apply {
            writingStyle = getPrefString("writing_style", "casual")
            emojiUsage = getPrefString("emoji_usage", "sometimes")
            replyLength = getPrefString("reply_length", "short")
            primaryLanguage = getPrefString("primary_language", "English")
            matchLanguage = prefs().getBoolean("match_language", true)
        }
    }

    private fun addStyleEmojiLengthLanguage(
        root: LinearLayout,
        selections: StyleSelections,
        includeLanguage: Boolean
    ) {
        val styleViews = mutableListOf<MaterialCardView>()
        addStyleCards(root, selections, styleViews)
        addChipGroup(root, "Emoji usage", EMOJI_OPTIONS, selections.emojiUsage) {
            selections.emojiUsage = it
        }
        addChipGroup(root, "Reply length", LENGTH_OPTIONS, selections.replyLength) {
            selections.replyLength = it
        }
        if (includeLanguage) addLanguageSection(root, selections)
    }

    private fun finishEditOrPermissions() {
        if (editMode) {
            Toast.makeText(this, "Profile updated!", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            showScreen4()
        }
    }

    // ── Screen 1 ──

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
                "notifications, and writes AI-powered replies to your DMs."
        ))
        root.addView(primaryButton("Next") { showScreen2() })
    }

    // ── Screen 2 ──

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
            val card = MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = dp(2).toFloat()
                strokeWidth = dp(1)
                strokeColor = STROKE
                setCardBackgroundColor(CARD)
                setOnClickListener {
                    userType = type
                    SettingsManager.setUserType(this@OnboardingActivity, type)
                    when (type) {
                        "creator" -> showScreen3Creator()
                        "business" -> showScreen3Business()
                        else -> showScreen3BPersonalStyle()
                    }
                }
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(36, 40, 36, 40)
            }
            content.addView(TextView(this).apply {
                text = emoji
                textSize = 32f
                setPadding(0, 0, 28, 0)
            })
            content.addView(TextView(this).apply {
                text = label
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(TEXT)
            })
            card.addView(content)
            root.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 12, 0, 12) })
        }
    }

    // ── Creator Screen 3 ──

    private fun showScreen3Creator() {
        currentScreen = 3
        val root = screenRoot()
        root.addView(progressLabel(1, 4))
        root.addView(title("Set up your creator profile"))
        root.addView(subtitle("Used so AI replies sound like you."))

        val nameInput = darkEditText("e.g. Priya", value = SettingsManager.getUserName(this))
        addFormField(root, "Your name", nameInput)

        root.addView(fieldLabel("Your niche"))
        val nicheSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@OnboardingActivity, android.R.layout.simple_spinner_dropdown_item, CREATOR_NICHES)
        }
        root.addView(nicheSpinner, fieldMarginParams())

        val rateInput = darkEditText(
            "",
            lines = 3,
            value = SettingsManager.getRateCardMessage(this).ifBlank { CREATOR_RATE_CARD }
        )
        addFormField(root, "Rate card message", rateInput)

        addNavRow(root,
            onBack = if (editMode) null else ({ showScreen2() }),
            onNext = {
                SettingsManager.setUserName(this, nameInput.text.toString().trim())
                SettingsManager.setUserNiche(this, nicheSpinner.selectedItem?.toString() ?: "Other")
                SettingsManager.setRateCardMessage(this, rateInput.text.toString().trim())
                showScreen3BCreatorWritingStyle()
            },
            onSkip = { showScreen3BCreatorWritingStyle() }
        )
    }

    private fun showScreen3BCreatorWritingStyle() {
        currentScreen = 31
        val root = screenRoot()
        val selections = loadStyleSelections()
        root.addView(progressLabel(2, 4))
        root.addView(title("How do you write?"))
        root.addView(subtitle("This helps AI match your personal style"))
        addStyleEmojiLengthLanguage(root, selections, includeLanguage = false)
        addNavRow(root,
            onBack = { showScreen3Creator() },
            onNext = {
                saveStyleSelections(selections)
                showScreen3CCreatorContent()
            },
            onSkip = { showScreen3CCreatorContent() }
        )
    }

    private fun showScreen3CCreatorContent() {
        currentScreen = 32
        val root = screenRoot()
        root.addView(progressLabel(3, 4))
        root.addView(title("Tell AI about your content"))
        root.addView(subtitle("The more it knows, the better it replies"))

        val questionsInput = darkEditText(
            "e.g. camera gear, editing tips, collab requests",
            lines = 2,
            value = getPrefString("common_questions")
        )
        addFormField(root, "What do people ask you most?", questionsInput)

        val neverSayInput = darkEditText(
            "e.g. never give free shoutouts, never quote prices",
            lines = 2,
            value = getPrefString("never_say")
        )
        addFormField(root, "What should AI never say?", neverSayInput)

        root.addView(fieldLabel("Your platforms"))
        val savedPlatforms = getPrefString("platforms").split(",")
            .map { it.trim() }.filter { it.isNotEmpty() }
        val selectedPlatforms = savedPlatforms.filter { it in PLATFORM_OPTIONS }.toMutableSet()
        val platformViews = mutableListOf<TextView>()
        val platformRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PLATFORM_OPTIONS.forEach { platform ->
            val chip = TextView(this).apply {
                text = platform
                textSize = 12f
                setTextColor(if (platform in selectedPlatforms) Color.WHITE else MUTED)
                gravity = Gravity.CENTER
                setPadding(16, 12, 16, 12)
                background = GradientDrawable().apply {
                    setColor(if (platform in selectedPlatforms) 0xFF0D1B2A.toInt() else CARD)
                    cornerRadius = 20f
                    setStroke(1, if (platform in selectedPlatforms) ACCENT else STROKE)
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins(4, 0, 4, 0) }
                setOnClickListener {
                    if (platform in selectedPlatforms) selectedPlatforms.remove(platform)
                    else selectedPlatforms.add(platform)
                    platformViews.forEach { v ->
                        val p = v.text.toString()
                        val sel = p in selectedPlatforms
                        v.setTextColor(if (sel) Color.WHITE else MUTED)
                        (v.background as GradientDrawable).apply {
                            setColor(if (sel) 0xFF0D1B2A.toInt() else CARD)
                            setStroke(1, if (sel) ACCENT else STROKE)
                        }
                    }
                }
            }
            platformViews.add(chip)
            platformRow.addView(chip)
        }
        root.addView(platformRow, fieldMarginParams())

        val customPlatformInput = darkEditText(
            "e.g. Pinterest, Snapchat, Podcast...",
            value = getPrefString("custom_platform")
        )
        addFormField(root, "Other platform:", customPlatformInput)

        addNavRow(root,
            onBack = { showScreen3BCreatorWritingStyle() },
            onNext = {
                savePrefString("common_questions", questionsInput.text.toString().trim())
                savePrefString("never_say", neverSayInput.text.toString().trim())
                val customPlatform = customPlatformInput.text.toString().trim()
                savePrefString("custom_platform", customPlatform)
                val allPlatforms = selectedPlatforms.toMutableList()
                if (customPlatform.isNotEmpty()) allPlatforms.add(customPlatform)
                savePrefString("platforms", allPlatforms.joinToString(", "))
                showScreen3DCreatorLanguage()
            },
            onSkip = {
                savePrefString("custom_platform", customPlatformInput.text.toString().trim())
                showScreen3DCreatorLanguage()
            }
        )
    }

    private fun showScreen3DCreatorLanguage() {
        currentScreen = 33
        val root = screenRoot()
        val selections = loadStyleSelections()
        root.addView(progressLabel(4, 4))
        root.addView(title("Language preferences"))
        addLanguageSection(root, selections)
        addNavRow(root,
            onBack = { showScreen3CCreatorContent() },
            onNext = {
                saveStyleSelections(selections)
                finishEditOrPermissions()
            },
            onSkip = { finishEditOrPermissions() }
        )
    }

    // ── Business Screen 3 ──

    private fun showScreen3Business() {
        currentScreen = 3
        val root = screenRoot()
        root.addView(progressLabel(1, 4))
        root.addView(title("Set up your business profile"))
        root.addView(subtitle("Used so AI replies represent your business well."))

        val nameInput = darkEditText("e.g. Sunrise Salon", value = SettingsManager.getUserName(this))
        addFormField(root, "Business name", nameInput)

        root.addView(fieldLabel("Service type"))
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@OnboardingActivity, android.R.layout.simple_spinner_dropdown_item, BUSINESS_TYPES)
        }
        root.addView(typeSpinner, fieldMarginParams())

        val replyInput = darkEditText(
            "",
            lines = 3,
            value = SettingsManager.getRateCardMessage(this).ifBlank { BUSINESS_DEFAULT_REPLY }
        )
        addFormField(root, "Default reply", replyInput)

        addNavRow(root,
            onBack = if (editMode) null else ({ showScreen2() }),
            onNext = {
                SettingsManager.setUserName(this, nameInput.text.toString().trim())
                SettingsManager.setUserNiche(this, typeSpinner.selectedItem?.toString() ?: "Other")
                SettingsManager.setRateCardMessage(this, replyInput.text.toString().trim())
                showScreen3BBusinessDetails()
            },
            onSkip = { showScreen3BBusinessDetails() }
        )
    }

    private fun showScreen3BBusinessDetails() {
        currentScreen = 31
        val root = screenRoot()
        root.addView(progressLabel(2, 4))
        root.addView(title("Tell us about your business"))

        val locationInput = darkEditText("e.g. Dallas, TX", value = getPrefString("business_location"))
        addFormField(root, "City / Location", locationInput)

        val hoursInput = darkEditText("e.g. Mon-Sat 9am-6pm", value = getPrefString("business_hours"))
        addFormField(root, "Business hours", hoursInput)

        val promiseInput = darkEditText(
            "e.g. We call back within 1 hour",
            value = getPrefString("response_promise")
        )
        addFormField(root, "Response time promise", promiseInput)

        addNavRow(root,
            onBack = { showScreen3Business() },
            onNext = {
                savePrefString("business_location", locationInput.text.toString().trim())
                savePrefString("business_hours", hoursInput.text.toString().trim())
                savePrefString("response_promise", promiseInput.text.toString().trim())
                showScreen3BBusinessServices()
            },
            onSkip = { showScreen3BBusinessServices() }
        )
    }

    private fun showScreen3BBusinessServices() {
        currentScreen = 32
        val root = screenRoot()
        root.addView(progressLabel(3, 4))
        root.addView(title("What do you offer?"))

        val servicesInput = darkEditText(
            "e.g. leak repair, water heater, drain cleaning",
            lines = 2,
            value = getPrefString("business_services")
        )
        addFormField(root, "Your main services", servicesInput)

        val questionsInput = darkEditText(
            "e.g. pricing, availability, emergency service",
            lines = 2,
            value = getPrefString("common_questions")
        )
        addFormField(root, "Questions you get most", questionsInput)

        val neverSayInput = darkEditText(
            "e.g. never quote prices without seeing the job",
            lines = 2,
            value = getPrefString("never_say")
        )
        addFormField(root, "Things AI should never say", neverSayInput)

        addNavRow(root,
            onBack = { showScreen3BBusinessDetails() },
            onNext = {
                savePrefString("business_services", servicesInput.text.toString().trim())
                savePrefString("common_questions", questionsInput.text.toString().trim())
                savePrefString("never_say", neverSayInput.text.toString().trim())
                showScreen3DBusinessCommunication()
            },
            onSkip = { showScreen3DBusinessCommunication() }
        )
    }

    private fun showScreen3DBusinessCommunication() {
        currentScreen = 33
        val root = screenRoot()
        val selections = loadStyleSelections()
        root.addView(progressLabel(4, 4))
        root.addView(title("Communication Style"))
        root.addView(subtitle("How should AI reply on your behalf?"))
        addStyleEmojiLengthLanguage(root, selections, includeLanguage = true)
        addNavRow(root,
            onBack = { showScreen3BBusinessServices() },
            onNext = {
                saveStyleSelections(selections)
                finishEditOrPermissions()
            },
            onSkip = { finishEditOrPermissions() }
        )
    }

    // ── Personal Screen 3B ──

    private fun showScreen3BPersonalStyle() {
        currentScreen = 31
        val root = screenRoot()
        val selections = loadStyleSelections()
        root.addView(progressLabel(1, 1))
        root.addView(title("Your Style"))
        root.addView(subtitle("Help AI match how you naturally reply"))
        addStyleEmojiLengthLanguage(root, selections, includeLanguage = true)
        addNavRow(root,
            onBack = if (editMode) null else ({ showScreen2() }),
            onNext = {
                saveStyleSelections(selections)
                finishEditOrPermissions()
            },
            onSkip = { finishEditOrPermissions() }
        )
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

        fun permissionRow(label: String, granted: Boolean, onClick: () -> Unit): MaterialCardView {
            val card = MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = dp(2).toFloat()
                strokeWidth = dp(1)
                strokeColor = if (granted) ACCENT else STROKE
                setCardBackgroundColor(if (granted) 0xFFFFE0B2.toInt() else CARD)
                setOnClickListener { if (!granted) onClick() }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(28, 28, 28, 28)
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(TEXT)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = if (granted) "✓ Granted" else "Grant →"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (granted) 0xFF4ADE80.toInt() else ACCENT)
            })
            card.addView(row)
            return card
        }

        listOf(
            permissionRow("🪟 Display over other apps", overlayOk) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            permissionRow("♿ Accessibility (gestures)", a11yOk) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            permissionRow("🔔 Notification access (DMs)", notifOk) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        ).forEach { row ->
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
