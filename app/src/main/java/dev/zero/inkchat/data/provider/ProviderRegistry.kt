package dev.zero.inkchat.data.provider

class ProviderRegistry(providers: List<AiProvider>) {

    private val byId = providers.associateBy { it.id }

    val all: List<AiProvider> = providers

    fun get(id: String): AiProvider? = byId[id]

    fun require(id: String): AiProvider =
        byId[id] ?: throw IllegalArgumentException("Unknown provider: $id")
}
