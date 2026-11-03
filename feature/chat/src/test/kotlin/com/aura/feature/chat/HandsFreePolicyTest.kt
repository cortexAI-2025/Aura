package com.aura.feature.chat

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreePolicyTest {

    @Test fun `silence restarts quietly then gives up after the retry limit`() {
        for (retries in 0 until HandsFreePolicy.MAX_RETRIES) {
            assertEquals(
                ListenOutcome.Restart(300),
                HandsFreePolicy.onRecognizerError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, retries),
            )
        }
        val out = HandsFreePolicy.onRecognizerError(SpeechRecognizer.ERROR_NO_MATCH, HandsFreePolicy.MAX_RETRIES)
        assertTrue(out is ListenOutcome.Stop)
    }

    @Test fun `busy recogniser backs off before retrying`() {
        assertEquals(
            ListenOutcome.Restart(1_000),
            HandsFreePolicy.onRecognizerError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, 0),
        )
        assertTrue(
            HandsFreePolicy.onRecognizerError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, HandsFreePolicy.MAX_RETRIES)
                is ListenOutcome.Stop,
        )
    }

    @Test fun `permission audio and network errors stop immediately with a message`() {
        listOf(
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        ).forEach { code ->
            val out = HandsFreePolicy.onRecognizerError(code, 0)
            assertTrue("code $code", out is ListenOutcome.Stop && out.message.isNotBlank())
        }
    }

    @Test fun `unknown error stops and mentions the code`() {
        val out = HandsFreePolicy.onRecognizerError(999, 0) as ListenOutcome.Stop
        assertTrue(out.message.contains("999"))
    }
}
