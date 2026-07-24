package dev.zero.inkchat.domain

/** Chat configuration the repository needs; SecurePrefs implements it. */
interface ChatSettings {

    /** Provider used for new conversations; null = app default. */
    val activeProviderId: String?

    /** User-chosen default model for a given provider, or null if none picked. */
    fun defaultModelFor(providerId: String): String?

    val systemPrompt: String?
    val temperature: Float?
    val maxTokens: Int?
}
