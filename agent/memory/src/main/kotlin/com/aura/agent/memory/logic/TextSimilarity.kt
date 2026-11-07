package com.aura.agent.memory.logic

import java.text.Normalizer

/** Lexical similarity that works without any model: accent-insensitive, stop-word aware, prefix tolerant (FR + EN). */
object TextSimilarity {
    private val stopwords = (
        "le la les un une des de du d l au aux et ou a à en dans sur pour par avec sans ce cet cette ces mon ma mes ton ta tes " +
            "son sa ses notre nos votre vos leur leurs je tu il elle on nous vous ils elles me te se y est suis es sont etre être " +
            "avoir ai as avons ont que qu qui ne pas plus tres très aussi mais donc si comme c j m n s t " +
            "the a an and or of to in on for with is are was be i you he she we they my your his her our their it this that " +
            "these those at by from as am do does"
        ).split(' ').filter { it.isNotBlank() }.toHashSet()

    private val marks = Regex("\\p{M}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(marks, "")

    /**
     * Stop words are matched on the accented lowercase form first, so French "thé" (tea) is not mistaken
     * for the English stop word "the"; unaccented forms still match ("etre", "tres").
     */
    private fun isStopword(raw: String, folded: String): Boolean =
        raw in stopwords || (folded == raw && folded in stopwords)

    fun tokens(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFC).split(nonWord)
            .asSequence()
            .map { raw -> raw to normalize(raw) }
            .filter { (raw, folded) ->
                folded.isNotEmpty() && !isStopword(raw, folded) && (folded.length >= 2 || folded.all(Char::isDigit))
            }
            .map { it.second }
            .distinct()
            .toList()

    /** Equal, or one is a prefix of the other (≥ 4 chars): "preference" ~ "preferences", "prefere" ~ "preferences". */
    private fun same(a: String, b: String): Boolean =
        a == b || (minOf(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a)))

    private fun matched(a: List<String>, b: List<String>): Int = a.count { x -> b.any { y -> same(x, y) } }

    /** Share of the query's words found in [doc]: how well [doc] answers the query (0..1). */
    fun relevance(query: String, doc: String): Double {
        val q = tokens(query)
        val d = tokens(doc)
        if (q.isEmpty() || d.isEmpty()) return 0.0
        val m = matched(q, d)
        val coverage = m.toDouble() / q.size
        val dice = 2.0 * m / (q.size + d.size)
        return 0.8 * coverage + 0.2 * dice
    }

    /** Symmetric overlap (Sørensen–Dice) used to spot duplicates and near-duplicates (0..1). */
    fun overlap(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return 2.0 * matched(ta, tb) / (ta.size + tb.size)
    }
}
