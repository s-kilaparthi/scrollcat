package com.example.scrollcat

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Manage keyword-triggered auto-reply rules (max 10). Each rule has trigger
 * keywords, a reply message, an on/off toggle and a delete button. Rules are
 * saved as JSON and applied by CatNotificationListener as DMs arrive.
 */
class AutoReplyActivity : Activity() {

    private val rules = mutableListOf<AutoReplyManager.Rule>()
    private lateinit var rulesContainer: LinearLayout
    private lateinit var addButton: MaterialButton

    // Views bound per rule so edits can be collected on save
    private data class RuleViews(
        val triggersInput: EditText,
        val replyInput: EditText,
        val toggle: SwitchMaterial
    )
    private val ruleViews = mutableListOf<RuleViews>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rules.addAll(AutoReplyManager.getRules(this))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 56, 32, 32)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

        // Toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }
        toolbar.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            setOnClickListener { finish() }
        })
        toolbar.addView(TextView(this).apply {
            text = "  Auto-Reply Rules"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(toolbar)

        root.addView(TextView(this).apply {
            text = "Cat replies automatically when these keywords are detected"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 16)
        })

        rulesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(rulesContainer)

        addButton = UiKit.tonalButton(this, "+ Add Rule") {
                collectEdits()
                if (rules.size >= AutoReplyManager.MAX_RULES) return@tonalButton
                rules.add(AutoReplyManager.Rule(triggers = "", reply = "", enabled = true))
                renderRules()
        }
        root.addView(addButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 16, 0, 0) })

        root.addView(UiKit.primaryButton(this, "Save") {
                collectEdits()
                val valid = rules.filter { it.triggers.isNotBlank() && it.reply.isNotBlank() }
                AutoReplyManager.saveRules(this@AutoReplyActivity, valid)
                Toast.makeText(this@AutoReplyActivity, "Saved ${valid.size} rules ✓", Toast.LENGTH_SHORT).show()
                finish()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 8, 0, 0) })

        val scrollView = ScrollView(this)
        scrollView.addView(root)
        setContentView(scrollView)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(32, bars.top + 16, 32, 32)
            insets
        }

        renderRules()
    }

    private fun renderRules() {
        rulesContainer.removeAllViews()
        ruleViews.clear()

        if (rules.isEmpty()) {
            rulesContainer.addView(TextView(this).apply {
                text = "No rules yet. Add one below — e.g. trigger \"price, cost, how much\" with your rate card as the reply."
                textSize = 14f
                setTextColor(0xFF555555.toInt())
                setBackgroundColor(0xFFFFF9E6.toInt())
                setPadding(24, 20, 24, 20)
            })
        }

        rules.forEachIndexed { index, rule ->
            val card = MaterialCardView(this).apply {
                radius = UiKit.dp(this@AutoReplyActivity, 16).toFloat()
                cardElevation = UiKit.dp(this@AutoReplyActivity, 2).toFloat()
                strokeWidth = 1
                strokeColor = 0x338C7A68
                setCardBackgroundColor(0xFFF7F7FA.toInt())
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
            }

            // Header row: rule number + toggle + delete
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headerRow.addView(TextView(this).apply {
                text = "Rule ${index + 1}"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val toggle = SwitchMaterial(this).apply {
                isChecked = rule.enabled
            }
            headerRow.addView(toggle)
            headerRow.addView(TextView(this).apply {
                text = "🗑"
                textSize = 18f
                setPadding(24, 4, 4, 4)
                setOnClickListener {
                    collectEdits()
                    rules.removeAt(index)
                    renderRules()
                }
            })
            content.addView(headerRow)

            val triggersInput = EditText(this).apply {
                hint = "Trigger keywords (comma-separated) e.g. price, cost, rate, how much"
                textSize = 14f
                setText(rule.triggers)
                setSingleLine(true)
            }
            content.addView(triggersInput)

            val replyInput = EditText(this).apply {
                hint = "Auto-reply message"
                textSize = 14f
                setText(rule.reply)
                minLines = 2
            }
            content.addView(replyInput)

            ruleViews.add(RuleViews(triggersInput, replyInput, toggle))
            card.addView(content)

            rulesContainer.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) })
        }

        addButton.visibility =
            if (rules.size >= AutoReplyManager.MAX_RULES) android.view.View.GONE
            else android.view.View.VISIBLE
    }

    /** Pull current EditText/Switch state back into the rules list. */
    private fun collectEdits() {
        ruleViews.forEachIndexed { index, views ->
            if (index < rules.size) {
                rules[index].triggers = views.triggersInput.text.toString().trim()
                rules[index].reply = views.replyInput.text.toString().trim()
                rules[index].enabled = views.toggle.isChecked
            }
        }
    }
}
