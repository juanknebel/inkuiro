package dev.zero.inkchat.ui.chat

import dev.zero.inkchat.data.provider.ChatEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamCoalescerTest {

    @Test
    fun `paragraph break triggers an immediate repaint with the full text`() = runTest {
        val updates = flowOf(
            ChatEvent.Delta("Hello world."),
            ChatEvent.Delta("\n\nSecond paragraph"),
            ChatEvent.Done,
        ).coalesceForEink().toList()

        assertEquals(
            listOf(
                StreamUpdate.Progress("Hello world.\n\nSecond paragraph"),
                StreamUpdate.Finished("Hello world.\n\nSecond paragraph", null, null),
            ),
            updates,
        )
    }

    @Test
    fun `paragraph break split across two deltas is still detected`() = runTest {
        val updates = flowOf(
            ChatEvent.Delta("One\n"),
            ChatEvent.Delta("\nTwo"),
            ChatEvent.Done,
        ).coalesceForEink().toList()

        assertEquals(StreamUpdate.Progress("One\n\nTwo"), updates.first())
    }

    @Test
    fun `without paragraphs, the ticker repaints every 1500ms when there is new content`() = runTest {
        val updates = flow {
            emit(ChatEvent.Delta("Hello"))
            delay(1600)
            emit(ChatEvent.Delta(" world"))
            delay(1600)
            emit(ChatEvent.Usage(5, 3))
            emit(ChatEvent.Done)
        }.coalesceForEink(flushIntervalMs = 1500).toList()

        assertEquals(
            listOf(
                StreamUpdate.Progress("Hello"),
                StreamUpdate.Progress("Hello world"),
                StreamUpdate.Finished("Hello world", 5, 3),
            ),
            updates,
        )
    }

    @Test
    fun `without new content the ticker does not repaint`() = runTest {
        val updates = flow {
            emit(ChatEvent.Delta("Text\n\nfinal"))
            delay(5000) // several ticks without new deltas
            emit(ChatEvent.Done)
        }.coalesceForEink(flushIntervalMs = 1500).toList()

        assertEquals(
            listOf(
                StreamUpdate.Progress("Text\n\nfinal"),
                StreamUpdate.Finished("Text\n\nfinal", null, null),
            ),
            updates,
        )
    }

    @Test
    fun `error preserves the accumulated partial text`() = runTest {
        val updates = flowOf(
            ChatEvent.Delta("Generated part"),
            ChatEvent.Error("boom", recoverable = true),
        ).coalesceForEink().toList()

        assertEquals(
            listOf(StreamUpdate.Failed("Generated part", "boom", recoverable = true)),
            updates,
        )
    }

    @Test
    fun `done without content emits an empty Finished`() = runTest {
        val updates = flowOf(ChatEvent.Done).coalesceForEink().toList()

        assertEquals(listOf(StreamUpdate.Finished("", null, null)), updates)
    }
}
