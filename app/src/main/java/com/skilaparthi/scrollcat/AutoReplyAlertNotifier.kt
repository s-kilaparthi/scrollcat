package com.skilaparthi.scrollcat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Immediate alert whenever a keyword auto-reply rule fires successfully.
 * Separate channel from [DailyDigestNotifier] so users can mute this alone.
 */
object AutoReplyAlertNotifier {

    private const val CHANNEL_ID = "scrollcat_auto_reply_alerts"
    private const val CHANNEL_NAME = "Auto-Reply Alerts"
    private const val NOTIFICATION_ID_BASE = 43000
    private val nextId = AtomicInteger(0)

    fun notify(
        context: Context,
        sender: String,
        matchedKeyword: String,
        replyText: String
    ) {
        val appCtx = context.applicationContext
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                android.util.Log.d(
                    "ScrollCat",
                    "Auto-reply alert skipped — POST_NOTIFICATIONS not granted"
                )
                return
            }
        }

        ensureChannel(appCtx)

        val displaySender = sender.ifBlank { "someone" }
        val keyword = matchedKeyword.ifBlank { "?" }
        val reply = replyText.trim().ifBlank { "(empty reply)" }
        val title = "Auto-reply sent to $displaySender"
        val body = "Keyword: \"$keyword\" → \"$reply\""

        val open = PendingIntent.getActivity(
            appCtx,
            NOTIFICATION_ID_BASE,
            Intent(appCtx, AutoReplyTrackerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        val nm = appCtx.getSystemService(NotificationManager::class.java) ?: return
        val id = NOTIFICATION_ID_BASE + (nextId.getAndIncrement() % 1000)
        nm.notify(id, notification)
        android.util.Log.d(
            "ScrollCat",
            "Auto-reply alert posted — sender=$displaySender keyword=$keyword"
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts whenever an auto-reply rule fires"
            }
        )
    }
}
