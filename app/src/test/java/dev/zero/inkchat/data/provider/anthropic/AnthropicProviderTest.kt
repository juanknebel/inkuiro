package dev.zero.inkchat.data.provider.anthropic

import dev.zero.inkchat.data.provider.ChatEvent
import dev.zero.inkchat.data.provider.ProviderException
import dev.zero.inkchat.domain.model.ChatRequest
import dev.zero.inkchat.domain.model.ChatTurn
import dev.zero.inkchat.domain.model.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AnthropicProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: AnthropicProvider
    private var apiKey: String? = "test-key"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = AnthropicProvider(
            client = OkHttpClient(),
            apiKeyProvider = { apiKey },
            baseUrl = server.url("/v1").toString().removeSuffix("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // SSE dispatches an event only after a blank line; trimIndent() eats the
    // last one, so we restore it just like the real server sends it.
    private fun sseResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body + "\n\n")

    @Test
    fun `happy stream - deltas, usage from message events and done in order`() = runTest {
        server.enqueue(
            sseResponse(
                """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"model":"claude-opus-4-8","usage":{"input_tokens":25,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" world"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}

                event: message_stop
                data: {"type":"message_stop"}

                """.trimIndent()
            )
        )

        val request = ChatRequest(
            modelId = "claude-opus-4-8",
            messages = listOf(
                ChatTurn(Role.SYSTEM, "Be brief."),
                ChatTurn(Role.USER, "Hi"),
            ),
        )
        val events = provider.streamChat(request).toList()

        val text = events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }
        assertEquals("Hello world", text)
        assertEquals(ChatEvent.Usage(25, 12), events.filterIsInstance<ChatEvent.Usage>().single())
        assertEquals(ChatEvent.Done, events.last())

        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("test-key", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val sentBody = recorded.body.readUtf8()
        // System turn becomes the top-level "system" field, not a message.
        assertTrue(sentBody.contains("\"system\":\"Be brief.\""))
        assertFalse(sentBody.contains("\"role\":\"system\""))
        // max_tokens is required by the Messages API; default kicks in.
        assertTrue(sentBody.contains("\"max_tokens\":4096"))
    }

    @Test
    fun `http 401 emits a single non-recoverable Error with the API message`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        )

        val events = provider.streamChat(
            ChatRequest("claude-opus-4-8", listOf(ChatTurn(Role.USER, "Hi")))
        ).toList()

        val error = events.single() as ChatEvent.Error
        assertFalse(error.recoverable)
        assertTrue(error.message.contains("invalid x-api-key"))
    }

    @Test
    fun `mid-stream overloaded error is recoverable`() = runTest {
        server.enqueue(
            sseResponse(
                """
                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Part"}}

                event: error
                data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

                """.trimIndent()
            )
        )

        val events = provider.streamChat(
            ChatRequest("claude-opus-4-8", listOf(ChatTurn(Role.USER, "Hi")))
        ).toList()

        assertEquals(ChatEvent.Delta("Part"), events.first())
        val error = events.last() as ChatEvent.Error
        assertTrue(error.recoverable)
        assertEquals("Overloaded", error.message)
    }

    @Test
    fun `without api key emits Error without touching the network`() = runTest {
        apiKey = null

        val events = provider.streamChat(
            ChatRequest("claude-opus-4-8", listOf(ChatTurn(Role.USER, "Hi")))
        ).toList()

        val error = events.single() as ChatEvent.Error
        assertFalse(error.recoverable)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `listModels parses the Anthropic models endpoint`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"data":[
                        {"type":"model","id":"claude-opus-4-8","display_name":"Claude Opus 4.8","created_at":"2026-01-01T00:00:00Z"},
                        {"type":"model","id":"claude-haiku-4-5","display_name":"Claude Haiku 4.5","created_at":"2025-10-01T00:00:00Z"}
                    ],"has_more":false}"""
                )
        )

        val models = provider.listModels()

        assertEquals(listOf("claude-opus-4-8", "claude-haiku-4-5"), models.map { it.id })
        assertEquals("Claude Opus 4.8", models.first().displayName)
        assertEquals("test-key", server.takeRequest().getHeader("x-api-key"))
    }

    @Test
    fun `listModels without key throws non-recoverable`() = runTest {
        apiKey = null
        try {
            provider.listModels()
            fail("Should have thrown ProviderException")
        } catch (e: ProviderException) {
            assertFalse(e.recoverable)
        }
        assertEquals(0, server.requestCount)
    }
}
