package com.aura.core.data.repository

import android.content.Context
import com.aura.core.database.dao.ActionLogDao
import com.aura.core.database.dao.ConversationDao
import com.aura.core.database.dao.MessageDao
import com.aura.core.database.dao.UsageRow
import com.aura.core.database.entity.ActionLogEntity
import com.aura.core.database.entity.ConversationEntity
import com.aura.core.database.entity.MessageEntity
import com.aura.core.domain.model.ActionResult
import com.aura.core.domain.model.Conversation
import com.aura.core.domain.model.Message
import com.aura.core.domain.model.MessageRole
import com.aura.core.domain.model.ModelUsage
import com.aura.core.domain.model.UsageTotals
import com.aura.core.domain.repository.ConversationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed conversations. One conversation is "current"; the v0.1 methods (addMessage, observeMessages…)
 * act on it so existing callers keep working. The current id lives in plain preferences (not a secret).
 */
@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val actionLogDao: ActionLogDao,
    @ApplicationContext context: Context,
) : ConversationRepository {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val current = MutableStateFlow(prefs.getString(KEY_CURRENT, null).orEmpty())
    private val lock = Mutex()

    // ── current conversation ────────────────────────────────────────────────

    override suspend fun currentConversationId(): String = lock.withLock {
        val id = current.value
        if (id.isNotEmpty() && conversationDao.get(id) != null) return id
        val fallback = conversationDao.mostRecentId() ?: insertConversation(null)
        setCurrent(fallback)
        fallback
    }

    override fun observeCurrentConversationId(): Flow<String> = current.map { it }.distinctUntilChanged()

    private fun setCurrent(id: String) {
        current.value = id
        prefs.edit().putString(KEY_CURRENT, id).apply()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMessages(limit: Int): Flow<List<Message>> =
        current.flatMapLatest { id ->
            // An empty id (first launch) yields no rows; the UI calls currentConversationId() to initialise it.
            messageDao.observeRecentIn(id, limit).map { rows -> rows.map { it.toDomain() } }
        }

    override suspend fun addMessage(message: Message) = addMessage(currentConversationId(), message)

    override suspend fun getRecentMessages(limit: Int): List<Message> = getRecentMessages(currentConversationId(), limit)

    override suspend fun clearHistory() = messageDao.deleteByConversation(currentConversationId())

    // ── any conversation ────────────────────────────────────────────────────

    override suspend fun addMessage(conversationId: String, message: Message) {
        val conversation = conversationDao.get(conversationId) ?: return // deleted meanwhile: drop quietly
        messageDao.insert(message.toEntity(conversationId))
        val title = if (conversation.title.isBlank() && message.role == MessageRole.USER) autoTitle(message.content) else null
        if (title != null) conversationDao.rename(conversationId, title, nowMs()) else conversationDao.touch(conversationId, nowMs())
    }

    override suspend fun getRecentMessages(conversationId: String, limit: Int): List<Message> =
        messageDao.getRecentIn(conversationId, limit).map { it.toDomain() }.reversed()

    override fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun createConversation(title: String?): String = lock.withLock {
        val id = insertConversation(title)
        setCurrent(id)
        id
    }

    override suspend fun selectConversation(id: String) {
        if (conversationDao.get(id) != null) setCurrent(id)
    }

    override suspend fun renameConversation(id: String, title: String) {
        val clean = title.trim().take(MAX_TITLE)
        if (clean.isNotEmpty()) conversationDao.rename(id, clean, nowMs())
    }

    override suspend fun deleteConversation(id: String) {
        messageDao.deleteByConversation(id)
        conversationDao.delete(id)
        if (current.value == id) setCurrent(conversationDao.mostRecentId().orEmpty())
    }

    override suspend fun deleteAllConversations() {
        messageDao.clear()
        conversationDao.deleteAll()
        setCurrent("")
    }

    override suspend fun logActionResult(result: ActionResult) = actionLogDao.insert(result.toEntity())

    // ── consumption ─────────────────────────────────────────────────────────

    override suspend fun usageTotals(conversationId: String?): UsageTotals =
        (if (conversationId == null) messageDao.usageAll() else messageDao.usageIn(conversationId)).toDomain()

    override fun observeUsageByModel(): Flow<List<ModelUsage>> =
        messageDao.observeUsageByModel().map { rows ->
            rows.map { ModelUsage(it.providerId, it.model, UsageTotals(it.prompt, it.completion, it.requests)) }
        }

    // ── helpers ─────────────────────────────────────────────────────────────

    private suspend fun insertConversation(title: String?): String {
        val id = UUID.randomUUID().toString()
        val now = nowMs()
        // A blank title means "name it after the first message".
        conversationDao.upsert(ConversationEntity(id, title?.trim()?.take(MAX_TITLE).orEmpty(), now, now))
        return id
    }

    private fun nowMs() = Instant.now().toEpochMilli()

    private fun autoTitle(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").take(AUTO_TITLE).trimEnd().ifBlank { "Conversation" }

    private companion object {
        const val PREFS = "aura_conversations"
        const val KEY_CURRENT = "current_conversation_id"
        const val MAX_TITLE = 80
        const val AUTO_TITLE = 40
    }
}

private fun UsageRow.toDomain() = UsageTotals(prompt, completion, requests)

private fun ConversationEntity.toDomain() = Conversation(
    id = id, title = title.ifBlank { "Nouvelle conversation" },
    createdAt = Instant.ofEpochMilli(createdAtMs), updatedAt = Instant.ofEpochMilli(updatedAtMs),
)

private fun Message.toEntity(conversationId: String) = MessageEntity(
    id = id, role = role.name, content = content,
    timestampMs = timestamp.toEpochMilli(), metadata = metadata.toString(),
    conversationId = conversationId, providerId = providerId, model = model,
    promptTokens = promptTokens, completionTokens = completionTokens, finishReason = finishReason,
)

private fun MessageEntity.toDomain() = Message(
    id = id, role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.ASSISTANT), content = content,
    timestamp = Instant.ofEpochMilli(timestampMs),
    providerId = providerId, model = model,
    promptTokens = promptTokens, completionTokens = completionTokens, finishReason = finishReason,
)

private fun ActionResult.toEntity() = ActionLogEntity(
    actionId = actionId, tool = tool.name, success = success,
    output = output, timestampMs = timestamp.toEpochMilli(),
)
