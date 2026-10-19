package co.aura.test

import co.aura.domain.model.ChatMessage
import co.aura.presentation.viewmodel.VoiceAssistantState
import co.aura.presentation.viewmodel.VoiceAssistantViewModel
import co.aura.voice.WakeWordDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WakeWordTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─────────────────────────────────────────────────────────────
    // 1. WakeWordDetector Unit Tests (Case, Punctuation, Variations)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testWakeWordDetectionExactMatchesAndVariations() {
        // Standard variations
        assertTrue(WakeWordDetector.isWakeWord("Wake up, JARVIS"))
        assertTrue(WakeWordDetector.isWakeWord("wake up jarvis"))
        assertTrue(WakeWordDetector.isWakeWord("Wake Up Jarvis!"))
        assertTrue(WakeWordDetector.isWakeWord("wake up, jarvis."))
        assertTrue(WakeWordDetector.isWakeWord("Wake up JARVIS please"))
        assertTrue(WakeWordDetector.isWakeWord("Jarvis, wake up"))
        assertTrue(WakeWordDetector.isWakeWord("jarvis wake up!"))
        assertTrue(WakeWordDetector.isWakeWord("Hey Jarvis wake up"))
        assertTrue(WakeWordDetector.isWakeWord("hello jarvis wake up"))
        assertTrue(WakeWordDetector.isWakeWord("OK Jarvis wake up"))
        assertTrue(WakeWordDetector.isWakeWord("wake up"))
        assertTrue(WakeWordDetector.isWakeWord("wake up please"))

        // Common speech-to-text phonetic deviations
        assertTrue(WakeWordDetector.isWakeWord("wait up jarvis"))
        assertTrue(WakeWordDetector.isWakeWord("wake up travis"))
        assertTrue(WakeWordDetector.isWakeWord("woke up jarvis"))
    }

    @Test
    fun testRejectionOfCasualMentionsWithoutWakeIntent() {
        // Zero token consumption - casual conversational mentions must be rejected
        assertFalse(WakeWordDetector.isWakeWord("I was speaking to Jarvis earlier"))
        assertFalse(WakeWordDetector.isWakeWord("Jarvis is an AI assistant"))
        assertFalse(WakeWordDetector.isWakeWord("Did Jarvis tell you that?"))
        assertFalse(WakeWordDetector.isWakeWord("Hey there how are you doing"))
        assertFalse(WakeWordDetector.isWakeWord("What time is it"))
        assertFalse(WakeWordDetector.isWakeWord("Turn on the flashlight"))
        assertFalse(WakeWordDetector.isWakeWord(""))
        assertFalse(WakeWordDetector.isWakeWord("   "))
    }

    @Test
    fun testTrailingCommandExtraction() {
        // One-shot commands spoken together with the wake word
        assertEquals("what time is it?", WakeWordDetector.extractCommandAfterWakeWord("Wake up Jarvis, what time is it?"))
        assertEquals("Turn on the flashlight.", WakeWordDetector.extractCommandAfterWakeWord("Wake up, JARVIS! Turn on the flashlight."))
        assertEquals("open youtube", WakeWordDetector.extractCommandAfterWakeWord("jarvis wake up open youtube"))

        // Wake word alone has no trailing command
        assertNull(WakeWordDetector.extractCommandAfterWakeWord("Wake up, JARVIS"))
        assertNull(WakeWordDetector.extractCommandAfterWakeWord("Wake up Jarvis!"))
        assertNull(WakeWordDetector.extractCommandAfterWakeWord("Jarvis wake up"))
    }

    // ─────────────────────────────────────────────────────────────
    // 2. State Machine & VoiceAssistantViewModel Wake Word Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testWakeWordMonitoringIgnoresCasualSpeechWithoutCallingGemini() = runTest {
        val recognizer = FakeVoiceRecognizer()
        val tts = FakeTextToSpeechEngine()
        val manager = TrackingConversationManager("I should never be called")
        val permissions = FakePermissionManager()
        val security = FakeSecurityManager()
        security.saveSecureToken("wake_word_enabled", "true")

        val viewModel = VoiceAssistantViewModel(
            recognizer, tts, manager, permissions, security,
            defaultWakeWordEnabled = true
        )

        viewModel.startWakeWordMonitoring()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value is VoiceAssistantState.WakeWordMonitoring)

        // User speaks casual phrase mentioning Jarvis
        recognizer.emitFinalResult("I was talking to Jarvis yesterday")
        testScheduler.runCurrent()

        // Must remain in WakeWordMonitoring state, TTS must NOT speak, Gemini must NOT be called
        assertTrue(viewModel.uiState.value is VoiceAssistantState.WakeWordMonitoring)
        assertNull(tts.textSpoken)
        assertEquals(0, manager.processCallCount)
    }

    @Test
    fun testWakeWordTriggerPlaysGreetingAndTransitionsToActiveListening() = runTest {
        val recognizer = FakeVoiceRecognizer()
        val tts = FakeTextToSpeechEngine()
        val manager = TrackingConversationManager("The flashlight is on, sir.")
        val permissions = FakePermissionManager()
        val security = FakeSecurityManager()
        security.saveSecureToken("wake_word_enabled", "true")

        val viewModel = VoiceAssistantViewModel(
            recognizer, tts, manager, permissions, security,
            defaultWakeWordEnabled = true
        )

        // 1. Assistant is monitoring for wake word
        viewModel.startWakeWordMonitoring()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value is VoiceAssistantState.WakeWordMonitoring)

        // 2. User says: "Wake up, JARVIS"
        recognizer.emitFinalResult("Wake up, JARVIS")
        testScheduler.runCurrent()

        // 3. Greeting "Yes, sir?" is spoken through TTS
        assertEquals("Yes, sir?", tts.textSpoken)

        // 4. Automatically transitions into active Listening state for user command
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Listening)

        // 5. User speaks command: "Turn on the flashlight"
        recognizer.emitFinalResult("Turn on the flashlight")
        testScheduler.runCurrent()

        // 6. Command was processed!
        assertEquals(1, manager.processCallCount)
    }

    @Test
    fun testOneShotWakeWordAndCommandExecutesImmediately() = runTest {
        val recognizer = FakeVoiceRecognizer()
        val tts = FakeTextToSpeechEngine()
        val manager = TrackingConversationManager("It is currently 3:00 PM, sir.")
        val permissions = FakePermissionManager()
        val security = FakeSecurityManager()
        security.saveSecureToken("wake_word_enabled", "true")

        val viewModel = VoiceAssistantViewModel(
            recognizer, tts, manager, permissions, security,
            defaultWakeWordEnabled = true
        )

        viewModel.startWakeWordMonitoring()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value is VoiceAssistantState.WakeWordMonitoring)

        // User speaks one-shot: "Wake up Jarvis, what time is it?"
        recognizer.emitFinalResult("Wake up Jarvis, what time is it?")
        testScheduler.runCurrent()

        // Directly processed command without redundant greeting delay
        assertEquals(1, manager.processCallCount)
        assertEquals("what time is it?", manager.lastProcessedQuery)
    }

    @Test
    fun testStopWakeWordMonitoringReturnsToIdle() = runTest {
        val recognizer = FakeVoiceRecognizer()
        val tts = FakeTextToSpeechEngine()
        val manager = TrackingConversationManager("Response")
        val permissions = FakePermissionManager()
        val security = FakeSecurityManager()
        security.saveSecureToken("wake_word_enabled", "true")

        val viewModel = VoiceAssistantViewModel(
            recognizer, tts, manager, permissions, security,
            defaultWakeWordEnabled = true
        )

        viewModel.startWakeWordMonitoring()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value is VoiceAssistantState.WakeWordMonitoring)

        // Explicitly stop wake word monitoring
        viewModel.stopWakeWordMonitoring()
        testScheduler.runCurrent()

        assertTrue(viewModel.uiState.value is VoiceAssistantState.Idle)
        assertTrue(recognizer.stopListeningCalled)
    }

    private class TrackingConversationManager(private val responseToReturn: String) : co.aura.conversation.ConversationManager {
        var processCallCount = 0
        var lastProcessedQuery: String? = null
        private val messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())

        override suspend fun processUserMessage(text: String): String {
            processCallCount++
            lastProcessedQuery = text
            return responseToReturn
        }

        override fun getMessages(): Flow<List<ChatMessage>> = messagesFlow
        override suspend fun clearSession() {}
    }
}
