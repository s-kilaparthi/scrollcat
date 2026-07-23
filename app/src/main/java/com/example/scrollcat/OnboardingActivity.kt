package com.example.scrollcat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.content.res.ColorStateList
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
        private const val ACCENT = 0xFFB39DDB.toInt()
        private const val BG = 0xFF1A1A1E.toInt()
        private const val CARD = 0xFF25252C.toInt()
        private const val STROKE = 0x556B6578
        private const val TEXT = 0xFFF5F3F7.toInt()
        private const val MUTED = 0xFFA39BB0.toInt()
        private const val INPUT_BG = 0xFF2E2A3A.toInt()
        private const val HINT_COLOR = 0xFFA39BB0.toInt()
        private const val ACCENT_SOFT = 0xFF4A3F6B.toInt()
        private const val ONBOARDING_STEPS = 4
        /** Meet ScrollCat — idle/curious. */
        private const val HERO_WELCOME = 84
        /** Summon the Cat — alert/excited. */
        private const val HERO_SUMMON = 63
        /** Notification access — happy/content. */
        private const val HERO_PERMISSIONS = 105
        /** Connect AI — neutral/waiting. */
        private const val HERO_CONNECT_AI = 69
        /** Final "A couple more things" — same as welcome. */
        private const val HERO_FINAL = 84
        private const val HERO_SIZE_DP = 148

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
    private val demoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var container: ScrollView

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun hasNotificationAccess(): Boolean = CatNotificationListener.instance != null

    /** Both permissions already granted — skip demo/grant screen straight to Connect AI. */
    private fun shouldSkipDemoGrantScreen(): Boolean =
        hasOverlayPermission() && hasNotificationAccess()

    private fun isDemoCompleted(): Boolean =
        SettingsManager.isOnboardingDemoCompleted(this)

    /** Pick the right onboarding step after recreate, resume, or permission changes. */
    private fun resumeOnboardingFlow() {
        if (shouldSkipDemoGrantScreen()) {
            showScreen3()
            return
        }
        val step = loadOnboardingStep()
        currentScreen = step
        when (step) {
            1 -> if (isDemoCompleted()) showScreen2() else showScreen1()
            2 -> showScreen2()
            3 -> showScreen3()
            4 -> showScreen4()
            else -> showScreen1()
        }
    }

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

    private fun saveOnboardingStep(step: Int) {
        prefs().edit().putInt("onboarding_step", step).apply()
    }

    private fun loadOnboardingStep(): Int =
        prefs().getInt("onboarding_step", 1).coerceIn(1, ONBOARDING_STEPS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editMode = intent.getBooleanExtra("edit_mode", false)
        container = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
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
        resumeOnboardingFlow()
    }

    override fun onResume() {
        super.onResume()
        if (editMode) return
        resumeOnboardingFlow()
    }

    /**
     * Builds a full-height onboarding scaffold: progress dots on top, then a
     * vertically centered content column so short screens don't leave a large void.
     * Pass [centerVertically] = false for keyboard-heavy screens (e.g. Connect AI)
     * so the ScrollView can scroll the focused field above the soft keyboard.
     */
    private fun screenRoot(step: Int = -1, centerVertically: Boolean = true): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        container.removeAllViews()
        container.addView(
            outer,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                if (centerVertically) {
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                } else {
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                }
            )
        )
        ViewCompat.setOnApplyWindowInsetsListener(outer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                dp(24),
                bars.top + dp(16),
                dp(24),
                dp(24) + maxOf(bars.bottom, ime.bottom)
            )
            insets
        }

        if (step in 1..ONBOARDING_STEPS) {
            outer.addView(progressDots(step))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        if (centerVertically) {
            val topSpacer = View(this)
            outer.addView(
                topSpacer,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            outer.addView(
                content,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            val bottomSpacer = View(this)
            outer.addView(
                bottomSpacer,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        } else {
            outer.addView(
                content,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        outer.tag = content
        return content
    }

    /** Legacy edit-mode screens still use a simple non-centered root. */
    private fun editScreenRoot(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
            setBackgroundColor(BG)
        }
        container.removeAllViews()
        container.addView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                dp(24),
                bars.top + dp(24),
                dp(24),
                dp(24) + maxOf(bars.bottom, ime.bottom)
            )
            insets
        }
        return root
    }

    private fun progressDots(currentStep: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
            for (i in 1..ONBOARDING_STEPS) {
                val active = i == currentStep
                val size = if (active) dp(10) else dp(8)
                addView(View(this@OnboardingActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (active) {
                            setColor(ACCENT)
                        } else {
                            setColor(Color.TRANSPARENT)
                            setStroke(dp(1), 0x886B6578.toInt())
                        }
                    }
                }, LinearLayout.LayoutParams(size, size).apply {
                    marginStart = dp(5)
                    marginEnd = dp(5)
                })
            }
        }
    }

    private fun progressLabel(step: Int, total: Int) = TextView(this).apply {
        text = "Step $step of $total"
        textSize = 13f
        setTextColor(ACCENT)
        typeface = UiKit.headingTypeface(this@OnboardingActivity)
        setPadding(0, 0, 0, 16)
    }

    private fun heroIllustration(frameIndex: Int, sizeDp: Int = HERO_SIZE_DP): ImageView {
        val size = dp(sizeDp)
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "ScrollCat"
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(20)
                topMargin = dp(4)
            }
            try {
                assets.open("sprites/%04d.webp".format(frameIndex)).use { stream ->
                    setImageBitmap(BitmapFactory.decodeStream(stream))
                }
            } catch (_: Exception) {
                // Fallback: keep empty rather than showing a stray cropped deco
            }
        }
    }

    private fun dismissFloatingCat() {
        demoHandler.removeCallbacksAndMessages(null)
        try {
            startService(
                Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_DISMISS)
            )
        } catch (_: Exception) { }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 24f
        typeface = UiKit.headingTypeface(this@OnboardingActivity)
        setTextColor(TEXT)
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 0, 0, dp(12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun subtitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(MUTED)
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 0, 0, dp(20))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun fieldLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(MUTED)
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
        setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) scrollFieldIntoView(v)
        }
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
                maxLines = 1
                minHeight = 0
            }
            background = null
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) scrollFieldIntoView(v)
            }
        }
        val layout = TextInputLayout(
            ContextThemeWrapper(
                this,
                if (lines > 1) {
                    com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox
                } else {
                    com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox_Dense
                }
            )
        ).apply {
            hint = label
            placeholderText = placeholder
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxBackgroundColor(CARD)
            setBoxStrokeColorStateList(ColorStateList.valueOf(ACCENT))
            defaultHintTextColor = ColorStateList.valueOf(MUTED)
            setHintTextColor(ColorStateList.valueOf(MUTED))
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        layout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return layout to input
    }

    /** Compact single-line outlined field used on the Connect AI screen. */
    private fun compactKeyField(hint: String): Pair<TextInputLayout, TextInputEditText> {
        val input = TextInputEditText(this).apply {
            this.hint = hint
            setHintTextColor(HINT_COLOR)
            setTextColor(TEXT)
            textSize = 14f
            setSingleLine(true)
            maxLines = 1
            minHeight = 0
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = null
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) scrollFieldIntoView(v)
            }
        }
        val layout = TextInputLayout(
            ContextThemeWrapper(
                this,
                com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox_Dense
            )
        ).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            setBoxBackgroundColor(CARD)
            setBoxStrokeColorStateList(ColorStateList.valueOf(ACCENT))
            defaultHintTextColor = ColorStateList.valueOf(MUTED)
            setHintTextColor(ColorStateList.valueOf(MUTED))
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        return layout to input
    }

    private fun scrollFieldIntoView(field: View) {
        field.post {
            val rect = android.graphics.Rect(0, 0, field.width, field.height + dp(48))
            field.requestRectangleOnScreen(rect, true)
        }
        if (::container.isInitialized) {
            container.post {
                val loc = IntArray(2)
                field.getLocationOnScreen(loc)
                val containerLoc = IntArray(2)
                container.getLocationOnScreen(containerLoc)
                val relativeTop = loc[1] - containerLoc[1]
                val target = (container.scrollY + relativeTop - dp(24)).coerceAtLeast(0)
                container.smoothScrollTo(0, target)
            }
        }
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
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) scrollFieldIntoView(v)
            }
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
                intArrayOf(ACCENT_SOFT, CARD)
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
        backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT_SOFT)
        cornerRadius = dp(24)
        minHeight = dp(52)
        insetTop = 0
        insetBottom = 0
        setPadding(24, 20, 24, 20)
        setOnClickListener { onClick() }
    }

    /** Light outlined helper for setup steps — visually below primary Connect. */
    private fun outlinedHelperButton(label: String, onClick: () -> Unit) = MaterialButton(
        ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_Button_OutlinedButton)
    ).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(ACCENT)
        strokeColor = ColorStateList.valueOf(ACCENT)
        strokeWidth = dp(1)
        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        cornerRadius = dp(20)
        minHeight = dp(40)
        insetTop = 0
        insetBottom = 0
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { onClick() }
    }

    private fun accentTextLink(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(ACCENT)
        paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, dp(4))
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
        onSkip: (() -> Unit)? = null,
        nextLabel: String = "Next"
    ) {
        if (onBack != null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(secondaryButton("Back", onBack), LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = 8 })
            row.addView(primaryButton(nextLabel, onNext), LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = 8 })
            root.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 32, 0, 0) })
        } else {
            root.addView(primaryButton(nextLabel, onNext), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 32, 0, 0) })
        }
        if (onSkip != null) {
            root.addView(skipLink(onSkip))
        }
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
                typeface = UiKit.headingTypeface(this@OnboardingActivity)
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
                backgroundTintList = android.content.res.ColorStateList.valueOf(if (key == selectedKey) ACCENT_SOFT else CARD)
                setTextColor(if (key == selectedKey) TEXT else MUTED)
                setPadding(24, 18, 24, 18)
                setOnClickListener {
                    onSelect(key)
                    chips.forEachIndexed { i, c ->
                        val k = options[i].second
                        c.setTextColor(if (k == key) TEXT else MUTED)
                        c.backgroundTintList = android.content.res.ColorStateList.valueOf(if (k == key) ACCENT_SOFT else CARD)
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
        val predefined = LANGUAGE_OPTIONS.filter { it != "Other" }
        val isCustomSaved = selections.primaryLanguage !in predefined
        var selectedChip = if (isCustomSaved) "Other" else selections.primaryLanguage
        var summary: TextView? = null

        fun summaryText(): String {
            val base = if (selections.matchLanguage) {
                "AI will reply in the message's language"
            } else {
                "AI will reply in ${selections.primaryLanguage}"
            }
            return "$base. It may occasionally make mistakes."
        }

        val (customLangLayout, customLangInput) = outlinedEditText(
            label = "Enter language name",
            placeholder = "e.g. German, Japanese",
            value = if (isCustomSaved) selections.primaryLanguage else ""
        )
        customLangLayout.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        customLangInput.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        customLangInput.setSingleLine(true)

        fun confirmCustomLanguage() {
            val typed = customLangInput.text?.toString()?.trim().orEmpty()
            if (typed.isBlank()) {
                Toast.makeText(this, "Enter a language name", Toast.LENGTH_SHORT).show()
                return
            }
            selections.primaryLanguage = typed
            SettingsManager.setPrimaryLanguage(this, typed)
            summary?.text = summaryText()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(customLangInput.windowToken, 0)
            Toast.makeText(this, "Language set to $typed", Toast.LENGTH_SHORT).show()
        }

        customLangInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                confirmCustomLanguage()
                true
            } else {
                false
            }
        }
        customLangInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (selectedChip == "Other") {
                    selections.primaryLanguage = s?.toString()?.trim().orEmpty().ifBlank { "Other" }
                    summary?.text = summaryText()
                }
            }
        })

        val addLangBtn = MaterialButton(this).apply {
            text = "Add"
            textSize = 13f
            isAllCaps = false
            setTextColor(TEXT)
            backgroundTintList = ColorStateList.valueOf(ACCENT_SOFT)
            cornerRadius = dp(20)
            minHeight = dp(48)
            insetTop = 0
            insetBottom = 0
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            setOnClickListener { confirmCustomLanguage() }
        }

        val customLangRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (selectedChip == "Other") View.VISIBLE else View.GONE
        }
        customLangRow.addView(customLangLayout)
        customLangRow.addView(addLangBtn)

        LANGUAGE_OPTIONS.forEach { lang ->
            val chip = filterChip(lang, lang == selectedChip) { checked ->
                if (!checked) return@filterChip
                selectedChip = lang
                if (lang == "Other") {
                    customLangRow.visibility = View.VISIBLE
                    selections.primaryLanguage =
                        customLangInput.text?.toString()?.trim().orEmpty().ifBlank { "Other" }
                } else {
                    customLangRow.visibility = View.GONE
                    selections.primaryLanguage = lang
                }
                summary?.text = summaryText()
            }
            langGroup.addView(chip, ChipGroup.LayoutParams(
                ChipGroup.LayoutParams.WRAP_CONTENT,
                ChipGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(langGroup, fieldMarginParams())
        root.addView(customLangRow, fieldMarginParams())

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
        SettingsManager.setPrimaryLanguage(this, selections.primaryLanguage)
        SettingsManager.setMatchLanguageEnabled(this, selections.matchLanguage)
    }

    private fun loadStyleSelections(): StyleSelections {
        return StyleSelections().apply {
            writingStyle = getPrefString("writing_style", "casual")
            emojiUsage = getPrefString("emoji_usage", "sometimes")
            replyLength = getPrefString("reply_length", "short")
            primaryLanguage = SettingsManager.getPrimaryLanguage(this@OnboardingActivity)
            matchLanguage = SettingsManager.isMatchLanguageEnabled(this@OnboardingActivity)
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


    private fun tipCard(text: String): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = STROKE
            setCardBackgroundColor(CARD)
        }
        card.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(TEXT)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        })
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(12)) }
        return card
    }

    private fun permissionRow(
        emoji: String,
        label: String,
        granted: Boolean,
        onClick: () -> Unit
    ): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(2).toFloat()
            strokeWidth = dp(1)
            strokeColor = if (granted) ACCENT else STROKE
            setCardBackgroundColor(if (granted) ACCENT_SOFT else CARD)
            setOnClickListener { if (!granted) onClick() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(18), dp(16))
        }

        val iconSize = dp(48)
        val iconWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = dp(14)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x55B39DDB)
            }
        }
        iconWrap.addView(TextView(this).apply {
            text = emoji
            textSize = 22f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })
        row.addView(iconWrap)

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
        saveOnboardingStep(1)
        dismissFloatingCat()
        val root = screenRoot(1)
        root.addView(heroIllustration(HERO_WELCOME))
        root.addView(title("Meet ScrollCat"))
        root.addView(subtitle(
            "Your floating cat companion. It scrolls for you, watches your " +
                "notifications, and helps you reply to DMs without leaving what you're doing."
        ))

        val overlayOk = Settings.canDrawOverlays(this)
        root.addView(permissionRow("🪟", "Display over other apps", overlayOk) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(4), 0, dp(20)) })

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
                setPadding(0, dp(16), 0, 0)
            })
        }
    }

    // ── Screen 2: Summon + Demo + Notification Access ──

    private fun showScreen2() {
        currentScreen = 2
        saveOnboardingStep(2)
        // Permissions already granted — skip demo/grant UI entirely.
        if (shouldSkipDemoGrantScreen()) {
            showScreen3()
            return
        }

        val demoDone = isDemoCompleted()
        // After a completed demo the floating overlay cat peeks over the UI — dismiss it
        // so only the deliberate hero illustration remains. Summon brings it back.
        if (demoDone) dismissFloatingCat()

        val root = screenRoot(2)
        root.addView(heroIllustration(HERO_SUMMON))
        root.addView(title("Summon the Cat"))
        root.addView(subtitle(
            "Summon your AI cat companion — we'll send a test message right after so you can see how it works."
        ))

        root.addView(primaryButton("Summon the Cat 🐱") {
            summonCatThenStartDemo()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(4), 0, 0) })

        if (demoDone) {
            root.addView(TextView(this).apply {
                text = "Want to try the demo again? Tap Summon the Cat again."
                textSize = 13f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(28), 0, dp(16))
            })
            root.addView(title("Loved that? Grant access for real Smart Replies"))
            root.addView(subtitle(
                "Notification access lets the cat spot DMs and suggest replies. " +
                    "You can enable Accessibility later in Settings if you want scroll gestures."
            ))
            root.addView(primaryButton("Grant Access") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, 0) })
            root.addView(TextView(this).apply {
                text = "Return here after granting — we'll continue automatically."
                textSize = 13f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
            })
        } else {
            root.addView(TextView(this).apply {
                text = "After the badge appears, tap the cat to try a reply"
                textSize = 13f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(28), 0, 0)
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
                        // Flag is persisted in ReplyPanel; refresh UI if still on this step.
                        if (currentScreen == 2 || !isFinishing) {
                            showScreen2()
                        }
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

    // ── Screen 3: Connect AI ──

    private fun showScreen3() {
        currentScreen = 3
        saveOnboardingStep(3)
        dismissFloatingCat()
        // Top-aligned + scrollable so the soft keyboard doesn't cover input fields
        val root = screenRoot(3, centerVertically = false)
        root.addView(heroIllustration(HERO_CONNECT_AI, sizeDp = 100))
        root.addView(title("Connect AI for Smart Replies"))
        root.addView(subtitle(
            "Get a free API key from Groq to unlock instant AI replies."
        ))

        var useGroq = true

        val groqHelperSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        groqHelperSection.addView(outlinedHelperButton("Step 1: Sign in to Groq") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com")))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(8)) })
        groqHelperSection.addView(outlinedHelperButton("Step 2: Create your API key") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(8)) })
        groqHelperSection.addView(TextView(this).apply {
            text = "Click Generate Key, then paste it below."
            textSize = 14f
            setTextColor(MUTED)
            setPadding(0, dp(4), 0, dp(8))
        })
        root.addView(groqHelperSection)

        val customFieldsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val (nameLayout, nameInput) = outlinedEditText(
            "Provider name",
            "e.g. OpenAI, Claude, My custom endpoint"
        )
        nameInput.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) scrollFieldIntoView(v)
        }
        customFieldsSection.addView(nameLayout, fieldMarginParams())
        root.addView(customFieldsSection)

        val (keyLayout, keyInput) = compactKeyField("Paste your API key here")
        root.addView(keyLayout, fieldMarginParams())

        val providerToggleLink = accentTextLink(
            "Already have an API key from another provider?"
        ) { }

        fun showGroqFlow() {
            useGroq = true
            groqHelperSection.visibility = View.VISIBLE
            customFieldsSection.visibility = View.GONE
            providerToggleLink.text = "Already have an API key from another provider?"
        }

        fun showCustomFlow() {
            useGroq = false
            groqHelperSection.visibility = View.GONE
            customFieldsSection.visibility = View.VISIBLE
            providerToggleLink.text = "Use Groq instead"
            scrollFieldIntoView(nameInput)
        }

        providerToggleLink.setOnClickListener {
            if (useGroq) showCustomFlow() else showGroqFlow()
        }
        root.addView(providerToggleLink)

        root.addView(primaryButton("Connect") {
            if (useGroq) {
                val key = keyInput.text?.toString()?.trim().orEmpty()
                if (key.isEmpty()) {
                    Toast.makeText(this, "Paste your Groq API key first", Toast.LENGTH_SHORT).show()
                    return@primaryButton
                }
                AiProviderActivity.saveGroqApiKey(this, key)
                Toast.makeText(this, "Groq connected!", Toast.LENGTH_SHORT).show()
            } else {
                val name = nameInput.text?.toString()?.trim().orEmpty()
                val key = keyInput.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Enter a provider name", Toast.LENGTH_SHORT).show()
                    return@primaryButton
                }
                if (key.isEmpty()) {
                    Toast.makeText(this, "Paste your API key first", Toast.LENGTH_SHORT).show()
                    return@primaryButton
                }
                AiProviderActivity.saveCustomNamedProvider(this, name, key)
                Toast.makeText(this, "Provider connected!", Toast.LENGTH_SHORT).show()
            }
            showScreen4()
        })

        root.addView(TextView(this).apply {
            text = "We recommend not skipping — you'll miss Smart Replies, our best feature."
            textSize = 13f
            setTextColor(ACCENT)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        })

        root.addView(MaterialButton(this).apply {
            text = "Skip for now"
            textSize = 14f
            isAllCaps = false
            setTextColor(MUTED)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setOnClickListener { showScreen4() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(4), 0, 0) })
    }

    // ── Screen 4: Done (+ quick feature notes) ──

    private fun showScreen4() {
        currentScreen = 4
        saveOnboardingStep(4)
        dismissFloatingCat()
        ReplyStore.clearDemo()
        OverlayService.instance?.clearOnboardingDemoCallback()
        val root = screenRoot(4)
        root.addView(heroIllustration(HERO_FINAL))
        root.addView(title("A couple more things"))
        root.addView(tipCard("Auto-Reply Rules: instantly reply to specific keywords, no AI needed."))
        root.addView(tipCard("Smart Notifications: flag messages from specific people or keywords so they're always shown first."))
        root.addView(TextView(this).apply {
            text = "Both can be set up anytime later in Settings / Smart Notifications — not required now."
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, 0, 0, 32)
        })
        root.addView(primaryButton("Get Started") {
            SettingsManager.setOnboardingComplete(this, true)
            prefs().edit().remove("onboarding_step").apply()
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
        val root = editScreenRoot()
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
            onSkip = if (editMode) null else ({ showScreen3BCreatorWritingStyle() })
        )
    }

    private fun showScreen3BCreatorWritingStyle() {
        currentScreen = 31
        val root = editScreenRoot()
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
            onSkip = if (editMode) null else ({ showScreen3CCreatorContent() })
        )
    }

    private fun showScreen3CCreatorContent() {
        currentScreen = 32
        val root = editScreenRoot()
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
            onSkip = if (editMode) null else ({
                savePrefString("custom_platform", customPlatformInput.text.toString().trim())
                showScreen3DCreatorLanguage()
            })
        )
    }

    private fun showScreen3DCreatorLanguage() {
        currentScreen = 33
        val root = editScreenRoot()
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
            onSkip = if (editMode) null else ({ finishEditOrPermissions() }),
            nextLabel = if (editMode) "Save" else "Next"
        )
    }

    // ── Business Screen 3 ──

    private fun showScreen3Business() {
        currentScreen = 3
        val root = editScreenRoot()
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
            onSkip = if (editMode) null else ({ showScreen3BBusinessDetails() })
        )
    }

    private fun showScreen3BBusinessDetails() {
        currentScreen = 31
        val root = editScreenRoot()
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
            onSkip = if (editMode) null else ({ showScreen3BBusinessServices() })
        )
    }

    private fun showScreen3BBusinessServices() {
        currentScreen = 32
        val root = editScreenRoot()
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
            onSkip = if (editMode) null else ({ showScreen3DBusinessCommunication() })
        )
    }

    private fun showScreen3DBusinessCommunication() {
        currentScreen = 33
        val root = editScreenRoot()
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
            onSkip = if (editMode) null else ({ finishEditOrPermissions() }),
            nextLabel = if (editMode) "Save" else "Next"
        )
    }

    // ── Personal Screen 3B ──

    private fun showScreen3BPersonalStyle() {
        currentScreen = 31
        val root = editScreenRoot()
        val selections = loadStyleSelections()
        root.addView(title("Your Style"))
        root.addView(subtitle("Help AI match how you naturally reply"))
        addStyleEmojiLengthLanguage(root, selections, includeLanguage = true)
        addNavRow(root,
            onBack = if (editMode) null else ({ showScreen2() }),
            onNext = {
                saveStyleSelections(selections)
                finishEditOrPermissions()
            },
            onSkip = if (editMode) null else ({ finishEditOrPermissions() }),
            nextLabel = if (editMode) "Save" else "Next"
        )
    }

}
