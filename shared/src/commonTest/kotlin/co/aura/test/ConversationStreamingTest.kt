package co.aura.test

import co.aura.actions.*
import co.aura.ai.AIProvider
import co.aura.conversation.ContextBuilderImpl
import co.aura.conversation.ConversationManagerImpl
import co.aura.conversation.PersonalityEngineImpl
import co.aura.domain.model.ChatMessage
import co.aura.domain.repository.ConversationRepository
import co.aura.voice.SpeechCommandNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationStreamingTest {

    private class StreamingFakeAIProvider(private val tokens: List<String>) : AIProvider {
        override suspend fun generateResponse(prompt: String): String = tokens.joinToString("")
        override fun streamResponse(prompt: String): Flow<String> = flow {
            for (token in tokens) {
                emit(token)
            }
        }
        override suspend fun summarize(text: String): String = "Summary"
        override suspend fun extractIntent(text: String): String = "{}"
    }

    private class RecordingConversationRepository : ConversationRepository {
        val savedMessages = mutableListOf<ChatMessage>()
        override suspend fun saveMessage(message: ChatMessage) {
            savedMessages.add(message)
        }
        override fun getMessages(limit: Int): Flow<List<ChatMessage>> = flow {
            emit(savedMessages)
        }
        override suspend fun clearHistory() {
            savedMessages.clear()
        }
    }

    @Test
    fun testConversationalStreamingProducesSentenceChunksAndOneSavedMessage() = runTest {
        val tokens = listOf("The capital ", "of France is ", "Paris. ", "It is ", "beautiful.")
        val aiProvider = StreamingFakeAIProvider(tokens)
        val repo = RecordingConversationRepository()
        val memoryManager = FakeMemoryManager()

        val manager = ConversationManagerImpl(
            aiProvider = aiProvider,
            memoryManager = memoryManager,
            personalityEngine = PersonalityEngineImpl(),
            conversationRepository = repo,
            contextBuilder = ContextBuilderImpl(),
            actionParser = ActionParserImpl(kotlinx.serialization.json.Json { ignoreUnknownKeys = true }),
            actionValidator = ActionValidatorImpl(),
            actionRouter = ActionRouterImpl(CommandBusImpl(), NoOpActionExecutor()),
            speechNormalizer = SpeechCommandNormalizer(),
            securityManager = FakeSecurityManager(),
            aliasResolver = AppAliasResolver()
        )

        val streamedChunks = manager.processUserMessageStream("Where is Paris?").toList()

        // Should have emitted sentence chunks
        assertEquals(2, streamedChunks.size)
        assertEquals("The capital of France is Paris.", streamedChunks[0])
        assertEquals("It is beautiful.", streamedChunks[1])

        // Should have saved exactly 1 USER message and 1 ASSISTANT message
        assertEquals(2, repo.savedMessages.size)
        assertEquals(co.aura.domain.model.MessageSender.USER, repo.savedMessages[0].sender)
        assertEquals(co.aura.domain.model.MessageSender.ASSISTANT, repo.savedMessages[1].sender)
        assertEquals("The capital of France is Paris. It is beautiful.", repo.savedMessages[1].content)
    }

    @Test
    fun testActionJsonStreamingBuffersAndExecutesAction() = runTest {
        val jsonAction = """{"type": "ACTION", "actions": [{"actionType": "OPEN_APP", "appName": "YouTube"}]}"""
        val tokens = listOf("{\"type\":", " \"ACTION\",", " \"actions\": [{\"actionType\": \"OPEN_APP\", \"appName\": \"YouTube\"}]}")
        val aiProvider = StreamingFakeAIProvider(tokens)
        val repo = RecordingConversationRepository()
        val memoryManager = FakeMemoryManager()

        var appOpened: String? = null
        val testExecutor = object : ActionExecutor {
            override suspend fun executeAction(action: Action): ActionResult {
                if (action is OpenAppAction) {
                    appOpened = action.appName
                    return ActionResult.Success("Opening YouTube, sir.")
                }
                return ActionResult.Success("Done")
            }
        }

        val manager = ConversationManagerImpl(
            aiProvider = aiProvider,
            memoryManager = memoryManager,
            personalityEngine = PersonalityEngineImpl(),
            conversationRepository = repo,
            contextBuilder = ContextBuilderImpl(),
            actionParser = ActionParserImpl(kotlinx.serialization.json.Json { ignoreUnknownKeys = true }),
            actionValidator = ActionValidatorImpl(),
            actionRouter = ActionRouterImpl(CommandBusImpl(), testExecutor),
            speechNormalizer = SpeechCommandNormalizer(),
            securityManager = FakeSecurityManager(),
            aliasResolver = AppAliasResolver()
        )

        val emitted = manager.processUserMessageStream("Open YouTube").toList()

        // Should have executed the action
        assertEquals("YouTube", appOpened)
        // Should have emitted the spoken confirmation message, NOT raw JSON!
        assertEquals(1, emitted.size)
        assertEquals("Opening YouTube, sir.", emitted[0])
        assertTrue(!emitted[0].contains("{"))
    }

    @Test
    fun testHelloJarvisStreamsSequentialSentencesThroughChunker() = runTest {
        val tokens = listOf("{\"type\": \"NORMAL_CONVERSATION\", ", "\"response\": \"Hello, sir. How may I be of service to you?\", ", "\"actions\": []}")
        val aiProvider = StreamingFakeAIProvider(tokens)
        val repo = RecordingConversationRepository()
        val memoryManager = FakeMemoryManager()

        val manager = ConversationManagerImpl(
            aiProvider = aiProvider,
            memoryManager = memoryManager,
            personalityEngine = PersonalityEngineImpl(),
            conversationRepository = repo,
            contextBuilder = ContextBuilderImpl(),
            actionParser = ActionParserImpl(kotlinx.serialization.json.Json { ignoreUnknownKeys = true }),
            actionValidator = ActionValidatorImpl(),
            actionRouter = ActionRouterImpl(CommandBusImpl(), NoOpActionExecutor()),
            speechNormalizer = SpeechCommandNormalizer(),
            securityManager = FakeSecurityManager(),
            aliasResolver = AppAliasResolver()
        )

        val emitted = manager.processUserMessageStream("hello Jarvis").toList()

        // Should have emitted sequential sentence chunks via SentenceChunker
        assertEquals(2, emitted.size)
        assertEquals("Hello, sir.", emitted[0])
        assertEquals("How may I be of service to you?", emitted[1])
    }
}
