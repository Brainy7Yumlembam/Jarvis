package co.aura.test

import co.aura.actions.Action
import co.aura.ai.CredentialStatus
import co.aura.ai.NvidiaCredentialManager
import co.aura.ai.NvidiaProvider
import co.aura.domain.model.AuraError
import co.aura.security.SecurityManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class NvidiaTestSecurityManager(initialKey: String = "nvapi-testkey-12345") : SecurityManager {
    private val storage = mutableMapOf<String, String>().apply {
        put("nvidia_api_key", initialKey)
        put("nvidia_model", "meta/llama-3.1-8b-instruct")
        put("nvidia_base_url", "https://integrate.api.nvidia.com/v1")
        put("nvidia_enabled", "true")
    }
    override suspend fun authorizeAction(action: Action): Boolean = true
    override suspend fun confirmSensitiveAction(action: Action, promptMessage: String): Boolean = true
    override suspend fun saveSecureToken(key: String, token: String) {
        storage[key] = token
    }
    override suspend fun getSecureToken(key: String): String? = storage[key]
}

class NvidiaProviderTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun createMockClient(
        responseBody: String,
        status: HttpStatusCode,
        contentType: String = "application/json"
    ): HttpClient {
        return HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
            engine {
                addHandler {
                    respond(
                        content = responseBody,
                        status = status,
                        headers = headersOf("Content-Type", contentType)
                    )
                }
            }
        }
    }

    @Test
    fun testNonStreamingSuccess() = runTest {
        val successJson = """
            {
              "id": "chat-123",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "Hello, I am JARVIS powered by NVIDIA NIM."
                  },
                  "finish_reason": "stop"
                }
              ]
            }
        """.trimIndent()

        val client = createMockClient(successJson, HttpStatusCode.OK)
        val sec = NvidiaTestSecurityManager()
        val credManager = NvidiaCredentialManager(sec)
        val provider = NvidiaProvider(client, credManager, json)

        val response = provider.generateResponse("hi")
        assertEquals("Hello, I am JARVIS powered by NVIDIA NIM.", response)
    }

    @Test
    fun testSseStreamingSuccess() = runTest {
        val ssePayload = """
            : keep-alive
            data: {"choices":[{"delta":{"content":"Hello "}}]}
            
            : ping
            data: {"choices":[{"delta":{"content":"from "}}]}
            data: {"choices":[{"delta":{"content":"NVIDIA"}}]}
            data: [DONE]
        """.trimIndent()

        val client = createMockClient(ssePayload, HttpStatusCode.OK, "text/event-stream")
        val sec = NvidiaTestSecurityManager()
        val credManager = NvidiaCredentialManager(sec)
        val provider = NvidiaProvider(client, credManager, json)

        val chunks = provider.streamResponse("hi").toList()
        assertEquals(listOf("Hello ", "from ", "NVIDIA"), chunks)
        assertFalse(chunks.contains("[DONE]"))
    }

    @Test
    fun testHttp429RateLimitAppliesCooldown() = runTest {
        val errorJson = """
            {
              "error": {
                "message": "Rate limit exceeded. Please try again later.",
                "code": "429"
              }
            }
        """.trimIndent()

        val client = createMockClient(errorJson, HttpStatusCode.TooManyRequests)
        val sec = NvidiaTestSecurityManager()
        val credManager = NvidiaCredentialManager(sec)
        val provider = NvidiaProvider(client, credManager, json)

        assertFailsWith<AuraError.AIRateLimitError> {
            provider.generateResponse("hi")
        }
        assertEquals(CredentialStatus.COOLDOWN, credManager.status.value)
    }

    @Test
    fun testHttp401UnauthorizedMarksInvalid() = runTest {
        val errorJson = """
            {
              "error": {
                "message": "Invalid API Key",
                "code": "401"
              }
            }
        """.trimIndent()

        val client = createMockClient(errorJson, HttpStatusCode.Unauthorized)
        val sec = NvidiaTestSecurityManager()
        val credManager = NvidiaCredentialManager(sec)
        val provider = NvidiaProvider(client, credManager, json)

        assertFailsWith<AuraError.AIRequestError> {
            provider.generateResponse("hi")
        }
        assertEquals(CredentialStatus.INVALID, credManager.status.value)
    }

    @Test
    fun testHttp404ModelNotFound() = runTest {
        val errorJson = """
            {
              "error": {
                "message": "Model 'meta/llama-3.1-8b-instruct' not found",
                "code": "404"
              }
            }
        """.trimIndent()

        val client = createMockClient(errorJson, HttpStatusCode.NotFound)
        val sec = NvidiaTestSecurityManager()
        val credManager = NvidiaCredentialManager(sec)
        val provider = NvidiaProvider(client, credManager, json)

        val error = assertFailsWith<AuraError.AIRequestError> {
            provider.generateResponse("hi")
        }
        assertTrue(error.message.contains("unavailable or not found"))
        // Credential should NOT be marked invalid for model errors
        assertNotEquals(CredentialStatus.INVALID, credManager.status.value)
    }
}
