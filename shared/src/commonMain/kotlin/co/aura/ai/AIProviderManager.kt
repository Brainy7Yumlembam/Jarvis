package co.aura.ai

import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import co.aura.domain.model.AuraError
import co.aura.security.SecurityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Orchestrates AI generation and streaming across multiple providers (Gemini, NVIDIA NIM, Local AI)
 * with robust, bounded failover and mid-stream safety guarantees.
 */
class AIProviderManager(
    private val geminiProvider: GeminiProvider,
    private val nvidiaProvider: NvidiaProvider,
    private val securityManager: SecurityManager,
    private val localAiProvider: LocalAIProvider? = null
) : AIProvider {

    private enum class ProviderId {
        GEMINI,
        NVIDIA
    }

    private data class ProviderEntry(
        val id: ProviderId,
        val name: String,
        val provider: AIProvider,
        val isAvailable: suspend () -> Boolean
    )

    private fun getBoundedProviders(preferredType: String): List<ProviderEntry> {
        val geminiEntry = ProviderEntry(
            id = ProviderId.GEMINI,
            name = "Gemini",
            provider = geminiProvider,
            isAvailable = { geminiProvider.hasAvailableCredentials() }
        )

        val nvidiaEntry = ProviderEntry(
            id = ProviderId.NVIDIA,
            name = "NVIDIA",
            provider = nvidiaProvider,
            isAvailable = { nvidiaProvider.isAvailable() }
        )

        return if (preferredType.equals("NVIDIA", ignoreCase = true)) {
            listOf(nvidiaEntry, geminiEntry)
        } else {
            listOf(geminiEntry, nvidiaEntry)
        }
    }

    override suspend fun generateResponse(prompt: String): String {
        val preferredStr = securityManager.getSecureToken("ai_provider_type") ?: "GEMINI"
        val providerSequence = getBoundedProviders(preferredStr)
        var lastException: Exception? = null

        for ((index, entry) in providerSequence.withIndex()) {
            val available = try { entry.isAvailable() } catch (e: Exception) { false }
            if (!available) {
                if (entry.id == ProviderId.GEMINI) {
                    AuraLogger.w(LogCategory.AI, "[AI] Gemini credential unavailable")
                    AuraLogger.w(LogCategory.AI, "[AI] Gemini provider exhausted")
                } else {
                    AuraLogger.w(LogCategory.AI, "[AI] NVIDIA credential unavailable or in cooldown")
                }
                continue
            }

            if (index > 0) {
                AuraLogger.i(LogCategory.AI, "[AI] Switching provider: ${entry.name}")
            }
            AuraLogger.i(LogCategory.AI, "[AI] Provider selected: ${entry.name}")

            try {
                return entry.provider.generateResponse(prompt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                AuraLogger.w(LogCategory.AI, "[AI] ${entry.name} request failed: ${e.message}")
                if (entry.id == ProviderId.GEMINI) {
                    AuraLogger.w(LogCategory.AI, "[AI] Gemini provider exhausted")
                }
            }
        }

        if (lastException != null) throw lastException
        throw AuraError.AIRequestError("All configured AI providers are currently exhausted or unavailable.")
    }

    override fun streamResponse(prompt: String): Flow<String> = flow {
        val preferredStr = securityManager.getSecureToken("ai_provider_type") ?: "GEMINI"
        val providerSequence = getBoundedProviders(preferredStr)
        var hasEmittedAnyChunk = false
        var lastException: Exception? = null

        for ((index, entry) in providerSequence.withIndex()) {
            val available = try { entry.isAvailable() } catch (e: Exception) { false }
            if (!available) {
                if (entry.id == ProviderId.GEMINI) {
                    AuraLogger.w(LogCategory.AI, "[AI] Gemini credential unavailable")
                    AuraLogger.w(LogCategory.AI, "[AI] Gemini provider exhausted")
                } else {
                    AuraLogger.w(LogCategory.AI, "[AI] NVIDIA credential unavailable or in cooldown")
                }
                continue
            }

            if (index > 0) {
                AuraLogger.i(LogCategory.AI, "[AI] Switching provider: ${entry.name}")
            }
            AuraLogger.i(LogCategory.AI, "[AI] Provider selected: ${entry.name}")

            try {
                entry.provider.streamResponse(prompt).collect { chunk ->
                    hasEmittedAnyChunk = true
                    emit(chunk)
                }
                // Successfully completed stream
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (hasEmittedAnyChunk) {
                    // Mid-stream safety rule: Never retry with another provider once content has started streaming
                    AuraLogger.w(
                        LogCategory.AI,
                        "[AI] Mid-stream failure during ${entry.name} emission. Not switching provider to prevent duplicate speech."
                    )
                    throw e
                }

                AuraLogger.w(LogCategory.AI, "[AI] ${entry.name} provider failed before response content: ${e.message}")
                if (entry.id == ProviderId.GEMINI) {
                    AuraLogger.w(LogCategory.AI, "[AI] Gemini provider exhausted")
                }
            }
        }

        if (lastException != null) throw lastException
        throw AuraError.AIRequestError("All configured AI providers are currently exhausted or unavailable.")
    }

    override suspend fun summarize(text: String): String {
        return generateResponse("Summarize this text: $text")
    }

    override suspend fun extractIntent(text: String): String {
        return generateResponse("Extract action intent from this query in JSON format: $text")
    }
}
