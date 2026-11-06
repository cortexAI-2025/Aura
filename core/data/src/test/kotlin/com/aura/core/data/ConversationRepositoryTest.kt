package com.aura.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aura.core.data.repository.ConversationRepositoryImpl
import com.aura.core.data.repository.RoomMemoryStore
import com.aura.core.database.AuraDatabase
import com.aura.core.domain.model.Memory
import com.aura.core.domain.model.MemorySource
import com.aura.core.domain.model.MemoryType
import com.aura.core.domain.model.Message
import com.aura.core.domain.model.MessageRole
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
class ConversationRepositoryTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AuraDatabase
    private lateinit var repo: ConversationRepositoryImpl

    private fun newRepo() = ConversationRepositoryImpl(db.messageDao(), db.conversationDao(), db.actionLogDao(), ctx)
    private fun user(id: String, text: String, ts: Long = System.currentTimeMillis()) =
        Message(id, MessageRole.USER, text, java.time.Instant.ofEpochMilli(ts))

    @Before fun setUp() {
        ctx.getSharedPreferences("aura_conversations", Context.MODE_PRIVATE).edit().clear().commit()
        db = AuraDatabase.create(ctx, inMemory = true)
        repo = newRepo()
    }

    @After fun tearDown() = db.close()

    @Test fun `a first launch creates a current conversation`() = runBlocking {
        val id = repo.currentConversationId()
        assertTrue(id.isNotEmpty())
        assertEquals(1, repo.observeConversations().first().size)
        assertEquals(id, repo.currentConversationId())
    }

    @Test fun `v0_1 style calls act on the current conversation`() = runBlocking {
        repo.addMessage(user("1", "Bonjour", 10))
        repo.addMessage(user("2", "Ça va ?", 20))
        assertEquals(listOf("Bonjour", "Ça va ?"), repo.getRecentMessages(10).map { it.content })
        assertEquals(listOf("Bonjour", "Ça va ?"), repo.observeMessages(10).first().reversed().map { it.content })
    }

    @Test fun `conversations are independent and switching changes what is shown`() = runBlocking {
        val a = repo.currentConversationId()
        repo.addMessage(a, user("1", "dans A", 10))
        val b = repo.createConversation()
        repo.addMessage(b, user("2", "dans B", 20))
        assertEquals(b, repo.currentConversationId())
        assertEquals(listOf("dans B"), repo.getRecentMessages(10).map { it.content })
        repo.selectConversation(a)
        assertEquals(listOf("dans A"), repo.getRecentMessages(10).map { it.content })
        assertEquals(a, repo.observeCurrentConversationId().first())
    }

    @Test fun `the first user message names the conversation`() = runBlocking {
        val id = repo.createConversation()
        repo.addMessage(id, user("1", "   Quel temps fait-il à Lyon demain matin avec ce vent ?  "))
        val title = repo.observeConversations().first().first { it.id == id }.title
        assertTrue(title, title.startsWith("Quel temps fait-il à Lyon"))
        assertTrue(title.length <= 40)
        repo.addMessage(id, user("2", "Une autre question"))
        assertEquals(title, repo.observeConversations().first().first { it.id == id }.title)
    }

    @Test fun `rename and delete`() = runBlocking {
        val a = repo.currentConversationId()
        val b = repo.createConversation("Voyage")
        repo.addMessage(b, user("1", "billet", 5))
        repo.renameConversation(b, "  Voyage Japon  ")
        assertEquals("Voyage Japon", repo.observeConversations().first().first { it.id == b }.title)
        repo.renameConversation(b, "   ")
        assertEquals("Voyage Japon", repo.observeConversations().first().first { it.id == b }.title)

        repo.deleteConversation(b)
        assertTrue(repo.observeConversations().first().none { it.id == b })
        assertTrue(repo.getRecentMessages(b, 10).isEmpty())
        assertEquals("the current one falls back to a remaining conversation", a, repo.currentConversationId())
    }

    @Test fun `a message for a deleted conversation is dropped, not orphaned`() = runBlocking {
        val id = repo.createConversation()
        repo.deleteConversation(id)
        repo.addMessage(id, user("1", "tardif"))
        assertEquals(0, db.messageDao().countIn(id))
    }

    @Test fun `conversations and the current choice survive a restart`() = runBlocking {
        val a = repo.currentConversationId()
        repo.addMessage(a, user("1", "persistant", 10))
        val b = repo.createConversation("Autre")

        val restarted = newRepo() // new instance, same database + preferences
        assertEquals(b, restarted.currentConversationId())
        assertEquals(listOf("persistant"), restarted.getRecentMessages(a, 10).map { it.content })
        assertEquals(2, restarted.observeConversations().first().size)
    }

    @Test fun `usage is recorded per reply and summed`() = runBlocking {
        val id = repo.currentConversationId()
        repo.addMessage(id, Message("a1", MessageRole.ASSISTANT, "ok", providerId = "zai", model = "glm-4.7-flash", promptTokens = 100, completionTokens = 20))
        repo.addMessage(id, Message("a2", MessageRole.ASSISTANT, "ok", providerId = "zai", model = "glm-4.7-flash", promptTokens = 50, completionTokens = 5))
        val t = repo.usageTotals(id)
        assertEquals(150L, t.promptTokens); assertEquals(25L, t.completionTokens); assertEquals(2, t.requests); assertEquals(175L, t.totalTokens)
        assertEquals(175L, repo.usageTotals(null).totalTokens)
        val perModel = repo.observeUsageByModel().first().single()
        assertEquals("glm-4.7-flash", perModel.model)
        assertEquals("zai", repo.getRecentMessages(id, 5).last().providerId)
    }

    @Test fun `deleteAllConversations wipes everything`() = runBlocking {
        val id = repo.currentConversationId()
        repo.addMessage(id, user("1", "x"))
        repo.deleteAllConversations()
        assertTrue(repo.observeConversations().first().isEmpty())
        assertEquals(0, db.messageDao().getRecent(10).size)
        assertTrue("a fresh conversation is created on demand", repo.currentConversationId().isNotEmpty())
    }

    @Test fun `clearHistory empties the current conversation only`() = runBlocking {
        val a = repo.currentConversationId()
        repo.addMessage(a, user("1", "a"))
        val b = repo.createConversation()
        repo.addMessage(b, user("2", "b"))
        repo.clearHistory()
        assertTrue(repo.getRecentMessages(b, 10).isEmpty())
        assertEquals(1, repo.getRecentMessages(a, 10).size)
    }

    // ── memory store ─────────────────────────────────────────────────────────

    @Test fun `memory store round trips embeddings and flags`() = runBlocking {
        val store = RoomMemoryStore(db.memoryDao(), db.knowledgeDao())
        val m = Memory(
            "m1", "j'aime le café", floatArrayOf(0.5f, -1.25f, 3f), MemoryType.SEMANTIC, source = MemorySource.AUTO,
            needsReview = true, relatedId = "m0", embeddingKind = "minilm-l6-v2", tags = listOf("a", "b"),
        )
        store.upsert(m)
        val back = store.get("m1")!!
        assertArrayEquals(floatArrayOf(0.5f, -1.25f, 3f), back.embedding, 0f)
        assertEquals(MemorySource.AUTO, back.source); assertTrue(back.needsReview)
        assertEquals("m0", back.relatedId); assertEquals("minilm-l6-v2", back.embeddingKind)
        assertEquals(listOf("a", "b"), back.tags)
    }

    @Test fun `memory store deleteAll also clears the knowledge graph`() = runBlocking {
        val store = RoomMemoryStore(db.memoryDao(), db.knowledgeDao())
        store.upsert(Memory("m1", "x", FloatArray(1), MemoryType.SEMANTIC))
        db.knowledgeDao().insertNode(com.aura.core.database.entity.KnowledgeNodeEntity("n1", "Paris", "ENTITY"))
        store.deleteAll()
        assertTrue(store.all().isEmpty())
        assertNull(db.knowledgeDao().getNode("n1"))
    }
}
