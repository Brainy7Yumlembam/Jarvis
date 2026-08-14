package co.aura.test

import co.aura.actions.*
import co.aura.conversation.*
import co.aura.domain.model.ChatMessage
import co.aura.domain.model.MessageSender
import co.aura.domain.model.MessageStatus
import co.aura.memory.MemoryManagerImpl
import co.aura.voice.SpeechCommandNormalizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationContextTest {

    private class TestActionExecutor : ActionExecutor {
        var lastExecutedAction: Action? = null
        var responseToReturn: ActionResult = ActionResult.Success("Done")

        override suspend fun executeAction(action: Action): ActionResult {
            lastExecutedAction = action
            return responseToReturn
        }
    }

    private val actionParser = ActionParserImpl(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
    private val actionValidator = ActionValidatorImpl()

    @Test
    fun testContextBuilderLimitsTurns() {
        val builder = ContextBuilderImpl()
        val systemInstructions = "System Instructions"
        val personalityPrompt = "Personality Profile"
        val mode = ConversationMode.HYBRID
        val memories = emptyList<co.aura.domain.model.MemoryFragment>()
        
        val messages = (1..20).map { i ->
            ChatMessage(
                id = "msg_$i",
                sessionId = "session_default",
                sender = if (i % 2 == 0) MessageSender.USER else MessageSender.ASSISTANT,
                content = "Turn $i",
                timestamp = System.currentTimeMillis() + i,
                status = MessageStatus.SENT
            )
        }

        val context = builder.buildContext(
            systemInstructions = systemInstructions,
            personalityPrompt = personalityPrompt,
            conversationMode = mode,
            relevantMemories = memories,
            recentMessages = messages.takeLast(10), // Limit to 10 recent messages
            recentActionContext = "Last opened app: YouTube",
            currentRequest = "Play music"
        )

        // Verify the context text
        assertTrue(context.contains("System Instructions"))
        assertTrue(context.contains("Personality Profile"))
        assertTrue(context.contains("Current Conversation Mode: HYBRID"))
        assertTrue(context.contains("Last opened app: YouTube"))
        
        // It must NOT contain the first message (Turn 1) but must contain Turn 11 to 20
        assertFalse(context.contains("User: Turn 1\n"))
        assertTrue(context.contains("User: Turn 20\n"))
    }

    @Test
    fun testReferenceResolutionAndActionContext() = runTest {
        val aiProvider = FakeAIProvider()
        val repository = FakeConversationRepository()
        val memoryManager = FakeMemoryManager()
        val executor = TestActionExecutor()
        val actionRouter = ActionRouterImpl(CommandBusImpl(), executor)
        
        val conversationManager = ConversationManagerImpl(
            aiProvider = aiProvider,
            memoryManager = memoryManager,
            personalityEngine = PersonalityEngineImpl(),
            conversationRepository = repository,
            contextBuilder = ContextBuilderImpl(),
            actionParser = actionParser,
            actionValidator = actionValidator,
            actionRouter = actionRouter,
            speechNormalizer = SpeechCommandNormalizer(),
            securityManager = FakeSecurityManager(),
            aliasResolver = AppAliasResolver()
        )

        // Scenario: Open YouTube, then Close it
        aiProvider.responseToReturn = """{
            "type": "ACTION",
            "response": "Opening YouTube, sir.",
            "actions": [
                {
                    "action": "OPEN_APP",
                    "parameters": {
                        "appName": "YouTube"
                    }
                }
            ]
        }"""

        val resp1 = conversationManager.processUserMessage("Open YouTube")
        assertEquals("Done", resp1)
        assertEquals(OpenAppAction("YouTube"), executor.lastExecutedAction)

        // Next turn: Close it
        aiProvider.responseToReturn = """{
            "type": "NORMAL_CONVERSATION",
            "response": "Done, sir.",
            "actions": []
        }"""
        
        val resp2 = conversationManager.processUserMessage("Close it")
        assertEquals("Done, sir.", resp2)
    }

    @Test
    fun testPlaySecondOneResolution() = runTest {
        val aiProvider = FakeAIProvider()
        val repository = FakeConversationRepository()
        val memoryManager = FakeMemoryManager()
        val executor = TestActionExecutor()
        val actionRouter = ActionRouterImpl(CommandBusImpl(), executor)
        
        val conversationManager = ConversationManagerImpl(
            aiProvider = aiProvider,
            memoryManager = memoryManager,
            personalityEngine = PersonalityEngineImpl(),
            conversationRepository = repository,
            contextBuilder = ContextBuilderImpl(),
            actionParser = actionParser,
            actionValidator = actionValidator,
            actionRouter = actionRouter,
            speechNormalizer = SpeechCommandNormalizer(),
            securityManager = FakeSecurityManager(),
            aliasResolver = AppAliasResolver()
        )

        // Mock search result context
        aiProvider.responseToReturn = """{
            "type": "NORMAL_CONVERSATION",
            "response": "I found these: 1. In the End, 2. Numb. Which one, sir?",
            "actions": []
        }"""
        
        val resp1 = conversationManager.processUserMessage("Search for Linkin Park")
        assertEquals("I found these: 1. In the End, 2. Numb. Which one, sir?", resp1)

        // Play the second one
        aiProvider.responseToReturn = """{
            "type": "HYBRID",
            "response": "Playing Numb, sir.",
            "actions": [
                {
                    "action": "OPEN_APP",
                    "parameters": {
                        "appName": "music"
                    }
                }
            ]
        }"""

        val resp2 = conversationManager.processUserMessage("Play the second one")
        assertEquals("Done", resp2)
    }

    @Test
    fun testAiDecisionParsing() = runTest {
        val aiProvider = FakeAIProvider()
        val repository = FakeConversationRepository()
        val memoryManager = FakeMemoryManager()
        val executor = TestActionExecutor()
        val actionRouter = ActionRouterImpl(CommandBusImpl(), executor)
        
        val conversationManager = ConversationManagerImpl(
            aiProvider = aiProvider,
            memoryManager = memoryManager,
            personalityEngine = PersonalityEngineImpl(),
            conversationRepository = repository,
            contextBuilder = ContextBuilderImpl(),
            actionParser = actionParser,
            actionValidator = actionValidator,
            actionRouter = actionRouter,
            speechNormalizer = SpeechCommandNormalizer(),
            securityManager = FakeSecurityManager(),
            aliasResolver = AppAliasResolver()
        )

        // 1. "How are you?" -> NORMAL_CONVERSATION
        aiProvider.responseToReturn = """{
            "type": "NORMAL_CONVERSATION",
            "response": "I am functioning within normal parameters, sir.",
            "actions": []
        }"""
        val resp1 = conversationManager.processUserMessage("How are you?")
        assertEquals("I am functioning within normal parameters, sir.", resp1)

        // 2. "Open YouTube." -> ACTION
        aiProvider.responseToReturn = """{
            "type": "ACTION",
            "response": "Opening YouTube.",
            "actions": [
                {
                    "action": "OPEN_APP",
                    "parameters": {
                        "appName": "YouTube"
                    }
                }
            ]
        }"""
        val resp2 = conversationManager.processUserMessage("Open YouTube.")
        assertEquals("Done", resp2)

        // 3. "I'm bored, play some music." -> HYBRID
        aiProvider.responseToReturn = """{
            "type": "HYBRID",
            "response": "Understood. Playing some music.",
            "actions": [
                {
                    "action": "OPEN_APP",
                    "parameters": {
                        "appName": "music"
                    }
                }
            ]
        }"""
        val resp3 = conversationManager.processUserMessage("I'm bored, play some music.")
        assertEquals("Done", resp3)

        // 4. "WhatsApp kholo." -> ACTION
        aiProvider.responseToReturn = """{
            "type": "ACTION",
            "response": "Opening WhatsApp.",
            "actions": [
                {
                    "action": "OPEN_APP",
                    "parameters": {
                        "appName": "WhatsApp"
                    }
                }
            ]
        }"""
        val resp4 = conversationManager.processUserMessage("WhatsApp kholo.")
        assertEquals("Done", resp4)

        // 5. "Tell me something interesting and open YouTube." -> HYBRID
        aiProvider.responseToReturn = """{
            "type": "HYBRID",
            "response": "Did you know that gravity pulls everything at the same rate? Opening YouTube now.",
            "actions": [
                {
                    "action": "OPEN_APP",
                    "parameters": {
                        "appName": "YouTube"
                    }
                }
            ]
        }"""
        val resp5 = conversationManager.processUserMessage("Tell me something interesting and open YouTube.")
        assertEquals("Done", resp5)
    }
}
