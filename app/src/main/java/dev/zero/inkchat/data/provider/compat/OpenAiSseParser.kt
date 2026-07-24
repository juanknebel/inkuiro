package dev.zero.inkchat.data.provider.compat

import dev.zero.inkchat.data.provider.ChatEvent
import dev.zero.inkchat.data.provider.isRecoverableHttp
import dev.zero.inkchat.i18n.Msg

/**
 * Converts an OpenAI-compatible SSE payload (whatever follows "data: ") into
 * [ChatEvent]s. Does not handle "[DONE]": that is stream framing, not JSON.
 *
 * Malformed payloads are ignored (empty list): a broken chunk must not
 * abort a generation that is otherwise going fine.
 */
internal object OpenAiSseParser {

    fun parse(data: String): List<ChatEvent> {
        val chunk = try {
            wireJson.decodeFromString(StreamChunkDto.serializer(), data)
        } catch (_: Exception) {
            return emptyList()
        }

        chunk.error?.let { error ->
            return listOf(
                ChatEvent.Error(
                    message = error.message ?: Msg.providerError,
                    recoverable = isRecoverableHttp(error.codeInt),
                )
            )
        }

        val events = mutableListOf<ChatEvent>()
        for (choice in chunk.choices) {
            val text = choice.delta?.content
            if (!text.isNullOrEmpty()) events += ChatEvent.Delta(text)
        }
        chunk.usage?.let { events += ChatEvent.Usage(it.promptTokens, it.completionTokens) }
        return events
    }
}
