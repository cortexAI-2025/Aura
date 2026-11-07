package com.aura.ai.provider

import kotlinx.coroutines.flow.Flow

data class ModelInfo(
    val id: String,
    val displayName: String,
    val contextTokens: Int? = null,
    val free: Boolean = false,
    val note: String? = null,
)

data class ProviderCapabilities(
    val streaming: Boolean = true,
    /** The API reports token usage in its responses (shown in the consumption panel). */
    val reportsUsage: Boolean = true,
    /** The model can be asked to reason first (`thinking`). */
    val thinkingToggle: Boolean = false,
    /** A public endpoint returns the remaining account balance. Not the case for any current provider. */
    val balanceApi: Boolean = false,
)

/** Connection parameters. [toString] never prints the key. */
data class ProviderConfig(val apiKey: String, val baseUrl: String, val model: String) {
    override fun toString() = "ProviderConfig(baseUrl=$baseUrl, model=$model, apiKey=***)"
}

/**
 * The single contract every cloud model provider implements. Adding a provider means implementing
 * this interface and registering it in [ProviderRegistry]; nothing else in Aura depends on a vendor.
 */
interface AIProvider {
    val id: String
    val displayName: String
    val defaultBaseUrl: String
    val defaultModel: String
    val suggestedModels: List<ModelInfo>
    val capabilities: ProviderCapabilities

    /** Where to get a valid key / which key type to use. Shown next to the key field. */
    val keyHint: String?

    /** What leaves the device when this provider is used. Shown before the first use. */
    val dataNotice: String

    /** Streaming chat. Throws [ProviderException] on failure; ends with [ChatEvent.Finished]. */
    fun streamChat(config: ProviderConfig, request: ChatRequest): Flow<ChatEvent>

    /** Non-streaming chat. */
    suspend fun complete(config: ProviderConfig, request: ChatRequest): ChatResult

    /** A tiny real request that proves the key, endpoint and model work together. Never throws. */
    suspend fun testConnection(config: ProviderConfig): ConnectionResult

    /** Other models to try, in order, when [model] is saturated or gone. Empty = never switch silently. */
    fun fallbackModels(model: String): List<String> = emptyList()
}
