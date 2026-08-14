package co.aura.test

import co.aura.actions.*
import co.aura.conversation.*
import co.aura.domain.model.ChatMessage
import co.aura.domain.model.UserProfile
import co.aura.voice.SpeechCommandNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Captures prompts sent to AIProvider so we can inspect instructions. */
private class PromptCapturingAIProvider : co.aura.ai.AIProvider {
    var lastCapturedPrompt: String? = null
    var responseToReturn: String = """{"type":"NORMAL_CONVERSATION","response":"I was built by Brainy, sir.","actions":[]}"""

    override suspend fun generateResponse(prompt: String): String {
        lastCapturedPrompt = prompt
        return responseToReturn
    }

    override fun streamResponse(prompt: String): Flow<String> {
        lastCapturedPrompt = prompt
        return flowOf(responseToReturn)
    }

    override suspend fun summarize(text: String): String = "Summary"
    override suspend fun extractIntent(text: String): String = "{}"
}

class CreatorIdentityTest {

    private fun createManager(aiProvider: PromptCapturingAIProvider): ConversationManagerImpl {
        val commandBus = CommandBusImpl()
        val executor = object : ActionExecutor {
            override suspend fun executeAction(action: Action): ActionResult = ActionResult.Success("Done")
        }
        val actionRouter = ActionRouterImpl(commandBus, executor)
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        return ConversationManagerImpl(
            aiProvider = aiProvider,
            memoryManager = FakeMemoryManager(),
            personalityEngine = PersonalityEngineImpl(),
            conversationRepository = FakeConversationRepository(),
            contextBuilder = ContextBuilderImpl(),
            actionParser = ActionParserImpl(json),
            actionValidator = ActionValidatorImpl(),
            actionRouter = actionRouter,
            speechNormalizer = SpeechCommandNormalizer(),
            securityManager = FakeSecurityManager(),
            aliasResolver = AppAliasResolver()
        )
    }

    @Test
    fun `1 Who built you identifies Brainy`() { runBlocking {
        val aiProvider = PromptCapturingAIProvider().apply {
            responseToReturn = """{"type":"NORMAL_CONVERSATION","response":"I was built by Brainy, sir.","actions":[]}"""
        }
        val manager = createManager(aiProvider)

        val response = manager.processUserMessage("Who built you?")
        val prompt = aiProvider.lastCapturedPrompt

        assertNotNull(prompt)
        assertTrue(prompt.contains("Brainy"), "Prompt sent to LLM must contain creator name 'Brainy'")
        assertTrue(response.contains("Brainy"), "Response to 'Who built you?' must identify Brainy as builder")
        assertEquals(JARVIS_CREATOR_NAME, "Brainy")
    }}

    @Test
    fun `2 Who created you identifies Brainy`() { runBlocking {
        val aiProvider = PromptCapturingAIProvider().apply {
            responseToReturn = """{"type":"NORMAL_CONVERSATION","response":"Brainy created me, sir.","actions":[]}"""
        }
        val manager = createManager(aiProvider)

        val response = manager.processUserMessage("Who created you?")
        val prompt = aiProvider.lastCapturedPrompt

        assertNotNull(prompt)
        assertTrue(prompt.contains("Brainy"), "Prompt sent to LLM must contain creator name 'Brainy'")
        assertTrue(response.contains("Brainy"), "Response to 'Who created you?' must identify Brainy as creator")
    }}

    @Test
    fun `3 Who is your developer identifies Brainy`() { runBlocking {
        val aiProvider = PromptCapturingAIProvider().apply {
            responseToReturn = """{"type":"NORMAL_CONVERSATION","response":"My developer is Brainy, sir.","actions":[]}"""
        }
        val manager = createManager(aiProvider)

        val response = manager.processUserMessage("Who is your developer?")
        val prompt = aiProvider.lastCapturedPrompt

        assertNotNull(prompt)
        assertTrue(prompt.contains("Brainy"), "Prompt sent to LLM must contain creator name 'Brainy'")
        assertTrue(response.contains("Brainy"), "Response to 'Who is your developer?' must identify Brainy as developer")
    }}

    @Test
    fun `4 Are you Tony Starks JARVIS explicitly distinguishes from Marvel JARVIS`() { runBlocking {
        val aiProvider = PromptCapturingAIProvider().apply {
            responseToReturn = """{"type":"NORMAL_CONVERSATION","response":"No, sir. I'm Brainy's JARVIS.","actions":[]}"""
        }
        val manager = createManager(aiProvider)

        val response = manager.processUserMessage("Are you Tony Stark's JARVIS?")
        val prompt = aiProvider.lastCapturedPrompt

        assertNotNull(prompt)
        assertTrue(prompt.contains("Tony Stark"), "Prompt must reference rule regarding Tony Stark/Marvel")
        assertTrue(prompt.contains("Brainy"), "Prompt must instruct that Brainy is the actual developer")
        assertTrue(response.contains("Brainy"), "Response must mention Brainy")
        assertTrue(response.contains("No") || response.contains("Brainy's"), "Response must distinguish from Tony Stark's fictional JARVIS")
    }}

    @Test
    fun `5 Changing current user name does NOT change creator name`() { runBlocking {
        val aiProvider = PromptCapturingAIProvider()
        val manager = createManager(aiProvider)

        // Store user profile with a different user name (e.g. "Alice")
        val userMemoryRepo = FakeMemoryRepository()
        userMemoryRepo.updateSemanticProfile(UserProfile(id = "user_1", name = "Alice", preferences = emptyMap()))

        val response = manager.processUserMessage("Who built you?")
        val prompt = aiProvider.lastCapturedPrompt

        assertNotNull(prompt)
        assertTrue(prompt.contains("Brainy"), "Creator name in prompt must remain 'Brainy'")
        assertNotEquals("Alice", JARVIS_CREATOR_NAME, "User name must never overwrite creator name")
        assertEquals("Brainy", JARVIS_CREATOR_NAME)
    }}

    @Test
    fun `6 Installing APK for another user does NOT change creator name`() { runBlocking {
        // User B installs app
        val userBProfile = UserProfile(id = "user_b_99", name = "Bob Explorer", preferences = emptyMap())

        val aiProvider = PromptCapturingAIProvider()
        val manager = createManager(aiProvider)

        manager.processUserMessage("Who is your developer?")
        val prompt = aiProvider.lastCapturedPrompt

        assertNotNull(prompt)
        assertTrue(prompt.contains("Brainy"), "New APK installation for User B must preserve 'Brainy' as creator")
        assertEquals("Brainy", JARVIS_CREATOR_NAME)
    }}

    @Test
    fun `7 Brainy remains creator across app restarts`() { runBlocking {
        // App Instance 1
        val engine1 = PersonalityEngineImpl()
        val instructions1 = engine1.getSystemInstructions(ConversationMode.HYBRID)
        assertTrue(instructions1.contains("Brainy"), "Instance 1 must have Brainy as creator")

        // Simulate app restart (new application process creating fresh PersonalityEngine)
        val engine2 = PersonalityEngineImpl()
        val instructions2 = engine2.getSystemInstructions(ConversationMode.HYBRID)
        assertTrue(instructions2.contains("Brainy"), "Instance 2 must have Brainy as creator after restart")
        assertEquals("Brainy", JARVIS_CREATOR_NAME)
    }}
}
