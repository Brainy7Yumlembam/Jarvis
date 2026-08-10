package co.aura.domain.repository

import co.aura.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun getMessages(limit: Int): Flow<List<ChatMessage>>
    suspend fun saveMessage(message: ChatMessage)
    suspend fun clearHistory()
}
