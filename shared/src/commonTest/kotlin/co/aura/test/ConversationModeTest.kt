package co.aura.test

import co.aura.conversation.ConversationMode
import co.aura.presentation.viewmodel.SettingsAction
import co.aura.presentation.viewmodel.SettingsState
import co.aura.presentation.viewmodel.SettingsViewModel
import co.aura.voice.TextToSpeechEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlin.test.BeforeTest
import kotlin.test.AfterTest

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationModeTest {

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
    fun testDefaultModeIsHybrid() = runTest {
        val securityManager = FakeSecurityManager()
        val tts = FakeTextToSpeechEngine()
        val viewModel = SettingsViewModel(securityManager, tts)

        assertEquals(ConversationMode.HYBRID, viewModel.uiState.value.selectedConversationMode)
    }

    @Test
    fun testModePersistenceAndSelection() = runTest {
        val securityManager = FakeSecurityManager()
        val tts = FakeTextToSpeechEngine()
        val viewModel = SettingsViewModel(securityManager, tts)

        // Switch to COMMAND
        viewModel.onEvent(SettingsAction.UpdateConversationMode(ConversationMode.COMMAND))
        assertEquals(ConversationMode.COMMAND, viewModel.uiState.value.selectedConversationMode)
        assertEquals("COMMAND", securityManager.getSecureToken("conversation_mode"))

        // Switch to CONVERSATION
        viewModel.onEvent(SettingsAction.UpdateConversationMode(ConversationMode.CONVERSATION))
        assertEquals(ConversationMode.CONVERSATION, viewModel.uiState.value.selectedConversationMode)
        assertEquals("CONVERSATION", securityManager.getSecureToken("conversation_mode"))

        // Switch back to HYBRID
        viewModel.onEvent(SettingsAction.UpdateConversationMode(ConversationMode.HYBRID))
        assertEquals(ConversationMode.HYBRID, viewModel.uiState.value.selectedConversationMode)
        assertEquals("HYBRID", securityManager.getSecureToken("conversation_mode"))
    }
}
