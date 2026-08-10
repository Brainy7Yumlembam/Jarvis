package co.aura.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ClaudeProvider : AIProvider {
    override suspend fun generateResponse(prompt: String): String = "Claude Provider response."
    override fun streamResponse(prompt: String): Flow<String> = flow { emit("Claude Stream") }
    override suspend fun summarize(text: String): String = "Claude Summary"
    override suspend fun extractIntent(text: String): String = "{}"
}
