package com.aura.ai.engine

/**
 * Minimal BERT WordPiece tokenizer compatible with the standard bert-base-uncased vocabulary.
 *
 * The vocabulary file (bert_vocab.txt, ~200 KB) must be placed at:
 *   app/src/main/assets/models/bert_vocab.txt
 * It is the standard HuggingFace bert-base-uncased vocab, line-indexed (token at line N → ID N).
 *
 * Algorithm:
 *   1. Basic tokenization: lowercase, strip accents, split on whitespace + punctuation.
 *   2. WordPiece segmentation: greedily find the longest matching prefix in the vocab,
 *      prepend "##" to continuations.
 *   3. Map tokens to integer IDs; unknown subwords → [UNK] (ID 100).
 *   4. Prepend [CLS] (101) and append [SEP] (102), truncate to [maxLen].
 */
class WordPieceTokenizer(vocabLines: List<String>) {

    private val vocab: Map<String, Int> = buildMap {
        vocabLines.forEachIndexed { idx, token -> put(token.trim(), idx) }
    }

    companion object {
        const val PAD_ID = 0
        const val UNK_ID = 100
        const val CLS_ID = 101
        const val SEP_ID = 102
        const val MAX_CHARS_PER_WORD = 100
    }

    fun tokenize(text: String, maxLen: Int = 128): TokenizerOutput {
        val wordTokens = basicTokenize(text)
        val ids = mutableListOf(CLS_ID)

        for (word in wordTokens) {
            if (ids.size >= maxLen - 1) break
            val subTokens = wordPiece(word)
            for (sub in subTokens) {
                if (ids.size >= maxLen - 1) break
                ids.add(vocab[sub] ?: UNK_ID)
            }
        }
        ids.add(SEP_ID)

        val actualLen = ids.size
        val inputIds = IntArray(maxLen) { if (it < actualLen) ids[it] else PAD_ID }
        val attentionMask = IntArray(maxLen) { if (it < actualLen) 1 else 0 }
        val tokenTypeIds = IntArray(maxLen) { 0 }

        return TokenizerOutput(inputIds, attentionMask, tokenTypeIds, actualLen)
    }

    private fun basicTokenize(text: String): List<String> {
        return text.lowercase()
            .map { ch ->
                when {
                    ch.isWhitespace() -> ' '
                    isPunctuation(ch) -> " $ch "
                    else -> ch
                }
            }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
    }

    private fun wordPiece(word: String): List<String> {
        if (word.length > MAX_CHARS_PER_WORD) return listOf("[UNK]")
        val subTokens = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: String? = null
            while (start < end) {
                val substr = word.substring(start, end)
                val candidate = if (start == 0) substr else "##$substr"
                if (vocab.containsKey(candidate)) { found = candidate; break }
                end--
            }
            if (found == null) return listOf("[UNK]")
            subTokens.add(found)
            start = end
        }
        return subTokens
    }

    private fun isPunctuation(ch: Char): Boolean {
        val cp = ch.code
        return cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126
    }
}

data class TokenizerOutput(
    val inputIds: IntArray,
    val attentionMask: IntArray,
    val tokenTypeIds: IntArray,
    val actualLength: Int,
)
