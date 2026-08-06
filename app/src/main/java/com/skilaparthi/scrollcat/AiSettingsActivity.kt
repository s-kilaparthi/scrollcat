package com.skilaparthi.scrollcat

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.radiobutton.MaterialRadioButton

class AiSettingsActivity : Activity() {

    private var onDeviceStatusText: TextView? = null
    private var onDeviceProgressBar: ProgressBar? = null
    private var onDeviceProgressLabel: TextView? = null
    private var onDeviceDownloadButton: MaterialButton? = null
    private var onDeviceStatusRow: LinearLayout? = null
    private var onDeviceActiveLabel: TextView? = null
    private var onDeviceMenuButton: TextView? = null
    private var onDeviceDownloading = false

    private val downloadListener = object : ModelDownloadManager.Listener {
        override fun onProgress(percentComplete: Int, downloadedBytes: Long, totalBytes: Long) {
            runOnUiThread {
                bindDownloadProgressUi(percentComplete, downloadedBytes, totalBytes)
            }
        }

        override fun onComplete(success: Boolean) {
            runOnUiThread {
                handleDownloadComplete(success)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = UiKit.pageRoot(this)
        addToolbar(root)
        addReplyTone(root)
        addPersonalization(root)
        addOnDeviceAi(root)
        addAiModel(root)
        addAiStatus(root)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(UiKit.surfaceColor(this@AiSettingsActivity))
            addView(root)
        }
        setContentView(scrollView)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                UiKit.dp(this, 24),
                bars.top + UiKit.dp(this, 16),
                UiKit.dp(this, 24),
                UiKit.dp(this, 24) + maxOf(bars.bottom, ime.bottom)
            )
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        if (onDeviceStatusText == null) return
        ModelDownloadManager.addListener(downloadListener)
        if (ModelDownloadManager.isDownloading()) {
            val (percent, downloaded, total) = ModelDownloadManager.lastProgress()
            bindDownloadProgressUi(percent, downloaded, total)
        } else {
            refreshOnDeviceUi()
        }
    }

    override fun onPause() {
        ModelDownloadManager.removeListener(downloadListener)
        super.onPause()
    }

    /**
     * Single On-Device AI card: Download when missing, Active + ⋮ Delete when present.
     * Model-picker UI removed — tier selection stays in DeviceCapabilityChecker only.
     */
    private fun addOnDeviceAi(root: LinearLayout) {
        val viable = DeviceCapabilityChecker.checkOnDeviceAiViability(this)
        android.util.Log.d(
            "ScrollCat",
            "AiSettingsActivity: on-device viability check result = $viable"
        )
        if (!viable) {
            android.util.Log.d(
                "ScrollCat",
                "AiSettingsActivity: On-Device AI card hidden (device not viable)"
            )
            return
        }

        android.util.Log.d(
            "ScrollCat",
            "AiSettingsActivity: adding On-Device AI (Beta) card to layout"
        )
        UiKit.section(
            root,
            "On-Device AI (Beta)",
            "Runs entirely on your phone — no account needed, nothing leaves your device"
        ) {
            val statusText = UiKit.body(this@AiSettingsActivity, "", muted = true)
            onDeviceStatusText = statusText
            addView(statusText)

            val progressBar = ProgressBar(
                this@AiSettingsActivity,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 100
                progress = 0
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    UiKit.dp(this@AiSettingsActivity, 8)
                ).apply {
                    topMargin = UiKit.dp(this@AiSettingsActivity, 8)
                    bottomMargin = UiKit.dp(this@AiSettingsActivity, 4)
                }
            }
            onDeviceProgressBar = progressBar
            addView(progressBar)

            val progressLabel = UiKit.body(this@AiSettingsActivity, "", muted = true).apply {
                visibility = View.GONE
                textSize = 13f
            }
            onDeviceProgressLabel = progressLabel
            addView(progressLabel)

            val statusRow = LinearLayout(this@AiSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = UiKit.dp(this@AiSettingsActivity, 8)
                }
            }
            val activeLabel = TextView(this@AiSettingsActivity).apply {
                text = "Active"
                textSize = 15f
                setTextColor(UiKit.onSurfaceColor(this@AiSettingsActivity))
                typeface = UiKit.headingTypeface(this@AiSettingsActivity)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            onDeviceActiveLabel = activeLabel
            statusRow.addView(activeLabel)

            val menuButton = TextView(this@AiSettingsActivity).apply {
                text = "⋮"
                textSize = 22f
                setTextColor(UiKit.onSurfaceColor(this@AiSettingsActivity))
                setPadding(
                    UiKit.dp(this@AiSettingsActivity, 12),
                    UiKit.dp(this@AiSettingsActivity, 4),
                    UiKit.dp(this@AiSettingsActivity, 4),
                    UiKit.dp(this@AiSettingsActivity, 4)
                )
                setOnClickListener { anchor -> showOnDeviceOverflowMenu(anchor) }
            }
            onDeviceMenuButton = menuButton
            statusRow.addView(menuButton)
            onDeviceStatusRow = statusRow
            addView(statusRow)

            val downloadButton = UiKit.primaryButton(
                this@AiSettingsActivity,
                "Download (2.6 GB)"
            ) {
                startOnDeviceDownload()
            }
            onDeviceDownloadButton = downloadButton
            UiKit.addButton(this, downloadButton)

            refreshOnDeviceUi()
        }
    }

    private fun showOnDeviceOverflowMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Delete Model")
            setOnMenuItemClickListener { item ->
                if (item.itemId == 1) {
                    confirmDeleteOnDeviceModel()
                    true
                } else {
                    false
                }
            }
            show()
        }
    }

    private fun confirmDeleteOnDeviceModel() {
        AlertDialog.Builder(this)
            .setTitle("Delete on-device model?")
            .setMessage(
                "This removes the Gemma model file (~2.6 GB) from your phone and " +
                    "unloads it from memory. You can download it again later."
            )
            .setPositiveButton("Delete") { _, _ -> deleteOnDeviceModelFile() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshOnDeviceUi() {
        val statusText = onDeviceStatusText ?: return
        val downloadButton = onDeviceDownloadButton ?: return
        val statusRow = onDeviceStatusRow ?: return
        val activeLabel = onDeviceActiveLabel ?: return
        val progressBar = onDeviceProgressBar
        val progressLabel = onDeviceProgressLabel

        if (onDeviceDownloading) return

        val filePresent = ModelDownloadManager.modelFileExists(this)
        progressBar?.visibility = View.GONE
        progressLabel?.visibility = View.GONE

        if (filePresent) {
            downloadButton.visibility = View.GONE
            statusRow.visibility = View.VISIBLE
            val primaryOnDevice =
                SettingsManager.getPrimaryAiProvider(this) == SettingsManager.PRIMARY_AI_ON_DEVICE
            val engineReady = OnDeviceAiEngine.isReady()
            activeLabel.text = when {
                primaryOnDevice && engineReady -> "Active"
                primaryOnDevice -> "Downloaded — ready"
                else -> "Downloaded"
            }
            statusText.text = when {
                primaryOnDevice && engineReady ->
                    "Gemma 4 E2B is loaded. Change primary engine in AI Model settings."
                primaryOnDevice ->
                    "Model on disk. Select On-Device AI as primary in AI Model settings to load it."
                else ->
                    "Model on disk. Select On-Device AI in AI Model settings to use it as primary."
            }
        } else {
            statusRow.visibility = View.GONE
            downloadButton.visibility = View.VISIBLE
            downloadButton.isEnabled = true
            downloadButton.text = "Download (2.6 GB)"
            statusText.text =
                "Download Gemma 4 E2B (~2.6 GB). After download, choose it as your primary " +
                    "engine under AI Model. Groq/Custom keys remain available as fallback."
        }
    }

    private fun bindDownloadProgressUi(percent: Int, downloaded: Long, total: Long) {
        val statusText = onDeviceStatusText ?: return
        val downloadButton = onDeviceDownloadButton ?: return
        val statusRow = onDeviceStatusRow ?: return
        val progressBar = onDeviceProgressBar ?: return
        val progressLabel = onDeviceProgressLabel ?: return

        onDeviceDownloading = true
        statusRow.visibility = View.GONE
        downloadButton.visibility = View.VISIBLE
        downloadButton.isEnabled = false
        downloadButton.text = "Downloading…"
        statusText.text = ModelDownloadManager.formatDownloadHeadline(percent)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = percent
        progressLabel.visibility = View.VISIBLE
        progressLabel.text = ModelDownloadManager.formatDownloadProgress(percent, downloaded, total)
    }

    private fun handleDownloadComplete(success: Boolean) {
        val statusText = onDeviceStatusText ?: return
        val progressBar = onDeviceProgressBar ?: return
        val progressLabel = onDeviceProgressLabel ?: return

        onDeviceDownloading = false
        if (!success) {
            progressBar.visibility = View.GONE
            progressLabel.visibility = View.GONE
            val reason = ModelDownloadManager.lastFailureReason()
            statusText.text = reason
                ?: "Download failed. Check network/storage and try again."
            Toast.makeText(
                this,
                reason ?: "On-device model download failed",
                Toast.LENGTH_LONG
            ).show()
            refreshOnDeviceUi()
            return
        }

        // Prefer on-device as primary when nothing else was explicitly chosen, or already on-device.
        val currentPrimary = SettingsManager.getPrimaryAiProvider(this)
        if (currentPrimary.isEmpty() || currentPrimary == SettingsManager.PRIMARY_AI_ON_DEVICE) {
            SettingsManager.setPrimaryAiProvider(this, SettingsManager.PRIMARY_AI_ON_DEVICE)
        }

        statusText.text = "Download complete — loading model…"
        progressLabel.text = "100% — initializing…"
        val shouldInit =
            SettingsManager.getPrimaryAiProvider(this) == SettingsManager.PRIMARY_AI_ON_DEVICE
        if (!shouldInit) {
            progressBar.visibility = View.GONE
            progressLabel.visibility = View.GONE
            Toast.makeText(this, "Model downloaded", Toast.LENGTH_SHORT).show()
            refreshOnDeviceUi()
            return
        }

        val tier = DeviceCapabilityChecker.selectModelTier(this)
            ?: DeviceCapabilityChecker.tierForModelFile(
                this,
                DeviceCapabilityChecker.MODEL_E2B_FILE
            )
        val initTier = tier.copy(
            modelPath = OnDeviceAiEngine.defaultModelPath(this),
            modelFileName = DeviceCapabilityChecker.MODEL_E2B_FILE
        )
        Thread {
            val ok = OnDeviceAiEngine.initialize(this, initTier, forceReload = true)
            runOnUiThread {
                progressBar.visibility = View.GONE
                progressLabel.visibility = View.GONE
                Toast.makeText(
                    this,
                    if (ok) "On-device AI ready" else "Model on disk; engine init failed",
                    if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
                refreshOnDeviceUi()
            }
        }.start()
    }

    private fun startOnDeviceDownload() {
        if (onDeviceDownloading || ModelDownloadManager.isDownloading()) return
        if (ModelDownloadManager.modelFileExists(this)) {
            refreshOnDeviceUi()
            return
        }
        ModelDownloadManager.addListener(downloadListener)
        bindDownloadProgressUi(0, 0L, 0L)
        ModelDownloadManager.startDownloadIfNeeded(this)
    }

    /** Shutdown + delete file. If primary was on-device, clear it to cloud fallback. */
    private fun deleteOnDeviceModelFile() {
        val statusText = onDeviceStatusText ?: return
        onDeviceDownloadButton?.isEnabled = false
        onDeviceMenuButton?.isEnabled = false
        statusText.text = "Removing on-device model…"
        if (SettingsManager.getPrimaryAiProvider(this) == SettingsManager.PRIMARY_AI_ON_DEVICE) {
            SettingsManager.setOnDeviceAiEnabled(this, false)
        }
        Thread {
            OnDeviceAiEngine.shutdown()
            android.util.Log.d(
                "ScrollCat",
                "AiSettings: delete — shutdown complete, removing model file"
            )
            val deleted = ModelDownloadManager.deleteModel(this)
            runOnUiThread {
                onDeviceMenuButton?.isEnabled = true
                if (deleted) {
                    Toast.makeText(this, "On-device model removed", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Could not delete model file. Try again.", Toast.LENGTH_LONG)
                        .show()
                }
                refreshOnDeviceUi()
            }
        }.start()
    }

    private fun addToolbar(root: LinearLayout) {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, UiKit.dp(this@AiSettingsActivity, 16))
        }
        toolbar.addView(TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(UiKit.onSurfaceColor(this@AiSettingsActivity))
            setPadding(0, 0, UiKit.dp(this@AiSettingsActivity, 12), 0)
            setOnClickListener { finish() }
        })
        toolbar.addView(UiKit.headline(this, "My AI Settings"))
        root.addView(toolbar)
    }

    private fun addReplyTone(root: LinearLayout) {
        UiKit.section(root, "AI Reply Tone", "How the cat writes reply suggestions to your DMs.") {
            val options = listOf(
                "friendly" to "😊 Friendly - warm and personal",
                "casual" to "✌️ Casual - like texting a friend",
                "professional" to "💼 Professional - for business DMs"
            )
            addRadioOptions(options, SettingsManager.getReplyTone(this@AiSettingsActivity)) {
                SettingsManager.setReplyTone(this@AiSettingsActivity, it)
            }
        }
    }

    private fun addPersonalization(root: LinearLayout) {
        UiKit.section(root, "AI Personalization", "Customize your profile so AI replies sound like you.") {
            UiKit.addButton(this, UiKit.tonalButton(this@AiSettingsActivity, "Edit My Profile") {
                startActivity(android.content.Intent(this@AiSettingsActivity, OnboardingActivity::class.java).apply {
                    putExtra("edit_mode", true)
                })
            })
        }
    }

    private fun addAiModel(root: LinearLayout) {
        UiKit.section(root, "AI Model", "Choose your primary reply engine and manage cloud providers.") {
            UiKit.addButton(this, UiKit.tonalButton(this@AiSettingsActivity, "AI Model") {
                startActivity(android.content.Intent(this@AiSettingsActivity, AiProviderActivity::class.java))
            })
        }
    }

    private fun addAiStatus(root: LinearLayout) {
        UiKit.section(root, "AI Status", "Check which reply engine is currently active.") {
            val statusText = UiKit.body(this@AiSettingsActivity, "Tap to check which AI engine is active", muted = true)
            addView(statusText)
            UiKit.addButton(this, UiKit.tonalButton(this@AiSettingsActivity, "AI Status") {
                statusText.text = "Checking..."
                val generator = AiReplyGenerator(this@AiSettingsActivity)
                generator.checkAiStatus { status ->
                    runOnUiThread {
                        statusText.text = status
                        Toast.makeText(this@AiSettingsActivity, status, Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
    }

    private fun LinearLayout.addRadioOptions(
        options: List<Pair<String, String>>,
        selected: String,
        onSelect: (String) -> Unit
    ) {
        val group = RadioGroup(this@AiSettingsActivity).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, UiKit.dp(this@AiSettingsActivity, 4), 0, 0)
        }
        val radioMap = mutableMapOf<Int, String>()
        options.forEach { (key, label) ->
            val radio = MaterialRadioButton(this@AiSettingsActivity).apply {
                text = label
                textSize = 15f
                id = android.view.View.generateViewId()
                isChecked = key == selected
                setTextColor(UiKit.onSurfaceColor(this@AiSettingsActivity))
                setPadding(0, UiKit.dp(this@AiSettingsActivity, 10), 0, UiKit.dp(this@AiSettingsActivity, 10))
            }
            radioMap[radio.id] = key
            group.addView(radio)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            onSelect(radioMap[checkedId] ?: selected)
        }
        addView(group)
    }
}
