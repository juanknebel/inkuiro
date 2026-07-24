package dev.zero.inkchat.data.provider.compat

import dev.zero.inkchat.data.provider.AiProvider
import dev.zero.inkchat.data.provider.ChatEvent
import dev.zero.inkchat.data.provider.ChatSseHandler
import dev.zero.inkchat.data.provider.JSON_MEDIA_TYPE
import dev.zero.inkchat.data.provider.ProviderException
import dev.zero.inkchat.data.provider.chatSseFlow
import dev.zero.inkchat.data.provider.isRecoverableHttp
import dev.zero.inkchat.domain.model.ChatRequest
import dev.zero.inkchat.domain.model.ModelInfo
import dev.zero.inkchat.i18n.Msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Provider for any OpenAI-compatible chat API: OpenAI itself, local servers
 * (Ollama, llama.cpp) and — via subclass — OpenRouter. Differences are
 * parameterized: base URL (may be dynamic, e.g. from Settings), whether a key
 * is required, and which flavor of usage reporting the API understands.
 */
open class OpenAiCompatProvider(
    final override val id: String,
    final override val displayName: String,
    final override val fallbackModelId: String,
    protected val client: OkHttpClient,
    protected val apiKeyProvider: () -> String?,
    private val baseUrlProvider: () -> String,
    private val requiresKey: Boolean = true,
    private val usageFlavor: UsageFlavor = UsageFlavor.STREAM_OPTIONS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AiProvider {

    enum class UsageFlavor { OPENROUTER, STREAM_OPTIONS }

    protected val baseUrl: String get() = baseUrlProvider().trimEnd('/')

    @Volatile
    private var modelsCache: Pair<Long, List<ModelInfo>>? = null

    private fun missingKey(): Boolean = requiresKey && apiKeyProvider().isNullOrBlank()

    /** Auth header only when a key exists (local servers usually have none). */
    private fun requestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        apiKeyProvider()?.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
        }
        return builder
    }

    override suspend fun listModels(forceRefresh: Boolean): List<ModelInfo> {
        if (!forceRefresh) {
            modelsCache?.let { (fetchedAt, models) ->
                if (nowMs() - fetchedAt < MODELS_TTL_MS) return models
            }
        }
        if (missingKey()) throw ProviderException(Msg.noApiKey(displayName), recoverable = false)

        return withContext(Dispatchers.IO) {
            val request = requestBuilder("$baseUrl/models").build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ProviderException(
                        httpErrorMessage(displayName, response.code, body),
                        isRecoverableHttp(response.code),
                    )
                }
                wireJson.decodeFromString(ModelsResponseDto.serializer(), body).data
                    .map { ModelInfo(it.id, it.name ?: it.id, it.contextLength) }
                    .sortedBy { it.displayName.lowercase() }
                    .also { modelsCache = nowMs() to it }
            }
        }
    }

    override fun streamChat(request: ChatRequest): Flow<ChatEvent> {
        if (missingKey()) {
            return flowOf(ChatEvent.Error(Msg.noApiKey(displayName), recoverable = false))
        }
        val dto = ChatCompletionRequestDto(
            model = request.modelId,
            messages = request.messages.map { ChatMessageDto(it.role.wire, it.content) },
            stream = true,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            usage = if (usageFlavor == UsageFlavor.OPENROUTER) UsageIncludeDto(true) else null,
            streamOptions = if (usageFlavor == UsageFlavor.STREAM_OPTIONS) {
                StreamOptionsDto(includeUsage = true)
            } else null,
        )
        val httpRequest = requestBuilder("$baseUrl/chat/completions")
            .header("Accept", "text/event-stream")
            .post(
                wireJson.encodeToString(ChatCompletionRequestDto.serializer(), dto)
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()
        return chatSseFlow(client, httpRequest, OpenAiCompatSseHandler(displayName))
    }

    companion object {
        private const val MODELS_TTL_MS = 24L * 60 * 60 * 1000
    }
}

internal class OpenAiCompatSseHandler(private val providerName: String) : ChatSseHandler {

    override fun onData(type: String?, data: String): List<ChatEvent> =
        if (data == "[DONE]") listOf(ChatEvent.Done) else OpenAiSseParser.parse(data)

    override fun onClosedWithoutTerminal(): ChatEvent =
        ChatEvent.Error(Msg.streamCut, recoverable = true)

    override fun onHttpError(code: Int, body: String): ChatEvent.Error =
        ChatEvent.Error(httpErrorMessage(providerName, code, body), isRecoverableHttp(code))
}
