package co.aura.data.repository

import co.aura.core.database.DatabaseHelper
import co.aura.domain.repository.ConversationSummaryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ConversationSummaryRepositoryImpl(
    private val databaseHelper: DatabaseHelper
) : ConversationSummaryRepository {

    override suspend fun getSummary(sessionId: String): String? = withContext(Dispatchers.Default) {
        try {
            databaseHelper.chatMessageQueries.getSummary(sessionId).executeAsOneOrNull()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun saveSummary(sessionId: String, summary: String, lastMessageId: String?) = withContext(Dispatchers.Default) {
        try {
            databaseHelper.chatMessageQueries.insertOrUpdateSummary(
                session_id = sessionId,
                summary = summary,
                last_summarized_message_id = lastMessageId,
                updated_at = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            // Log and fail gracefully
        }
    }

    override suspend fun clearSummary(sessionId: String) = withContext(Dispatchers.Default) {
        try {
            databaseHelper.chatMessageQueries.clearSummary(sessionId)
        } catch (e: Exception) {
            // Ignore
        }
    }
}
