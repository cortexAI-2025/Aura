package com.aura.feature.chat

import org.junit.Assert.*
import org.junit.Test

class SpeechTextTest {
    @Test fun `strips markdown markers and collapses whitespace`() {
        assertEquals("Titre Bonjour monde", SpeechText.clean("# Titre\n\n**Bonjour**   _monde_"))
    }

    @Test fun `drops code blocks and replaces urls`() {
        val out = SpeechText.clean("Voir https://example.com/a?b=1 puis ```val x = 1``` fin")
        assertEquals("Voir lien puis fin", out)
    }

    @Test fun `truncates to the TTS input limit`() {
        assertEquals(SpeechText.MAX_CHARS, SpeechText.clean("a".repeat(10_000)).length)
    }

    @Test fun `blank input stays blank`() {
        assertEquals("", SpeechText.clean("  \n "))
    }
}
