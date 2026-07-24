package dev.zero.inkchat.domain

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.zero.inkchat.data.db.AppDatabase
import dev.zero.inkchat.data.provider.AiProvider
import dev.zero.inkchat.data.provider.ChatEvent
import dev.zero.inkchat.data.provider.ProviderRegistry
import dev.zero.inkchat.domain.model.ChatRequest
import dev.zero.inkchat.domain.model.ModelInfo
import dev.zero.inkchat.domain.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatRepositoryTest {

    private class FakeProvider : AiProvider {
        override val id = "openrouter"
        override val displayName = "Fake"
        override val fallbackModelId = "fallback/model"
        var events: List<ChatEvent> = emptyList()
        var lastRequest: ChatRequest? = null

        override suspend fun listModels(forceRefresh: Boolean): List<ModelInfo> = emptyList()

        override fun streamChat(request: ChatRequest): Flow<ChatEvent> {
            lastRequest = request
            return events.asFlow()
        }
    }

    private class FakeSettings : ChatSettings {
        override var activeProviderId: String? = null
        var defaultModel: String? = null
        override fun defaultModelFor(providerId: String): String? = defaultModel
        override var systemPrompt: String? = null
        override var temperature: Float? = null
        override var maxTokens: Int? = null
    }

    private lateinit var db: AppDatabase
    private lateinit var provider: FakeProvider
    private lateinit var settings: FakeSettings
    private lateinit var repository: ChatRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        provider = FakeProvider()
        settings = FakeSettings()
        repository = ChatRepository(db, ProviderRegistry(listOf(provider)), settings) { now }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `createConversation uses the default model from settings or the provider fallback`() = runTest {
        settings.defaultModel = "anthropic/claude-sonnet-4-6"
        assertEquals("anthropic/claude-sonnet-4-6", repository.createConversation().modelId)

        settings.defaultModel = null
        assertEquals("fallback/model", repository.createConversation().modelId)
    }

    @Test
    fun `createConversation uses the active provider from settings`() = runTest {
        settings.activeProviderId = null
        assertEquals("openrouter", repository.createConversation().providerId)
    }

    @Test
    fun `the first message auto-generates the title, the second does not overwrite it`() = runTest {
        val conversation = repository.createConversation()

        repository.appendUserMessage(conversation, "  What   is\ne-ink exactly? ")
        assertEquals(
            "What is e-ink exactly?",
            repository.getConversation(conversation.id)!!.title,
        )

        val renamed = repository.getConversation(conversation.id)!!
        now += 100
        repository.appendUserMessage(renamed, "Another message")
        val after = repository.getConversation(conversation.id)!!
        assertEquals("What is e-ink exactly?", after.title)
        assertEquals(now, after.updatedAt)
    }

    @Test
    fun `the auto-generated title is truncated to 60 characters`() {
        val long = "a".repeat(200)
        assertEquals(60, ChatRepository.autoTitle(long).length)
    }

    @Test
    fun `streamReply builds system prompt, history and parameters`() = runTest {
        settings.systemPrompt = "Be brief."
        settings.temperature = 0.4f
        settings.maxTokens = 2048

        val conversation = repository.createConversation()
        repository.appendUserMessage(conversation, "Hi")
        now += 10
        repository.saveAssistantMessage(conversation.id, "Hello!", conversation.modelId, 3, 2)
        now += 10
        repository.appendUserMessage(
            repository.getConversation(conversation.id)!!, "Tell me more",
        )

        repository.streamReply(conversation, repository.listMessages(conversation.id)).collect()

        val request = provider.lastRequest!!
        assertEquals(conversation.modelId, request.modelId)
        assertEquals(0.4f, request.temperature)
        assertEquals(2048, request.maxTokens)
        assertEquals(
            listOf(
                Role.SYSTEM to "Be brief.",
                Role.USER to "Hi",
                Role.ASSISTANT to "Hello!",
                Role.USER to "Tell me more",
            ),
            request.messages.map { it.role to it.content },
        )
    }

    @Test
    fun `without a system prompt no system turn is added`() = runTest {
        val conversation = repository.createConversation()
        repository.appendUserMessage(conversation, "Hi")

        repository.streamReply(conversation, repository.listMessages(conversation.id)).collect()

        assertEquals(listOf(Role.USER), provider.lastRequest!!.messages.map { it.role })
    }

    @Test
    fun `saveAssistantMessage persists content, model and tokens`() = runTest {
        val conversation = repository.createConversation()

        repository.saveAssistantMessage(conversation.id, "Reply", "x/y", 10, 20)

        val message = repository.listMessages(conversation.id).single()
        assertEquals("Reply", message.content)
        assertEquals(Role.ASSISTANT.wire, message.role)
        assertEquals("x/y", message.modelId)
        assertEquals(10, message.tokensIn)
        assertEquals(20, message.tokensOut)
        assertNull(repository.listMessages(conversation.id).single().tokensIn?.takeIf { it != 10 })
    }
}
