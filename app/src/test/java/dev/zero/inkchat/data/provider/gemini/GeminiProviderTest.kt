package dev.zero.inkchat.data.provider.gemini

import dev.zero.inkchat.data.provider.ChatEvent
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
import org.junit.Before
import org.junit.Test

class GeminiProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: GeminiProvider
    private var apiKey: String? = "test-key"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = GeminiProvider(
            client = OkHttpClient(),
            apiKeyProvider = { apiKey },
            baseUrl = server.url("/v1beta").toString().removeSuffix("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sseResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body + "\n\n")

    @Test
    fun `happy stream - deltas, usage and done when finishReason arrives`() = runTest {
        server.enqueue(
            sseResponse(
                """
                data: {"candidates":[{"content":{"role":"model","parts":[{"text":"Hello"}]},"index":0}]}

                data: {"candidates":[{"content":{"role":"model","parts":[{"text":" world"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":5,"totalTokenCount":13}}

                """.trimIndent()
            )
        )

        val request = ChatRequest(
            modelId = "gemini-2.5-flash",
            messages = listOf(
                ChatTurn(Role.SYSTEM, "Be brief."),
                ChatTurn(Role.USER, "Hi"),
                ChatTurn(Role.ASSISTANT, "Hello!"),
                ChatTurn(Role.USER, "Again"),
            ),
        )
        val events = provider.streamChat(request).toList()

        val text = events.filterIsInstance<ChatEvent.Delta>().joinToString("") { it.text }
        assertEquals("Hello world", text)
        assertEquals(ChatEvent.Usage(8, 5), events.filterIsInstance<ChatEvent.Usage>().single())
        assertEquals(ChatEvent.Done, events.last())

        val recorded = server.takeRequest()
        assertEquals(
            "/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            recorded.path,
        )
        assertEquals("test-key", recorded.getHeader("x-goog-api-key"))
        val sentBody = recorded.body.readUtf8()
        // System turn becomes systemInstruction; assistant maps to role "model".
        assertTrue(sentBody.contains("\"systemInstruction\""))
        assertTrue(sentBody.contains("\"role\":\"model\""))
        assertFalse(sentBody.contains("\"role\":\"assistant\""))
    }

    @Test
    fun `http 400 with invalid key emits a non-recoverable Error`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""")
        )

        val events = provider.streamChat(
            ChatRequest("gemini-2.5-flash", listOf(ChatTurn(Role.USER, "Hi")))
        ).toList()

        val error = events.single() as ChatEvent.Error
        assertFalse(error.recoverable)
        assertTrue(error.message.contains("API key not valid"))
    }

    @Test
    fun `stream cut without finishReason emits a recoverable Error`() = runTest {
        server.enqueue(
            sseResponse(
                """
                data: {"candidates":[{"content":{"role":"model","parts":[{"text":"Part"}]},"index":0}]}

                """.trimIndent()
            )
        )

        val events = provider.streamChat(
            ChatRequest("gemini-2.5-flash", listOf(ChatTurn(Role.USER, "Hi")))
        ).toList()

        assertEquals(ChatEvent.Delta("Part"), events.first())
        val error = events.last() as ChatEvent.Error
        assertTrue(error.recoverable)
    }

    @Test
    fun `listModels filters to generateContent models and strips the prefix`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"models":[
                        {"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash","inputTokenLimit":1048576,"supportedGenerationMethods":["generateContent","countTokens"]},
                        {"name":"models/embedding-001","displayName":"Embedding 001","supportedGenerationMethods":["embedContent"]}
                    ]}"""
                )
        )

        val models = provider.listModels()

        assertEquals(listOf("gemini-2.5-flash"), models.map { it.id })
        assertEquals(1048576, models.single().contextLength)
        assertEquals("test-key", server.takeRequest().getHeader("x-goog-api-key"))
    }
}
