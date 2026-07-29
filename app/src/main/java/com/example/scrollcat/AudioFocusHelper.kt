package com.example.scrollcat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Transient audio focus around voice recognition so other apps (e.g. YouTube)
 * pause via their own focus handling and resume when we abandon.
 */
object AudioFocusHelper {

    private const val TAG = "ScrollCat"

    private val noOpListener = AudioManager.OnAudioFocusChangeListener { }

    fun requestTransientAudioFocus(context: Context): AudioFocusRequest? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(noOpListener)
            .build()
        val result = audioManager.requestAudioFocus(focusRequest)
        Log.d(TAG, "Audio focus request result: $result")
        return if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest else null
    }

    fun abandonAudioFocus(context: Context, request: AudioFocusRequest?) {
        if (request == null) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.abandonAudioFocusRequest(request)
        Log.d(TAG, "Audio focus abandoned")
    }
}
