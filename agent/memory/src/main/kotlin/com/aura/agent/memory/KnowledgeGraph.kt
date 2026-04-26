package com.aura.agent.memory

import com.aura.core.database.dao.KnowledgeDao
import com.aura.core.database.entity.KnowledgeEdgeEntity
import com.aura.core.database.entity.KnowledgeNodeEntity
import com.aura.core.domain.model.KnowledgeEdge
import com.aura.core.domain.model.KnowledgeNode
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local knowledge graph stored in Room.
 *
 * Nodes are named entities (Person, Place, Event, Preference, etc.).
 * Edges capture semantic relations (knows, likes, works_at, scheduled_for, ...).
 *
 * Entity extraction is intentionally simple in MVP: a regex-based NER that
 * identifies capitalized multi-word expressions. In production, replace with
 * a TFLite NER model (e.g., NER-small, ~10 MB).
 */
@Singleton
class KnowledgeGraph @Inject constructor(
    private val knowledgeDao: KnowledgeDao,
) {
    suspend fun upsertNode(node: KnowledgeNode) =
        knowledgeDao.insertNode(KnowledgeNodeEntity(node.id, node.label, node.type, node.properties.toString()))

    suspend fun addEdge(edge: KnowledgeEdge) =
        knowledgeDao.insertEdge(KnowledgeEdgeEntity(edge.fromId, edge.toId, edge.relation, edge.weight))

    suspend fun getNeighbors(nodeId: String, relation: String? = null): List<KnowledgeNode> =
        (if (relation == null) knowledgeDao.getNeighbors(nodeId)
        else knowledgeDao.getNeighborsByRelation(nodeId, relation)).map { it.toDomain() }

    suspend fun search(query: String): List<KnowledgeNode> =
        knowledgeDao.searchNodes(query).map { it.toDomain() }

    /** Lightweight entity extraction from free text — links entities to a source memory. */
    suspend fun extractAndStore(text: String, sourceMemoryId: String) {
        val entities = extractEntities(text)
        entities.forEach { (label, type) ->
            val nodeId = "entity_${label.lowercase().replace(" ", "_")}"
            upsertNode(KnowledgeNode(nodeId, label, type))
            addEdge(KnowledgeEdge(sourceMemoryId, nodeId, "mentions"))
        }
        if (entities.isNotEmpty()) Timber.d("KG extracted ${entities.size} entities from memory $sourceMemoryId")
    }

    private fun extractEntities(text: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        // Simple capitalised phrase detection (2-3 consecutive title-cased words)
        val regex = Regex("""(?:[A-Z][a-z]+(?:\s+[A-Z][a-z]+){0,2})""")
        regex.findAll(text).forEach { match ->
            val label = match.value.trim()
            if (label.length > 3) results.add(label to "ENTITY")
        }
        // Date/time patterns → EVENT type
        val dateRegex = Regex("""\b\d{1,2}[/-]\d{1,2}[/-]\d{2,4}\b|\b(lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""", RegexOption.IGNORE_CASE)
        dateRegex.findAll(text).forEach { results.add(it.value to "DATE") }
        return results.distinctBy { it.first }
    }
}

private fun KnowledgeNodeEntity.toDomain() = KnowledgeNode(id, label, type)
