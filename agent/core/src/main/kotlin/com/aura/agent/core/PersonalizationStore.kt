package com.aura.agent.core

import android.content.Context
import com.aura.core.domain.model.DetailLevel
import com.aura.core.domain.model.PersonalizationProfile
import com.aura.core.domain.model.ProfileProvider
import com.aura.core.domain.model.Tone
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Local, transparent and reversible assistant personalization. Nothing here is a secret and nothing leaves the device by itself. */
@Singleton
class PersonalizationStore @Inject constructor(
    @ApplicationContext context: Context,
) : ProfileProvider {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val profile: StateFlow<PersonalizationProfile> = state.asStateFlow()

    override fun current(): PersonalizationProfile = state.value

    fun update(transform: (PersonalizationProfile) -> PersonalizationProfile) {
        val next = transform(state.value).sanitized()
        prefs.edit()
            .putString("name", next.assistantName).putString("language", next.language)
            .putString("tone", next.tone.name).putString("detail", next.detail.name)
            .putString("instructions", next.customInstructions)
            .putBoolean("auto_memory", next.autoMemory).putBoolean("use_memory", next.useMemory)
            .putBoolean("share_memory", next.shareMemoryWithCloud).putBoolean("share_goals", next.shareGoalsWithCloud)
            .apply()
        state.value = next
    }

    /** Back to the factory behaviour. */
    fun reset() {
        prefs.edit().clear().apply()
        state.value = PersonalizationProfile.DEFAULT
    }

    private fun load(): PersonalizationProfile {
        val d = PersonalizationProfile.DEFAULT
        return PersonalizationProfile(
            assistantName = prefs.getString("name", d.assistantName) ?: d.assistantName,
            language = prefs.getString("language", d.language) ?: d.language,
            tone = runCatching { Tone.valueOf(prefs.getString("tone", null) ?: "") }.getOrDefault(d.tone),
            detail = runCatching { DetailLevel.valueOf(prefs.getString("detail", null) ?: "") }.getOrDefault(d.detail),
            customInstructions = prefs.getString("instructions", "") ?: "",
            autoMemory = prefs.getBoolean("auto_memory", d.autoMemory),
            useMemory = prefs.getBoolean("use_memory", d.useMemory),
            shareMemoryWithCloud = prefs.getBoolean("share_memory", d.shareMemoryWithCloud),
            shareGoalsWithCloud = prefs.getBoolean("share_goals", d.shareGoalsWithCloud),
        ).sanitized()
    }

    private companion object { const val FILE = "aura_profile" }
}
