package dev.zero.inkchat.i18n

import java.util.Locale

/**
 * Messages for Context-less layers (provider, ViewModel), where string
 * resources cannot reach. PoC: en/es only — Spanish when the system language
 * is Spanish, English fallback. Evaluated on every access so it follows
 * system language changes.
 */
object Msg {

    private val es: Boolean
        get() = Locale.getDefault().language == "es"

    private fun of(en: String, spanish: String): String = if (es) spanish else en

    fun noApiKey(provider: String): String = of(
        "No $provider API key configured. Add it in Settings.",
        "No hay API key de $provider configurada. Agregala en Ajustes.",
    )

    val streamCut
        get() = of(
            "The response was cut off before finishing.",
            "La respuesta se cortó antes de terminar.",
        )

    val networkError get() = of("Network error.", "Error de red.")

    val providerError get() = of("Provider error.", "Error del proveedor.")

    val emptyResponse
        get() = of(
            "The model returned an empty response.",
            "El modelo devolvió una respuesta vacía.",
        )

    val unexpectedError get() = of("Unexpected error.", "Error inesperado.")

    val defaultConversationTitle get() = of("New conversation", "Nueva conversación")

    /** Default titles in any supported language (to detect "not renamed yet"). */
    val defaultTitles = setOf("New conversation", "Nueva conversación")

    fun httpError(code: Int, provider: String): String = when (code) {
        400 -> of("Invalid request (400).", "Solicitud inválida (400).")
        401 -> of("Invalid or revoked API key (401).", "API key inválida o revocada (401).")
        402 -> of("Out of credits (402).", "Sin créditos (402).")
        403 -> of("Access denied (403).", "Acceso denegado (403).")
        404 -> of("Model not found (404).", "Modelo no encontrado (404).")
        408 -> of("Request timed out (408).", "La solicitud expiró (408).")
        429 -> of("Rate limit reached (429).", "Límite de uso alcanzado (429).")
        in 500..599 -> of("$provider server error ($code).", "Error del servidor de $provider ($code).")
        else -> of("HTTP error $code.", "Error HTTP $code.")
    }
}
