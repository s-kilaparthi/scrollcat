package com.example.scrollcat

import java.util.Calendar

object SleepManager {

    private const val SLEEP_HOUR_START = 22
    private const val SLEEP_HOUR_END = 7

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
