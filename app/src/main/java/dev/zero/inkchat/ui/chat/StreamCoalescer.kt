package dev.zero.inkchat.ui.chat

import dev.zero.inkchat.data.provider.ChatEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Update ready to paint on screen. Each [Progress] carries the FULL
 * accumulated text: the UI re-renders the whole message (plan §6.3).
 */
sealed interface StreamUpdate {
    data class Progress(val markdown: String) : StreamUpdate
    data class Finished(val markdown: String, val inTokens: Int?, val outTokens: Int?) : StreamUpdate
    data class Failed(val partialMarkdown: String, val errorMessage: String, val recoverable: Boolean) : StreamUpdate
}

/**
 * Coalescing buffer for e-ink (plan §6): accumulates [ChatEvent.Delta]s and
 * emits a repaint when whichever happens first:
 *  - a paragraph break (`\n\n`) appears in the not-yet-painted part, or
 *  - [flushIntervalMs] elapsed and there is new content.
 *
 * Never emits two [StreamUpdate.Progress] with the same text.
 */
fun Flow<ChatEvent>.coalesceForEink(flushIntervalMs: Long = 1500L): Flow<StreamUpdate> = channelFlow {
    val mutex = Mutex()
    val text = StringBuilder()
    var flushedLength = 0
    var terminal = false
    var inTokens: Int? = null
    var outTokens: Int? = null

    suspend fun flushIfDirty() {
        val snapshot = mutex.withLock {
            if (terminal || text.length == flushedLength) null
            else text.toString().also { flushedLength = it.length }
        }
        snapshot?.let { send(StreamUpdate.Progress(it)) }
    }

    val ticker = launch {
        while (isActive) {
            delay(flushIntervalMs)
            flushIfDirty()
        }
    }

    collect { event ->
        when (event) {
            is ChatEvent.Delta -> {
                val paragraphArrived = mutex.withLock {
                    text.append(event.text)
                    // -1 in case the "\n\n" was split right at the edge of the last flush
                    text.indexOf("\n\n", startIndex = maxOf(0, flushedLength - 1)) >= 0
                }
                if (paragraphArrived) flushIfDirty()
            }
            is ChatEvent.Usage -> {
                inTokens = event.inTokens
                outTokens = event.outTokens
            }
            is ChatEvent.Error -> {
                mutex.withLock { terminal = true }
                send(StreamUpdate.Failed(text.toString(), event.message, event.recoverable))
            }
            ChatEvent.Done -> {
                mutex.withLock { terminal = true }
                send(StreamUpdate.Finished(text.toString(), inTokens, outTokens))
            }
        }
    }
    ticker.cancel()
}
