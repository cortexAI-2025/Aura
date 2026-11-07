package com.aura.ai.brain

/**
 * Prompt-injection defence for anything that is not the owner's own message: tool results, calendar
 * entries, SMS, screen text, web pages, even stored memories. Such text is *data*. It is sanitised
 * (control/bidi characters, chat-template tokens and forged fence tags removed, length capped) and
 * fenced, and the system prompt tells the model never to obey what is inside the fence.
 */
object UntrustedContent {
    const val TAG = "untrusted-data"

    private val invisible = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2069\\uFEFF]")
    private val chatTokens = Regex(
        "<\\|[^>\\n]{0,40}\\|>|<start_of_turn>|<end_of_turn>|\\[/?INST\\]|<</?SYS>>|</?s>",
        RegexOption.IGNORE_CASE,
    )
    private val forgedTag = Regex("<\\s*/?\\s*$TAG[^>]*>", RegexOption.IGNORE_CASE)
    private val manyBlankLines = Regex("\\n{4,}")

    fun sanitize(text: String, maxChars: Int = 2_000): String {
        var out = text.replace(invisible, "").replace(chatTokens, "").replace(forgedTag, "[balise retirée]")
        out = out.replace(manyBlankLines, "\n\n\n").trim()
        if (out.length > maxChars) out = out.take(maxChars).trimEnd() + " … [tronqué]"
        return out
    }

    fun wrap(source: String, text: String, maxChars: Int = 2_000): String {
        val safeSource = source.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }.ifEmpty { "external" }
        return "<$TAG source=\"$safeSource\">\n${sanitize(text, maxChars)}\n</$TAG>"
    }
}
