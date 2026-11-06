package com.aura.ai.settings

import com.aura.ai.provider.ErrorKind
import com.aura.ai.provider.OpenRouterProvider
import com.aura.ai.provider.ProviderException
import com.aura.ai.provider.ProviderRegistry
import com.aura.ai.provider.ZaiProvider
import org.junit.Assert.*
import org.junit.Test

class AiSettingsTest {
    private val zaiKey = "0123456789abcdef0123456789abcdef.AbCdEfGhIjKlMnOp"
    private val registry = ProviderRegistry(listOf(ZaiProvider(), OpenRouterProvider()))
    private fun settings(store: KeyValueStore = InMemoryKeyValueStore()) = AiSettings(store, registry)

    @Test fun `fresh install is local mode with provider defaults`() {
        val s = settings()
        assertNull(s.selectedProviderId)
        assertFalse(s.cloudActive)
        assertEquals("glm-4.7-flash", s.model("zai"))
        assertEquals("https://api.z.ai/api/paas/v4", s.baseUrl("zai"))
        assertFalse(s.thinking); assertTrue(s.autoFallback)
        assertNull(s.configFor("zai"))
    }

    @Test fun `cloud needs both a selected provider and a stored key`() {
        val s = settings()
        s.selectedProviderId = "zai"
        assertFalse("provider without key is not active", s.cloudActive)
        s.setApiKey("zai", zaiKey)
        assertTrue(s.cloudActive)
        assertEquals("zai", s.activeProvider()!!.id)
        val cfg = s.configFor("zai")!!
        assertEquals(zaiKey, cfg.apiKey); assertEquals("glm-4.7-flash", cfg.model)
    }

    @Test fun `the key is trimmed validated and never part of toString output`() {
        val s = settings()
        s.setApiKey("zai", "  $zaiKey \n")
        assertEquals(zaiKey, s.apiKey("zai"))
        listOf("short", "has space inside 12345678", "").forEach {
            try { s.setApiKey("zai", it); fail("should reject '$it'") } catch (e: IllegalArgumentException) { }
        }
        assertFalse(s.toString().contains(zaiKey))
        assertFalse(s.configFor("zai").toString().contains(zaiKey))
    }

    @Test fun `deleting the key leaves cloud mode and keeps other providers`() {
        val s = settings()
        s.setApiKey("zai", zaiKey); s.setApiKey("openrouter", "or-key-1234567890")
        s.selectedProviderId = "zai"
        s.clearApiKey("zai")
        assertNull(s.apiKey("zai")); assertNull(s.selectedProviderId); assertFalse(s.cloudActive)
        assertEquals("or-key-1234567890", s.apiKey("openrouter"))
    }

    @Test fun `several providers keep independent keys and models and switching loses nothing`() {
        val s = settings()
        s.setApiKey("zai", zaiKey); s.setModel("zai", "glm-4.7")
        s.setApiKey("openrouter", "or-key-1234567890"); s.setModel("openrouter", "google/gemma-4-26b-a4b-it:free")
        s.selectedProviderId = "zai"
        assertEquals("glm-4.7", s.configFor("zai")!!.model)
        s.selectedProviderId = "openrouter"
        assertEquals("google/gemma-4-26b-a4b-it:free", s.activeProvider()!!.let { s.model(it.id) })
        s.selectedProviderId = "zai"
        assertEquals("glm-4.7", s.model("zai")); assertEquals(zaiKey, s.apiKey("zai"))
    }

    @Test fun `changing the model is immediate and a blank model restores the default`() {
        val s = settings()
        s.setModel("zai", " glm-5.3 ")
        assertEquals("glm-5.3", s.model("zai"))
        s.setModel("zai", "  ")
        assertEquals("glm-4.7-flash", s.model("zai"))
    }

    @Test fun `unknown providers are refused`() {
        val s = settings()
        try { s.selectedProviderId = "openai"; fail() } catch (e: IllegalArgumentException) { }
        try { s.setApiKey("openai", zaiKey); fail() } catch (e: IllegalStateException) { }
    }

    @Test fun `only https endpoints can be configured`() {
        val s = settings()
        try { s.setBaseUrl("zai", "http://api.z.ai/api/paas/v4"); fail() }
        catch (e: ProviderException) { assertEquals(ErrorKind.INSECURE_ENDPOINT, e.error.kind) }
        s.setBaseUrl("zai", "https://proxy.example.com/v4/")
        assertEquals("https://proxy.example.com/v4", s.baseUrl("zai"))
        s.setBaseUrl("zai", null)
        assertEquals("https://api.z.ai/api/paas/v4", s.baseUrl("zai"))
    }

    @Test fun `v0_1 openrouter settings are picked up as they were stored`() {
        val legacy = InMemoryKeyValueStore().apply {
            putString("provider", "OPENROUTER"); putString("openrouter_api_key", "or-key-1234567890")
            putString("openrouter_model", "google/gemma-4-31b-it:free"); putBoolean("auto_fallback", false)
        }
        val s = settings(legacy)
        assertEquals("openrouter", s.selectedProviderId)
        assertTrue(s.cloudActive)
        assertEquals("google/gemma-4-31b-it:free", s.model("openrouter"))
        assertFalse(s.autoFallback)
        s.selectedProviderId = null
        assertNull("an explicit choice overrides the legacy flag", s.selectedProviderId)
    }

    @Test fun `v0_1 local choice stays local`() {
        val legacy = InMemoryKeyValueStore().apply { putString("provider", "LOCAL") }
        assertNull(settings(legacy).selectedProviderId)
    }

    @Test fun `clearAll removes every secret and choice`() {
        val store = InMemoryKeyValueStore()
        val s = settings(store)
        s.setApiKey("zai", zaiKey); s.selectedProviderId = "zai"; s.thinking = true; s.lastModelUsed = "glm-4.7-flash"
        s.clearAll()
        assertNull(s.apiKey("zai")); assertNull(s.selectedProviderId); assertFalse(s.thinking); assertNull(s.lastModelUsed)
        assertNull(store.getString("zai_api_key"))
    }

    @Test fun `storage security flag is exposed for the warning banner`() {
        assertFalse(settings(InMemoryKeyValueStore(isSecure = false)).secureStorage)
        assertTrue(settings(InMemoryKeyValueStore(isSecure = true)).secureStorage)
    }

    @Test fun `the key is stored only in the injected store under its own entry`() {
        val writes = mutableListOf<Pair<String, String?>>()
        val spy = object : KeyValueStore by InMemoryKeyValueStore(true) {
            override fun putString(key: String, value: String?) { writes += key to value }
        }
        settings(spy).setApiKey("zai", zaiKey)
        assertEquals(listOf("zai_api_key" to zaiKey), writes)
    }
}
