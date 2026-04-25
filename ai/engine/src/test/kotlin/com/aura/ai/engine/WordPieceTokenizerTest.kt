package com.aura.ai.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WordPieceTokenizerTest {

    /**
     * Minimal vocabulary for testing — mirrors the structure of bert-base-uncased vocab.
     * Real vocab has ~30k entries; we use a tiny subset covering our test sentences.
     */
    private val vocabLines = listOf(
        "[PAD]",         // 0
        "[unused0]",     // 1 … (simulate gap)
        "[unused1]",
        "[unused2]",
        "[unused3]",
        "[unused4]",
        "[unused5]",
        "[unused6]",
        "[unused7]",
        "[unused8]",
        "[unused9]",
        "[unused10]",
        "[unused11]",
        "[unused12]",
        "[unused13]",
        "[unused14]",
        "[unused15]",
        "[unused16]",
        "[unused17]",
        "[unused18]",
        "[unused19]",
        "[unused20]",
        "[unused21]",
        "[unused22]",
        "[unused23]",
        "[unused24]",
        "[unused25]",
        "[unused26]",
        "[unused27]",
        "[unused28]",
        "[unused29]",
        "[unused30]",
        "[unused31]",
        "[unused32]",
        "[unused33]",
        "[unused34]",
        "[unused35]",
        "[unused36]",
        "[unused37]",
        "[unused38]",
        "[unused39]",
        "[unused40]",
        "[unused41]",
        "[unused42]",
        "[unused43]",
        "[unused44]",
        "[unused45]",
        "[unused46]",
        "[unused47]",
        "[unused48]",
        "[unused49]",
        "[unused50]",
        "[unused51]",
        "[unused52]",
        "[unused53]",
        "[unused54]",
        "[unused55]",
        "[unused56]",
        "[unused57]",
        "[unused58]",
        "[unused59]",
        "[unused60]",
        "[unused61]",
        "[unused62]",
        "[unused63]",
        "[unused64]",
        "[unused65]",
        "[unused66]",
        "[unused67]",
        "[unused68]",
        "[unused69]",
        "[unused70]",
        "[unused71]",
        "[unused72]",
        "[unused73]",
        "[unused74]",
        "[unused75]",
        "[unused76]",
        "[unused77]",
        "[unused78]",
        "[unused79]",
        "[unused80]",
        "[unused81]",
        "[unused82]",
        "[unused83]",
        "[unused84]",
        "[unused85]",
        "[unused86]",
        "[unused87]",
        "[unused88]",
        "[unused89]",
        "[unused90]",
        "[unused91]",
        "[unused92]",
        "[unused93]",
        "[unused94]",
        "[unused95]",
        "[unused96]",
        "[unused97]",
        "[unused98]",
        "[UNK]",         // 100
        "[CLS]",         // 101
        "[SEP]",         // 102
        "hello",         // 103
        "world",         // 104
        "aura",          // 105
        "##ing",         // 106  (continuation subword)
        "test",          // 107
    )

    private lateinit var tokenizer: WordPieceTokenizer

    @Before fun setUp() { tokenizer = WordPieceTokenizer(vocabLines) }

    @Test fun `output always starts with CLS and ends with SEP`() {
        val out = tokenizer.tokenize("hello world")
        assertEquals(WordPieceTokenizer.CLS_ID, out.inputIds[0])
        val sep = out.inputIds.take(out.actualLength).last()
        assertEquals(WordPieceTokenizer.SEP_ID, sep)
    }

    @Test fun `known tokens are mapped to correct IDs`() {
        val out = tokenizer.tokenize("hello world")
        // [CLS]=101, hello=103, world=104, [SEP]=102
        assertEquals(WordPieceTokenizer.CLS_ID, out.inputIds[0])
        assertEquals(103, out.inputIds[1])
        assertEquals(104, out.inputIds[2])
        assertEquals(WordPieceTokenizer.SEP_ID, out.inputIds[3])
    }

    @Test fun `unknown tokens map to UNK`() {
        val out = tokenizer.tokenize("xyznotinvocab")
        // Should contain UNK_ID (100) between CLS and SEP
        val ids = out.inputIds.take(out.actualLength)
        assertTrue(ids.contains(WordPieceTokenizer.UNK_ID))
    }

    @Test fun `attention mask is 1 for real tokens and 0 for padding`() {
        val out = tokenizer.tokenize("hello")
        // Positions 0..actualLength-1 → mask=1; rest → mask=0
        for (i in 0 until out.actualLength) assertEquals(1, out.attentionMask[i])
        for (i in out.actualLength until out.inputIds.size) assertEquals(0, out.attentionMask[i])
    }

    @Test fun `output is padded to maxLen`() {
        val out = tokenizer.tokenize("hello", maxLen = 16)
        assertEquals(16, out.inputIds.size)
        assertEquals(16, out.attentionMask.size)
    }

    @Test fun `long text is truncated to maxLen`() {
        val longText = "aura ".repeat(200)
        val out = tokenizer.tokenize(longText, maxLen = 32)
        assertEquals(32, out.inputIds.size)
        assertTrue(out.actualLength <= 32)
    }

    @Test fun `token type ids are all zero for single-segment input`() {
        val out = tokenizer.tokenize("hello world")
        assertTrue(out.tokenTypeIds.all { it == 0 })
    }
}
