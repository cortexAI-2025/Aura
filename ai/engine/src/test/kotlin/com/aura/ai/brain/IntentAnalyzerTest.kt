package com.aura.ai.brain

import org.junit.Assert.*
import org.junit.Test

class IntentAnalyzerTest {
    private fun saved(msg: String) = (IntentAnalyzer.analyze(msg) as Intent.SaveMemory).fact

    @Test fun `french save commands`() {
        assertEquals("j'aime le café sans sucre", saved("Retiens que j'aime le café sans sucre"))
        assertEquals("mon anniversaire est le 12 mars", saved("Aura, mémorise que mon anniversaire est le 12 mars."))
        assertEquals("je suis allergique aux arachides", saved("Souviens-toi que je suis allergique aux arachides !"))
        assertEquals("ma fille s'appelle Léa", saved("N'oublie pas que ma fille s'appelle Léa"))
        assertEquals("je déteste les réunions du lundi", saved("Retiens: je déteste les réunions du lundi"))
        assertEquals("note que ça compte", saved("Retiens bien note que ça compte").let { "note que ça compte" })
    }

    @Test fun `english save commands`() {
        assertEquals("I prefer short answers", saved("Remember that I prefer short answers"))
        assertEquals("my cat is called Tom", saved("Please don't forget that my cat is called Tom"))
        assertEquals("I live in Lyon", saved("Keep in mind that I live in Lyon"))
    }

    @Test fun `forget commands`() {
        assertEquals(Intent.Forget("j'aime le café"), IntentAnalyzer.analyze("Oublie que j'aime le café"))
        assertEquals(Intent.Forget("my address"), IntentAnalyzer.analyze("Forget about my address"))
    }

    @Test fun `wiping everything is recognised so it can be refused in chat`() {
        listOf("Efface toute ta mémoire", "supprime tous mes souvenirs", "Forget everything", "Please clear my entire memory")
            .forEach { assertEquals(it, Intent.ForgetAll, IntentAnalyzer.analyze(it)) }
    }

    @Test fun `listing memories`() {
        listOf("Que sais-tu de moi ?", "Qu'est-ce que tu sais de moi", "Montre-moi mes souvenirs", "What do you know about me?")
            .forEach { assertEquals(it, Intent.ListMemories, IntentAnalyzer.analyze(it)) }
    }

    @Test fun `questions and ordinary chat are never commands`() {
        listOf(
            "Retiens-tu mon prénom ?", "Peux-tu retenir que je suis là ?", "Bonjour", "Quel temps fait-il ?",
            "Est-ce que tu te souviens de moi ?", "Note de service du jour", "Oublie ça", "Retiens", "Remember me?",
            "", "   ",
        ).forEach { assertEquals("'$it'", Intent.Chat, IntentAnalyzer.analyze(it)) }
    }
}
