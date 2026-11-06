package com.aura.ai.settings

import com.aura.ai.provider.AIProvider
import com.aura.ai.provider.EndpointPolicy
import com.aura.ai.provider.ProviderConfig
import com.aura.ai.provider.ProviderException
import com.aura.ai.provider.ProviderRegistry

/**
 * Which model backend the owner chose and with which credentials.
 *
 *  - `selectedProviderId == null` means LOCAL (on-device model); otherwise Cloud BYOK with that provider.
 *  - Each provider has its own key / model / endpoint, so several keys can coexist and switching model or
 *    provider never loses the others.
 *  - Keys live in the [KeyValueStore] only; nothing here logs, prints or exposes them except [apiKey] for the
 *    HTTP layer.
 *
 * Key names are `<providerId>_api_key` / `_model` / `_base_url`: for OpenRouter these are exactly the v0.1 names,
 * so an upgrade keeps the existing key and model without any copy.
 */
class AiSettings(
    private val store: KeyValueStore,
    private val registry: ProviderRegistry,
) {
    /** False when secure storage was unavailable: keys then only live for this session. */
    val secureStorage: Boolean get() = store.isSecure

    /** The model that really answered the last request (differs from the chosen one after a fallback). */
    @Volatile var lastModelUsed: String? = null

    var selectedProviderId: String?
        get() {
            val explicit = store.getString(K_SELECTED)
            if (explicit != null) return explicit.takeIf { it.isNotBlank() && registry.get(it) != null }
            // v0.1 stored `provider = OPENROUTER | LOCAL`.
            return if (store.getString(K_LEGACY_PROVIDER) == "OPENROUTER") registry.get("openrouter")?.id else null
        }
        set(value) {
            require(value == null || registry.get(value) != null) { "Unknown provider: $value" }
            store.putString(K_SELECTED, value ?: "")
        }

    /** Ask reasoning-capable models to think first. Off by default: faster and cheaper. */
    var thinking: Boolean
        get() = store.getBoolean(K_THINKING, false)
        set(v) = store.putBoolean(K_THINKING, v)

    /** Try other free models when the chosen free one is saturated (OpenRouter). */
    var autoFallback: Boolean
        get() = store.getBoolean(K_FALLBACK, true)
        set(v) = store.putBoolean(K_FALLBACK, v)

    // ── per provider ─────────────────────────────────────────────────────────

    fun model(providerId: String): String =
        store.getString("${providerId}_model")?.takeIf { it.isNotBlank() } ?: registry.require(providerId).defaultModel

    fun setModel(providerId: String, model: String) {
        registry.require(providerId)
        val clean = model.trim()
        store.putString("${providerId}_model", clean.ifBlank { null })
    }

    fun baseUrl(providerId: String): String =
        store.getString("${providerId}_base_url")?.takeIf { it.isNotBlank() } ?: registry.require(providerId).defaultBaseUrl

    /** @param url null (or blank) restores the provider's default. Only HTTPS is accepted. */
    @Throws(ProviderException::class)
    fun setBaseUrl(providerId: String, url: String?) {
        registry.require(providerId)
        if (url.isNullOrBlank()) { store.putString("${providerId}_base_url", null); return }
        store.putString("${providerId}_base_url", EndpointPolicy.validate(url))
    }

    fun apiKey(providerId: String): String? = store.getString("${providerId}_api_key")?.takeIf { it.isNotBlank() }

    fun hasKey(providerId: String): Boolean = apiKey(providerId) != null

    fun setApiKey(providerId: String, key: String) {
        registry.require(providerId)
        val clean = key.trim()
        require(clean.length >= MIN_KEY_LENGTH) { "Clé trop courte" }
        require(clean.none { it.isWhitespace() }) { "Une clé API ne contient pas d'espace" }
        store.putString("${providerId}_api_key", clean)
    }

    /** "Supprimer la clé": also leaves cloud mode if this provider was the active one. */
    fun clearApiKey(providerId: String) {
        store.remove("${providerId}_api_key")
        if (selectedProviderId == providerId) store.putString(K_SELECTED, "")
    }

    // ── what the engine uses ─────────────────────────────────────────────────

    fun activeProvider(): AIProvider? = selectedProviderId?.let { registry.get(it) }

    /** Cloud mode is active only with a provider selected AND a key stored. */
    val cloudActive: Boolean get() = selectedProviderId?.let { hasKey(it) } == true

    fun configFor(providerId: String): ProviderConfig? {
        val key = apiKey(providerId) ?: return null
        return ProviderConfig(key, baseUrl(providerId), model(providerId))
    }

    /** "Effacer mes données": every key, model, choice and preference. */
    fun clearAll() {
        store.clear()
        lastModelUsed = null
    }

    private companion object {
        const val K_SELECTED = "selected_provider"
        const val K_LEGACY_PROVIDER = "provider"
        const val K_THINKING = "thinking_enabled"
        const val K_FALLBACK = "auto_fallback"
        const val MIN_KEY_LENGTH = 8
    }
}
