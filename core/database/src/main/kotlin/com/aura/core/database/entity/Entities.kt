package com.aura.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val role: String,
    val content: String,
    val timestampMs: Long = Instant.now().toEpochMilli(),
    val metadata: String = "{}",
    @ColumnInfo(defaultValue = "''") val conversationId: String = "",
    val providerId: String? = null,
    val model: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val finishReason: String? = null,
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
    @ColumnInfo(defaultValue = "'USER'") val source: String = "USER",
    @ColumnInfo(defaultValue = "0") val needsReview: Boolean = false,
    val relatedId: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = Instant.now().toEpochMilli(),
    @ColumnInfo(defaultValue = "'stub'") val embeddingKind: String = "stub",
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
