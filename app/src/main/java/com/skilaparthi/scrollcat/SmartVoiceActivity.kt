package com.skilaparthi.scrollcat

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
 * Smart Voice settings: Language 1 (you speak) → recognition language,
 * Language 2 (text appears as) → optional ML Kit translation target.
 * Translate / Romanize are mutually exclusive output modes.
 * Language selections and Translate/Romanize toggles persist immediately.
 */
class SmartVoiceActivity : Activity() {

    companion object {
        private const val ADD_LANGUAGE = "Add language"
        private const val ACCENT = 0xFFB39DDB.toInt()
        private const val ACCENT_SOFT = 0xFF4A3F6B.toInt()
        private const val CARD = 0xFF25252C.toInt()
        private const val STROKE = 0x556B6578
        private const val TEXT = 0xFFF5F3F7.toInt()
        private const val MUTED = 0xFFA39BB0.toInt()
        private const val TIP_ACCENT = 0xFFCE93D8.toInt()

        val BASE_LANGUAGE_OPTIONS = listOf(
            "English", "Hindi", "Telugu", "Spanish", "Arabic", "French"
        )
    }

    private val materialContext: ContextThemeWrapper by lazy {
        ContextThemeWrapper(this, R.style.Theme_ScrollCat)
    }

    /** When true, Language 1 changes also push Language 2 to match (draft only). */
    private var syncLang2FromLang1 = true

    private var draftLang1 = "English"
    private var draftLang2 = "English"
    private val draftCustoms1 = mutableListOf<String>()
    private val draftCustoms2 = mutableListOf<String>()
    private var refreshLang2Ui: (() -> Unit)? = null
    private var lang2Card: MaterialCardView? = null
    private var translateSwitch: SwitchMaterial? = null
    private var romanizeSwitch: SwitchMaterial? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        draftLang1 = SettingsManager.getVoiceLanguage1(this)
        draftLang2 = SettingsManager.getVoiceLanguage2(this)
        draftCustoms1.clear()
        draftCustoms1.addAll(SettingsManager.getVoiceCustomLanguages1(this))
        draftCustoms2.clear()
        draftCustoms2.addAll(SettingsManager.getVoiceCustomLanguages2(this))
        pruneBaseLanguagesFromCustoms()
        ensureCustomListed(draftLang1, draftCustoms1)
        ensureCustomListed(draftLang2, draftCustoms2)
        syncLang2FromLang1 = draftLang1.equals(draftLang2, ignoreCase = true)

        val root = UiKit.pageRoot(this)
        addToolbar(root)
        root.addView(
            UiKit.body(
                this,
                "Choose what you speak into the mic, and what language appears in the reply box.",
                muted = true
            )
        )

        addOutputModeToggles(root)
        root.addView(
            UiKit.body(
                this,
                "Tip: Translate converts Language 1 → Language 2. Romanize keeps Language 1 but writes it in English letters (e.g. Telugu → emi chesthunnavu).",
                muted = true
            )
        )

        addLanguageSelector(
            parent = root,
            title = "Language 1 (You speak)",
            subtitle = "Speech recognition language for Voice to text / Continue",
            getValue = { draftLang1 },
            setValue = { selected ->
                draftLang1 = selected
                if (syncLang2FromLang1) {
                    ensureCustomListed(selected, draftCustoms2)
                    draftLang2 = selected
                    refreshLang2Ui?.invoke()
                }
                persistDraft(showToast = true)
            },
            getCustoms = { draftCustoms1.toList() },
            addCustom = { name -> ensureCustomListed(name, draftCustoms1) },
            onBindRefresh = null
        )

        lang2Card = addLanguageSelector(
            parent = root,
            title = "Language 2 (Text appears as)",
            subtitle = "When translate is on, mic text is converted to this language",
            getValue = { draftLang2 },
            setValue = { selected ->
                syncLang2FromLang1 = selected.equals(draftLang1, ignoreCase = true)
                draftLang2 = selected
                persistDraft(showToast = true)
            },
            getCustoms = { draftCustoms2.toList() },
            addCustom = { name -> ensureCustomListed(name, draftCustoms2) },
            onBindRefresh = { refresh -> refreshLang2Ui = refresh }
        )
        updateLang2Availability()

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(UiKit.surfaceColor(this@SmartVoiceActivity))
            addView(root)
        }
        setContentView(scrollView)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                UiKit.dp(this, 24),
                bars.top + UiKit.dp(this, 16),
                UiKit.dp(this, 24),
                UiKit.dp(this, 24) + maxOf(bars.bottom, ime.bottom)
            )
            insets
        }
    }

    private fun addOutputModeToggles(parent: LinearLayout) {
        UiKit.section(parent, "Output mode", "Translate and Romanize cannot be on at the same time") {
            val translateRow = modeToggleRow(
                iconRes = R.drawable.ic_translate,
                title = "Translate",
                subtitle = "Convert Language 1 → Language 2",
                checked = SettingsManager.isVoiceTranslateEnabled(this@SmartVoiceActivity)
            ) { enabled ->
                SettingsManager.setVoiceTranslateEnabled(this@SmartVoiceActivity, enabled)
                syncModeSwitchesFromPrefs()
                updateLang2Availability()
                Toast.makeText(this@SmartVoiceActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
            translateSwitch = translateRow.second
            addView(translateRow.first)

            addView(View(this@SmartVoiceActivity).apply {
                setBackgroundColor(STROKE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply {
                    topMargin = UiKit.dp(this@SmartVoiceActivity, 8)
                    bottomMargin = UiKit.dp(this@SmartVoiceActivity, 8)
                }
            })

            val romanizeRow = modeToggleRow(
                iconRes = R.drawable.ic_romanize,
                title = "Romanize",
                subtitle = "Same language, English letters (phonetic)",
                checked = SettingsManager.isVoiceRomanizeEnabled(this@SmartVoiceActivity)
            ) { enabled ->
                SettingsManager.setVoiceRomanizeEnabled(this@SmartVoiceActivity, enabled)
                syncModeSwitchesFromPrefs()
                updateLang2Availability()
                Toast.makeText(this@SmartVoiceActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
            romanizeSwitch = romanizeRow.second
            addView(romanizeRow.first)
        }
    }

    private fun modeToggleRow(
        iconRes: Int,
        title: String,
        subtitle: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ): Pair<LinearLayout, SwitchMaterial> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(TIP_ACCENT)
            layoutParams = LinearLayout.LayoutParams(
                UiKit.dp(this@SmartVoiceActivity, 28),
                UiKit.dp(this@SmartVoiceActivity, 28)
            ).apply { marginEnd = UiKit.dp(this@SmartVoiceActivity, 12) }
        })
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(TEXT)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(MUTED)
        })
        row.addView(textCol)
        val sw = SwitchMaterial(materialContext).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked ->
                if (tag == "syncing") return@setOnCheckedChangeListener
                onChanged(isChecked)
            }
        }
        row.addView(sw)
        return row to sw
    }

    private fun syncModeSwitchesFromPrefs() {
        val t = SettingsManager.isVoiceTranslateEnabled(this)
        val r = SettingsManager.isVoiceRomanizeEnabled(this)
        translateSwitch?.let { sw ->
            sw.tag = "syncing"
            sw.isChecked = t
            sw.tag = null
        }
        romanizeSwitch?.let { sw ->
            sw.tag = "syncing"
            sw.isChecked = r
            sw.tag = null
        }
    }

    /** Language 2 is irrelevant while Romanize is on (output stays L1, Latin-lettered). */
    private fun updateLang2Availability() {
        val romanizeOn = SettingsManager.isVoiceRomanizeEnabled(this)
        lang2Card?.alpha = if (romanizeOn) 0.4f else 1f
        setViewGroupInteractive(lang2Card, !romanizeOn)
    }

    private fun setViewGroupInteractive(view: View?, enabled: Boolean) {
        view ?: return
        view.isClickable = enabled
        view.isEnabled = enabled
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                setViewGroupInteractive(view.getChildAt(i), enabled)
            }
        }
    }

    private fun persistDraft(showToast: Boolean = false) {
        pruneBaseLanguagesFromCustoms()
        SettingsManager.setVoiceLanguage1(this, draftLang1)
        SettingsManager.setVoiceLanguage2(this, draftLang2)
        SettingsManager.setVoiceCustomLanguages1(this, draftCustoms1)
        SettingsManager.setVoiceCustomLanguages2(this, draftCustoms2)
        if (showToast) {
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
    }

    /** Drop customs that are now built-in chips (e.g. a previously added "Telugu"). */
    private fun pruneBaseLanguagesFromCustoms() {
        draftCustoms1.removeAll { custom ->
            BASE_LANGUAGE_OPTIONS.any { it.equals(custom, ignoreCase = true) }
        }
        draftCustoms2.removeAll { custom ->
            BASE_LANGUAGE_OPTIONS.any { it.equals(custom, ignoreCase = true) }
        }
    }

    private fun ensureCustomListed(language: String, target: MutableList<String>) {
        val name = language.trim()
        if (name.isEmpty() || name.equals(ADD_LANGUAGE, ignoreCase = true)) return
        if (BASE_LANGUAGE_OPTIONS.any { it.equals(name, ignoreCase = true) }) return
        val idx = target.indexOfFirst { it.equals(name, ignoreCase = true) }
        if (idx >= 0) {
            target[idx] = name
        } else {
            target.add(name)
        }
    }

    private fun addToolbar(root: LinearLayout) {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, UiKit.dp(this@SmartVoiceActivity, 16))
        }
        toolbar.addView(TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(UiKit.onSurfaceColor(this@SmartVoiceActivity))
            setPadding(0, 0, UiKit.dp(this@SmartVoiceActivity, 12), 0)
            setOnClickListener { finish() }
        })
        toolbar.addView(UiKit.headline(this, "Smart Voice"))
        root.addView(toolbar)
    }

    private fun addLanguageSelector(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        getValue: () -> String,
        setValue: (String) -> Unit,
        getCustoms: () -> List<String>,
        addCustom: (String) -> Unit,
        onBindRefresh: ((() -> Unit) -> Unit)?
    ): MaterialCardView {
        return UiKit.section(parent, title, subtitle) {
            val group = ChipGroup(materialContext).apply {
                isSingleSelection = true
                isSelectionRequired = true
                chipSpacingHorizontal = UiKit.dp(this@SmartVoiceActivity, 8)
                chipSpacingVertical = UiKit.dp(this@SmartVoiceActivity, 8)
            }

            val (customLangLayout, customLangInput) = underlinedLanguageInput()
            customLangLayout.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )

            val customLangRow = LinearLayout(this@SmartVoiceActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = UiKit.dp(this@SmartVoiceActivity, 8) }
            }

            fun selectableLanguages(): List<String> {
                val customs = getCustoms().filter { custom ->
                    custom.isNotBlank() &&
                        BASE_LANGUAGE_OPTIONS.none { it.equals(custom, ignoreCase = true) }
                }
                return BASE_LANGUAGE_OPTIONS + customs + ADD_LANGUAGE
            }

            fun showAddRow(show: Boolean) {
                customLangRow.visibility = if (show) View.VISIBLE else View.GONE
                if (show) customLangInput.requestFocus()
            }

            fun rebuildChips(selectedValue: String) {
                if (selectedValue.isNotBlank() &&
                    !selectedValue.equals(ADD_LANGUAGE, ignoreCase = true) &&
                    BASE_LANGUAGE_OPTIONS.none { it.equals(selectedValue, ignoreCase = true) }
                ) {
                    addCustom(selectedValue)
                }
                group.removeAllViews()
                val options = selectableLanguages()
                val match = options.firstOrNull {
                    it != ADD_LANGUAGE && it.equals(selectedValue, ignoreCase = true)
                }
                val selectedLabel = match ?: ADD_LANGUAGE

                options.forEach { lang ->
                    val chip = Chip(
                        ContextThemeWrapper(
                            this@SmartVoiceActivity,
                            com.google.android.material.R.style.Widget_Material3_Chip_Filter
                        )
                    ).apply {
                        text = lang
                        isCheckable = true
                        isChecked = lang == selectedLabel
                        chipBackgroundColor = ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(ACCENT_SOFT, CARD)
                        )
                        chipStrokeColor = ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(ACCENT, STROKE)
                        )
                        chipStrokeWidth = UiKit.dp(this@SmartVoiceActivity, 1).toFloat()
                        setTextColor(
                            ColorStateList(
                                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                                intArrayOf(TEXT, MUTED)
                            )
                        )
                        setOnCheckedChangeListener { _, checked ->
                            if (!checked) return@setOnCheckedChangeListener
                            if (lang == ADD_LANGUAGE) {
                                showAddRow(true)
                                customLangInput.setText("")
                            } else {
                                showAddRow(false)
                                setValue(lang)
                            }
                        }
                    }
                    group.addView(chip)
                }

                if (selectedLabel == ADD_LANGUAGE && selectedValue.isNotBlank() &&
                    !selectedValue.equals(ADD_LANGUAGE, ignoreCase = true)
                ) {
                    customLangInput.setText(selectedValue)
                    showAddRow(true)
                } else {
                    showAddRow(selectedLabel == ADD_LANGUAGE)
                }
            }

            fun confirmAddLanguage() {
                val typed = customLangInput.text?.toString()?.trim().orEmpty()
                if (typed.isBlank()) {
                    Toast.makeText(this@SmartVoiceActivity, "Enter a language name", Toast.LENGTH_SHORT).show()
                    return
                }
                if (typed.equals(ADD_LANGUAGE, ignoreCase = true)) {
                    Toast.makeText(this@SmartVoiceActivity, "Pick a real language name", Toast.LENGTH_SHORT).show()
                    return
                }
                addCustom(typed)
                setValue(typed)
                rebuildChips(typed)
                showAddRow(false)
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(customLangInput.windowToken, 0)
                // setValue already persists + toasts "Saved"
            }

            val addLangBtn = MaterialButton(materialContext).apply {
                text = "Add"
                textSize = 13f
                isAllCaps = false
                setTextColor(TEXT)
                backgroundTintList = ColorStateList.valueOf(ACCENT_SOFT)
                cornerRadius = UiKit.dp(this@SmartVoiceActivity, 20)
                minHeight = UiKit.dp(this@SmartVoiceActivity, 48)
                insetTop = 0
                insetBottom = 0
                setPadding(
                    UiKit.dp(this@SmartVoiceActivity, 16),
                    UiKit.dp(this@SmartVoiceActivity, 10),
                    UiKit.dp(this@SmartVoiceActivity, 16),
                    UiKit.dp(this@SmartVoiceActivity, 10)
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = UiKit.dp(this@SmartVoiceActivity, 8) }
                setOnClickListener { confirmAddLanguage() }
            }

            customLangInput.imeOptions = EditorInfo.IME_ACTION_DONE
            customLangInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    confirmAddLanguage()
                    true
                } else {
                    false
                }
            }

            customLangRow.addView(customLangLayout)
            customLangRow.addView(addLangBtn)

            rebuildChips(getValue())

            addView(group)
            addView(customLangRow)
            onBindRefresh?.invoke {
                rebuildChips(getValue())
            }
        }
    }

    /** Bottom-line (filled) TextInputLayout — not a full outlined box. */
    private fun underlinedLanguageInput(): Pair<TextInputLayout, EditText> {
        val input = TextInputEditText(materialContext).apply {
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            textSize = 15f
            background = null
            setSingleLine(true)
            maxLines = 1
            setPadding(
                UiKit.dp(this@SmartVoiceActivity, 4),
                UiKit.dp(this@SmartVoiceActivity, 8),
                UiKit.dp(this@SmartVoiceActivity, 4),
                UiKit.dp(this@SmartVoiceActivity, 8)
            )
        }
        val layout = TextInputLayout(
            ContextThemeWrapper(
                this,
                com.google.android.material.R.style.Widget_Material3_TextInputLayout_FilledBox
            )
        ).apply {
            hint = "Language name"
            placeholderText = "e.g. Telugu, German"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
            setBoxBackgroundColor(0x0025252C)
            boxStrokeColor = ACCENT
            setBoxStrokeColorStateList(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_focused),
                        intArrayOf()
                    ),
                    intArrayOf(ACCENT, STROKE)
                )
            )
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
}
