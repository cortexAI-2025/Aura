package com.aura.agent.memory.logic

import com.aura.ai.provider.SecretScanner
import com.aura.core.domain.model.Memory

enum class RejectReason { EMPTY, TOO_SHORT, TOO_LONG, SECRET }

sealed interface MemoryDecision {
    /** Store it. [relatedTo] is set when a similar-but-different memory exists: flag for the user to check. */
    data class Accept(val relatedTo: Memory?) : MemoryDecision
    data class Duplicate(val existing: Memory) : MemoryDecision
}

/** The rules deciding what may enter memory. Pure and exhaustively unit-tested. */
object MemoryPolicy {
    const val MIN_CHARS = 4
    const val MAX_CHARS = 500

    /** At or above this similarity the new statement adds nothing: it is merged into the existing memory. */
    const val DUPLICATE_THRESHOLD = 0.85

    /** Between this and [DUPLICATE_THRESHOLD] the statements share a topic but differ: possible contradiction. */
    const val RELATED_THRESHOLD = 0.45

    /** Secrets, API keys and banking data are NEVER memorised, whoever asks. */
    fun precheck(text: String): RejectReason? = when {
        text.isBlank() -> RejectReason.EMPTY
        text.trim().length < MIN_CHARS -> RejectReason.TOO_SHORT
        text.length > MAX_CHARS -> RejectReason.TOO_LONG
        SecretScanner.containsSecret(text) -> RejectReason.SECRET
        else -> null
    }

    fun decide(text: String, mostSimilar: Memory?, similarity: Double): MemoryDecision = when {
        mostSimilar == null -> MemoryDecision.Accept(null)
        TextSimilarity.normalize(text).trim() == TextSimilarity.normalize(mostSimilar.content).trim() ->
            MemoryDecision.Duplicate(mostSimilar)
        similarity >= DUPLICATE_THRESHOLD -> MemoryDecision.Duplicate(mostSimilar)
        similarity >= RELATED_THRESHOLD -> MemoryDecision.Accept(mostSimilar)
        else -> MemoryDecision.Accept(null)
    }
}
