package com.example.scrollcat

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

class MusicDetector(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var isMusicPlaying = false
    private val CHECK_INTERVAL_MS = 1000L

    // Only these apps trigger the dance
    private val musicApps = setOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.apple.android.music",
        "com.amazon.mp3",
        "com.pandora.android",
        "com.soundcloud.android",
        "com.tidal.music",
        "com.deezer.android.app"
    )

    private var currentForegroundApp = ""

    private val checkRunnable = object : Runnable {
        override fun run() {
            val musicActive = audioManager.isMusicActive

            // Only dance if a music app is in foreground
            val isMusicApp = currentForegroundApp in musicApps
            val danceEnabled = SettingsManager.isMusicDanceEnabled(context)
            val shouldDance = musicActive && isMusicApp && danceEnabled

            if (shouldDance != isMusicPlaying) {
                isMusicPlaying = shouldDance
                if (shouldDance) {
                    android.util.Log.d("ScrollCat", "Music started from: $currentForegroundApp")
                    OverlayService.instance?.onMusicStarted()
                } else {
                    android.util.Log.d("ScrollCat", "Music stopped")
                    OverlayService.instance?.onMusicStopped()
                }
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun updateForegroundApp(packageName: String) {
        currentForegroundApp = packageName
    }

    fun start() {
        handler.post(checkRunnable)
        android.util.Log.d("ScrollCat", "Music detector started")
    }

    fun stop() {
        handler.removeCallbacks(checkRunnable)
        android.util.Log.d("ScrollCat", "Music detector stopped")
    }
}
