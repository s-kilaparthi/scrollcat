package com.example.scrollcat

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Minimal local crash reporting: an uncaught-exception handler saves the
 * crash details to SharedPreferences, and the next launch offers a restart.
 * No data leaves the device.
 */
object CrashReportingHelper {

    private const val TAG = "ScrollCat"
    private const val PREFS_NAME = "scrollcat_crash"
    private const val KEY_PENDING = "crash_pending"
    private const val KEY_TIMESTAMP = "crash_timestamp"
    private const val KEY_MESSAGE = "crash_message"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Install from Application.onCreate. Chains to the default handler. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // commit() not apply() — the process is about to die
                prefs(appContext).edit()
                    .putBoolean(KEY_PENDING, true)
                    .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                    .putString(
                        KEY_MESSAGE,
                        "${throwable.javaClass.simpleName}: ${throwable.message ?: "no message"}"
                    )
                    .commit()
            } catch (e: Exception) {
                // Never let crash reporting cause its own crash
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "Crash handler installed")
    }

    fun hasPendingCrash(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PENDING, false)

    fun getLastCrashMessage(context: Context): String =
        prefs(context).getString(KEY_MESSAGE, "") ?: ""

    fun clearPendingCrash(context: Context) {
        prefs(context).edit().putBoolean(KEY_PENDING, false).apply()
    }

    /** Call from MainActivity.onCreate — shows the restart dialog if needed. */
    fun showCrashDialogIfNeeded(activity: Activity) {
        if (!hasPendingCrash(activity)) return
        clearPendingCrash(activity)

        val detail = getLastCrashMessage(activity)
        AlertDialog.Builder(activity)
            .setTitle("😿 ScrollCat had an issue")
            .setMessage(
                "ScrollCat had an issue last time. Restart?" +
                    if (detail.isNotBlank()) "\n\nDetails: $detail" else ""
            )
            .setPositiveButton("Restart") { _, _ ->
                val intent = Intent(activity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
                activity.finish()
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }
}
