package co.aura.ai

import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import co.aura.domain.model.AuraError
import co.aura.security.SecurityManager
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GeminiPart(val text: String? = null)

@Serializable
data class GeminiContent(val parts: List<GeminiPart>? = null)

@Serializable
data class GeminiRequest(val contents: List<GeminiContent>)

@Serializable
data class GeminiCandidate(val content: GeminiContent? = null)

@Serializable
data class GeminiResponse(val candidates: List<GeminiCandidate>? = null)

@Serializable
data class GeminiErrorDetails(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null
)

@Serializable
data class GeminiErrorResponse(
    val error: GeminiErrorDetails? = null
)

class GeminiProvider(
    private val httpClient: HttpClient,
    private val modelConfig: GeminiModelConfig,
    private val geminiCredentialManager: GeminiCredentialManager,
    private val json: Json
) : AIProvider {

    constructor(
        httpClient: HttpClient,
        modelConfig: GeminiModelConfig,
        securityManager: co.aura.security.SecurityManager,
        json: Json
    ) : this(httpClient, modelConfig, GeminiCredentialManager(securityManager), json)

    override suspend fun generateResponse(prompt: String): String {
        var retries = 0
        var lastException: Exception? = null
        while (retries < 10) {
            val credential = geminiCredentialManager.getBestAvailableCredential()
            if (credential == null) {
                AuraLogger.e(LogCategory.AI, "No available Gemini credentials configured or all are in cooldown/invalid state.")
                if (lastException != null) throw lastException
                throw AuraError.AIRequestError("No available Gemini credentials configured.")
            }

            val slot = credential.slot
            val apiKey = credential.apiKey ?: ""
            val label = credential.label

            if (apiKey.isBlank()) {
                geminiCredentialManager.removeCredential(slot)
                retries++
                continue
            }

            geminiCredentialManager.setActiveSlot(slot)
            val formattedSlot = if (slot < 10) "0$slot" else "$slot"
            AuraLogger.i(LogCategory.AI, "Gemini Project $formattedSlot selected.")

            val url = "https://generativelanguage.googleapis.com/v1beta/models/${modelConfig.modelName}:generateContent?key=$apiKey"
            val requestBody = GeminiRequest(
                contents = listOf(
                    GeminiContent(parts = listOf(GeminiPart(text = prompt)))
                )
            )

            try {
                val response = httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
                val statusCode = response.status.value
                val responseText = response.bodyAsText()
                
                if (statusCode in 200..299) {
                    AuraLogger.i(LogCategory.AI, "Gemini Project $formattedSlot request successful.")
                    val geminiResponse = try {
                        json.decodeFromString<GeminiResponse>(responseText)
                    } catch (e: Exception) {
                        AuraLogger.e(LogCategory.AI, "Malformed successful Gemini response JSON: $responseText", e)
                        throw AuraError.AIResponseError("Failed to parse Gemini response.")
                    }
                    
                    val text = geminiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    if (text.isNullOrBlank()) {
                        AuraLogger.e(LogCategory.AI, "Gemini response text content is null or blank: $responseText")
                        throw AuraError.AIResponseError("Received empty response from Gemini.")
                    }
                    
                    geminiCredentialManager.setActiveSlot(null)
                    return text
                } else {
                    val errorResponse = try {
                        json.decodeFromString<GeminiErrorResponse>(responseText)
                    } catch (e: Exception) {
                        null
                    }
                    val errorDetails = errorResponse?.error
                    val errorMsg = errorDetails?.message ?: "HTTP Error $statusCode"

                    if (statusCode == 429) {
                        val retryAfterHeader = response.headers["Retry-After"]
                        val cooldownMs = retryAfterHeader?.toLongOrNull()?.times(1000) ?: 300000L
                        val mapped = mapHttpError(statusCode, errorMsg)
                        lastException = mapped
                        AuraLogger.w(LogCategory.AI, "Gemini Project $formattedSlot rate limited. Trying next project.")
                        geminiCredentialManager.markCooldown(slot, cooldownMs)
                        geminiCredentialManager.setActiveSlot(null)
                        retries++
                        continue
                    } else if (statusCode == 401) {
                        val mapped = mapHttpError(statusCode, errorMsg)
                        lastException = mapped
                        AuraLogger.w(LogCategory.AI, "Gemini Project $formattedSlot API key unauthorized. Trying next project.")
                        geminiCredentialManager.markInvalid(slot)
                        geminiCredentialManager.setActiveSlot(null)
                        retries++
                        continue
                    } else if (statusCode == 403) {
                        val isInvalid = errorDetails?.status == "API_KEY_INVALID" || 
                                        errorMsg.contains("api key not valid", ignoreCase = true) ||
                                        errorMsg.contains("invalid api key", ignoreCase = true) ||
                                        errorMsg.contains("api key is invalid", ignoreCase = true)
                        
                        if (isInvalid) {
                            val mapped = mapHttpError(statusCode, errorMsg)
                            lastException = mapped
                            AuraLogger.w(LogCategory.AI, "Gemini Project $formattedSlot API key invalid (403). Trying next project.")
                            geminiCredentialManager.markInvalid(slot)
                            geminiCredentialManager.setActiveSlot(null)
                            retries++
                            continue
                        } else {
                            geminiCredentialManager.setActiveSlot(null)
                            throw mapHttpError(statusCode, errorMsg)
                        }
                    } else {
                        geminiCredentialManager.setActiveSlot(null)
                        throw mapHttpError(statusCode, errorMsg)
                    }
                }
            } catch (e: RedirectResponseException) {
                geminiCredentialManager.setActiveSlot(null)
                throw e
            } catch (e: ClientRequestException) {
                val statusCode = e.response.status.value
                val responseText = e.response.bodyAsText()
                val errorResponse = try {
                    json.decodeFromString<GeminiErrorResponse>(responseText)
                } catch (ex: Exception) {
                    null
                }
                val errorDetails = errorResponse?.error
                val errorMsg = errorDetails?.message ?: "HTTP Error $statusCode"

                if (statusCode == 429) {
                    val retryAfterHeader = e.response.headers["Retry-After"]
                    val cooldownMs = retryAfterHeader?.toLongOrNull()?.times(1000) ?: 300000L
                    val mapped = mapHttpError(statusCode, errorMsg)
                    lastException = mapped
                    AuraLogger.w(LogCategory.AI, "Gemini Project $formattedSlot rate limited (ClientRequestException). Trying next project.")
                    geminiCredentialManager.markCooldown(slot, cooldownMs)
                    geminiCredentialManager.setActiveSlot(null)
                    retries++
                    continue
                } else if (statusCode == 401) {
                    val mapped = mapHttpError(statusCode, errorMsg)
                    lastException = mapped
                    AuraLogger.w(LogCategory.AI, "Gemini Project $formattedSlot API key unauthorized (ClientRequestException). Trying next project.")
                    geminiCredentialManager.markInvalid(slot)
                    geminiCredentialManager.setActiveSlot(null)
                    retries++
                    continue
                } else if (statusCode == 403) {
                    val isInvalid = errorDetails?.status == "API_KEY_INVALID" || 
                                    errorMsg.contains("api key not valid", ignoreCase = true) ||
                                    errorMsg.contains("invalid api key", ignoreCase = true) ||
                                    errorMsg.contains("api key is invalid", ignoreCase = true)
                    
                    if (isInvalid) {
                        val mapped = mapHttpError(statusCode, errorMsg)
                        lastException = mapped
                        AuraLogger.w(LogCategory.AI, "Gemini Project $formattedSlot API key invalid (ClientRequestException 403). Trying next project.")
                        geminiCredentialManager.markInvalid(slot)
                        geminiCredentialManager.setActiveSlot(null)
                        retries++
                        continue
                    } else {
                        geminiCredentialManager.setActiveSlot(null)
                        throw mapHttpError(statusCode, errorMsg)
                    }
                } else {
                    geminiCredentialManager.setActiveSlot(null)
                    throw mapHttpError(statusCode, errorMsg)
                }
            } catch (e: ServerResponseException) {
                geminiCredentialManager.setActiveSlot(null)
                val statusCode = e.response.status.value
                val responseText = e.response.bodyAsText()
                val errorMsg = parseErrorMessage(responseText, statusCode)
                throw mapHttpError(statusCode, errorMsg)
            } catch (e: AuraError) {
                geminiCredentialManager.setActiveSlot(null)
                throw e
            } catch (e: Exception) {
                geminiCredentialManager.setActiveSlot(null)
                AuraLogger.e(LogCategory.AI, "Network or system error during Gemini call: ${e.message}", e)
                throw AuraError.AINetworkError("Unable to reach. Check your internet connection.")
            }
        }

        if (lastException != null) throw lastException
        throw AuraError.AIRequestError("All configured Gemini credentials exhausted or unavailable.")
    }

    override fun streamResponse(prompt: String): Flow<String> = flow {
        emit(generateResponse(prompt))
    }

    override suspend fun summarize(text: String): String {
        return generateResponse("Summarize this text: $text")
    }

    override suspend fun extractIntent(text: String): String {
        return generateResponse("Extract action intent from this query in JSON format: $text")
    }

    private fun parseErrorMessage(responseText: String, statusCode: Int): String {
        return try {
            val errorResponse = json.decodeFromString<GeminiErrorResponse>(responseText)
            val message = errorResponse.error?.message
            if (message.isNullOrBlank()) "HTTP Error $statusCode" else sanitizeErrorMessage(message)
        } catch (e: Exception) {
            "HTTP Error $statusCode"
        }
    }

    private fun sanitizeErrorMessage(message: String): String {
        return message.replace(Regex("AIzaSy[a-zA-Z0-9_-]{33}"), "[REDACTED_API_KEY]")
    }

    private fun mapHttpError(statusCode: Int, message: String): Exception {
        val sanitizedMsg = sanitizeErrorMessage(message)
        AuraLogger.e(LogCategory.AI, "Gemini HTTP error status: $statusCode. Message: $sanitizedMsg")
        return when (statusCode) {
            401, 403 -> AuraError.AIRequestError("Gemini API authentication failed. Check your API key.")
            429 -> AuraError.AIRateLimitError("Gemini API rate limit reached. Please try again later.")
            400 -> AuraError.AIRequestError("Gemini request failed: $sanitizedMsg")
            in 500..599 -> AuraError.AIRequestError("Gemini server error. Please try again later.")
            else -> AuraError.AINetworkError("Unable to reach. Check your internet connection.")
        }
    }
}
