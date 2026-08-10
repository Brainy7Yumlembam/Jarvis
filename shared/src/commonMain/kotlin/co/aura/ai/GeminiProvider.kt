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
data class GeminiPart(val text: String)

@Serializable
data class GeminiContent(val parts: List<GeminiPart>)

@Serializable
data class GeminiRequest(val contents: List<GeminiContent>)

@Serializable
data class GeminiCandidate(val content: GeminiContent)

@Serializable
data class GeminiResponse(val candidates: List<GeminiCandidate>)

class GeminiProvider(
    private val httpClient: HttpClient,
    private val modelConfig: GeminiModelConfig,
    private val securityManager: SecurityManager,
    private val json: Json
) : AIProvider {

    override suspend fun generateResponse(prompt: String): String {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            AuraLogger.e(LogCategory.AI, "Gemini API key is empty.")
            throw AuraError.AIRequestError("API key is not configured.")
        }

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
            val responseText = response.bodyAsText()
            val geminiResponse = json.decodeFromString<GeminiResponse>(responseText)
            val responseContent = geminiResponse.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (responseContent.isNullOrBlank()) {
                throw AuraError.AIResponseError("Received empty response from Gemini.")
            }
            return responseContent
        } catch (e: RedirectResponseException) {
            val code = e.response.status.value
            throw mapHttpError(code, e.message)
        } catch (e: ClientRequestException) {
            val code = e.response.status.value
            throw mapHttpError(code, e.message)
        } catch (e: ServerResponseException) {
            val code = e.response.status.value
            throw mapHttpError(code, e.message)
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.AI, "Network or system error during Gemini call", e)
            throw AuraError.AINetworkError("Network communication failed: ${e.message}")
        }
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

    private suspend fun getApiKey(): String {
        // 1. Check Security Manager
        val storedKey = securityManager.getSecureToken("gemini_api_key")
        if (!storedKey.isNullOrBlank()) return storedKey

        // 2. Check System property or Environment Variable
        val envKey = System.getenv("GEMINI_API_KEY")
        if (!envKey.isNullOrBlank()) return envKey

        val propKey = System.getProperty("GEMINI_API_KEY")
        if (!propKey.isNullOrBlank()) return propKey

        return ""
    }

    private fun mapHttpError(statusCode: Int, message: String): Exception {
        AuraLogger.e(LogCategory.AI, "Gemini HTTP Error: $statusCode - $message")
        return when (statusCode) {
            429 -> AuraError.AIRateLimitError("Gemini API Rate limit exceeded.")
            400, 401, 403, 404 -> AuraError.AIRequestError("Invalid Gemini request details (Code $statusCode).")
            else -> AuraError.AINetworkError("Gemini Server Error (Code $statusCode).")
        }
    }
}
