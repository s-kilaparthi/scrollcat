package com.example.scrollcat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import androidx.core.content.ContextCompat

/**
 * Fully transparent Activity that requests RECORD_AUDIO if needed and hosts a
 * SpeechRecognizer session in the foreground (no system speech UI).
 *
 * Uses [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE] so launching this Activity
 * does not steal input/accessibility focus from the underlying app's text field
 * (needed for voice-dictation insertion after recognition).
 */
class RecordAudioPermissionActivity : Activity() {

    interface Callback {
        fun onListening()
        fun onTranscript(text: String)
        fun onError(message: String)
        fun onCancelled()
    }

    companion object {
        private const val REQ_PERMISSION = 4201
        /** Shared silence timeout for all voice entry points (default ~2s is too aggressive). */
        private const val SPEECH_SILENCE_TIMEOUT_MS = 7000L

        @Volatile
        private var activeInstance: RecordAudioPermissionActivity? = null
        private var pendingCallback: Callback? = null
        /** Which UI path started this session — for logging only. */
        @Volatile
        private var pendingSource: String = "unknown"
        /** Held for this voice session — abandoned on any end state. */
        @Volatile
        private var heldAudioFocusRequest: AudioFocusRequest? = null
        @Volatile
        private var focusAppContext: Context? = null

        /**
         * @param source one of: "voice-to-text", "continue", "dictation"
         */
        fun start(context: Context, callback: Callback, source: String) {
            pendingCallback = callback
            pendingSource = source
            val appCtx = context.applicationContext
            focusAppContext = appCtx
            heldAudioFocusRequest = AudioFocusHelper.requestTransientAudioFocus(appCtx)
            val intent = Intent(appCtx, RecordAudioPermissionActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_HISTORY or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            try {
                appCtx.startActivity(intent)
            } catch (e: Exception) {
                Logger.e("RecordAudioPermissionActivity start failed: ${e.message}")
                releaseAudioFocus(appCtx)
                val cb = pendingCallback
                pendingCallback = null
                cb?.onError("Didn't catch that, try again")
            }
        }

        fun cancelActive() {
            activeInstance?.finishCancelled()
                ?: run {
                    releaseAudioFocus(null)
                    val cb = pendingCallback
                    pendingCallback = null
                    cb?.onCancelled()
                }
        }

        fun isActive(): Boolean = activeInstance != null || pendingCallback != null

        fun isSpeechRecognitionAvailable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context)

        private fun releaseAudioFocus(context: Context?) {
            val request = heldAudioFocusRequest
            heldAudioFocusRequest = null
            val ctx = context ?: focusAppContext ?: activeInstance?.applicationContext
            focusAppContext = null
            if (ctx != null) {
                AudioFocusHelper.abandonAudioFocus(ctx, request)
            }
        }
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the host app's focused EditText as the a11y/input focus target.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        activeInstance = this
        overridePendingTransition(0, 0)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            // Permission dialog needs focus — temporarily allow it.
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode != REQ_PERMISSION) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            startListening()
        } else {
            finishWithError("Mic permission needed")
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            finishWithError("Speech not available")
            return
        }
        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            Logger.e("SpeechRecognizer create failed: ${e.message}")
            finishWithError("Didn't catch that, try again")
            return
        }
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                runOnUiThread { pendingCallback?.onListening() }
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "Didn't catch that, try again"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Mic permission needed"
                    SpeechRecognizer.ERROR_CLIENT -> null // usually from cancel
                    else -> "Didn't catch that, try again"
                }
                runOnUiThread {
                    if (message == null) finishCancelled()
                    else finishWithError(message)
                }
            }

            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = texts?.firstOrNull()?.trim().orEmpty()
                runOnUiThread {
                    if (transcript.isEmpty()) {
                        finishWithError("Didn't catch that, try again")
                    } else {
                        finishWithTranscript(transcript)
                    }
                }
            }
        })

        val languageTag = SettingsManager.getSpeechRecognitionLanguageTag(this)
        val source = pendingSource
        android.util.Log.d("ScrollCat", "Voice recognition using language: $languageTag")
        android.util.Log.d(
            "ScrollCat",
            "Speech recognition started via: $source with silence timeout=${SPEECH_SILENCE_TIMEOUT_MS}ms"
        )

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SPEECH_SILENCE_TIMEOUT_MS
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SPEECH_SILENCE_TIMEOUT_MS
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                SPEECH_SILENCE_TIMEOUT_MS
            )
        }
        try {
            pendingCallback?.onListening()
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Logger.e("startListening failed: ${e.message}")
            finishWithError("Didn't catch that, try again")
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }

    private fun finishSilent() {
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }

    private fun finishWithTranscript(text: String) {
        if (finished) return
        finished = true
        destroyRecognizer()
        releaseAudioFocus(applicationContext)
        val cb = pendingCallback
        pendingCallback = null
        cb?.onTranscript(text)
        finishSilent()
    }

    private fun finishWithError(message: String) {
        if (finished) return
        finished = true
        destroyRecognizer()
        releaseAudioFocus(applicationContext)
        val cb = pendingCallback
        pendingCallback = null
        cb?.onError(message)
        finishSilent()
    }

    fun finishCancelled() {
        if (finished) return
        finished = true
        destroyRecognizer()
        releaseAudioFocus(applicationContext)
        val cb = pendingCallback
        pendingCallback = null
        cb?.onCancelled()
        finishSilent()
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        destroyRecognizer()
        if (!finished && pendingCallback != null) {
            releaseAudioFocus(applicationContext)
            val cb = pendingCallback
            pendingCallback = null
            cb?.onCancelled()
        }
        super.onDestroy()
    }
}
