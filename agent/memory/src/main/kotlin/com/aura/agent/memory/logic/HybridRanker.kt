package com.aura.agent.memory.logic

import com.aura.core.domain.model.Memory
import kotlin.math.sqrt

/**
 * Scores memories. When both vectors come from the same *real* embedding model the cosine similarity
 * is blended with the lexical score; otherwise (stub vectors, mixed spaces) only the lexical score is used,
 * so the ranking is honest about what it can actually measure.
 */
object HybridRanker {
    const val SEMANTIC_WEIGHT = 0.6

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || a.size != b.size) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val d = sqrt(na) * sqrt(nb)
        return if (d == 0.0) 0.0 else (dot / d).coerceIn(-1.0, 1.0)
    }

    private fun comparable(query: Embedding, memory: Memory): Boolean =
        query.semantic && memory.embeddingKind == query.kind && memory.embedding.size == query.vector.size

    /** How relevant [memory] is to a question or topic. 0..1 */
    fun relevance(query: String, queryEmbedding: Embedding, memory: Memory): Double {
        val lexical = TextSimilarity.relevance(query, memory.content)
        val score = if (comparable(queryEmbedding, memory)) {
            val cos = cosine(queryEmbedding.vector, memory.embedding).coerceAtLeast(0.0)
            SEMANTIC_WEIGHT * cos + (1 - SEMANTIC_WEIGHT) * lexical
        } else lexical
        return (score + 0.05 * memory.importance.coerceIn(0f, 1f)).coerceAtMost(1.0).takeIf { lexical > 0 || score > 0 } ?: 0.0
    }

    /** How alike two statements are (duplicate / contradiction detection). 0..1 */
    fun similarity(text: String, embedding: Embedding, memory: Memory): Double {
        val lexical = TextSimilarity.overlap(text, memory.content)
        return if (comparable(embedding, memory)) {
            val cos = cosine(embedding.vector, memory.embedding).coerceAtLeast(0.0)
            SEMANTIC_WEIGHT * cos + (1 - SEMANTIC_WEIGHT) * lexical
        } else lexical
    }
}
