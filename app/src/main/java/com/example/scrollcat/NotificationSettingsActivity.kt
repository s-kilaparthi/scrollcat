package com.example.scrollcat

import android.app.Activity
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class NotificationSettingsActivity : Activity() {

    private val selectedApps = mutableSetOf<String>()
    private val peopleSet = mutableSetOf<String>()
    private val ignoredChatsSet = mutableSetOf<String>()
    private val keywordsSet = mutableSetOf<String>()

    private lateinit var peopleChipLayout: FlexboxLayout
    private lateinit var ignoredChatsChipLayout: FlexboxLayout
    private lateinit var keywordsChipLayout: FlexboxLayout

    private val popularApps = listOf(
        "com.whatsapp" to "WhatsApp",
        "com.instagram.android" to "Instagram",
        "com.google.android.gm" to "Gmail",
        "org.telegram.messenger" to "Telegram",
        "com.discord" to "Discord",
        "com.snapchat.android" to "Snapchat",
        "com.twitter.android" to "Twitter/X",
        "com.facebook.orca" to "Messenger",
        "com.linkedin.android" to "LinkedIn",
        "com.microsoft.office.outlook" to "Outlook",
        "com.microsoft.teams" to "Teams",
        "us.zoom.videomeetings" to "Zoom",
        "org.thoughtcrime.securesms" to "Signal",
        "com.facebook.katana" to "Facebook",
        "com.samsung.android.messaging" to "Messages",
        "com.slack" to "Slack"
    )

    inner class FlexboxLayout(context: android.content.Context) : android.view.ViewGroup(context) {
        private val spacing = UiKit.dp(context, 8)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            var x = paddingLeft
            var y = paddingTop
            var lineHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                measureChild(child, widthMeasureSpec, heightMeasureSpec)
                if (x + child.measuredWidth + paddingRight > width && x > paddingLeft) {
                    x = paddingLeft
                    y += lineHeight + spacing
                    lineHeight = 0
                }
                x += child.measuredWidth + spacing
                lineHeight = maxOf(lineHeight, child.measuredHeight)
            }
            y += lineHeight + paddingBottom
            setMeasuredDimension(width, maxOf(y, paddingTop + paddingBottom))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l
            var x = paddingLeft
            var y = paddingTop
            var lineHeight = 0
            val row = mutableListOf<View>()
            fun layoutRow() {
                var rx = paddingLeft
                row.forEach { child ->
                    child.layout(rx, y, rx + child.measuredWidth, y + child.measuredHeight)
                    rx += child.measuredWidth + spacing
                }
                row.clear()
            }
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (x + child.measuredWidth + paddingRight > width && row.isNotEmpty()) {
                    layoutRow()
                    x = paddingLeft
                    y += lineHeight + spacing
                    lineHeight = 0
                }
                row.add(child)
                x += child.measuredWidth + spacing
                lineHeight = maxOf(lineHeight, child.measuredHeight)
            }
            if (row.isNotEmpty()) layoutRow()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        selectedApps.addAll(SettingsManager.getWatchedApps(this))
        peopleSet.addAll(SettingsManager.getWatchedPeople(this))
        ignoredChatsSet.addAll(SettingsManager.getIgnoredChats(this))
        keywordsSet.addAll(SettingsManager.getWatchedKeywords(this))

        val root = UiKit.pageRoot(this)
        addToolbar(root)
        addAppsSection(root)
        addPeopleSection(root)
        addIgnoredChatsSection(root)
        addKeywordsSection(root)
        addTipCard(root)
        addSaveButton(root)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(UiKit.surfaceColor(this@NotificationSettingsActivity))
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

        renderChips(peopleChipLayout, peopleSet)
        renderChips(ignoredChatsChipLayout, ignoredChatsSet)
        renderChips(keywordsChipLayout, keywordsSet)
    }

    private fun addToolbar(root: LinearLayout) {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, UiKit.dp(this@NotificationSettingsActivity, 16))
        }
        toolbar.addView(TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(UiKit.onSurfaceColor(this@NotificationSettingsActivity))
            setPadding(0, 0, UiKit.dp(this@NotificationSettingsActivity, 12), 0)
            setOnClickListener { finish() }
        })
        toolbar.addView(UiKit.headline(this, "Smart Notifications"))
        root.addView(toolbar)
    }

    private fun addAppsSection(root: LinearLayout) {
        materialSection(
            root,
            "📱 Apps",
            "Tap to select which apps the cat watches. Leave all off = watch everything."
        ) {
            addView(UiKit.label(this@NotificationSettingsActivity, "Popular Apps"))
            val appChipLayout = FlexboxLayout(this@NotificationSettingsActivity).apply {
                setPadding(0, UiKit.dp(this@NotificationSettingsActivity, 8), 0, 0)
            }
            popularApps.forEach { (pkg, name) ->
                appChipLayout.addView(appFilterChip(name, pkg in selectedApps) { checked ->
                    if (checked) selectedApps.add(pkg) else selectedApps.remove(pkg)
                })
            }
            addView(appChipLayout)
        }
    }

    private fun addPeopleSection(root: LinearLayout) {
        materialSection(root, "👤 People", "Cat alerts you when messages mention these names.") {
            peopleChipLayout = FlexboxLayout(this@NotificationSettingsActivity)
            addView(buildChipInput(peopleChipLayout, peopleSet, "Type a name"))
            peopleChipLayout.setPadding(0, UiKit.dp(this@NotificationSettingsActivity, 12), 0, 0)
            addView(peopleChipLayout)
        }
    }

    private fun addIgnoredChatsSection(root: LinearLayout) {
        materialSection(root, "Ignored Chats", "Cat won't notify or reply to these people at all") {
            ignoredChatsChipLayout = FlexboxLayout(this@NotificationSettingsActivity)
            addView(buildChipInput(ignoredChatsChipLayout, ignoredChatsSet, "Type a chat name"))
            ignoredChatsChipLayout.setPadding(0, UiKit.dp(this@NotificationSettingsActivity, 12), 0, 0)
            addView(ignoredChatsChipLayout)
        }
    }

    private fun addKeywordsSection(root: LinearLayout) {
        materialSection(root, "🔑 Keywords", "Cat alerts you when notifications contain these words.") {
            keywordsChipLayout = FlexboxLayout(this@NotificationSettingsActivity)
            addView(buildChipInput(keywordsChipLayout, keywordsSet, "Type a keyword"))
            keywordsChipLayout.setPadding(0, UiKit.dp(this@NotificationSettingsActivity, 12), 0, 0)
            addView(keywordsChipLayout)
        }
    }

    private fun materialSection(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        build: LinearLayout.() -> Unit
    ) {
        val card = MaterialCardView(this).apply {
            radius = UiKit.dp(this@NotificationSettingsActivity, 16).toFloat()
            cardElevation = UiKit.dp(this@NotificationSettingsActivity, 2).toFloat()
            strokeWidth = 1
            strokeColor = 0x338C7A68
            setCardBackgroundColor(UiKit.surfaceColor(this@NotificationSettingsActivity))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(this@NotificationSettingsActivity, 20),
                UiKit.dp(this@NotificationSettingsActivity, 18),
                UiKit.dp(this@NotificationSettingsActivity, 20),
                UiKit.dp(this@NotificationSettingsActivity, 18)
            )
            addView(UiKit.sectionTitle(this@NotificationSettingsActivity, title))
            addView(UiKit.body(this@NotificationSettingsActivity, subtitle, muted = true))
            build()
        }
        card.addView(content)
        parent.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, UiKit.dp(this@NotificationSettingsActivity, 16)) })
    }

    private fun appFilterChip(
        label: String,
        checked: Boolean,
        onChecked: (Boolean) -> Unit
    ): Chip {
        return Chip(ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_Chip_Filter)).apply {
            text = label
            isCheckable = true
            isChecked = checked
            isCheckedIconVisible = true
            setOnCheckedChangeListener { _, value -> onChecked(value) }
        }
    }

    private fun buildChipInput(
        chipLayout: FlexboxLayout,
        dataSet: MutableSet<String>,
        hint: String
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = TextInputEditText(this).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
        }
        val inputLayout = TextInputLayout(
            ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox)
        ).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            addView(input)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addBtn = MaterialButton(this).apply {
            text = "Add"
            isAllCaps = false
            minHeight = UiKit.dp(this@NotificationSettingsActivity, 48)
            cornerRadius = UiKit.dp(this@NotificationSettingsActivity, 24)
            setOnClickListener { addItem(input, chipLayout, dataSet) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(UiKit.dp(this@NotificationSettingsActivity, 8), 0, 0, 0) }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addItem(input, chipLayout, dataSet)
                true
            } else {
                false
            }
        }
        row.addView(inputLayout)
        row.addView(addBtn)
        return row
    }

    private fun addItem(
        input: TextInputEditText,
        chipLayout: FlexboxLayout,
        dataSet: MutableSet<String>
    ) {
        val value = input.text?.toString()?.trim()?.lowercase().orEmpty()
        if (value.isNotEmpty()) {
            dataSet.add(value)
            renderChips(chipLayout, dataSet)
            input.setText("")
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        }
    }

    private fun renderChips(container: FlexboxLayout, dataSet: MutableSet<String>) {
        container.removeAllViews()
        dataSet.toSortedSet().forEach { item ->
            container.addView(
                Chip(ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_Chip_Input)).apply {
                    text = item
                    isCloseIconVisible = true
                    setOnCloseIconClickListener {
                        dataSet.remove(item)
                        renderChips(container, dataSet)
                    }
                }
            )
        }
    }

    private fun addTipCard(root: LinearLayout) {
        val card = MaterialCardView(this).apply {
            radius = UiKit.dp(this@NotificationSettingsActivity, 16).toFloat()
            cardElevation = UiKit.dp(this@NotificationSettingsActivity, 1).toFloat()
            setCardBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurfaceVariant,
                    0xFFF2E4D4.toInt()
                )
            )
        }
        card.addView(UiKit.body(this, "💡 Leave all sections empty to get alerted for every notification.").apply {
            setPadding(
                UiKit.dp(this@NotificationSettingsActivity, 20),
                UiKit.dp(this@NotificationSettingsActivity, 16),
                UiKit.dp(this@NotificationSettingsActivity, 20),
                UiKit.dp(this@NotificationSettingsActivity, 16)
            )
        })
        root.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, UiKit.dp(this@NotificationSettingsActivity, 16)) })
    }

    private fun addSaveButton(root: LinearLayout) {
        val saveButton = UiKit.primaryButton(this, "Save") {
            SettingsManager.setWatchedApps(this@NotificationSettingsActivity, selectedApps)
            SettingsManager.setWatchedPeople(this@NotificationSettingsActivity, peopleSet)
            SettingsManager.setIgnoredChats(this@NotificationSettingsActivity, ignoredChatsSet)
            SettingsManager.setWatchedKeywords(this@NotificationSettingsActivity, keywordsSet)
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
            Toast.makeText(this@NotificationSettingsActivity, "Saved! ✓", Toast.LENGTH_SHORT).show()
            finish()
        }.apply {
            minHeight = UiKit.dp(this@NotificationSettingsActivity, 56)
        }
        root.addView(saveButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }
}
