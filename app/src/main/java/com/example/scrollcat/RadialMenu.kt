package com.example.scrollcat

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class RadialMenu(
    private val context: Context,
    private val windowManager: WindowManager
) {

    data class MenuItem(
        val icon: String,
        val label: String,
        val action: String,
        val offsetX: Int,
        val offsetY: Int
    )

    private val menuItems = listOf(
        MenuItem("🤖", "AI", "ai", 0, -220),      // top
        MenuItem("📍", "Move", "move", 0, 220)     // bottom
    )

    private val menuViews = mutableListOf<Pair<TextView, WindowManager.LayoutParams>>()
    private var highlightedAction: String? = null
    var isShowing = false

    fun show(catX: Int, catY: Int, catSize: Int) {
        if (isShowing) return
        isShowing = true
        highlightedAction = null

        val centerX = catX + catSize / 2
        val centerY = catY + catSize / 2

        menuItems.forEach { item ->
            val view = TextView(context).apply {
                text = item.icon
                textSize = 28f
                gravity = Gravity.CENTER
                setPadding(24, 24, 24, 24)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xCC1A1A1A.toInt())
                    setStroke(2, 0x88FFFFFF.toInt())
                }
            }

            val size = 120
            val params = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = centerX - size / 2 + item.offsetX
                y = centerY - size / 2 + item.offsetY
            }

            try {
                if (view.parent == null) {
                    windowManager.addView(view, params)
                }
                menuViews.add(Pair(view, params))
            } catch (e: Exception) {
                android.util.Log.w("ScrollCat", "RadialMenu addView failed: ${e.message}")
            }
        }
    }

    fun updateHighlight(fingerX: Float, fingerY: Float, catX: Int, catY: Int, catSize: Int) {
        if (!isShowing) return

        val centerX = catX + catSize / 2f
        val centerY = catY + catSize / 2f
        val dx = fingerX - centerX
        val dy = fingerY - centerY
        val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        // Only highlight if finger moved away from center
        if (distance < 80) {
            highlightedAction = null
            menuViews.forEachIndexed { index, (view, _) ->
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xCC1A1A1A.toInt())
                    setStroke(2, 0x88FFFFFF.toInt())
                }
            }
            return
        }

        // Find nearest menu item
        var nearestIndex = 0
        var nearestDistance = Float.MAX_VALUE

        menuItems.forEachIndexed { index, item ->
            val itemDx = dx - item.offsetX
            val itemDy = dy - item.offsetY
            val d = Math.sqrt((itemDx * itemDx + itemDy * itemDy).toDouble()).toFloat()
            if (d < nearestDistance) {
                nearestDistance = d
                nearestIndex = index
            }
        }

        highlightedAction = menuItems[nearestIndex].action

        // Update visual highlight
        menuViews.forEachIndexed { index, (view, _) ->
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (index == nearestIndex) {
                    setColor(0xFF4A90D9.toInt()) // blue highlight
                    setStroke(3, 0xFFFFFFFF.toInt())
                } else {
                    setColor(0xCC1A1A1A.toInt())
                    setStroke(2, 0x88FFFFFF.toInt())
                }
            }
        }
    }

    fun getHighlightedAction(): String? = highlightedAction

    fun dismiss() {
        menuViews.forEach { (view, _) ->
            try {
                if (view.parent != null) windowManager.removeView(view)
            } catch (e: Exception) {
                android.util.Log.w("ScrollCat", "RadialMenu removeView failed: ${e.message}")
            }
        }
        menuViews.clear()
        isShowing = false
        highlightedAction = null
    }

    fun destroy() {
        dismiss()
    }
}
