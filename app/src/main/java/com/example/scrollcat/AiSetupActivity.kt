package com.example.scrollcat

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Guided first-time AI setup (dashboard banner entry).
 * ≥7GB: On-Device vs Groq choice, then Connect AI (optional backup if on-device).
 * &lt;7GB: skip choice → Groq guided setup with Step 1 / Step 2 links.
 *
 * Path preference is in-session only until setup is real (Groq key saved or
 * on-device model file present). Skip/back without completing does not stick.
 */
class AiSetupActivity : Activity() {

    companion object {
        private const val ACCENT = 0xFFB39DDB.toInt()
        private const val BG = 0xFF1A1A1E.toInt()
        private const val CARD = 0xFF25252C.toInt()
        private const val STROKE = 0x556B6578
        private const val TEXT = 0xFFF5F3F7.toInt()
        private const val MUTED = 0xFFA39BB0.toInt()
        private const val HINT_COLOR = 0xFFA39BB0.toInt()
        private const val HERO_CONNECT_AI = 69
        private const val AI_PATH_ON_DEVICE = "on_device"
        private const val AI_PATH_CLOUD = "cloud"

        /** Stale pref from earlier builds that saved path on Continue alone. */
        private const val PREF_AI_PATH = "onboarding_ai_path"

        const val SHOWING_CHOICE = "choice screen"
        const val SHOWING_DIRECT_GROQ = "direct Groq"
        const val SHOWING_DIRECT_DASHBOARD = "direct dashboard"

        fun hasCompletedAiSetup(context: android.content.Context): Boolean {
            val hasGroqKey = ApiKeyStore.hasGroqApiKey(context)
            val hasModelFile = ModelDownloadManager.modelFileExists(context)
            return hasGroqKey || hasModelFile
        }

        /**
         * What the Add-AI banner entry should show, based on real setup state
         * (not a persisted path tap).
         */
        fun resolveBannerDestination(context: android.content.Context): String {
            val hasGroqKey = ApiKeyStore.hasGroqApiKey(context)
            val hasModelFile = ModelDownloadManager.modelFileExists(context)
            return when {
                hasGroqKey || hasModelFile -> SHOWING_DIRECT_DASHBOARD
                DeviceCapabilityChecker.isHighRamDevice() -> SHOWING_CHOICE
                else -> SHOWING_DIRECT_GROQ
            }
        }
    }

    private lateinit var container: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Clear sticky path prefs so Skip after choosing Groq can't skip choice next time.
        getSharedPreferences("scrollcat_prefs", MODE_PRIVATE)
            .edit()
            .remove(PREF_AI_PATH)
            .apply()
        container = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            clipToPadding = false
        }
        setContentView(container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(24), bars.top + dp(16), dp(24), bars.bottom + dp(24))
            insets
        }
        startFlow()
    }

    private fun startFlow() {
        val hasGroqKey = ApiKeyStore.hasGroqApiKey(this)
        val hasModelFile = ModelDownloadManager.modelFileExists(this)
        val showing = resolveBannerDestination(this)
        android.util.Log.d(
            "ScrollCat",
            "Add AI banner tapped - groq configured: $hasGroqKey, " +
                "on-device downloaded: $hasModelFile - showing: $showing"
        )
        when (showing) {
            SHOWING_DIRECT_DASHBOARD -> {
                // Already configured — nothing to guide; return to dashboard.
                finish()
            }
            SHOWING_CHOICE -> showAiPathChoiceScreen()
            else -> showConnectAiScreen(asOptionalBackup = false)
        }
    }

    /** ≥7GB: pick On-Device (default) vs Groq before the Connect AI step. */
    private fun showAiPathChoiceScreen() {
        val root = screenRoot()
        root.addView(heroIllustration(HERO_CONNECT_AI, sizeDp = 100))
        root.addView(title("How should ScrollCat reply?"))
        root.addView(
            subtitle(
                "On high-memory phones you can run AI privately on-device, or use Groq in the cloud."
            )
        )

        var selected = AI_PATH_ON_DEVICE
        val cards = mutableListOf<MaterialCardView>()

        data class PathOption(val key: String, val label: String, val desc: String)
        val options = listOf(
            PathOption(
                AI_PATH_ON_DEVICE,
                "On-Device AI",
                "Your messages never leave your phone — completely private. Might slow down other apps a little."
            ),
            PathOption(
                AI_PATH_CLOUD,
                "Groq (Cloud AI)",
                "Sends your messages to generate replies. Lighter and faster on your phone."
            )
        )

        fun refreshCardStrokes() {
            cards.forEachIndexed { i, card ->
                val selectedCard = options[i].key == selected
                card.strokeWidth = if (selectedCard) dp(2) else dp(1)
                card.strokeColor = if (selectedCard) ACCENT else STROKE
            }
        }

        options.forEach { opt ->
            val card = MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = dp(2).toFloat()
                strokeWidth = if (opt.key == selected) dp(2) else dp(1)
                strokeColor = if (opt.key == selected) ACCENT else STROKE
                setCardBackgroundColor(CARD)
                setOnClickListener {
                    selected = opt.key
                    refreshCardStrokes()
                }
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(18))
            }
            content.addView(TextView(this).apply {
                text = opt.label
                textSize = 16f
                setTextColor(TEXT)
                typeface = UiKit.headingTypeface(this@AiSetupActivity)
            })
            content.addView(TextView(this).apply {
                text = opt.desc
                textSize = 13f
                setTextColor(MUTED)
                setPadding(0, dp(6), 0, 0)
            })
            card.addView(content)
            cards.add(card)
            root.addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(12)) }
            )
        }

        root.addView(
            primaryButton("Continue") {
                // Do not persist path — Skip without a key/model must show choice again.
                if (selected == AI_PATH_ON_DEVICE) {
                    SettingsManager.setPrimaryAiProvider(this, SettingsManager.PRIMARY_AI_ON_DEVICE)
                    startBackgroundOnDeviceDownload()
                    showConnectAiScreen(asOptionalBackup = true)
                } else {
                    showConnectAiScreen(asOptionalBackup = false)
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, 0) }
        )
    }

    /** Kick off E2B model download without blocking; progress lives in AI Settings. */
    private fun startBackgroundOnDeviceDownload() {
        if (ModelDownloadManager.modelFileExists(this)) {
            android.util.Log.d("ScrollCat", "AiSetup: on-device model already present")
            return
        }
        android.util.Log.d(
            "ScrollCat",
            "AiSetup: starting background on-device model download (non-blocking)"
        )
        Toast.makeText(
            this,
            "Downloading On-Device AI in the background…",
            Toast.LENGTH_SHORT
        ).show()
        val appCtx = applicationContext
        ModelDownloadManager.downloadModel(
            appCtx,
            onProgress = { percent, _, _ ->
                android.util.Log.d("ScrollCat", "AiSetup model download: $percent%")
            },
            onComplete = { success ->
                android.util.Log.d(
                    "ScrollCat",
                    "AiSetup model download finished success=$success"
                )
                if (!success) return@downloadModel
                val tier = DeviceCapabilityChecker.selectModelTier(appCtx)
                    ?: DeviceCapabilityChecker.tierForModelFile(
                        appCtx,
                        DeviceCapabilityChecker.MODEL_E2B_FILE
                    )
                val initTier = tier.copy(
                    modelPath = OnDeviceAiEngine.defaultModelPath(appCtx),
                    modelFileName = DeviceCapabilityChecker.MODEL_E2B_FILE
                )
                OnDeviceAiEngine.initialize(appCtx, initTier, forceReload = true)
            }
        )
    }

    /**
     * Groq / custom-provider Connect AI screen.
     * [asOptionalBackup] reframes copy when the user already chose On-Device AI.
     */
    private fun showConnectAiScreen(asOptionalBackup: Boolean) {
        val root = screenRoot(centerVertically = false)
        root.addView(heroIllustration(HERO_CONNECT_AI, sizeDp = 100))
        if (asOptionalBackup) {
            root.addView(title("Set up a backup AI (optional)"))
            root.addView(
                subtitle(
                    "If on-device AI has trouble on your phone, Groq will step in automatically so replies never break."
                )
            )
        } else {
            root.addView(title("Connect AI for Smart Replies"))
            root.addView(
                subtitle(
                    "Get a free API key from Groq to unlock instant AI replies."
                )
            )
        }

        var useGroq = true

        val groqHelperSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        groqHelperSection.addView(
            outlinedHelperButton("Step 1: Sign in to Groq") {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com")))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        )
        groqHelperSection.addView(
            outlinedHelperButton("Step 2: Create your API key") {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        )
        groqHelperSection.addView(TextView(this).apply {
            text = "Click Generate Key, then paste it below."
            textSize = 14f
            setTextColor(MUTED)
            setPadding(0, dp(4), 0, dp(8))
        })
        root.addView(groqHelperSection)

        val customFieldsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val (nameLayout, nameInput) = outlinedEditText(
            "Provider name",
            "e.g. OpenAI, Claude, My custom endpoint"
        )
        nameInput.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) scrollFieldIntoView(v)
        }
        customFieldsSection.addView(nameLayout, fieldMarginParams())
        root.addView(customFieldsSection)

        val (keyLayout, keyInput) = compactKeyField("Paste your API key here")
        root.addView(keyLayout, fieldMarginParams())

        val providerToggleLink = accentTextLink(
            "Already have an API key from another provider?"
        ) { }

        fun showGroqFlow() {
            useGroq = true
            groqHelperSection.visibility = View.VISIBLE
            customFieldsSection.visibility = View.GONE
            providerToggleLink.text = "Already have an API key from another provider?"
        }

        fun showCustomFlow() {
            useGroq = false
            groqHelperSection.visibility = View.GONE
            customFieldsSection.visibility = View.VISIBLE
            providerToggleLink.text = "Use Groq instead"
            scrollFieldIntoView(nameInput)
        }

        providerToggleLink.setOnClickListener {
            if (useGroq) showCustomFlow() else showGroqFlow()
        }
        root.addView(providerToggleLink)

        root.addView(primaryButton("Connect") {
            if (useGroq) {
                val key = keyInput.text?.toString()?.trim().orEmpty()
                if (key.isEmpty()) {
                    Toast.makeText(this, "Paste your Groq API key first", Toast.LENGTH_SHORT).show()
                    return@primaryButton
                }
                AiProviderActivity.saveGroqApiKey(this, key)
                Toast.makeText(this, "Groq connected!", Toast.LENGTH_SHORT).show()
            } else {
                val name = nameInput.text?.toString()?.trim().orEmpty()
                val key = keyInput.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Enter a provider name", Toast.LENGTH_SHORT).show()
                    return@primaryButton
                }
                if (key.isEmpty()) {
                    Toast.makeText(this, "Paste your API key first", Toast.LENGTH_SHORT).show()
                    return@primaryButton
                }
                AiProviderActivity.saveCustomNamedProvider(this, name, key)
                Toast.makeText(this, "Provider connected!", Toast.LENGTH_SHORT).show()
            }
            finish()
        })

        root.addView(TextView(this).apply {
            text = if (asOptionalBackup) {
                "You can skip this — on-device AI will handle replies when ready."
            } else {
                "We recommend not skipping — you'll miss Smart Replies, our best feature."
            }
            textSize = 13f
            setTextColor(ACCENT)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        })

        root.addView(
            MaterialButton(this).apply {
                text = "Skip for now"
                textSize = 14f
                isAllCaps = false
                setTextColor(MUTED)
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, 0) }
        )

        root.addView(
            accentTextLink("Manage all AI providers") {
                startActivity(Intent(this, AiProviderActivity::class.java))
            }
        )
    }

    // ── UI helpers ──

    private fun screenRoot(centerVertically: Boolean = true): LinearLayout {
        container.removeAllViews()
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (centerVertically) gravity = Gravity.CENTER_VERTICAL
        }
        val frame = FrameLayout(this)
        frame.addView(
            inner,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            frame,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        return inner
    }

    private fun heroIllustration(frameIndex: Int, sizeDp: Int): ImageView {
        val size = dp(sizeDp)
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "ScrollCat"
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(20)
                topMargin = dp(4)
            }
            try {
                assets.open("sprites/%04d.webp".format(frameIndex)).use { stream ->
                    setImageBitmap(BitmapFactory.decodeStream(stream))
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 24f
        typeface = UiKit.headingTypeface(this@AiSetupActivity)
        setTextColor(TEXT)
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 0, 0, dp(12))
    }

    private fun subtitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(MUTED)
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 0, 0, dp(20))
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(ACCENT)
        cornerRadius = dp(24)
        minHeight = dp(52)
        insetTop = 0
        insetBottom = 0
        setPadding(32, 28, 32, 28)
        setOnClickListener { onClick() }
    }

    private fun outlinedHelperButton(label: String, onClick: () -> Unit) = MaterialButton(
        ContextThemeWrapper(this, com.google.android.material.R.style.Widget_Material3_Button_OutlinedButton)
    ).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(ACCENT)
        strokeColor = ColorStateList.valueOf(ACCENT)
        strokeWidth = dp(1)
        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        cornerRadius = dp(20)
        minHeight = dp(40)
        insetTop = 0
        insetBottom = 0
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { onClick() }
    }

    private fun accentTextLink(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(ACCENT)
        paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, dp(4))
        setOnClickListener { onClick() }
    }

    private fun outlinedEditText(
        label: String,
        placeholder: String
    ): Pair<TextInputLayout, TextInputEditText> {
        val input = TextInputEditText(this).apply {
            setHintTextColor(HINT_COLOR)
            setTextColor(TEXT)
            textSize = 14f
            setSingleLine(true)
            background = null
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        val layout = TextInputLayout(
            ContextThemeWrapper(
                this,
                com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox_Dense
            )
        ).apply {
            hint = label
            placeholderText = placeholder
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxBackgroundColor(CARD)
            setBoxStrokeColorStateList(ColorStateList.valueOf(ACCENT))
            defaultHintTextColor = ColorStateList.valueOf(MUTED)
            setHintTextColor(ColorStateList.valueOf(MUTED))
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        return layout to input
    }

    private fun compactKeyField(hint: String): Pair<TextInputLayout, TextInputEditText> {
        val input = TextInputEditText(this).apply {
            this.hint = hint
            setHintTextColor(HINT_COLOR)
            setTextColor(TEXT)
            textSize = 14f
            setSingleLine(true)
            maxLines = 1
            minHeight = 0
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = null
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) scrollFieldIntoView(v)
            }
        }
        val layout = TextInputLayout(
            ContextThemeWrapper(
                this,
                com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox_Dense
            )
        ).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            setBoxBackgroundColor(CARD)
            setBoxStrokeColorStateList(ColorStateList.valueOf(ACCENT))
            defaultHintTextColor = ColorStateList.valueOf(MUTED)
            setHintTextColor(ColorStateList.valueOf(MUTED))
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        return layout to input
    }

    private fun scrollFieldIntoView(field: View) {
        field.post {
            val rect = android.graphics.Rect(0, 0, field.width, field.height + dp(48))
            field.requestRectangleOnScreen(rect, true)
        }
    }

    private fun fieldMarginParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 0, 0, dp(12)) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
