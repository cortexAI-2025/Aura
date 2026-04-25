package com.aura.core.domain.usecase

import com.aura.core.domain.model.Message
import com.aura.core.domain.model.MessageRole
import com.aura.core.domain.repository.ConversationRepository
import java.util.UUID
import javax.inject.Inject

class ProcessUserInputUseCase @Inject constructor(
    private val conversationRepository: ConversationRepository,
) {
    suspend operator fun invoke(userText: String): Message {
        val message = Message(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = userText,
        )
        conversationRepository.addMessage(message)
        return message
    }
}
