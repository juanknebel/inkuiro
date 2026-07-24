package dev.zero.inkchat.data.provider

import dev.zero.inkchat.i18n.Msg
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** Errors where a manual retry makes sense, regardless of provider. */
internal fun isRecoverableHttp(code: Int?): Boolean =
    code == 408 || code == 429 || (code != null && code in 500..599)

/**
 * Provider-specific SSE interpretation. The generic streamer handles the
 * connection lifecycle; the handler turns payloads into [ChatEvent]s.
 */
internal interface ChatSseHandler {

    /**
     * Converts one SSE data payload into events. Emitting [ChatEvent.Done] or
     * [ChatEvent.Error] terminates the stream.
     */
    fun onData(type: String?, data: String): List<ChatEvent>

    /** The server closed the stream without a terminal event. */
    fun onClosedWithoutTerminal(): ChatEvent

    fun onHttpError(code: Int, body: String): ChatEvent.Error
}

/**
 * Shared SSE → [ChatEvent] flow. Cancelling collection cancels the underlying
 * EventSource (this is what the "Stop" button relies on). A `finished` flag
 * guards against overlapping terminal callbacks (onEvent/onClosed/onFailure).
 */
internal fun chatSseFlow(
    client: OkHttpClient,
    request: Request,
    handler: ChatSseHandler,
): Flow<ChatEvent> = callbackFlow {
    val finished = AtomicBoolean(false)

    val listener = object : EventSourceListener() {
        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            if (finished.get()) return
            for (event in handler.onData(type, data)) {
                trySendBlocking(event)
                if (event is ChatEvent.Done || event is ChatEvent.Error) {
                    finished.set(true)
                    close()
                    return
                }
            }
        }

        override fun onClosed(eventSource: EventSource) {
            if (finished.compareAndSet(false, true)) {
                trySendBlocking(handler.onClosedWithoutTerminal())
                close()
            }
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            if (!finished.compareAndSet(false, true)) return
            val event = if (response != null && !response.isSuccessful) {
                val body = try {
                    response.body?.string().orEmpty()
                } catch (_: IOException) {
                    ""
                }
                handler.onHttpError(response.code, body)
            } else {
                ChatEvent.Error(t?.message ?: Msg.networkError, recoverable = true)
            }
            trySendBlocking(event)
            close()
        }
    }

    val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
    awaitClose { eventSource.cancel() }
}
