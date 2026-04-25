package com.aura.ai.engine

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Lightweight text embedding engine for semantic memory indexing.
 *
 * Uses a quantised MiniLM-L6 (d=384) TFLite model shipped with the app.
 * Model asset: assets/models/minilm-l6-v2-int8.tflite (~23 MB)
 *
 * In the current MVP we ship a deterministic hash-based stub so the rest of
 * the stack can be developed and tested without the binary asset. Replace
 * [embedStub] with [embedTFLite] once the model asset is available.
 */
@Singleton
class EmbeddingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val EMBEDDING_DIM = 384
        private const val MODEL_ASSET = "models/minilm-l6-v2-int8.tflite"
    }

    suspend fun embed(text: String): FloatArray {
        return if (isTFLiteModelAvailable()) embedTFLite(text) else embedStub(text)
    }

    private fun isTFLiteModelAvailable(): Boolean = try {
        context.assets.open(MODEL_ASSET).close(); true
    } catch (_: Exception) { false }

    /**
     * Production path — runs MiniLM via TFLite Interpreter.
     * Requires adding `org.tensorflow:tensorflow-lite:2.16.1` to this module.
     */
    private fun embedTFLite(text: String): FloatArray {
        // TODO: initialise TFLite interpreter, tokenize text with WordPiece,
        //       run inference, L2-normalise output. See:
        //       https://www.tensorflow.org/lite/inference_with_metadata/task_library/text_embedder
        Timber.w("TFLite embedding not yet implemented; falling back to stub")
        return embedStub(text)
    }

    /**
     * Deterministic stub — produces a consistent pseudo-embedding from the text's
     * character bigrams. Useful for development/testing: semantically similar texts
     * will NOT have high cosine similarity, but the vector store pipeline works end-to-end.
     */
    private fun embedStub(text: String): FloatArray {
        val vec = FloatArray(EMBEDDING_DIM)
        val lower = text.lowercase()
        for (i in 0 until min(lower.length - 1, EMBEDDING_DIM)) {
            val a = lower[i].code.toFloat()
            val b = lower[i + 1].code.toFloat()
            vec[i % EMBEDDING_DIM] += (a * 31f + b) / 10000f
        }
        // L2 normalise
        var norm = 0f
        for (v in vec) norm += v * v
        norm = Math.sqrt(norm.toDouble()).toFloat()
        if (norm > 0f) for (i in vec.indices) vec[i] /= norm
        return vec
    }
}
