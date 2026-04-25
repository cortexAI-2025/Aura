package com.aura.core.data.repository

import com.aura.core.database.dao.ActionLogDao
import com.aura.core.database.dao.MessageDao
import com.aura.core.database.entity.ActionLogEntity
import com.aura.core.database.entity.MessageEntity
import com.aura.core.domain.model.ActionResult
import com.aura.core.domain.model.Message
import com.aura.core.domain.model.MessageRole
import com.aura.core.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject

class ConversationRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val actionLogDao: ActionLogDao,
) : ConversationRepository {

    override suspend fun addMessage(message: Message) =
        messageDao.insert(message.toEntity())

    override fun observeMessages(limit: Int): Flow<List<Message>> =
        messageDao.observeRecent(limit).map { it.map { e -> e.toDomain() } }

    override suspend fun getRecentMessages(limit: Int): List<Message> =
        messageDao.getRecent(limit).map { it.toDomain() }

    override suspend fun logActionResult(result: ActionResult) =
        actionLogDao.insert(result.toEntity())

    override suspend fun clearHistory() = messageDao.clear()
}

private fun Message.toEntity() = MessageEntity(
    id = id, role = role.name, content = content,
    timestampMs = timestamp.toEpochMilli(), metadata = metadata.toString(),
)

private fun MessageEntity.toDomain() = Message(
    id = id, role = MessageRole.valueOf(role), content = content,
    timestamp = Instant.ofEpochMilli(timestampMs),
)

private fun ActionResult.toEntity() = ActionLogEntity(
    actionId = actionId, tool = tool.name, success = success,
    output = output, timestampMs = timestamp.toEpochMilli(),
)
