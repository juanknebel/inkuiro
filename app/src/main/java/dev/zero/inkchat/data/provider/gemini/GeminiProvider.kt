package dev.zero.inkchat.data.provider.gemini

import dev.zero.inkchat.data.provider.AiProvider
import dev.zero.inkchat.data.provider.ChatEvent
import dev.zero.inkchat.data.provider.ChatSseHandler
import dev.zero.inkchat.data.provider.JSON_MEDIA_TYPE
import dev.zero.inkchat.data.provider.ProviderException
import dev.zero.inkchat.data.provider.chatSseFlow
import dev.zero.inkchat.data.provider.isRecoverableHttp
import dev.zero.inkchat.domain.model.ChatRequest
import dev.zero.inkchat.domain.model.ModelInfo
import dev.zero.inkchat.domain.model.Role
import dev.zero.inkchat.i18n.Msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal val geminiJson = Json { ignoreUnknownKeys = true }

// ---- Request (generateContent) ----

@Serializable
internal data class GeminiRequestDto(
    val contents: List<GeminiContentDto>,
    @SerialName("systemInstruction") val systemInstruction: GeminiContentDto? = null,
    @SerialName("generationConfig") val generationConfig: GeminiGenerationConfigDto? = null,
)

@Serializable
internal data class GeminiContentDto(
    val role: String? = null,
    val parts: List<GeminiPartDto> = emptyList(),
)

@Serializable
internal data class GeminiPartDto(
    val text: String = "",
)

@Serializable
internal data class GeminiGenerationConfigDto(
    val temperature: Float? = null,
    @SerialName("maxOutputTokens") val maxOutputTokens: Int? = null,
)

// ---- Streaming chunks ----

@Serializable
internal data class GeminiChunkDto(
    val candidates: List<GeminiCandidateDto> = emptyList(),
    @SerialName("usageMetadata") val usageMetadata: GeminiUsageDto? = null,
    val error: GeminiErrorDto? = null,
)

@Serializable
internal data class GeminiCandidateDto(
    val content: GeminiContentDto? = null,
    @SerialName("finishReason") val finishReason: String? = null,
)

@Serializable
internal data class GeminiUsageDto(
    @SerialName("promptTokenCount") val promptTokenCount: Int? = null,
    @SerialName("candidatesTokenCount") val candidatesTokenCount: Int? = null,
)

// ---- Errors ----

@Serializable
internal data class GeminiErrorResponseDto(
    val error: GeminiErrorDto? = null,
)

@Serializable
internal data class GeminiErrorDto(
    val code: Int? = null,
    val message: String? = null,
)

// ---- GET /models ----

@Serializable
internal data class GeminiModelsResponseDto(
    val models: List<GeminiModelDto> = emptyList(),
)

@Serializable
internal data class GeminiModelDto(
    val name: String,
    @SerialName("displayName") val displayName: String? = null,
    @SerialName("inputTokenLimit") val inputTokenLimit: Int? = null,
    @SerialName("supportedGenerationMethods") val supportedGenerationMethods: List<String> = emptyList(),
)

internal fun geminiErrorMessage(providerName: String, code: Int, body: String): String {
    val apiMessage = try {
        geminiJson.decodeFromString(GeminiErrorResponseDto.serializer(), body).error?.message
    } catch (_: Exception) {
        null
    }
    val base = Msg.httpError(code, providerName)
    return if (apiMessage.isNullOrBlank()) base else "$base $apiMessage"
}

/**
 * Native client for the Gemini API (generativelanguage.googleapis.com).
 * Streaming uses :streamGenerateContent?alt=sse; there is no [DONE] marker —
 * the final chunk carries finishReason, and that is when Done is emitted.
 */
class GeminiProvider(
    private val client: OkHttpClient,
    private val apiKeyProvider: () -> String?,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AiProvider {

    override val id: String = ID
    override val displayName: String = "Google Gemini"
    override val fallbackModelId: String = "gemini-2.5-flash"

    @Volatile
    private var modelsCache: Pair<Long, List<ModelInfo>>? = null

    private fun requestBuilder(url: String, key: String): Request.Builder =
        Request.Builder().url(url).header("x-goog-api-key", key)

    override suspend fun listModels(forceRefresh: Boolean): List<ModelInfo> {
        if (!forceRefresh) {
            modelsCache?.let { (fetchedAt, models) ->
                if (nowMs() - fetchedAt < MODELS_TTL_MS) return models
            }
        }
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) throw ProviderException(Msg.noApiKey(displayName), recoverable = false)

        return withContext(Dispatchers.IO) {
            val request = requestBuilder("$baseUrl/models?pageSize=200", key).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ProviderException(
                        geminiErrorMessage(displayName, response.code, body),
                        isRecoverableHttp(response.code),
                    )
                }
                geminiJson.decodeFromString(GeminiModelsResponseDto.serializer(), body).models
                    .filter { "generateContent" in it.supportedGenerationMethods }
                    .map {
                        val modelId = it.name.removePrefix("models/")
                        ModelInfo(modelId, it.displayName ?: modelId, it.inputTokenLimit)
                    }
                    .sortedBy { it.displayName.lowercase() }
                    .also { modelsCache = nowMs() to it }
            }
        }
    }

    override fun streamChat(request: ChatRequest): Flow<ChatEvent> {
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) {
            return flowOf(ChatEvent.Error(Msg.noApiKey(displayName), recoverable = false))
        }
        val system = request.messages
            .filter { it.role == Role.SYSTEM }
            .joinToString("\n\n") { it.content }
            .ifBlank { null }
        val dto = GeminiRequestDto(
            contents = request.messages
                .filter { it.role != Role.SYSTEM }
                .map {
                    GeminiContentDto(
                        role = if (it.role == Role.ASSISTANT) "model" else "user",
                        parts = listOf(GeminiPartDto(it.content)),
                    )
                },
            systemInstruction = system?.let { GeminiContentDto(parts = listOf(GeminiPartDto(it))) },
            generationConfig = if (request.temperature != null || request.maxTokens != null) {
                GeminiGenerationConfigDto(
                    temperature = request.temperature,
                    maxOutputTokens = request.maxTokens,
                )
            } else null,
        )
        val httpRequest = requestBuilder(
            "$baseUrl/models/${request.modelId}:streamGenerateContent?alt=sse", key,
        )
            .header("Accept", "text/event-stream")
            .post(
                geminiJson.encodeToString(GeminiRequestDto.serializer(), dto)
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()
        return chatSseFlow(client, httpRequest, GeminiSseHandler(displayName))
    }

    companion object {
        const val ID = "gemini"
        private const val MODELS_TTL_MS = 24L * 60 * 60 * 1000
    }
}

internal class GeminiSseHandler(private val providerName: String) : ChatSseHandler {

    private var usage: ChatEvent.Usage? = null

    override fun onData(type: String?, data: String): List<ChatEvent> {
        val chunk = try {
            geminiJson.decodeFromString(GeminiChunkDto.serializer(), data)
        } catch (_: Exception) {
            return emptyList()
        }

        chunk.error?.let { error ->
            return listOf(
                ChatEvent.Error(
                    error.message ?: Msg.providerError,
                    recoverable = isRecoverableHttp(error.code),
                )
            )
        }

        val events = mutableListOf<ChatEvent>()
        var finished = false
        for (candidate in chunk.candidates) {
            candidate.content?.parts?.forEach { part ->
                if (part.text.isNotEmpty()) events += ChatEvent.Delta(part.text)
            }
            if (candidate.finishReason != null) finished = true
        }
        chunk.usageMetadata?.let {
            usage = ChatEvent.Usage(it.promptTokenCount ?: 0, it.candidatesTokenCount ?: 0)
        }
        if (finished) {
            usage?.let { events += it }
            events += ChatEvent.Done
        }
        return events
    }

    override fun onClosedWithoutTerminal(): ChatEvent =
        ChatEvent.Error(Msg.streamCut, recoverable = true)

    override fun onHttpError(code: Int, body: String): ChatEvent.Error =
        ChatEvent.Error(geminiErrorMessage(providerName, code, body), isRecoverableHttp(code))
}
