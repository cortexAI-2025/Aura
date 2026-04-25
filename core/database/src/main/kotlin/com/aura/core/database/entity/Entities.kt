package com.aura.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val role: String,
    val content: String,
    val timestampMs: Long = Instant.now().toEpochMilli(),
    val metadata: String = "{}",
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val content: String,
    /** Serialized FloatArray as comma-separated values. */
    val embeddingBlob: ByteArray,
    val type: String,
    val importance: Float = 0.5f,
    val createdAtMs: Long = Instant.now().toEpochMilli(),
    val accessedAtMs: Long = Instant.now().toEpochMilli(),
    val accessCount: Int = 0,
    val tags: String = "",
    val linkedGoalId: String? = null,
) {
    override fun equals(other: Any?) = other is MemoryEntity && id == other.id
    override fun hashCode() = id.hashCode()
}

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val category: String,
    val priority: Int = 5,
    val deadlineMs: Long? = null,
    val progress: Float = 0f,
    val isActive: Boolean = true,
    val createdAtMs: Long = Instant.now().toEpochMilli(),
    val updatedAtMs: Long = Instant.now().toEpochMilli(),
)

@Entity(tableName = "action_log")
data class ActionLogEntity(
    @PrimaryKey val actionId: String,
    val tool: String,
    val success: Boolean,
    val output: String,
    val timestampMs: Long = Instant.now().toEpochMilli(),
)

@Entity(tableName = "knowledge_nodes")
data class KnowledgeNodeEntity(
    @PrimaryKey val id: String,
    val label: String,
    val type: String,
    val propertiesJson: String = "{}",
)

@Entity(tableName = "knowledge_edges", primaryKeys = ["fromId", "toId", "relation"])
data class KnowledgeEdgeEntity(
    val fromId: String,
    val toId: String,
    val relation: String,
    val weight: Float = 1f,
)
