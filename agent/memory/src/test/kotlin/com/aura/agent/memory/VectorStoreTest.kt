package com.aura.agent.memory

import com.aura.core.database.dao.MemoryDao
import com.aura.core.database.entity.MemoryEntity
import com.aura.core.domain.model.MemoryType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VectorStoreTest {
    private lateinit var memoryDao: MemoryDao
    private lateinit var vectorStore: VectorStore

    @Before fun setUp() {
        memoryDao = mockk(relaxed = true)
        vectorStore = VectorStore(memoryDao)
    }

    @Test fun `search returns top-k sorted by cosine similarity`() = runTest {
        val dim = 4
        val entities = listOf(
            makeEntity("1", floatArrayOf(1f, 0f, 0f, 0f)),  // cos=1.0 (exact match)
            makeEntity("2", floatArrayOf(0f, 1f, 0f, 0f)),  // cos=0.0 (orthogonal)
            makeEntity("3", floatArrayOf(0.7f, 0.7f, 0f, 0f)), // cos≈0.7 (partial)
        )
        coEvery { memoryDao.getAll(any(), any()) } returns entities

        val query = floatArrayOf(1f, 0f, 0f, 0f)
        val results = vectorStore.search(query, k = 3, threshold = 0f)

        assertEquals(3, results.size)
        assertEquals("1", results[0].memory.id)
        assertTrue(results[0].score > results[1].score)
    }

    @Test fun `search respects threshold`() = runTest {
        val entities = listOf(
            makeEntity("1", floatArrayOf(1f, 0f, 0f, 0f)),
            makeEntity("2", floatArrayOf(0f, 1f, 0f, 0f)),
        )
        coEvery { memoryDao.getAll(any(), any()) } returns entities

        val query = floatArrayOf(1f, 0f, 0f, 0f)
        val results = vectorStore.search(query, k = 5, threshold = 0.5f)

        // Only the first entity (cos=1.0) should pass the 0.5 threshold
        assertEquals(1, results.size)
        assertEquals("1", results[0].memory.id)
    }

    @Test fun `prune calls DAO when over limit`() = runTest {
        coEvery { memoryDao.count() } returns 200
        coEvery { memoryDao.pruneOldest(any()) } just Runs

        vectorStore.prune(maxCount = 100)

        coVerify { memoryDao.pruneOldest(100) }
    }

    private fun makeEntity(id: String, embedding: FloatArray): MemoryEntity {
        val buf = ByteBuffer.allocate(embedding.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        embedding.forEach { buf.putFloat(it) }
        return MemoryEntity(id = id, content = "test $id", embeddingBlob = buf.array(), type = MemoryType.EPISODIC.name)
    }
}
