package com.example.scrollcat

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.radiobutton.MaterialRadioButton

class AiSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = UiKit.pageRoot(this)
        addToolbar(root)
        addReplyTone(root)
        addPersonalization(root)
        addAiModel(root)
        addAiStatus(root)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(UiKit.surfaceColor(this@AiSettingsActivity))
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
            setPadding(0, 0, 0, UiKit.dp(this@AiSettingsActivity, 16))
        }
        toolbar.addView(TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(UiKit.onSurfaceColor(this@AiSettingsActivity))
            setPadding(0, 0, UiKit.dp(this@AiSettingsActivity, 12), 0)
            setOnClickListener { finish() }
        })
        toolbar.addView(UiKit.headline(this, "My AI Settings"))
        root.addView(toolbar)
    }

    private fun addReplyTone(root: LinearLayout) {
        UiKit.section(root, "AI Reply Tone", "How the cat writes reply suggestions to your DMs.") {
            val options = listOf(
                "friendly" to "😊 Friendly - warm and personal",
                "casual" to "✌️ Casual - like texting a friend",
                "professional" to "💼 Professional - for business DMs"
            )
            addRadioOptions(options, SettingsManager.getReplyTone(this@AiSettingsActivity)) {
                SettingsManager.setReplyTone(this@AiSettingsActivity, it)
            }
        }
    }

    private fun addPersonalization(root: LinearLayout) {
        UiKit.section(root, "AI Personalization", "Customize your profile so AI replies sound like you.") {
            UiKit.addButton(this, UiKit.tonalButton(this@AiSettingsActivity, "Edit My Profile") {
                startActivity(android.content.Intent(this@AiSettingsActivity, OnboardingActivity::class.java).apply {
                    putExtra("edit_mode", true)
                })
            })
        }
    }

    private fun addAiModel(root: LinearLayout) {
        UiKit.section(root, "AI Model", "Configure Claude, Groq, OpenAI, or custom AI providers.") {
            UiKit.addButton(this, UiKit.tonalButton(this@AiSettingsActivity, "AI Model") {
                startActivity(android.content.Intent(this@AiSettingsActivity, AiProviderActivity::class.java))
            })
        }
    }

    private fun addAiStatus(root: LinearLayout) {
        UiKit.section(root, "AI Status", "Check which reply engine is currently active.") {
            val statusText = UiKit.body(this@AiSettingsActivity, "Tap to check which AI engine is active", muted = true)
            addView(statusText)
            UiKit.addButton(this, UiKit.tonalButton(this@AiSettingsActivity, "AI Status") {
                statusText.text = "Checking..."
                val generator = AiReplyGenerator(this@AiSettingsActivity)
                generator.checkAiStatus { status ->
                    runOnUiThread {
                        statusText.text = status
                        Toast.makeText(this@AiSettingsActivity, status, Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
    }

    private fun LinearLayout.addRadioOptions(
        options: List<Pair<String, String>>,
        selected: String,
        onSelect: (String) -> Unit
    ) {
        val group = RadioGroup(this@AiSettingsActivity).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, UiKit.dp(this@AiSettingsActivity, 4), 0, 0)
        }
        val radioMap = mutableMapOf<Int, String>()
        options.forEach { (key, label) ->
            val radio = MaterialRadioButton(this@AiSettingsActivity).apply {
                text = label
                textSize = 15f
                id = android.view.View.generateViewId()
                isChecked = key == selected
                setTextColor(UiKit.onSurfaceColor(this@AiSettingsActivity))
                setPadding(0, UiKit.dp(this@AiSettingsActivity, 10), 0, UiKit.dp(this@AiSettingsActivity, 10))
            }
            radioMap[radio.id] = key
            group.addView(radio)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            onSelect(radioMap[checkedId] ?: selected)
        }
        addView(group)
    }
}
