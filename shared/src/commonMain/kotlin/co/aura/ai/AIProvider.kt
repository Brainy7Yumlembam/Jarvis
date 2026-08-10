package co.aura.ai

import kotlinx.coroutines.flow.Flow

interface AIProvider {
    suspend fun generateResponse(prompt: String): String
    fun streamResponse(prompt: String): Flow<String>
    suspend fun summarize(text: String): String
    suspend fun extractIntent(text: String): String
}
