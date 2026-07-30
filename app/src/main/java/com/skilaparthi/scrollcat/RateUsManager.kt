package com.skilaparthi.scrollcat

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * In-app review prompt via the Google Play Review API.
 * Shown once the user has opened the app 5+ times, sent 3+ smart replies,
 * and hasn't been asked before.
 */
object RateUsManager {

    private const val TAG = "ScrollCat"
    private const val PREFS_NAME = "scrollcat_rate_us"
    private const val KEY_OPEN_COUNT = "open_count"
    private const val KEY_REPLIES_SENT = "replies_sent"
    private const val KEY_RATED = "rated"

    private const val MIN_OPENS = 5
    private const val MIN_REPLIES = 3

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Call once per MainActivity launch. */
    fun recordAppOpen(context: Context) {
        val p = prefs(context)
        p.edit().putInt(KEY_OPEN_COUNT, p.getInt(KEY_OPEN_COUNT, 0) + 1).apply()
    }

    /** Call whenever a smart reply is successfully sent. */
    fun recordReplySent(context: Context) {
        val p = prefs(context)
        p.edit().putInt(KEY_REPLIES_SENT, p.getInt(KEY_REPLIES_SENT, 0) + 1).apply()
    }

    fun shouldShowPrompt(context: Context): Boolean {
        val p = prefs(context)
        return !p.getBoolean(KEY_RATED, false) &&
            p.getInt(KEY_OPEN_COUNT, 0) >= MIN_OPENS &&
            p.getInt(KEY_REPLIES_SENT, 0) >= MIN_REPLIES
    }

    /** Shows the Play in-app review flow when conditions are met. */
    fun maybeShowRatePrompt(activity: Activity) {
        if (!shouldShowPrompt(activity)) return

        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow()
            .addOnSuccessListener { reviewInfo ->
                manager.launchReviewFlow(activity, reviewInfo)
                    .addOnCompleteListener {
                        // Play decides whether the dialog actually appeared;
                        // either way don't nag again.
                        prefs(activity).edit().putBoolean(KEY_RATED, true).apply()
                        Log.i(TAG, "In-app review flow finished")
                    }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Review flow unavailable: ${e.message}")
            }
    }
}
