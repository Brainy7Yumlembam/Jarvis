package co.aura.test

import co.aura.actions.Action
import co.aura.security.SecurityManager
import co.aura.presentation.viewmodel.SettingsAction
import co.aura.presentation.viewmodel.SettingsViewModel
import co.aura.ai.AiProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*



@OptIn(ExperimentalCoroutinesApi::class)
class SettingsTest {

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
    fun testSavingAndClearingApiKey() = runTest {
        val securityManager = FakeSecurityManager()
        val ttsEngine = FakeTextToSpeechEngine()
        val viewModel = SettingsViewModel(securityManager, ttsEngine)

        // Initial state: not configured
        assertFalse(viewModel.uiState.value.isKeyConfigured)

        // Save key
        viewModel.onEvent(SettingsAction.SaveKey("AIzaSyTestApiKey"))
        testScheduler.advanceUntilIdle()

        // Key should be configured
        assertTrue(viewModel.uiState.value.isKeyConfigured)
        assertEquals("AIzaSyTestApiKey", securityManager.getSecureToken("gemini_api_key"))

        // Clear key
        viewModel.onEvent(SettingsAction.ClearKey)
        testScheduler.advanceUntilIdle()

        // Key should no longer be configured
        assertFalse(viewModel.uiState.value.isKeyConfigured)
        val token = securityManager.getSecureToken("gemini_api_key")
        assertTrue(token.isNullOrBlank())
    }

    @Test
    fun testRecreateSettingsViewModelKeyExists() = runTest {
        val securityManager = FakeSecurityManager()
        val ttsEngine = FakeTextToSpeechEngine()
        
        // Save key via initial viewmodel
        val viewModel1 = SettingsViewModel(securityManager, ttsEngine)
        viewModel1.onEvent(SettingsAction.SaveKey("AIzaSyTestApiKey"))
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel1.uiState.value.isKeyConfigured)

        // Recreate SettingsViewModel
        val viewModel2 = SettingsViewModel(securityManager, ttsEngine)
        testScheduler.advanceUntilIdle()
        
        // Verify key is still configured
        assertTrue(viewModel2.uiState.value.isKeyConfigured)
        assertEquals("AIzaSyTestApiKey", securityManager.getSecureToken("gemini_api_key"))
    }

    @Test
    fun testRecreateSettingsViewModelKeyAbsent() = runTest {
        val securityManager = FakeSecurityManager()
        val ttsEngine = FakeTextToSpeechEngine()
        
        // Save key via initial viewmodel
        val viewModel1 = SettingsViewModel(securityManager, ttsEngine)
        viewModel1.onEvent(SettingsAction.SaveKey("AIzaSyTestApiKey"))
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel1.uiState.value.isKeyConfigured)

        // Clear key
        viewModel1.onEvent(SettingsAction.ClearKey)
        testScheduler.advanceUntilIdle()
        assertFalse(viewModel1.uiState.value.isKeyConfigured)

        // Recreate SettingsViewModel
        val viewModel2 = SettingsViewModel(securityManager, ttsEngine)
        testScheduler.advanceUntilIdle()
        
        // Verify key is absent
        assertFalse(viewModel2.uiState.value.isKeyConfigured)
        assertTrue(securityManager.getSecureToken("gemini_api_key").isNullOrBlank())
    }

    @Test
    fun testVoiceSpeechConfigurationsPersistence() = runTest {
        val securityManager = FakeSecurityManager()
        val ttsEngine = FakeTextToSpeechEngine()
        
        val viewModel = SettingsViewModel(securityManager, ttsEngine)
        
        // Initial defaults
        assertEquals("en-GB", viewModel.uiState.value.selectedLanguage)
        assertEquals(1.0f, viewModel.uiState.value.speechRate)
        assertEquals(1.0f, viewModel.uiState.value.pitch)
        assertFalse(viewModel.uiState.value.isContinuousConversation)
        
        // Update language
        viewModel.onEvent(SettingsAction.UpdateLanguage("en-US"))
        viewModel.onEvent(SettingsAction.UpdateSpeechRate(1.2f))
        viewModel.onEvent(SettingsAction.UpdatePitch(0.9f))
        viewModel.onEvent(SettingsAction.UpdateContinuousConversation(true))
        testScheduler.advanceUntilIdle()
        
        // Recreate and verify persistence
        val recreatedViewModel = SettingsViewModel(securityManager, ttsEngine)
        testScheduler.advanceUntilIdle()
        
        assertEquals("en-US", recreatedViewModel.uiState.value.selectedLanguage)
        assertEquals(1.2f, recreatedViewModel.uiState.value.speechRate)
        assertEquals(0.9f, recreatedViewModel.uiState.value.pitch)
        assertTrue(recreatedViewModel.uiState.value.isContinuousConversation)
    }

    @Test
    fun testSpeakTestVoiceAction() = runTest {
        val securityManager = FakeSecurityManager()
        val ttsEngine = FakeTextToSpeechEngine()
        val viewModel = SettingsViewModel(securityManager, ttsEngine)
        
        viewModel.onEvent(SettingsAction.SpeakTestVoice)
        testScheduler.advanceUntilIdle()
        
        assertEquals("Good evening, Sir. I am JARVIS. How may I assist you?", ttsEngine.textSpoken)
    }

    @Test
    fun testAiProviderSettingsPersistence() = runTest {
        val securityManager = FakeSecurityManager()
        val ttsEngine = FakeTextToSpeechEngine()
        val viewModel = SettingsViewModel(securityManager, ttsEngine)
        testScheduler.advanceUntilIdle()

        // 1. Default provider is GEMINI
        assertEquals(AiProviderType.GEMINI, viewModel.uiState.value.aiProviderType)

        // 2. Provider selection persists
        viewModel.onEvent(SettingsAction.UpdateAiProvider(AiProviderType.LOCAL))
        testScheduler.advanceUntilIdle()
        assertEquals(AiProviderType.LOCAL, viewModel.uiState.value.aiProviderType)
        assertEquals("LOCAL", securityManager.getSecureToken("ai_provider_type"))

        // 3. Recreated SettingsViewModel restores provider selection
        val recreatedViewModel = SettingsViewModel(securityManager, ttsEngine)
        testScheduler.advanceUntilIdle()
        assertEquals(AiProviderType.LOCAL, recreatedViewModel.uiState.value.aiProviderType)
    }
}
