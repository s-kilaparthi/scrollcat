package com.skilaparthi.scrollcat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Tracks genuine Deep Doze via [PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED]
 * ([PowerManager.isDeviceIdleMode] — not Light Doze / brief screen-off).
 */
object DeviceIdleMonitor {

    private const val TAG = "ScrollCat"

    @Volatile
    private var deepDozeActive: Boolean = false

    private var registered = false
    private var appContext: Context? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) return
            refresh(context)
        }
    }

    fun isDeepDoze(): Boolean = deepDozeActive

    fun register(context: Context) {
        if (registered) return
        val app = context.applicationContext
        appContext = app
        refresh(app)
        val filter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        registered = true
        Log.d(TAG, "DeviceIdleMonitor registered (deepDoze=$deepDozeActive)")
    }

    fun unregister(context: Context) {
        if (!registered) return
        try {
            context.applicationContext.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        registered = false
        appContext = null
        Log.d(TAG, "DeviceIdleMonitor unregistered")
    }

    fun refresh(context: Context? = null) {
        val ctx = context ?: appContext ?: return
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val idle = pm?.isDeviceIdleMode == true
        if (idle != deepDozeActive) {
            Log.d(TAG, "Deep Doze state changed: $deepDozeActive → $idle")
        }
        deepDozeActive = idle
    }
}
