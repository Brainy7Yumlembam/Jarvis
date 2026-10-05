package co.aura.ai

import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import co.aura.domain.model.AuraError
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class NvidiaChatRequest(
    val model: String,
    val messages: List<NvidiaChatMessage>,
    val temperature: Double = 0.6,
    val top_p: Double = 0.9,
    val max_tokens: Int = 1024,
    val stream: Boolean = false
)

@Serializable
internal data class NvidiaChatMessage(
    val role: String,
    val content: String
)

@Serializable
internal data class NvidiaChatResponse(
    val id: String? = null,
    val choices: List<NvidiaChoice>? = null,
    val error: NvidiaErrorDetail? = null
)

@Serializable
internal data class NvidiaChoice(
    val index: Int? = null,
    val message: NvidiaChatMessage? = null,
    val delta: NvidiaDelta? = null,
    val finish_reason: String? = null
)

@Serializable
internal data class NvidiaDelta(
    val content: String? = null
)

@Serializable
internal data class NvidiaErrorResponse(
    val error: NvidiaErrorDetail? = null,
    val detail: String? = null
)

@Serializable
internal data class NvidiaErrorDetail(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)

/**
 * NVIDIA NIM (Inference Microservices) provider implementing [AIProvider].
 *
 * Provides OpenAI-compatible chat completions with Server-Sent Events (SSE) streaming,
 * robust error classification, and non-sensitive logging.
 */
class NvidiaProvider(
    private val httpClient: HttpClient,
    private val credentialManager: NvidiaCredentialManager,
    private val json: Json
) : AIProvider {

    suspend fun isAvailable(): Boolean {
        return credentialManager.isAvailable()
    }

    override suspend fun generateResponse(prompt: String): String {
        val apiKey = credentialManager.getApiKey()
        if (apiKey.isNullOrBlank()) {
            throw AuraError.AIRequestError("No NVIDIA API key configured.")
        }
        if (!credentialManager.isAvailable()) {
            throw AuraError.AIRateLimitError("NVIDIA API is currently in cooldown or unavailable.")
        }

        val model = credentialManager.getModel()
        val baseUrl = credentialManager.getBaseUrl()
        val endpoint = "$baseUrl/chat/completions"

        val request = NvidiaChatRequest(
            model = model,
            messages = listOf(NvidiaChatMessage(role = "user", content = prompt)),
            stream = false
        )

        AuraLogger.i(LogCategory.AI, "[AI] NVIDIA request started")

        try {
            var responseText = ""
            var statusCode = 200

            httpClient.preparePost(endpoint) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(request)
            }.execute { response ->
                statusCode = response.status.value
                responseText = response.bodyAsText()
            }

            if (statusCode in 200..299) {
                val responseObj = json.decodeFromString<NvidiaChatResponse>(responseText)
                val content = responseObj.choices?.firstOrNull()?.message?.content
                    ?: throw AuraError.AIRequestError("Empty response from NVIDIA NIM.")
                AuraLogger.i(LogCategory.AI, "[AI] NVIDIA request successful")
                return content
            } else {
                throw handleHttpError(statusCode, responseText, model)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuraError) {
            throw e
        } catch (e: ClientRequestException) {
            val status = e.response.status.value
            val text = try { e.response.bodyAsText() } catch (ex: Exception) { "" }
            throw handleHttpError(status, text, model)
        } catch (e: ServerResponseException) {
            val status = e.response.status.value
            val text = try { e.response.bodyAsText() } catch (ex: Exception) { "" }
            throw handleHttpError(status, text, model)
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.AI, "[AI] NVIDIA network error: ${sanitizeErrorMessage(e.message ?: "")}")
            throw AuraError.AINetworkError("Unable to reach NVIDIA AI. Check your internet connection.")
        }
    }

    override fun streamResponse(prompt: String): Flow<String> = flow {
        val apiKey = credentialManager.getApiKey()
        if (apiKey.isNullOrBlank()) {
            throw AuraError.AIRequestError("No NVIDIA API key configured.")
        }
        if (!credentialManager.isAvailable()) {
            throw AuraError.AIRateLimitError("NVIDIA API is currently in cooldown or unavailable.")
        }

        val model = credentialManager.getModel()
        val baseUrl = credentialManager.getBaseUrl()
        val endpoint = "$baseUrl/chat/completions"

        val request = NvidiaChatRequest(
            model = model,
            messages = listOf(NvidiaChatMessage(role = "user", content = prompt)),
            stream = true
        )

        AuraLogger.i(LogCategory.AI, "[AI] NVIDIA request started")

        try {
            httpClient.preparePost(endpoint) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                header(HttpHeaders.Accept, "text/event-stream")
                setBody(request)
            }.execute { response ->
                val statusCode = response.status.value
                if (statusCode !in 200..299) {
                    val responseText = response.bodyAsText()
                    throw handleHttpError(statusCode, responseText, model)
                }

                val channel: ByteReadChannel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    val trimmedLine = line.trim()

                    // Skip empty lines and SSE comment / keep-alive pings (starts with ':')
                    if (trimmedLine.isEmpty() || trimmedLine.startsWith(":")) {
                        continue
                    }

                    if (trimmedLine.startsWith("data:")) {
                        val payload = trimmedLine.removePrefix("data:").trim()
                        if (payload.isEmpty()) continue
                        if (payload == "[DONE]") {
                            break // Protocol completion sentinel: do not emit
                        }

                        // Inspect for embedded error payloads
                        val errObj = try { json.decodeFromString<NvidiaErrorResponse>(payload) } catch (e: Exception) { null }
                        if (errObj?.error?.message != null) {
                            val code = errObj.error.code?.toIntOrNull() ?: 500
                            val msg = errObj.error.message ?: "Stream error"
                            throw handleHttpError(code, msg, model)
                        }

                        try {
                            val chunk = json.decodeFromString<NvidiaChatResponse>(payload)
                            val text = chunk.choices?.firstOrNull()?.delta?.content
                            if (!text.isNullOrEmpty()) {
                                emit(text)
                            }
                        } catch (e: Exception) {
                            if (e is AuraError) throw e
                            // Non-fatal chunk decode issue (e.g. tool delta), continue
                        }
                    }
                }
                AuraLogger.i(LogCategory.AI, "[AI] NVIDIA request successful")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuraError) {
            throw e
        } catch (e: ClientRequestException) {
            val status = e.response.status.value
            val text = try { e.response.bodyAsText() } catch (ex: Exception) { "" }
            throw handleHttpError(status, text, model)
        } catch (e: ServerResponseException) {
            val status = e.response.status.value
            val text = try { e.response.bodyAsText() } catch (ex: Exception) { "" }
            throw handleHttpError(status, text, model)
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.AI, "[AI] NVIDIA network error: ${sanitizeErrorMessage(e.message ?: "")}")
            throw AuraError.AINetworkError("Unable to reach NVIDIA AI. Check your internet connection.")
        }
    }

    override suspend fun summarize(text: String): String {
        return generateResponse("Summarize this text: $text")
    }

    override suspend fun extractIntent(text: String): String {
        return generateResponse("Extract action intent from this query in JSON format: $text")
    }

    private fun handleHttpError(statusCode: Int, rawBody: String, model: String): Exception {
        val errorMsg = parseErrorMessage(rawBody, statusCode)
        val sanitized = sanitizeErrorMessage(errorMsg)

        return when (statusCode) {
            401, 403 -> {
                credentialManager.markInvalid()
                AuraLogger.w(LogCategory.AI, "[AI] NVIDIA API key unauthorized ($statusCode). Marked invalid.")
                AuraError.AIRequestError("NVIDIA API authentication failed. Check your API key.")
            }
            429 -> {
                credentialManager.markCooldown(300_000L)
                AuraLogger.w(LogCategory.AI, "[AI] NVIDIA rate limit exceeded (429). Applying cooldown.")
                AuraError.AIRateLimitError("NVIDIA API rate limit reached. Please try again later.")
            }
            404 -> {
                AuraLogger.e(LogCategory.AI, "[AI] NVIDIA model not found: $model ($sanitized)")
                AuraError.AIRequestError("NVIDIA model '$model' unavailable or not found.")
            }
            400 -> {
                if (sanitized.contains("model", ignoreCase = true) &&
                    (sanitized.contains("not found", ignoreCase = true) || sanitized.contains("does not exist", ignoreCase = true))
                ) {
                    AuraLogger.e(LogCategory.AI, "[AI] NVIDIA model not found: $model ($sanitized)")
                    AuraError.AIRequestError("NVIDIA model '$model' unavailable or not found.")
                } else {
                    AuraLogger.e(LogCategory.AI, "[AI] NVIDIA bad request: $sanitized")
                    AuraError.AIRequestError("NVIDIA request failed: $sanitized")
                }
            }
            in 500..599 -> {
                credentialManager.markCooldown(30_000L)
                AuraLogger.w(LogCategory.AI, "[AI] NVIDIA server error ($statusCode). Applying temporary cooldown.")
                AuraError.AIRequestError("NVIDIA server error ($statusCode). Please try again later.")
            }
            else -> {
                AuraLogger.e(LogCategory.AI, "[AI] NVIDIA error ($statusCode): $sanitized")
                AuraError.AINetworkError("Unable to reach NVIDIA AI. ($statusCode)")
            }
        }
    }

    private fun parseErrorMessage(rawBody: String, statusCode: Int): String {
        if (rawBody.isBlank()) return "HTTP Error $statusCode"
        return try {
            val errorResponse = json.decodeFromString<NvidiaErrorResponse>(rawBody)
            errorResponse.error?.message ?: errorResponse.detail ?: rawBody.take(120)
        } catch (e: Exception) {
            rawBody.take(120)
        }
    }

    private fun sanitizeErrorMessage(message: String): String {
        return message
            .replace(Regex("nvapi-[a-zA-Z0-9_-]+"), "[REDACTED_API_KEY]")
            .replace(Regex("AIzaSy[a-zA-Z0-9_-]{33}"), "[REDACTED_API_KEY]")
    }
}
