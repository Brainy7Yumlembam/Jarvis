package co.aura.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class OpenAIProvider : AIProvider {
    override suspend fun generateResponse(prompt: String): String = "OpenAI Provider response."
    override fun streamResponse(prompt: String): Flow<String> = flow { emit("OpenAI Stream") }
    override suspend fun summarize(text: String): String = "OpenAI Summary"
    override suspend fun extractIntent(text: String): String = "{}"
}
