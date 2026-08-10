package co.aura.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import co.aura.core.database.DatabaseHelper
import co.aura.domain.model.ChatMessage
import co.aura.domain.model.MessageSender
import co.aura.domain.model.MessageStatus
import co.aura.domain.repository.ConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ConversationRepositoryImpl(
    private val databaseHelper: DatabaseHelper
) : ConversationRepository {
    
    override fun getMessages(limit: Int): Flow<List<ChatMessage>> {
        return databaseHelper.chatMessageQueries
            .getMessages(limit.toLong())
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { list ->
                list.map { entity ->
                    ChatMessage(
                        id = entity.id,
                        sessionId = entity.session_id,
                        sender = MessageSender.valueOf(entity.sender),
                        content = entity.content,
                        timestamp = entity.timestamp,
                        status = MessageStatus.valueOf(entity.status)
                    )
                }
            }
    }

    override suspend fun saveMessage(message: ChatMessage) {
        databaseHelper.chatMessageQueries.insertMessage(
            id = message.id,
            session_id = message.sessionId,
            sender = message.sender.name,
            content = message.content,
            timestamp = message.timestamp,
            status = message.status.name
        )
    }

    override suspend fun clearHistory() {
        databaseHelper.chatMessageQueries.clearHistory()
    }
}
