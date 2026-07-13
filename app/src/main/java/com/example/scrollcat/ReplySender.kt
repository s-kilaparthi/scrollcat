package com.example.scrollcat

import android.app.Notification
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
     */
    fun send(context: Context, message: ReplyStore.ReplyableMessage, replyText: String): Boolean {
        val action = message.replyAction
        val remoteInputs = action.remoteInputs
        if (remoteInputs.isNullOrEmpty()) {
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
            action.actionIntent.send(context, 0, intent)
            Log.i(TAG, "Reply sent to ${message.sender} via ${message.packageName}")
            // Dismiss the notification we just replied to so it doesn't linger
            try {
                CatNotificationListener.instance?.cancelNotification(message.notificationKey)
            } catch (e: Exception) {
                Log.w(TAG, "Could not cancel notification: ${e.message}")
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
}
