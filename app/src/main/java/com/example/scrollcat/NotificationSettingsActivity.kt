package com.example.scrollcat

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*

class NotificationSettingsActivity : Activity() {

    private val selectedApps = mutableSetOf<String>()
    private val peopleSet = mutableSetOf<String>()
    private val keywordsSet = mutableSetOf<String>()

    private lateinit var peopleChipLayout: FlexboxLayout
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
        private val spacing = 16
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            var x = paddingLeft; var y = paddingTop; var lineHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                measureChild(child, widthMeasureSpec, heightMeasureSpec)
                if (x + child.measuredWidth + paddingRight > width && x > paddingLeft) {
                    x = paddingLeft; y += lineHeight + spacing; lineHeight = 0
                }
                x += child.measuredWidth + spacing
                lineHeight = maxOf(lineHeight, child.measuredHeight)
            }
            y += lineHeight + paddingBottom
            setMeasuredDimension(width, maxOf(y, paddingTop + paddingBottom))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l
            var x = paddingLeft; var y = paddingTop; var lineHeight = 0
            val row = mutableListOf<android.view.View>()
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
                    layoutRow(); x = paddingLeft; y += lineHeight + spacing; lineHeight = 0
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
        keywordsSet.addAll(SettingsManager.getWatchedKeywords(this))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 0, 32, 32)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

        // Toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 24)
        }
        TextView(this).apply {
            text = "←"
            textSize = 22f
            setOnClickListener { finish() }
            toolbar.addView(this)
        }
        TextView(this).apply {
            text = "  Notification Filters"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            toolbar.addView(this)
        }
        root.addView(toolbar)

        // ── APPS SECTION ──
        root.addView(sectionTitle("📱 Apps"))
        root.addView(sectionSubtitle("Tap to select which apps the cat watches. Leave all off = watch everything."))

        val appToggleLayout = FlexboxLayout(this).apply {
            setPadding(0, 12, 0, 8)
        }
        popularApps.forEach { (pkg, name) ->
            val isSelected = pkg in selectedApps
            val btn = TextView(this).apply {
                text = name
                textSize = 13f
                setPadding(28, 16, 28, 16)
                setTextColor(if (isSelected) Color.WHITE else 0xFF333333.toInt())
                background = appToggleBackground(isSelected)
                tag = isSelected
                setOnClickListener {
                    val nowSelected = !(tag as Boolean)
                    tag = nowSelected
                    if (nowSelected) selectedApps.add(pkg) else selectedApps.remove(pkg)
                    setTextColor(if (nowSelected) Color.WHITE else 0xFF333333.toInt())
                    background = appToggleBackground(nowSelected)
                }
            }
            appToggleLayout.addView(btn)
        }
        root.addView(TextView(this).apply {
            text = "Popular Apps"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF888888.toInt())
            setPadding(0, 16, 0, 8)
        })
        root.addView(appToggleLayout)
        root.addView(divider())

        // ── PEOPLE SECTION ──
        peopleChipLayout = FlexboxLayout(this)
        root.addView(sectionTitle("👤 People"))
        root.addView(sectionSubtitle("Cat alerts you when messages mention these names."))
        root.addView(buildChipInput(peopleChipLayout, peopleSet, "Type name + Add"))
        peopleChipLayout.setPadding(0, 12, 0, 8)
        root.addView(peopleChipLayout)
        root.addView(divider())

        // ── KEYWORDS SECTION ──
        keywordsChipLayout = FlexboxLayout(this)
        root.addView(sectionTitle("🔑 Keywords"))
        root.addView(sectionSubtitle("Cat alerts you when notifications contain these words."))
        root.addView(buildChipInput(keywordsChipLayout, keywordsSet, "Type keyword + Add"))
        keywordsChipLayout.setPadding(0, 12, 0, 8)
        root.addView(keywordsChipLayout)

        // Tip
        TextView(this).apply {
            text = "💡 Leave all sections empty to get alerted for every notification."
            textSize = 13f
            setTextColor(0xFF555555.toInt())
            setBackgroundColor(0xFFFFF9E6.toInt())
            setPadding(24, 20, 24, 20)
            root.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 24, 0, 16) })
        }

        // Save button
        Button(this).apply {
            text = "Save"
            textSize = 16f
            setOnClickListener {
                SettingsManager.setWatchedApps(this@NotificationSettingsActivity, selectedApps)
                SettingsManager.setWatchedPeople(this@NotificationSettingsActivity, peopleSet)
                SettingsManager.setWatchedKeywords(this@NotificationSettingsActivity, keywordsSet)
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
                Toast.makeText(this@NotificationSettingsActivity, "Saved! ✓", Toast.LENGTH_SHORT).show()
                finish()
            }
            root.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val scrollView = ScrollView(this)
        scrollView.addView(root)
        setContentView(scrollView)

        // Render initial chips
        renderChips(peopleChipLayout, peopleSet)
        renderChips(keywordsChipLayout, keywordsSet)
    }

    private fun appToggleBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = 64f
            if (selected) {
                setColor(0xFF4A90D9.toInt())
            } else {
                setColor(0xFFF0F0F0.toInt())
            }
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
        val input = EditText(this).apply {
            this.hint = hint
            textSize = 15f
            setPadding(24, 20, 24, 20)
            setBackgroundColor(0xFFF5F5F5.toInt())
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addBtn = Button(this).apply {
            text = "Add"
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(8, 0, 0, 0) }
        }
        fun addItem() {
            val value = input.text.toString().trim().lowercase()
            if (value.isNotEmpty()) {
                dataSet.add(value)
                renderChips(chipLayout, dataSet)
                input.setText("")
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(input.windowToken, 0)
            }
        }
        addBtn.setOnClickListener { addItem() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { addItem(); true } else false
        }
        row.addView(input)
        row.addView(addBtn)
        return row
    }

    private fun renderChips(container: FlexboxLayout, dataSet: MutableSet<String>) {
        container.removeAllViews()
        dataSet.toSortedSet().forEach { item ->
            TextView(this).apply {
                text = "$item  ✕"
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(28, 14, 28, 14)
                background = GradientDrawable().apply {
                    setColor(0xFF4A90D9.toInt())
                    cornerRadius = 64f
                }
                setOnClickListener {
                    dataSet.remove(item)
                    renderChips(container, dataSet)
                }
                container.addView(this)
            }
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 20, 0, 4)
    }

    private fun sectionSubtitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFF888888.toInt())
        setPadding(0, 0, 0, 8)
    }

    private fun divider() = android.view.View(this).apply {
        setBackgroundColor(0xFFEEEEEE.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 2
        ).apply { setMargins(0, 16, 0, 0) }
    }
}
