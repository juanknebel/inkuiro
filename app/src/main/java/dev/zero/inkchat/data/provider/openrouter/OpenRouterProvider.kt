package dev.zero.inkchat.data.provider.openrouter

import dev.zero.inkchat.data.provider.ProviderException
import dev.zero.inkchat.data.provider.compat.OpenAiCompatProvider
import dev.zero.inkchat.data.provider.compat.httpErrorMessage
import dev.zero.inkchat.data.provider.isRecoverableHttp
import dev.zero.inkchat.i18n.Msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OpenRouter speaks the OpenAI-compatible wire format, so almost everything
 * lives in [OpenAiCompatProvider]. What is OpenRouter-specific: the usage
 * flavor and the authenticated GET /key endpoint for verifying credentials
 * (its /models endpoint is public and proves nothing).
 */
class OpenRouterProvider(
    client: OkHttpClient,
    apiKeyProvider: () -> String?,
    baseUrl: String = "https://openrouter.ai/api/v1",
    nowMs: () -> Long = System::currentTimeMillis,
) : OpenAiCompatProvider(
    id = ID,
    displayName = "OpenRouter",
    fallbackModelId = "openrouter/auto",
    client = client,
    apiKeyProvider = apiKeyProvider,
    baseUrlProvider = { baseUrl },
    requiresKey = true,
    usageFlavor = UsageFlavor.OPENROUTER,
    supportsWebSearch = true,
    nowMs = nowMs,
) {

    /** GET /key is authenticated: 200 = the key actually works. */
    override suspend fun verifyAuth() {
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) throw ProviderException(Msg.noApiKey(displayName), recoverable = false)

        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/key")
                .header("Authorization", "Bearer $key")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    throw ProviderException(
                        httpErrorMessage(displayName, response.code, body),
                        isRecoverableHttp(response.code),
                    )
                }
            }
        }
    }

    companion object {
        const val ID = "openrouter"
    }
}
