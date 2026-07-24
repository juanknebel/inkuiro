package dev.zero.inkchat.domain

import dev.zero.inkchat.data.db.AppDatabase
import dev.zero.inkchat.data.db.ConversationEntity
import dev.zero.inkchat.data.db.MessageEntity
import dev.zero.inkchat.data.provider.ChatEvent
import dev.zero.inkchat.data.provider.ProviderRegistry
import dev.zero.inkchat.data.provider.openrouter.OpenRouterProvider
import dev.zero.inkchat.domain.model.ChatRequest
import dev.zero.inkchat.domain.model.ChatTurn
import dev.zero.inkchat.domain.model.Role
import dev.zero.inkchat.i18n.Msg
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class ChatRepository(
    private val db: AppDatabase,
    private val registry: ProviderRegistry,
    private val settings: ChatSettings,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        db.messageDao().observeForConversation(conversationId)

    suspend fun listMessages(conversationId: String): List<MessageEntity> =
        db.messageDao().listForConversation(conversationId)

    suspend fun getConversation(id: String): ConversationEntity? =
        db.conversationDao().getById(id)

    suspend fun createConversation(): ConversationEntity {
        val providerId = settings.activeProviderId ?: OpenRouterProvider.ID
        val provider = registry.require(providerId)
        val now = nowMs()
        val conversation = ConversationEntity(
            id = UUID.randomUUID().toString(),
            title = Msg.defaultConversationTitle,
            providerId = providerId,
            modelId = settings.defaultModelFor(providerId) ?: provider.fallbackModelId,
            createdAt = now,
            updatedAt = now,
        )
        db.conversationDao().upsert(conversation)
        return conversation
    }

    /**
     * Persists the user message. If the conversation still has the default
     * title, it is auto-generated from this first message.
     */
    suspend fun appendUserMessage(conversation: ConversationEntity, text: String): MessageEntity {
        val now = nowMs()
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversation.id,
            role = Role.USER.wire,
            content = text,
            modelId = null,
            tokensIn = null,
            tokensOut = null,
            createdAt = now,
        )
        db.messageDao().upsert(message)
        // The default title may have been created in any supported language.
        if (conversation.title in Msg.defaultTitles) {
            db.conversationDao().rename(conversation.id, autoTitle(text), now)
        } else {
            db.conversationDao().touch(conversation.id, now)
        }
        return message
    }

    suspend fun saveAssistantMessage(
        conversationId: String,
        content: String,
        modelId: String,
        tokensIn: Int?,
        tokensOut: Int?,
    ): MessageEntity {
        val now = nowMs()
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = Role.ASSISTANT.wire,
            content = content,
            modelId = modelId,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            createdAt = now,
        )
        db.messageDao().upsert(message)
        db.conversationDao().touch(conversationId, now)
        return message
    }

    /** Builds the request (global system prompt + history) and opens the stream. */
    fun streamReply(conversation: ConversationEntity, history: List<MessageEntity>): Flow<ChatEvent> {
        val provider = registry.require(conversation.providerId)
        val turns = buildList {
            settings.systemPrompt?.takeIf { it.isNotBlank() }?.let { add(ChatTurn(Role.SYSTEM, it)) }
            history.forEach { message ->
                roleOf(message.role)?.let { add(ChatTurn(it, message.content)) }
            }
        }
        return provider.streamChat(
            ChatRequest(
                modelId = conversation.modelId,
                messages = turns,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
            )
        )
    }

    private fun roleOf(wire: String): Role? = Role.entries.firstOrNull { it.wire == wire }

    companion object {
        fun autoTitle(text: String): String =
            text.trim().replace(Regex("\\s+"), " ").take(60)
    }
}
