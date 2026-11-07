package com.aura.agent.core

import com.aura.agent.memory.logic.MemoryService
import com.aura.ai.settings.AiSettings
import com.aura.core.domain.repository.ConversationRepository
import javax.inject.Inject
import javax.inject.Singleton

/** "Effacer mes données": each part can be erased on its own, or everything at once. Local data only. */
@Singleton
class UserDataWiper @Inject constructor(
    private val conversations: ConversationRepository,
    private val memory: MemoryService,
    private val personalization: PersonalizationStore,
    private val aiSettings: AiSettings,
) {
    suspend fun eraseConversations() = conversations.deleteAllConversations()
    suspend fun eraseMemory() = memory.deleteAll()
    fun resetPersonalization() = personalization.reset()

    /** Removes every API key, the provider choice and the model choices. */
    fun eraseKeysAndAiSettings() = aiSettings.clearAll()

    suspend fun eraseAll() {
        eraseConversations()
        eraseMemory()
        resetPersonalization()
        eraseKeysAndAiSettings()
    }
}
