package dev.zero.inkchat.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dev.zero.inkchat.App
import dev.zero.inkchat.AppGraph
import dev.zero.inkchat.R
import dev.zero.inkchat.data.provider.AiProvider
import dev.zero.inkchat.data.provider.ProviderException
import dev.zero.inkchat.data.provider.openrouter.OpenRouterProvider
import dev.zero.inkchat.databinding.ActivitySettingsBinding
import dev.zero.inkchat.ui.eink.EinkRefresh
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val graph get() = (application as App).graph
    private val prefs get() = graph.securePrefs

    /**
     * Provider currently being configured. Local to this screen — it does NOT
     * change the default provider for new conversations (that lives on the
     * home screen). Starts on the current default for convenience.
     */
    private var configuringProviderId: String? = null

    private val configuringProvider: AiProvider
        get() = graph.providerRegistry.get(
            configuringProviderId ?: prefs.activeProviderId ?: OpenRouterProvider.ID
        ) ?: graph.providerRegistry.all.first()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnTestConnection.setOnClickListener { testConnection() }
        binding.btnDeleteKey.setOnClickListener { deleteKey() }
        binding.btnChangeProvider.setOnClickListener { showProviderPicker() }
        binding.btnAbout.setOnClickListener {
            startActivity(android.content.Intent(this, dev.zero.inkchat.ui.about.AboutActivity::class.java))
        }
        binding.btnChooseModel.setOnClickListener {
            startActivity(
                ModelPickerActivity.intent(this, conversationId = null, providerId = configuringProvider.id)
            )
        }
        binding.inputApiKey.setOnFocusChangeListener { view, hasFocus ->
            (view as android.widget.EditText).isCursorVisible = hasFocus
        }

        // Migration: if an OpenRouter key was stored without the prefix (or with
        // spaces), repair it here — it caused 401 "missing authentication header".
        prefs.apiKey(OpenRouterProvider.ID)?.let { existing ->
            val fixed = normalizeKey(OpenRouterProvider.ID, existing)
            if (fixed != null && fixed != existing) {
                prefs.setApiKey(OpenRouterProvider.ID, fixed)
            }
        }

        binding.inputSystemPrompt.setText(prefs.systemPrompt.orEmpty())
        binding.inputTemperature.setText(prefs.temperature?.toString().orEmpty())
        binding.inputMaxTokens.setText(prefs.maxTokens?.toString().orEmpty())
        renderProvider()
    }

    override fun onResume() {
        super.onResume()
        renderProvider()
        EinkRefresh.fullRefresh(binding.root)
    }

    private fun showProviderPicker() {
        val providers = graph.providerRegistry.all
        AlertDialog.Builder(this, R.style.Theme_InkChat_Dialog)
            .setTitle(R.string.configure_provider_title)
            .setItems(providers.map { it.displayName }.toTypedArray()) { _, which ->
                // Only switches which provider we're editing — the default for
                // new conversations is chosen on the home screen.
                configuringProviderId = providers[which].id
                binding.txtStatus.text = ""
                renderProvider()
            }
            .show()
    }

    /** Re-binds every provider-scoped field to the provider being configured. */
    private fun renderProvider() {
        val provider = configuringProvider
        binding.txtProvider.text = provider.displayName

        val isLocal = provider.id == AppGraph.LOCAL_PROVIDER_ID
        binding.baseUrlLabel.isVisible = isLocal
        binding.inputBaseUrl.isVisible = isLocal
        if (isLocal) {
            binding.inputBaseUrl.setText(prefs.localBaseUrl ?: AppGraph.DEFAULT_LOCAL_BASE_URL)
        }
        binding.inputApiKey.setText(
            if (provider.id == OpenRouterProvider.ID) OPENROUTER_KEY_PREFIX else ""
        )
        renderKeyStatus()
        renderDefaultModel()
    }

    /**
     * Normalizes a pasted key: strips whitespace, a pasted "Bearer" prefix from
     * docs, and (for OpenRouter) deduplicates the sk-or-v1- prefix the input
     * field pre-fills. Returns null when nothing was entered (= keep current).
     */
    private fun normalizeKey(providerId: String, raw: String): String? {
        var body = raw
            .filterNot { it.isWhitespace() }
            .replace(Regex("(?i)bearer"), "")
        if (providerId == OpenRouterProvider.ID) {
            body = body.replace(OPENROUTER_KEY_PREFIX, "")
            return if (body.isEmpty()) null else OPENROUTER_KEY_PREFIX + body
        }
        return body.ifEmpty { null }
    }

    private fun deleteKey() {
        val provider = configuringProvider
        prefs.setApiKey(provider.id, null)
        binding.inputApiKey.setText(
            if (provider.id == OpenRouterProvider.ID) OPENROUTER_KEY_PREFIX else ""
        )
        renderKeyStatus()
        binding.txtStatus.text = getString(R.string.key_deleted)
    }

    private fun renderKeyStatus() {
        val key = prefs.apiKey(configuringProvider.id)
        binding.btnDeleteKey.isVisible = !key.isNullOrBlank()
        binding.txtKeyStatus.text = if (key.isNullOrBlank()) {
            getString(R.string.api_key_missing)
        } else {
            getString(R.string.api_key_configured, key.takeLast(4))
        }
    }

    private fun renderDefaultModel() {
        val provider = configuringProvider
        binding.txtDefaultModel.text = prefs.defaultModelFor(provider.id)
            ?: getString(R.string.default_model_fallback, provider.fallbackModelId)
    }

    private fun save() {
        val temperatureText = binding.inputTemperature.text.toString().trim().replace(',', '.')
        val temperature = if (temperatureText.isEmpty()) null else temperatureText.toFloatOrNull()
        if (temperatureText.isNotEmpty() && (temperature == null || temperature < 0f || temperature > 2f)) {
            binding.txtStatus.text = getString(R.string.invalid_temperature)
            return
        }

        val maxTokensText = binding.inputMaxTokens.text.toString().trim()
        val maxTokens = if (maxTokensText.isEmpty()) null else maxTokensText.toIntOrNull()
        if (maxTokensText.isNotEmpty() && (maxTokens == null || maxTokens < 1)) {
            binding.txtStatus.text = getString(R.string.invalid_max_tokens)
            return
        }

        val provider = configuringProvider

        // Field containing nothing (or only the pre-filled prefix) = keep the current key.
        normalizeKey(provider.id, binding.inputApiKey.text.toString())?.let { key ->
            prefs.setApiKey(provider.id, key)
            binding.inputApiKey.setText(
                if (provider.id == OpenRouterProvider.ID) OPENROUTER_KEY_PREFIX else ""
            )
        }

        if (provider.id == AppGraph.LOCAL_PROVIDER_ID) {
            prefs.localBaseUrl = binding.inputBaseUrl.text.toString().trim().ifEmpty { null }
        }

        prefs.systemPrompt = binding.inputSystemPrompt.text.toString().trim().ifEmpty { null }
        prefs.temperature = temperature
        prefs.maxTokens = maxTokens

        renderKeyStatus()
        binding.txtStatus.text = getString(R.string.saved)
    }

    private fun testConnection() {
        binding.txtStatus.text = getString(R.string.testing_connection)
        val provider = configuringProvider
        lifecycleScope.launch {
            binding.txtStatus.text = try {
                provider.verifyAuth()
                val models = provider.listModels()
                getString(R.string.connection_ok, models.size)
            } catch (e: ProviderException) {
                e.message
            } catch (e: Exception) {
                getString(R.string.connection_failed, e.message.orEmpty())
            }
        }
    }

    companion object {
        /** OpenRouter API key prefix, pre-filled in the input for convenience. */
        private const val OPENROUTER_KEY_PREFIX = "sk-or-v1-"
    }
}
