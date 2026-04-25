package com.aura.core.common

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ExtensionsTest {

    @Test fun `cosineSimilarity returns 1 for identical vectors`() {
        val v = floatArrayOf(0.1f, 0.5f, 0.8f)
        assertEquals(1f, v.cosineSimilarity(v), 1e-5f)
    }

    @Test fun `cosineSimilarity returns 0 for orthogonal vectors`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(0f, a.cosineSimilarity(b), 1e-5f)
    }

    @Test fun `cosineSimilarity returns -1 for opposite vectors`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(-1f, 0f)
        assertEquals(-1f, a.cosineSimilarity(b), 1e-5f)
    }

    @Test fun `cosineSimilarity returns 0 for zero vector`() {
        val a = floatArrayOf(0f, 0f, 0f)
        val b = floatArrayOf(1f, 2f, 3f)
        assertEquals(0f, a.cosineSimilarity(b), 1e-5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cosineSimilarity throws on dimension mismatch`() {
        floatArrayOf(1f, 2f).cosineSimilarity(floatArrayOf(1f, 2f, 3f))
    }

    @Test fun `normalize produces unit vector`() {
        val v = floatArrayOf(3f, 4f)
        v.normalize()
        val norm = Math.sqrt((v[0] * v[0] + v[1] * v[1]).toDouble()).toFloat()
        assertEquals(1f, norm, 1e-5f)
    }
}
