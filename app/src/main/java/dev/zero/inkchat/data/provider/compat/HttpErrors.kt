package dev.zero.inkchat.data.provider.compat

import dev.zero.inkchat.i18n.Msg

/**
 * Human-readable message for an HTTP error from an OpenAI-compatible API.
 * [body] is the response body, usually {"error":{"message":...,"code":...}}.
 */
internal fun httpErrorMessage(providerName: String, code: Int, body: String): String {
    val apiMessage = try {
        wireJson.decodeFromString(ErrorResponseDto.serializer(), body).error?.message
    } catch (_: Exception) {
        null
    }
    val base = Msg.httpError(code, providerName)
    return if (apiMessage.isNullOrBlank()) base else "$base $apiMessage"
}
