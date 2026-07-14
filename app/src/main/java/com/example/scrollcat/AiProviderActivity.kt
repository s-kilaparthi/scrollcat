package com.example.scrollcat

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*

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
        private const val ACCENT = 0xFF4A90D9.toInt()
        private const val CARD = 0xFF1a1a1a.toInt()
        private const val CARD_SELECTED = 0xFF0D1B2A.toInt()
        private const val STROKE = 0xFF333333.toInt()
        private const val MUTED = 0xFF888888.toInt()
        private const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

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
                model = "llama-3.1-8b-instant"
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
    }

    private lateinit var providerListLayout: LinearLayout
    private var providers = mutableListOf<AiProvider>()
    private var activeProviderId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadProviders()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0a0a0a.toInt())
            setPadding(0, 0, 0, 0)
        }

        // Toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1a1a1a.toInt())
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
            typeface = Typeface.DEFAULT_BOLD
            toolbar.addView(this)
        }
        root.addView(toolbar)

        // Active model indicator
        val activeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 20, 32, 20)
            setBackgroundColor(0xFF111111.toInt())
        }
        val activeLabel = TextView(this).apply {
            val active = providers.find { it.isActive }
            text = if (active != null) "Active: ${providerEmoji(active)} ${active.name}"
            else "No AI provider set up yet"
            textSize = 14f
            setTextColor(ACCENT)
            typeface = Typeface.DEFAULT_BOLD
        }
        activeLayout.addView(activeLabel)
        root.addView(activeLayout)

        root.addView(divider())

        val scrollView = ScrollView(this)
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }

        TextView(this).apply {
            text = "My AI Providers"
            textSize = 13f
            setTextColor(MUTED)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(8, 16, 8, 12)
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
                typeface = Typeface.DEFAULT_BOLD
                addView(this)
            }
            scrollContent.addView(this)
        }

        scrollView.addView(scrollContent)
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        renderProviderList()
    }

    private fun renderProviderList() {
        providerListLayout.removeAllViews()

        if (providers.isEmpty()) {
            TextView(this).apply {
                text = "No providers yet.\nTap + Add AI Provider to get started."
                textSize = 14f
                setTextColor(0xFF666666.toInt())
                gravity = Gravity.CENTER
                setPadding(32, 48, 32, 48)
                providerListLayout.addView(this)
            }
            return
        }

        providers.forEach { provider ->
            val info = providerInfoFor(provider)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 18, 20, 18)
                background = GradientDrawable().apply {
                    setColor(CARD)
                    cornerRadius = 16f
                    if (provider.isActive) setStroke(2, ACCENT) else setStroke(1, STROKE)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            TextView(this).apply {
                text = "${info.emoji}  ${provider.name}"
                textSize = 16f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                topRow.addView(this)
            }

            TextView(this).apply {
                text = " ${info.badge} "
                textSize = 10f
                setTextColor(if (info.badge == "Free") 0xFF4CAF50.toInt() else 0xFFFFB74D.toInt())
                background = GradientDrawable().apply {
                    setColor(0xFF222222.toInt())
                    cornerRadius = 32f
                }
                setPadding(12, 4, 12, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8 }
                topRow.addView(this)
            }

            if (provider.isActive) {
                TextView(this).apply {
                    text = " Active "
                    textSize = 11f
                    setTextColor(ACCENT)
                    background = GradientDrawable().apply {
                        setColor(CARD_SELECTED)
                        cornerRadius = 32f
                        setStroke(1, ACCENT)
                    }
                    setPadding(14, 6, 14, 6)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = 6 }
                    topRow.addView(this)
                }
            } else {
                TextView(this).apply {
                    text = "Use"
                    textSize = 12f
                    setTextColor(ACCENT)
                    setPadding(14, 8, 14, 8)
                    background = GradientDrawable().apply {
                        setColor(CARD_SELECTED)
                        cornerRadius = 32f
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = 6 }
                    setOnClickListener { setActiveProvider(provider.id) }
                    topRow.addView(this)
                }
            }

            TextView(this).apply {
                text = "Edit"
                textSize = 12f
                setTextColor(MUTED)
                setPadding(10, 8, 10, 8)
                setOnClickListener { showAddProviderDialog(existingProvider = provider) }
                topRow.addView(this)
            }

            TextView(this).apply {
                text = "Delete"
                textSize = 12f
                setTextColor(0xFFCC6666.toInt())
                setPadding(8, 8, 0, 8)
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@AiProviderActivity)
                        .setTitle("Remove ${provider.name}?")
                        .setMessage("This will remove this AI provider from ScrollCat.")
                        .setPositiveButton("Remove") { _, _ ->
                            providers.remove(provider)
                            if (provider.isActive && providers.isNotEmpty()) {
                                providers[0] = providers[0].copy(isActive = true)
                            }
                            saveProviders()
                            renderProviderList()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                topRow.addView(this)
            }

            card.addView(topRow)

            val maskedKey = if (provider.apiKey.length > 8)
                provider.apiKey.take(4) + "••••••••" + provider.apiKey.takeLast(4)
            else "••••••••"
            TextView(this).apply {
                text = maskedKey
                textSize = 12f
                setTextColor(0xFF555555.toInt())
                typeface = Typeface.MONOSPACE
                setPadding(0, 8, 0, 0)
                card.addView(this)
            }

            providerListLayout.addView(card)
        }
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
            typeface = Typeface.DEFAULT_BOLD
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
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, dp(16), 0, dp(8))
        }

        val nameInput = EditText(this).apply {
            setHintTextColor(0xFF555555.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, dp(8), 0, dp(8))
            background = null
            setText(existingProvider?.name ?: PROVIDER_OPTIONS.first { it.key == initialKey }.title)
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
            setHintTextColor(0xFF555555.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
            background = null
            setText(existingProvider?.endpoint ?: "")
        }
        val customModelInput = EditText(this).apply {
            hint = "Your model name"
            setHintTextColor(0xFF555555.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
            background = null
            setText(existingProvider?.model ?: "")
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
            setHintTextColor(0xFF555555.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
            background = null
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setText(existingProvider?.apiKey ?: "")
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
                typeface = Typeface.DEFAULT_BOLD
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
        setColor(if (selected) CARD_SELECTED else 0xFF2a2a2a.toInt())
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun setActiveProvider(providerId: String) {
        providers = providers.map {
            it.copy(isActive = it.id == providerId)
        }.toMutableList()
        saveProviders()
        renderProviderList()
        Toast.makeText(this, "AI provider switched!", Toast.LENGTH_SHORT).show()
    }

    private fun loadProviders() {
        val prefs = getSharedPreferences("ai_providers", MODE_PRIVATE)
        val json = prefs.getString("providers", null)
        activeProviderId = prefs.getString("active_id", "") ?: ""

        if (json != null) {
            try {
                val arr = org.json.JSONArray(json)
                providers = (0 until arr.length()).map { i ->
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
            } catch (e: Exception) {
                providers = mutableListOf()
            }
        } else {
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
        getSharedPreferences("ai_providers", MODE_PRIVATE)
            .edit()
            .putString("providers", arr.toString())
            .putString("active_id", providers.find { it.isActive }?.id ?: "")
            .apply()

        val active = providers.find { it.isActive }
        if (active != null) {
            val endpoint = if (active.endpoint.isEmpty()) {
                when {
                    active.name.lowercase().contains("groq") -> GROQ_ENDPOINT
                    active.name.lowercase().contains("claude") ->
                        "https://api.anthropic.com/v1/messages"
                    active.name.lowercase().contains("openai") ->
                        "https://api.openai.com/v1/chat/completions"
                    else -> GROQ_ENDPOINT
                }
            } else active.endpoint

            if (endpoint.isNotEmpty() && !endpoint.startsWith("https://")) {
                Toast.makeText(this, "Invalid endpoint URL - must start with https://", Toast.LENGTH_SHORT).show()
                return
            }

            val model = if (active.model.isEmpty()) {
                when {
                    active.name.lowercase().contains("groq") -> "llama-3.1-8b-instant"
                    active.name.lowercase().contains("claude") -> "claude-haiku-4-5"
                    active.name.lowercase().contains("openai") -> "gpt-4o-mini"
                    else -> "llama-3.1-8b-instant"
                }
            } else active.model

            Logger.d("Saving active provider: ${active.name} endpoint: $endpoint model: $model")

            SettingsManager.setActiveAiProvider(
                this,
                endpoint,
                model,
                active.apiKey
            )
        } else {
            SettingsManager.setActiveAiProvider(this, "", "", "")
        }
    }

    private fun divider() = android.view.View(this).apply {
        setBackgroundColor(0xFF222222.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        )
    }

    private fun thinDivider() = android.view.View(this).apply {
        setBackgroundColor(0xFF333333.toInt())
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { setMargins(0, 4, 0, 4) }
    }
}
