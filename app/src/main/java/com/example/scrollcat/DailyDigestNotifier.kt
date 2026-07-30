package com.example.scrollcat

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
import java.util.Calendar

/**
 * Once-per-day summary of AI reply + voice-to-text usage.
 *
 * Trigger: any app entry point that calls [maybeShow] at/after 8:00 PM local time,
 * when today's digest has not been posted yet and at least one counted activity
 * exists for the day. No AlarmManager — fires when the user (or overlay) is active
 * after 8 PM, which is reliable without exact-alarm permission.
 */
object DailyDigestNotifier {

    private const val CHANNEL_ID = "scrollcat_daily_summary"
    private const val CHANNEL_NAME = "Daily Summary"
    private const val NOTIFICATION_ID = 42042
    private const val PREFS_NAME = "scrollcat_daily_digest"
    private const val KEY_SHOWN_DATE = "digest_shown_date"
    /** Local hour (0–23) at/after which the digest may fire. */
    private const val DIGEST_HOUR = 20

    fun maybeShow(context: Context) {
        val appCtx = context.applicationContext
        if (!isAtOrAfterDigestHour()) return
        if (alreadyShownToday(appCtx)) return

        val replies = StatsTracker.getRepliesSentToday(appCtx)
        val voiceUses = StatsTracker.getVoiceUsesToday(appCtx)
        if (replies <= 0 && voiceUses <= 0) return

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                android.util.Log.d(
                    "ScrollCat",
                    "Daily digest skipped — POST_NOTIFICATIONS not granted"
                )
                return
            }
        }

        ensureChannel(appCtx)

        val replyMinutes = StatsTracker.getMinutesSavedToday(appCtx)
        val voiceMinutes = StatsTracker.getVoiceMinutesSavedToday(appCtx)
        val lines = mutableListOf<String>()
        if (replies > 0) {
            lines.add(
                "Your AI cat replied to $replies messages today — saved ~$replyMinutes min of typing"
            )
        }
        if (voiceUses > 0) {
            lines.add(
                "You used Voice-to-text $voiceUses times today — saved ~$voiceMinutes min"
            )
        }
        if (lines.isEmpty()) return

        val title = "ScrollCat daily summary"
        val collapsed = lines.joinToString(" · ")
        val open = PendingIntent.getActivity(
            appCtx,
            0,
            Intent(appCtx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pets)
            .setContentTitle(title)
            .setContentText(collapsed)
            .setStyle(
                NotificationCompat.InboxStyle()
                    .setBigContentTitle(title)
                    .also { style -> lines.forEach { style.addLine(it) } }
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        val nm = appCtx.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, builder.build())
        markShownToday(appCtx)
        android.util.Log.d(
            "ScrollCat",
            "Daily digest posted — replies=$replies voice=$voiceUses"
        )
    }

    private fun isAtOrAfterDigestHour(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= DIGEST_HOUR
    }

    private fun todayKey(): String {
        val cal = Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun alreadyShownToday(context: Context): Boolean =
        prefs(context).getString(KEY_SHOWN_DATE, "") == todayKey()

    private fun markShownToday(context: Context) {
        prefs(context).edit().putString(KEY_SHOWN_DATE, todayKey()).apply()
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
                description = "Once-a-day summary of replies and voice-to-text usage"
            }
        )
    }
}
