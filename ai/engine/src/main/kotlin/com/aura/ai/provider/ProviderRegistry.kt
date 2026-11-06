package com.aura.ai.provider

/** Providers that are NOT implemented yet. Listed for transparency only: they are never selectable. */
data class PlannedProvider(val id: String, val displayName: String, val note: String)

class ProviderRegistry(private val providers: List<AIProvider>) {
    init {
        require(providers.map { it.id }.toSet().size == providers.size) { "duplicate provider ids" }
    }

    fun all(): List<AIProvider> = providers
    fun get(id: String): AIProvider? = providers.firstOrNull { it.id == id }
    fun require(id: String): AIProvider = get(id) ?: error("Unknown provider: $id")

    /** Honest roadmap: extensions to build, none of them pretends to work today. */
    fun planned(): List<PlannedProvider> = PLANNED.filter { p -> providers.none { it.id == p.id } }

    companion object {
        val PLANNED = listOf(
            PlannedProvider("qwen", "Qwen (Alibaba)", "API compatible OpenAI : ajout par un adaptateur léger."),
            PlannedProvider("deepseek", "DeepSeek", "API compatible OpenAI : ajout par un adaptateur léger."),
            PlannedProvider("kimi", "Kimi (Moonshot)", "API compatible OpenAI : ajout par un adaptateur léger."),
            PlannedProvider("minimax", "MiniMax", "À vérifier : dialecte et authentification propres."),
            PlannedProvider("mistral", "Mistral", "API compatible OpenAI : ajout par un adaptateur léger."),
            PlannedProvider("openai", "OpenAI", "API de référence du dialecte : ajout trivial."),
            PlannedProvider("anthropic", "Claude (Anthropic)", "Dialecte différent (Messages API) : adaptateur dédié."),
            PlannedProvider("gemini", "Gemini (Google)", "Dialecte différent : adaptateur dédié."),
            PlannedProvider("xai", "Grok (xAI)", "API compatible OpenAI : ajout par un adaptateur léger."),
            PlannedProvider("perplexity", "Perplexity", "API compatible OpenAI : ajout par un adaptateur léger."),
            PlannedProvider("custom", "Fournisseur personnalisé", "Toute API compatible OpenAI via une URL HTTPS saisie par l'utilisateur."),
        )
    }
}
