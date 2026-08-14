package co.aura.test

import co.aura.ai.*
import co.aura.domain.model.AuraError
import co.aura.actions.Action
import co.aura.security.SecurityManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class TestSecurityManager(private val initialKey: String) : SecurityManager {
    private val storage = mutableMapOf<String, String>().apply {
        put("gemini_api_key", initialKey)
    }
    override suspend fun authorizeAction(action: Action): Boolean = true
    override suspend fun confirmSensitiveAction(action: Action, promptMessage: String): Boolean = true
    override suspend fun saveSecureToken(key: String, token: String) {
        storage[key] = token
    }
    override suspend fun getSecureToken(key: String): String? = storage[key]
}

class GeminiIntegrationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun createMockClient(
        responseBody: String,
        status: HttpStatusCode
    ): HttpClient {
        return HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
            engine {
                addHandler { request ->
                    respond(
                        content = responseBody,
                        status = status,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }
            }
        }
    }

    @Test
    fun testSuccessfulResponseParsing() = runTest {
        val successJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "Hello, I am JARVIS."
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        val client = createMockClient(successJson, HttpStatusCode.OK)
        val provider = GeminiProvider(
            httpClient = client,
            modelConfig = GeminiModelConfig("gemini-3.6-flash"),
            securityManager = TestSecurityManager("AIzaSyTestKey"),
            json = json
        )

        val response = provider.generateResponse("hi")
        assertEquals("Hello, I am JARVIS.", response)
    }

    @Test
    fun testHTTP400BadRequest() = runTest {
        val errorJson = """
            {
              "error": {
                "code": 400,
                "message": "Invalid request parameter topK",
                "status": "INVALID_ARGUMENT"
              }
            }
        """.trimIndent()
        val client = createMockClient(errorJson, HttpStatusCode.BadRequest)
        val provider = GeminiProvider(
            httpClient = client,
            modelConfig = GeminiModelConfig("gemini-3.6-flash"),
            securityManager = TestSecurityManager("AIzaSyTestKey"),
            json = json
        )

        val error = assertFailsWith<AuraError.AIRequestError> {
            provider.generateResponse("hi")
        }
        assertTrue(error.message.contains("Gemini request failed: Invalid request parameter topK"))
    }

    @Test
    fun testHTTP401Unauthorized() = runTest {
        val errorJson = """
            {
              "error": {
                "code": 401,
                "message": "API key not valid. Please pass a valid API key.",
                "status": "UNAUTHENTICATED"
              }
            }
        """.trimIndent()
        val client = createMockClient(errorJson, HttpStatusCode.Unauthorized)
        val provider = GeminiProvider(
            httpClient = client,
            modelConfig = GeminiModelConfig("gemini-3.6-flash"),
            securityManager = TestSecurityManager("AIzaSyTestKey"),
            json = json
        )

        val error = assertFailsWith<AuraError.AIRequestError> {
            provider.generateResponse("hi")
        }
        assertEquals("Gemini API authentication failed. Check your API key.", error.message)
    }

    @Test
    fun testHTTP403Forbidden() = runTest {
        val errorJson = """
            {
              "error": {
                "code": 403,
                "message": "Permission denied for this key",
                "status": "PERMISSION_DENIED"
              }
            }
        """.trimIndent()
        val client = createMockClient(errorJson, HttpStatusCode.Forbidden)
        val provider = GeminiProvider(
            httpClient = client,
            modelConfig = GeminiModelConfig("gemini-3.6-flash"),
            securityManager = TestSecurityManager("AIzaSyTestKey"),
            json = json
        )

        val error = assertFailsWith<AuraError.AIRequestError> {
            provider.generateResponse("hi")
        }
        assertEquals("Gemini API authentication failed. Check your API key.", error.message)
    }

    @Test
    fun testHTTP429TooManyRequests() = runTest {
        val errorJson = """
            {
              "error": {
                "code": 429,
                "message": "Resource has been exhausted",
                "status": "RESOURCE_EXHAUSTED"
              }
            }
        """.trimIndent()
        val client = createMockClient(errorJson, HttpStatusCode.TooManyRequests)
        val provider = GeminiProvider(
            httpClient = client,
            modelConfig = GeminiModelConfig("gemini-3.6-flash"),
            securityManager = TestSecurityManager("AIzaSyTestKey"),
            json = json
        )

        val error = assertFailsWith<AuraError.AIRateLimitError> {
            provider.generateResponse("hi")
        }
        assertEquals("Gemini API rate limit reached. Please try again later.", error.message)
    }

    @Test
    fun testHTTP500ServerError() = runTest {
        val errorJson = """
            {
              "error": {
                "code": 500,
                "message": "Internal error occurred",
                "status": "INTERNAL"
              }
            }
        """.trimIndent()
        val client = createMockClient(errorJson, HttpStatusCode.InternalServerError)
        val provider = GeminiProvider(
            httpClient = client,
            modelConfig = GeminiModelConfig("gemini-3.6-flash"),
            securityManager = TestSecurityManager("AIzaSyTestKey"),
            json = json
        )

        val error = assertFailsWith<AuraError.AIRequestError> {
            provider.generateResponse("hi")
        }
        assertEquals("Gemini server error. Please try again later.", error.message)
    }

    @Test
    fun testNetworkFailure() = runTest {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    throw io.ktor.utils.io.errors.IOException("Timeout connection")
                }
            }
        }
        val provider = GeminiProvider(
            httpClient = client,
            modelConfig = GeminiModelConfig("gemini-3.6-flash"),
            securityManager = TestSecurityManager("AIzaSyTestKey"),
            json = json
        )

        val error = assertFailsWith<AuraError.AINetworkError> {
            provider.generateResponse("hi")
        }
        assertEquals("Unable to reach. Check your internet connection.", error.message)
    }

    @Test
    fun testMalformedJson() = runTest {
        val client = createMockClient("{ invalid json", HttpStatusCode.OK)
        val provider = GeminiProvider(
            httpClient = client,
            modelConfig = GeminiModelConfig("gemini-3.6-flash"),
            securityManager = TestSecurityManager("AIzaSyTestKey"),
            json = json
        )

        val error = assertFailsWith<AuraError.AIResponseError> {
            provider.generateResponse("hi")
        }
        assertEquals("Failed to parse Gemini response.", error.message)
    }

    @Test
    fun testMissingCandidates() = runTest {
        val client = createMockClient("{}", HttpStatusCode.OK)
        val provider = GeminiProvider(
            httpClient = client,
            modelConfig = GeminiModelConfig("gemini-3.6-flash"),
            securityManager = TestSecurityManager("AIzaSyTestKey"),
            json = json
        )

        val error = assertFailsWith<AuraError.AIResponseError> {
            provider.generateResponse("hi")
        }
        assertEquals("Received empty response from Gemini.", error.message)
    }

    @Test
    fun testEmptyCandidates() = runTest {
        val client = createMockClient("""{"candidates": []}""", HttpStatusCode.OK)
        val provider = GeminiProvider(
            httpClient = client,
            modelConfig = GeminiModelConfig("gemini-3.6-flash"),
            securityManager = TestSecurityManager("AIzaSyTestKey"),
            json = json
        )

        val error = assertFailsWith<AuraError.AIResponseError> {
            provider.generateResponse("hi")
        }
        assertEquals("Received empty response from Gemini.", error.message)
    }

    @Test
    fun testMissingContent() = runTest {
        val client = createMockClient("""{"candidates": [{}]}""", HttpStatusCode.OK)
        val provider = GeminiProvider(
            httpClient = client,
            modelConfig = GeminiModelConfig("gemini-3.6-flash"),
            securityManager = TestSecurityManager("AIzaSyTestKey"),
            json = json
        )

        val error = assertFailsWith<AuraError.AIResponseError> {
            provider.generateResponse("hi")
        }
        assertEquals("Received empty response from Gemini.", error.message)
    }

    @Test
    fun testMissingParts() = runTest {
        val client = createMockClient("""{"candidates": [{"content": {}}]}""", HttpStatusCode.OK)
        val provider = GeminiProvider(
            httpClient = client,
            modelConfig = GeminiModelConfig("gemini-3.6-flash"),
            securityManager = TestSecurityManager("AIzaSyTestKey"),
            json = json
        )

        val error = assertFailsWith<AuraError.AIResponseError> {
            provider.generateResponse("hi")
        }
        assertEquals("Received empty response from Gemini.", error.message)
    }

    @Test
    fun testMissingText() = runTest {
        val client = createMockClient("""{"candidates": [{"content": {"parts": [{}]}}]}""", HttpStatusCode.OK)
        val provider = GeminiProvider(
            httpClient = client,
            modelConfig = GeminiModelConfig("gemini-3.6-flash"),
            securityManager = TestSecurityManager("AIzaSyTestKey"),
            json = json
        )

        val error = assertFailsWith<AuraError.AIResponseError> {
            provider.generateResponse("hi")
        }
        assertEquals("Received empty response from Gemini.", error.message)
    }

    @Test
    fun testApiKeyNeverAppearsInErrors() = runTest {
        val errorJson = """
            {
              "error": {
                "code": 400,
                "message": "Key AIzaSyABC123XYZ7890_asdfghjklqwertyuiop is invalid",
                "status": "INVALID_ARGUMENT"
              }
            }
        """.trimIndent()
        val client = createMockClient(errorJson, HttpStatusCode.BadRequest)
        val provider = GeminiProvider(
            httpClient = client,
            modelConfig = GeminiModelConfig("gemini-3.6-flash"),
            securityManager = TestSecurityManager("AIzaSyABC123XYZ7890_asdfghjklqwertyuiop"),
            json = json
        )

        val error = assertFailsWith<AuraError.AIRequestError> {
            provider.generateResponse("hi")
        }
        
        // Assert the returned exception message does not contain the key, but is redacted
        assertFalse(error.message.contains("AIzaSyABC123XYZ7890_asdfghjklqwertyuiop"))
        assertTrue(error.message.contains("[REDACTED_API_KEY]"))
    }
}
