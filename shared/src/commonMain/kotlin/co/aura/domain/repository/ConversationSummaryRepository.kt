package co.aura.domain.repository

interface ConversationSummaryRepository {
    suspend fun getSummary(sessionId: String = "session_default"): String?
    suspend fun saveSummary(sessionId: String = "session_default", summary: String, lastMessageId: String? = null)
    suspend fun clearSummary(sessionId: String = "session_default")
}
