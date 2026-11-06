package com.aura.ai.provider

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Retry / fallback strategy around a provider stream.
 *
 *  - A *retryable* failure before any text was produced (rate limit, overload, 5xx, network, timeout)
 *    is retried on the same model with a growing delay (honouring `Retry-After`).
 *  - If it keeps failing, or the model is unknown, the provider's [AIProvider.fallbackModels] are tried.
 *  - Non-retryable failures (bad key, no balance, content filter…) surface immediately.
 *  - Once text has been delivered nothing is retried here: the caller decides how to continue.
 */
class ResilientChat(
    private val maxRetriesPerModel: Int = 2,
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 8_000,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    fun stream(
        provider: AIProvider,
        config: ProviderConfig,
        request: ChatRequest,
        allowFallback: Boolean = true,
    ): Flow<ChatEvent> = flow {
        val models = ArrayDeque<String>().apply {
            add(request.model)
            if (allowFallback) addAll(provider.fallbackModels(request.model).filter { it != request.model })
        }
        var delivered = false
        while (true) {
            val model = models.first()
            var attempt = 0
            var switchModel = false
            while (!switchModel) {
                try {
                    provider.streamChat(config.copy(model = model), request.copy(model = model)).collect { ev ->
                        if (ev is ChatEvent.Delta) delivered = true
                        emit(ev)
                    }
                    return@flow
                } catch (e: ProviderException) {
                    if (delivered) throw e
                    val err = e.error
                    val canSwitch = models.size > 1
                    when {
                        err.kind == ErrorKind.MODEL_NOT_FOUND && canSwitch -> switchModel = true
                        err.retryable && attempt < maxRetriesPerModel -> {
                            attempt++
                            sleep(err.retryAfterMs ?: (baseDelayMs * (1L shl (attempt - 1))).coerceAtMost(maxDelayMs))
                        }
                        err.retryable && canSwitch -> switchModel = true
                        else -> throw e
                    }
                }
            }
            models.removeFirst()
        }
    }
}
