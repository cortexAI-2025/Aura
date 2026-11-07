package com.aura.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aura.core.database.entity.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuraDaoTest {
    private lateinit var db: AuraDatabase

    @Before fun setUp() {
        db = AuraDatabase.create(ApplicationProvider.getApplicationContext<Context>(), inMemory = true)
    }

    @After fun tearDown() = db.close()

    private suspend fun conversation(id: String, updated: Long = 1) =
        db.conversationDao().upsert(ConversationEntity(id, "t-$id", 0, updated))

    private fun msg(id: String, cid: String, ts: Long, role: String = "USER", p: Int? = null, c: Int? = null, model: String? = null) =
        MessageEntity(id, role, "c-$id", ts, "{}", cid, if (model != null) "zai" else null, model, p, c)

    @Test fun `messages are isolated per conversation and newest first`() = runBlocking {
        conversation("a"); conversation("b")
        db.messageDao().insert(msg("1", "a", 10)); db.messageDao().insert(msg("2", "a", 20)); db.messageDao().insert(msg("3", "b", 15))
        assertEquals(listOf("2", "1"), db.messageDao().getRecentIn("a", 10).map { it.id })
        assertEquals(listOf("3"), db.messageDao().getRecentIn("b", 10).map { it.id })
        assertEquals(1, db.messageDao().getRecentIn("a", 1).size)
        assertEquals(listOf("2", "1"), db.messageDao().observeRecentIn("a", 10).first().map { it.id })
    }

    @Test fun `deleting a conversation removes only its messages`() = runBlocking {
        conversation("a"); conversation("b")
        db.messageDao().insert(msg("1", "a", 10)); db.messageDao().insert(msg("2", "b", 10))
        db.messageDao().deleteByConversation("a"); db.conversationDao().delete("a")
        assertNull(db.conversationDao().get("a"))
        assertEquals(0, db.messageDao().countIn("a"))
        assertEquals(1, db.messageDao().countIn("b"))
    }

    @Test fun `conversations are ordered by recent activity and can be renamed`() = runBlocking {
        conversation("old", 1); conversation("new", 5)
        assertEquals(listOf("new", "old"), db.conversationDao().observeAll().first().map { it.id })
        assertEquals("new", db.conversationDao().mostRecentId())
        db.conversationDao().rename("old", "Voyage", nowMs = 9)
        assertEquals("Voyage", db.conversationDao().get("old")!!.title)
        assertEquals("old", db.conversationDao().mostRecentId())
    }

    @Test fun `usage is summed from provider reported tokens only`() = runBlocking {
        conversation("a"); conversation("b")
        db.messageDao().insert(msg("u1", "a", 1)) // user message: no usage
        db.messageDao().insert(msg("r1", "a", 2, "ASSISTANT", 100, 20, "glm-4.7-flash"))
        db.messageDao().insert(msg("r2", "a", 3, "ASSISTANT", 50, 10, "glm-4.7-flash"))
        db.messageDao().insert(msg("r3", "b", 4, "ASSISTANT", 7, 3, "glm-4.7"))
        val a = db.messageDao().usageIn("a")
        assertEquals(150L, a.prompt); assertEquals(30L, a.completion); assertEquals(2, a.requests)
        val all = db.messageDao().usageAll()
        assertEquals(157L, all.prompt); assertEquals(3, all.requests)
        val byModel = db.messageDao().observeUsageByModel().first().associateBy { it.model }
        assertEquals(150L, byModel.getValue("glm-4.7-flash").prompt)
        assertEquals(7L, byModel.getValue("glm-4.7").prompt)
    }

    @Test fun `no usage means zeros not nulls`() = runBlocking {
        val u = db.messageDao().usageAll()
        assertEquals(0L, u.prompt); assertEquals(0, u.requests)
    }

    @Test fun `memory flags survive a round trip and deleteAll empties the table`() = runBlocking {
        val m = MemoryEntity("m1", "j'aime le café", ByteArray(8), "SEMANTIC", needsReview = true, relatedId = "m0", source = "AUTO", embeddingKind = "minilm")
        db.memoryDao().insert(m)
        val back = db.memoryDao().getById("m1")!!
        assertTrue(back.needsReview); assertEquals("m0", back.relatedId); assertEquals("AUTO", back.source); assertEquals("minilm", back.embeddingKind)
        db.memoryDao().deleteAll()
        assertTrue(db.memoryDao().listAll().isEmpty())
    }

    @Test fun `knowledge graph can be wiped`() = runBlocking {
        db.knowledgeDao().insertNode(KnowledgeNodeEntity("n1", "Paris", "ENTITY"))
        db.knowledgeDao().insertEdge(KnowledgeEdgeEntity("m1", "n1", "mentions"))
        db.knowledgeDao().deleteAllEdges(); db.knowledgeDao().deleteAllNodes()
        assertNull(db.knowledgeDao().getNode("n1"))
        assertTrue(db.knowledgeDao().getNeighbors("m1").isEmpty())
    }
}
