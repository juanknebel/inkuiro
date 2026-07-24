package dev.zero.inkchat

import android.content.Context
import dev.zero.inkchat.data.db.AppDatabase
import dev.zero.inkchat.data.prefs.SecurePrefs
import dev.zero.inkchat.data.provider.ProviderRegistry
import dev.zero.inkchat.data.provider.anthropic.AnthropicProvider
import dev.zero.inkchat.data.provider.compat.OpenAiCompatProvider
import dev.zero.inkchat.data.provider.gemini.GeminiProvider
import dev.zero.inkchat.data.provider.openrouter.OpenRouterProvider
import dev.zero.inkchat.domain.ChatRepository
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Manual dependency graph: personal app, no DI framework.
 * Everything lazy to avoid paying initialization cost at startup.
 */
class AppGraph(private val context: Context) {

    val database: AppDatabase by lazy { AppDatabase.build(context) }

    val securePrefs: SecurePrefs by lazy { SecurePrefs(context) }

    // No logging interceptor on purpose: the Authorization header must never
    // be able to end up in any log (plan §8).
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // SSE: OpenRouter sends keep-alive comments while processing, so
            // 120s of true silence already means a dead stream.
            // TODO(device): tune based on real feel on the Palma.
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val chatRepository: ChatRepository by lazy {
        ChatRepository(database, providerRegistry, securePrefs)
    }

    val providerRegistry: ProviderRegistry by lazy {
        ProviderRegistry(
            listOf(
                OpenRouterProvider(
                    client = httpClient,
                    apiKeyProvider = { securePrefs.apiKey(OpenRouterProvider.ID) },
                ),
                OpenAiCompatProvider(
                    id = "openai",
                    displayName = "OpenAI",
                    fallbackModelId = "gpt-4o",
                    client = httpClient,
                    apiKeyProvider = { securePrefs.apiKey("openai") },
                    baseUrlProvider = { "https://api.openai.com/v1" },
                ),
                AnthropicProvider(
                    client = httpClient,
                    apiKeyProvider = { securePrefs.apiKey(AnthropicProvider.ID) },
                ),
                GeminiProvider(
                    client = httpClient,
                    apiKeyProvider = { securePrefs.apiKey(GeminiProvider.ID) },
                ),
                OpenAiCompatProvider(
                    id = LOCAL_PROVIDER_ID,
                    displayName = "Local (Ollama / llama.cpp)",
                    fallbackModelId = "llama3.2",
                    client = httpClient,
                    apiKeyProvider = { securePrefs.apiKey(LOCAL_PROVIDER_ID) },
                    baseUrlProvider = { securePrefs.localBaseUrl ?: DEFAULT_LOCAL_BASE_URL },
                    requiresKey = false,
                ),
            )
        )
    }

    companion object {
        const val LOCAL_PROVIDER_ID = "local"

        /** Ollama's default OpenAI-compatible endpoint. */
        const val DEFAULT_LOCAL_BASE_URL = "http://127.0.0.1:11434/v1"
    }
}
