package com.example.scrollcat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

object UiKit {
    private const val AMBER = 0xFFD97706.toInt()
    private const val AMBER_CONTAINER = 0xFFFFE0B2.toInt()
    private const val ON_AMBER_CONTAINER = 0xFF3F2200.toInt()
    private const val TEAL_CONTAINER = 0xFFCCFBF1.toInt()
    private const val ON_TEAL_CONTAINER = 0xFF042F2E.toInt()
    private const val SURFACE = 0xFFFFF8F0.toInt()
    private const val ON_SURFACE = 0xFF241A12.toInt()
    private const val MUTED = 0xFF6F5F50.toInt()
    private const val OUTLINE = 0x338C7A68

    fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    fun surfaceColor(context: Context): Int {
        return MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, SURFACE)
    }

    fun onSurfaceColor(context: Context): Int {
        return MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, ON_SURFACE)
    }

    fun primaryColor(context: Context): Int {
        return MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, AMBER)
    }

    fun mutedColor(context: Context): Int = MUTED

    fun pageRoot(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 32), dp(context, 24), dp(context, 24))
            setBackgroundColor(surfaceColor(context))
        }
    }

    fun section(
        parent: LinearLayout,
        title: String? = null,
        subtitle: String? = null,
        build: LinearLayout.() -> Unit
    ): MaterialCardView {
        val context = parent.context
        val card = MaterialCardView(context).apply {
            radius = dp(context, 16).toFloat()
            cardElevation = dp(context, 2).toFloat()
            strokeWidth = 1
            strokeColor = OUTLINE
            setCardBackgroundColor(surfaceColor(context))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 18))
        }
        title?.let { content.addView(sectionTitle(context, it)) }
        subtitle?.let { content.addView(body(context, it, muted = true)) }
        content.build()
        card.addView(content)
        parent.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(context, 12)) })
        return card
    }

    fun headline(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onSurfaceColor(context))
            setPadding(0, 0, 0, dp(context, 8))
        }
    }

    fun sectionTitle(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onSurfaceColor(context))
            setPadding(0, 0, 0, dp(context, 8))
        }
    }

    fun body(context: Context, text: String, muted: Boolean = false): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 16f
            setTextColor(if (muted) MUTED else onSurfaceColor(context))
            setPadding(0, 0, 0, dp(context, 8))
        }
    }

    fun label(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(onSurfaceColor(context))
            setPadding(0, dp(context, 8), 0, dp(context, 6))
        }
    }

    fun tonalButton(context: Context, label: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(context).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            cornerRadius = dp(context, 24)
            minHeight = dp(context, 48)
            insetTop = 0
            insetBottom = 0
            backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorSecondaryContainer,
                    TEAL_CONTAINER
                )
            )
            setTextColor(
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorOnSecondaryContainer,
                    ON_TEAL_CONTAINER
                )
            )
            setOnClickListener { onClick() }
        }
    }

    fun primaryButton(context: Context, label: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(context).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            cornerRadius = dp(context, 24)
            minHeight = dp(context, 48)
            insetTop = 0
            insetBottom = 0
            backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorPrimaryContainer,
                    AMBER_CONTAINER
                )
            )
            setTextColor(
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorOnPrimaryContainer,
                    ON_AMBER_CONTAINER
                )
            )
            setOnClickListener { onClick() }
        }
    }

    fun addButton(parent: LinearLayout, button: MaterialButton) {
        parent.addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(parent.context, 6), 0, dp(parent.context, 6)) })
    }

    fun statusRow(context: Context, label: String, ok: Boolean, detail: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 6), 0, dp(context, 6))
            addView(TextView(context).apply {
                text = if (ok) "✓" else "!"
                textSize = 16f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (ok) 0xFF15803D.toInt() else 0xFFB42318.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(context, 28), dp(context, 28))
            })
            addView(TextView(context).apply {
                text = "$label: $detail"
                textSize = 15f
                setTextColor(onSurfaceColor(context))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    fun spacer(context: Context, heightDp: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, heightDp)
            )
        }
    }
}
