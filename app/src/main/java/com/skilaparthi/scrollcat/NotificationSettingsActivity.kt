package com.skilaparthi.scrollcat

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
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
    private lateinit var appChipLayout: FlexboxLayout
    private lateinit var contentRoot: LinearLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var selectAppsButton: MaterialButton

    private var loadingApps = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val popularApps = listOf(
        "com.whatsapp" to "WhatsApp",
        "com.google.android.apps.messaging" to "Google Messages",
        "com.android.mms" to "Messages",
        "com.samsung.android.messaging" to "Messages (Samsung)",
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

        contentRoot = UiKit.pageRoot(this)
        addToolbar(contentRoot)
        addAppsSection(contentRoot)
        addPeopleSection(contentRoot)
        addKeywordsSection(contentRoot)
        addIgnoredChatsSection(contentRoot)

        contentScroll = ScrollView(this).apply {
            setBackgroundColor(UiKit.surfaceColor(this@NotificationSettingsActivity))
            addView(contentRoot)
        }

        setContentView(contentScroll)

        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { v, insets ->
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
            R.drawable.ic_section_apps,
            "Apps",
            "Tap to select which apps the cat watches. Leave all off = watch everything."
        ) {
            addView(financeSecurityNote())
            addView(UiKit.sectionTitle(this@NotificationSettingsActivity, "Popular Apps"))
            appChipLayout = FlexboxLayout(this@NotificationSettingsActivity).apply {
                setPadding(0, UiKit.dp(this@NotificationSettingsActivity, 8), 0, 0)
            }
            renderPopularAppChips()
            addView(appChipLayout)
            addView(UiKit.body(
                this@NotificationSettingsActivity,
                "Can't find your app above? Select from the full list below.",
                muted = true
            ).apply {
                setPadding(0, UiKit.dp(this@NotificationSettingsActivity, 12), 0, 0)
            })
            selectAppsButton = outlinedButton("Select Apps") { launchAppPicker() }
            addView(selectAppsButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = UiKit.dp(this@NotificationSettingsActivity, 8) })
        }
    }

    private fun financeSecurityNote(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = UiKit.dp(this@NotificationSettingsActivity, 14).toFloat()
            cardElevation = 0f
            strokeWidth = 1
            strokeColor = 0xFFB39DDB.toInt()
            setCardBackgroundColor(0xFF2E2A3A.toInt())
            isClickable = false
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = UiKit.dp(this@NotificationSettingsActivity, 12)
            }
        }
        card.addView(TextView(this).apply {
            text =
                "\uD83D\uDD12 For your security, ScrollCat never reads banking, payment, or finance app notifications — this can't be changed."
            textSize = 13f
            setTextColor(0xFFF5F3F7.toInt())
            setPadding(
                UiKit.dp(this@NotificationSettingsActivity, 16),
                UiKit.dp(this@NotificationSettingsActivity, 14),
                UiKit.dp(this@NotificationSettingsActivity, 16),
                UiKit.dp(this@NotificationSettingsActivity, 14)
            )
        })
        return card
    }

    private fun addPeopleSection(root: LinearLayout) {
        materialSection(
            root,
            R.drawable.ic_section_person,
            "People",
            "Cat alerts you when messages mention these names. A green notification badge appears on the cat when a message matches."
        ) {
            peopleChipLayout = FlexboxLayout(this@NotificationSettingsActivity)
            addView(buildChipInput(peopleChipLayout, peopleSet, "Type a name"))
            peopleChipLayout.setPadding(0, UiKit.dp(this@NotificationSettingsActivity, 12), 0, 0)
            addView(peopleChipLayout)
        }
    }

    private fun addIgnoredChatsSection(root: LinearLayout) {
        materialSection(
            root,
            R.drawable.ic_section_block,
            "Ignored Chats",
            "Cat won't notify or reply to these people at all"
        ) {
            ignoredChatsChipLayout = FlexboxLayout(this@NotificationSettingsActivity)
            addView(buildChipInput(ignoredChatsChipLayout, ignoredChatsSet, "Type a chat name"))
            ignoredChatsChipLayout.setPadding(0, UiKit.dp(this@NotificationSettingsActivity, 12), 0, 0)
            addView(ignoredChatsChipLayout)
        }
    }

    private fun addKeywordsSection(root: LinearLayout) {
        materialSection(
            root,
            R.drawable.ic_section_key,
            "Keywords",
            "Cat alerts you when notifications contain these words. A green notification badge appears on the cat when a message matches."
        ) {
            keywordsChipLayout = FlexboxLayout(this@NotificationSettingsActivity)
            addView(buildChipInput(keywordsChipLayout, keywordsSet, "Type a keyword"))
            keywordsChipLayout.setPadding(0, UiKit.dp(this@NotificationSettingsActivity, 12), 0, 0)
            addView(keywordsChipLayout)
        }
    }

    private fun materialSection(
        parent: LinearLayout,
        iconRes: Int,
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
            addView(sectionHeader(title, iconRes))
            addView(UiKit.body(this@NotificationSettingsActivity, subtitle, muted = true))
            build()
        }
        card.addView(content)
        parent.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, UiKit.dp(this@NotificationSettingsActivity, 16)) })
    }

    private fun sectionHeader(title: String, iconRes: Int): LinearLayout {
        val accent = UiKit.primaryColor(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, UiKit.dp(this@NotificationSettingsActivity, 8))
            addView(ImageView(this@NotificationSettingsActivity).apply {
                setImageDrawable(ContextCompat.getDrawable(this@NotificationSettingsActivity, iconRes))
                imageTintList = ColorStateList.valueOf(accent)
                layoutParams = LinearLayout.LayoutParams(
                    UiKit.dp(this@NotificationSettingsActivity, 24),
                    UiKit.dp(this@NotificationSettingsActivity, 24)
                ).apply { marginEnd = UiKit.dp(this@NotificationSettingsActivity, 10) }
                contentDescription = title
            })
            addView(TextView(this@NotificationSettingsActivity).apply {
                text = title
                textSize = 18f
                typeface = UiKit.headingTypeface(this@NotificationSettingsActivity)
                setTextColor(UiKit.onSurfaceColor(this@NotificationSettingsActivity))
            })
        }
    }

    private fun buildPopularAppsList(): List<Pair<String, String>> {
        val apps = popularApps.toMutableList()
        val defaultSms = Telephony.Sms.getDefaultSmsPackage(this)
        if (defaultSms != null && apps.none { it.first == defaultSms }) {
            val label = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(defaultSms, PackageManager.GET_META_DATA)
                ).toString()
            } catch (_: Exception) {
                "Messages"
            }
            apps.add(1, defaultSms to label)
        }
        return apps.distinctBy { it.first }
    }

    private fun renderPopularAppChips() {
        appChipLayout.removeAllViews()
        buildPopularAppsList().forEach { (pkg, name) ->
            appChipLayout.addView(appFilterChip(name, pkg in selectedApps) { checked ->
                if (checked) selectedApps.add(pkg) else selectedApps.remove(pkg)
                persistFilters(showToast = true)
            })
        }
    }

    private fun launchAppPicker() {
        if (loadingApps) return
        loadingApps = true
        selectAppsButton.isEnabled = false
        selectAppsButton.text = "Loading apps..."
        selectAppsButton.icon = createLoadingIcon()
        selectAppsButton.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        selectAppsButton.iconPadding = UiKit.dp(this@NotificationSettingsActivity, 8)
        (selectAppsButton.icon as? IndeterminateDrawable<*>)?.start()

        Thread {
            val installedApps = queryInstalledApps()
            mainHandler.post {
                if (isFinishing) return@post
                stopSelectAppsLoading()
                showAppPickerDialog(installedApps)
            }
        }.start()
    }

    private fun createLoadingIcon(): IndeterminateDrawable<CircularProgressIndicatorSpec> {
        val spec = CircularProgressIndicatorSpec(
            this,
            null,
            0,
            com.google.android.material.R.style.Widget_Material3_CircularProgressIndicator
        ).apply {
            indicatorSize = UiKit.dp(this@NotificationSettingsActivity, 20)
            indicatorColors = intArrayOf(UiKit.primaryColor(this@NotificationSettingsActivity))
        }
        return IndeterminateDrawable.createCircularDrawable(this, spec)
    }

    private fun stopSelectAppsLoading() {
        loadingApps = false
        (selectAppsButton.icon as? IndeterminateDrawable<*>)?.stop()
        selectAppsButton.icon = null
        selectAppsButton.text = "Select Apps"
        selectAppsButton.isEnabled = true
    }

    private fun queryInstalledApps(): List<Pair<String, String>> {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .distinct()
            .mapNotNull { pkg ->
                try {
                    val info = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
                    val label = pm.getApplicationLabel(info).toString()
                    pkg to label
                } catch (_: Exception) {
                    null
                }
            }
            .sortedBy { it.second.lowercase() }
    }

    private fun showAppPickerDialog(installedApps: List<Pair<String, String>>) {
        val pm = packageManager
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(this@NotificationSettingsActivity, 8),
                UiKit.dp(this@NotificationSettingsActivity, 4),
                UiKit.dp(this@NotificationSettingsActivity, 8),
                UiKit.dp(this@NotificationSettingsActivity, 4)
            )
        }

        installedApps.forEach { (pkg, label) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    UiKit.dp(this@NotificationSettingsActivity, 4),
                    UiKit.dp(this@NotificationSettingsActivity, 8),
                    UiKit.dp(this@NotificationSettingsActivity, 4),
                    UiKit.dp(this@NotificationSettingsActivity, 8)
                )
            }
            try {
                val icon = pm.getApplicationIcon(pkg)
                row.addView(ImageView(this).apply {
                    setImageDrawable(icon)
                    layoutParams = LinearLayout.LayoutParams(
                        UiKit.dp(this@NotificationSettingsActivity, 32),
                        UiKit.dp(this@NotificationSettingsActivity, 32)
                    ).apply { marginEnd = UiKit.dp(this@NotificationSettingsActivity, 12) }
                })
            } catch (_: Exception) {
                // Skip icon if unavailable
            }
            val checkbox = CheckBox(this).apply {
                text = label
                isChecked = pkg in selectedApps
                setTextColor(UiKit.onSurfaceColor(this@NotificationSettingsActivity))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedApps.add(pkg) else selectedApps.remove(pkg)
                }
            }
            row.addView(checkbox)
            listContainer.addView(row)
        }

        val scroll = ScrollView(this).apply {
            addView(listContainer)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Select Apps")
            .setView(scroll)
            .setPositiveButton("Done") { dialog, _ ->
                renderPopularAppChips()
                persistFilters(showToast = true)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun outlinedButton(label: String, onClick: () -> Unit): MaterialButton {
        val accent = UiKit.primaryColor(this)
        return MaterialButton(
            ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_Button_OutlinedButton)
        ).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setTextColor(accent)
            strokeColor = ColorStateList.valueOf(accent)
            strokeWidth = UiKit.dp(this@NotificationSettingsActivity, 1)
            backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            cornerRadius = UiKit.dp(this@NotificationSettingsActivity, 24)
            minHeight = UiKit.dp(this@NotificationSettingsActivity, 48)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onClick() }
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
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) scrollFieldIntoView(v)
            }
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

    private fun scrollFieldIntoView(field: View) {
        field.post {
            val rect = android.graphics.Rect(0, 0, field.width, field.height + UiKit.dp(this, 48))
            field.requestRectangleOnScreen(rect, true)
        }
        if (::contentScroll.isInitialized) {
            contentScroll.post {
                val loc = IntArray(2)
                field.getLocationOnScreen(loc)
                val scrollLoc = IntArray(2)
                contentScroll.getLocationOnScreen(scrollLoc)
                val relativeTop = loc[1] - scrollLoc[1]
                val target = (contentScroll.scrollY + relativeTop - UiKit.dp(this, 24)).coerceAtLeast(0)
                contentScroll.smoothScrollTo(0, target)
            }
        }
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
            persistFilters(showToast = true)
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
                        persistFilters(showToast = true)
                    }
                }
            )
        }
    }

    private fun persistFilters(showToast: Boolean) {
        SettingsManager.setWatchedApps(this, selectedApps)
        SettingsManager.setWatchedPeople(this, peopleSet)
        SettingsManager.setIgnoredChats(this, ignoredChatsSet)
        SettingsManager.setWatchedKeywords(this, keywordsSet)
        if (showToast) {
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
    }
}
