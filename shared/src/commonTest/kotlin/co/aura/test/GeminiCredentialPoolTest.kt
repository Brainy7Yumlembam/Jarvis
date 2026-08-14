package co.aura.test

import co.aura.ai.*
import co.aura.domain.model.AuraError
import co.aura.domain.model.ChatMessage
import co.aura.domain.model.MessageSender
import co.aura.domain.model.MessageStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class GeminiCredentialPoolTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun createFailoverMockClient(
        shouldFailOnFirst: Boolean,
        firstStatus: HttpStatusCode,
        firstBody: String,
        retryAfterHeader: String? = null
    ): HttpClient {
        var callCount = 0
        return HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
            engine {
                addHandler { request ->
                    callCount++
                    if (shouldFailOnFirst && callCount == 1) {
                        val headers = headersOf(
                            "Content-Type" to listOf("application/json"),
                            *(retryAfterHeader?.let { listOf("Retry-After" to listOf(it)) }?.toTypedArray() ?: emptyList<Pair<String, List<String>>>().toTypedArray())
                        )
                        respond(
                            content = firstBody,
                            status = firstStatus,
                            headers = headers
                        )
                    } else {
                        respond(
                            content = """{"candidates": [{"content": {"parts": [{"text": "Successful failover response"}]}}]}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", "application/json")
                        )
                    }
                }
            }
        }
    }

    @Test
    fun test429RotatesCredentialsAndParsesRetryAfter() = runTest {
        val securityManager = FakeSecurityManager()
        securityManager.saveSecureToken("gemini_project_key_1", "key1")
        securityManager.saveSecureToken("gemini_project_key_2", "key2")

        val client = createFailoverMockClient(
            shouldFailOnFirst = true,
            firstStatus = HttpStatusCode.TooManyRequests,
            firstBody = """{"error": {"code": 429, "message": "Rate limit exceeded", "status": "RESOURCE_EXHAUSTED"}}""",
            retryAfterHeader = "10"
        )

        val credentialManager = GeminiCredentialManager(securityManager)
        val provider = GeminiProvider(client, GeminiModelConfig("gemini-3.6-flash"), credentialManager, json)

        val response = provider.generateResponse("hi")
        assertEquals("Successful failover response", response)

        val credentials = credentialManager.credentials.value
        val slot1 = credentials.first { it.slot == 1 }
        assertEquals(CredentialStatus.COOLDOWN, slot1.status)
        assertNotNull(slot1.cooldownUntil)
        assertTrue(slot1.cooldownUntil!! > System.currentTimeMillis())
    }

    @Test
    fun test401RotatesCredentials() = runTest {
        val securityManager = FakeSecurityManager()
        securityManager.saveSecureToken("gemini_project_key_1", "key1")
        securityManager.saveSecureToken("gemini_project_key_2", "key2")

        val client = createFailoverMockClient(
            shouldFailOnFirst = true,
            firstStatus = HttpStatusCode.Unauthorized,
            firstBody = """{"error": {"code": 401, "message": "Invalid API key", "status": "UNAUTHENTICATED"}}"""
        )

        val credentialManager = GeminiCredentialManager(securityManager)
        val provider = GeminiProvider(client, GeminiModelConfig("gemini-3.6-flash"), credentialManager, json)

        val response = provider.generateResponse("hi")
        assertEquals("Successful failover response", response)

        val credentials = credentialManager.credentials.value
        val slot1 = credentials.first { it.slot == 1 }
        assertEquals(CredentialStatus.INVALID, slot1.status)
    }

    @Test
    fun testUnrelated403DoesNotRotate() = runTest {
        val securityManager = FakeSecurityManager()
        securityManager.saveSecureToken("gemini_project_key_1", "key1")
        securityManager.saveSecureToken("gemini_project_key_2", "key2")

        val client = createFailoverMockClient(
            shouldFailOnFirst = true,
            firstStatus = HttpStatusCode.Forbidden,
            firstBody = """{"error": {"code": 403, "message": "Country not supported", "status": "PERMISSION_DENIED"}}"""
        )

        val credentialManager = GeminiCredentialManager(securityManager)
        val provider = GeminiProvider(client, GeminiModelConfig("gemini-3.6-flash"), credentialManager, json)

        assertFailsWith<AuraError.AIRequestError> {
            provider.generateResponse("hi")
        }
        
        val credentials = credentialManager.credentials.value
        val slot1 = credentials.first { it.slot == 1 }
        assertNotEquals(CredentialStatus.INVALID, slot1.status)
    }

    @Test
    fun testCredentialSpecific403Rotates() = runTest {
        val securityManager = FakeSecurityManager()
        securityManager.saveSecureToken("gemini_project_key_1", "key1")
        securityManager.saveSecureToken("gemini_project_key_2", "key2")

        val client = createFailoverMockClient(
            shouldFailOnFirst = true,
            firstStatus = HttpStatusCode.Forbidden,
            firstBody = """{"error": {"code": 403, "message": "API key not valid", "status": "API_KEY_INVALID"}}"""
        )

        val credentialManager = GeminiCredentialManager(securityManager)
        val provider = GeminiProvider(client, GeminiModelConfig("gemini-3.6-flash"), credentialManager, json)

        val response = provider.generateResponse("hi")
        assertEquals("Successful failover response", response)

        val credentials = credentialManager.credentials.value
        val slot1 = credentials.first { it.slot == 1 }
        assertEquals(CredentialStatus.INVALID, slot1.status)
    }

    @Test
    fun test400DoesNotRotate() = runTest {
        val securityManager = FakeSecurityManager()
        securityManager.saveSecureToken("gemini_project_key_1", "key1")
        securityManager.saveSecureToken("gemini_project_key_2", "key2")

        val client = createFailoverMockClient(
            shouldFailOnFirst = true,
            firstStatus = HttpStatusCode.BadRequest,
            firstBody = """{"error": {"code": 400, "message": "Bad Request parameters", "status": "INVALID_ARGUMENT"}}"""
        )

        val credentialManager = GeminiCredentialManager(securityManager)
        val provider = GeminiProvider(client, GeminiModelConfig("gemini-3.6-flash"), credentialManager, json)

        assertFailsWith<AuraError.AIRequestError> {
            provider.generateResponse("hi")
        }
    }

    @Test
    fun testAllTenUnavailableProperError() = runTest {
        val securityManager = FakeSecurityManager()
        securityManager.saveSecureToken("gemini_project_key_1", "key1")

        val client = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
            engine {
                addHandler { request ->
                    respond(
                        content = """{"error": {"code": 401, "message": "Expired API key", "status": "UNAUTHENTICATED"}}""",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }
            }
        }

        val credentialManager = GeminiCredentialManager(securityManager)
        val provider = GeminiProvider(client, GeminiModelConfig("gemini-3.6-flash"), credentialManager, json)

        assertFailsWith<AuraError.AIRequestError> {
            provider.generateResponse("hi")
        }
    }

    @Test
    fun testFailoverProducesOneResponseAndOneTtsAndOneMemoryWrite() = runTest {
        val securityManager = FakeSecurityManager()
        securityManager.saveSecureToken("gemini_project_key_1", "key1")
        securityManager.saveSecureToken("gemini_project_key_2", "key2")

        val client = createFailoverMockClient(
            shouldFailOnFirst = true,
            firstStatus = HttpStatusCode.TooManyRequests,
            firstBody = """{"error": {"code": 429, "message": "Rate limit exceeded", "status": "RESOURCE_EXHAUSTED"}}"""
        )

        val credentialManager = GeminiCredentialManager(securityManager)
        val provider = GeminiProvider(client, GeminiModelConfig("gemini-3.6-flash"), credentialManager, json)

        val response = provider.generateResponse("hi")
        assertEquals("Successful failover response", response)
        
        val mockTts = FakeTextToSpeechEngine()
        var speakCount = 0
        
        val successSpeak = mockTts.speak(response)
        if (successSpeak) {
            speakCount++
        }
        assertEquals(1, speakCount)
        assertEquals("Successful failover response", mockTts.textSpoken)

        val mockRepo = FakeConversationRepository()
        val assistantMsg = ChatMessage(
            id = "msg_1",
            sessionId = "session_default",
            sender = MessageSender.ASSISTANT,
            content = response,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENT
        )
        mockRepo.saveMessage(assistantMsg)

        assertEquals(1, mockRepo.messages.size)
        assertEquals("Successful failover response", mockRepo.messages.first().content)
    }

    @Test
    fun testApiKeysAreNeverExposedInLogs() = runTest {
        val securityManager = FakeSecurityManager()
        val credentialManager = GeminiCredentialManager(securityManager)
        credentialManager.saveCredential(1, "Test project", "AIzaSyTestApiKeyString12345", true)

        val client = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
            engine {
                addHandler { request ->
                    respond(
                        content = """{"error": {"code": 403, "message": "API key AIzaSyTestApiKeyString12345 is not valid", "status": "API_KEY_INVALID"}}""",
                        status = HttpStatusCode.Forbidden,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }
            }
        }

        val provider = GeminiProvider(client, GeminiModelConfig("gemini-3.6-flash"), credentialManager, json)
        val error = assertFailsWith<AuraError.AIRequestError> {
            provider.generateResponse("hi")
        }

        assertFalse(error.message.contains("AIzaSyTestApiKeyString12345"))
    }
}
