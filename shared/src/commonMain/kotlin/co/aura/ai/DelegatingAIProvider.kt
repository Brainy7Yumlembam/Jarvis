package co.aura.ai

import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import co.aura.security.SecurityManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DelegatingAIProvider(
    private val geminiProvider: GeminiProvider,
    private val localAiProvider: LocalAIProvider,
    private val securityManager: SecurityManager
) : AIProvider {

    private suspend fun getActiveProvider(): AIProvider {
        val providerType = securityManager.getSecureToken("ai_provider_type") ?: "GEMINI"
        return if (providerType == "LOCAL") {
            // Local AI is not available in Milestone A, fall back to Gemini
            AuraLogger.w(LogCategory.AI, "Local AI unavailable. Falling back to Gemini.")
            geminiProvider
        } else {
            geminiProvider
        }
    }

    override suspend fun generateResponse(prompt: String): String {
        return getActiveProvider().generateResponse(prompt)
    }

    override fun streamResponse(prompt: String): Flow<String> = flow {
        val provider = getActiveProvider()
        provider.streamResponse(prompt).collect { emit(it) }
    }

    override suspend fun summarize(text: String): String {
        return getActiveProvider().summarize(text)
    }

    override suspend fun extractIntent(text: String): String {
        return getActiveProvider().extractIntent(text)
    }
}
