package com.example.scrollcat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

object UiKit {
    // Midnight Cat fallbacks (theme attrs preferred when available)
    private const val PRIMARY = 0xFFB39DDB.toInt()
    private const val PRIMARY_CONTAINER = 0xFF4A3F6B.toInt()
    private const val ON_PRIMARY_CONTAINER = 0xFFF5F3F7.toInt()
    private const val SECONDARY_CONTAINER = 0xFF2E2A3A.toInt()
    private const val ON_SECONDARY_CONTAINER = 0xFFE8E4EF.toInt()
    private const val SURFACE = 0xFF1A1A1E.toInt()
    private const val ON_SURFACE = 0xFFF5F3F7.toInt()
    private const val MUTED = 0xFFA39BB0.toInt()
    private const val OUTLINE = 0x556B6578

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
        return MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, PRIMARY)
    }

    fun mutedColor(context: Context): Int = MUTED

    fun headingTypeface(context: Context): Typeface {
        return try {
            ResourcesCompat.getFont(context, R.font.baloo2) ?: Typeface.DEFAULT_BOLD
        } catch (_: Exception) {
            Typeface.DEFAULT_BOLD
        }
    }

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
            setCardBackgroundColor(
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorSurfaceVariant,
                    0xFF25252C.toInt()
                )
            )
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
            typeface = headingTypeface(context)
            setTextColor(onSurfaceColor(context))
            setPadding(0, 0, 0, dp(context, 8))
        }
    }

    fun sectionTitle(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 18f
            typeface = headingTypeface(context)
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
                    SECONDARY_CONTAINER
                )
            )
            setTextColor(
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorOnSecondaryContainer,
                    ON_SECONDARY_CONTAINER
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
                    PRIMARY_CONTAINER
                )
            )
            setTextColor(
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorOnPrimaryContainer,
                    ON_PRIMARY_CONTAINER
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
                setTextColor(if (ok) 0xFF86EFAC.toInt() else 0xFFFCA5A5.toInt())
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
