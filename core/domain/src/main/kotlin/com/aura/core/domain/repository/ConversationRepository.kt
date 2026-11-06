package com.aura.core.domain.repository

import com.aura.core.domain.model.ActionResult
import com.aura.core.domain.model.Conversation
import com.aura.core.domain.model.Message
import com.aura.core.domain.model.ModelUsage
import com.aura.core.domain.model.UsageTotals
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    // ── current conversation (the v0.1 API, unchanged for existing callers) ──
    suspend fun addMessage(message: Message)
    fun observeMessages(limit: Int = 50): Flow<List<Message>>
    suspend fun getRecentMessages(limit: Int = 20): List<Message>
    suspend fun logActionResult(result: ActionResult)

    /** Deletes the messages of the CURRENT conversation (the conversation itself stays). */
    suspend fun clearHistory()

    // ── v0.2: several conversations ──
    suspend fun addMessage(conversationId: String, message: Message)
    suspend fun getRecentMessages(conversationId: String, limit: Int): List<Message>
    fun observeConversations(): Flow<List<Conversation>>
    fun observeCurrentConversationId(): Flow<String>
    suspend fun currentConversationId(): String

    /** Creates a conversation and makes it the current one. */
    suspend fun createConversation(title: String? = null): String
    suspend fun selectConversation(id: String)
    suspend fun renameConversation(id: String, title: String)
    suspend fun deleteConversation(id: String)
    suspend fun deleteAllConversations()

    // ── consumption ──
    suspend fun usageTotals(conversationId: String? = null): UsageTotals
    fun observeUsageByModel(): Flow<List<ModelUsage>>
}
