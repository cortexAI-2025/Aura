package com.aura.ai.brain

sealed interface Intent {
    /** Ordinary conversation: the model decides everything. */
    data object Chat : Intent

    /** "Retiens que…": store [fact] in memory. */
    data class SaveMemory(val fact: String) : Intent

    /** "Oublie que…": delete the memory best matching [topic]. */
    data class Forget(val topic: String) : Intent

    /** "Efface toute ta mémoire": never executed from chat, the user is pointed to the Memory screen. */
    data object ForgetAll : Intent

    /** "Que sais-tu de moi ?" */
    data object ListMemories : Intent
}

/**
 * Rule-based reading of the owner's own message (never of tool output). It only recognises explicit
 * memory commands; everything else is left to the model: no pretend understanding.
 */
object IntentAnalyzer {
    private val opts = setOf(RegexOption.IGNORE_CASE)
    private val addressee = "(?:\\p{L}[\\p{L}\\p{N}_-]{1,23}\\s*[,:]\\s+)?"

    private val forgetAll = listOf(
        Regex("\\b(?:efface|supprime|vide|oublie|réinitialise|reinitialise)\\b.{0,20}\\b(?:toute|tout|tous|toutes)\\b.{0,25}\\b(?:mémoire|memoire|souvenirs)\\b", opts),
        Regex("\\bforget\\s+everything\\b", opts),
        Regex("\\b(?:clear|wipe|erase|delete)\\b.{0,15}\\b(?:your|my|the)\\s+(?:whole\\s+|entire\\s+)?memor(?:y|ies)\\b", opts),
    )

    private val list = listOf(
        Regex("\\b(?:que\\s+sais[- ]tu|qu'est[- ]ce\\s+que\\s+tu\\s+sais|qu'as[- ]tu\\s+retenu|que\\s+retiens[- ]tu)\\b.{0,25}\\b(?:moi|de\\s+moi)\\b", opts),
        Regex("\\b(?:montre|affiche|liste)(?:[- ]moi)?\\b.{0,15}\\b(?:mes\\s+souvenirs|ma\\s+mémoire|ma\\s+memoire)\\b", opts),
        Regex("\\bwhat\\s+do\\s+you\\s+(?:know|remember)\\s+about\\s+me\\b", opts),
        Regex("\\bshow\\s+(?:me\\s+)?my\\s+memor(?:y|ies)\\b", opts),
    )

    private val save = listOf(
        Regex("^$addressee(?:retiens|mémorise|memorise|souviens[- ]toi|rappelle[- ]toi|garde\\s+en\\s+mémoire|garde\\s+en\\s+memoire)(?:\\s+bien)?\\s*(?:que\\s+|qu'|:\\s*|,\\s*|\\s)\\s*(.{4,})$", opts + RegexOption.DOT_MATCHES_ALL),
        Regex("^$addressee(?:n'oublie\\s+pas|noublie\\s+pas|note|enregistre)\\s+(?:que\\s+|qu')(.{4,})$", opts + RegexOption.DOT_MATCHES_ALL),
        Regex("^$addressee(?:please\\s+)?(?:remember|keep\\s+in\\s+mind|don'?t\\s+forget|note)\\s+(?:that\\s+|:\\s*)(.{4,})$", opts + RegexOption.DOT_MATCHES_ALL),
    )

    private val forget = listOf(
        Regex("^$addressee(?:oublie|oublier)\\s+(?:que\\s+|qu'|le\\s+fait\\s+que\\s+|ce\\s+que\\s+)(.{4,})$", opts + RegexOption.DOT_MATCHES_ALL),
        Regex("^$addressee(?:please\\s+)?forget\\s+(?:that\\s+|about\\s+)(.{4,})$", opts + RegexOption.DOT_MATCHES_ALL),
    )

    fun analyze(message: String): Intent {
        val text = message.trim()
        if (text.isEmpty()) return Intent.Chat
        if (forgetAll.any { it.containsMatchIn(text) }) return Intent.ForgetAll
        if (list.any { it.containsMatchIn(text) }) return Intent.ListMemories
        // A question is never a command ("Retiens-tu mon prénom ?").
        if (text.endsWith("?")) return Intent.Chat
        for (r in save) r.find(text)?.let { m -> clean(m.groupValues[1])?.let { return Intent.SaveMemory(it) } }
        for (r in forget) r.find(text)?.let { m -> clean(m.groupValues[1])?.let { return Intent.Forget(it) } }
        return Intent.Chat
    }

    private fun clean(raw: String): String? =
        raw.trim().trim('.', '!', ' ', '\n').takeIf { it.length >= 4 }
}
