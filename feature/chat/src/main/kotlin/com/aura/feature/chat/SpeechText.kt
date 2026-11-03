package com.aura.feature.chat

/** Prepares an agent reply for text-to-speech (no markdown noise, bounded length). */
internal object SpeechText {
    /** TextToSpeech rejects inputs longer than getMaxSpeechInputLength() (4000). */
    const val MAX_CHARS = 3_900

    fun clean(text: String): String =
        text
            .replace(Regex("```[\\s\\S]*?```"), " ")      // code blocks are unreadable aloud
            .replace(Regex("https?://\\S+"), " lien ")     // don't spell out URLs
            .replace(Regex("[*_#`>~|]"), "")               // markdown markers
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_CHARS)
}
