package com.skilaparthi.scrollcat

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
 * auto-saved on field focus-loss (and toggle/delete) — including incomplete
 * drafts — but only [AutoReplyManager.Rule.isQualified] rules fire on messages.
 */
class AutoReplyActivity : Activity() {

    private val rules = mutableListOf<AutoReplyManager.Rule>()
    private lateinit var rulesContainer: LinearLayout
    private lateinit var addButton: MaterialButton
    private lateinit var bulkToggleButton: MaterialButton
    private lateinit var contentScroll: ScrollView

    private data class RuleViews(
        val triggersInput: EditText,
        val replyInput: EditText,
        val toggle: SwitchMaterial,
        val incompleteLabel: TextView,
        val card: MaterialCardView,
        val titleLabel: TextView
    )
    private val ruleViews = mutableListOf<RuleViews>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rules.addAll(AutoReplyManager.getRules(this))

        val root = UiKit.pageRoot(this)
        addToolbar(root)
        root.addView(UiKit.body(this, "Cat replies automatically when these keywords are detected", muted = true))
        root.addView(summonRequiredTip())
        root.addView(
            UiKit.body(
                this,
                "Changes save when you leave a field. Rules need both a keyword and a reply to fire.",
                muted = true
            )
        )

        bulkToggleButton = outlinedButton("Activate All") {
            collectEdits()
            if (rules.isEmpty()) return@outlinedButton
            val activate = !rules.any { it.enabled }
            rules.forEach { it.enabled = activate }
            persistRules(showToast = false)
            renderRules()
            Toast.makeText(
                this,
                if (activate) "All rules activated" else "All rules deactivated",
                Toast.LENGTH_SHORT
            ).show()
        }
        root.addView(bulkToggleButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = UiKit.dp(this@AutoReplyActivity, 4)
            bottomMargin = UiKit.dp(this@AutoReplyActivity, 8)
        })

        rulesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(rulesContainer)

        addButton = outlinedButton("+ Add Rule") {
            collectEdits()
            if (rules.size >= AutoReplyManager.MAX_RULES) return@outlinedButton
            rules.add(AutoReplyManager.Rule(triggers = "", reply = "", enabled = true))
            persistRules(showToast = false)
            renderRules()
        }
        root.addView(addButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = UiKit.dp(this@AutoReplyActivity, 16) })

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

    override fun onPause() {
        collectEdits()
        persistRules(showToast = false)
        super.onPause()
    }

    private fun summonRequiredTip(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = UiKit.dp(this@AutoReplyActivity, 14).toFloat()
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
                topMargin = UiKit.dp(this@AutoReplyActivity, 4)
                bottomMargin = UiKit.dp(this@AutoReplyActivity, 12)
            }
        }
        card.addView(TextView(this).apply {
            text =
                "The cat needs to be summoned for auto-replies to work — they won't fire while the cat is dismissed."
            textSize = 13f
            setTextColor(0xFFF5F3F7.toInt())
            setPadding(
                UiKit.dp(this@AutoReplyActivity, 16),
                UiKit.dp(this@AutoReplyActivity, 14),
                UiKit.dp(this@AutoReplyActivity, 16),
                UiKit.dp(this@AutoReplyActivity, 14)
            )
        })
        return card
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
        refreshBulkToggleButton()
    }

    private fun refreshBulkToggleButton() {
        if (!::bulkToggleButton.isInitialized) return
        if (rules.isEmpty()) {
            bulkToggleButton.visibility = View.GONE
            return
        }
        bulkToggleButton.visibility = View.VISIBLE
        val anyEnabled = rules.any { it.enabled }
        bulkToggleButton.text = if (anyEnabled) "Deactivate All" else "Activate All"
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
            setPadding(0, 0, 0, UiKit.dp(this@AutoReplyActivity, 4))
        }
        val titleLabel = TextView(this).apply {
            text = "Rule ${index + 1}"
            textSize = 16f
            typeface = UiKit.headingTypeface(this@AutoReplyActivity)
            setTextColor(UiKit.onSurfaceColor(this@AutoReplyActivity))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleLabel)
        val toggle = SwitchMaterial(this).apply {
            isChecked = rule.enabled
            styleSwitch(this)
            setOnCheckedChangeListener { _, isChecked ->
                if (tag == "syncing") return@setOnCheckedChangeListener
                collectEdits()
                if (index < rules.size) rules[index].enabled = isChecked
                persistRules(showToast = true)
                refreshIncompleteUi()
                refreshBulkToggleButton()
            }
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
                persistRules(showToast = true)
                renderRules()
            }
        })
        content.addView(headerRow)

        val incompleteLabel = TextView(this).apply {
            text = "Incomplete — needs keyword and reply"
            textSize = 12f
            setTextColor(UiKit.mutedColor(this@AutoReplyActivity))
            setPadding(0, 0, 0, UiKit.dp(this@AutoReplyActivity, 10))
            visibility = if (rule.isQualified()) View.GONE else View.VISIBLE
        }
        content.addView(incompleteLabel)

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

        val views = RuleViews(triggersInput, replyInput, toggle, incompleteLabel, card, titleLabel)
        ruleViews.add(views)
        applyIncompleteStyle(views, rule.isQualified())
        card.addView(content)

        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = UiKit.dp(this@AutoReplyActivity, 16)
        }
        return card
    }

    private fun applyIncompleteStyle(views: RuleViews, qualified: Boolean) {
        views.incompleteLabel.visibility = if (qualified) View.GONE else View.VISIBLE
        views.card.alpha = if (qualified) 1f else 0.72f
        views.titleLabel.setTextColor(
            if (qualified) UiKit.onSurfaceColor(this)
            else UiKit.mutedColor(this)
        )
    }

    private fun refreshIncompleteUi() {
        ruleViews.forEachIndexed { index, views ->
            if (index < rules.size) {
                applyIncompleteStyle(views, rules[index].isQualified())
            }
        }
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
                if (hasFocus) {
                    scrollFieldIntoView(v)
                } else {
                    collectEdits()
                    persistRules(showToast = true)
                    refreshIncompleteUi()
                }
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

    private fun persistRules(showToast: Boolean) {
        AutoReplyManager.saveRules(this, rules)
        if (showToast) {
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
    }
}
