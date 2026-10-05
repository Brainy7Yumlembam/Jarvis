package co.aura.test

import co.aura.actions.Action
import co.aura.ai.*
import co.aura.domain.model.AuraError
import co.aura.security.SecurityManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class AIProviderManagerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun createMockClient(body: String, status: HttpStatusCode, contentType: String = "application/json"): HttpClient {
        return HttpClient(MockEngine) {
            install(ContentNegotiation) { json(json) }
            engine {
                addHandler {
                    respond(
                        content = body,
                        status = status,
                        headers = headersOf("Content-Type", contentType)
                    )
                }
            }
        }
    }

    @Test
    fun testGeminiWorksFirst() = runTest {
        val geminiSuccess = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [{"text": "Hello from Gemini"}]
                  }
                }
              ]
            }
        """.trimIndent()

        val geminiClient = createMockClient(geminiSuccess, HttpStatusCode.OK)
        val sec = TestSecurityManager("AIzaSyTestKey")
        sec.saveSecureToken("gemini_project_key_1", "AIzaSyTestKey")
        val geminiCredManager = GeminiCredentialManager(sec)
        val geminiProvider = GeminiProvider(geminiClient, GeminiModelConfig("gemini-3.6-flash"), geminiCredManager, json)

        val nvidiaClient = createMockClient("{}", HttpStatusCode.OK)
        val nvidiaSec = NvidiaTestSecurityManager()
        val nvidiaCredManager = NvidiaCredentialManager(nvidiaSec)
        val nvidiaProvider = NvidiaProvider(nvidiaClient, nvidiaCredManager, json)

        val manager = AIProviderManager(geminiProvider, nvidiaProvider, sec)

        val result = manager.generateResponse("hi")
        assertEquals("Hello from Gemini", result)
    }

    @Test
    fun testGeminiExhaustedFailoverToNvidia() = runTest {
        // Gemini returns 429
        val geminiError = """{"error": {"code": 429, "message": "Resource exhausted"}}"""
        val geminiClient = createMockClient(geminiError, HttpStatusCode.TooManyRequests)
        val sec = TestSecurityManager("AIzaSyTestKey")
        sec.saveSecureToken("gemini_project_key_1", "AIzaSyTestKey")
        val geminiCredManager = GeminiCredentialManager(sec)
        val geminiProvider = GeminiProvider(geminiClient, GeminiModelConfig("gemini-3.6-flash"), geminiCredManager, json)

        // NVIDIA succeeds
        val nvidiaSuccess = """
            {
              "id": "chat-1",
              "choices": [
                {
                  "index": 0,
                  "message": {"role": "assistant", "content": "Hello from NVIDIA failover"},
                  "finish_reason": "stop"
                }
              ]
            }
        """.trimIndent()
        val nvidiaClient = createMockClient(nvidiaSuccess, HttpStatusCode.OK)
        val nvidiaSec = NvidiaTestSecurityManager()
        val nvidiaCredManager = NvidiaCredentialManager(nvidiaSec)
        val nvidiaProvider = NvidiaProvider(nvidiaClient, nvidiaCredManager, json)

        val manager = AIProviderManager(geminiProvider, nvidiaProvider, sec)

        val result = manager.generateResponse("hi")
        assertEquals("Hello from NVIDIA failover", result)
    }

    @Test
    fun testGeminiFailsBeforeStreamingFailoverToNvidia() = runTest {
        val geminiError = """{"error": {"code": 503, "message": "Service unavailable"}}"""
        val geminiClient = createMockClient(geminiError, HttpStatusCode.ServiceUnavailable)
        val sec = TestSecurityManager("AIzaSyTestKey")
        sec.saveSecureToken("gemini_project_key_1", "AIzaSyTestKey")
        val geminiCredManager = GeminiCredentialManager(sec)
        val geminiProvider = GeminiProvider(geminiClient, GeminiModelConfig("gemini-3.6-flash"), geminiCredManager, json)

        val ssePayload = "data: {\"choices\":[{\"delta\":{\"content\":\"NVIDIA stream\"}}]}\n\ndata: [DONE]\n\n"
        val nvidiaClient = createMockClient(ssePayload, HttpStatusCode.OK, "text/event-stream")
        val nvidiaSec = NvidiaTestSecurityManager()
        val nvidiaCredManager = NvidiaCredentialManager(nvidiaSec)
        val nvidiaProvider = NvidiaProvider(nvidiaClient, nvidiaCredManager, json)

        val manager = AIProviderManager(geminiProvider, nvidiaProvider, sec)

        val chunks = manager.streamResponse("hi").toList()
        assertEquals(listOf("NVIDIA stream"), chunks)
    }

    @Test
    fun testMidStreamFailureDoesNotFailoverToNvidia() = runTest {
        // Mock SSE where Gemini emits chunk 1, then client connection drops mid-stream
        val partialSse = "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"First chunk \"}]}}]}\n\n"
        val geminiClient = createMockClient(partialSse, HttpStatusCode.OK, "text/event-stream")
        val sec = TestSecurityManager("AIzaSyTestKey")
        sec.saveSecureToken("gemini_project_key_1", "AIzaSyTestKey")
        val geminiCredManager = GeminiCredentialManager(sec)
        val geminiProvider = GeminiProvider(geminiClient, GeminiModelConfig("gemini-3.6-flash"), geminiCredManager, json)

        // NVIDIA has a valid response, but should NEVER be called if Gemini failed mid-stream
        val nvidiaSuccess = "data: {\"choices\":[{\"delta\":{\"content\":\"Duplicated NVIDIA response\"}}]}\n\ndata: [DONE]\n\n"
        val nvidiaClient = createMockClient(nvidiaSuccess, HttpStatusCode.OK, "text/event-stream")
        val nvidiaSec = NvidiaTestSecurityManager()
        val nvidiaCredManager = NvidiaCredentialManager(nvidiaSec)
        val nvidiaProvider = NvidiaProvider(nvidiaClient, nvidiaCredManager, json)

        val manager = AIProviderManager(geminiProvider, nvidiaProvider, sec)

        // The flow should emit "First chunk ", and not switch to NVIDIA
        val chunks = manager.streamResponse("hi").toList()
        assertEquals(listOf("First chunk "), chunks)
        assertFalse(chunks.contains("Duplicated NVIDIA response"))
    }

    @Test
    fun testBothProvidersFailThrowsException() = runTest {
        val geminiError = """{"error": {"code": 500, "message": "Server error"}}"""
        val geminiClient = createMockClient(geminiError, HttpStatusCode.InternalServerError)
        val sec = TestSecurityManager("AIzaSyTestKey")
        sec.saveSecureToken("gemini_project_key_1", "AIzaSyTestKey")
        val geminiCredManager = GeminiCredentialManager(sec)
        val geminiProvider = GeminiProvider(geminiClient, GeminiModelConfig("gemini-3.6-flash"), geminiCredManager, json)

        val nvidiaError = """{"error": {"code": "500", "message": "NVIDIA down"}}"""
        val nvidiaClient = createMockClient(nvidiaError, HttpStatusCode.InternalServerError)
        val nvidiaSec = NvidiaTestSecurityManager()
        val nvidiaCredManager = NvidiaCredentialManager(nvidiaSec)
        val nvidiaProvider = NvidiaProvider(nvidiaClient, nvidiaCredManager, json)

        val manager = AIProviderManager(geminiProvider, nvidiaProvider, sec)

        assertFailsWith<Exception> {
            manager.generateResponse("hi")
        }
    }
}
