package co.aura.test

import co.aura.conversation.ContextBuilderImpl
import co.aura.conversation.ConversationManagerImpl
import co.aura.conversation.PersonalityEngineImpl
import co.aura.domain.model.ChatMessage
import co.aura.domain.model.MessageSender
import co.aura.domain.model.MessageStatus
import co.aura.presentation.viewmodel.VoiceAssistantViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

class ConversationOrderTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testConversationMessagesOrder() = runTest(testDispatcher) {
        val aiProvider = FakeAIProvider().apply {
            responseToReturn = "JARVIS Response"
        }
        val memoryManager = FakeMemoryManager()
        val personalityEngine = PersonalityEngineImpl()
        val repository = FakeConversationRepository()
        val voiceRecognizer = FakeVoiceRecognizer()
        val ttsEngine = FakeTextToSpeechEngine()
        val permissionManager = FakePermissionManager()
        val securityManager = FakeSecurityManager()

        val msg1 = ChatMessage("msg_1", "session_default", MessageSender.USER, "Old user message", 1000L, MessageStatus.SENT)
        val msg2 = ChatMessage("msg_2", "session_default", MessageSender.ASSISTANT, "Old assistant response", 2000L, MessageStatus.SENT)
        repository.saveMessage(msg1)
        repository.saveMessage(msg2)

        val conversationManager = ConversationManagerImpl(
            aiProvider = aiProvider,
            memoryManager = memoryManager,
            personalityEngine = personalityEngine,
            conversationRepository = repository,
            contextBuilder = ContextBuilderImpl()
        )

        var viewModel = VoiceAssistantViewModel(
            voiceRecognizer = voiceRecognizer,
            ttsEngine = ttsEngine,
            conversationManager = conversationManager,
            permissionManager = permissionManager,
            securityManager = securityManager
        )

        testDispatcher.scheduler.advanceUntilIdle()

        var currentMessages = viewModel.messages.first()
        assertEquals(2, currentMessages.size)
        assertEquals("Old user message", currentMessages[0].content)
        assertEquals("Old assistant response", currentMessages[1].content)
        assertTrue(currentMessages[0].timestamp < currentMessages[1].timestamp)

        conversationManager.processUserMessage("New user message")
        testDispatcher.scheduler.advanceUntilIdle()

        currentMessages = viewModel.messages.first()
        assertEquals(4, currentMessages.size)
        assertEquals("Old user message", currentMessages[0].content)
        assertEquals("Old assistant response", currentMessages[1].content)
        assertEquals("New user message", currentMessages[2].content)
        assertEquals("JARVIS Response", currentMessages[3].content)
        assertTrue(currentMessages[2].timestamp < currentMessages[3].timestamp)

        viewModel = VoiceAssistantViewModel(
            voiceRecognizer = voiceRecognizer,
            ttsEngine = ttsEngine,
            conversationManager = conversationManager,
            permissionManager = permissionManager,
            securityManager = securityManager
        )
        testDispatcher.scheduler.advanceUntilIdle()

        currentMessages = viewModel.messages.first()
        assertEquals(4, currentMessages.size)
        assertEquals("Old user message", currentMessages[0].content)
        assertEquals("Old assistant response", currentMessages[1].content)
        assertEquals("New user message", currentMessages[2].content)
        assertEquals("JARVIS Response", currentMessages[3].content)
    }
}
