package com.example.scrollcat

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class AiProviderActivity : Activity() {

    data class AiProvider(
        val id: String,
        val name: String,
        val endpoint: String,
        val model: String,
        val apiKey: String,
        val isActive: Boolean = false
    )

    data class ProviderInfo(
        val key: String,
        val emoji: String,
        val title: String,
        val subtitle: String,
        val badge: String,
        val hint: String,
        val keyLinkText: String,
        val keyLinkUrl: String,
        val endpoint: String,
        val model: String
    )

    companion object {
        private const val ACCENT = 0xFFB39DDB.toInt()
        private const val CARD = 0xFF25252C.toInt()
        private const val CARD_SELECTED = 0xFF2E2A3A.toInt()
        private const val STROKE = 0xFF6B6578.toInt()
        private const val MUTED = 0xFFA39BB0.toInt()
        const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        const val GROQ_MODEL = "llama-3.1-8b-instant"
        private const val PREFS_NAME = "ai_providers"

        val PROVIDER_OPTIONS = listOf(
            ProviderInfo(
                key = "groq",
                emoji = "🟣",
                title = "Groq",
                subtitle = "Free tier\navailable",
                badge = "Free",
                hint = "Fast replies with a generous free tier. Great for everyday messages.",
                keyLinkText = "→ Get free Groq key at console.groq.com",
                keyLinkUrl = "https://console.groq.com",
                endpoint = GROQ_ENDPOINT,
                model = GROQ_MODEL
            ),
            ProviderInfo(
                key = "claude",
                emoji = "🔵",
                title = "Claude",
                subtitle = "Best quality",
                badge = "Paid",
                hint = "Thoughtful, natural replies. Best when quality matters most.",
                keyLinkText = "→ Get Claude key at console.anthropic.com",
                keyLinkUrl = "https://console.anthropic.com",
                endpoint = "https://api.anthropic.com/v1/messages",
                model = "claude-haiku-4-5"
            ),
            ProviderInfo(
                key = "openai",
                emoji = "🟢",
                title = "OpenAI",
                subtitle = "ChatGPT",
                badge = "Paid",
                hint = "Reliable ChatGPT-style replies. Familiar and versatile.",
                keyLinkText = "→ Get OpenAI key at platform.openai.com",
                keyLinkUrl = "https://platform.openai.com",
                endpoint = "https://api.openai.com/v1/chat/completions",
                model = "gpt-4o-mini"
            ),
            ProviderInfo(
                key = "custom",
                emoji = "⚙️",
                title = "Custom",
                subtitle = "Advanced",
                badge = "Paid",
                hint = "Connect your own AI service. For advanced users only.",
                keyLinkText = "→ Set up your own OpenAI-compatible service",
                keyLinkUrl = "",
                endpoint = "",
                model = ""
            )
        )

        val PRESETS = PROVIDER_OPTIONS.associate { it.key to Triple(it.endpoint, it.model, it.keyLinkText) }

        fun loadProviderList(context: android.content.Context): MutableList<AiProvider> {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val json = prefs.getString("providers", null) ?: return mutableListOf()
            return try {
                val arr = org.json.JSONArray(json)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    AiProvider(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        endpoint = obj.getString("endpoint"),
                        model = obj.getString("model"),
                        apiKey = obj.getString("apiKey"),
                        isActive = obj.getBoolean("isActive")
                    )
                }.toMutableList()
            } catch (_: Exception) {
                mutableListOf()
            }
        }

        /** Persist providers and sync the active one into SettingsManager / key stores. */
        fun saveProviderList(context: android.content.Context, providers: List<AiProvider>) {
            val arr = org.json.JSONArray()
            providers.forEach { p ->
                arr.put(org.json.JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("endpoint", p.endpoint)
                    put("model", p.model)
                    put("apiKey", p.apiKey)
                    put("isActive", p.isActive)
                })
            }
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString("providers", arr.toString())
                .putString("active_id", providers.find { it.isActive }?.id ?: "")
                .apply()

            val active = providers.find { it.isActive }
            if (active != null) {
                val endpoint = resolveEndpoint(active)
                val model = resolveModel(active)
                if (endpoint.isNotEmpty() && !endpoint.startsWith("https://")) {
                    Logger.e("Invalid endpoint URL for ${active.name}: $endpoint")
                    return
                }
                Logger.d("Saving active provider: ${active.name} endpoint: $endpoint model: $model")
                SettingsManager.setActiveAiProvider(context, endpoint, model, active.apiKey)
                if (active.name.lowercase().contains("groq") || active.id == "groq_default") {
                    ApiKeyStore.setGroqApiKey(context, active.apiKey)
                }
            } else {
                SettingsManager.setActiveAiProvider(context, "", "", "")
            }
        }

        fun saveGroqApiKey(context: android.content.Context, key: String) {
            ApiKeyStore.setGroqApiKey(context, key)
            val providers = loadProviderList(context)
            var found = false
            for (i in providers.indices) {
                val p = providers[i]
                if (p.id == "groq_default" || p.name.lowercase().contains("groq")) {
                    providers[i] = p.copy(
                        apiKey = key,
                        endpoint = GROQ_ENDPOINT,
                        model = GROQ_MODEL,
                        isActive = true
                    )
                    found = true
                } else {
                    providers[i] = p.copy(isActive = false)
                }
            }
            if (!found) {
                providers.add(
                    AiProvider(
                        id = "groq_default",
                        name = "Groq",
                        endpoint = GROQ_ENDPOINT,
                        model = GROQ_MODEL,
                        apiKey = key,
                        isActive = true
                    )
                )
            }
            saveProviderList(context, providers)
        }

        /**
         * Saves a named custom/third-party provider from onboarding.
         * Endpoint/model are inferred from the name when possible (same rules as the AI settings UI).
         */
        fun saveCustomNamedProvider(context: android.content.Context, name: String, apiKey: String) {
            val trimmed = name.trim().ifBlank { "Custom" }
            val endpoint = when {
                trimmed.lowercase().contains("claude") -> "https://api.anthropic.com/v1/messages"
                trimmed.lowercase().contains("openai") || trimmed.lowercase().contains("gpt") ->
                    "https://api.openai.com/v1/chat/completions"
                trimmed.lowercase().contains("groq") -> GROQ_ENDPOINT
                else -> GROQ_ENDPOINT // OpenAI-compatible default
            }
            val model = when {
                trimmed.lowercase().contains("claude") -> "claude-haiku-4-5"
                trimmed.lowercase().contains("openai") || trimmed.lowercase().contains("gpt") -> "gpt-4o-mini"
                trimmed.lowercase().contains("groq") -> GROQ_MODEL
                else -> "gpt-4o-mini"
            }
            val providers = loadProviderList(context).map { it.copy(isActive = false) }.toMutableList()
            providers.add(
                AiProvider(
                    id = "custom_${System.currentTimeMillis()}",
                    name = trimmed,
                    endpoint = endpoint,
                    model = model,
                    apiKey = apiKey,
                    isActive = true
                )
            )
            saveProviderList(context, providers)
        }

        private fun resolveEndpoint(active: AiProvider): String {
            if (active.endpoint.isNotEmpty()) return active.endpoint
            return when {
                active.name.lowercase().contains("groq") -> GROQ_ENDPOINT
                active.name.lowercase().contains("claude") -> "https://api.anthropic.com/v1/messages"
                active.name.lowercase().contains("openai") -> "https://api.openai.com/v1/chat/completions"
                else -> GROQ_ENDPOINT
            }
        }

        private fun resolveModel(active: AiProvider): String {
            if (active.model.isNotEmpty()) return active.model
            return when {
                active.name.lowercase().contains("groq") -> GROQ_MODEL
                active.name.lowercase().contains("claude") -> "claude-haiku-4-5"
                active.name.lowercase().contains("openai") -> "gpt-4o-mini"
                else -> GROQ_MODEL
            }
        }
    }

    private lateinit var providerListLayout: LinearLayout
    private var providers = mutableListOf<AiProvider>()
    private var activeProviderId: String = ""
    private var activeLabelView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadProviders()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1A1E.toInt())
            setPadding(0, 0, 0, 0)
        }

        // Toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF25252C.toInt())
            setPadding(32, 56, 32, 24)
        }
        TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(Color.WHITE)
            setOnClickListener { finish() }
            toolbar.addView(this)
        }
        TextView(this).apply {
            text = "  AI Reply Settings"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = UiKit.headingTypeface(this@AiProviderActivity)
            toolbar.addView(this)
        }
        root.addView(toolbar)

        // Active model indicator
        val activeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 20, 32, 20)
            setBackgroundColor(0xFF1A1A1E.toInt())
        }
        val activeLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(ACCENT)
            typeface = UiKit.headingTypeface(this@AiProviderActivity)
        }
        activeLabelView = activeLabel
        activeLayout.addView(activeLabel)
        root.addView(activeLayout)

        root.addView(divider())

        val scrollView = ScrollView(this)
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }

        TextView(this).apply {
            text = "Primary reply engine"
            textSize = 13f
            setTextColor(MUTED)
            typeface = UiKit.headingTypeface(this@AiProviderActivity)
            setPadding(8, 16, 8, 4)
            scrollContent.addView(this)
        }
        TextView(this).apply {
            text = "Pick which engine generates replies first. Others stay available as fallback when on-device is primary."
            textSize = 12f
            setTextColor(MUTED)
            setPadding(8, 0, 8, 12)
            scrollContent.addView(this)
        }

        providerListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollContent.addView(providerListLayout)

        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 32)
            setOnClickListener { showAddProviderDialog(existingProvider = null) }

            TextView(this@AiProviderActivity).apply {
                text = "+ Add AI Provider"
                textSize = 15f
                setTextColor(ACCENT)
                typeface = UiKit.headingTypeface(this@AiProviderActivity)
                addView(this)
            }
            scrollContent.addView(this)
        }

        scrollView.addView(scrollContent)
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }
        renderProviderList()
    }

    private fun refreshActiveLabel() {
        val primary = SettingsManager.getPrimaryAiProvider(this)
        activeLabelView?.text = when {
            primary == SettingsManager.PRIMARY_AI_ON_DEVICE &&
                ModelDownloadManager.modelFileExists(this) ->
                "Primary: 📱 On-Device AI (Gemma 4)"
            primary.isNotEmpty() -> {
                val match = providers.find {
                    SettingsManager.primaryKeyForCloudProvider(it.name, it.endpoint) == primary
                } ?: providers.find { it.isActive }
                if (match != null) "Primary: ${providerEmoji(match)} ${match.name}"
                else "Primary: $primary"
            }
            else -> "No AI provider set up yet"
        }
    }

    private fun renderProviderList() {
        providerListLayout.removeAllViews()
        refreshActiveLabel()

        val modelDownloaded = ModelDownloadManager.modelFileExists(this)
        val showOnDeviceOption = DeviceCapabilityChecker.checkOnDeviceAiViability(this) ||
            modelDownloaded

        if (showOnDeviceOption) {
            providerListLayout.addView(buildOnDevicePrimaryCard(modelDownloaded))
        }

        if (providers.isEmpty() && !modelDownloaded) {
            TextView(this).apply {
                text = "No providers yet.\nTap + Add AI Provider to get started."
                textSize = 14f
                setTextColor(0xFFA39BB0.toInt())
                gravity = Gravity.CENTER
                setPadding(32, 48, 32, 48)
                providerListLayout.addView(this)
            }
            return
        }

        if (providers.isNotEmpty()) {
            TextView(this).apply {
                text = "Cloud providers"
                textSize = 13f
                setTextColor(MUTED)
                typeface = UiKit.headingTypeface(this@AiProviderActivity)
                setPadding(8, 20, 8, 12)
                providerListLayout.addView(this)
            }
        }

        val primary = SettingsManager.getPrimaryAiProvider(this)

        providers.forEach { provider ->
            val info = providerInfoFor(provider)
            val providerKey = SettingsManager.primaryKeyForCloudProvider(provider.name, provider.endpoint)
            val selectedAsPrimary = primary != SettingsManager.PRIMARY_AI_ON_DEVICE &&
                primary == providerKey &&
                (provider.isActive || providers.count {
                    SettingsManager.primaryKeyForCloudProvider(it.name, it.endpoint) == providerKey
                } == 1)

            val maskedKey = if (provider.apiKey.length > 8)
                provider.apiKey.take(4) + "••••••••" + provider.apiKey.takeLast(4)
            else "••••••••"

            val badges = mutableListOf(
                BadgeSpec(
                    label = info.badge,
                    textColor = if (info.badge == "Free") 0xFF4CAF50.toInt() else 0xFFFFB74D.toInt()
                )
            )
            if (selectedAsPrimary) {
                badges.add(
                    BadgeSpec(
                        label = "Primary",
                        textColor = ACCENT,
                        outlined = true
                    )
                )
            }

            providerListLayout.addView(
                buildProviderCard(
                    title = "${info.emoji}  ${provider.name}",
                    badges = badges,
                    isPrimary = selectedAsPrimary,
                    enabled = true,
                    subtitle = null,
                    maskedApiKey = maskedKey,
                    onUse = { selectPrimaryCloudProvider(provider) },
                    onEdit = { showAddProviderDialog(existingProvider = provider) },
                    onDelete = {
                        android.app.AlertDialog.Builder(this@AiProviderActivity)
                            .setTitle("Remove ${provider.name}?")
                            .setMessage("This will remove this AI provider from ScrollCat.")
                            .setPositiveButton("Remove") { _, _ ->
                                val wasPrimary = selectedAsPrimary
                                providers.remove(provider)
                                if (provider.isActive && providers.isNotEmpty()) {
                                    providers[0] = providers[0].copy(isActive = true)
                                }
                                saveProviders()
                                if (wasPrimary && providers.isNotEmpty()) {
                                    selectPrimaryCloudProvider(providers.first())
                                } else if (wasPrimary &&
                                    ModelDownloadManager.modelFileExists(this@AiProviderActivity)
                                ) {
                                    selectPrimaryOnDevice()
                                } else {
                                    renderProviderList()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                )
            )
        }
    }

    private data class BadgeSpec(
        val label: String,
        val textColor: Int,
        val outlined: Boolean = false
    )

    /**
     * Spacious MaterialCardView layout shared by On-Device and cloud providers:
     * Row1 header+badges · Row2 Use/Primary pill · Row3 masked key · Row4 Edit/Delete.
     */
    private fun buildProviderCard(
        title: String,
        badges: List<BadgeSpec>,
        isPrimary: Boolean,
        enabled: Boolean,
        subtitle: String?,
        maskedApiKey: String?,
        onUse: (() -> Unit)?,
        onEdit: (() -> Unit)?,
        onDelete: (() -> Unit)?
    ): MaterialCardView {
        val pad = dp(18)
        val rowGap = dp(12)

        return MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(CARD)
            strokeWidth = if (isPrimary) dp(2) else dp(1)
            strokeColor = when {
                isPrimary -> ACCENT
                enabled -> STROKE
                else -> 0xFF3A3548.toInt()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(14)) }
            alpha = if (enabled) 1f else 0.55f

            val content = LinearLayout(this@AiProviderActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }

            // Row 1 — icon + name | badges
            val header = LinearLayout(this@AiProviderActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(TextView(this@AiProviderActivity).apply {
                text = title
                textSize = 16f
                setTextColor(if (enabled) Color.WHITE else MUTED)
                typeface = UiKit.headingTypeface(this@AiProviderActivity)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = dp(8) }
            })
            val badgeRow = LinearLayout(this@AiProviderActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            badges.forEachIndexed { index, badge ->
                badgeRow.addView(makeBadgeChip(badge).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (index > 0) marginStart = dp(6)
                    }
                })
            }
            header.addView(badgeRow)
            content.addView(header)

            if (!subtitle.isNullOrBlank()) {
                content.addView(TextView(this@AiProviderActivity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(MUTED)
                    setPadding(0, dp(8), 0, 0)
                })
            }

            // Row 2 — Use / Primary pill
            val useLabel = if (isPrimary) "Primary" else "Use"
            val useButton = MaterialButton(this@AiProviderActivity).apply {
                text = useLabel
                textSize = 14f
                isAllCaps = false
                cornerRadius = dp(24)
                minHeight = dp(52)
                insetTop = 0
                insetBottom = 0
                setPadding(dp(32), dp(14), dp(32), dp(14))
                if (isPrimary) {
                    isClickable = false
                    isFocusable = false
                    backgroundTintList = ColorStateList.valueOf(CARD_SELECTED)
                    setTextColor(ACCENT)
                    strokeWidth = dp(1)
                    strokeColor = ColorStateList.valueOf(ACCENT)
                } else if (enabled && onUse != null) {
                    backgroundTintList = ColorStateList.valueOf(ACCENT)
                    setTextColor(Color.WHITE)
                    setOnClickListener { onUse() }
                } else {
                    isEnabled = false
                    backgroundTintList = ColorStateList.valueOf(0xFF3A3548.toInt())
                    setTextColor(MUTED)
                }
            }
            content.addView(
                useButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = rowGap }
            )

            // Row 3 — masked API key (cloud only)
            if (!maskedApiKey.isNullOrBlank()) {
                content.addView(TextView(this@AiProviderActivity).apply {
                    text = maskedApiKey
                    textSize = 12f
                    setTextColor(MUTED)
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.START
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = rowGap }
                })
            }

            // Row 4 — Edit / Delete
            if (onEdit != null || onDelete != null) {
                val actions = LinearLayout(this@AiProviderActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(14) }
                }
                if (onEdit != null) {
                    actions.addView(TextView(this@AiProviderActivity).apply {
                        text = "Edit"
                        textSize = 14f
                        setTextColor(MUTED)
                        setPadding(0, dp(8), dp(20), dp(4))
                        setOnClickListener { onEdit() }
                    })
                }
                if (onDelete != null) {
                    actions.addView(TextView(this@AiProviderActivity).apply {
                        text = "Delete"
                        textSize = 14f
                        setTextColor(0xFFCC6666.toInt())
                        setPadding(0, dp(8), 0, dp(4))
                        setOnClickListener { onDelete() }
                    })
                }
                content.addView(actions)
            }

            addView(content)
        }
    }

    private fun makeBadgeChip(badge: BadgeSpec): TextView {
        return TextView(this).apply {
            text = " ${badge.label} "
            textSize = 10f
            setTextColor(badge.textColor)
            background = GradientDrawable().apply {
                setColor(0xFF2E2A3A.toInt())
                cornerRadius = dp(32).toFloat()
                if (badge.outlined) setStroke(dp(1), badge.textColor)
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
    }

    private fun buildOnDevicePrimaryCard(modelDownloaded: Boolean): MaterialCardView {
        val primary = SettingsManager.getPrimaryAiProvider(this)
        val isPrimary = modelDownloaded && primary == SettingsManager.PRIMARY_AI_ON_DEVICE

        val badges = mutableListOf(
            BadgeSpec(label = "Recommended", textColor = 0xFF4CAF50.toInt())
        )
        if (isPrimary) {
            badges.add(BadgeSpec(label = "Primary", textColor = ACCENT, outlined = true))
        }

        val card = buildProviderCard(
            title = "📱  On-Device AI (Gemma 4)",
            badges = badges,
            isPrimary = isPrimary,
            enabled = modelDownloaded,
            subtitle = if (modelDownloaded) {
                "Private on-phone replies. Cloud providers stay as automatic fallback."
            } else {
                "Download in My AI Settings first."
            },
            maskedApiKey = null,
            onUse = if (modelDownloaded) {
                { selectPrimaryOnDevice() }
            } else {
                null
            },
            onEdit = null,
            onDelete = null
        )

        if (!modelDownloaded) {
            card.setOnClickListener {
                Toast.makeText(
                    this,
                    "Download On-Device AI in My AI Settings first",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        return card
    }

    /** Make on-device the primary engine and re-initialize if needed. */
    private fun selectPrimaryOnDevice() {
        if (!ModelDownloadManager.modelFileExists(this)) {
            Toast.makeText(this, "Download the model in My AI Settings first", Toast.LENGTH_SHORT)
                .show()
            return
        }
        SettingsManager.setPrimaryAiProvider(this, SettingsManager.PRIMARY_AI_ON_DEVICE)
        Toast.makeText(this, "Primary: On-Device AI", Toast.LENGTH_SHORT).show()
        renderProviderList()
        Thread {
            val ok = OnDeviceAiEngine.ensureInitialized(this)
            android.util.Log.d(
                "ScrollCat",
                "AiProvider: selected on-device primary — initialize success=$ok"
            )
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(
                        this,
                        "On-device selected, but engine failed to load",
                        Toast.LENGTH_LONG
                    ).show()
                }
                refreshActiveLabel()
            }
        }.start()
    }

    /**
     * Make a cloud provider primary: persist selection, mark it active for API config,
     * and immediately unload on-device from memory (file stays on disk).
     */
    private fun selectPrimaryCloudProvider(provider: AiProvider) {
        val key = SettingsManager.primaryKeyForCloudProvider(provider.name, provider.endpoint)
        SettingsManager.setPrimaryAiProvider(this, key)
        providers = providers.map {
            it.copy(isActive = it.id == provider.id)
        }.toMutableList()
        saveProviders()
        Toast.makeText(this, "Primary: ${provider.name}", Toast.LENGTH_SHORT).show()
        renderProviderList()
        Thread {
            OnDeviceAiEngine.shutdown()
            android.util.Log.d(
                "ScrollCat",
                "AiProvider: primary=$key — on-device engine shut down (file kept)"
            )
        }.start()
    }

    private fun showAddProviderDialog(existingProvider: AiProvider? = null) {
        val initialKey = existingProvider?.let { guessPresetKey(it) } ?: "groq"
        var selectedKey = initialKey

        val dialogRoot = ScrollView(this).apply {
            setBackgroundColor(CARD)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(CARD)
        }
        dialogRoot.addView(content)

        TextView(this).apply {
            text = if (existingProvider != null) "Edit AI Provider" else "Add AI Provider"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = UiKit.headingTypeface(this@AiProviderActivity)
            setPadding(0, 0, 0, dp(16))
            content.addView(this)
        }

        TextView(this).apply {
            text = "Choose a provider"
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, 0, 0, dp(12))
            content.addView(this)
        }

        val hintText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFE8E4EF.toInt())
            setPadding(0, dp(16), 0, dp(8))
        }

        val nameInput = EditText(this).apply {
            setHintTextColor(0xFFA39BB0.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
            background = null
            setText(existingProvider?.name ?: PROVIDER_OPTIONS.first { it.key == initialKey }.title)
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) scrollFieldIntoView(dialogRoot, v)
            }
        }

        val keyLinkText = TextView(this).apply {
            textSize = 13f
            setTextColor(ACCENT)
            setPadding(0, dp(10), 0, dp(4))
        }

        val customFieldsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val customEndpointInput = EditText(this).apply {
            hint = "https://..."
            setHintTextColor(0xFFA39BB0.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
            background = null
            setText(existingProvider?.endpoint ?: "")
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) scrollFieldIntoView(dialogRoot, v)
            }
        }
        val customModelInput = EditText(this).apply {
            hint = "Your model name"
            setHintTextColor(0xFFA39BB0.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
            background = null
            setText(existingProvider?.model ?: "")
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) scrollFieldIntoView(dialogRoot, v)
            }
        }

        val cardViews = mutableMapOf<String, LinearLayout>()
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        PROVIDER_OPTIONS.forEachIndexed { index, info ->
            val card = buildSelectorCard(info, selectedKey == info.key) { key ->
                selectedKey = key
                cardViews.values.forEach { layout ->
                    val k = layout.tag as String
                    updateSelectorCard(layout, k == selectedKey)
                }
                applyProviderSelection(
                    selectedKey, nameInput, hintText, keyLinkText,
                    customFieldsSection, customEndpointInput, customModelInput
                )
            }
            card.tag = info.key
            cardViews[info.key] = card
            if (index < 2) row1.addView(card) else row2.addView(card)
        }
        content.addView(row1)
        content.addView(row2)
        content.addView(hintText)

        content.addView(thinDivider())

        TextView(this).apply {
            text = "Provider name"
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, dp(16), 0, dp(4))
            content.addView(this)
        }
        content.addView(nameInput)
        content.addView(thinDivider())

        TextView(this).apply {
            text = "API Key"
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, dp(16), 0, dp(4))
            content.addView(this)
        }

        val keyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var keyVisible = false
        val keyInput = EditText(this).apply {
            hint = "Paste your key here"
            setHintTextColor(0xFFA39BB0.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
            background = null
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setText(existingProvider?.apiKey ?: "")
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) scrollFieldIntoView(dialogRoot, v)
            }
        }
        val eyeBtn = TextView(this).apply {
            text = "👁"
            textSize = 18f
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener {
                keyVisible = !keyVisible
                keyInput.inputType = if (keyVisible)
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                keyInput.setSelection(keyInput.text.length)
                text = if (keyVisible) "🔒" else "👁"
            }
        }
        keyRow.addView(keyInput)
        keyRow.addView(eyeBtn)
        content.addView(keyRow)
        content.addView(keyLinkText)

        TextView(this).apply {
            text = "Custom endpoint"
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, dp(12), 0, dp(4))
            customFieldsSection.addView(this)
        }
        customFieldsSection.addView(customEndpointInput)

        TextView(this).apply {
            text = "Model name"
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, dp(12), 0, dp(4))
            customFieldsSection.addView(this)
        }
        customFieldsSection.addView(customModelInput)
        content.addView(customFieldsSection)

        applyProviderSelection(
            selectedKey, nameInput, hintText, keyLinkText,
            customFieldsSection, customEndpointInput, customModelInput
        )

        keyLinkText.setOnClickListener {
            val info = PROVIDER_OPTIONS.first { it.key == selectedKey }
            if (info.keyLinkUrl.isNotEmpty()) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.keyLinkUrl)))
            }
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogRoot)
            .setPositiveButton("Save") { _, _ ->
                val info = PROVIDER_OPTIONS.first { it.key == selectedKey }
                val name = nameInput.text.toString().trim().ifEmpty { info.title }
                val key = keyInput.text.toString().trim()

                if (key.isEmpty()) {
                    Toast.makeText(this, "Please paste your API key", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val endpoint: String
                val model: String
                if (selectedKey == "custom") {
                    endpoint = customEndpointInput.text.toString().trim()
                    model = customModelInput.text.toString().trim()
                    if (endpoint.isEmpty() || model.isEmpty()) {
                        Toast.makeText(this, "Please fill in custom endpoint and model", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                } else {
                    endpoint = info.endpoint
                    model = info.model
                }

                if (endpoint.isNotEmpty() && !endpoint.startsWith("https://")) {
                    Toast.makeText(this, "Invalid endpoint URL - must start with https://", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val provider = AiProvider(
                    id = existingProvider?.id ?: System.currentTimeMillis().toString(),
                    name = name,
                    endpoint = endpoint,
                    model = model,
                    apiKey = key,
                    isActive = existingProvider?.isActive ?: providers.isEmpty()
                )

                if (existingProvider != null) {
                    val idx = providers.indexOfFirst { it.id == existingProvider.id }
                    if (idx >= 0) providers[idx] = provider
                } else {
                    providers.add(provider)
                }

                saveProviders()
                renderProviderList()
                Toast.makeText(this, "Provider saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(CARD) })
        dialog.show()
    }

    private fun applyProviderSelection(
        selectedKey: String,
        nameInput: EditText,
        hintText: TextView,
        keyLinkText: TextView,
        customFieldsSection: LinearLayout,
        customEndpointInput: EditText,
        customModelInput: EditText
    ) {
        val info = PROVIDER_OPTIONS.first { it.key == selectedKey }
        if (nameInput.text.toString().trim().isEmpty() ||
            PROVIDER_OPTIONS.any { it.title == nameInput.text.toString().trim() }
        ) {
            nameInput.setText(info.title)
        }
        hintText.text = info.hint
        keyLinkText.text = info.keyLinkText
        keyLinkText.visibility = if (info.keyLinkUrl.isNotEmpty() || selectedKey == "custom") View.VISIBLE else View.GONE

        if (selectedKey == "custom") {
            customFieldsSection.visibility = View.VISIBLE
        } else {
            customFieldsSection.visibility = View.GONE
            customEndpointInput.setText(info.endpoint)
            customModelInput.setText(info.model)
        }
    }

    private fun buildSelectorCard(
        info: ProviderInfo,
        selected: Boolean,
        onSelect: (String) -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = selectorCardBackground(selected)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnClickListener { onSelect(info.key) }

            TextView(this@AiProviderActivity).apply {
                text = info.emoji
                textSize = 22f
                gravity = Gravity.CENTER
                addView(this)
            }
            TextView(this@AiProviderActivity).apply {
                text = info.title
                textSize = 14f
                setTextColor(Color.WHITE)
                typeface = UiKit.headingTypeface(this@AiProviderActivity)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
                addView(this)
            }
            TextView(this@AiProviderActivity).apply {
                text = info.subtitle
                textSize = 11f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
                addView(this)
            }
        }
    }

    private fun updateSelectorCard(card: LinearLayout, selected: Boolean) {
        card.background = selectorCardBackground(selected)
    }

    private fun selectorCardBackground(selected: Boolean) = GradientDrawable().apply {
        setColor(if (selected) CARD_SELECTED else 0xFF2E2A3A.toInt())
        cornerRadius = dp(12).toFloat()
        setStroke(if (selected) 2 else 1, if (selected) ACCENT else STROKE)
    }

    private fun guessPresetKey(provider: AiProvider): String {
        val nameLower = provider.name.lowercase()
        val endpointLower = provider.endpoint.lowercase()
        return when {
            nameLower.contains("groq") || endpointLower.contains("groq.com") -> "groq"
            nameLower.contains("claude") || endpointLower.contains("anthropic.com") -> "claude"
            nameLower.contains("openai") || endpointLower.contains("openai.com") -> "openai"
            else -> "custom"
        }
    }

    private fun providerInfoFor(provider: AiProvider): ProviderInfo {
        val key = guessPresetKey(provider)
        return PROVIDER_OPTIONS.firstOrNull { it.key == key }
            ?: PROVIDER_OPTIONS.first { it.key == "custom" }
    }

    private fun providerEmoji(provider: AiProvider): String = providerInfoFor(provider).emoji

    private fun scrollFieldIntoView(scroll: ScrollView, field: View) {
        field.post {
            val rect = android.graphics.Rect(0, 0, field.width, field.height + dp(48))
            field.requestRectangleOnScreen(rect, true)
        }
        scroll.post {
            val loc = IntArray(2)
            field.getLocationOnScreen(loc)
            val scrollLoc = IntArray(2)
            scroll.getLocationOnScreen(scrollLoc)
            val relativeTop = loc[1] - scrollLoc[1]
            val target = (scroll.scrollY + relativeTop - dp(24)).coerceAtLeast(0)
            scroll.smoothScrollTo(0, target)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun setActiveProvider(providerId: String) {
        val provider = providers.find { it.id == providerId } ?: return
        selectPrimaryCloudProvider(provider)
    }

    private fun loadProviders() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        activeProviderId = prefs.getString("active_id", "") ?: ""
        providers = loadProviderList(this)
        if (providers.isEmpty()) {
            migrateExistingKeys()
        }
    }

    private fun migrateExistingKeys() {
        val groqKey = ApiKeyStore.getGroqApiKey(this)
        val claudeKey = ApiKeyStore.getClaudeApiKey(this)

        if (!groqKey.isNullOrEmpty()) {
            providers.add(
                AiProvider(
                    id = "groq_default",
                    name = "Groq",
                    endpoint = PRESETS["groq"]!!.first,
                    model = PRESETS["groq"]!!.second,
                    apiKey = groqKey,
                    isActive = true
                )
            )
        }
        if (claudeKey.isNotEmpty()) {
            providers.add(
                AiProvider(
                    id = "claude_default",
                    name = "Claude",
                    endpoint = PRESETS["claude"]!!.first,
                    model = PRESETS["claude"]!!.second,
                    apiKey = claudeKey,
                    isActive = groqKey.isNullOrEmpty()
                )
            )
        }
        if (providers.isNotEmpty()) saveProviders()
    }

    private fun saveProviders() {
        saveProviderList(this, providers)
    }

    private fun divider() = android.view.View(this).apply {
        setBackgroundColor(0xFF2E2A3A.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        )
    }

    private fun thinDivider() = android.view.View(this).apply {
        setBackgroundColor(0xFF3A3548.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { setMargins(0, 4, 0, 4) }
    }
}
