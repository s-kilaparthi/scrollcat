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
    private var currentEntry: ReplyStore.ReplyableMessage? = null
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

        try {
            if (panel.parent == null) windowManager.addView(panel, params)
        } catch (e: Exception) {
            android.util.Log.w("ScrollCat", "ReplyPanel addView failed: ${e.message}")
            isShowing = false
            return
        }
        panelView = panel

        showMessage(pending.first())
    }

    private fun showMessage(message: ReplyStore.ReplyableMessage) {
        currentEntry = message
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

        // ── Suggestions container ──
        val chipsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        panel.addView(chipsContainer)

        // ── Bottom row: Reply in app + Ignore ──
        val bottomRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        }

        val replyInAppBtn = android.widget.TextView(context).apply {
            text = "↗ Reply in app"
            textSize = 13f
            setTextColor(0xFF4A90D9.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(16, 20, 16, 20)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF2a2a2a.toInt())
                cornerRadius = 24f
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = 6 }
        }

        val ignoreBtn = android.widget.TextView(context).apply {
            text = "✕ Ignore"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(16, 20, 16, 20)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF2a2a2a.toInt())
                cornerRadius = 24f
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = 6 }
        }

        // Reply in app click
        replyInAppBtn.setOnClickListener {
            Logger.d("Reply in app button clicked - entry: ${currentEntry?.packageName} contentIntent: ${currentEntry?.contentIntent}")
            val entry = currentEntry ?: return@setOnClickListener
            try {
                if (entry.contentIntent != null) {
                    val options = android.app.ActivityOptions.makeBasic().apply {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            setPendingIntentBackgroundActivityStartMode(
                                android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                            )
                        }
                    }
                    entry.contentIntent.send(context, 0, null, null, null, null, options.toBundle())
                } else {
                    val launchIntent = context.packageManager
                        .getLaunchIntentForPackage(entry.packageName)?.apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                    launchIntent?.let { context.startActivity(it) }
                }
            } catch (e: Exception) {
                try {
                    val launchIntent = context.packageManager
                        .getLaunchIntentForPackage(entry.packageName)?.apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    launchIntent?.let { context.startActivity(it) }
                } catch (e2: Exception) {
                    Logger.e("Failed to open app: ${e2.message}")
                }
            }
            // Clear badge and remove from store after opening app
            ReplyStore.remove(entry.notificationKey)
            OverlayService.instance?.updateBadgeAfterReply()
            dismiss()
        }

        // Ignore click - just dismiss and remove from store
        ignoreBtn.setOnClickListener {
            val entry = currentEntry ?: return@setOnClickListener
            ReplyStore.remove(entry.notificationKey)
            OverlayService.instance?.updateBadgeAfterReply()
            dismiss()
            Logger.d("Message ignored: ${entry.sender}")
        }

        bottomRow.addView(replyInAppBtn)
        bottomRow.addView(ignoreBtn)
        panel.addView(bottomRow)

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

        fun showThinkingState() {
            chipsContainer.removeAllViews()
            chipsContainer.addView(TextView(context).apply {
                text = "🐾 Cat is thinking…"
                textSize = 13f
                setTextColor(0xFF9999BB.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 12, 0, 12)
            })
        }

        fun showReplies(suggestions: List<String>, engine: String = "Pre-generated") {
            if (!isShowing || currentEntry != message) return
            chipsContainer.removeAllViews()
            if (engine == AiReplyGenerator.ENGINE_LIMIT_REACHED) {
                chipsContainer.addView(TextView(context).apply {
                    text = AiReplyGenerator.UPGRADE_MESSAGE
                    textSize = 13f
                    setTextColor(0xFFFFD37A.toInt())
                    gravity = Gravity.CENTER
                    setPadding(8, 12, 8, 12)
                    setOnClickListener {
                        val intent = android.content.Intent(context, SubscriptionActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        dismiss()
                    }
                })
                return
            }
            if (suggestions.isEmpty()) {
                chipsContainer.addView(TextView(context).apply {
                    text = "😿 Couldn't think of a reply"
                    textSize = 13f
                    setTextColor(0xFF9999BB.toInt())
                    gravity = Gravity.CENTER
                    setPadding(0, 12, 0, 12)
                })
                return
            }
            Logger.d("Replies from $engine: $suggestions")
            if (currentEntry?.hasRemoteInput != true) {
                chipsContainer.addView(TextView(context).apply {
                    text = "💡 Tap a suggestion to copy it, then paste in the app"
                    textSize = 12f
                    setTextColor(0xFF888888.toInt())
                    setPadding(16, 8, 16, 8)
                })
                suggestions.forEach { suggestion ->
                    val chip = TextView(context).apply {
                        text = suggestion
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
                        setOnClickListener {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("reply", suggestion)
                            )
                            android.widget.Toast.makeText(
                                context,
                                "Copied! Opening app...",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            replyInAppBtn.performClick()
                        }
                    }
                    chipsContainer.addView(chip)
                }
            } else {
                suggestions.forEach { suggestion ->
                    chipsContainer.addView(suggestionChip(suggestion, message))
                }
            }
        }

        // Check for pre-generated replies first
        val pregenerated = CatNotificationListener.instance?.getPregeneratedReplies(
            message.packageName,
            message.notificationId,
            message.sender,
            message.message
        )

        if (pregenerated != null && pregenerated.isNotEmpty()) {
            Logger.d("Using pre-generated replies - INSTANT!")
            showReplies(pregenerated)
            CatNotificationListener.instance?.clearPregeneratedReplies(
                message.packageName,
                message.notificationId,
                message.sender,
                message.message
            )
        } else {
            Logger.d("No pre-generated replies - generating now")
            showThinkingState()
            val aiGenerator = AiReplyGenerator(context)
            aiGenerator.generateReplies(message.sender, message.message) { replies, engine ->
                handler.post {
                    showReplies(replies, engine)
                }
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
        currentEntry = null
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
