package co.aura.test

import co.aura.ai.*
import co.aura.domain.model.AuraError
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

class LocalAIProviderTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun createMockClient(): HttpClient {
        return HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
            engine {
                addHandler { request ->
                    respond(
                        content = """{"candidates": [{"content": {"parts": [{"text": "Mocked Gemini Response"}]}}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }
            }
        }
    }

    @Test
    fun testLocalProviderDoesNotMakeNetworkRequest() = runTest {
        val securityManager = FakeSecurityManager()
        val provider = LocalAIProvider(securityManager)

        val error = assertFailsWith<AuraError.AIRequestError> {
            provider.generateResponse("Hello")
        }
        assertEquals("Local AI is not available on this device yet.", error.message)

        val streamError = assertFailsWith<AuraError.AIRequestError> {
            provider.streamResponse("Hello").toList()
        }
        assertEquals("Local AI is not available on this device yet.", streamError.message)
    }

    @Test
    fun testDelegatingProviderDelegatesToGeminiWhenGeminiSelected() = runTest {
        val securityManager = FakeSecurityManager()
        securityManager.saveSecureToken("ai_provider_type", "GEMINI")
        securityManager.saveSecureToken("gemini_api_key", "mock_key")

        val httpClient = createMockClient()
        val modelConfig = GeminiModelConfig("gemini-3.6-flash")
        val geminiProvider = GeminiProvider(httpClient, modelConfig, GeminiCredentialManager(securityManager), json)
        val localProvider = LocalAIProvider(securityManager)
        val delegatingProvider = DelegatingAIProvider(geminiProvider, localProvider, securityManager)

        val response = delegatingProvider.generateResponse("Hello")
        assertEquals("Mocked Gemini Response", response)
    }

    @Test
    fun testDelegatingProviderFallsBackToGeminiWhenLocalSelected() = runTest {
        val securityManager = FakeSecurityManager()
        securityManager.saveSecureToken("ai_provider_type", "LOCAL")
        securityManager.saveSecureToken("gemini_api_key", "mock_key")

        val httpClient = createMockClient()
        val modelConfig = GeminiModelConfig("gemini-3.6-flash")
        val geminiProvider = GeminiProvider(httpClient, modelConfig, GeminiCredentialManager(securityManager), json)
        val localProvider = LocalAIProvider(securityManager)
        val delegatingProvider = DelegatingAIProvider(geminiProvider, localProvider, securityManager)

        // Since Local AI is not available in Milestone A, choosing LOCAL should log a fallback warning and gracefully fall back to Gemini
        val response = delegatingProvider.generateResponse("Hello")
        assertEquals("Mocked Gemini Response", response)
    }

    @Test
    fun testApiKeysAreNeverLoggedOrExposedInErrors() = runTest {
        val securityManager = FakeSecurityManager()
        securityManager.saveSecureToken("ai_provider_type", "GEMINI")
        securityManager.saveSecureToken("gemini_api_key", "SECRET_GEMINI_API_KEY_12345")

        // Create an HTTP client that always returns a 403 Forbidden error
        val httpClient = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
            engine {
                addHandler { request ->
                    respond(
                        content = """{"error": {"code": 403, "message": "API key not valid", "status": "INVALID_ARGUMENT"}}""",
                        status = HttpStatusCode.Forbidden,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }
            }
        }

        val modelConfig = GeminiModelConfig("gemini-3.6-flash")
        val geminiProvider = GeminiProvider(httpClient, modelConfig, GeminiCredentialManager(securityManager), json)
        val localProvider = LocalAIProvider(securityManager)
        val delegatingProvider = DelegatingAIProvider(geminiProvider, localProvider, securityManager)

        val error = assertFailsWith<AuraError.AIRequestError> {
            delegatingProvider.generateResponse("Hello")
        }
        
        // Assert that the sensitive API key is not included in the error message
        assertFalse(error.message.contains("SECRET_GEMINI_API_KEY_12345"))
    }
}
