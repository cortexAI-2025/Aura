package com.aura.agent.memory.logic

import org.junit.Assert.*
import org.junit.Test

class TextSimilarityTest {
    @Test fun `accents case and stop words are ignored`() {
        assertEquals(listOf("cafe", "sucre"), TextSimilarity.tokens("Le CAFÉ, sans sucre !"))
        assertEquals(listOf("anniversaire", "12", "mars"), TextSimilarity.tokens("Mon anniversaire est le 12 mars"))
    }

    @Test fun `apostrophes split elisions`() {
        assertEquals(listOf("aime", "the"), TextSimilarity.tokens("J'aime le thé"))
    }

    @Test fun `prefix tolerant matching links word forms`() {
        assertTrue(TextSimilarity.relevance("mes préférences", "Je préfère les réponses courtes") > 0.3)
        assertTrue(TextSimilarity.overlap("j'aime les chats", "J'aime le chat") > 0.8)
    }

    @Test fun `unrelated texts score zero`() {
        assertEquals(0.0, TextSimilarity.relevance("météo demain", "Mon chat s'appelle Tom"), 1e-9)
        assertEquals(0.0, TextSimilarity.overlap("café", "voiture"), 1e-9)
    }

    @Test fun `empty or stop-word-only input is safe`() {
        assertEquals(0.0, TextSimilarity.relevance("", "x"), 0.0)
        assertEquals(0.0, TextSimilarity.relevance("le la les", "le la les"), 0.0)
    }

    @Test fun `relevance rewards covering the whole question`() {
        val full = TextSimilarity.relevance("ville Lyon", "J'habite à Lyon, ville agréable")
        val half = TextSimilarity.relevance("ville Lyon", "La ville de Paris")
        assertTrue(full > half)
    }
}
