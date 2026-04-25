package com.aura.ai.engine

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import timber.log.Timber
import java.io.FileInputStream
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlin.math.min

/**
 * On-device text embedding engine using MiniLM-L6-v2 (INT8 quantised, d=384).
 *
 * Asset requirements (place both files under app/src/main/assets/models/):
 *   - minilm-l6-v2-int8.tflite   (~23 MB) — the TFLite model
 *   - bert_vocab.txt              (~200 KB) — bert-base-uncased vocabulary
 *
 * Download script (run once in project root):
 *   python3 scripts/export_minilm.py
 * or manually from HuggingFace:
 *   huggingface-cli download sentence-transformers/all-MiniLM-L6-v2 \
 *     --include "*.tflite" "vocab.txt"
 *
 * Inference pipeline:
 *   text → BasicTokenize → WordPiece → [CLS] t1 t2 … [SEP] [PAD]…
 *       → TFLite(input_ids, attention_mask, token_type_ids)
 *       → last_hidden_state [1, 128, 384]
 *       → mean-pool over unpadded positions
 *       → L2-normalise
 *       → FloatArray(384)
 *
 * Fallback: if the model asset is absent, [embedStub] returns a deterministic
 * pseudo-embedding so all other components work without the binary file.
 */
@Singleton
class EmbeddingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val EMBEDDING_DIM = 384
        const val MAX_SEQ_LEN = 128
        private const val MODEL_ASSET = "models/minilm-l6-v2-int8.tflite"
        private const val VOCAB_ASSET = "models/bert_vocab.txt"
    }

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var tokenizer: WordPieceTokenizer? = null
    @Volatile private var gpuDelegate: GpuDelegate? = null

    /** Thread-safe lazy initialisation — called once on first [embed] call. */
    private fun ensureInitialized(): Boolean {
        if (interpreter != null) return true
        synchronized(this) {
            if (interpreter != null) return true
            return try {
                val modelBuffer = loadModelBuffer() ?: return false
                val vocabLines = loadVocab() ?: return false
                tokenizer = WordPieceTokenizer(vocabLines)
                interpreter = buildInterpreter(modelBuffer)
                Timber.i("EmbeddingEngine ready (MiniLM-L6-v2 INT8, seq=$MAX_SEQ_LEN, d=$EMBEDDING_DIM)")
                true
            } catch (e: Exception) {
                Timber.e(e, "EmbeddingEngine init failed — using stub")
                false
            }
        }
    }

    suspend fun embed(text: String): FloatArray {
        if (!ensureInitialized()) return embedStub(text)
        return try {
            embedTFLite(text)
        } catch (e: Exception) {
            Timber.e(e, "TFLite inference failed — falling back to stub")
            embedStub(text)
        }
    }

    private fun embedTFLite(text: String): FloatArray {
        val tok = checkNotNull(tokenizer)
        val interp = checkNotNull(interpreter)
        val tokens = tok.tokenize(text, MAX_SEQ_LEN)

        // Inputs: three [1 × MAX_SEQ_LEN] int arrays
        val inputIds = Array(1) { tokens.inputIds }
        val attentionMask = Array(1) { tokens.attentionMask }
        val tokenTypeIds = Array(1) { tokens.tokenTypeIds }

        // Output: [1, MAX_SEQ_LEN, EMBEDDING_DIM] — last hidden state
        val outputBuffer = Array(1) { Array(MAX_SEQ_LEN) { FloatArray(EMBEDDING_DIM) } }

        interp.runForMultipleInputsOutputs(
            arrayOf(inputIds, attentionMask, tokenTypeIds),
            mapOf(0 to outputBuffer),
        )

        // Mean-pool over the non-padded token positions (ignore [CLS] and [SEP] for stability)
        val actualLen = tokens.actualLength
        val poolStart = 1                          // skip [CLS]
        val poolEnd = (actualLen - 1).coerceAtLeast(poolStart + 1)  // up to but not incl. [SEP]
        val poolSize = poolEnd - poolStart

        val embedding = FloatArray(EMBEDDING_DIM) { dim ->
            var sum = 0f
            for (pos in poolStart until poolEnd) sum += outputBuffer[0][pos][dim]
            sum / poolSize
        }

        return l2Normalize(embedding)
    }

    private fun buildInterpreter(model: MappedByteBuffer): Interpreter {
        val options = Interpreter.Options().apply {
            numThreads = 4
            // Prefer GPU delegate for faster inference (falls back to CPU automatically)
            if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate().also { addDelegate(it) }
                Timber.i("EmbeddingEngine: GPU delegate enabled")
            }
        }
        return Interpreter(model, options)
    }

    private fun loadModelBuffer(): MappedByteBuffer? = try {
        context.assets.openFd(MODEL_ASSET).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
            )
        }
    } catch (e: Exception) {
        Timber.w("MiniLM model asset not found at $MODEL_ASSET — embedding will use stub")
        null
    }

    private fun loadVocab(): List<String>? = try {
        context.assets.open(VOCAB_ASSET).bufferedReader().readLines()
    } catch (e: Exception) {
        Timber.w("BERT vocab asset not found at $VOCAB_ASSET — embedding will use stub")
        null
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var norm = 0f
        for (v in vec) norm += v * v
        norm = sqrt(norm)
        if (norm > 1e-9f) for (i in vec.indices) vec[i] /= norm
        return vec
    }

    /**
     * Fallback stub — produces a consistent pseudo-embedding from character bigrams.
     * Semantically similar texts will NOT cluster together, but the full pipeline
     * (VectorStore, RAG, MemoryManager) operates end-to-end without a model file.
     */
    private fun embedStub(text: String): FloatArray {
        val vec = FloatArray(EMBEDDING_DIM)
        val lower = text.lowercase()
        for (i in 0 until min(lower.length - 1, EMBEDDING_DIM)) {
            val a = lower[i].code.toFloat()
            val b = lower[i + 1].code.toFloat()
            vec[i % EMBEDDING_DIM] += (a * 31f + b) / 10000f
        }
        return l2Normalize(vec)
    }

    fun release() {
        synchronized(this) {
            interpreter?.close(); interpreter = null
            gpuDelegate?.close(); gpuDelegate = null
        }
    }
}
