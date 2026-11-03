package com.aura.ai.engine

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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

    /**
     * MediaPipe 0.10.x registers the streaming result listener at creation time
     * (LlmInferenceOptions.setResultListener), not per call. We route it to the
     * listener of the single in-flight generation.
     */
    @Volatile private var activeListener: ((String, Boolean) -> Unit)? = null

    override suspend fun load(modelPath: String) {
        require(File(modelPath).exists()) {
            "Model not found at $modelPath. Download Gemma-2B from Kaggle and copy to device."
        }
        Timber.i("Loading LLM from $modelPath")
        val options = LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(2048)
            .setResultListener { partial, done -> activeListener?.invoke(partial ?: "", done) }
            .build()
        llmInference = LlmInference.createFromOptions(context, options)
        Timber.i("LLM ready")
    }

    /**
     * Stream generation. Each [LLMToken] carries the incremental text delta.
     */
    override fun generate(prompt: String, maxTokens: Int): Flow<LLMToken> = callbackFlow {
        val engine = checkNotNull(llmInference) { "Call load() before generate()" }
        activeListener = { partial, done ->
            trySend(LLMToken(partial, done))
            if (done) close()
        }
        engine.generateResponseAsync(prompt)
        awaitClose { activeListener = null }
    }

    override suspend fun generateFull(prompt: String, maxTokens: Int): String {
        val engine = checkNotNull(llmInference) { "Call load() before generateFull()" }
        return withContext(Dispatchers.Default) { engine.generateResponse(prompt) }
    }

    override suspend fun embed(text: String): FloatArray = embeddingEngine.embed(text)

    override fun release() {
        llmInference?.close()
        llmInference = null
    }
}
