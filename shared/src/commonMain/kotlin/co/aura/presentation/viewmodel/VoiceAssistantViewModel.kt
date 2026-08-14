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
    data object Paused : VoiceAssistantState
}

class VoiceAssistantViewModel(
    private val voiceRecognizer: VoiceRecognizer,
    private val ttsEngine: TextToSpeechEngine,
    private val conversationManager: ConversationManager,
    private val permissionManager: PermissionManager,
    private val securityManager: co.aura.security.SecurityManager
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
        val lastState = uiState.value
        val transcript = if (lastState is VoiceAssistantState.Listening) lastState.partialTranscript else ""

        voiceRecognizer.stopListening()
        listeningJob?.cancel()
        listeningJob = null

        if (transcript.isNotBlank()) {
            launchInScope {
                processQuery(transcript)
            }
        } else {
            updateState { VoiceAssistantState.Idle }
        }
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
            
            val formattedResponse = co.aura.voice.SpeechTextFormatter.format(response)
            val speakSuccess = ttsEngine.speak(formattedResponse)
            if (!speakSuccess) {
                AuraLogger.e(LogCategory.VOICE, "Failed to speak response.")
            }
            
            // Check continuous conversation mode setting
            val isContinuous = securityManager.getSecureToken("continuous_conversation") == "true"
            if (isContinuous && uiState.value is VoiceAssistantState.Speaking) {
                startListening()
            } else if (uiState.value is VoiceAssistantState.Speaking) {
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

    fun onPause() {
        AuraLogger.i(LogCategory.VOICE, "Lifecycle onPause: cleaning up active listeners and speech...")
        
        // Stop SpeechRecognizer
        voiceRecognizer.cancel()
        listeningJob?.cancel()
        listeningJob = null
        
        // Stop TTS
        ttsEngine.stop()
        
        // Update state to Paused if we were actively listening, processing, or speaking
        val lastState = uiState.value
        if (lastState is VoiceAssistantState.Listening ||
            lastState is VoiceAssistantState.Processing ||
            lastState is VoiceAssistantState.Speaking
        ) {
            updateState { VoiceAssistantState.Paused }
        }
    }

    fun onResume() {
        AuraLogger.i(LogCategory.VOICE, "Lifecycle onResume: checking state restoration...")
        
        val lastState = uiState.value
        if (lastState is VoiceAssistantState.Paused) {
            updateState { VoiceAssistantState.Idle }
            launchInScope {
                val isContinuous = try {
                    securityManager.getSecureToken("continuous_conversation") == "true"
                } catch (e: Exception) {
                    false
                }
                if (isContinuous) {
                    startListening()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopListening()
        stopSpeaking()
    }
}
