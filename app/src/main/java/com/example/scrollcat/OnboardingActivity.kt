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
import android.content.res.ColorStateList
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * First-launch onboarding: welcome/overlay → summon+demo → real permissions → Groq key → done.
 * Profile personalization screens remain for AiSettingsActivity edit_mode only.
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
    private var demoReplyCompleted = false
    private val demoHandler = android.os.Handler(android.os.Looper.getMainLooper())
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
        if (editMode) return
        when (currentScreen) {
            1 -> showScreen1()
            3 -> showScreen3()
        }
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

    private fun outlinedEditText(label: String, placeholder: String, lines: Int = 1, value: String = ""): Pair<TextInputLayout, EditText> {
        val input = TextInputEditText(this).apply {
            setHintTextColor(HINT_COLOR)
            setTextColor(TEXT)
            textSize = 15f
            if (value.isNotEmpty()) setText(value)
            if (lines > 1) {
                minLines = lines
                gravity = Gravity.TOP or Gravity.START
                setSingleLine(false)
            } else {
                setSingleLine(true)
            }
            background = null
            setPadding(0, 0, 0, 0)
        }
        val layout = TextInputLayout(
            ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox)
        ).apply {
            hint = label
            placeholderText = placeholder
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxBackgroundColor(CARD)
            setBoxStrokeColorStateList(ColorStateList.valueOf(ACCENT))
            defaultHintTextColor = ColorStateList.valueOf(MUTED)
            setHintTextColor(ColorStateList.valueOf(MUTED))
            addView(input)
        }
        return layout to input
    }

    private fun addOutlinedField(root: LinearLayout, label: String, placeholder: String, lines: Int = 1, value: String = ""): EditText {
        val (layout, input) = outlinedEditText(label, placeholder, lines, value)
        root.addView(layout, fieldMarginParams())
        return input
    }

    private fun exposedDropdown(label: String, options: List<String>, initial: String = options.first()): Pair<TextInputLayout, AutoCompleteTextView> {
        val dropdown = AutoCompleteTextView(this).apply {
            setAdapter(ArrayAdapter(this@OnboardingActivity, android.R.layout.simple_dropdown_item_1line, options))
            setText(initial.ifBlank { options.first() }, false)
            setTextColor(TEXT)
            setHintTextColor(HINT_COLOR)
            textSize = 15f
            threshold = 0
            background = null
            setOnClickListener { showDropDown() }
        }
        val layout = TextInputLayout(
            ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox)
        ).apply {
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            setBoxBackgroundColor(CARD)
            setBoxStrokeColorStateList(ColorStateList.valueOf(ACCENT))
            defaultHintTextColor = ColorStateList.valueOf(MUTED)
            setHintTextColor(ColorStateList.valueOf(MUTED))
            addView(dropdown)
        }
        return layout to dropdown
    }

    private fun wrappingChipGroup() = ChipGroup(this).apply {
        isSingleLine = false
        setChipSpacingHorizontal(dp(8))
        setChipSpacingVertical(dp(8))
    }

    private fun filterChip(label: String, checked: Boolean, onChecked: (Boolean) -> Unit): Chip {
        return Chip(ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_Chip_Filter)).apply {
            text = label
            isCheckable = true
            isChecked = checked
            chipBackgroundColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFFFFE0B2.toInt(), CARD)
            )
            chipStrokeColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ACCENT, STROKE)
            )
            chipStrokeWidth = dp(1).toFloat()
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(TEXT, MUTED)
            ))
            setOnCheckedChangeListener { _, isChecked -> onChecked(isChecked) }
        }
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
        val langGroup = wrappingChipGroup().apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val langViews = mutableListOf<Chip>()
        var summary: TextView? = null
        fun summaryText(): String {
            return if (selections.matchLanguage) {
                "AI will reply in the message's language"
            } else {
                "AI will reply in ${selections.primaryLanguage}"
            }
        }
        LANGUAGE_OPTIONS.forEach { lang ->
            val chip = filterChip(lang, lang == selections.primaryLanguage) { checked ->
                if (checked) {
                    selections.primaryLanguage = lang
                    summary?.text = summaryText()
                }
            }
            langViews.add(chip)
            langGroup.addView(chip, ChipGroup.LayoutParams(
                ChipGroup.LayoutParams.WRAP_CONTENT,
                ChipGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(langGroup, fieldMarginParams())

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
                summary?.text = summaryText()
            }
        })
        root.addView(toggleRow)
        summary = TextView(this).apply {
            text = summaryText()
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, 8, 0, 0)
        }
        root.addView(summary)
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
        Toast.makeText(this, "Profile updated!", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun permissionRow(label: String, granted: Boolean, onClick: () -> Unit): MaterialCardView {
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

    // ── Screen 1: Welcome + Overlay ──

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
                "notifications, and helps you reply to DMs without leaving what you're doing."
        ))

        val overlayOk = Settings.canDrawOverlays(this)
        root.addView(permissionRow("🪟 Display over other apps", overlayOk) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 10, 0, 24) })

        val next = primaryButton("Next") { showScreen2() }
        next.isEnabled = overlayOk
        next.alpha = if (overlayOk) 1f else 0.45f
        root.addView(next)
        if (!overlayOk) {
            root.addView(TextView(this).apply {
                text = "Grant overlay permission to continue"
                textSize = 13f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 0)
            })
        }
    }

    // ── Screen 2: Summon + Demo ──

    private fun showScreen2() {
        currentScreen = 2
        val root = screenRoot()
        root.addView(title("Summon the Cat"))
        root.addView(subtitle(
            "Summon your AI cat companion — we'll send a test message right after so you can see how it works."
        ))

        root.addView(primaryButton("Summon the Cat 🐱") {
            summonCatThenStartDemo()
        })

        if (demoReplyCompleted) {
            root.addView(TextView(this).apply {
                text = "Want to try the demo again? Tap Summon the Cat again."
                textSize = 13f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(0, 28, 0, 8)
            })
            root.addView(primaryButton("Next") { showScreen3() })
        } else {
            root.addView(TextView(this).apply {
                text = "After the badge appears, tap the cat to try a reply"
                textSize = 13f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(0, 28, 0, 0)
            })
        }
    }

    private fun summonCatThenStartDemo() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
            return
        }
        startForegroundService(
            Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SUMMON)
        )
        Toast.makeText(this, "Cat summoned!", Toast.LENGTH_SHORT).show()
        demoHandler.removeCallbacksAndMessages(null)
        demoHandler.postDelayed({
            val svc = OverlayService.instance
            if (svc == null) {
                Toast.makeText(this, "Cat is starting… tap Summon again", Toast.LENGTH_SHORT).show()
                return@postDelayed
            }
            svc.seedOnboardingDemo(
                onDemoPanelShown = null,
                onDemoReplySent = {
                    runOnUiThread {
                        demoReplyCompleted = true
                        if (currentScreen == 2) showScreen2()
                    }
                }
            )
            Toast.makeText(
                this,
                "Badge on the cat — tap it to open the demo reply",
                Toast.LENGTH_LONG
            ).show()
        }, 3000L)
    }

    // ── Screen 3: Real permissions ──

    private fun showScreen3() {
        currentScreen = 3
        val root = screenRoot()
        root.addView(title("Loved that? Let's make it work on your real messages."))
        root.addView(subtitle(
            "Accessibility lets the cat scroll. Notification access lets it spot DMs and suggest replies."
        ))

        val a11yOk = CatAccessibilityService.instance != null
        val notifOk = CatNotificationListener.instance != null

        root.addView(permissionRow("♿ Accessibility (gestures)", a11yOk) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 10, 0, 10) })

        root.addView(permissionRow("🔔 Notification access (DMs)", notifOk) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 10, 0, 10) })

        val both = a11yOk && notifOk
        val next = primaryButton("Next") { showScreen4() }
        next.isEnabled = both
        next.alpha = if (both) 1f else 0.45f
        root.addView(next, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 32, 0, 0) })
        if (!both) {
            root.addView(TextView(this).apply {
                text = "Grant both permissions to continue (then return here)"
                textSize = 13f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 0)
            })
        }
    }

    // ── Screen 4: Connect Groq ──

    private fun showScreen4() {
        currentScreen = 4
        val root = screenRoot()
        root.addView(title("Connect AI for Smart Replies"))
        root.addView(subtitle(
            "We need an API key for instant AI replies. Already have one? Paste it below. " +
                "Don't have one? Get a free key from Groq — no credit card needed."
        ))

        root.addView(secondaryButton("Step 1: Sign in to Groq") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com")))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 8, 0, 8) })

        root.addView(secondaryButton("Step 2: Create your API key") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 8, 0, 8) })

        root.addView(TextView(this).apply {
            text = "Click Generate Key, then paste it below."
            textSize = 14f
            setTextColor(MUTED)
            setPadding(0, 8, 0, 16)
        })

        val keyInput = TextInputEditText(this).apply {
            hint = "Paste your API key here"
            setHintTextColor(HINT_COLOR)
            setTextColor(TEXT)
            textSize = 15f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = null
            setPadding(0, 0, 0, 0)
        }
        val keyLayout = TextInputLayout(
            ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox)
        ).apply {
            hint = "Paste your API key here"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            setBoxBackgroundColor(CARD)
            setBoxStrokeColorStateList(ColorStateList.valueOf(ACCENT))
            defaultHintTextColor = ColorStateList.valueOf(MUTED)
            setHintTextColor(ColorStateList.valueOf(MUTED))
            addView(keyInput)
        }
        root.addView(keyLayout, fieldMarginParams())

        root.addView(primaryButton("Connect") {
            val key = keyInput.text?.toString()?.trim().orEmpty()
            if (key.isEmpty()) {
                Toast.makeText(this, "Paste your Groq API key first", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            saveGroqKey(key)
            Toast.makeText(this, "Groq connected!", Toast.LENGTH_SHORT).show()
            showScreen5()
        })

        root.addView(MaterialButton(this).apply {
            text = "Skip for now"
            textSize = 14f
            isAllCaps = false
            setTextColor(MUTED)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setOnClickListener { showScreen5() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 8, 0, 0) })
    }

    private fun saveGroqKey(key: String) {
        ApiKeyStore.setGroqApiKey(this, key)
        SettingsManager.setActiveAiProvider(
            this,
            "https://api.groq.com/openai/v1/chat/completions",
            "llama-3.1-8b-instant",
            key
        )
        // Keep providers list in sync with AiProviderActivity
        try {
            val prefs = getSharedPreferences("ai_providers", MODE_PRIVATE)
            val arr = org.json.JSONArray(prefs.getString("providers", "[]") ?: "[]")
            var found = false
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("id") == "groq_default" ||
                    obj.optString("name").lowercase().contains("groq")
                ) {
                    obj.put("apiKey", key)
                    obj.put("isActive", true)
                    obj.put("endpoint", "https://api.groq.com/openai/v1/chat/completions")
                    obj.put("model", "llama-3.1-8b-instant")
                    found = true
                } else {
                    obj.put("isActive", false)
                }
            }
            if (!found) {
                arr.put(org.json.JSONObject().apply {
                    put("id", "groq_default")
                    put("name", "Groq")
                    put("endpoint", "https://api.groq.com/openai/v1/chat/completions")
                    put("model", "llama-3.1-8b-instant")
                    put("apiKey", key)
                    put("isActive", true)
                })
            }
            prefs.edit()
                .putString("providers", arr.toString())
                .putString("active_id", "groq_default")
                .apply()
        } catch (_: Exception) { }
    }

    // ── Screen 5: Done ──

    private fun showScreen5() {
        currentScreen = 5
        ReplyStore.clearDemo()
        OverlayService.instance?.clearOnboardingDemoCallback()
        val root = screenRoot()
        root.addView(TextView(this).apply {
            text = "🎉"
            textSize = 72f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })
        root.addView(title("You're all set!"))
        root.addView(subtitle(
            "Find Auto-Reply Rules, Smart Notifications, and My AI Settings anytime from the dashboard. " +
                "You can personalize how AI sounds like you under My AI Settings."
        ))
        root.addView(primaryButton("Get Started") {
            SettingsManager.setOnboardingComplete(this, true)
            if (Settings.canDrawOverlays(this)) {
                startForegroundService(
            Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SUMMON)
        )
            }
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        })
    }

    // ── Creator Screen 3 ──

    private fun showScreen3Creator() {
        currentScreen = 3
        val root = screenRoot()
        root.addView(progressLabel(1, 4))
        root.addView(title("Set up your creator profile"))
        root.addView(subtitle("Used so AI replies sound like you."))

        val nameInput = addOutlinedField(
            root,
            "Your name",
            "e.g. Priya",
            value = SettingsManager.getUserName(this)
        )

        val (nicheLayout, nicheDropdown) = exposedDropdown(
            "Your niche",
            CREATOR_NICHES,
            SettingsManager.getUserNiche(this).ifBlank { CREATOR_NICHES.first() }
        )
        root.addView(nicheLayout, fieldMarginParams())

        val rateInput = addOutlinedField(
            root,
            "Rate card message",
            "e.g. My rates start at $50/photo 💕",
            lines = 3,
            value = SettingsManager.getRateCardMessage(this).ifBlank { CREATOR_RATE_CARD }
        )

        addNavRow(root,
            onBack = if (editMode) null else ({ showScreen2() }),
            onNext = {
                SettingsManager.setUserName(this, nameInput.text.toString().trim())
                SettingsManager.setUserNiche(this, nicheDropdown.text.toString().ifBlank { "Other" })
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

        val questionsInput = addOutlinedField(
            root,
            "What do people ask you most?",
            "e.g. camera gear, editing tips, collab requests",
            lines = 2,
            value = getPrefString("common_questions")
        )

        val neverSayInput = addOutlinedField(
            root,
            "What should AI never say?",
            "e.g. never give free shoutouts, never quote prices",
            lines = 2,
            value = getPrefString("never_say")
        )

        root.addView(fieldLabel("Your platforms"))
        val savedPlatforms = getPrefString("platforms").split(",")
            .map { it.trim() }.filter { it.isNotEmpty() }
        val selectedPlatforms = savedPlatforms.filter { it in PLATFORM_OPTIONS }.toMutableSet()
        val platformGroup = wrappingChipGroup()
        PLATFORM_OPTIONS.forEach { platform ->
            val chip = filterChip(platform, platform in selectedPlatforms) { checked ->
                if (checked) selectedPlatforms.add(platform) else selectedPlatforms.remove(platform)
            }
            platformGroup.addView(chip, ChipGroup.LayoutParams(
                ChipGroup.LayoutParams.WRAP_CONTENT,
                ChipGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(platformGroup, fieldMarginParams())

        val customPlatformInput = addOutlinedField(
            root,
            "Other platform",
            "e.g. Pinterest, Snapchat, Podcast...",
            value = getPrefString("custom_platform")
        )

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
        root.gravity = Gravity.CENTER_VERTICAL
        root.minimumHeight = (resources.displayMetrics.heightPixels - dp(160)).coerceAtLeast(0)
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

}
