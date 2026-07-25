package dev.zero.inkchat.data.provider.compat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.putJsonObject

/** Shared Json for the OpenAI-compatible wire format: tolerant to new API fields. */
internal val wireJson = Json { ignoreUnknownKeys = true }

// ---- Request ----

@Serializable
internal data class ChatCompletionRequestDto(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean,
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /** OpenRouter flavor: asks the last stream chunk to include usage. */
    val usage: UsageIncludeDto? = null,
    /** OpenAI flavor: same request, different parameter. */
    @SerialName("stream_options") val streamOptions: StreamOptionsDto? = null,
    /** OpenRouter-only: e.g. [{"id":"web"}] to ground the reply with a live search. */
    val plugins: List<PluginDto>? = null,
)

@Serializable
internal data class PluginDto(val id: String)

@Serializable
internal data class ChatMessageDto(
    val role: String,
    val content: JsonElement,
)

/** Plain text content, sent as a bare string as most OpenAI-compatible servers expect. */
internal fun textContent(text: String): JsonElement = JsonPrimitive(text)

/** Multi-part content: image_url blocks must come alongside the text in a single array. */
internal fun textAndImageContent(text: String, dataUrl: String): JsonElement = buildJsonArray {
    addJsonObject {
        put("type", JsonPrimitive("text"))
        put("text", JsonPrimitive(text))
    }
    addJsonObject {
        put("type", JsonPrimitive("image_url"))
        putJsonObject("image_url") { put("url", JsonPrimitive(dataUrl)) }
    }
}

@Serializable
internal data class UsageIncludeDto(val include: Boolean)

@Serializable
internal data class StreamOptionsDto(@SerialName("include_usage") val includeUsage: Boolean)

// ---- Streaming (OpenAI format: data: {chunk}) ----

@Serializable
internal data class StreamChunkDto(
    val choices: List<StreamChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
    val error: ApiErrorDto? = null,
)

@Serializable
internal data class StreamChoiceDto(
    val delta: DeltaDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class DeltaDto(
    val content: String? = null,
)

@Serializable
internal data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)

// ---- Errors ----

@Serializable
internal data class ErrorResponseDto(
    val error: ApiErrorDto? = null,
)

@Serializable
internal data class ApiErrorDto(
    val message: String? = null,
    /** May arrive as a number or a string depending on the upstream provider. */
    val code: JsonPrimitive? = null,
) {
    val codeInt: Int? get() = code?.content?.toIntOrNull()
}

// ---- GET /models ----

@Serializable
internal data class ModelsResponseDto(
    val data: List<ModelDto> = emptyList(),
)

@Serializable
internal data class ModelDto(
    val id: String,
    val name: String? = null,
    @SerialName("context_length") val contextLength: Int? = null,
)
