package com.skilaparthi.scrollcat

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Sends a reply through a notification's RemoteInput action — the same
 * mechanism Android Auto and Wear use — so the message goes out through
 * WhatsApp/Instagram/Telegram/etc. without ever opening the app.
 */
object ReplySender {

    private const val TAG = "ScrollCat"

    /**
     * Fills the reply action's RemoteInput with [replyText] and fires it.
     * Returns true if the PendingIntent was sent successfully.
     *
     * @param recordAsAiReply when true (default), increments the Share Stats
     *   "AI cat replied" counter. Pass false for keyword auto-replies so they
     *   only count toward Auto Reply Tracker via [AutoReplyManager.logTrackerEntry].
     */
    fun send(
        context: Context,
        message: ReplyStore.ReplyableMessage,
        replyText: String,
        recordAsAiReply: Boolean = true
    ): Boolean {
        val remoteInputs = message.remoteInputs
        val actionIntent = message.actionIntent
        if (remoteInputs.isEmpty() || actionIntent == null) {
            Log.e(TAG, "Reply action has no RemoteInputs")
            return false
        }

        val intent = Intent()
        val results = Bundle()
        remoteInputs.forEach { remoteInput ->
            if (remoteInput.allowFreeFormInput) {
                results.putCharSequence(remoteInput.resultKey, replyText)
            }
        }
        RemoteInput.addResultsToIntent(remoteInputs, intent, results)

        return try {
            actionIntent.send(context, 0, intent)
            Log.i(TAG, "Reply sent to ${message.sender} via ${message.packageName}")
            ReplyStore.trackSentReply(replyText)
            RateUsManager.recordReplySent(context)
            if (recordAsAiReply) {
                StatsTracker.recordReplySent(context)
            }
            // Dismiss the shade only when nothing else in the queue still needs this
            // notification key (same-sender queued messages often share one key).
            val siblingsRemain = ReplyStore.countForNotificationKey(message.notificationKey) > 0
            if (!siblingsRemain) {
                try {
                    CatNotificationListener.instance?.cancelNotification(message.notificationKey)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not cancel notification: ${e.message}")
                }
            } else {
                Log.d(
                    TAG,
                    "Skipping notification cancel — " +
                        "${ReplyStore.countForNotificationKey(message.notificationKey)} " +
                        "queued entr(y/ies) still use key=${message.notificationKey}"
                )
            }
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "Reply PendingIntent was cancelled: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Reply send failed: ${e.message}")
            false
        }
    }

    /**
     * Fallback when RemoteInput isn't usable: open the conversation via the
     * notification's content intent, or launch the app if that fails too.
     * Returns true if something was opened.
     */
    fun openApp(context: Context, message: ReplyStore.ReplyableMessage): Boolean {
        try {
            message.contentIntent?.let {
                it.send()
                Log.i(TAG, "Opened conversation with ${message.sender} in ${message.packageName}")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Content intent failed: ${e.message}")
        }
        return try {
            val launch = context.packageManager.getLaunchIntentForPackage(message.packageName)
                ?: return false
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            Log.i(TAG, "Launched ${message.packageName}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not open app: ${e.message}")
            false
        }
    }
}
