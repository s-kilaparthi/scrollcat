package com.example.scrollcat

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a shareable stats card ("My AI cat replied to X messages today!")
 * as a bitmap and shares it via ACTION_SEND. Viral growth loop: the card
 * carries ScrollCat branding into the user's stories/chats.
 */
class ShareCardActivity : Activity() {

    companion object {
        private const val TAG = "ScrollCat"
        private const val CARD_SIZE = 1080
        private const val ACCENT = 0xFFB39DDB.toInt()
    }

    private lateinit var cardBitmap: Bitmap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val replies = StatsTracker.getRepliesSentToday(this) + StatsTracker.getAutoRepliesToday(this)
        val minutes = StatsTracker.getMinutesSavedToday(this)
        cardBitmap = drawCard(replies, minutes)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF1A1A1E.toInt())
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "Share your cat's work 🐾"
            textSize = 18f
            typeface = UiKit.headingTypeface(this@ShareCardActivity)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        })

        root.addView(ImageView(this).apply {
            setImageBitmap(cardBitmap)
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(UiKit.primaryButton(this, "📤 Share") { shareCard() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 32, 0, 0) })

        root.addView(UiKit.tonalButton(this, "Close") { finish() })

        setContentView(root)
    }

    private fun drawCard(replies: Int, minutes: Int): Bitmap {
        val bmp = Bitmap.createBitmap(CARD_SIZE, CARD_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = CARD_SIZE / 2f

        // Background: dark with a subtle blue gradient from the top
        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, CARD_SIZE.toFloat(),
                intArrayOf(0xFF2E2A3A.toInt(), 0xFF1A1A1E.toInt(), 0xFF1A1A1E.toInt()),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, CARD_SIZE.toFloat(), CARD_SIZE.toFloat(), bgPaint)

        // Accent card behind the numbers
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF25252C.toInt() }
        canvas.drawRoundRect(RectF(90f, 400f, CARD_SIZE - 90f, 800f), 48f, 48f, panelPaint)

        val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 200f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("🐱", cx, 300f, emojiPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 56f
            textAlign = Paint.Align.CENTER
            typeface = UiKit.headingTypeface(this@ShareCardActivity)
        }
        canvas.drawText("My AI cat replied to", cx, 510f, titlePaint)

        val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT
            textSize = 150f
            textAlign = Paint.Align.CENTER
            typeface = UiKit.headingTypeface(this@ShareCardActivity)
        }
        canvas.drawText("$replies", cx, 670f, bigPaint)

        canvas.drawText("messages today!", cx, 760f, titlePaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFA39BB0.toInt()
            textSize = 44f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("⏱ Saved $minutes minutes of typing", cx, 880f, subPaint)

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT
            textSize = 46f
            textAlign = Paint.Align.CENTER
            typeface = UiKit.headingTypeface(this@ShareCardActivity)
        }
        canvas.drawText("🐱 ScrollCat — scrollcat.app", cx, 1000f, brandPaint)

        return bmp
    }

    private fun shareCard() {
        try {
            val cacheDir = File(cacheDir, "share").apply { mkdirs() }
            val file = File(cacheDir, "scrollcat_stats.png")
            FileOutputStream(file).use { out ->
                cardBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "My AI cat handles my DMs 🐱 Get ScrollCat: https://scrollcat.app")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Share your stats"))
        } catch (e: Exception) {
            Log.e(TAG, "Share failed: ${e.message}")
            Toast.makeText(this, "Couldn't share right now", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cardBitmap.isInitialized) cardBitmap.recycle()
    }
}
