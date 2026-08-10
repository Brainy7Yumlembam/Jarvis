package co.aura.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DeepSeekProvider : AIProvider {
    override suspend fun generateResponse(prompt: String): String = "DeepSeek Provider response."
    override fun streamResponse(prompt: String): Flow<String> = flow { emit("DeepSeek Stream") }
    override suspend fun summarize(text: String): String = "DeepSeek Summary"
    override suspend fun extractIntent(text: String): String = "{}"
}
