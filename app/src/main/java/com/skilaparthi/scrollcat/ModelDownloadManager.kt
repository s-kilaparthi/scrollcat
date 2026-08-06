package com.skilaparthi.scrollcat

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request

/** Downloads the on-device Gemma model to [OnDeviceAiEngine.defaultModelPath]. */
object ModelDownloadManager {

    private const val TAG = "ScrollCat"

    /**
     * If the hosted model file on R2 is ever replaced/updated, this hash MUST be updated to match,
     * or all future downloads will fail integrity verification.
     *
     * SHA-256 of the currently hosted gemma-4-e2b-it.litertlm
     * (https://pub-adc9f313f0f847699104e000bd529687.r2.dev/gemma-4-e2b-it.litertlm,
     * Content-Length 2588147712).
     */
    private const val EXPECTED_MODEL_SHA256 =
        "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

    private const val INTEGRITY_FAILURE_MESSAGE =
        "Download verification failed, please try again"

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
    @Volatile private var lastFailureMessage: String? = null

    fun isDownloading(): Boolean = downloadInFlight.get()

    fun lastProgress(): Triple<Int, Long, Long> =
        Triple(lastPercent, lastDownloaded, lastTotal)

    /** Non-null after a failed download with a specific user-facing reason (e.g. integrity). */
    fun lastFailureReason(): String? = lastFailureMessage

    /**
     * Decimal MB/GB so sizes line up with the advertised "2.6 GB" model size.
     * Each value is scaled on its own, so "450 MB of 2.6 GB" is expected mid-download.
     */
    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1_000_000.0
        return if (mb >= 1000) {
            String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        } else {
            String.format(Locale.US, "%.0f MB", mb)
        }
    }

    /** Shared headline for on-device download progress (dashboard, AI Settings, setup). */
    fun formatDownloadHeadline(percent: Int): String =
        "Downloading on-device AI for more privacy... $percent%"

    /** Shared progress text for every download UI (AI Settings + Add-your-AI setup). */
    fun formatDownloadProgress(percent: Int, downloaded: Long, total: Long): String {
        return if (total > 0) {
            "$percent% — ${formatSize(downloaded)} of ${formatSize(total)}"
        } else {
            "$percent% — ${formatSize(downloaded)} downloaded"
        }
    }

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
        lastFailureMessage = null
        Thread {
            val dest = File(OnDeviceAiEngine.defaultModelPath(appContext))
            val partial = File(dest.parentFile, "${dest.name}.partial")
            try {
                    if (partial.exists() && !partial.delete()) {
                    Log.e(TAG, "ModelDownload: could not clear partial file")
                    finishDownload(appContext, false)
                    return@Thread
                }
                dest.parentFile?.mkdirs()

                val request = Request.Builder().url(DOWNLOAD_URL).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "ModelDownload: HTTP ${response.code}")
                        cleanupPartial(partial)
                        finishDownload(appContext, false)
                        return@Thread
                    }
                    val body = response.body
                    if (body == null) {
                        Log.e(TAG, "ModelDownload: empty body")
                        cleanupPartial(partial)
                        finishDownload(appContext, false)
                        return@Thread
                    }

                    val totalBytes = body.contentLength()
                    if (totalBytes > 0 && !hasEnoughSpace(dest.parentFile!!, totalBytes)) {
                        Log.e(TAG, "ModelDownload: insufficient storage for $totalBytes bytes")
                        cleanupPartial(partial)
                        finishDownload(appContext, false)
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
                                    finishDownload(appContext, false)
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

                    val actualHash = sha256Hex(partial)
                    if (!actualHash.equals(EXPECTED_MODEL_SHA256, ignoreCase = true)) {
                        Log.e(
                            TAG,
                            "ModelDownload: integrity check failed " +
                                "(expected=$EXPECTED_MODEL_SHA256 actual=$actualHash)"
                        )
                        cleanupPartial(partial)
                        finishDownload(appContext, false, INTEGRITY_FAILURE_MESSAGE)
                        return@Thread
                    }

                    if (dest.exists() && !dest.delete()) {
                        Log.e(TAG, "ModelDownload: could not replace existing model")
                        cleanupPartial(partial)
                        finishDownload(appContext, false)
                        return@Thread
                    }
                    if (!partial.renameTo(dest)) {
                        // Cross-filesystem fallback
                        partial.copyTo(dest, overwrite = true)
                        partial.delete()
                    }
                    notifyProgress(100, dest.length(), dest.length())
                    Log.d(TAG, "ModelDownload: complete → ${dest.absolutePath}")
                    finishDownload(appContext, true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "ModelDownload failed: ${e.message}", e)
                cleanupPartial(partial)
                if (dest.exists() && dest.length() == 0L) dest.delete()
                finishDownload(appContext, false)
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

    private fun finishDownload(
        appContext: Context,
        success: Boolean,
        failureReason: String? = null
    ) {
        lastFailureMessage = if (success) null else failureReason
        downloadInFlight.set(false)
        if (success) {
            SettingsManager.setOnDeviceReadyBannerPending(appContext, true)
        }
        notifyComplete(success)
    }

    /** Chunked SHA-256 so a ~2.6GB model is never fully loaded into memory. */
    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
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
