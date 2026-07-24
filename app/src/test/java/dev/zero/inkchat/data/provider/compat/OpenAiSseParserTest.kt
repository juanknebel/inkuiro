package dev.zero.inkchat.data.provider.compat

import dev.zero.inkchat.data.provider.ChatEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures taken from OpenRouter's real (OpenAI-compatible) format,
 * including extra fields the parser must ignore.
 */
class OpenAiSseParserTest {

    @Test
    fun `chunk with content emits Delta`() {
        val data = """{"id":"gen-1234","provider":"Anthropic","model":"anthropic/claude-sonnet-4-6",""" +
            """"object":"chat.completion.chunk","created":1721000000,""" +
            """"choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},""" +
            """"finish_reason":null,"native_finish_reason":null,"logprobs":null}]}"""

        assertEquals(listOf(ChatEvent.Delta("Hello")), OpenAiSseParser.parse(data))
    }

    @Test
    fun `final chunk with usage and empty delta emits only Usage`() {
        val data = """{"id":"gen-1234","object":"chat.completion.chunk",""" +
            """"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],""" +
            """"usage":{"prompt_tokens":12,"completion_tokens":7,"total_tokens":19}}"""

        assertEquals(listOf(ChatEvent.Usage(12, 7)), OpenAiSseParser.parse(data))
    }

    @Test
    fun `chunk with empty delta content emits nothing`() {
        val data = """{"choices":[{"index":0,"delta":{"content":""},"finish_reason":null}]}"""

        assertTrue(OpenAiSseParser.parse(data).isEmpty())
    }

    @Test
    fun `mid-stream 429 error is recoverable`() {
        val data = """{"error":{"message":"Rate limit exceeded: free tier","code":429},"user_id":"user-1"}"""

        val events = OpenAiSseParser.parse(data)
        assertEquals(1, events.size)
        val error = events.single() as ChatEvent.Error
        assertTrue(error.recoverable)
        assertEquals("Rate limit exceeded: free tier", error.message)
    }

    @Test
    fun `moderation 403 error is not recoverable`() {
        val data = """{"error":{"message":"Content flagged by moderation","code":403,""" +
            """"metadata":{"reasons":["harassment"]}}}"""

        val error = OpenAiSseParser.parse(data).single() as ChatEvent.Error
        assertFalse(error.recoverable)
    }

    @Test
    fun `error with a string code is interpreted the same way`() {
        val data = """{"error":{"message":"Internal error","code":"502"}}"""

        val error = OpenAiSseParser.parse(data).single() as ChatEvent.Error
        assertTrue(error.recoverable)
    }

    @Test
    fun `malformed json is ignored without breaking the stream`() {
        assertTrue(OpenAiSseParser.parse("""{"choices":[{"delta"""").isEmpty())
        assertTrue(OpenAiSseParser.parse("not json at all").isEmpty())
    }

    @Test
    fun `chunk with only finish_reason emits nothing`() {
        val data = """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""

        assertTrue(OpenAiSseParser.parse(data).isEmpty())
    }

    @Test
    fun `line breaks and unicode in content are preserved`() {
        val data = """{"choices":[{"index":0,"delta":{"content":"line 1\n\nline 2 — «déjà vu»"},"finish_reason":null}]}"""

        assertEquals(
            listOf(ChatEvent.Delta("line 1\n\nline 2 — «déjà vu»")),
            OpenAiSseParser.parse(data),
        )
    }
}
