package com.example.scrollcat

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Manage keyword-triggered auto-reply rules (max 10). Each rule has trigger
 * keywords, a reply message, an on/off toggle and a delete button. Rules are
 * saved as JSON and applied by CatNotificationListener as DMs arrive.
 */
class AutoReplyActivity : Activity() {

    private val rules = mutableListOf<AutoReplyManager.Rule>()
    private lateinit var rulesContainer: LinearLayout
    private lateinit var addButton: MaterialButton
    private lateinit var contentScroll: ScrollView

    private data class RuleViews(
        val triggersInput: EditText,
        val replyInput: EditText,
        val toggle: SwitchMaterial
    )
    private val ruleViews = mutableListOf<RuleViews>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rules.addAll(AutoReplyManager.getRules(this))

        val root = UiKit.pageRoot(this)
        addToolbar(root)
        root.addView(UiKit.body(this, "Cat replies automatically when these keywords are detected", muted = true))

        rulesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(rulesContainer)

        addButton = outlinedButton("+ Add Rule") {
            collectEdits()
            if (rules.size >= AutoReplyManager.MAX_RULES) return@outlinedButton
            rules.add(AutoReplyManager.Rule(triggers = "", reply = "", enabled = true))
            renderRules()
        }
        root.addView(addButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = UiKit.dp(this@AutoReplyActivity, 16) })

        root.addView(UiKit.primaryButton(this, "Save") {
            collectEdits()
            val valid = rules.filter { it.triggers.isNotBlank() && it.reply.isNotBlank() }
            AutoReplyManager.saveRules(this@AutoReplyActivity, valid)
            Toast.makeText(this@AutoReplyActivity, "Saved ${valid.size} rules ✓", Toast.LENGTH_SHORT).show()
            finish()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = UiKit.dp(this@AutoReplyActivity, 12) })

        contentScroll = ScrollView(this).apply {
            setBackgroundColor(UiKit.surfaceColor(this@AutoReplyActivity))
            addView(root)
        }
        setContentView(contentScroll)
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

        renderRules()
    }

    private fun addToolbar(root: LinearLayout) {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, UiKit.dp(this@AutoReplyActivity, 16))
        }
        toolbar.addView(TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(UiKit.onSurfaceColor(this@AutoReplyActivity))
            setPadding(0, 0, UiKit.dp(this@AutoReplyActivity, 12), 0)
            setOnClickListener { finish() }
        })
        toolbar.addView(UiKit.headline(this, "Auto-Reply Rules"))
        root.addView(toolbar)
    }

    private fun renderRules() {
        rulesContainer.removeAllViews()
        ruleViews.clear()

        if (rules.isEmpty()) {
            rulesContainer.addView(emptyStateCard())
        }

        rules.forEachIndexed { index, rule ->
            rulesContainer.addView(buildRuleCard(index, rule))
        }

        addButton.visibility =
            if (rules.size >= AutoReplyManager.MAX_RULES) View.GONE
            else View.VISIBLE
    }

    private fun emptyStateCard(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = UiKit.dp(this@AutoReplyActivity, 16).toFloat()
            cardElevation = UiKit.dp(this@AutoReplyActivity, 1).toFloat()
            strokeWidth = 1
            strokeColor = 0x556B6578
            setCardBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurfaceVariant,
                    0xFF2E2A3A.toInt()
                )
            )
        }
        card.addView(TextView(this).apply {
            text = "No rules yet. Add one below — e.g. trigger \"price, cost, how much\" with your rate card as the reply."
            textSize = 14f
            setTextColor(UiKit.mutedColor(this@AutoReplyActivity))
            setPadding(
                UiKit.dp(this@AutoReplyActivity, 20),
                UiKit.dp(this@AutoReplyActivity, 16),
                UiKit.dp(this@AutoReplyActivity, 20),
                UiKit.dp(this@AutoReplyActivity, 16)
            )
        })
        return card
    }

    private fun buildRuleCard(index: Int, rule: AutoReplyManager.Rule): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = UiKit.dp(this@AutoReplyActivity, 16).toFloat()
            cardElevation = UiKit.dp(this@AutoReplyActivity, 2).toFloat()
            strokeWidth = 1
            strokeColor = 0x556B6578
            setCardBackgroundColor(0xFF25252C.toInt())
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(this@AutoReplyActivity, 20),
                UiKit.dp(this@AutoReplyActivity, 16),
                UiKit.dp(this@AutoReplyActivity, 20),
                UiKit.dp(this@AutoReplyActivity, 16)
            )
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, UiKit.dp(this@AutoReplyActivity, 12))
        }
        headerRow.addView(TextView(this).apply {
            text = "Rule ${index + 1}"
            textSize = 16f
            typeface = UiKit.headingTypeface(this@AutoReplyActivity)
            setTextColor(UiKit.onSurfaceColor(this@AutoReplyActivity))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val toggle = SwitchMaterial(this).apply {
            isChecked = rule.enabled
            styleSwitch(this)
        }
        headerRow.addView(toggle)
        headerRow.addView(ImageButton(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@AutoReplyActivity, R.drawable.ic_delete_outline))
            imageTintList = ColorStateList.valueOf(UiKit.mutedColor(this@AutoReplyActivity))
            background = null
            contentDescription = "Delete rule"
            minimumWidth = UiKit.dp(this@AutoReplyActivity, 48)
            minimumHeight = UiKit.dp(this@AutoReplyActivity, 48)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(
                UiKit.dp(this@AutoReplyActivity, 12),
                UiKit.dp(this@AutoReplyActivity, 12),
                0,
                UiKit.dp(this@AutoReplyActivity, 12)
            )
            setOnClickListener {
                collectEdits()
                rules.removeAt(index)
                renderRules()
            }
        })
        content.addView(headerRow)

        val (triggersLayout, triggersInput) = outlinedField(
            label = "Trigger keywords",
            placeholder = "Comma-separated, e.g. price, cost, rate, how much",
            value = rule.triggers,
            minLines = 2
        )
        content.addView(triggersLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = UiKit.dp(this@AutoReplyActivity, 12) })

        val (replyLayout, replyInput) = outlinedField(
            label = "Auto-reply message",
            placeholder = "Message sent when a keyword matches",
            value = rule.reply,
            minLines = 3
        )
        content.addView(replyLayout)

        ruleViews.add(RuleViews(triggersInput, replyInput, toggle))
        card.addView(content)

        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = UiKit.dp(this@AutoReplyActivity, 16)
        }
        return card
    }

    private fun outlinedField(
        label: String,
        placeholder: String,
        value: String,
        minLines: Int = 1
    ): Pair<TextInputLayout, EditText> {
        val accent = UiKit.primaryColor(this)
        val input = TextInputEditText(this).apply {
            setTextColor(UiKit.onSurfaceColor(this@AutoReplyActivity))
            textSize = 15f
            if (value.isNotEmpty()) setText(value)
            background = null
            setPadding(
                UiKit.dp(this@AutoReplyActivity, 4),
                UiKit.dp(this@AutoReplyActivity, 8),
                UiKit.dp(this@AutoReplyActivity, 4),
                UiKit.dp(this@AutoReplyActivity, 8)
            )
            if (minLines > 1) {
                this.minLines = minLines
                gravity = Gravity.TOP or Gravity.START
                setSingleLine(false)
            } else {
                setSingleLine(true)
                maxLines = 1
            }
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) scrollFieldIntoView(v)
            }
        }
        val layout = TextInputLayout(
            ContextThemeWrapper(
                this,
                com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox
            )
        ).apply {
            hint = label
            placeholderText = placeholder
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxBackgroundColor(0xFF25252C.toInt())
            setBoxStrokeColorStateList(ColorStateList.valueOf(accent))
            defaultHintTextColor = ColorStateList.valueOf(UiKit.mutedColor(this@AutoReplyActivity))
            setHintTextColor(ColorStateList.valueOf(UiKit.mutedColor(this@AutoReplyActivity)))
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
            strokeWidth = UiKit.dp(this@AutoReplyActivity, 1)
            backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            cornerRadius = UiKit.dp(this@AutoReplyActivity, 24)
            minHeight = UiKit.dp(this@AutoReplyActivity, 48)
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onClick() }
        }
    }

    private fun styleSwitch(sw: SwitchMaterial) {
        val accent = UiKit.primaryColor(this)
        val trackOff = 0x556B6578
        sw.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0x804A3F6B.toInt(), trackOff)
        )
        sw.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, 0xFF6B6578.toInt())
        )
    }

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
