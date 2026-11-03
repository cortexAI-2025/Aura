package com.aura.ai.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class OpenRouterModel(
    val id: String,
    val name: String,
    val contextLength: Int = 0,
    /** ISO date after which the model is withdrawn, if announced. */
    val expires: String? = null,
)

/** Live list of free OpenRouter models (public endpoint, no API key needed). */
@Singleton
class OpenRouterCatalog @Inject constructor() {

    suspend fun fetchFreeModels(): Result<List<OpenRouterModel>> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(OpenRouterProtocol.MODELS_ENDPOINT).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                OpenRouterProtocol.parseFreeModels(body).also { check(it.isNotEmpty()) { "aucun modèle gratuit trouvé" } }
            } finally {
                conn.disconnect()
            }
        }
    }

    companion object {
        /** Verified free text models (3 Nov 2026), used until the live list is fetched. */
        val FALLBACK = listOf(
            OpenRouterModel("google/gemma-4-31b-it:free", "Google: Gemma 4 31B (free)", 262_144),
            OpenRouterModel("google/gemma-4-26b-a4b-it:free", "Google: Gemma 4 26B A4B (free)", 262_144),
            OpenRouterModel("nvidia/nemotron-3.5-lightning:free", "NVIDIA: Nemotron 3.5 Lightning (free)", 1_000_000),
            OpenRouterModel("nvidia/nemotron-3-ultra-550b-a55b:free", "NVIDIA: Nemotron 3 Ultra (free)", 1_000_000),
            OpenRouterModel("xingchen-agi/xing4.0-29b-a4b:free", "XingChen: Xing4.0 29B A4B (free)", 262_144),
        )
    }
}
