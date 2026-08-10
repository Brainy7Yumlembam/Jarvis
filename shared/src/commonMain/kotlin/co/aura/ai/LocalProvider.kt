package co.aura.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LocalProvider : AIProvider {
    override suspend fun generateResponse(prompt: String): String = "Local LLM response."
    override fun streamResponse(prompt: String): Flow<String> = flow { emit("Local LLM Stream") }
    override suspend fun summarize(text: String): String = "Local Summary"
    override suspend fun extractIntent(text: String): String = "{}"
}
