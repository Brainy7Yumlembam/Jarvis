package co.aura.ai

import co.aura.domain.model.AuraError
import co.aura.security.SecurityManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LocalAIProvider(
    private val securityManager: SecurityManager
) : AIProvider {

    // Keep base url and model name configurable in code for future Wi-Fi/LAN laptop Ollama milestones
    suspend fun getBaseUrl(): String {
        return securityManager.getSecureToken("local_ai_base_url") ?: "http://localhost:11434"
    }

    suspend fun getModelName(): String {
        return securityManager.getSecureToken("local_ai_model_name") ?: "gemma3"
    }

    override suspend fun generateResponse(prompt: String): String {
        throw AuraError.AIRequestError("Local AI is not available on this device yet.")
    }

    override fun streamResponse(prompt: String): Flow<String> = flow {
        throw AuraError.AIRequestError("Local AI is not available on this device yet.")
    }

    override suspend fun summarize(text: String): String {
        throw AuraError.AIRequestError("Local AI is not available on this device yet.")
    }

    override suspend fun extractIntent(text: String): String {
        throw AuraError.AIRequestError("Local AI is not available on this device yet.")
    }
}
