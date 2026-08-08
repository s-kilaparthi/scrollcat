package com.skilaparthi.scrollcat

import android.content.Context
import android.media.AudioFocusRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Thin TextToSpeech wrapper for reading incoming messages aloud from [ReplyPanel].
 * Language tags typically come from [ScreenTranslator.identifyLanguageCode].
 *
 * Uses [AudioFocusHelper] the same way mic recording does so background media
 * pauses for the utterance and resumes when speech ends or is stopped.
 */
class TtsManager(
    context: Context,
    private val onSpeechStatusChanged: (isSpeaking: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "ScrollCat"
        private const val UTTERANCE_ID = "scrollcat_read_aloud"
    }

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingText: String? = null
    private var pendingLang: String? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                setupProgressListener()
                pendingText?.let { text ->
                    speak(text, pendingLang)
                    pendingText = null
                    pendingLang = null
                }
            } else {
                Log.e(TAG, "TextToSpeech initialization failed status=$status")
                releaseAudioFocus()
                onSpeechStatusChanged(false)
            }
        }
    }

    private fun takeAudioFocus() {
        if (audioFocusRequest == null) {
            audioFocusRequest = AudioFocusHelper.requestTransientAudioFocus(appContext)
        }
    }

    private fun releaseAudioFocus() {
        AudioFocusHelper.abandonAudioFocus(appContext, audioFocusRequest)
        audioFocusRequest = null
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID) onSpeechStatusChanged(true)
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID) {
                    releaseAudioFocus()
                    onSpeechStatusChanged(false)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID) {
                    releaseAudioFocus()
                    onSpeechStatusChanged(false)
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS Error code: $errorCode")
                if (utteranceId == UTTERANCE_ID) {
                    releaseAudioFocus()
                    onSpeechStatusChanged(false)
                }
            }
        })
    }

    /**
     * Speaks [text]. [languageCode] is a BCP-47 tag from ML Kit detection
     * ([ScreenTranslator.identifyLanguageCode]); falls back to the device default
     * when null or unsupported on-device.
     */
    fun speak(text: String, languageCode: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        if (!isInitialized) {
            pendingText = trimmed
            pendingLang = languageCode
            return
        }

        val ttsEngine = tts ?: return

        if (!languageCode.isNullOrBlank()) {
            val locale = Locale.forLanguageTag(languageCode)
            val result = ttsEngine.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.w(
                    TAG,
                    "Requested TTS language $languageCode unavailable — using device default"
                )
                ttsEngine.language = Locale.getDefault()
            }
        } else {
            ttsEngine.language = Locale.getDefault()
        }

        takeAudioFocus()
        val ok = ttsEngine.speak(trimmed, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (ok == TextToSpeech.ERROR) {
            Log.w(TAG, "TTS speak() returned ERROR")
            releaseAudioFocus()
            onSpeechStatusChanged(false)
        } else {
            // Optimistic UI; onStart/onDone keep it accurate.
            onSpeechStatusChanged(true)
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) { }
        releaseAudioFocus()
        onSpeechStatusChanged(false)
        pendingText = null
        pendingLang = null
    }

    fun shutdown() {
        pendingText = null
        pendingLang = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) { }
        tts = null
        isInitialized = false
        releaseAudioFocus()
        onSpeechStatusChanged(false)
    }
}
