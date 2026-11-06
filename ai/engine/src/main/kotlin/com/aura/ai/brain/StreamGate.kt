package com.aura.ai.brain

/**
 * Decides, while a reply streams in, whether it is something to show the user. A reply that starts
 * with `{` (or a ```json fence) is a structured tool call: showing its raw JSON live would be noise.
 */
class StreamGate {
    private val buffer = StringBuilder()
    private var decision: Boolean? = null // true = visible, false = structured

    val isStructured: Boolean get() = decision == false

    /** @return the text that may be shown now (possibly buffered text released at the decision point). */
    fun accept(delta: String): String {
        decision?.let { return if (it) delta else "" }
        buffer.append(delta)
        val t = buffer.trimStart()
        if (t.isEmpty()) return ""
        val structured = when {
            t.startsWith("{") -> true
            t.startsWith("```json", ignoreCase = true) -> true
            t.length < FENCE.length && FENCE.startsWith(t.toString().lowercase()) -> return "" // could still become a json fence
            else -> false
        }
        decision = !structured
        return if (structured) "" else buffer.trimStart().toString()
    }

    private companion object { const val FENCE = "```json" }
}
