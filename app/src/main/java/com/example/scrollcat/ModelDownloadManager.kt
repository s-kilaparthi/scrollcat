package com.example.scrollcat

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request

/** Downloads the on-device Gemma model to [OnDeviceAiEngine.defaultModelPath]. */
object ModelDownloadManager {

    private const val TAG = "ScrollCat"

    /** Hosted Gemma 4 E2B LiteRT-LM model (~2.6GB). */
    const val DOWNLOAD_URL =
        "https://pub-adc9f313f0f847699104e000bd529687.r2.dev/gemma-4-e2b-it.litertlm"

    interface Listener {
        fun onProgress(percentComplete: Int, downloadedBytes: Long, totalBytes: Long)
        fun onComplete(success: Boolean)
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val downloadInFlight = AtomicBoolean(false)

    @Volatile private var lastPercent: Int = 0
    @Volatile private var lastDownloaded: Long = 0L
    @Volatile private var lastTotal: Long = 0L

    fun isDownloading(): Boolean = downloadInFlight.get()

    fun lastProgress(): Triple<Int, Long, Long> =
        Triple(lastPercent, lastDownloaded, lastTotal)

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
        if (downloadInFlight.get()) {
            listener.onProgress(lastPercent, lastDownloaded, lastTotal)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Downloads the model on a background thread.
     * [onProgress] receives percent complete 0–100 (may repeat the same value).
     * [onComplete] is invoked with true only after a full successful write to the final path.
     * Any failure deletes partial files and calls [onComplete](false).
     *
     * If a download is already running, this attaches the callbacks to that run
     * instead of starting a second transfer.
     */
    fun downloadModel(
        context: Context,
        onProgress: (Int) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        downloadModel(context, onProgress = { percent, _, _ -> onProgress(percent) }, onComplete)
    }

    /**
     * Same as [downloadModel] with percent-only progress, but also reports byte counts
     * for UI (MB downloaded / total).
     */
    fun downloadModel(
        context: Context,
        onProgress: (percentComplete: Int, downloadedBytes: Long, totalBytes: Long) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        val appContext = context.applicationContext
        if (modelFileExists(appContext)) {
            onProgress(100, 0L, 0L)
            onComplete(true)
            return
        }
        val bridge = object : Listener {
            override fun onProgress(percentComplete: Int, downloadedBytes: Long, totalBytes: Long) {
                onProgress(percentComplete, downloadedBytes, totalBytes)
            }

            override fun onComplete(success: Boolean) {
                onComplete(success)
                removeListener(this)
            }
        }
        addListener(bridge)
        startDownloadIfNeeded(appContext)
    }

    /** Starts the E2B download if one is not already running and the file is missing. */
    fun startDownloadIfNeeded(context: Context) {
        val appContext = context.applicationContext
        if (modelFileExists(appContext)) {
            Log.d(TAG, "ModelDownload: file already present — skipping")
            return
        }
        if (!downloadInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "ModelDownload: already in progress — reusing existing transfer")
            return
        }
        lastPercent = 0
        lastDownloaded = 0L
        lastTotal = 0L
        Thread {
            val dest = File(OnDeviceAiEngine.defaultModelPath(appContext))
            val partial = File(dest.parentFile, "${dest.name}.partial")
            try {
                if (partial.exists() && !partial.delete()) {
                    Log.e(TAG, "ModelDownload: could not clear partial file")
                    finishDownload(false)
                    return@Thread
                }
                dest.parentFile?.mkdirs()

                val request = Request.Builder().url(DOWNLOAD_URL).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "ModelDownload: HTTP ${response.code}")
                        cleanupPartial(partial)
                        finishDownload(false)
                        return@Thread
                    }
                    val body = response.body
                    if (body == null) {
                        Log.e(TAG, "ModelDownload: empty body")
                        cleanupPartial(partial)
                        finishDownload(false)
                        return@Thread
                    }

                    val totalBytes = body.contentLength()
                    if (totalBytes > 0 && !hasEnoughSpace(dest.parentFile!!, totalBytes)) {
                        Log.e(TAG, "ModelDownload: insufficient storage for $totalBytes bytes")
                        cleanupPartial(partial)
                        finishDownload(false)
                        return@Thread
                    }

                    body.byteStream().use { input ->
                        FileOutputStream(partial).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var downloaded = 0L
                            var lastReported = -1
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloaded += read

                                if (totalBytes > 0 &&
                                    freeBytes(dest.parentFile!!) < (totalBytes - downloaded)
                                ) {
                                    Log.e(TAG, "ModelDownload: ran out of storage mid-download")
                                    cleanupPartial(partial)
                                    finishDownload(false)
                                    return@Thread
                                }

                                val percent = if (totalBytes > 0) {
                                    ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                                } else {
                                    0
                                }
                                if (percent != lastReported) {
                                    lastReported = percent
                                    notifyProgress(percent, downloaded, totalBytes)
                                }
                            }
                            output.flush()
                        }
                    }

                    if (dest.exists() && !dest.delete()) {
                        Log.e(TAG, "ModelDownload: could not replace existing model")
                        cleanupPartial(partial)
                        finishDownload(false)
                        return@Thread
                    }
                    if (!partial.renameTo(dest)) {
                        // Cross-filesystem fallback
                        partial.copyTo(dest, overwrite = true)
                        partial.delete()
                    }
                    notifyProgress(100, dest.length(), dest.length())
                    Log.d(TAG, "ModelDownload: complete → ${dest.absolutePath}")
                    finishDownload(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "ModelDownload failed: ${e.message}", e)
                cleanupPartial(partial)
                if (dest.exists() && dest.length() == 0L) dest.delete()
                finishDownload(false)
            }
        }.start()
    }

    /** Deletes the model file and any partial download. */
    fun deleteModel(context: Context): Boolean {
        val dest = File(OnDeviceAiEngine.defaultModelPath(context))
        val partial = File(dest.parentFile, "${dest.name}.partial")
        cleanupPartial(partial)
        return !dest.exists() || dest.delete()
    }

    fun modelFileExists(context: Context): Boolean {
        val file = File(OnDeviceAiEngine.defaultModelPath(context))
        return file.isFile && file.length() > 0L
    }

    private fun notifyProgress(percent: Int, downloaded: Long, total: Long) {
        lastPercent = percent
        lastDownloaded = downloaded
        lastTotal = total
        for (listener in listeners) {
            try {
                listener.onProgress(percent, downloaded, total)
            } catch (e: Exception) {
                Log.w(TAG, "ModelDownload listener progress error: ${e.message}")
            }
        }
    }

    private fun notifyComplete(success: Boolean) {
        for (listener in listeners) {
            try {
                listener.onComplete(success)
            } catch (e: Exception) {
                Log.w(TAG, "ModelDownload listener complete error: ${e.message}")
            }
        }
    }

    private fun finishDownload(success: Boolean) {
        downloadInFlight.set(false)
        notifyComplete(success)
    }

    private fun cleanupPartial(partial: File) {
        try {
            if (partial.exists()) partial.delete()
        } catch (e: Exception) {
            Log.w(TAG, "ModelDownload: partial cleanup failed: ${e.message}")
        }
    }

    private fun hasEnoughSpace(dir: File, neededBytes: Long): Boolean {
        // Keep ~200MB headroom beyond the model size
        return freeBytes(dir) > neededBytes + (200L * 1024 * 1024)
    }

    private fun freeBytes(dir: File): Long {
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }
}
