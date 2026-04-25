package com.aura.ai.engine

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device LLM inference via MediaPipe Tasks GenAI.
 *
 * Supported models (place in /data/user/0/com.aura.app/files/models/):
 *   - gemma-2b-it-gpu-int4.bin  (recommended: ~1.4 GB, NPU/GPU-accelerated)
 *   - gemma-2b-it-cpu-int4.bin  (fallback: ~1.4 GB, CPU-only)
 *
 * Download from: https://www.kaggle.com/models/google/gemma/frameworks/tfLite
 */
@Singleton
class MediaPipeLLM @Inject constructor(
    @ApplicationContext private val context: Context,
    private val embeddingEngine: EmbeddingEngine,
) : LLMEngine {

    private var llmInference: LlmInference? = null
    override val isReady: Boolean get() = llmInference != null

    override suspend fun load(modelPath: String) {
        require(File(modelPath).exists()) {
            "Model not found at $modelPath. Download Gemma-2B from Kaggle and copy to device."
        }
        Timber.i("Loading LLM from $modelPath")
        val options = LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(2048)
            // Prefer GPU/NPU — falls back to CPU automatically on unsupported hardware
            .setPreferredBackend(LlmInferenceOptions.Backend.GPU)
            .build()
        llmInference = LlmInference.createFromOptions(context, options)
        Timber.i("LLM ready")
    }

    /**
     * Stream generation using MediaPipe's async callback.
     * Each [LLMToken] carries the incremental text delta.
     */
    override fun generate(prompt: String, maxTokens: Int): Flow<LLMToken> = callbackFlow {
        val engine = checkNotNull(llmInference) { "Call load() before generate()" }
        engine.generateResponseAsync(prompt) { partialResult, done ->
            val token = LLMToken(partialResult ?: "", done)
            trySend(token)
            if (done) close()
        }
        awaitClose { /* MediaPipe manages its own lifecycle */ }
    }

    override suspend fun generateFull(prompt: String, maxTokens: Int): String {
        val engine = checkNotNull(llmInference) { "Call load() before generateFull()" }
        return suspendCancellableCoroutine { cont ->
            val sb = StringBuilder()
            engine.generateResponseAsync(prompt) { partial, done ->
                if (partial != null) sb.append(partial)
                if (done) cont.resume(sb.toString())
            }
        }
    }

    override suspend fun embed(text: String): FloatArray = embeddingEngine.embed(text)

    override fun release() {
        llmInference?.close()
        llmInference = null
    }
}
