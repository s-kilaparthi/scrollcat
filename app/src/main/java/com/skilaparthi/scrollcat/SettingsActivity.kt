package com.skilaparthi.scrollcat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : Activity() {

    private var accessibilityBadge: TextView? = null
    private var setupOverlayBadge: TextView? = null
    private var setupAccessibilityBadge: TextView? = null
    private var setupNotificationBadge: TextView? = null
    private var gestureHint: TextView? = null
    private var gestureOptionsContainer: LinearLayout? = null
    private var gesturesExpandedManually = false
    private val gestureSwitchRows = mutableListOf<Pair<View, SwitchMaterial>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = UiKit.pageRoot(this)
        addToolbar(root)
        addCatAppearance(root)
        addReplyTextSize(root)
        addMusicAndGestures(root)
        addAppReactions(root)
        addSetupSteps(root)
        addPrivacyLink(root)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(UiKit.surfaceColor(this@SettingsActivity))
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
        refreshGestureAccessibilityState()
        refreshSetupPermissionBadges()
    }

    override fun onResume() {
        super.onResume()
        refreshGestureAccessibilityState()
        refreshSetupPermissionBadges()
    }

    private fun addToolbar(root: LinearLayout) {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, UiKit.dp(this@SettingsActivity, 16))
        }
        toolbar.addView(TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(UiKit.onSurfaceColor(this@SettingsActivity))
            setPadding(0, 0, UiKit.dp(this@SettingsActivity, 12), 0)
            setOnClickListener { finish() }
        })
        toolbar.addView(UiKit.headline(this, "Settings"))
        root.addView(toolbar)
    }

    private fun addCatAppearance(root: LinearLayout) {
        UiKit.section(root, "Cat Appearance", "Size and sleep transparency for the floating cat.") {
            val sizeLabel = TextView(this@SettingsActivity).apply {
                textSize = 15f
                setTextColor(UiKit.onSurfaceColor(this@SettingsActivity))
                setPadding(0, 0, 0, UiKit.dp(this@SettingsActivity, 4))
            }
            val sizeSlider = Slider(this@SettingsActivity).apply {
                valueFrom = SettingsManager.MIN_CAT_SIZE_DP.toFloat()
                valueTo = SettingsManager.MAX_CAT_SIZE_DP.toFloat()
                stepSize = 5f
                val raw = SettingsManager.getCatSizeDp(this@SettingsActivity)
                    .coerceIn(SettingsManager.MIN_CAT_SIZE_DP, SettingsManager.MAX_CAT_SIZE_DP)
                value = ((raw + 2) / 5 * 5)
                    .coerceIn(SettingsManager.MIN_CAT_SIZE_DP, SettingsManager.MAX_CAT_SIZE_DP)
                    .toFloat()
            }
            fun updateSizeLabel(sizeDp: Int) {
                sizeLabel.text = "Cat size: ${sizeDp}dp"
            }
            updateSizeLabel(sizeSlider.value.toInt())
            sizeSlider.addOnChangeListener { _, value, fromUser ->
                val sizeDp = value.toInt()
                updateSizeLabel(sizeDp)
                if (fromUser) {
                    SettingsManager.setCatSize(this@SettingsActivity, sizeDp)
                    OverlayService.instance?.updateCatSize(sizeDp)
                }
            }
            addView(sizeLabel)
            addView(sizeSlider)

            val opacityLabel = TextView(this@SettingsActivity).apply {
                textSize = 15f
                setTextColor(UiKit.onSurfaceColor(this@SettingsActivity))
                setPadding(
                    0,
                    UiKit.dp(this@SettingsActivity, 12),
                    0,
                    UiKit.dp(this@SettingsActivity, 4)
                )
            }
            val currentOpacityPct = ((SettingsManager.getSleepOpacity(this@SettingsActivity) * 100f)
                .toInt()
                .coerceIn(30, 100) + 2) / 5 * 5
            val opacitySlider = Slider(this@SettingsActivity).apply {
                valueFrom = 30f
                valueTo = 100f
                stepSize = 5f
                value = currentOpacityPct.toFloat()
            }
            fun updateOpacityLabel(pct: Int) {
                opacityLabel.text = "Sleep opacity: $pct%"
            }
            updateOpacityLabel(opacitySlider.value.toInt())
            opacitySlider.addOnChangeListener { _, value, fromUser ->
                val pct = value.toInt()
                updateOpacityLabel(pct)
                if (fromUser) {
                    val opacity = pct / 100f
                    SettingsManager.setSleepOpacity(this@SettingsActivity, opacity)
                    OverlayService.instance?.updateSleepOpacity(opacity)
                }
            }
            addView(opacityLabel)
            addView(opacitySlider)

            addView(TextView(this@SettingsActivity).apply {
                text = "Cat display mode"
                textSize = 15f
                setTextColor(UiKit.onSurfaceColor(this@SettingsActivity))
                setPadding(
                    0,
                    UiKit.dp(this@SettingsActivity, 16),
                    0,
                    UiKit.dp(this@SettingsActivity, 4)
                )
            })
            val displayOptions = listOf(
                SettingsManager.DISPLAY_MODE_ALWAYS_VISIBLE to "Always visible",
                SettingsManager.DISPLAY_MODE_EDGE_DOCKING to "Edge docking"
            )
            val selectedMode = SettingsManager.getCatDisplayMode(this@SettingsActivity)
            val displayGroup = RadioGroup(this@SettingsActivity).apply {
                orientation = RadioGroup.VERTICAL
            }
            val displayMap = mutableMapOf<Int, String>()
            displayOptions.forEach { (key, label) ->
                val radio = MaterialRadioButton(this@SettingsActivity).apply {
                    text = label
                    textSize = 15f
                    id = View.generateViewId()
                    isChecked = key == selectedMode
                    setTextColor(UiKit.onSurfaceColor(this@SettingsActivity))
                    setPadding(
                        0,
                        UiKit.dp(this@SettingsActivity, 10),
                        0,
                        UiKit.dp(this@SettingsActivity, 10)
                    )
                }
                displayMap[radio.id] = key
                displayGroup.addView(radio)
            }
            displayGroup.setOnCheckedChangeListener { _, checkedId ->
                val key = displayMap[checkedId] ?: return@setOnCheckedChangeListener
                SettingsManager.setCatDisplayMode(this@SettingsActivity, key)
                OverlayService.instance?.applyCatDisplayMode()
            }
            addView(displayGroup)
        }
    }

    private fun addReplyTextSize(root: LinearLayout) {
        UiKit.section(
            root,
            "Reply text size",
            "Size of message preview and AI reply suggestions."
        ) {
            val options = listOf(
                "small" to "Small",
                "normal" to "Normal",
                "large" to "Large",
                "xlarge" to "Extra Large"
            )
            val selected = SettingsManager.getReplyTextSizeOption(this@SettingsActivity)
            val group = RadioGroup(this@SettingsActivity).apply {
                orientation = RadioGroup.VERTICAL
                setPadding(0, UiKit.dp(this@SettingsActivity, 4), 0, 0)
            }
            val radioMap = mutableMapOf<Int, String>()
            options.forEach { (key, label) ->
                val radio = MaterialRadioButton(this@SettingsActivity).apply {
                    text = label
                    textSize = 15f
                    id = View.generateViewId()
                    isChecked = key == selected
                    setTextColor(UiKit.onSurfaceColor(this@SettingsActivity))
                    setPadding(
                        0,
                        UiKit.dp(this@SettingsActivity, 10),
                        0,
                        UiKit.dp(this@SettingsActivity, 10)
                    )
                }
                radioMap[radio.id] = key
                group.addView(radio)
            }
            group.setOnCheckedChangeListener { _, checkedId ->
                val key = radioMap[checkedId] ?: return@setOnCheckedChangeListener
                SettingsManager.setReplyTextSizeOption(this@SettingsActivity, key)
            }
            addView(group)
        }
    }

    private fun addMusicAndGestures(root: LinearLayout) {
        UiKit.section(root) {
            addView(UiKit.sectionTitle(this@SettingsActivity, "Gestures").apply {
                setOnClickListener { toggleGesturesExpanded() }
            })
            addView(UiKit.body(
                this@SettingsActivity,
                "Music dance and cat gestures. Gestures need Accessibility.",
                muted = true
            ))

            val a11yFrame = FrameLayout(this@SettingsActivity)
            val a11yButton = UiKit.tonalButton(
                this@SettingsActivity,
                "Enable Accessibility Service for Gestures"
            ) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(
                    UiKit.dp(this@SettingsActivity, 18),
                    paddingTop,
                    UiKit.dp(this@SettingsActivity, 112),
                    paddingBottom
                )
            }
            a11yFrame.addView(
                a11yButton,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            val badge = TextView(this@SettingsActivity).apply {
                text = "Enabled"
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(
                    UiKit.dp(this@SettingsActivity, 10),
                    UiKit.dp(this@SettingsActivity, 4),
                    UiKit.dp(this@SettingsActivity, 10),
                    UiKit.dp(this@SettingsActivity, 4)
                )
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF15803D.toInt())
                    cornerRadius = UiKit.dp(this@SettingsActivity, 16).toFloat()
                }
                visibility = View.GONE
            }
            a11yFrame.addView(
                badge,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.CENTER_VERTICAL
                ).apply { marginEnd = UiKit.dp(this@SettingsActivity, 14) }
            )
            a11yFrame.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, UiKit.dp(this@SettingsActivity, 8))
            }
            accessibilityBadge = badge
            addView(a11yFrame)

            gestureHint = TextView(this@SettingsActivity).apply {
                text = "Enable Accessibility above to use gestures"
                textSize = 13f
                setTextColor(UiKit.mutedColor(this@SettingsActivity))
                setPadding(0, 0, 0, UiKit.dp(this@SettingsActivity, 8))
                visibility = View.GONE
            }

            val optionsContainer = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            gestureOptionsContainer = optionsContainer

            optionsContainer.apply {
                addView(gestureHint)
                addSwitchRow(
                    "Cat dances when music is playing",
                    SettingsManager.isMusicDanceEnabled(this@SettingsActivity)
                ) { checked ->
                    SettingsManager.setMusicDanceEnabled(this@SettingsActivity, checked)
                    if (!checked) OverlayService.instance?.onMusicStopped()
                }

                listOf(
                    "tap_scroll" to "Single tap → scroll",
                    "push_scroll" to "Push up/down → scroll",
                    "swipe_back" to "Swipe right → back button",
                    "swipe_voice" to "Swipe left → Recent apps",
                    "double_tap_mode" to "Double tap → toggle Feed/Reels"
                ).forEach { (key, label) ->
                    val (row, sw) = addGestureSwitchRow(
                        label,
                        SettingsManager.getGestureEnabled(this@SettingsActivity, key)
                    ) { checked ->
                        SettingsManager.setGestureEnabled(this@SettingsActivity, key, checked)
                    }
                    gestureSwitchRows.add(row to sw)
                }
            }
            addView(optionsContainer)
        }
    }

    private fun toggleGesturesExpanded() {
        if (CatAccessibilityService.instance != null) return
        gesturesExpandedManually = !gesturesExpandedManually
        updateGestureOptionsVisibility()
    }

    private fun updateGestureOptionsVisibility() {
        val a11yOk = CatAccessibilityService.instance != null
        val showOptions = a11yOk || gesturesExpandedManually
        gestureOptionsContainer?.visibility = if (showOptions) View.VISIBLE else View.GONE
        accessibilityBadge?.visibility = if (a11yOk) View.VISIBLE else View.GONE
        gestureHint?.visibility = if (showOptions && !a11yOk) View.VISIBLE else View.GONE
        gestureSwitchRows.forEach { (row, sw) ->
            row.alpha = if (a11yOk) 1f else 0.45f
            sw.isEnabled = a11yOk
        }
    }

    private fun refreshGestureAccessibilityState() {
        updateGestureOptionsVisibility()
    }

    private fun LinearLayout.addGestureSwitchRow(
        label: String,
        checked: Boolean,
        onChecked: (Boolean) -> Unit
    ): Pair<View, SwitchMaterial> {
        val row = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, UiKit.dp(this@SettingsActivity, 8), 0, UiKit.dp(this@SettingsActivity, 8))
        }
        row.addView(TextView(this@SettingsActivity).apply {
            text = label
            textSize = 15f
            setTextColor(UiKit.onSurfaceColor(this@SettingsActivity))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = SwitchMaterial(this@SettingsActivity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChecked(value) }
        }
        row.addView(sw)
        addView(row)
        return row to sw
    }

    private fun LinearLayout.addSwitchRow(
        label: String,
        checked: Boolean,
        onChecked: (Boolean) -> Unit
    ) {
        val row = LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, UiKit.dp(this@SettingsActivity, 8), 0, UiKit.dp(this@SettingsActivity, 8))
        }
        row.addView(TextView(this@SettingsActivity).apply {
            text = label
            textSize = 15f
            setTextColor(UiKit.onSurfaceColor(this@SettingsActivity))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(SwitchMaterial(this@SettingsActivity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChecked(value) }
        })
        addView(row)
    }

    private fun addAppReactions(root: LinearLayout) {
        UiKit.section(root, "App Reactions", "How the cat reacts when you open these apps.") {
            val optionsContainer = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (SettingsManager.isAppReactionsEnabled(this@SettingsActivity)) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }

            addSwitchRow("Enable app reactions", SettingsManager.isAppReactionsEnabled(this@SettingsActivity)) { checked ->
                SettingsManager.setAppReactionsEnabled(this@SettingsActivity, checked)
                optionsContainer.visibility = if (checked) View.VISIBLE else View.GONE
            }

            val reactionOptions = listOf(
                "EXCITED" to "😸 Excited",
                "ALERT" to "👀 Alert",
                "SLEEPY" to "😴 Sleepy",
                "MUSIC" to "🎵 Vibe",
                "HUNGRY" to "😋 Hungry",
                "SHY" to "🙈 Shy",
                "SERIOUS" to "😐 Serious",
                "CURIOUS" to "🤔 Curious",
                "NORMAL" to "😺 Normal"
            )

            val categoryPackages = mapOf(
                "📸 Camera" to listOf("com.sec.android.app.camera", "com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera"),
                "📱 Social/Reels" to listOf("com.instagram.android", "com.zhiliaoapp.musically", "com.google.android.youtube", "com.snapchat.android", "com.twitter.android"),
                "🎵 Music" to listOf("com.spotify.music", "com.google.android.apps.youtube.music"),
                "🍔 Food" to listOf("com.dd.doordash", "in.swiggy.android", "com.ubercab.eats", "com.mcdonalds.mobileapp"),
                "💬 Messages" to listOf("com.whatsapp", "org.telegram.messenger", "com.facebook.orca", "com.discord"),
                "💘 Dating" to listOf("com.tinder", "com.bumble.app", "com.hinge.app"),
                "💼 Work" to listOf("com.google.android.gm", "com.microsoft.office.outlook", "com.microsoft.teams", "us.zoom.videomeetings"),
                "🎬 Streaming" to listOf("com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient")
            )
            val categoryLabels = mapOf(
                "📸 Camera" to "Samsung Camera, Google Camera",
                "📱 Social/Reels" to "Instagram, TikTok, YouTube, Snapchat, Twitter",
                "🎵 Music" to "Spotify, YouTube Music",
                "🍔 Food" to "DoorDash, Swiggy, UberEats, McDonald's",
                "💬 Messages" to "WhatsApp, Telegram, Messenger, Discord",
                "💘 Dating" to "Tinder, Bumble, Hinge",
                "💼 Work" to "Gmail, Outlook, Teams, Zoom",
                "🎬 Streaming" to "Netflix, Prime Video"
            )
            val categoryDefaults = mapOf(
                "📸 Camera" to "EXCITED",
                "📱 Social/Reels" to "EXCITED",
                "🎵 Music" to "MUSIC",
                "🍔 Food" to "HUNGRY",
                "💬 Messages" to "ALERT",
                "💘 Dating" to "SHY",
                "💼 Work" to "SERIOUS",
                "🎬 Streaming" to "SLEEPY"
            )
            val prefs = getSharedPreferences("app_reactions", MODE_PRIVATE)

            categoryPackages.forEach { (category, packages) ->
                val row = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, UiKit.dp(this@SettingsActivity, 12), 0, UiKit.dp(this@SettingsActivity, 2))
                }
                row.addView(TextView(this@SettingsActivity).apply {
                    text = category
                    textSize = 15f
                    typeface = UiKit.headingTypeface(this@SettingsActivity)
                    setTextColor(UiKit.onSurfaceColor(this@SettingsActivity))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                val savedReaction = prefs.getString("reaction_${packages[0]}", categoryDefaults[category] ?: "NORMAL")
                val currentIndex = reactionOptions.indexOfFirst { it.first == savedReaction }.coerceAtLeast(0)
                row.addView(Spinner(this@SettingsActivity).apply {
                    adapter = ArrayAdapter(
                        this@SettingsActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        reactionOptions.map { it.second }
                    )
                    setSelection(currentIndex)
                    onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                            val reactionKey = reactionOptions[position].first
                            val editor = prefs.edit()
                            packages.forEach { pkg -> editor.putString("reaction_$pkg", reactionKey) }
                            editor.apply()
                            Logger.d("Saved reaction $reactionKey for category $category")
                        }
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    }
                })
                optionsContainer.addView(row)
                optionsContainer.addView(UiKit.body(this@SettingsActivity, categoryLabels[category] ?: "", muted = true))
            }
            addView(optionsContainer)
        }
    }

    private fun addSetupSteps(root: LinearLayout) {
        UiKit.section(root, "Setup Steps", "Grant permissions needed for the cat and smart replies.") {
            val overlayButton = setupButton("Grant Overlay Permission") {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            setupOverlayBadge = overlayButton.second
            addView(overlayButton.first)

            val accessibilityButton = setupButton("Enable Accessibility Service") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            setupAccessibilityBadge = accessibilityButton.second
            addView(accessibilityButton.first)

            val notificationButton = setupButton("Enable Notification Access") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            setupNotificationBadge = notificationButton.second
            addView(notificationButton.first)
        }
    }

    private fun setupButton(label: String, onClick: () -> Unit): Pair<FrameLayout, TextView> {
        val frame = FrameLayout(this)
        val button = UiKit.primaryButton(this, label, onClick).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(
                UiKit.dp(this@SettingsActivity, 18),
                paddingTop,
                UiKit.dp(this@SettingsActivity, 112),
                paddingBottom
            )
        }
        frame.addView(button, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        val badge = TextView(this).apply {
            text = "Enabled"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(
                UiKit.dp(this@SettingsActivity, 10),
                UiKit.dp(this@SettingsActivity, 4),
                UiKit.dp(this@SettingsActivity, 10),
                UiKit.dp(this@SettingsActivity, 4)
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF15803D.toInt())
                cornerRadius = UiKit.dp(this@SettingsActivity, 16).toFloat()
            }
            visibility = View.GONE
        }
        frame.addView(badge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.CENTER_VERTICAL
        ).apply { marginEnd = UiKit.dp(this@SettingsActivity, 14) })
        frame.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, UiKit.dp(this@SettingsActivity, 6), 0, UiKit.dp(this@SettingsActivity, 6)) }
        return frame to badge
    }

    private fun refreshSetupPermissionBadges() {
        setupOverlayBadge?.visibility =
            if (Settings.canDrawOverlays(this)) View.VISIBLE else View.GONE
        setupAccessibilityBadge?.visibility =
            if (CatAccessibilityService.instance != null) View.VISIBLE else View.GONE
        setupNotificationBadge?.visibility =
            if (CatNotificationListener.instance != null) View.VISIBLE else View.GONE
    }

    private fun addPrivacyLink(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "🔒 Privacy Policy"
            textSize = 14f
            setTextColor(UiKit.primaryColor(this@SettingsActivity))
            setPadding(0, UiKit.dp(this@SettingsActivity, 12), 0, UiKit.dp(this@SettingsActivity, 24))
            setOnClickListener {
                startActivity(android.content.Intent(this@SettingsActivity, PrivacyPolicyActivity::class.java))
            }
        })
    }
}
