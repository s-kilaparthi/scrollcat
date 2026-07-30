package com.example.scrollcat

import android.content.Context
import java.util.Calendar

/**
 * Daily reply stats for the share card, plus a lifetime counter.
 * Daily values reset automatically at midnight via a date key.
 */
object StatsTracker {

    private const val PREFS_NAME = "scrollcat_stats"
    private const val KEY_DATE = "date"
    private const val KEY_REPLIES_TODAY = "replies_sent_today"
    private const val KEY_AUTO_TODAY = "auto_replies_today"
    private const val KEY_VOICE_USES_TODAY = "voice_uses_today"
    private const val KEY_TOTAL = "total_replies_sent"

    /** Rough estimate of typing time saved per reply, for the share card. */
    private const val MINUTES_SAVED_PER_REPLY = 1.5

    /** Rough estimate of typing time saved per successful voice-to-text use. */
    private const val MINUTES_SAVED_PER_VOICE_USE = 1.5

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun todayKey(): String {
        val cal = Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** Resets today's counters if the stored date is stale. */
    private fun rollover(context: Context) {
        val p = prefs(context)
        if (p.getString(KEY_DATE, "") != todayKey()) {
            p.edit()
                .putString(KEY_DATE, todayKey())
                .putInt(KEY_REPLIES_TODAY, 0)
                .putInt(KEY_AUTO_TODAY, 0)
                .putInt(KEY_VOICE_USES_TODAY, 0)
                .apply()
        }
    }

    fun recordReplySent(context: Context) {
        rollover(context)
        val p = prefs(context)
        p.edit()
            .putInt(KEY_REPLIES_TODAY, p.getInt(KEY_REPLIES_TODAY, 0) + 1)
            .putInt(KEY_TOTAL, p.getInt(KEY_TOTAL, 0) + 1)
            .apply()
        DailyDigestNotifier.maybeShow(context)
    }

    fun recordAutoReply(context: Context) {
        rollover(context)
        val p = prefs(context)
        p.edit()
            .putInt(KEY_AUTO_TODAY, p.getInt(KEY_AUTO_TODAY, 0) + 1)
            .apply()
    }

    /** One successful voice transcription (Voice-to-text, Continue, or any-app dictation). */
    fun recordVoiceUse(context: Context) {
        rollover(context)
        val p = prefs(context)
        p.edit()
            .putInt(KEY_VOICE_USES_TODAY, p.getInt(KEY_VOICE_USES_TODAY, 0) + 1)
            .apply()
        DailyDigestNotifier.maybeShow(context)
    }

    fun getRepliesSentToday(context: Context): Int {
        rollover(context)
        return prefs(context).getInt(KEY_REPLIES_TODAY, 0)
    }

    fun getAutoRepliesToday(context: Context): Int {
        rollover(context)
        return prefs(context).getInt(KEY_AUTO_TODAY, 0)
    }

    fun getVoiceUsesToday(context: Context): Int {
        rollover(context)
        return prefs(context).getInt(KEY_VOICE_USES_TODAY, 0)
    }

    fun getTotalRepliesSent(context: Context): Int =
        prefs(context).getInt(KEY_TOTAL, 0)

    fun getMinutesSavedToday(context: Context): Int {
        // Share Stats / "AI cat replied" — AI sends only, not keyword auto-replies.
        val replies = getRepliesSentToday(context)
        return (replies * MINUTES_SAVED_PER_REPLY).toInt()
    }

    fun getVoiceMinutesSavedToday(context: Context): Int {
        val uses = getVoiceUsesToday(context)
        return (uses * MINUTES_SAVED_PER_VOICE_USE).toInt()
    }
}
