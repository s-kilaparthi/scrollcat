package com.example.scrollcat

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class FeedbackActivity : Activity() {

    private lateinit var feedbackInput: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = UiKit.pageRoot(this)
        addToolbar(root)

        root.addView(TextView(this).apply {
            text = "Tell us what you love, what\u2019s confusing, or what the cat should do next."
            textSize = 14f
            setTextColor(UiKit.mutedColor(this@FeedbackActivity))
            setPadding(0, 0, 0, UiKit.dp(this@FeedbackActivity, 16))
        })

        val accent = UiKit.primaryColor(this)
        feedbackInput = TextInputEditText(this).apply {
            setTextColor(UiKit.onSurfaceColor(this@FeedbackActivity))
            textSize = 15f
            background = null
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
            setSingleLine(false)
            setPadding(
                UiKit.dp(this@FeedbackActivity, 4),
                UiKit.dp(this@FeedbackActivity, 8),
                UiKit.dp(this@FeedbackActivity, 4),
                UiKit.dp(this@FeedbackActivity, 8)
            )
        }
        val inputLayout = TextInputLayout(
            ContextThemeWrapper(
                this,
                com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox
            )
        ).apply {
            hint = "Your feedback"
            placeholderText = "Type your thoughts here\u2026"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxBackgroundColor(0xFF25252C.toInt())
            setBoxStrokeColorStateList(ColorStateList.valueOf(accent))
            defaultHintTextColor = ColorStateList.valueOf(UiKit.mutedColor(this@FeedbackActivity))
            setHintTextColor(ColorStateList.valueOf(UiKit.mutedColor(this@FeedbackActivity)))
            addView(
                feedbackInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            inputLayout,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, UiKit.dp(this@FeedbackActivity, 16)) }
        )

        UiKit.addButton(
            root,
            UiKit.primaryButton(this, "Submit") { submitFeedback() }
        )

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(UiKit.surfaceColor(this@FeedbackActivity))
            clipToPadding = false
            addView(root)
        }
        setContentView(scrollView)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                UiKit.dp(this, 24) + bars.left,
                UiKit.dp(this, 16) + bars.top,
                UiKit.dp(this, 24) + bars.right,
                UiKit.dp(this, 24) + maxOf(bars.bottom, ime.bottom)
            )
            insets
        }
    }

    private fun addToolbar(root: LinearLayout) {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, UiKit.dp(this@FeedbackActivity, 16))
        }
        toolbar.addView(TextView(this).apply {
            text = "\u2190"
            textSize = 24f
            setTextColor(UiKit.onSurfaceColor(this@FeedbackActivity))
            setPadding(0, 0, UiKit.dp(this@FeedbackActivity, 12), 0)
            setOnClickListener { finish() }
        })
        toolbar.addView(UiKit.headline(this, "Give Feedback"))
        root.addView(toolbar)
    }

    private fun submitFeedback() {
        val text = feedbackInput.text?.toString().orEmpty().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Write a little something first", Toast.LENGTH_SHORT).show()
            return
        }
        SettingsManager.addFeedback(this, text)
        feedbackInput.setText("")
        Toast.makeText(this, "Thanks for your feedback!", Toast.LENGTH_SHORT).show()
    }
}
