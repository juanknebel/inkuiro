package dev.zero.inkchat.data.provider.anthropic

import dev.zero.inkchat.data.images.ImageStore
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal val anthropicJson = Json { ignoreUnknownKeys = true }

// ---- Request (Messages API) ----

@Serializable
internal data class AnthropicRequestDto(
    val model: String,
    /** Required by the Messages API, unlike OpenAI-compatible ones. */
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<AnthropicMessageDto>,
    /** System prompt is a top-level field, not a message role. */
    val system: String? = null,
    val stream: Boolean,
    val temperature: Float? = null,
)

@Serializable
internal data class AnthropicMessageDto(
    val role: String,
    val content: JsonElement,
)

internal fun anthropicTextContent(text: String): JsonElement = JsonPrimitive(text)

/** Anthropic's recommended order is image block(s) before the accompanying text. */
internal fun anthropicTextAndImageContent(text: String, base64: String, mediaType: String): JsonElement =
    buildJsonArray {
        addJsonObject {
            put("type", JsonPrimitive("image"))
            putJsonObject("source") {
                put("type", JsonPrimitive("base64"))
                put("media_type", JsonPrimitive(mediaType))
                put("data", JsonPrimitive(base64))
            }
        }
        addJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(text))
        }
    }

// ---- Streaming events (payload "type" field drives dispatch) ----

@Serializable
internal data class AnthropicEventDto(
    val type: String? = null,
    val message: AnthropicMessageStartDto? = null,
    val delta: AnthropicDeltaDto? = null,
    val usage: AnthropicUsageDto? = null,
    val error: AnthropicErrorDto? = null,
)

@Serializable
internal data class AnthropicMessageStartDto(
    val usage: AnthropicUsageDto? = null,
)

@Serializable
internal data class AnthropicDeltaDto(
    val type: String? = null,
    val text: String? = null,
)

@Serializable
internal data class AnthropicUsageDto(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
)

// ---- Errors ----

@Serializable
internal data class AnthropicErrorResponseDto(
    val error: AnthropicErrorDto? = null,
)

@Serializable
internal data class AnthropicErrorDto(
    val type: String? = null,
    val message: String? = null,
)

// ---- GET /models ----

@Serializable
internal data class AnthropicModelsDto(
    val data: List<AnthropicModelDto> = emptyList(),
)

@Serializable
internal data class AnthropicModelDto(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
)

internal fun anthropicErrorMessage(providerName: String, code: Int, body: String): String {
    val apiMessage = try {
        anthropicJson.decodeFromString(AnthropicErrorResponseDto.serializer(), body).error?.message
    } catch (_: Exception) {
        null
    }
    val base = Msg.httpError(code, providerName)
    return if (apiMessage.isNullOrBlank()) base else "$base $apiMessage"
}

/**
 * Native client for the Anthropic Messages API (wire-level on purpose: the
 * app's provider abstraction is HTTP+SSE based, shared across providers).
 */
class AnthropicProvider(
    private val client: OkHttpClient,
    private val apiKeyProvider: () -> String?,
    private val baseUrl: String = "https://api.anthropic.com/v1",
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AiProvider {

    override val id: String = ID
    override val displayName: String = "Anthropic (Claude)"
    override val fallbackModelId: String = "claude-opus-4-8"

    @Volatile
    private var modelsCache: Pair<Long, List<ModelInfo>>? = null

    private fun requestBuilder(url: String, key: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("x-api-key", key)
            .header("anthropic-version", API_VERSION)

    override suspend fun listModels(forceRefresh: Boolean): List<ModelInfo> {
        if (!forceRefresh) {
            modelsCache?.let { (fetchedAt, models) ->
                if (nowMs() - fetchedAt < MODELS_TTL_MS) return models
            }
        }
        val key = apiKeyProvider()
        if (key.isNullOrBlank()) throw ProviderException(Msg.noApiKey(displayName), recoverable = false)

        return withContext(Dispatchers.IO) {
            val request = requestBuilder("$baseUrl/models?limit=100", key).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ProviderException(
                        anthropicErrorMessage(displayName, response.code, body),
                        isRecoverableHttp(response.code),
                    )
                }
                anthropicJson.decodeFromString(AnthropicModelsDto.serializer(), body).data
                    .map { ModelInfo(it.id, it.displayName ?: it.id, null) }
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
        val dto = AnthropicRequestDto(
            model = request.modelId,
            maxTokens = request.maxTokens ?: DEFAULT_MAX_TOKENS,
            messages = request.messages
                .filter { it.role != Role.SYSTEM }
                .map { turn ->
                    val content = turn.imagePath?.let { path ->
                        anthropicTextAndImageContent(turn.content, ImageStore.readBase64(path), ImageStore.MIME_TYPE)
                    } ?: anthropicTextContent(turn.content)
                    AnthropicMessageDto(turn.role.wire, content)
                },
            system = system,
            stream = true,
            temperature = request.temperature,
        )
        val httpRequest = requestBuilder("$baseUrl/messages", key)
            .header("Accept", "text/event-stream")
            .post(
                anthropicJson.encodeToString(AnthropicRequestDto.serializer(), dto)
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()
        return chatSseFlow(client, httpRequest, AnthropicSseHandler(displayName))
    }

    companion object {
        const val ID = "anthropic"
        private const val API_VERSION = "2023-06-01"
        private const val DEFAULT_MAX_TOKENS = 4096
        private const val MODELS_TTL_MS = 24L * 60 * 60 * 1000
    }
}

internal class AnthropicSseHandler(private val providerName: String) : ChatSseHandler {

    private var inputTokens: Int? = null
    private var outputTokens: Int? = null

    override fun onData(type: String?, data: String): List<ChatEvent> {
        val event = try {
            anthropicJson.decodeFromString(AnthropicEventDto.serializer(), data)
        } catch (_: Exception) {
            return emptyList()
        }
        return when (event.type) {
            "message_start" -> {
                inputTokens = event.message?.usage?.inputTokens
                emptyList()
            }

            "content_block_delta" -> {
                val text = event.delta?.text
                if (!text.isNullOrEmpty()) listOf(ChatEvent.Delta(text)) else emptyList()
            }

            "message_delta" -> {
                event.usage?.outputTokens?.let { outputTokens = it }
                emptyList()
            }

            "message_stop" -> buildList {
                if (inputTokens != null || outputTokens != null) {
                    add(ChatEvent.Usage(inputTokens ?: 0, outputTokens ?: 0))
                }
                add(ChatEvent.Done)
            }

            "error" -> listOf(
                ChatEvent.Error(
                    event.error?.message ?: Msg.providerError,
                    recoverable = event.error?.type == "overloaded_error" ||
                        event.error?.type == "rate_limit_error",
                )
            )

            // ping, content_block_start/stop and future event types.
            else -> emptyList()
        }
    }

    override fun onClosedWithoutTerminal(): ChatEvent =
        ChatEvent.Error(Msg.streamCut, recoverable = true)

    override fun onHttpError(code: Int, body: String): ChatEvent.Error =
        ChatEvent.Error(anthropicErrorMessage(providerName, code, body), isRecoverableHttp(code))
}
