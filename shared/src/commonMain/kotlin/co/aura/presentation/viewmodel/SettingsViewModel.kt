package co.aura.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import co.aura.security.SecurityManager
import co.aura.voice.TextToSpeechEngine
import co.aura.ai.AiProviderType
import co.aura.ai.GeminiCredential
import co.aura.ai.GeminiCredentialManager
import co.aura.ai.CredentialStatus
import kotlinx.coroutines.launch

import co.aura.conversation.ConversationMode

data class SettingsState(
    val isKeyConfigured: Boolean = false,
    val selectedVoice: String = "JARVIS",
    val actualSelectedVoice: String = "System Default",
    val selectedLanguage: String = "en-GB",
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val isContinuousConversation: Boolean = false,
    val aiProviderType: AiProviderType = AiProviderType.GEMINI,
    val geminiCredentials: List<GeminiCredential> = emptyList(),
    val activeGeminiProject: String = "None",
    val configuredProjectsCount: Int = 0,
    val selectedConversationMode: ConversationMode = ConversationMode.HYBRID
)

sealed interface SettingsAction {
    data class SaveKey(val key: String) : SettingsAction
    object ClearKey : SettingsAction
    object RefreshStatus : SettingsAction
    data class UpdateLanguage(val language: String) : SettingsAction
    data class UpdateSpeechRate(val rate: Float) : SettingsAction
    data class UpdatePitch(val pitch: Float) : SettingsAction
    data class UpdateContinuousConversation(val enabled: Boolean) : SettingsAction
    object SpeakTestVoice : SettingsAction
    data class UpdateAiProvider(val type: AiProviderType) : SettingsAction
    data class SaveProjectCredential(val slot: Int, val label: String, val apiKey: String?, val enabled: Boolean) : SettingsAction
    data class RemoveProjectCredential(val slot: Int) : SettingsAction
    data class UpdateConversationMode(val mode: ConversationMode) : SettingsAction
}

class SettingsViewModel(
    private val securityManager: SecurityManager,
    private val ttsEngine: TextToSpeechEngine,
    private val geminiCredentialManager: GeminiCredentialManager
) : BaseViewModel<SettingsState, SettingsAction>(SettingsState()) {

    constructor(
        securityManager: SecurityManager,
        ttsEngine: TextToSpeechEngine
    ) : this(securityManager, ttsEngine, GeminiCredentialManager(securityManager))

    init {
        checkKeyStatus()
        loadVoiceSettings()
        launchInScope {
            geminiCredentialManager.credentials.collect { list ->
                val activeSlot = geminiCredentialManager.activeSlot.value
                val activeLabel = activeSlot?.let { slot ->
                    list.firstOrNull { it.slot == slot }?.label ?: "Gemini Project ${if (slot < 10) "0$slot" else "$slot"}"
                } ?: "None"
                val configuredCount = list.count { it.status != CredentialStatus.EMPTY }
                updateState {
                    it.copy(
                        geminiCredentials = list,
                        activeGeminiProject = activeLabel,
                        configuredProjectsCount = configuredCount
                    )
                }
            }
        }
        launchInScope {
            geminiCredentialManager.activeSlot.collect { activeSlot ->
                val list = geminiCredentialManager.credentials.value
                val activeLabel = activeSlot?.let { slot ->
                    list.firstOrNull { it.slot == slot }?.label ?: "Gemini Project ${if (slot < 10) "0$slot" else "$slot"}"
                } ?: "None"
                updateState {
                    it.copy(activeGeminiProject = activeLabel)
                }
            }
        }
    }

    override fun onEvent(event: SettingsAction) {
        when (event) {
            is SettingsAction.SaveKey -> saveApiKey(event.key)
            is SettingsAction.ClearKey -> clearApiKey()
            is SettingsAction.RefreshStatus -> checkKeyStatus()
            is SettingsAction.UpdateLanguage -> updateLanguage(event.language)
            is SettingsAction.UpdateSpeechRate -> updateSpeechRate(event.rate)
            is SettingsAction.UpdatePitch -> updatePitch(event.pitch)
            is SettingsAction.UpdateContinuousConversation -> updateContinuous(event.enabled)
            is SettingsAction.SpeakTestVoice -> playTestVoice()
            is SettingsAction.UpdateAiProvider -> updateAiProvider(event.type)
            is SettingsAction.SaveProjectCredential -> saveProjectCredential(event.slot, event.label, event.apiKey, event.enabled)
            is SettingsAction.RemoveProjectCredential -> removeProjectCredential(event.slot)
            is SettingsAction.UpdateConversationMode -> updateConversationMode(event.mode)
        }
    }

    private fun checkKeyStatus() {
        launchInScope {
            val key = securityManager.getSecureToken("gemini_api_key")
            val isConfigured = !key.isNullOrBlank()
            updateState { it.copy(isKeyConfigured = isConfigured) }
        }
    }

    private fun loadVoiceSettings() {
        launchInScope {
            val lang = securityManager.getSecureToken("voice_language") ?: "en-GB"
            val rate = securityManager.getSecureToken("voice_speech_rate")?.toFloatOrNull() ?: 1.0f
            val ptch = securityManager.getSecureToken("voice_pitch")?.toFloatOrNull() ?: 1.0f
            val continuous = securityManager.getSecureToken("continuous_conversation") == "true"
            val actualVoice = securityManager.getSecureToken("selected_voice_name") ?: "System Default"
            val providerStr = securityManager.getSecureToken("ai_provider_type") ?: "GEMINI"
            val providerType = try {
                AiProviderType.valueOf(providerStr)
            } catch (e: Exception) {
                AiProviderType.GEMINI
            }
            val modeStr = securityManager.getSecureToken("conversation_mode") ?: "HYBRID"
            val mode = try {
                ConversationMode.valueOf(modeStr)
            } catch (e: Exception) {
                ConversationMode.HYBRID
            }
            updateState {
                it.copy(
                    selectedLanguage = lang,
                    speechRate = rate,
                    pitch = ptch,
                    isContinuousConversation = continuous,
                    actualSelectedVoice = actualVoice,
                    aiProviderType = providerType,
                    selectedConversationMode = mode
                )
            }
            geminiCredentialManager.refresh()
        }
    }

    private fun saveApiKey(newKey: String) {
        launchInScope {
            securityManager.saveSecureToken("gemini_api_key", newKey)
            checkKeyStatus()
        }
    }

    private fun clearApiKey() {
        launchInScope {
            securityManager.saveSecureToken("gemini_api_key", "")
            checkKeyStatus()
        }
    }

    private fun updateLanguage(language: String) {
        launchInScope {
            securityManager.saveSecureToken("voice_language", language)
            updateState { it.copy(selectedLanguage = language) }
        }
    }

    private fun updateSpeechRate(rate: Float) {
        launchInScope {
            securityManager.saveSecureToken("voice_speech_rate", rate.toString())
            updateState { it.copy(speechRate = rate) }
        }
    }

    private fun updatePitch(pitch: Float) {
        launchInScope {
            securityManager.saveSecureToken("voice_pitch", pitch.toString())
            updateState { it.copy(pitch = pitch) }
        }
    }

    private fun updateContinuous(enabled: Boolean) {
        launchInScope {
            securityManager.saveSecureToken("continuous_conversation", enabled.toString())
            updateState { it.copy(isContinuousConversation = enabled) }
        }
    }

    private fun playTestVoice() {
        launchInScope {
            val testPhrase = "Good evening, Sir. I am JARVIS. How may I assist you?"
            ttsEngine.speak(testPhrase)
            val actualVoice = securityManager.getSecureToken("selected_voice_name") ?: "System Default"
            updateState { it.copy(actualSelectedVoice = actualVoice) }
        }
    }

    private fun updateAiProvider(type: AiProviderType) {
        launchInScope {
            securityManager.saveSecureToken("ai_provider_type", type.name)
            updateState { it.copy(aiProviderType = type) }
        }
    }

    private fun saveProjectCredential(slot: Int, label: String, apiKey: String?, enabled: Boolean) {
        launchInScope {
            geminiCredentialManager.saveCredential(slot, label, apiKey, enabled)
            checkKeyStatus() // Sync configured status check
        }
    }

    private fun removeProjectCredential(slot: Int) {
        launchInScope {
            geminiCredentialManager.removeCredential(slot)
            checkKeyStatus() // Sync configured status check
        }
    }

    private fun updateConversationMode(mode: ConversationMode) {
        launchInScope {
            securityManager.saveSecureToken("conversation_mode", mode.name)
            updateState { it.copy(selectedConversationMode = mode) }
        }
    }
}
