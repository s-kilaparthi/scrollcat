package com.skilaparthi.scrollcat

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Log of auto-replies the cat sent — clear entries you've already reviewed.
 */
class AutoReplyTrackerActivity : Activity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var todayCountLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = UiKit.pageRoot(this)
        addToolbar(root)
        root.addView(
            UiKit.body(
                this,
                "Auto-replies the cat sent for you — tap ✕ to clear one",
                muted = true
            )
        )

        todayCountLabel = TextView(this).apply {
            textSize = 15f
            typeface = UiKit.headingTypeface(this@AutoReplyTrackerActivity)
            setTextColor(UiKit.primaryColor(this@AutoReplyTrackerActivity))
            setPadding(0, 0, 0, UiKit.dp(this@AutoReplyTrackerActivity, 12))
        }
        root.addView(todayCountLabel)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(listContainer)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(UiKit.surfaceColor(this@AutoReplyTrackerActivity))
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

        renderList()
    }

    override fun onResume() {
        super.onResume()
        if (::listContainer.isInitialized) renderList()
    }

    private fun renderList() {
        listContainer.removeAllViews()
        val all = AutoReplyManager.getTrackerEntries(this)
        val todayCount = all.count { isSameCalendarDay(it.timestamp, System.currentTimeMillis()) }
        todayCountLabel.text = when (todayCount) {
            1 -> "1 auto-reply sent today"
            else -> "$todayCount auto-replies sent today"
        }

        val pending = all.filter { !it.cleared }
        when {
            pending.isNotEmpty() -> pending.forEach { entry ->
                listContainer.addView(buildEntryCard(entry))
            }
            all.isNotEmpty() -> listContainer.addView(
                emptyStateCard("All caught up! Every auto-reply has been cleared.")
            )
            else -> listContainer.addView(
                emptyStateCard(
                    "No auto-replies sent yet. When a keyword rule fires, it'll show up here."
                )
            )
        }
    }

    private fun addToolbar(root: LinearLayout) {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, UiKit.dp(this@AutoReplyTrackerActivity, 16))
        }
        toolbar.addView(TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(UiKit.onSurfaceColor(this@AutoReplyTrackerActivity))
            setPadding(0, 0, UiKit.dp(this@AutoReplyTrackerActivity, 12), 0)
            setOnClickListener { finish() }
        })
        toolbar.addView(UiKit.headline(this, "Auto Reply Tracker"))
        root.addView(toolbar)
    }

    private fun emptyStateCard(message: String): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = UiKit.dp(this@AutoReplyTrackerActivity, 16).toFloat()
            cardElevation = UiKit.dp(this@AutoReplyTrackerActivity, 1).toFloat()
            strokeWidth = 1
            strokeColor = 0x556B6578
            setCardBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurfaceVariant,
                    0xFF2E2A3A.toInt()
                )
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiKit.dp(this@AutoReplyTrackerActivity, 12) }
        }
        card.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(UiKit.mutedColor(this@AutoReplyTrackerActivity))
            setPadding(
                UiKit.dp(this@AutoReplyTrackerActivity, 20),
                UiKit.dp(this@AutoReplyTrackerActivity, 16),
                UiKit.dp(this@AutoReplyTrackerActivity, 20),
                UiKit.dp(this@AutoReplyTrackerActivity, 16)
            )
        })
        return card
    }

    private fun buildEntryCard(entry: AutoReplyManager.TrackerEntry): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = UiKit.dp(this@AutoReplyTrackerActivity, 16).toFloat()
            cardElevation = UiKit.dp(this@AutoReplyTrackerActivity, 2).toFloat()
            strokeWidth = 1
            strokeColor = 0x556B6578
            setCardBackgroundColor(0xFF25252C.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = UiKit.dp(this@AutoReplyTrackerActivity, 12) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(this@AutoReplyTrackerActivity, 16),
                UiKit.dp(this@AutoReplyTrackerActivity, 14),
                UiKit.dp(this@AutoReplyTrackerActivity, 16),
                UiKit.dp(this@AutoReplyTrackerActivity, 14)
            )
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(appIconView(entry.packageName, sizeDp = 36).apply {
            layoutParams = LinearLayout.LayoutParams(
                UiKit.dp(this@AutoReplyTrackerActivity, 36),
                UiKit.dp(this@AutoReplyTrackerActivity, 36)
            ).apply { marginEnd = UiKit.dp(this@AutoReplyTrackerActivity, 12) }
        })
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = entry.sender.ifBlank { "Unknown" }
            textSize = 15f
            typeface = UiKit.headingTypeface(this@AutoReplyTrackerActivity)
            setTextColor(UiKit.onSurfaceColor(this@AutoReplyTrackerActivity))
        })
        titleCol.addView(TextView(this).apply {
            text = formatEntryWhen(entry.timestamp)
            textSize = 12f
            setTextColor(UiKit.mutedColor(this@AutoReplyTrackerActivity))
            setPadding(0, UiKit.dp(this@AutoReplyTrackerActivity, 2), 0, 0)
        })
        headerRow.addView(titleCol)
        headerRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_close_zone_x)
            imageTintList = android.content.res.ColorStateList.valueOf(
                UiKit.mutedColor(this@AutoReplyTrackerActivity)
            )
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Clear"
            setPadding(
                UiKit.dp(this@AutoReplyTrackerActivity, 12),
                UiKit.dp(this@AutoReplyTrackerActivity, 12),
                UiKit.dp(this@AutoReplyTrackerActivity, 4),
                UiKit.dp(this@AutoReplyTrackerActivity, 12)
            )
            minimumWidth = UiKit.dp(this@AutoReplyTrackerActivity, 48)
            minimumHeight = UiKit.dp(this@AutoReplyTrackerActivity, 48)
            layoutParams = LinearLayout.LayoutParams(
                UiKit.dp(this@AutoReplyTrackerActivity, 48),
                UiKit.dp(this@AutoReplyTrackerActivity, 48)
            )
            setOnClickListener {
                AutoReplyManager.clearTrackerEntry(this@AutoReplyTrackerActivity, entry.id)
                renderList()
            }
        })
        content.addView(headerRow)

        content.addView(TextView(this).apply {
            text = entry.message
            textSize = 14f
            setTextColor(UiKit.onSurfaceColor(this@AutoReplyTrackerActivity))
            setPadding(0, UiKit.dp(this@AutoReplyTrackerActivity, 10), 0, 0)
        })

        card.addView(content)
        return card
    }

    private fun formatEntryWhen(timestampMs: Long): String {
        if (timestampMs <= 0L) return ""
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMs))
        val now = System.currentTimeMillis()
        return if (isSameCalendarDay(timestampMs, now)) {
            "Today, $time"
        } else {
            val date = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestampMs))
            "$date, $time"
        }
    }

    private fun isSameCalendarDay(aMs: Long, bMs: Long): Boolean {
        val calA = Calendar.getInstance().apply { timeInMillis = aMs }
        val calB = Calendar.getInstance().apply { timeInMillis = bMs }
        return calA.get(Calendar.YEAR) == calB.get(Calendar.YEAR) &&
            calA.get(Calendar.DAY_OF_YEAR) == calB.get(Calendar.DAY_OF_YEAR)
    }

    private fun appIconView(packageName: String, sizeDp: Int): ImageView {
        val size = UiKit.dp(this, sizeDp)
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            imageTintList = null
            clearColorFilter()
            setImageDrawable(null)
            try {
                setImageDrawable(packageManager.getApplicationIcon(packageName))
            } catch (_: Exception) {
                setImageResource(android.R.drawable.sym_def_app_icon)
            }
        }
    }
}
