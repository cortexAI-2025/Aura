package com.aura.core.database.dao

import androidx.room.*
import com.aura.core.database.entity.KnowledgeEdgeEntity
import com.aura.core.database.entity.KnowledgeNodeEntity

@Dao
interface KnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: KnowledgeNodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdge(edge: KnowledgeEdgeEntity)

    @Query("SELECT * FROM knowledge_nodes WHERE id = :id")
    suspend fun getNode(id: String): KnowledgeNodeEntity?

    @Query("SELECT n.* FROM knowledge_nodes n INNER JOIN knowledge_edges e ON n.id = e.toId WHERE e.fromId = :fromId AND (:relation IS NULL OR e.relation = :relation)")
    suspend fun getNeighbors(fromId: String, relation: String? = null): List<KnowledgeNodeEntity>

    @Query("SELECT * FROM knowledge_nodes WHERE label LIKE '%' || :query || '%' LIMIT 20")
    suspend fun searchNodes(query: String): List<KnowledgeNodeEntity>
}
