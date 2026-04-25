package com.aura.core.domain.repository

import com.aura.core.domain.model.ActionResult
import com.aura.core.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    suspend fun addMessage(message: Message)
    fun observeMessages(limit: Int = 50): Flow<List<Message>>
    suspend fun getRecentMessages(limit: Int = 20): List<Message>
    suspend fun logActionResult(result: ActionResult)
    suspend fun clearHistory()
}
