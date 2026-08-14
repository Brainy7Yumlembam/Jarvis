package co.aura.test

import co.aura.conversation.ConversationManagerImpl
import co.aura.conversation.PersonalityEngineImpl
import co.aura.domain.model.AuraError
import co.aura.presentation.viewmodel.VoiceAssistantState
import co.aura.presentation.viewmodel.VoiceAssistantViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AuraTest {

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
    fun testConversationManagerAddsMessagesAndQueriesAI() = runTest {
        val aiProvider = FakeAIProvider()
        val memoryManager = FakeMemoryManager()
        val personalityEngine = PersonalityEngineImpl()
        val repository = FakeConversationRepository()

        val manager = ConversationManagerImpl(
            aiProvider = aiProvider,
            memoryManager = memoryManager,
            personalityEngine = personalityEngine,
            conversationRepository = repository,
            contextBuilder = co.aura.conversation.ContextBuilderImpl()
        )

        val response = manager.processUserMessage("Hello JARVIS")
        assertEquals("Mock response from fake LLM provider", response)
        assertEquals(2, repository.messages.size)
        assertEquals("Hello JARVIS", repository.messages[0].content)
        assertEquals("Mock response from fake LLM provider", repository.messages[1].content)
    }

    @Test
    fun testVoiceAssistantViewModelIdleToListeningFlow() = runTest {
        val voiceRecognizer = FakeVoiceRecognizer()
        val ttsEngine = FakeTextToSpeechEngine()
        val aiProvider = FakeAIProvider()
        val memoryManager = FakeMemoryManager()
        val personalityEngine = PersonalityEngineImpl()
        val repository = FakeConversationRepository()
        val permissionManager = FakePermissionManager()

        val conversationManager = ConversationManagerImpl(
            aiProvider = aiProvider,
            memoryManager = memoryManager,
            personalityEngine = personalityEngine,
            conversationRepository = repository,
            contextBuilder = co.aura.conversation.ContextBuilderImpl()
        )

        val viewModel = VoiceAssistantViewModel(
            voiceRecognizer = voiceRecognizer,
            ttsEngine = ttsEngine,
            conversationManager = conversationManager,
            permissionManager = permissionManager,
            securityManager = FakeSecurityManager()
        )

        assertEquals(VoiceAssistantState.Idle, viewModel.uiState.value)

        viewModel.startListening()
        assertEquals(VoiceAssistantState.Listening(""), viewModel.uiState.value)

        testScheduler.runCurrent()

        voiceRecognizer.emitTranscript("Show me the weather")
        testScheduler.runCurrent()

        assertEquals(VoiceAssistantState.Listening("Show me the weather"), viewModel.uiState.value)
    }

    @Test
    fun testVoiceAssistantViewModelPermissionDeniedError() = runTest {
        val voiceRecognizer = FakeVoiceRecognizer()
        val ttsEngine = FakeTextToSpeechEngine()
        val aiProvider = FakeAIProvider()
        val memoryManager = FakeMemoryManager()
        val personalityEngine = PersonalityEngineImpl()
        val repository = FakeConversationRepository()
        
        val permissionManager = FakePermissionManager().apply {
            permissionsMap["android.permission.RECORD_AUDIO"] = false
        }

        val conversationManager = ConversationManagerImpl(
            aiProvider = aiProvider,
            memoryManager = memoryManager,
            personalityEngine = personalityEngine,
            conversationRepository = repository,
            contextBuilder = co.aura.conversation.ContextBuilderImpl()
        )

        val viewModel = VoiceAssistantViewModel(
            voiceRecognizer = voiceRecognizer,
            ttsEngine = ttsEngine,
            conversationManager = conversationManager,
            permissionManager = permissionManager,
            securityManager = FakeSecurityManager()
        )

        viewModel.startListening()
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Error)
        assertEquals("Audio recording permission required.", (viewModel.uiState.value as VoiceAssistantState.Error).errorMessage)
    }

    @Test
    fun testVoiceAssistantViewModelAIMapHttpError() = runTest {
        val voiceRecognizer = FakeVoiceRecognizer()
        val ttsEngine = FakeTextToSpeechEngine()
        val aiProvider = FakeAIProvider().apply {
            shouldThrowError = true
            errorToThrow = AuraError.AIRateLimitError("Gemini API Rate limit exceeded.")
        }
        val memoryManager = FakeMemoryManager()
        val personalityEngine = PersonalityEngineImpl()
        val repository = FakeConversationRepository()
        val permissionManager = FakePermissionManager()

        val conversationManager = ConversationManagerImpl(
            aiProvider = aiProvider,
            memoryManager = memoryManager,
            personalityEngine = personalityEngine,
            conversationRepository = repository,
            contextBuilder = co.aura.conversation.ContextBuilderImpl()
        )

        val viewModel = VoiceAssistantViewModel(
            voiceRecognizer = voiceRecognizer,
            ttsEngine = ttsEngine,
            conversationManager = conversationManager,
            permissionManager = permissionManager,
            securityManager = FakeSecurityManager()
        )

        viewModel.startListening()
        testScheduler.runCurrent()

        voiceRecognizer.emitTranscript("Hi")
        testScheduler.runCurrent()
        voiceRecognizer.completeListening()
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is VoiceAssistantState.Error)
        assertEquals("Gemini API Rate limit exceeded.", (viewModel.uiState.value as VoiceAssistantState.Error).errorMessage)
    }

    @Test
    fun testVoiceAssistantViewModelContinuousConversationFlow() = runTest {
        val voiceRecognizer = FakeVoiceRecognizer()
        val ttsEngine = FakeTextToSpeechEngine()
        val aiProvider = FakeAIProvider()
        val memoryManager = FakeMemoryManager()
        val personalityEngine = PersonalityEngineImpl()
        val repository = FakeConversationRepository()
        val permissionManager = FakePermissionManager()
        
        // Scenario 1: Continuous Conversation Disabled (default)
        val securityManager = FakeSecurityManager()
        val conversationManager = ConversationManagerImpl(
            aiProvider = aiProvider,
            memoryManager = memoryManager,
            personalityEngine = personalityEngine,
            conversationRepository = repository,
            contextBuilder = co.aura.conversation.ContextBuilderImpl()
        )

        val viewModel = VoiceAssistantViewModel(
            voiceRecognizer = voiceRecognizer,
            ttsEngine = ttsEngine,
            conversationManager = conversationManager,
            permissionManager = permissionManager,
            securityManager = securityManager
        )

        viewModel.startListening()
        testScheduler.runCurrent()
        voiceRecognizer.emitTranscript("Hi")
        testScheduler.runCurrent()
        voiceRecognizer.completeListening()
        testScheduler.advanceUntilIdle()

        // Should be Idle after speaking finishes
        assertEquals(VoiceAssistantState.Idle, viewModel.uiState.value)

        // Scenario 2: Continuous Conversation Enabled
        securityManager.saveSecureToken("continuous_conversation", "true")
        
        viewModel.startListening()
        testScheduler.runCurrent()
        voiceRecognizer.emitTranscript("Hi")
        testScheduler.runCurrent()
        voiceRecognizer.completeListening()
        testScheduler.advanceUntilIdle()

        // Should return to Listening state immediately
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Listening)
    }
}
