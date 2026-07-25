package dev.zero.inkchat.domain.model

data class ModelInfo(
    val id: String,
    val displayName: String,
    val contextLength: Int?,
)

enum class Role(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
}

data class ChatTurn(
    val role: Role,
    val content: String,
    /** Local file path of an attached image (JPEG), if any. User turns only. */
    val imagePath: String? = null,
)

data class ChatRequest(
    val modelId: String,
    val messages: List<ChatTurn>,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    /** Only honored by providers where [dev.zero.inkchat.data.provider.AiProvider.supportsWebSearch] is true. */
    val webSearch: Boolean = false,
)
