package com.aura.core.domain.model

import java.time.Instant

/** A stored memory fragment with its vector embedding. */
data class Memory(
    val id: String,
    val content: String,
    val embedding: FloatArray,
    val type: MemoryType,
    val importance: Float = 0.5f,
    val createdAt: Instant = Instant.now(),
    val accessedAt: Instant = Instant.now(),
    val accessCount: Int = 0,
    val tags: List<String> = emptyList(),
    val linkedGoalId: String? = null,
) {
    override fun equals(other: Any?) = other is Memory && id == other.id
    override fun hashCode() = id.hashCode()
}

enum class MemoryType {
    /** Short-lived working context (current task). */
    WORKING,
    /** Episodic memories — specific events and conversations. */
    EPISODIC,
    /** Semantic knowledge — facts, preferences, patterns. */
    SEMANTIC,
    /** Procedural knowledge — how to do things. */
    PROCEDURAL,
}

/** A retrieved memory with its relevance score. */
data class MemoryResult(
    val memory: Memory,
    val score: Float,
)

/** A user goal driving proactive agent behaviour. */
data class Goal(
    val id: String,
    val title: String,
    val description: String,
    val category: GoalCategory,
    val priority: Int = 5,
    val deadline: Instant? = null,
    val progress: Float = 0f,
    val isActive: Boolean = true,
    val subGoals: List<Goal> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

enum class GoalCategory {
    HEALTH, FINANCE, CAREER, RELATIONSHIPS, PERSONAL_GROWTH, TRAVEL, EDUCATION, OTHER
}

/** Knowledge graph node. */
data class KnowledgeNode(
    val id: String,
    val label: String,
    val type: String,
    val properties: Map<String, String> = emptyMap(),
)

/** Knowledge graph edge between two nodes. */
data class KnowledgeEdge(
    val fromId: String,
    val toId: String,
    val relation: String,
    val weight: Float = 1f,
)
