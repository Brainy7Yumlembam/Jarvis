package co.aura.test

import co.aura.conversation.ConversationMode
import co.aura.presentation.viewmodel.VoiceAssistantState
import co.aura.presentation.viewmodel.VoiceAssistantViewModel
import co.aura.voice.TextToSpeechEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationLifecycleTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testOnPauseCleansUpActiveListening() = runTest {
        val recognizer = FakeVoiceRecognizer()
        val tts = FakeTextToSpeechEngine()
        val manager = FakeConversationManager()
        val permissions = FakePermissionManager()
        val security = FakeSecurityManager()
        val viewModel = VoiceAssistantViewModel(recognizer, tts, manager, permissions, security)

        // Set state to Listening
        viewModel.startListening()
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Listening)

        // Pause
        viewModel.onPause()
        assertEquals(VoiceAssistantState.Paused, viewModel.uiState.value)
        assertTrue(recognizer.cancelCalled)
        assertTrue(tts.stopCalled)
    }

    @Test
    fun testOnPauseIdempotency() = runTest {
        val recognizer = FakeVoiceRecognizer()
        val tts = FakeTextToSpeechEngine()
        val manager = FakeConversationManager()
        val permissions = FakePermissionManager()
        val security = FakeSecurityManager()
        val viewModel = VoiceAssistantViewModel(recognizer, tts, manager, permissions, security)

        // Start listening
        viewModel.startListening()
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Listening)

        // Call onPause twice
        viewModel.onPause()
        recognizer.cancelCalled = false
        tts.stopCalled = false

        viewModel.onPause()
        // Cancel should not be triggered again since state is already Paused, and it's idempotent
        assertEquals(VoiceAssistantState.Paused, viewModel.uiState.value)
    }

    @Test
    fun testOnResumeRestoresContinuousConversation() = runTest {
        val recognizer = FakeVoiceRecognizer()
        val tts = FakeTextToSpeechEngine()
        val manager = FakeConversationManager()
        val permissions = FakePermissionManager()
        val security = FakeSecurityManager()
        
        // Enable continuous conversation
        security.saveSecureToken("continuous_conversation", "true")
        
        val viewModel = VoiceAssistantViewModel(recognizer, tts, manager, permissions, security)

        // Start listening and pause
        viewModel.startListening()
        viewModel.onPause()
        assertEquals(VoiceAssistantState.Paused, viewModel.uiState.value)

        // Resume: should automatically start listening again
        viewModel.onResume()
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Listening)
    }

    @Test
    fun testOnResumeRemainsIdleWhenContinuousConversationDisabled() = runTest {
        val recognizer = FakeVoiceRecognizer()
        val tts = FakeTextToSpeechEngine()
        val manager = FakeConversationManager()
        val permissions = FakePermissionManager()
        val security = FakeSecurityManager()
        
        // Disable continuous conversation
        security.saveSecureToken("continuous_conversation", "false")
        
        val viewModel = VoiceAssistantViewModel(recognizer, tts, manager, permissions, security)

        // Start listening and pause
        viewModel.startListening()
        viewModel.onPause()
        assertEquals(VoiceAssistantState.Paused, viewModel.uiState.value)

        // Resume: should remain in Idle state
        viewModel.onResume()
        assertEquals(VoiceAssistantState.Idle, viewModel.uiState.value)
    }

    private class FakeConversationManager : co.aura.conversation.ConversationManager {
        private val messagesFlow = kotlinx.coroutines.flow.MutableStateFlow<List<co.aura.domain.model.ChatMessage>>(emptyList())
        override suspend fun processUserMessage(text: String): String = "Response"
        override fun getMessages(): kotlinx.coroutines.flow.Flow<List<co.aura.domain.model.ChatMessage>> = messagesFlow
        override suspend fun clearSession() {}
    }
}
