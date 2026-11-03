package com.aura.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenRouterCatalogParsingTest {

    private fun model(
        id: String, name: String = id, prompt: String = "0", completion: String = "0",
        outputs: String = "[\"text\"]", inputs: String = "[\"text\",\"image\"]", expires: String = "null",
    ) = """{"id":"$id","name":"$name","context_length":1000,
        "pricing":{"prompt":"$prompt","completion":"$completion"},
        "architecture":{"input_modalities":$inputs,"output_modalities":$outputs},
        "expiration_date":$expires}"""

    @Test fun `keeps only free text chat models and sorts by name`() {
        val json = """{"data":[
            ${model("b/free", "Zeta")},
            ${model("a/free", "Alpha")},
            ${model("c/paid", prompt = "0.0000001")},
            ${model("d/music", outputs = "[\"text\",\"audio\"]")},
            ${model("e/content-safety:free")},
            ${model("f/old:free", expires = "\"2026-11-01\"")},
            ${model("g/soon:free", name = "Soon", expires = "\"2026-12-31\"")}
        ]}"""
        val ids = OpenRouterProtocol.parseFreeModels(json, today = "2026-11-03").map { it.id }
        assertEquals(listOf("a/free", "g/soon:free", "b/free"), ids)
    }

    @Test fun `expiry date is exposed`() {
        val json = """{"data":[${model("g/soon:free", expires = "\"2026-12-31\"")}]}"""
        assertEquals("2026-12-31", OpenRouterProtocol.parseFreeModels(json, today = "2026-11-03").single().expires)
    }

    @Test fun `empty or malformed payload gives an empty list`() {
        assertEquals(emptyList<OpenRouterModel>(), OpenRouterProtocol.parseFreeModels("""{"data":[]}"""))
        assertEquals(emptyList<OpenRouterModel>(), OpenRouterProtocol.parseFreeModels("""{}"""))
    }
}
