package com.example.scrollcat

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating panel shown above the cat with 3 AI reply suggestions for the
 * newest replyable message. Tapping a suggestion sends it via RemoteInput
 * and shows a "Sent to [Name] ✓" confirmation — no app opening needed.
 */
class ReplyPanel(
    private val context: Context,
    private val windowManager: WindowManager
) {

    companion object {
        private const val PANEL_WIDTH = 680
        private const val CONFIRMATION_MS = 3000L
        private const val ACCENT = 0xFF4A90D9.toInt()
        private const val PANEL_BG = 0xF21A1A2E.toInt()
        private const val CHIP_BG = 0xFF2A2A45.toInt()
    }

    var isShowing = false
        private set

    private var panelView: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    // Routes to Claude when an API key is set, on-device Gemini Nano otherwise
    private val generator = ClaudeReplyGenerator(context)
    private var pending: MutableList<ReplyStore.ReplyableMessage> = mutableListOf()
    private var current: ReplyStore.ReplyableMessage? = null
    var onDismissed: (() -> Unit)? = null

    fun show(catX: Int, catY: Int, catSize: Int) {
        pending = ReplyStore.getAll().toMutableList()
        if (pending.isEmpty()) return
        dismiss()
        isShowing = true

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            background = GradientDrawable().apply {
                setColor(PANEL_BG)
                cornerRadius = 36f
                setStroke(2, 0x33FFFFFF)
            }
        }

        val dm = context.resources.displayMetrics
        val x = (catX + catSize / 2 - PANEL_WIDTH / 2)
            .coerceIn(16, (dm.widthPixels - PANEL_WIDTH - 16).coerceAtLeast(16))
        // Rough panel height; final height wraps content
        val y = (catY - 520).coerceAtLeast(60)

        val params = WindowManager.LayoutParams(
            PANEL_WIDTH,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        windowManager.addView(panel, params)
        panelView = panel

        showMessage(pending.first())
    }

    private fun showMessage(message: ReplyStore.ReplyableMessage) {
        current = message
        val panel = panelView ?: return
        panel.removeAllViews()

        // ── Header: sender + app + close ──
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "💬 ${message.sender}"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(context).apply {
            text = appLabel(message.packageName)
            textSize = 11f
            setTextColor(0xFF9999BB.toInt())
            setPadding(0, 0, 16, 0)
        })
        header.addView(TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(0xFF9999BB.toInt())
            setPadding(12, 4, 4, 4)
            setOnClickListener { dismiss() }
        })
        panel.addView(header)

        // ── Incoming message preview ──
        panel.addView(TextView(context).apply {
            text = if (message.message.length > 140) message.message.take(140) + "…" else message.message
            textSize = 13f
            setTextColor(0xFFCCCCDD.toInt())
            setPadding(0, 10, 0, 16)
        })

        // ── Suggestions container (starts as loading state) ──
        val suggestionsBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val loading = TextView(context).apply {
            text = "🐾 Cat is thinking…"
            textSize = 13f
            setTextColor(0xFF9999BB.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }
        suggestionsBox.addView(loading)
        panel.addView(suggestionsBox)

        // ── 4th option: reply manually in the app ──
        panel.addView(TextView(context).apply {
            text = "↗ Reply in app"
            textSize = 13f
            setTextColor(0xFFAACCFF.toInt())
            gravity = Gravity.CENTER
            setPadding(24, 14, 24, 14)
            background = GradientDrawable().apply {
                setColor(0x00000000)
                cornerRadius = 28f
                setStroke(1, ACCENT)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 6, 0, 0) }
            setOnClickListener {
                ReplySender.openApp(context, message)
                ReplyStore.remove(message.notificationKey)
                dismiss()
            }
        })

        // ── Footer: remaining conversations ──
        if (pending.size > 1) {
            panel.addView(TextView(context).apply {
                text = "→ ${pending.size - 1} more waiting · tap to skip"
                textSize = 11f
                setTextColor(0xFF7777AA.toInt())
                gravity = Gravity.END
                setPadding(0, 12, 0, 0)
                setOnClickListener { advance() }
            })
        }

        generator.generateReplies(message.sender, message.message) { suggestions, engine ->
            // User may have closed the panel or skipped to another message
            if (!isShowing || current != message) return@generateReplies
            suggestionsBox.removeAllViews()
            if (suggestions.isEmpty()) {
                suggestionsBox.addView(TextView(context).apply {
                    text = "😿 Couldn't think of a reply"
                    textSize = 13f
                    setTextColor(0xFF9999BB.toInt())
                    gravity = Gravity.CENTER
                    setPadding(0, 12, 0, 12)
                })
                return@generateReplies
            }
            android.util.Log.d("ScrollCat", "Replies from $engine: $suggestions")
            suggestions.forEach { suggestion ->
                suggestionsBox.addView(suggestionChip(suggestion, message))
            }
        }
    }

    private fun suggestionChip(text: String, message: ReplyStore.ReplyableMessage): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(24, 18, 24, 18)
            background = GradientDrawable().apply {
                setColor(CHIP_BG)
                cornerRadius = 28f
                setStroke(1, 0x44FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 6, 0, 6) }
            setOnClickListener { sendReply(message, text) }
        }
    }

    private fun sendReply(message: ReplyStore.ReplyableMessage, replyText: String) {
        val sent = ReplySender.send(context, message, replyText)
        ReplyStore.remove(message.notificationKey)
        pending.remove(message)
        if (sent) {
            OverlayService.instance?.clearBadge()
            showConfirmation("Sent to ${message.sender} ✓")
        } else {
            // RemoteInput unusable — fall back to opening the conversation
            ReplySender.openApp(context, message)
            dismiss()
        }
    }

    private fun showConfirmation(text: String) {
        val panel = panelView ?: return
        panel.removeAllViews()
        panel.addView(TextView(context).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 28)
        })
        handler.postDelayed({
            if (!isShowing) return@postDelayed
            if (pending.isNotEmpty()) {
                showMessage(pending.first())
            } else {
                dismiss()
            }
        }, CONFIRMATION_MS)
    }

    private fun advance() {
        if (pending.size <= 1) return
        // Move current conversation to the back of the queue
        val first = pending.removeAt(0)
        pending.add(first)
        showMessage(pending.first())
    }

    private fun appLabel(packageName: String): String {
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        panelView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { }
        }
        panelView = null
        current = null
        if (isShowing) {
            isShowing = false
            onDismissed?.invoke()
        }
    }

    fun destroy() {
        dismiss()
        generator.close()
    }
}
