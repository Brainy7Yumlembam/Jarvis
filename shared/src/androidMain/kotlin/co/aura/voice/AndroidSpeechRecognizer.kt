package co.aura.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Android SpeechRecognizer implementation adhering to the single-instance managed pattern,
 * strict RecognitionState lifecycle, and typed RecognitionEvent stream.
 */
class AndroidSpeechRecognizer(
    private val context: Context
) : VoiceRecognizer {

    private val _audioAmplitude = MutableStateFlow(0f)
    override val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    private val _recognitionState = MutableStateFlow(RecognitionState.IDLE)
    override val recognitionState: StateFlow<RecognitionState> = _recognitionState.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private val originalVolumes = mutableMapOf<Int, Int>()

    private val earconStreams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_NOTIFICATION
    )

    /**
     * Completely zeroes out the volume of audio channels used by Android's SpeechRecognizer
     * service for playing earcon chimes ("tudung" / beep).
     */
    @Synchronized
    private fun muteEarconStreams() {
        val am = audioManager ?: return
        for (stream in earconStreams) {
            try {
                val current = am.getStreamVolume(stream)
                // Only record original if not already recorded, preserving real user volume
                if (!originalVolumes.containsKey(stream)) {
                    originalVolumes[stream] = current
                }
                am.setStreamVolume(stream, 0, 0)
            } catch (e: Exception) {
                // Safeguard against OEM-specific restrictions or DND policy on API 24+
            }
        }
    }

    /**
     * Restores all modified audio streams back to their exact original volume levels.
     */
    @Synchronized
    private fun restoreEarconStreams() {
        val am = audioManager ?: return
        if (originalVolumes.isEmpty()) return
        for ((stream, volume) in originalVolumes) {
            try {
                am.setStreamVolume(stream, volume, 0)
            } catch (e: Exception) {
                // Safe fallback
            }
        }
        originalVolumes.clear()
    }

    @Volatile
    private var isListeningRequested = false

    @Volatile
    private var isContinuous = true

    @Volatile
    private var lastPartialTranscript = ""

    private var activeSessionId: Long = 0L
    private var utteranceCounter: Long = 0L
    private var pendingRestartRunnable: Runnable? = null

    private fun setInternalState(state: RecognitionState) {
        if (_recognitionState.value != state) {
            AuraLogger.d(LogCategory.VOICE, "[VOICE] session=$activeSessionId State: ${_recognitionState.value} -> $state")
            _recognitionState.value = state
        }
    }

    private fun cancelPendingRestart() {
        pendingRestartRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingRestartRunnable = null
        }
    }

    private fun createRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer {
        val current = speechRecognizer
        if (current != null) return current

        AuraLogger.d(LogCategory.VOICE, "[VOICE] session=$activeSessionId recognizer recreated")
        val newRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = newRecognizer
        return newRecognizer
    }

    override fun startListeningEvents(): Flow<RecognitionEvent> = callbackFlow {
        val sessionId = ++activeSessionId
        isListeningRequested = true
        lastPartialTranscript = ""
        cancelPendingRestart()
        setInternalState(RecognitionState.LISTENING)

        AuraLogger.i(LogCategory.VOICE, "[VOICE] session=$sessionId startListeningEvents requested")

        val intent = createRecognizerIntent()
        var consecutiveClientErrors = 0

        fun scheduleRestart(delayMs: Long = 150L) {
            if (!isListeningRequested || sessionId != activeSessionId) return
            cancelPendingRestart()

            val runnable = Runnable {
                pendingRestartRunnable = null
                if (isListeningRequested && sessionId == activeSessionId) {
                    try {
                        setInternalState(RecognitionState.LISTENING)
                        val recognizer = ensureRecognizer()
                        muteEarconStreams()
                        AuraLogger.d(LogCategory.VOICE, "[VOICE] session=$sessionId SpeechRecognizer.startListening() executing after delay")
                        recognizer.startListening(intent)
                    } catch (e: Exception) {
                        restoreEarconStreams()
                        AuraLogger.e(LogCategory.VOICE, "[VOICE] session=$sessionId Failed to restart speech recognition", e)
                    }
                } else {
                    AuraLogger.d(LogCategory.VOICE, "[VOICE] session=$sessionId Skipping restart: state=${_recognitionState.value}, requested=$isListeningRequested")
                }
            }
            pendingRestartRunnable = runnable
            mainHandler.postDelayed(runnable, delayMs)
        }

        mainHandler.post {
            try {
                val recognizer = ensureRecognizer()
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        AuraLogger.i(LogCategory.VOICE, "[VOICE] session=$sessionId recognizer ready")
                        consecutiveClientErrors = 0
                        trySend(RecognitionEvent.ReadyForSpeech)
                    }

                    override fun onBeginningOfSpeech() {
                        AuraLogger.i(LogCategory.VOICE, "[VOICE] session=$sessionId USER SPEECH STARTED")
                        consecutiveClientErrors = 0
                        trySend(RecognitionEvent.SpeechStarted)
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        // Normalize typical rmsdB [-2.0f, 10.0f] into [0.0f, 1.0f]
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                        _audioAmplitude.value = normalized
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partialResult = matches?.firstOrNull() ?: ""
                        if (partialResult.isNotBlank()) {
                            lastPartialTranscript = partialResult
                            AuraLogger.d(LogCategory.VOICE, "[VOICE] session=$sessionId PARTIAL: $partialResult")
                            trySend(RecognitionEvent.PartialResult(partialResult))
                        }
                    }

                    override fun onEndOfSpeech() {
                        AuraLogger.i(LogCategory.VOICE, "[VOICE] session=$sessionId USER SPEECH ENDED")
                        _audioAmplitude.value = 0f
                        muteEarconStreams()
                        trySend(RecognitionEvent.SpeechEnded)
                    }

                    override fun onError(error: Int) {
                        _audioAmplitude.value = 0f
                        val errorMessage = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                            SpeechRecognizer.ERROR_NETWORK -> "Network error"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech matches found"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
                            SpeechRecognizer.ERROR_SERVER -> "Server-side error"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input timeout"
                            else -> "Unknown speech recognizer error ($error)"
                        }

                        AuraLogger.d(LogCategory.VOICE, "[VOICE] session=$sessionId onError: code=$error msg=\"$errorMessage\"")

                        // Recoverable errors in continuous mode
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            if (isListeningRequested && isContinuous) {
                                scheduleRestart(100L)
                                return
                            }
                        } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                            AuraLogger.w(LogCategory.VOICE, "[VOICE] session=$sessionId Recognizer busy, recreating instance...")
                            try {
                                speechRecognizer?.cancel()
                                speechRecognizer?.destroy()
                            } catch (e: Exception) {
                                // Ignore
                            }
                            speechRecognizer = null
                            if (isListeningRequested && isContinuous) {
                                scheduleRestart(300L)
                                return
                            }
                        } else if (error == SpeechRecognizer.ERROR_CLIENT) {
                            // Intentional stop protection
                            if (!isListeningRequested) {
                                AuraLogger.d(LogCategory.VOICE, "[VOICE] session=$sessionId Ignoring ERROR_CLIENT: listening not actively requested")
                                restoreEarconStreams()
                                return
                            }

                            consecutiveClientErrors++
                            AuraLogger.w(LogCategory.VOICE, "[VOICE] session=$sessionId ERROR_CLIENT (attempt $consecutiveClientErrors of 3)")

                            // Destroy stale recognizer instance
                            try {
                                speechRecognizer?.cancel()
                                speechRecognizer?.destroy()
                            } catch (e: Exception) {
                                // Ignore
                            }
                            speechRecognizer = null

                            // Bounded exponential backoff: 300ms, 600ms, 1200ms
                            if (consecutiveClientErrors <= 3 && isContinuous && isListeningRequested) {
                                val backoffDelay = 300L * (1 shl (consecutiveClientErrors - 1))
                                scheduleRestart(backoffDelay)
                                return
                            } else {
                                AuraLogger.e(LogCategory.VOICE, "[VOICE] session=$sessionId Repeated ERROR_CLIENT ($consecutiveClientErrors). Halting auto-restart.")
                            }
                        } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == SpeechRecognizer.ERROR_AUDIO || error == SpeechRecognizer.ERROR_SERVER) {
                            if (isContinuous && isListeningRequested) {
                                AuraLogger.w(LogCategory.VOICE, "[VOICE] session=$sessionId Recoverable error $error. Retrying in 800ms...")
                                scheduleRestart(800L)
                                return
                            }
                        }

                        restoreEarconStreams()
                        setInternalState(RecognitionState.ERROR)
                        trySend(RecognitionEvent.Error(error, errorMessage, error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT))

                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || !isContinuous) {
                            close(Exception(errorMessage))
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        _audioAmplitude.value = 0f
                        consecutiveClientErrors = 0
                        restoreEarconStreams()
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val resultsText = matches?.firstOrNull() ?: ""
                        val finalText = resultsText.takeIf { it.isNotBlank() } ?: lastPartialTranscript

                        if (finalText.isNotBlank()) {
                            val utteranceId = ++utteranceCounter
                            setInternalState(RecognitionState.PROCESSING)
                            cancelPendingRestart()
                            AuraLogger.i(LogCategory.VOICE, "[VOICE] utteranceId=$utteranceId session=$sessionId FINAL: \"$finalText\"")
                            trySend(RecognitionEvent.FinalResult(finalText, utteranceId))
                        } else {
                            AuraLogger.d(LogCategory.VOICE, "[VOICE] session=$sessionId Empty onResults")
                            if (isListeningRequested && isContinuous) {
                                scheduleRestart(100L)
                            }
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                muteEarconStreams()
                mainHandler.postDelayed({
                    if (isListeningRequested && sessionId == activeSessionId && _recognitionState.value == RecognitionState.LISTENING) {
                        try {
                            AuraLogger.d(LogCategory.VOICE, "[VOICE] session=$sessionId SpeechRecognizer.startListening() initiated")
                            recognizer.startListening(intent)
                        } catch (e: Exception) {
                            restoreEarconStreams()
                            AuraLogger.e(LogCategory.VOICE, "[VOICE] session=$sessionId Exception starting speech recognizer", e)
                            setInternalState(RecognitionState.ERROR)
                            close(e)
                        }
                    }
                }, 50L)
            } catch (e: Exception) {
                restoreEarconStreams()
                AuraLogger.e(LogCategory.VOICE, "[VOICE] session=$sessionId Exception initializing speech recognizer", e)
                setInternalState(RecognitionState.ERROR)
                close(e)
            }
        }

        awaitClose {
            AuraLogger.d(LogCategory.VOICE, "[VOICE] session=$sessionId awaitClose: cleaning up")
            isListeningRequested = false
            cancelPendingRestart()
            _audioAmplitude.value = 0f
            setInternalState(RecognitionState.IDLE)
            mainHandler.post {
                try {
                    AuraLogger.d(LogCategory.VOICE, "[VOICE] session=$sessionId SpeechRecognizer.stopListening() / cancel() in awaitClose")
                    speechRecognizer?.stopListening()
                    speechRecognizer?.cancel()
                } catch (e: Exception) {
                    // Ignore
                } finally {
                    restoreEarconStreams()
                }
            }
        }
    }

    override fun startListening(): Flow<String> = startListeningEvents().mapNotNull { event ->
        when (event) {
            is RecognitionEvent.PartialResult -> event.text
            is RecognitionEvent.FinalResult -> event.text
            else -> null
        }
    }

    override fun stopListening() {
        AuraLogger.i(LogCategory.VOICE, "[VOICE] SpeechRecognizer.stopListening() requested")
        isListeningRequested = false
        cancelPendingRestart()
        _audioAmplitude.value = 0f
        setInternalState(RecognitionState.STOPPING)
        muteEarconStreams()
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                setInternalState(RecognitionState.IDLE)
            } catch (e: Exception) {
                setInternalState(RecognitionState.IDLE)
            } finally {
                mainHandler.postDelayed({ restoreEarconStreams() }, 400L)
            }
        }
    }

    override fun cancel() {
        AuraLogger.i(LogCategory.VOICE, "[VOICE] SpeechRecognizer.cancel() requested")
        isListeningRequested = false
        cancelPendingRestart()
        _audioAmplitude.value = 0f
        setInternalState(RecognitionState.IDLE)
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
                AuraLogger.d(LogCategory.VOICE, "[VOICE] recognizer stopped/cancelled and destroyed")
            } catch (e: Exception) {
                // Ignore
            } finally {
                restoreEarconStreams()
            }
        }
    }

    fun setContinuous(enabled: Boolean) {
        this.isContinuous = enabled
    }
}
