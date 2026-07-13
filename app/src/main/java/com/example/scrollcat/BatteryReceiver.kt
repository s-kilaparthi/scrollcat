package com.example.scrollcat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log

class BatteryReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScrollCat"
        const val LOW_BATTERY_THRESHOLD = 20
        const val CRITICAL_BATTERY_THRESHOLD = 10
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BATTERY_CHANGED) return

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val batteryPct = if (scale > 0) (level * 100 / scale) else -1

        Log.d(TAG, "Battery: $batteryPct% charging: $isCharging")

        val overlay = OverlayService.instance ?: return

        when {
            isCharging -> overlay.onBatteryCharging()
            batteryPct in 10..LOW_BATTERY_THRESHOLD -> overlay.onBatteryLow()
            batteryPct < CRITICAL_BATTERY_THRESHOLD -> overlay.onBatteryCritical()
            else -> overlay.onBatteryNormal()
        }
    }
}
