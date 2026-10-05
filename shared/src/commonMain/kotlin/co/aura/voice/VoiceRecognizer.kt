package co.aura.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Lifecycle and operational state of speech recognition engines.
 */
enum class RecognitionState {
    IDLE,
    LISTENING,
    PROCESSING,
    STOPPING,
    ERROR
}

/**
 * Structured events emitted during speech recognition.
 */
sealed interface RecognitionEvent {
    /**
     * User began speaking (onBeginningOfSpeech).
     */
    data object SpeechStarted : RecognitionEvent

    /**
     * Incremental partial transcript emitted while user is speaking (onPartialResults).
     */
    data class PartialResult(val text: String) : RecognitionEvent

    /**
     * User stopped speaking (onEndOfSpeech).
     */
    data object SpeechEnded : RecognitionEvent

    /**
     * Final recognized transcript for the completed utterance (onResults).
     */
    data class FinalResult(val text: String) : RecognitionEvent

    /**
     * An error occurred during recognition.
     */
    data class Error(val errorCode: Int, val message: String, val isRecoverable: Boolean = false) : RecognitionEvent
}

/**
 * Voice recognizer contract for JARVIS.
 */
interface VoiceRecognizer {
    val audioAmplitude: StateFlow<Float>
        get() = MutableStateFlow(0f)

    val recognitionState: StateFlow<RecognitionState>
        get() = MutableStateFlow(RecognitionState.IDLE)

    /**
     * Streams structured recognition events (SpeechStarted, PartialResult, SpeechEnded, FinalResult, Error).
     */
    fun startListeningEvents(): Flow<RecognitionEvent> = kotlinx.coroutines.flow.flow {
        startListening().collect { text ->
            emit(RecognitionEvent.FinalResult(text))
        }
    }

    /**
     * Legacy/backward-compatible stream emitting raw transcript strings.
     */
    fun startListening(): Flow<String> = startListeningEvents().mapNotNull { event ->
        when (event) {
            is RecognitionEvent.PartialResult -> event.text
            is RecognitionEvent.FinalResult -> event.text
            else -> null
        }
    }


    /**
     * Stops the active recognition session safely without destroying the recognizer.
     */
    fun stopListening()

    /**
     * Cancels the active session and resets state.
     */
    fun cancel()
}

