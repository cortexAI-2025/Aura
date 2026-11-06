package com.aura.core.domain.model

enum class Tone { NEUTRAL, FRIENDLY, FORMAL, CONCISE }

enum class DetailLevel { BRIEF, BALANCED, DETAILED }

/**
 * Everything the owner can tune about the assistant. Local, transparent (each field is shown in the
 * settings screen) and reversible ([DEFAULT] restores the factory behaviour).
 */
data class PersonalizationProfile(
    val assistantName: String = "Aura",
    /** `auto` follows the device language, otherwise a primary language tag such as `fr` or `en`. */
    val language: String = "auto",
    val tone: Tone = Tone.FRIENDLY,
    val detail: DetailLevel = DetailLevel.BALANCED,
    /** Free text written by the user ("tutoie-moi", "réponds en listes"…). Capped by [MAX_INSTRUCTIONS]. */
    val customInstructions: String = "",
    /** Automatically memorise a short summary of exchanges. OFF by default: memory is explicit. */
    val autoMemory: Boolean = false,
    /** Look up memories to enrich answers. */
    val useMemory: Boolean = true,
    /** Allow memory excerpts to be sent to a cloud provider (privacy). */
    val shareMemoryWithCloud: Boolean = true,
    /** Allow active goals to be sent to a cloud provider (privacy). */
    val shareGoalsWithCloud: Boolean = true,
) {
    companion object {
        const val MAX_INSTRUCTIONS = 500
        const val MAX_NAME = 24
        val DEFAULT = PersonalizationProfile()
    }

    fun sanitized(): PersonalizationProfile = copy(
        assistantName = assistantName.trim().take(MAX_NAME).ifBlank { DEFAULT.assistantName },
        customInstructions = customInstructions.trim().take(MAX_INSTRUCTIONS),
        language = language.trim().lowercase().ifBlank { "auto" },
    )
}

fun interface ProfileProvider {
    fun current(): PersonalizationProfile
}
