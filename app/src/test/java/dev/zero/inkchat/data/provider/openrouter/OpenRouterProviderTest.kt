package dev.zero.inkchat.data.provider.openrouter

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

class OpenRouterProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: OpenRouterProvider
    private var apiKey: String? = "test-key"
    private var now: Long = 1_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenRouterProvider(
            client = OkHttpClient(),
            apiKeyProvider = { apiKey },
            baseUrl = server.url("/api/v1").toString().removeSuffix("/"),
            nowMs = { now },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun request() = ChatRequest(
        modelId = "anthropic/claude-sonnet-4-6",
        messages = listOf(ChatTurn(Role.USER, "Hi")),
    )

    // SSE dispatches an event only after a blank line; trimIndent() eats the
    // last one, so we restore it just like the real server sends it.
    private fun sseResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body + "\n\n")

    // ---- streamChat ----

    @Test
    fun `happy stream - deltas, usage and done in order`() = runTest {
        server.enqueue(
            sseResponse(
                """
                : OPENROUTER PROCESSING

                data: {"id":"gen-1","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

                data: {"id":"gen-1","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}

                data: {"id":"gen-1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":7,"total_tokens":19}}

                data: [DONE]

                """.trimIndent()
            )
        )

        val events = provider.streamChat(request()).toList()

        val text = events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }
        assertEquals("Hello world", text)
        assertEquals(ChatEvent.Usage(12, 7), events.filterIsInstance<ChatEvent.Usage>().single())
        assertEquals(ChatEvent.Done, events.last())

        val recorded = server.takeRequest()
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        val sentBody = recorded.body.readUtf8()
        assertTrue(sentBody.contains("\"stream\":true"))
        assertTrue(sentBody.contains("\"model\":\"anthropic/claude-sonnet-4-6\""))
    }

    @Test
    fun `http 401 emits a single non-recoverable Error with the API message`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Invalid API key","code":401}}""")
        )

        val events = provider.streamChat(request()).toList()

        val error = events.single() as ChatEvent.Error
        assertFalse(error.recoverable)
        assertTrue(error.message.contains("401"))
        assertTrue(error.message.contains("Invalid API key"))
    }

    @Test
    fun `http 429 emits a recoverable Error`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Rate limited","code":429}}""")
        )

        val error = provider.streamChat(request()).toList().single() as ChatEvent.Error
        assertTrue(error.recoverable)
    }

    @Test
    fun `stream cut without DONE emits a recoverable Error after the deltas`() = runTest {
        server.enqueue(
            sseResponse(
                """
                data: {"id":"gen-1","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}

                """.trimIndent()
            )
        )

        val events = provider.streamChat(request()).toList()

        assertEquals(ChatEvent.Delta("Hello"), events.first())
        val error = events.last() as ChatEvent.Error
        assertTrue(error.recoverable)
    }

    @Test
    fun `mid-stream API error cuts the generation`() = runTest {
        server.enqueue(
            sseResponse(
                """
                data: {"id":"gen-1","choices":[{"index":0,"delta":{"content":"Part"},"finish_reason":null}]}

                data: {"error":{"message":"Provider unavailable","code":502}}

                """.trimIndent()
            )
        )

        val events = provider.streamChat(request()).toList()

        assertEquals(ChatEvent.Delta("Part"), events.first())
        val error = events.last() as ChatEvent.Error
        assertTrue(error.recoverable)
        assertEquals("Provider unavailable", error.message)
    }

    @Test
    fun `without api key emits a non-recoverable Error without touching the network`() = runTest {
        apiKey = null

        val events = provider.streamChat(request()).toList()

        val error = events.single() as ChatEvent.Error
        assertFalse(error.recoverable)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `webSearch true adds the web plugin to the request body`() = runTest {
        server.enqueue(sseResponse("data: [DONE]"))

        provider.streamChat(request().copy(webSearch = true)).toList()

        val sentBody = server.takeRequest().body.readUtf8()
        assertTrue(sentBody.contains("\"plugins\":[{\"id\":\"web\"}]"))
    }

    @Test
    fun `webSearch false omits the plugins field`() = runTest {
        server.enqueue(sseResponse("data: [DONE]"))

        provider.streamChat(request()).toList()

        val sentBody = server.takeRequest().body.readUtf8()
        assertFalse(sentBody.contains("plugins"))
    }

    // ---- verifyAuth ----

    @Test
    fun `verifyAuth with 200 passes and sends the auth header`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":{"label":"sk-or-v1-…0000","usage":0.42}}""")
        )

        provider.verifyAuth()

        val recorded = server.takeRequest()
        assertEquals("/api/v1/key", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
    }

    @Test
    fun `verifyAuth with 401 throws non-recoverable with the API message`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"User not found.","code":401}}""")
        )

        try {
            provider.verifyAuth()
            fail("Should have thrown ProviderException")
        } catch (e: ProviderException) {
            assertFalse(e.recoverable)
            assertTrue(e.message!!.contains("User not found."))
        }
    }

    @Test
    fun `verifyAuth without key throws without touching the network`() = runTest {
        apiKey = null
        try {
            provider.verifyAuth()
            fail("Should have thrown ProviderException")
        } catch (e: ProviderException) {
            assertFalse(e.recoverable)
        }
        assertEquals(0, server.requestCount)
    }

    // ---- listModels ----

    @Test
    fun `listModels parses, sorts by name and caches for 24h`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"data":[
                        {"id":"openai/gpt-4o","name":"GPT-4o","context_length":128000,"pricing":{"prompt":"0.0000025"}},
                        {"id":"anthropic/claude-sonnet-4-6","name":"Claude Sonnet 4.6","context_length":200000}
                    ]}"""
                )
        )

        val models = provider.listModels()

        assertEquals(listOf("Claude Sonnet 4.6", "GPT-4o"), models.map { it.displayName })
        assertEquals(200000, models.first().contextLength)

        // Second call within the TTL: does not touch the network.
        now += 60 * 60 * 1000
        provider.listModels()
        assertEquals(1, server.requestCount)

        // Once the TTL expires, it fetches again.
        now += 24L * 60 * 60 * 1000
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"id":"x/y","name":"Y"}]}""")
        )
        assertEquals(listOf("Y"), provider.listModels().map { it.displayName })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `listModels with forceRefresh skips the cache`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"id":"a/one","name":"One"}]}""")
        )
        provider.listModels()

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"id":"b/two","name":"Two"}]}""")
        )
        val refreshed = provider.listModels(forceRefresh = true)

        assertEquals(listOf("Two"), refreshed.map { it.displayName })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `listModels without key throws a non-recoverable ProviderException`() = runTest {
        apiKey = null
        try {
            provider.listModels()
            fail("Should have thrown ProviderException")
        } catch (e: ProviderException) {
            assertFalse(e.recoverable)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `listModels with 500 throws a recoverable ProviderException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        try {
            provider.listModels()
            fail("Should have thrown ProviderException")
        } catch (e: ProviderException) {
            assertTrue(e.recoverable)
        }
    }
}
