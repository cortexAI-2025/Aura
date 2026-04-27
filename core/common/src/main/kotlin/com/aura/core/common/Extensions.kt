package com.aura.core.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

fun <T> Flow<T>.asAuraResult(): Flow<AuraResult<T>> = map { AuraResult.Success(it) }
    .onStart { emit(AuraResult.Loading) }
    .catch { emit(AuraResult.Error(it)) }

/** Cosine similarity between two float vectors. Returns value in [-1, 1]. */
fun FloatArray.cosineSimilarity(other: FloatArray): Float {
    require(size == other.size) { "Vector dimensions must match: $size vs ${other.size}" }
    var dot = 0f; var normA = 0f; var normB = 0f
    for (i in indices) { dot += this[i] * other[i]; normA += this[i] * this[i]; normB += other[i] * other[i] }
    val denom = Math.sqrt((normA * normB).toDouble()).toFloat()
    return if (denom == 0f) 0f else dot / denom
}

/** Normalize a float vector in-place to unit length. */
fun FloatArray.normalize(): FloatArray {
    val norm = Math.sqrt(sumOf { (it * it).toDouble() }).toFloat()
    if (norm > 0f) for (i in indices) this[i] /= norm
    return this
}
