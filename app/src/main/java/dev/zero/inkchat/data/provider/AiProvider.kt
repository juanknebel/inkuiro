package dev.zero.inkchat.data.provider

import dev.zero.inkchat.domain.model.ChatRequest
import dev.zero.inkchat.domain.model.ModelInfo
import kotlinx.coroutines.flow.Flow

interface AiProvider {
    val id: String
    val displayName: String

    /** Sensible default model when the user has not picked one in Settings yet. */
    val fallbackModelId: String

    /**
     * @param forceRefresh skips the cache (e.g. "Test connection" in Settings).
     * @throws ProviderException when there is no key or the API returns an error.
     */
    suspend fun listModels(forceRefresh: Boolean = false): List<ModelInfo>

    /**
     * Verifies the key against an AUTHENTICATED endpoint (some, like
     * OpenRouter's /models, are public and prove nothing).
     * @throws ProviderException when the key is invalid or there is a network/API error.
     */
    suspend fun verifyAuth() {
        listModels(forceRefresh = true)
    }

    /**
     * Response stream. Never throws: every failure arrives as [ChatEvent.Error].
     * The flow completes after emitting [ChatEvent.Done] or a [ChatEvent.Error].
     */
    fun streamChat(request: ChatRequest): Flow<ChatEvent>
}

sealed interface ChatEvent {
    data class Delta(val text: String) : ChatEvent
    data class Usage(val inTokens: Int, val outTokens: Int) : ChatEvent

    /** [recoverable] = offering "Retry" makes sense (rate limit, network, 5xx). */
    data class Error(val message: String, val recoverable: Boolean) : ChatEvent
    data object Done : ChatEvent
}

class ProviderException(message: String, val recoverable: Boolean) : Exception(message)
