package com.aura.agent.memory.logic

const val STUB_KIND = "stub"

/** A vector tagged with the space it lives in. Vectors from different spaces are never compared. */
class Embedding(val vector: FloatArray, val kind: String) {
    /** `stub` vectors carry no meaning (hashing fallback when the MiniLM model is not installed). */
    val semantic: Boolean get() = kind != STUB_KIND
}

interface Embedder {
    suspend fun embed(text: String): Embedding
}
