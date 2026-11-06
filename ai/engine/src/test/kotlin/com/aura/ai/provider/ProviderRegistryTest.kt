package com.aura.ai.provider

import org.junit.Assert.*
import org.junit.Test

class ProviderRegistryTest {
    private val registry = ProviderRegistry(listOf(ZaiProvider(), OpenRouterProvider()))

    @Test fun `lookup by id`() {
        assertEquals("Z.ai (GLM)", registry.get("zai")?.displayName)
        assertNull(registry.get("openai"))
        assertEquals(listOf("zai", "openrouter"), registry.all().map { it.id })
    }

    @Test fun `planned providers never include an implemented one and none is selectable`() {
        val planned = registry.planned().map { it.id }
        assertFalse(planned.contains("zai"))
        assertTrue(planned.containsAll(listOf("qwen", "deepseek", "kimi", "minimax", "mistral", "openai", "anthropic", "gemini", "xai", "perplexity", "custom")))
        assertTrue(planned.none { registry.get(it) != null })
    }

    @Test fun `duplicate ids are rejected`() {
        try { ProviderRegistry(listOf(ZaiProvider(), ZaiProvider())); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun `openrouter keeps its free-model fallback and never replaces a paid model`() {
        val or = OpenRouterProvider()
        assertTrue(or.fallbackModels("google/gemma-4-31b-it:free").isNotEmpty())
        assertTrue(or.fallbackModels("google/gemma-4-31b-it:free").all { it.endsWith(":free") })
        assertTrue(or.fallbackModels("openai/gpt-4o-mini").isEmpty())
    }

    @Test fun `openrouter maps its http errors`() {
        val or = OpenRouterProvider()
        val m = or.javaClass.getDeclaredMethod("mapError", Int::class.javaObjectType, String::class.java).apply { isAccessible = true }
        fun kind(status: Int) = (m.invoke(or, status, """{"error":{"message":"x"}}""") as ProviderError).kind
        assertEquals(ErrorKind.INVALID_KEY, kind(401))
        assertEquals(ErrorKind.INSUFFICIENT_BALANCE, kind(402))
        assertEquals(ErrorKind.RATE_LIMITED, kind(429))
        assertEquals(ErrorKind.OVERLOADED, kind(503))
    }
}
