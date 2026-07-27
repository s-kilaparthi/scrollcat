package com.example.scrollcat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Screen on/off broadcasts for overlay + on-device AI idle/warmup. */
class ScreenStateReceiver(
    private val onScreenOn: () -> Unit,
    private val onScreenOff: () -> Unit = {}
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> onScreenOn()
            Intent.ACTION_SCREEN_OFF -> onScreenOff()
        }
    }
}
