package com.example.scrollcat

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Pause/resume whichever media session is actively playing (e.g. YouTube)
 * around brief voice-recognition Activities so the player does not enter PiP
 * when ScrollCat briefly takes the foreground.
 *
 * Uses [MediaSessionManager.getActiveSessions] with the existing
 * [CatNotificationListener] component — no extra permission beyond an enabled
 * notification listener.
 *
 * Fresh YouTube sessions often appear as [PlaybackState.STATE_STOPPED] /
 * [PlaybackState.STATE_BUFFERING] before [PlaybackState.STATE_PLAYING]; we wait
 * (bounded) for that transition via controller callbacks + session-list listener.
 */
object MediaSessionHelper {

    private const val TAG = "ScrollCat"
    private const val WAIT_TIMEOUT_MS = 3500L

    /**
     * Pause the first active session that reaches [PlaybackState.STATE_PLAYING].
     * @return that [MediaController] so the same session can be resumed later, or null.
     *
     * Prefer calling from a background thread — waits on [CountDownLatch] while
     * session/listener callbacks run on the main looper.
     */
    fun pauseActiveMediaIfPlaying(context: Context): MediaController? {
        Log.d(TAG, "pauseActiveMediaIfPlaying() entered thread=${Thread.currentThread().name}")
        val appCtx = context.applicationContext
        val mgr = appCtx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        if (mgr == null) {
            Log.d(TAG, "IMMEDIATE getActiveSessions() skipped — MediaSessionManager is null")
            Log.d(TAG, "Media pause resolved via: timeout, nothing found")
            return null
        }
        val listenerComponent = ComponentName(appCtx, CatNotificationListener::class.java)
        val mainHandler = Handler(Looper.getMainLooper())

        // 1) Immediate check — log FULL result before any listener/wait logic.
        val immediateSessions = try {
            mgr.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            Log.e(
                TAG,
                "IMMEDIATE getActiveSessions() SecurityException — CatNotificationListener may not be " +
                    "recognized as an enabled notification listener for MediaSessionManager",
                e
            )
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "IMMEDIATE getActiveSessions() exception", e)
            emptyList()
        }
        Log.d(
            TAG,
            "IMMEDIATE getActiveSessions() call - session count: ${immediateSessions.size}, " +
                "packages: ${immediateSessions.map { it.packageName }}"
        )
        immediateSessions.forEach { controller ->
            val state = controller.playbackState?.state
            Log.d(
                TAG,
                "IMMEDIATE Session: package=${controller.packageName}, " +
                    "playbackState=$state, " +
                    "playbackStateString=${state?.let { playbackStateToString(it) }}"
            )
        }
        findPlayingIn(immediateSessions)?.let { playing ->
            return pauseAndReturn(playing, "immediate check")
        }

        // 2) Wait for PLAYING (session may be STOPPED/BUFFERING right after open).
        val foundRef = AtomicReference<MediaController?>(null)
        val resolvedVia = AtomicReference<String?>(null)
        val done = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val watchedCallbacks = ConcurrentHashMap<MediaController, MediaController.Callback>()

        fun resolvePlaying(controller: MediaController, via: String) {
            if (!done.compareAndSet(false, true)) return
            if (!foundRef.compareAndSet(null, controller)) {
                done.set(true)
                return
            }
            resolvedVia.set(via)
            latch.countDown()
        }

        fun watchController(controller: MediaController) {
            if (watchedCallbacks.containsKey(controller)) return
            val state = controller.playbackState?.state
            Log.d(
                TAG,
                "Watching session: package=${controller.packageName}, " +
                    "playbackState=$state, " +
                    "playbackStateString=${state?.let { playbackStateToString(it) }}"
            )
            if (state == PlaybackState.STATE_PLAYING) {
                resolvePlaying(controller, "listener callback")
                return
            }
            // BUFFERING / STOPPED / etc. — keep waiting for PLAYING via callback.
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(playbackState: PlaybackState?) {
                    val newState = playbackState?.state ?: -1
                    Log.d(
                        TAG,
                        "Session state changed: package=${controller.packageName}, " +
                            "newState=${playbackStateToString(newState)}"
                    )
                    if (newState == PlaybackState.STATE_PLAYING) {
                        resolvePlaying(controller, "listener callback")
                    }
                    // STATE_BUFFERING (and STOPPED): keep waiting within the timeout.
                }
            }
            watchedCallbacks[controller] = callback
            try {
                controller.registerCallback(callback, mainHandler)
            } catch (e: Exception) {
                Log.w(TAG, "registerCallback failed for ${controller.packageName}: ${e.message}")
                watchedCallbacks.remove(controller)
            }
        }

        fun watchAll(sessions: List<MediaController>) {
            sessions.forEach { watchController(it) }
        }

        val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            val list = controllers.orEmpty()
            logAllSessions(list, "listener callback")
            val playing = findPlayingIn(list)
            Log.d(TAG, "Media session listener fired - found playing session=${playing != null}")
            if (playing != null) {
                resolvePlaying(playing, "listener callback")
            } else {
                // BUFFERING/STOPPED sessions: attach state callbacks and keep waiting.
                watchAll(list)
            }
        }

        try {
            // Explicit main-looper Handler — required when called from a worker thread.
            mgr.addOnActiveSessionsChangedListener(
                sessionsListener,
                listenerComponent,
                mainHandler
            )
        } catch (e: SecurityException) {
            Log.e(
                TAG,
                "addOnActiveSessionsChangedListener SecurityException — " +
                    "CatNotificationListener may not be enabled for this API",
                e
            )
            Log.d(TAG, "Media pause resolved via: timeout, nothing found")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "addOnActiveSessionsChangedListener failed", e)
            Log.d(TAG, "Media pause resolved via: timeout, nothing found")
            return null
        }

        try {
            // Race + watch non-PLAYING sessions from the immediate dump (e.g. STOPPED YouTube).
            watchAll(immediateSessions)
            val raceSessions = listActiveSessions(mgr, listenerComponent, "post-register check")
            val racePlaying = findPlayingIn(raceSessions)
            if (racePlaying != null) {
                resolvePlaying(racePlaying, "immediate check")
            } else {
                watchAll(raceSessions)
            }

            try {
                latch.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            val session = foundRef.get()
            return if (session != null) {
                pauseAndReturn(session, resolvedVia.get() ?: "listener callback")
            } else {
                Log.d(TAG, "Media pause resolved via: timeout, nothing found")
                null
            }
        } finally {
            watchedCallbacks.forEach { (controller, callback) ->
                try {
                    controller.unregisterCallback(callback)
                } catch (_: Exception) {
                }
            }
            watchedCallbacks.clear()
            removeListenerSafely(mgr, sessionsListener, mainHandler)
        }
    }

    private fun removeListenerSafely(
        mgr: MediaSessionManager,
        listener: MediaSessionManager.OnActiveSessionsChangedListener,
        mainHandler: Handler
    ) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                mgr.removeOnActiveSessionsChangedListener(listener)
                return
            }
            val removed = CountDownLatch(1)
            mainHandler.post {
                try {
                    mgr.removeOnActiveSessionsChangedListener(listener)
                } catch (e: Exception) {
                    Log.w(TAG, "removeOnActiveSessionsChangedListener failed: ${e.message}")
                } finally {
                    removed.countDown()
                }
            }
            removed.await(500, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "removeOnActiveSessionsChangedListener failed: ${e.message}")
        }
    }

    private fun pauseAndReturn(session: MediaController, via: String): MediaController? {
        return try {
            session.transportControls.pause()
            Log.d(TAG, "Media pause resolved via: $via")
            Log.d(TAG, "Media pause before voice: found active session=true")
            session
        } catch (e: Exception) {
            Log.w(TAG, "Media pause transportControls.pause failed: ${e.message}")
            Log.d(TAG, "Media pause resolved via: timeout, nothing found")
            null
        }
    }

    private fun listActiveSessions(
        mgr: MediaSessionManager,
        listenerComponent: ComponentName,
        label: String = "getActiveSessions"
    ): List<MediaController> {
        return try {
            val allSessions = mgr.getActiveSessions(listenerComponent)
            logAllSessions(allSessions, label)
            allSessions
        } catch (e: SecurityException) {
            Log.e(
                TAG,
                "getActiveSessions SecurityException — CatNotificationListener may not be " +
                    "recognized as an enabled notification listener for MediaSessionManager",
                e
            )
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "getActiveSessions exception ($label)", e)
            emptyList()
        }
    }

    private fun logAllSessions(allSessions: List<MediaController>, label: String) {
        Log.d(TAG, "$label — Total active sessions found: ${allSessions.size}")
        allSessions.forEach { controller ->
            val state = controller.playbackState?.state
            Log.d(
                TAG,
                "Session: package=${controller.packageName}, " +
                    "playbackState=$state, " +
                    "playbackStateString=${state?.let { playbackStateToString(it) }}"
            )
        }
    }

    private fun playbackStateToString(state: Int): String = when (state) {
        PlaybackState.STATE_NONE -> "STATE_NONE"
        PlaybackState.STATE_STOPPED -> "STATE_STOPPED"
        PlaybackState.STATE_PAUSED -> "STATE_PAUSED"
        PlaybackState.STATE_PLAYING -> "STATE_PLAYING"
        PlaybackState.STATE_FAST_FORWARDING -> "STATE_FAST_FORWARDING"
        PlaybackState.STATE_REWINDING -> "STATE_REWINDING"
        PlaybackState.STATE_BUFFERING -> "STATE_BUFFERING"
        PlaybackState.STATE_ERROR -> "STATE_ERROR"
        PlaybackState.STATE_CONNECTING -> "STATE_CONNECTING"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "STATE_SKIPPING_TO_PREVIOUS"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "STATE_SKIPPING_TO_NEXT"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "STATE_SKIPPING_TO_QUEUE_ITEM"
        -1 -> "null"
        else -> "UNKNOWN($state)"
    }

    private fun findPlayingIn(sessions: List<MediaController>): MediaController? =
        sessions.firstOrNull { session ->
            session.playbackState?.state == PlaybackState.STATE_PLAYING
        }

    /** Resume a controller previously returned by [pauseActiveMediaIfPlaying]. */
    fun resumeMedia(controller: MediaController?): Boolean {
        if (controller == null) {
            Log.d(TAG, "Media resumed after voice: false")
            return false
        }
        return try {
            controller.transportControls.play()
            Log.d(TAG, "Media resumed after voice: true")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Media resume after voice failed: ${e.message}")
            Log.d(TAG, "Media resumed after voice: false")
            false
        }
    }
}
