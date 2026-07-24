package dev.zero.inkchat.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.zero.inkchat.domain.ChatSettings

/**
 * The only place where API keys live. Never log values from here.
 */
class SecurePrefs(context: Context) : ChatSettings {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun apiKey(providerId: String): String? = prefs.getString("api_key_$providerId", null)

    fun setApiKey(providerId: String, key: String?) = prefs.edit {
        if (key.isNullOrBlank()) remove("api_key_$providerId") else putString("api_key_$providerId", key)
    }

    override var activeProviderId: String?
        get() = prefs.getString("active_provider", null)
        set(value) = prefs.edit { putString("active_provider", value) }

    override fun defaultModelFor(providerId: String): String? =
        prefs.getString("default_model_$providerId", null)
            // Legacy key from the single-provider (OpenRouter-only) era.
            ?: if (providerId == "openrouter") prefs.getString("default_model_id", null) else null

    fun setDefaultModel(providerId: String, modelId: String?) = prefs.edit {
        if (modelId.isNullOrBlank()) {
            remove("default_model_$providerId")
        } else {
            putString("default_model_$providerId", modelId)
        }
    }

    /** Base URL of the local OpenAI-compatible server (Ollama / llama.cpp). */
    var localBaseUrl: String?
        get() = prefs.getString("local_base_url", null)
        set(value) = prefs.edit {
            if (value.isNullOrBlank()) remove("local_base_url") else putString("local_base_url", value)
        }

    override var systemPrompt: String?
        get() = prefs.getString("system_prompt", null)
        set(value) = prefs.edit { putString("system_prompt", value) }

    override var temperature: Float?
        get() = if (prefs.contains("temperature")) prefs.getFloat("temperature", 0f) else null
        set(value) = prefs.edit {
            if (value == null) remove("temperature") else putFloat("temperature", value)
        }

    override var maxTokens: Int?
        get() = if (prefs.contains("max_tokens")) prefs.getInt("max_tokens", 0) else null
        set(value) = prefs.edit {
            if (value == null) remove("max_tokens") else putInt("max_tokens", value)
        }
}
