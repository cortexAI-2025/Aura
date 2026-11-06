package com.aura.agent.memory.logic

import com.aura.core.domain.model.MemorySource
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MemoryServiceTest {
    private var tick = 0L
    private var ids = 0
    private fun service(store: FakeMemoryStore = FakeMemoryStore(), embedder: Embedder = StubEmbedder()) =
        MemoryService(store, embedder, clock = { Instant.ofEpochSecond(1_700_000_000 + tick++) }, newId = { "m${++ids}" })

    private fun saved(r: RememberResult) = (r as RememberResult.Saved).memory

    // ── what may be stored ───────────────────────────────────────────────────

    @Test fun `remember stores a clean statement`() = runBlocking {
        val store = FakeMemoryStore()
        val m = saved(service(store).remember("  J'aime   le café sans sucre "))
        assertEquals("J'aime le café sans sucre", m.content)
        assertEquals(MemorySource.USER, m.source)
        assertFalse(m.needsReview)
        assertEquals(1, store.all().size)
    }

    @Test fun `secrets are rejected and nothing reaches the store`() = runBlocking {
        val store = FakeMemoryStore()
        val s = service(store)
        listOf(
            "retiens ma clé sk-proj-abcdefghijklmnop1234",
            "mon mot de passe est hunter2hunter2",
            "ma carte bleue 4111 1111 1111 1111",
            "mon IBAN FR76 3000 6000 0112 3456 7890 189",
            "Authorization: Bearer abcdefghijkl12345678",
        ).forEach { assertEquals(it, RememberResult.Rejected(RejectReason.SECRET), s.remember(it)) }
        assertTrue(store.all().isEmpty())
    }

    @Test fun `empty and tiny statements are rejected`() = runBlocking {
        val s = service()
        assertEquals(RememberResult.Rejected(RejectReason.EMPTY), s.remember(" "))
        assertEquals(RememberResult.Rejected(RejectReason.TOO_SHORT), s.remember("ok"))
    }

    // ── duplicates and contradictions ────────────────────────────────────────

    @Test fun `saying the same thing again does not create a duplicate`() = runBlocking {
        val store = FakeMemoryStore()
        val s = service(store)
        s.remember("J'aime le café")
        val again = s.remember("j'aime le CAFÉ !")
        assertTrue(again is RememberResult.Duplicate)
        assertEquals(1, store.all().size)
    }

    @Test fun `a different statement on the same topic is flagged for review`() = runBlocking {
        val store = FakeMemoryStore()
        val s = service(store)
        val first = saved(s.remember("Ma ville est Paris"))
        val second = saved(s.remember("Ma ville est Lyon"))
        assertTrue(second.needsReview)
        assertEquals(first.id, second.relatedId)
        assertEquals(2, store.all().size)
        assertFalse("the older memory is untouched", store.get(first.id)!!.needsReview)
    }

    @Test fun `unrelated statements are not flagged`() = runBlocking {
        val s = service()
        s.remember("Ma ville est Paris")
        assertFalse(saved(s.remember("Mon chat s'appelle Tom")).needsReview)
    }

    @Test fun `acceptReview clears the flag`() = runBlocking {
        val store = FakeMemoryStore()
        val s = service(store)
        s.remember("Ma ville est Paris")
        val flagged = saved(s.remember("Ma ville est Lyon"))
        s.acceptReview(flagged.id)
        assertFalse(store.get(flagged.id)!!.needsReview)
        assertNull(store.get(flagged.id)!!.relatedId)
    }

    // ── recall ───────────────────────────────────────────────────────────────

    @Test fun `lexical recall finds relevant memories and ignores the rest`() = runBlocking {
        val s = service()
        s.remember("J'aime le café sans sucre")
        s.remember("Mon chat s'appelle Tom")
        s.remember("Je travaille chez Acme à Lyon")
        val hits = s.recall("comment s'appelle mon chat ?")
        assertEquals(listOf("Mon chat s'appelle Tom"), hits.map { it.memory.content })
        assertTrue(s.recall("la météo de demain").isEmpty())
    }

    @Test fun `recall orders by relevance and honours topK`() = runBlocking {
        val s = service()
        s.remember("J'habite à Lyon")
        s.remember("J'habite à Lyon depuis 2019 dans le troisième arrondissement")
        s.remember("Lyon est ma ville préférée pour la gastronomie")
        val top = s.recall("où j'habite à Lyon", topK = 2)
        assertEquals(2, top.size)
        assertTrue(top[0].score >= top[1].score)
    }

    @Test fun `recall records access`() = runBlocking {
        val store = FakeMemoryStore()
        val s = service(store)
        val m = saved(s.remember("Mon chat s'appelle Tom"))
        s.recall("chat")
        assertEquals(1, store.get(m.id)!!.accessCount)
    }

    @Test fun `blank query recalls nothing`() = runBlocking {
        val s = service()
        s.remember("Mon chat s'appelle Tom")
        assertTrue(s.recall("  ").isEmpty())
    }

    @Test fun `real embeddings find a memory with no shared word`() = runBlocking {
        val concepts = mapOf("cafe" to 0, "boisson" to 0, "chaude" to 0, "chat" to 1)
        val s = service(embedder = ConceptEmbedder(concepts))
        s.remember("J'aime le café")
        s.remember("Mon chat dort")
        val hits = s.recall("boisson chaude")
        assertEquals("J'aime le café", hits.first().memory.content)
    }

    @Test fun `without a real model the same query honestly finds nothing`() = runBlocking {
        val s = service(embedder = StubEmbedder())
        s.remember("J'aime le café")
        assertTrue(s.recall("boisson chaude").isEmpty())
    }

    @Test fun `vectors from different spaces are never compared`() = runBlocking {
        val store = FakeMemoryStore()
        service(store, StubEmbedder()).remember("J'aime le café") // stored in the stub space
        val concepts = mapOf("cafe" to 0, "boisson" to 0)
        val hits = service(store, ConceptEmbedder(concepts)).recall("boisson")
        assertTrue("stub vector must not be compared with a real one", hits.isEmpty())
    }

    // ── edit, forget, wipe, export ───────────────────────────────────────────

    @Test fun `update corrects a memory and re-evaluates it`() = runBlocking {
        val store = FakeMemoryStore()
        val s = service(store)
        val m = saved(s.remember("Mon chat s'appelle Tim"))
        val u = saved(s.update(m.id, "Mon chat s'appelle Tom"))
        assertEquals("Mon chat s'appelle Tom", store.get(m.id)!!.content)
        assertFalse("not flagged against itself", u.needsReview)
    }

    @Test fun `update refuses secrets and unknown ids`() = runBlocking {
        val s = service()
        val m = saved(s.remember("Mon chat s'appelle Tim"))
        assertEquals(RememberResult.Rejected(RejectReason.SECRET), s.update(m.id, "token = abcdef123456"))
        assertTrue(s.update("nope", "quelque chose") is RememberResult.Rejected)
    }

    @Test fun `forget removes the best match only when it is a confident match`() = runBlocking {
        val store = FakeMemoryStore()
        val s = service(store)
        s.remember("J'aime le café sans sucre")
        s.remember("Mon chat s'appelle Tom")
        assertNull(s.forget("la météo"))
        assertEquals("J'aime le café sans sucre", s.forget("j'aime le café")?.content)
        assertEquals(1, store.all().size)
    }

    @Test fun `delete and deleteAll`() = runBlocking {
        val store = FakeMemoryStore()
        val s = service(store)
        val a = saved(s.remember("Mon chat s'appelle Tom"))
        s.remember("J'aime le café")
        s.delete(a.id)
        assertEquals(1, store.all().size)
        s.deleteAll()
        assertTrue(store.all().isEmpty())
        assertEquals(0, s.count())
    }

    @Test fun `search filters for the memory screen`() = runBlocking {
        val s = service()
        s.remember("J'aime le café sans sucre")
        s.remember("Mon chat s'appelle Tom")
        assertEquals(listOf("Mon chat s'appelle Tom"), s.search("chat").map { it.content })
        assertEquals(2, s.search("").size)
    }

    @Test fun `export is readable json without embeddings`() = runBlocking {
        val s = service()
        s.remember("J'aime le café sans sucre")
        s.remember("Mon chat s'appelle Tom")
        val json = JSONObject(s.exportJson())
        assertEquals("aura-memory", json.getString("format"))
        assertEquals(1, json.getInt("version"))
        assertEquals(2, json.getInt("count"))
        val first = json.getJSONArray("memories").getJSONObject(0)
        assertTrue(first.has("content"))
        assertFalse("embeddings are not exported", first.has("embedding"))
        assertFalse(json.toString().contains("sk-"))
    }

    @Test fun `legacy vector search only compares within a real space`() = runBlocking {
        val s = service(embedder = ConceptEmbedder(mapOf("cafe" to 0, "chat" to 1)))
        s.remember("J'aime le café")
        s.remember("Mon chat dort")
        val hits = s.searchByVector(floatArrayOf(1f, 0f, 0f, 0f), "fake-semantic", 1)
        assertEquals("J'aime le café", hits.single().memory.content)
        assertTrue(s.searchByVector(floatArrayOf(1f, 0f, 0f, 0f), STUB_KIND, 1).isEmpty())
    }
}
