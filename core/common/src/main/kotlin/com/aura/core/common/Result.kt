package com.aura.core.common

sealed interface AuraResult<out T> {
    data class Success<T>(val data: T) : AuraResult<T>
    data class Error(val exception: Throwable, val message: String? = null) : AuraResult<Nothing>
    data object Loading : AuraResult<Nothing>
}

inline fun <T> AuraResult<T>.onSuccess(action: (T) -> Unit): AuraResult<T> {
    if (this is AuraResult.Success) action(data)
    return this
}

inline fun <T> AuraResult<T>.onError(action: (Throwable, String?) -> Unit): AuraResult<T> {
    if (this is AuraResult.Error) action(exception, message)
    return this
}

inline fun <T, R> AuraResult<T>.map(transform: (T) -> R): AuraResult<R> = when (this) {
    is AuraResult.Success -> AuraResult.Success(transform(data))
    is AuraResult.Error -> this
    is AuraResult.Loading -> this
}

suspend fun <T> runCatchingAura(block: suspend () -> T): AuraResult<T> = try {
    AuraResult.Success(block())
} catch (e: Exception) {
    AuraResult.Error(e, e.message)
}
