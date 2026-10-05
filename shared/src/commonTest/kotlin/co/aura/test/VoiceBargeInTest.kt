package co.aura.test

import co.aura.domain.model.ChatMessage
import co.aura.presentation.viewmodel.VoiceAssistantState
import co.aura.presentation.viewmodel.VoiceAssistantViewModel
import co.aura.voice.DefaultInterruptionDetector
import co.aura.voice.TextToSpeechEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceBargeInTest {

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
    fun testInterruptionDetectorPhrases() {
        val detector = DefaultInterruptionDetector()

        // Strong interruption phrases
        assertTrue(detector.detect("stop"))
        assertTrue(detector.detect("Stop"))
        assertTrue(detector.detect("wait"))
        assertTrue(detector.detect("hold on"))
        assertTrue(detector.detect("stop talking"))
        assertTrue(detector.detect("shut up"))
        assertTrue(detector.detect("be quiet"))
        assertTrue(detector.detect("cancel"))
        assertTrue(detector.detect("wait a second"))

        // Inside sentence
        assertTrue(detector.detect("Hey Jarvis please stop"))
        assertTrue(detector.detect("Wait, hold on a minute"))

        // Normal phrases should NOT be treated as interruption
        assertFalse(detector.detect("tell me the weather"))
        assertFalse(detector.detect("play some music"))
        assertFalse(detector.detect("nonstop music")) // boundary test: 'nonstop' contains 'stop' but shouldn't trigger
        assertFalse(detector.detect("awaiting confirmation")) // boundary test: 'awaiting' contains 'wait'

        // Explicit test: ambiguous words like "no" alone must NOT trigger interruption
        assertFalse(detector.detect("No"))
        assertFalse(detector.detect("no"))
        assertFalse(detector.detect("No, that's not what I meant."))
        assertFalse(detector.detect("There are no emails."))
    }

    @Test
    fun testScenario1NormalConversationNoButton() = runTest {
        val recognizer = FakeVoiceRecognizer()
        val tts = FakeTextToSpeechEngine()
        val manager = FakeConversationManager("Hello, sir. How may I assist you today?")
        val permissions = FakePermissionManager()
        val security = FakeSecurityManager()

        val viewModel = VoiceAssistantViewModel(
            recognizer, tts, manager, permissions, security,
            defaultContinuousConversation = true
        )

        // 1. Start listening initially
        viewModel.startListening()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Listening)

        // 2. User speaks: "Hello Jarvis"
        recognizer.emitTranscript("Hello Jarvis")
        testScheduler.runCurrent()
        assertEquals("Hello Jarvis", (viewModel.uiState.value as VoiceAssistantState.Listening).partialTranscript)

        // 3. Speech ends, final result emitted automatically from recognizer
        recognizer.emitFinalResult("Hello Jarvis")
        testScheduler.runCurrent()

        // 4. Automatically transitions through Processing -> Speaking -> Listening without any button press!
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Listening)
        assertEquals("Hello, sir. How may I assist you today?", tts.textSpoken)
    }

    @Test
    fun testScenario2MultipleTurnsNoButton() = runTest {
        val recognizer = FakeVoiceRecognizer()
        val tts = FakeTextToSpeechEngine()
        val manager = SequentialFakeConversationManager(
            listOf("The weather in London is 18 degrees and sunny.", "Tomorrow will be 20 degrees with scattered clouds.")
        )
        val permissions = FakePermissionManager()
        val security = FakeSecurityManager()

        val viewModel = VoiceAssistantViewModel(
            recognizer, tts, manager, permissions, security,
            defaultContinuousConversation = true
        )

        viewModel.startListening()
        testScheduler.runCurrent()

        // Turn 1: "What's the weather?"
        recognizer.emitFinalResult("What's the weather?")
        testScheduler.runCurrent()

        // First turn completes, answers, and returns to Listening automatically
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Listening)
        assertEquals("The weather in London is 18 degrees and sunny.", tts.textSpoken)

        // Turn 2: User directly says "And tomorrow?" without touching any button
        recognizer.emitFinalResult("And tomorrow?")
        testScheduler.runCurrent()

        // Second turn completes and answers!
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Listening)
        assertEquals("Tomorrow will be 20 degrees with scattered clouds.", tts.textSpoken)
    }

    @Test
    fun testScenario3VoiceInterruptionStop() = runTest {
        val recognizer = FakeVoiceRecognizer()
        val tts = SuspendingTtsEngine()
        val manager = SequentialFakeConversationManager(
            listOf("Here is a long speech that will be interrupted...", "Certainly, here is something else.")
        )
        val permissions = FakePermissionManager()
        val security = FakeSecurityManager()
        val interruptionMonitor = FakeInterruptionMonitor()

        val viewModel = VoiceAssistantViewModel(
            recognizer, tts, manager, permissions, security,
            interruptionMonitor = interruptionMonitor,
            defaultContinuousConversation = true
        )

        viewModel.startListening()
        testScheduler.runCurrent()

        // Ask question
        recognizer.emitFinalResult("Tell me a long story.")
        testScheduler.runCurrent()

        // JARVIS is now speaking
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Speaking)
        assertTrue(tts.isSpeaking())
        assertTrue(interruptionMonitor.isMonitoringCalled)

        // User speaks "Stop" - detected by interruption monitor
        interruptionMonitor.simulateInterruption("Stop")
        testScheduler.runCurrent()

        // TTS stops immediately
        assertTrue(tts.stopCalled)
        assertFalse(tts.isSpeaking())

        // State transitions immediately to Listening
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Listening)

        // User immediately speaks new command on the SAME viewModel: "Tell me something else."
        tts.stopCalled = false
        recognizer.emitFinalResult("Tell me something else.")
        testScheduler.runCurrent()

        // Verify the second turn was processed and spoken
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Speaking)
        assertEquals("Certainly, here is something else.", tts.textSpoken)
    }


    @Test
    fun testScenario4VoiceInterruptionWait() = runTest {
        val recognizer = FakeVoiceRecognizer()
        val tts = SuspendingTtsEngine()
        val manager = FakeConversationManager("Commencing audio readout...")
        val permissions = FakePermissionManager()
        val security = FakeSecurityManager()
        val interruptionMonitor = FakeInterruptionMonitor()

        val viewModel = VoiceAssistantViewModel(
            recognizer, tts, manager, permissions, security,
            interruptionMonitor = interruptionMonitor
        )

        viewModel.startListening()
        testScheduler.runCurrent()

        recognizer.emitFinalResult("Read the report.")
        testScheduler.runCurrent()

        assertTrue(viewModel.uiState.value is VoiceAssistantState.Speaking)
        assertTrue(tts.isSpeaking())

        // User says "Wait"
        interruptionMonitor.simulateInterruption("Wait")
        testScheduler.runCurrent()

        // TTS halts immediately and returns to Listening
        assertTrue(tts.stopCalled)
        assertFalse(tts.isSpeaking())
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Listening)
    }

    @Test
    fun testScenario5ButtonFallback() = runTest {
        val recognizer = FakeVoiceRecognizer()
        val tts = SuspendingTtsEngine()
        val manager = FakeConversationManager("Audio playback in progress...")
        val permissions = FakePermissionManager()
        val security = FakeSecurityManager()

        val viewModel = VoiceAssistantViewModel(recognizer, tts, manager, permissions, security)

        viewModel.startListening()
        testScheduler.runCurrent()

        recognizer.emitFinalResult("Explain relativity.")
        testScheduler.runCurrent()

        assertTrue(viewModel.uiState.value is VoiceAssistantState.Speaking)
        assertTrue(tts.isSpeaking())

        // User manually taps the INTERRUPT button
        viewModel.interruptJarvis()
        testScheduler.runCurrent()

        // TTS stops immediately and state transitions to Listening
        assertTrue(tts.stopCalled)
        assertFalse(tts.isSpeaking())
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Listening)
    }

    @Test
    fun testScenario6DoNotAccidentallyInterrupt() {
        val detector = DefaultInterruptionDetector()

        // Phrases containing "no" or general words must NOT trigger interruption
        assertFalse(detector.detect("There are no updates."))
        assertFalse(detector.detect("No messages found."))
        assertFalse(detector.detect("no"))
        assertFalse(detector.detect("pause"))
        assertFalse(detector.detect("Please notice no errors occurred."))

        // Explicit commands MUST trigger interruption
        assertTrue(detector.detect("stop"))
        assertTrue(detector.detect("wait"))
        assertTrue(detector.detect("hold on"))
        assertTrue(detector.detect("stop talking"))
    }

    @Test
    fun testInterruptionCancelsActiveAiGeneration() = runTest {
        val recognizer = FakeVoiceRecognizer()
        val tts = SuspendingTtsEngine()
        val slowManager = SuspendingConversationManager()
        val permissions = FakePermissionManager()
        val security = FakeSecurityManager()

        val viewModel = VoiceAssistantViewModel(recognizer, tts, slowManager, permissions, security)

        viewModel.startListening()
        testScheduler.runCurrent()

        recognizer.emitTranscript("Explain quantum mechanics in detail")
        testScheduler.runCurrent()
        recognizer.emitFinalResult("Explain quantum mechanics in detail")
        testScheduler.runCurrent()

        // State should be Processing while AI generation is in progress
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Processing)

        // User interrupts before Gemini finishes
        viewModel.interruptJarvis()
        testScheduler.runCurrent()

        // Should switch to Listening immediately
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Listening)

        // The response must NOT be spoken because request was cancelled
        assertFalse(tts.isSpeaking())
        assertEquals(null, tts.textSpoken)
    }

    @Test
    fun testAudioVisualizationSeparation() = runTest {
        val recognizer = FakeVoiceRecognizer()
        val tts = SuspendingTtsEngine()
        val manager = FakeConversationManager("I am standing by.")
        val permissions = FakePermissionManager()
        val security = FakeSecurityManager()

        val viewModel = VoiceAssistantViewModel(recognizer, tts, manager, permissions, security)

        viewModel.startListening()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value is VoiceAssistantState.Listening)

        // While listening, speechAmplitude must be 0f
        assertEquals(0f, viewModel.visualizerState.value.speechAmplitude)

        // User speaks and transitions to speaking
        recognizer.emitTranscript("Jarvis")
        testScheduler.runCurrent()
        recognizer.emitFinalResult("Jarvis")
        testScheduler.runCurrent()

        assertTrue(viewModel.uiState.value is VoiceAssistantState.Speaking)
        // Microphone amplitude must be 0f while speaking
        assertEquals(0f, viewModel.visualizerState.value.microphoneAmplitude)
    }

    private class SuspendingTtsEngine : TextToSpeechEngine {
        var textSpoken: String? = null
        var stopCalled = false
        private var speaking = false

        override suspend fun speak(text: String): Boolean {
            textSpoken = text
            speaking = true
            try {
                awaitCancellation()
            } finally {
                speaking = false
            }
            return true
        }

        override fun stop() {
            stopCalled = true
            speaking = false
        }

        override fun isSpeaking(): Boolean = speaking
    }

    private class FakeConversationManager(private val responseToReturn: String) : co.aura.conversation.ConversationManager {
        private val messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
        override suspend fun processUserMessage(text: String): String = responseToReturn
        override fun getMessages(): Flow<List<ChatMessage>> = messagesFlow
        override suspend fun clearSession() {}
    }

    private class SequentialFakeConversationManager(private val responses: List<String>) : co.aura.conversation.ConversationManager {
        private var index = 0
        private val messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
        override suspend fun processUserMessage(text: String): String {
            val response = responses.getOrElse(index) { "Default response" }
            index++
            return response
        }
        override fun getMessages(): Flow<List<ChatMessage>> = messagesFlow
        override suspend fun clearSession() {}
    }

    private class SuspendingConversationManager : co.aura.conversation.ConversationManager {
        private val messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
        override suspend fun processUserMessage(text: String): String {
            awaitCancellation()
            return "Delayed AI response"
        }
        override fun getMessages(): Flow<List<ChatMessage>> = messagesFlow
        override suspend fun clearSession() {}
    }
}
