package com.skilaparthi.scrollcat

import android.os.Build
import android.util.Log
import java.util.Calendar

object SleepManager {

    private const val SLEEP_HOUR_START = 22
    private const val SLEEP_HOUR_END = 7

    /**
     * Diagnostic: log all Telugu-related Transliterator IDs available on this device.
     * Call once on startup from any convenient entry point.
     */
    fun logTeluguTransliterators() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.d("ScrollCat", "TEST: Transliterator API requires API 24+")
            return
        }
        val ids = android.icu.text.Transliterator.getAvailableIDs()
        val teluguIds = mutableListOf<String>()
        for (id in ids) {
            if (id.contains("telugu", ignoreCase = true)) {
                teluguIds.add(id)
            }
        }
        Log.d("ScrollCat", "TEST: Telugu-related transliterator IDs found: $teluguIds")
    }

    fun isSleepTime(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= SLEEP_HOUR_START || hour < SLEEP_HOUR_END
    }

    fun isGoodMorning(): Boolean {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        return hour == SLEEP_HOUR_END && minute < 5
    }
}
