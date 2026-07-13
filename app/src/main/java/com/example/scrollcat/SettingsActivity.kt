package com.example.scrollcat

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 56, 32, 32)
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
            text = "  Settings"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            toolbar.addView(this)
        }
        root.addView(toolbar)

        // Cat size slider
        root.addView(sectionTitle("🐱 Cat Size"))
        val catSizeValue = TextView(this).apply {
            text = "${SettingsManager.getCatSize(this@SettingsActivity)}px"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
        }
        root.addView(catSizeValue)
        val catSizeSlider = SeekBar(this).apply {
            max = 300
            min = 100
            progress = SettingsManager.getCatSize(this@SettingsActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    catSizeValue.text = "${value}px"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    SettingsManager.setCatSize(this@SettingsActivity, sb?.progress ?: 240)
                    // Apply immediately to live cat
                    OverlayService.instance?.updateCatSize(sb?.progress ?: 240)
                }
            })
        }
        root.addView(catSizeSlider)
        root.addView(divider())

        // Scroll sensitivity slider
        root.addView(sectionTitle("👆 Scroll Sensitivity"))
        root.addView(TextView(this).apply {
            text = "Higher = easier to trigger scroll"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
        })
        val sensitivityValue = TextView(this).apply {
            text = "${SettingsManager.getSensitivity(this@SettingsActivity)}"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
        }
        root.addView(sensitivityValue)
        val sensitivitySlider = SeekBar(this).apply {
            max = 150
            min = 20
            progress = SettingsManager.getSensitivity(this@SettingsActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    sensitivityValue.text = "$value"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    SettingsManager.setSensitivity(this@SettingsActivity, sb?.progress ?: 70)
                    OverlayService.instance?.updateSensitivity(sb?.progress ?: 70)
                }
            })
        }
        root.addView(sensitivitySlider)
        root.addView(divider())

        // Break timer interval
        root.addView(sectionTitle("⏰ Break Reminder"))
        root.addView(TextView(this).apply {
            text = "How long before the cat reminds you to take a break"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
        })
        val breakValue = TextView(this).apply {
            text = "${SettingsManager.getBreakInterval(this@SettingsActivity)} minutes"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
        }
        root.addView(breakValue)
        val breakSlider = SeekBar(this).apply {
            max = 60
            min = 5
            progress = SettingsManager.getBreakInterval(this@SettingsActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    breakValue.text = "$value minutes"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    SettingsManager.setBreakInterval(this@SettingsActivity, sb?.progress ?: 10)
                    OverlayService.instance?.updateBreakInterval(sb?.progress ?: 10)
                }
            })
        }
        root.addView(breakSlider)
        root.addView(divider())

        root.addView(sectionTitle("😴 Sleep Opacity"))
        root.addView(sectionSubtitle("How transparent the cat is while sleeping (30% to 100%)"))

        val opacityValue = TextView(this).apply {
            val current = (SettingsManager.getSleepOpacity(this@SettingsActivity) * 100).toInt()
            text = "$current%"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
        }
        root.addView(opacityValue)

        val opacitySlider = SeekBar(this).apply {
            max = 70 // 30% to 100% range
            min = 0
            val current = ((SettingsManager.getSleepOpacity(this@SettingsActivity) * 100) - 30).toInt()
            progress = current.coerceIn(0, 70)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val pct = value + 30
                    opacityValue.text = "$pct%"
                    if (fromUser) {
                        val opacity = pct / 100f
                        SettingsManager.setSleepOpacity(this@SettingsActivity, opacity)
                        // Apply in real time if cat is currently sleeping
                        OverlayService.instance?.updateSleepOpacity(opacity)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val pct = (sb?.progress ?: 20) + 30
                    val opacity = pct / 100f
                    SettingsManager.setSleepOpacity(this@SettingsActivity, opacity)
                }
            })
        }
        root.addView(opacitySlider)
        root.addView(divider())

        root.addView(sectionTitle("👈 Left Swipe Action"))
        root.addView(sectionSubtitle("What happens when you swipe left on the cat"))

        val leftSwipeOptions = listOf(
            "recents" to "Open recent apps",
            "screenshot" to "Take a screenshot",
            "notifications" to "Pull down notifications"
        )

        val currentBehavior = SettingsManager.getLeftSwipeBehavior(this)
        val radioGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            setPadding(0, 8, 0, 8)
        }

        val radioMap = mutableMapOf<Int, String>()

        leftSwipeOptions.forEach { (key, label) ->
            val radio = android.widget.RadioButton(this).apply {
                text = label
                textSize = 15f
                id = android.view.View.generateViewId()
                isChecked = key == currentBehavior
                setPadding(0, 16, 0, 16)
            }
            radioMap[radio.id] = key
            radioGroup.addView(radio)
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val value = radioMap[checkedId] ?: "recents"
            android.util.Log.d("ScrollCat", "Saving left swipe: $value")
            SettingsManager.setLeftSwipeBehavior(this, value)
        }

        root.addView(radioGroup)
        root.addView(divider())

        root.addView(sectionTitle("🤖 AI Reply Tone"))
        root.addView(sectionSubtitle("How the cat writes reply suggestions to your DMs"))

        val toneOptions = listOf(
            "friendly" to "😊 Friendly — warm and personal",
            "casual" to "✌️ Casual — like texting a friend",
            "professional" to "💼 Professional — for business DMs"
        )
        val currentTone = SettingsManager.getReplyTone(this)
        val toneGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val toneMap = mutableMapOf<Int, String>()
        toneOptions.forEach { (key, label) ->
            val radio = android.widget.RadioButton(this).apply {
                text = label
                textSize = 15f
                id = android.view.View.generateViewId()
                isChecked = key == currentTone
                setPadding(0, 16, 0, 16)
            }
            toneMap[radio.id] = key
            toneGroup.addView(radio)
        }
        toneGroup.setOnCheckedChangeListener { _, checkedId ->
            val value = toneMap[checkedId] ?: "friendly"
            SettingsManager.setReplyTone(this, value)
        }
        root.addView(toneGroup)
        root.addView(divider())

        root.addView(sectionTitle("🔑 Claude API Key"))
        root.addView(sectionSubtitle("Pro: use Claude for smarter replies. Leave empty to use free on-device AI (Gemini Nano)."))

        val apiKeyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val apiKeyInput = EditText(this).apply {
            hint = "sk-ant-..."
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(ApiKeyStore.getClaudeApiKey(this@SettingsActivity))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        apiKeyRow.addView(apiKeyInput)
        apiKeyRow.addView(Button(this).apply {
            text = "Save"
            textSize = 13f
            setOnClickListener {
                ApiKeyStore.setClaudeApiKey(this@SettingsActivity, apiKeyInput.text.toString())
                val saved = ApiKeyStore.hasClaudeApiKey(this@SettingsActivity)
                Toast.makeText(
                    this@SettingsActivity,
                    if (saved) "API key saved — Claude replies enabled ✓" else "API key cleared — using on-device AI",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
        root.addView(apiKeyRow)
        root.addView(divider())

        root.addView(sectionTitle("🎵 Music Dance"))
        val musicRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }
        TextView(this).apply {
            text = "Cat dances when music is playing"
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            musicRow.addView(this)
        }
        Switch(this).apply {
            isChecked = SettingsManager.isMusicDanceEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                SettingsManager.setMusicDanceEnabled(this@SettingsActivity, checked)
                if (!checked) OverlayService.instance?.onMusicStopped()
            }
            musicRow.addView(this)
        }
        root.addView(musicRow)
        root.addView(divider())

        // Gesture toggles
        root.addView(sectionTitle("👋 Gestures"))
        listOf(
            "tap_scroll" to "Single tap → scroll",
            "push_scroll" to "Push up/down → scroll",
            "swipe_back" to "Swipe right → back button",
            "swipe_voice" to "Swipe left → voice assistant",
            "double_tap_mode" to "Double tap → toggle Feed/Reels"
        ).forEach { (key, label) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 16, 0, 16)
            }
            TextView(this).apply {
                text = label
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(this)
            }
            Switch(this).apply {
                isChecked = SettingsManager.getGestureEnabled(this@SettingsActivity, key)
                setOnCheckedChangeListener { _, checked ->
                    SettingsManager.setGestureEnabled(this@SettingsActivity, key, checked)
                }
                row.addView(this)
            }
            root.addView(row)
            root.addView(divider())
        }

        root.addView(sectionTitle("📱 App Reactions"))
        root.addView(sectionSubtitle("How the cat reacts when you open these apps"))

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

        // category name to list of package names
        val categoryPackages = mapOf(
            "📸 Camera" to listOf(
                "com.sec.android.app.camera",
                "com.android.camera",
                "com.android.camera2",
                "com.google.android.GoogleCamera"
            ),
            "📱 Social/Reels" to listOf(
                "com.instagram.android",
                "com.zhiliaoapp.musically",
                "com.google.android.youtube",
                "com.snapchat.android",
                "com.twitter.android"
            ),
            "🎵 Music" to listOf(
                "com.spotify.music",
                "com.google.android.apps.youtube.music"
            ),
            "🍔 Food" to listOf(
                "com.dd.doordash",
                "in.swiggy.android",
                "com.ubercab.eats",
                "com.mcdonalds.mobileapp"
            ),
            "💬 Messages" to listOf(
                "com.whatsapp",
                "org.telegram.messenger",
                "com.facebook.orca",
                "com.discord"
            ),
            "💘 Dating" to listOf(
                "com.tinder",
                "com.bumble.app",
                "com.hinge.app"
            ),
            "💼 Work" to listOf(
                "com.google.android.gm",
                "com.microsoft.office.outlook",
                "com.microsoft.teams",
                "us.zoom.videomeetings"
            ),
            "🎬 Streaming" to listOf(
                "com.netflix.mediaclient",
                "com.amazon.avod.thirdpartyclient"
            )
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

        // Default reactions per category
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
            val categoryRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 16, 0, 4)
            }

            TextView(this).apply {
                text = category
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                categoryRow.addView(this)
            }

            // Get current saved reaction for first package in category
            val savedReaction = prefs.getString("reaction_${packages[0]}", categoryDefaults[category] ?: "NORMAL")
            val currentIndex = reactionOptions.indexOfFirst { it.first == savedReaction }.coerceAtLeast(0)

            val spinner = android.widget.Spinner(this).apply {
                adapter = android.widget.ArrayAdapter(
                    this@SettingsActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    reactionOptions.map { it.second }
                )
                setSelection(currentIndex)
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                        val reactionKey = reactionOptions[position].first
                        // Save for ALL packages in this category
                        val editor = prefs.edit()
                        packages.forEach { pkg ->
                            editor.putString("reaction_$pkg", reactionKey)
                        }
                        editor.apply()
                        android.util.Log.d("ScrollCat", "Saved reaction $reactionKey for category $category")
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            }
            categoryRow.addView(spinner)
            root.addView(categoryRow)

            TextView(this).apply {
                text = categoryLabels[category] ?: ""
                textSize = 12f
                setTextColor(0xFF888888.toInt())
                setPadding(0, 0, 0, 8)
                root.addView(this)
            }
            root.addView(divider())
        }

        val scrollView = ScrollView(this)
        scrollView.addView(root)
        setContentView(scrollView)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(32, bars.top + 16, 32, 32)
            insets
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 20, 0, 8)
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
        ).apply { setMargins(0, 8, 0, 0) }
    }
}
