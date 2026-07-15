package com.example.scrollcat

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = UiKit.pageRoot(this)
        addToolbar(root)
        addMusicAndGestures(root)
        addAppReactions(root)
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

    private fun addMusicAndGestures(root: LinearLayout) {
        UiKit.section(root, "Music Dance", null) {
            addSwitchRow("Cat dances when music is playing", SettingsManager.isMusicDanceEnabled(this@SettingsActivity)) { checked ->
                SettingsManager.setMusicDanceEnabled(this@SettingsActivity, checked)
                if (!checked) OverlayService.instance?.onMusicStopped()
            }
        }

        UiKit.section(root, "Gestures", "Choose which cat gestures are enabled.") {
            listOf(
                "tap_scroll" to "Single tap → scroll",
                "push_scroll" to "Push up/down → scroll",
                "swipe_back" to "Swipe right → back button",
                "swipe_voice" to "Swipe left → voice assistant",
                "double_tap_mode" to "Double tap → toggle Feed/Reels"
            ).forEach { (key, label) ->
                addSwitchRow(label, SettingsManager.getGestureEnabled(this@SettingsActivity, key)) { checked ->
                    SettingsManager.setGestureEnabled(this@SettingsActivity, key, checked)
                }
            }
        }
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
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
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
