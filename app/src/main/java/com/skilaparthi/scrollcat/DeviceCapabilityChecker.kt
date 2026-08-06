package com.skilaparthi.scrollcat

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import java.io.File

/**
 * Fast heuristic pre-filter for on-device AI (LiteRT-LM).
 * Does not prove GPU/generation will work — only filters out devices with
 * no realistic chance before offering a multi-GB model download.
 *
 * RAM-tier decisions use [/proc/meminfo] MemTotal (true physical RAM) rather than
 * [ActivityManager.MemoryInfo.totalMem], which budget OEMs can inflate via
 * "virtual RAM" / swap-file expansion.
 */
object DeviceCapabilityChecker {

    private const val TAG = "ScrollCat"
    private const val MIN_API = Build.VERSION_CODES.S // API 31 — uses-native-library OpenCL
    /** Below this: skip on-device AI entirely (even 270M is unreliable). */
    private const val MIN_ON_DEVICE_RAM_MB = 3L * 1024
    private const val MIN_FREE_STORAGE_BYTES = 4L * 1024 * 1024 * 1024
    /** Prefer E2B + GPU only on devices with ~7GB+ physical RAM. */
    private const val HIGH_RAM_MB = 7_000_000_000L / (1024L * 1024L) // ~6675 MB

    const val MODEL_E2B_FILE = "gemma-4-e2b-it.litertlm"
    const val MODEL_270M_FILE = "gemma-3-270m-it.litertlm"

    /** Full tier labels — never truncate; used in logs and UI. */
    const val TIER_LABEL_E2B_GPU = "E2B-GPU"
    const val TIER_LABEL_270M_CPU = "270M-CPU"
    const val TIER_LABEL_LEGACY_CPU = "legacy-CPU"

    const val E2B_REQUIRED_HEADROOM_MB = 2500L
    /** ~280–450MB footprint + safety margin for the 270M model. */
    const val SMALL_MODEL_REQUIRED_HEADROOM_MB = 750L

    /**
     * Linked model file + backend choice for the device's physical-RAM tier.
     */
    data class OnDeviceModelTier(
        /** Log label: E2B-GPU / 270M-CPU */
        val tierLabel: String,
        val modelFileName: String,
        val modelPath: String,
        /** Preferred compute backend after optional NPU (E2B) or immediately (270M). */
        val preferredBackend: Backend,
        /** When true, try NPU first (E2B/high-RAM only). */
        val attemptNpu: Boolean,
        /** [canSafelyLoadModel] headroom for this model size. */
        val requiredHeadroomMb: Long
    )

    /**
     * True physical RAM from `/proc/meminfo` MemTotal, in megabytes.
     * Returns 0 if the read fails — callers must fail-safe (treat as low-RAM).
     */
    fun getTruePhysicalRamMb(): Long {
        return try {
            val memInfo = File("/proc/meminfo").readLines()
            val totalKb = memInfo.firstOrNull { it.startsWith("MemTotal:") }
                ?.replace("[^0-9]".toRegex(), "")?.toLongOrNull() ?: 0L
            totalKb / 1024
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read /proc/meminfo: ${e.message}")
            0L
        }
    }

    /** ≥7GB true physical RAM — eligible for E2B on-device choice during onboarding. */
    fun isHighRamDevice(): Boolean = getTruePhysicalRamMb() >= HIGH_RAM_MB

    /** True when the active network is Wi‑Fi (not cellular / ethernet alone). */
    fun isConnectedToWifi(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            Log.d(TAG, "WiFi check failed: ${e.message}")
            false
        }
    }

    /**
     * Same free-space gate used by [checkOnDeviceAiViability] (≥ [MIN_FREE_STORAGE_BYTES]).
     */
    fun hasSufficientFreeStorageForOnDevice(context: Context): Boolean {
        val freeBytes = freeStorageBytes(context)
        return freeBytes >= MIN_FREE_STORAGE_BYTES
    }

    /**
     * Silent auto-download eligibility: high RAM + Wi‑Fi + enough free storage.
     * Used instead of presenting an On-Device vs Groq choice.
     */
    fun isEligibleForSilentOnDeviceDownload(context: Context): Boolean {
        return isHighRamDevice() &&
            isConnectedToWifi(context) &&
            hasSufficientFreeStorageForOnDevice(context)
    }

    fun modelPathFor(context: Context, fileName: String): String {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, fileName).absolutePath
    }

    /** Fixed E2B or 270M tier (for manual override / known file). */
    fun tierForModelFile(context: Context, modelFileName: String): OnDeviceModelTier {
        return if (modelFileName == MODEL_270M_FILE ||
            modelFileName.equals(SettingsManager.OVERRIDE_270M, ignoreCase = true)
        ) {
            OnDeviceModelTier(
                tierLabel = TIER_LABEL_270M_CPU,
                modelFileName = MODEL_270M_FILE,
                modelPath = modelPathFor(context, MODEL_270M_FILE),
                preferredBackend = Backend.CPU(threadCount = 4),
                attemptNpu = false,
                requiredHeadroomMb = SMALL_MODEL_REQUIRED_HEADROOM_MB
            )
        } else {
            OnDeviceModelTier(
                tierLabel = TIER_LABEL_E2B_GPU,
                modelFileName = MODEL_E2B_FILE,
                modelPath = modelPathFor(context, MODEL_E2B_FILE),
                preferredBackend = Backend.GPU(),
                attemptNpu = true,
                requiredHeadroomMb = E2B_REQUIRED_HEADROOM_MB
            )
        }
    }

    /**
     * Deep Doze: skip on-device entirely (GPU→CPU handoff in-process is broken at the
     * native layer). Callers should fall through to Groq immediately.
     */
    fun shouldSkipOnDeviceForDoze(context: Context): Boolean {
        DeviceIdleMonitor.refresh(context)
        if (!DeviceIdleMonitor.isDeepDoze()) return false
        Log.d(
            TAG,
            "Doze active — skipping on-device attempt entirely, using Groq directly"
        )
        return true
    }

    /**
     * Manual override from Settings first; otherwise automatic RAM-tier selection.
     * Returns null during Deep Doze (no on-device attempt).
     */
    fun resolveModelTier(context: Context): OnDeviceModelTier? {
        if (shouldSkipOnDeviceForDoze(context)) return null

        val override = SettingsManager.getOnDeviceModelOverride(context)
        if (override != null) {
            val tier = when (override) {
                SettingsManager.OVERRIDE_270M, MODEL_270M_FILE ->
                    tierForModelFile(context, MODEL_270M_FILE)
                SettingsManager.OVERRIDE_E2B, MODEL_E2B_FILE ->
                    tierForModelFile(context, MODEL_E2B_FILE)
                else -> null
            }
            if (tier != null) {
                val ramGb = getTruePhysicalRamMb() / 1024.0
                Log.d(
                    TAG,
                    "Model tier selected: ${tier.tierLabel} (manual override=$override) " +
                        "based on true physical RAM: ${"%.1f".format(ramGb)}GB"
                )
                return tier
            }
            Log.w(TAG, "Unknown on_device_model_override=$override — using automatic tier")
        }
        return selectModelTier(context)
    }

    /**
     * Picks model + backend from true physical RAM.
     * - ≥7GB → E2B + GPU preferred (NPU still tried first by the engine)
     * - 3–7GB → 270M + CPU only (no GPU / no NPU)
     * - &lt;3GB or unreadable → null (disabled; fall through to Groq)
     */
    fun selectModelTier(context: Context): OnDeviceModelTier? {
        val truePhysicalMb = logRamComparison(context)
        val ramGb = truePhysicalMb / 1024.0

        if (truePhysicalMb <= 0L) {
            Log.d(
                TAG,
                "Model tier selected: disabled-too-low-ram based on true physical RAM: " +
                    "${"%.1f".format(ramGb)}GB (unreadable /proc/meminfo — fail-safe)"
            )
            return null
        }

        return when {
            truePhysicalMb >= HIGH_RAM_MB -> {
                Log.d(
                    TAG,
                    "Model tier selected: $TIER_LABEL_E2B_GPU based on true physical RAM: " +
                        "${"%.1f".format(ramGb)}GB"
                )
                OnDeviceModelTier(
                    tierLabel = TIER_LABEL_E2B_GPU,
                    modelFileName = MODEL_E2B_FILE,
                    modelPath = modelPathFor(context, MODEL_E2B_FILE),
                    preferredBackend = Backend.GPU(),
                    attemptNpu = true,
                    requiredHeadroomMb = E2B_REQUIRED_HEADROOM_MB
                )
            }
            truePhysicalMb >= MIN_ON_DEVICE_RAM_MB -> {
                Log.d(
                    TAG,
                    "Model tier selected: $TIER_LABEL_270M_CPU based on true physical RAM: " +
                        "${"%.1f".format(ramGb)}GB"
                )
                OnDeviceModelTier(
                    tierLabel = TIER_LABEL_270M_CPU,
                    modelFileName = MODEL_270M_FILE,
                    modelPath = modelPathFor(context, MODEL_270M_FILE),
                    preferredBackend = Backend.CPU(threadCount = 4),
                    attemptNpu = false,
                    requiredHeadroomMb = SMALL_MODEL_REQUIRED_HEADROOM_MB
                )
            }
            else -> {
                Log.d(
                    TAG,
                    "Model tier selected: disabled-too-low-ram based on true physical RAM: " +
                        "${"%.1f".format(ramGb)}GB"
                )
                null
            }
        }
    }

    fun checkOnDeviceAiViability(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < MIN_API) {
            Log.d(
                TAG,
                "On-device AI viability check failed: Android API ${Build.VERSION.SDK_INT} < $MIN_API"
            )
            return false
        }

        // Reuses the same tier gate (≥3GB physical); logs the selected tier.
        if (selectModelTier(context) == null) {
            Log.d(
                TAG,
                "On-device AI viability check failed: physical RAM below 3GB or unreadable"
            )
            return false
        }

        val freeBytes = freeStorageBytes(context)
        if (!hasSufficientFreeStorageForOnDevice(context)) {
            val freeGb = freeBytes / (1024.0 * 1024.0 * 1024.0)
            Log.d(
                TAG,
                "On-device AI viability check failed: free storage ${"%.1f".format(freeGb)}GB < 4GB"
            )
            return false
        }

        Log.d(TAG, "On-device AI viability check passed")
        return true
    }

    /**
     * Preferred backend after tier selection (legacy helper).
     * Prefer [selectModelTier] when both model path and backend are needed.
     */
    fun selectSafeBackend(context: Context): Backend {
        return selectModelTier(context)?.preferredBackend
            ?: Backend.CPU(threadCount = 4)
    }

    /**
     * Headroom check before loading the on-device model.
     * Pass a tier-appropriate [requiredMb] (E2B ≈2500, 270M ≈750).
     */
    fun canSafelyLoadModel(context: Context, requiredMb: Long = E2B_REQUIRED_HEADROOM_MB): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        if (memInfo.lowMemory) {
            Log.d(TAG, "canSafelyLoadModel: false — ActivityManager reports lowMemory")
            return false
        }
        val availMb = memInfo.availMem / 1024 / 1024
        if (availMb < requiredMb) {
            Log.d(
                TAG,
                "canSafelyLoadModel: false — availMem=${availMb}MB < required=${requiredMb}MB"
            )
            return false
        }
        Log.d(TAG, "canSafelyLoadModel: true — availMem=${availMb}MB (need ${requiredMb}MB)")
        return true
    }

    /** Logs ActivityManager vs /proc/meminfo and returns true physical RAM in MB. */
    private fun logRamComparison(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalMemGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val truePhysicalMb = getTruePhysicalRamMb()
        val truePhysicalGb = truePhysicalMb / 1024.0
        Log.d(
            TAG,
            "RAM check - ActivityManager.totalMem: ${"%.1f".format(totalMemGb)}GB, " +
                "true physical (/proc/meminfo): ${"%.1f".format(truePhysicalGb)}GB"
        )
        return truePhysicalMb
    }

    private fun freeStorageBytes(context: Context): Long {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            Log.d(TAG, "On-device AI viability check failed: could not read storage (${e.message})")
            0L
        }
    }
}
