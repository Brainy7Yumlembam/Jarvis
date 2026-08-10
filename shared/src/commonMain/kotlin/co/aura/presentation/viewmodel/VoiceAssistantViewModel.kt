package co.aura.presentation.viewmodel

import co.aura.conversation.ConversationManager
import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import co.aura.domain.model.AuraError
import co.aura.security.PermissionManager
import co.aura.voice.TextToSpeechEngine
import co.aura.voice.VoiceRecognizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

sealed interface VoiceAssistantState {
    data object Idle : VoiceAssistantState
    data class Listening(val partialTranscript: String) : VoiceAssistantState
    data class Processing(val userTranscript: String) : VoiceAssistantState
    data class Speaking(val userTranscript: String, val aiResponse: String) : VoiceAssistantState
    data class Error(val errorMessage: String) : VoiceAssistantState
}

class VoiceAssistantViewModel(
    private val voiceRecognizer: VoiceRecognizer,
    private val ttsEngine: TextToSpeechEngine,
    private val conversationManager: ConversationManager,
    private val permissionManager: PermissionManager
) : BaseViewModel<VoiceAssistantState, Unit>(VoiceAssistantState.Idle) {

    val messages = conversationManager.getMessages()


    private var listeningJob: Job? = null

    override fun onEvent(event: Unit) {}

    fun startListening() {
        // Stop any active TTS vocalization
        stopSpeaking()

        if (!permissionManager.hasPermission("android.permission.RECORD_AUDIO")) {
            AuraLogger.e(LogCategory.SECURITY, "Microphone permission is missing")
            updateState { VoiceAssistantState.Error("Audio recording permission required.") }
            return
        }

        AuraLogger.i(LogCategory.VOICE, "Starting active microphone listening...")
        updateState { VoiceAssistantState.Listening("") }

        listeningJob?.cancel()
        listeningJob = launchInScope {
            voiceRecognizer.startListening()
                .catch { throwable ->
                    AuraLogger.e(LogCategory.VOICE, "Speech recognition error", throwable)
                    updateState { VoiceAssistantState.Error(throwable.message ?: "Failed to recognize speech.") }
                }
                .collect { transcript ->
                    updateState { VoiceAssistantState.Listening(transcript) }
                    // If transcript matches final output (or call stops), proceed to process it.
                    // For Android SpeechRecognizer, results callback closes/completes the flow automatically.
                }
            
            // Once the flow completes successfully, trigger Gemini processing on the last captured text
            val lastState = uiState.value
            if (lastState is VoiceAssistantState.Listening && lastState.partialTranscript.isNotBlank()) {
                processQuery(lastState.partialTranscript)
            } else {
                updateState { VoiceAssistantState.Idle }
            }
        }
    }

    fun stopListening() {
        AuraLogger.i(LogCategory.VOICE, "Stopping speech recognition stream...")
        voiceRecognizer.stopListening()
        listeningJob?.cancel()
        listeningJob = null
    }

    fun stopSpeaking() {
        AuraLogger.i(LogCategory.VOICE, "Stopping TTS speech playback...")
        ttsEngine.stop()
        if (uiState.value is VoiceAssistantState.Speaking) {
            updateState { VoiceAssistantState.Idle }
        }
    }

    private suspend fun processQuery(text: String) {
        AuraLogger.i(LogCategory.AI, "Processing query text: $text")
        updateState { VoiceAssistantState.Processing(text) }

        try {
            val response = conversationManager.processUserMessage(text)
            AuraLogger.i(LogCategory.AI, "Received AI response content. Starting TTS...")
            updateState { VoiceAssistantState.Speaking(text, response) }
            
            val speakSuccess = ttsEngine.speak(response)
            if (!speakSuccess) {
                AuraLogger.e(LogCategory.VOICE, "Failed to speak response.")
            }
            
            // Reset to idle after speaking finishes
            if (uiState.value is VoiceAssistantState.Speaking) {
                updateState { VoiceAssistantState.Idle }
            }
        } catch (error: AuraError) {
            AuraLogger.e(LogCategory.AI, "Cognitive error: ${error.message}")
            updateState { VoiceAssistantState.Error(error.message) }
        } catch (e: Exception) {
            AuraLogger.e(LogCategory.AI, "Unexpected exception during processing", e)
            updateState { VoiceAssistantState.Error(e.message ?: "An unknown error occurred.") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopListening()
        stopSpeaking()
    }
}
