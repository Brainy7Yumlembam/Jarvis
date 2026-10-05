package co.aura.presentation.viewmodel

import co.aura.conversation.ConversationManager
import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import co.aura.domain.model.AuraError
import co.aura.security.PermissionManager
import co.aura.voice.AudioVisualizationState
import co.aura.voice.AudioVisualizerController
import co.aura.voice.DefaultAudioVisualizerController
import co.aura.voice.DefaultInterruptionDetector
import co.aura.voice.InterruptionDetector
import co.aura.voice.InterruptionMonitor
import co.aura.voice.NoOpInterruptionMonitor
import co.aura.voice.RecognitionEvent
import co.aura.voice.SpeechTextFormatter
import co.aura.voice.DefaultTtsQueuePlayer
import co.aura.voice.TextToSpeechEngine
import co.aura.voice.TtsQueuePlayer
import co.aura.voice.VoiceRecognizer
import co.aura.voice.VoiceSessionCoordinator
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * Events for the deterministic JARVIS voice state machine.
 */
sealed interface VoiceEvent {
    data object StartListening : VoiceEvent
    data object StopListening : VoiceEvent
    data class FinishUserSpeech(val text: String? = null) : VoiceEvent
    data object CancelListening : VoiceEvent
    data object Interrupted : VoiceEvent
    data class SpeechRecognized(val text: String) : VoiceEvent
    data class ErrorOccurred(val message: String) : VoiceEvent
}

/**
 * Speaker tag for the real-time JARVIS subtitle system.
 */
enum class SubtitleSpeaker {
    USER,
    JARVIS
}

/**
 * Represents a single subtitle message in the holographic subtitle feed.
 */
data class SubtitleItem(
    val id: String,
    val speaker: SubtitleSpeaker,
    val text: String,
    val isLive: Boolean = false
)

/**
 * UI State for the holographic subtitle area at the bottom of the JARVIS screen.
 */
data class SubtitleUiState(
    val history: List<SubtitleItem> = emptyList(),
    val currentUserSubtitle: String = "",
    val currentJarvisSubtitle: String = "",
    val activeSpeaker: SubtitleSpeaker? = null
)

/**
 * Authoritative states for the JARVIS Voice Assistant.
 */
sealed interface JarvisVoiceState

sealed interface VoiceAssistantState : JarvisVoiceState {
    data object Idle : VoiceAssistantState
    data class Listening(val partialTranscript: String = "") : VoiceAssistantState
    data class Processing(val userTranscript: String) : VoiceAssistantState
    data class Speaking(val userTranscript: String, val aiResponse: String) : VoiceAssistantState
    data object Paused : VoiceAssistantState
    data class Error(val message: String) : VoiceAssistantState {
        val errorMessage: String get() = message
    }
}

class VoiceAssistantViewModel(
    private val voiceRecognizer: VoiceRecognizer,
    private val ttsEngine: TextToSpeechEngine,
    private val conversationManager: ConversationManager,
    private val permissionManager: PermissionManager,
    private val securityManager: co.aura.security.SecurityManager,
    private val interruptionDetector: InterruptionDetector = DefaultInterruptionDetector(),
    private val visualizerController: AudioVisualizerController = DefaultAudioVisualizerController(),
    private val interruptionMonitor: InterruptionMonitor = NoOpInterruptionMonitor(),
    private val defaultContinuousConversation: Boolean = false,
    private val ttsQueuePlayer: TtsQueuePlayer? = null
) : BaseViewModel<VoiceAssistantState, VoiceEvent>(VoiceAssistantState.Idle) {

    val messages = conversationManager.getMessages()
    val visualizerState: StateFlow<AudioVisualizationState> = visualizerController.visualizationState

    private val _subtitleState = MutableStateFlow(SubtitleUiState())
    val subtitleState: StateFlow<SubtitleUiState> = _subtitleState.asStateFlow()

    @Volatile
    private var activeRequestId: Long = 0L

    private val player: TtsQueuePlayer by lazy {
        ttsQueuePlayer ?: DefaultTtsQueuePlayer(
            ttsEngine = ttsEngine,
            coroutineScope = viewModelScope,
            onUtteranceStart = { chunk ->
                startSpeechCadenceSimulation(chunk, activeRequestId)
            },
            onUtteranceEnd = {
                visualizerController.updateSpeechAmplitude(0f)
            },
            onPlaybackComplete = {
                handlePlaybackFinished(activeRequestId)
            },
            onPlaybackCompleteWithId = { finishedReqId ->
                handlePlaybackFinished(finishedReqId)
            }
        )
    }

    private var listeningJob: Job? = null
    private var activeAiStreamJob: Job? = null
    private var activeAiJob: Job? = null
    private var activeTtsJob: Job? = null
    private var interruptionJob: Job? = null
    private var speechCadenceJob: Job? = null

    @Volatile
    private var isRequestInProgress: Boolean = false

    @Volatile
    private var lastProcessedTranscript: String = ""

    @Volatile
    private var lastProcessedTimestamp: Long = 0L

    override fun onEvent(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.StartListening -> startListening()
            is VoiceEvent.StopListening -> finishUserSpeech()
            is VoiceEvent.FinishUserSpeech -> finishUserSpeech(event.text)
            is VoiceEvent.CancelListening -> cancelListening()
            is VoiceEvent.Interrupted -> interruptJarvis()
            is VoiceEvent.SpeechRecognized -> {
                if (event.text.isNotBlank()) {
                    finishUserSpeech(event.text)
                }
            }
            is VoiceEvent.ErrorOccurred -> {
                updateState { VoiceAssistantState.Error(event.message) }
            }
        }
    }

    /**
     * Starts listening on the microphone.
     * Idempotent: Does not create duplicate sessions if already listening.
     * Safe: Does not start normal listening if currently speaking or processing.
     */
    fun startListening() {
        val currentState = uiState.value

        // Guard against starting normal listening while busy
        if (currentState is VoiceAssistantState.Speaking) {
            AuraLogger.d(LogCategory.VOICE, "[JARVIS] Ignoring startListening: assistant is currently SPEAKING")
            return
        }
        if (currentState is VoiceAssistantState.Processing) {
            AuraLogger.d(LogCategory.VOICE, "[JARVIS] Ignoring startListening: assistant is currently PROCESSING")
            return
        }
        if (currentState is VoiceAssistantState.Listening && listeningJob?.isActive == true) {
            AuraLogger.d(LogCategory.VOICE, "[JARVIS] Already actively listening")
            return
        }

        if (!permissionManager.hasPermission("android.permission.RECORD_AUDIO")) {
            AuraLogger.e(LogCategory.SECURITY, "[JARVIS] Microphone permission is missing")
            updateState { VoiceAssistantState.Error("Audio recording permission required.") }
            return
        }

        AuraLogger.i(LogCategory.VOICE, "[VOICE] STATE transition: ${currentState::class.simpleName ?: "IDLE"} -> LISTENING")
        AuraLogger.i(LogCategory.VOICE, "[JARVIS] STARTING LISTENING")

        visualizerController.updateSpeechAmplitude(0f)
        updateState { VoiceAssistantState.Listening("") }

        listeningJob?.cancel()
        listeningJob = launchInScope {
            // Collect audio amplitude for microphone visualizer
            launch {
                voiceRecognizer.audioAmplitude.collect { amplitude ->
                    if (uiState.value is VoiceAssistantState.Listening) {
                        visualizerController.updateMicrophoneAmplitude(amplitude)
                    }
                }
            }

            voiceRecognizer.startListeningEvents()
                .catch { throwable ->
                    AuraLogger.e(LogCategory.VOICE, "[JARVIS] Speech recognition error", throwable)
                    updateState { VoiceAssistantState.Error(throwable.message ?: "Failed to recognize speech.") }
                }
                .collect { event ->
                    handleRecognitionEvent(event)
                }

            // Fallback for completion of finite flows (e.g. desktop mock or test fakes)
            val lastState = uiState.value
            if (lastState is VoiceAssistantState.Listening && lastState.partialTranscript.isNotBlank()) {
                finishUserSpeech(lastState.partialTranscript)
            } else if (lastState is VoiceAssistantState.Listening) {
                val isContinuous = isContinuousModeEnabled()
                if (!isContinuous) {
                    updateState { VoiceAssistantState.Idle }
                }
            }
        }
    }

    fun updateUserSubtitle(text: String, isFinal: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        _subtitleState.update { current ->
            val list = current.history.toMutableList()
            val lastItem = list.lastOrNull()

            if (lastItem != null && lastItem.speaker == SubtitleSpeaker.USER && lastItem.isLive) {
                val updatedIndex = list.size - 1
                list[updatedIndex] = lastItem.copy(text = trimmed, isLive = !isFinal)
            } else if (lastItem != null && lastItem.speaker == SubtitleSpeaker.USER && isFinal && lastItem.text == trimmed) {
                // Already finalized with identical text, no-op
            } else {
                list.add(
                    SubtitleItem(
                        id = "user_${System.currentTimeMillis()}",
                        speaker = SubtitleSpeaker.USER,
                        text = trimmed,
                        isLive = !isFinal
                    )
                )
            }

            val bounded = if (list.size > 4) list.takeLast(4) else list
            current.copy(
                history = bounded,
                currentUserSubtitle = trimmed,
                activeSpeaker = SubtitleSpeaker.USER
            )
        }
    }

    fun updateJarvisSubtitle(fullText: String, isComplete: Boolean, requestId: Long? = null) {
        if (requestId != null && requestId != activeRequestId) {
            AuraLogger.w(LogCategory.AI, "[AI] Discarding stale subtitle update for Req #$requestId (active is #$activeRequestId)")
            return
        }

        val trimmed = fullText.trim()
        if (trimmed.isEmpty()) return

        _subtitleState.update { current ->
            val list = current.history.toMutableList()
            val lastItem = list.lastOrNull()

            if (lastItem != null && lastItem.speaker == SubtitleSpeaker.JARVIS && lastItem.isLive) {
                val updatedIndex = list.size - 1
                list[updatedIndex] = lastItem.copy(text = trimmed, isLive = !isComplete)
            } else {
                list.add(
                    SubtitleItem(
                        id = "jarvis_${System.currentTimeMillis()}",
                        speaker = SubtitleSpeaker.JARVIS,
                        text = trimmed,
                        isLive = !isComplete
                    )
                )
            }

            val bounded = if (list.size > 4) list.takeLast(4) else list
            current.copy(
                history = bounded,
                currentJarvisSubtitle = trimmed,
                activeSpeaker = if (isComplete) null else SubtitleSpeaker.JARVIS
            )
        }
    }

    private fun handleInterruptionInSubtitles() {
        _subtitleState.update { current ->
            val list = current.history.toMutableList()
            val lastItem = list.lastOrNull()
            if (lastItem != null && lastItem.speaker == SubtitleSpeaker.JARVIS && lastItem.isLive) {
                list[list.size - 1] = lastItem.copy(isLive = false)
            }
            current.copy(
                history = list,
                activeSpeaker = null
            )
        }
    }

    private fun handleRecognitionEvent(event: RecognitionEvent) {
        when (event) {
            is RecognitionEvent.SpeechStarted -> {
                // Speech begun; recognizer logged [JARVIS] USER SPEECH STARTED
            }
            is RecognitionEvent.PartialResult -> {
                val currentState = uiState.value
                // If listening, update the partial transcript display and real-time subtitle
                if (currentState is VoiceAssistantState.Listening) {
                    updateState { VoiceAssistantState.Listening(event.text) }
                }
                updateUserSubtitle(event.text, isFinal = false)
            }
            is RecognitionEvent.SpeechEnded -> {
                // Speech ended; recognizer logged [JARVIS] USER SPEECH ENDED
                // Transcript is preserved waiting for FinalResult
            }
            is RecognitionEvent.FinalResult -> {
                // Authoritative trigger: user finished speaking a command!
                AuraLogger.i(LogCategory.VOICE, "[JARVIS] Final recognition event received: ${event.text}")
                updateUserSubtitle(event.text, isFinal = true)
                finishUserSpeech(event.text)
            }
            is RecognitionEvent.Error -> {
                if (!event.isRecoverable) {
                    AuraLogger.e(LogCategory.VOICE, "[JARVIS] Non-recoverable speech recognizer error: ${event.message}")
                    updateState { VoiceAssistantState.Error(event.message) }
                }
            }
        }
    }

    /**
     * Completes user speech and dispatches the final transcript to Gemini.
     * Called automatically when the speech recognizer detects end of speech,
     * or manually when the user presses the fallback "FINISH & SEND" button.
     *
     * Protected by:
     * - Local interruption detection (does not call Gemini)
     * - Active-request guard (no duplicate requests in-flight)
     * - Duplicate callback suppression
     */
    fun finishUserSpeech(transcript: String? = null) {
        val currentState = uiState.value
        val textToProcess = transcript?.takeIf { it.isNotBlank() }
            ?: (currentState as? VoiceAssistantState.Listening)?.partialTranscript?.takeIf { it.isNotBlank() }
            ?: return

        val trimmed = textToProcess.trim()
        if (trimmed.isEmpty()) return

        // 1. Local Interruption Check: Interruption commands MUST NOT call Gemini!
        if (interruptionDetector.detect(trimmed)) {
            AuraLogger.i(LogCategory.VOICE, "[JARVIS] Local interruption phrase detected in input: \"$trimmed\". Handling locally without Gemini.")
            interruptJarvis()
            return
        }

        // 2. Duplicate Suppression: Ignore exact duplicate callbacks within 1500ms
        val now = System.currentTimeMillis()
        if (trimmed == lastProcessedTranscript && (now - lastProcessedTimestamp) < 1500L) {
            AuraLogger.w(LogCategory.VOICE, "[JARVIS] Ignoring duplicate final transcript: \"$trimmed\"")
            return
        }

        // 3. Active-Request Guard: Ignore new requests if one is actively in-flight
        if (isRequestInProgress && activeAiStreamJob?.isActive == true) {
            AuraLogger.w(LogCategory.VOICE, "[JARVIS] Busy: Gemini request already in-flight. Ignoring: \"$trimmed\"")
            return
        }

        lastProcessedTranscript = trimmed
        lastProcessedTimestamp = now

        // Ensure subtitle shows the final finalized sentence
        updateUserSubtitle(trimmed, isFinal = true)

        AuraLogger.i(LogCategory.VOICE, "[VOICE] STATE transition: LISTENING -> PROCESSING")
        AuraLogger.i(LogCategory.VOICE, "[VOICE] Final transcript: $trimmed")

        // Stop current listening session cleanly
        voiceRecognizer.stopListening()
        listeningJob?.cancel()
        listeningJob = null
        visualizerController.updateMicrophoneAmplitude(0f)

        launchInScope {
            processQuery(trimmed)
        }
    }

    /**
     * Backward-compatible alias for stopping listening or finishing user speech.
     */
    fun stopListening() {
        val lastState = uiState.value
        val transcript = if (lastState is VoiceAssistantState.Listening) lastState.partialTranscript else ""
        if (transcript.isNotBlank()) {
            finishUserSpeech(transcript)
        } else {
            cancelListening()
        }
    }

    /**
     * Cancels the active listening session and returns to IDLE.
     */
    fun cancelListening() {
        AuraLogger.i(LogCategory.VOICE, "[JARVIS] Cancelling listening session...")
        voiceRecognizer.stopListening()
        listeningJob?.cancel()
        listeningJob = null
        visualizerController.updateMicrophoneAmplitude(0f)
        updateState { VoiceAssistantState.Idle }
    }

    /**
     * Voice or manual interruption handler:
     * 1. Stops TTS playback and clears queue immediately.
     * 2. Cancels active AI streaming Job.
     * 3. Stops speech cadence animation and zeros out speech amplitude.
     * 4. Invalidates the current request ID so stale chunks are discarded.
     * 5. Immediately transitions state to LISTENING.
     * 6. Restarts active microphone listening automatically.
     */
    fun interruptJarvis() {
        AuraLogger.i(LogCategory.VOICE, "[JARVIS] INTERRUPTION DETECTED")
        AuraLogger.i(LogCategory.VOICE, "[JARVIS] STOPPING TTS")
        AuraLogger.i(LogCategory.VOICE, "[JARVIS] CANCELLING AI REQUEST")

        // 1. Invalidate current request ID and reset progress
        activeRequestId++
        isRequestInProgress = false

        // 2. Stop TTS engine and queue immediately
        player.stopAndClear()
        ttsEngine.stop()

        // 3. Cancel active stream jobs
        activeAiStreamJob?.cancel()
        activeAiStreamJob = null
        activeTtsJob?.cancel()
        activeTtsJob = null
        activeAiJob?.cancel()
        activeAiJob = null

        stopSpeakingCadenceAndInterruption()
        handleInterruptionInSubtitles()

        // 4. Cancel prior listening job before starting a new one to prevent duplicates
        listeningJob?.cancel()
        listeningJob = null
        voiceRecognizer.stopListening()

        // 5. Immediate state transition to LISTENING
        AuraLogger.i(LogCategory.VOICE, "[VOICE] STATE transition: ${uiState.value::class.simpleName} -> LISTENING")
        updateState { VoiceAssistantState.Listening("") }

        // 6. Automatically start listening for follow-up command
        AuraLogger.i(LogCategory.VOICE, "[JARVIS] STARTING LISTENING")
        startListening()
    }

    /**
     * Backward-compatible alias for interruption.
     */
    fun handleInterruption() = interruptJarvis()

    fun stopSpeaking() {
        AuraLogger.i(LogCategory.VOICE, "[JARVIS] Stopping speech playback...")
        stopSpeakingInternal()
        if (uiState.value is VoiceAssistantState.Speaking) {
            updateState { VoiceAssistantState.Idle }
        }
    }

    private fun stopSpeakingInternal() {
        activeRequestId++
        isRequestInProgress = false
        player.stopAndClear()
        ttsEngine.stop()
        activeAiStreamJob?.cancel()
        activeAiStreamJob = null
        activeTtsJob?.cancel()
        activeTtsJob = null
        activeAiJob?.cancel()
        activeAiJob = null
        stopSpeakingCadenceAndInterruption()
    }

    private fun startInterruptionMonitoring(currentSpeakingText: String) {
        interruptionJob?.cancel()
        interruptionJob = launchInScope {
            interruptionMonitor.startMonitoring(currentSpeakingText) { detectedPhrase ->
                AuraLogger.i(LogCategory.VOICE, "[JARVIS] Interruption monitor caught: \"$detectedPhrase\"")
                interruptJarvis()
            }
        }
    }

    private fun stopSpeakingCadenceAndInterruption() {
        speechCadenceJob?.cancel()
        speechCadenceJob = null
        interruptionJob?.cancel()
        interruptionJob = null
        interruptionMonitor.stopMonitoring()
        visualizerController.updateSpeechAmplitude(0f)
    }

    fun recoverVoiceState(friendlyMessage: String? = null) {
        val failedId = activeRequestId
        activeRequestId++
        isRequestInProgress = false

        AuraLogger.w(LogCategory.AI, "[GEMINI] Recovering voice state from Req #$failedId. New activeRequestId=$activeRequestId")
        AuraLogger.i(LogCategory.AI, "[GEMINI] Cancelling active stream")
        AuraLogger.i(LogCategory.AI, "[GEMINI] Clearing TTS queue")

        player.stopAndClear()
        ttsEngine.stop()
        stopSpeakingCadenceAndInterruption()

        activeAiStreamJob?.cancel()
        activeAiStreamJob = null
        activeTtsJob?.cancel()
        activeTtsJob = null
        activeAiJob?.cancel()
        activeAiJob = null

        if (!friendlyMessage.isNullOrBlank()) {
            updateJarvisSubtitle(friendlyMessage, isComplete = true, requestId = activeRequestId)
        }

        listeningJob?.cancel()
        listeningJob = null
        voiceRecognizer.stopListening()

        AuraLogger.i(LogCategory.AI, "[GEMINI] Recovering voice state")
        AuraLogger.i(LogCategory.AI, "[GEMINI] State -> LISTENING")
        AuraLogger.i(LogCategory.VOICE, "[VOICE] STATE transition: ${uiState.value::class.simpleName} -> LISTENING")
        updateState { VoiceAssistantState.Listening("") }

        startListening()
    }

    private suspend fun handleProcessingError(error: Throwable, userQuery: String, failedRequestId: Long) {
        if (failedRequestId != activeRequestId) {
            AuraLogger.w(LogCategory.AI, "[AI] Ignoring stale processing error for Req #$failedRequestId (active is #$activeRequestId)")
            return
        }

        val friendlyMessage = when (error) {
            is AuraError.AINetworkError -> "Connection interrupted. Please try again."
            is AuraError.AIRateLimitError -> "Service temporarily unavailable. Please try again."
            is AuraError.AIRequestError -> {
                if (error.message.contains("authentication", ignoreCase = true) ||
                    error.message.contains("API key", ignoreCase = true)
                ) {
                    "Authentication issue. Please check your API keys."
                } else {
                    "Connection interrupted. Please try again."
                }
            }
            else -> "Connection interrupted. Please try again."
        }

        if (isContinuousModeEnabled()) {
            recoverVoiceState(friendlyMessage)
        } else {
            activeRequestId++
            isRequestInProgress = false
            player.stopAndClear()
            ttsEngine.stop()
            stopSpeakingCadenceAndInterruption()
            activeAiStreamJob?.cancel()
            activeAiStreamJob = null
            activeTtsJob?.cancel()
            activeTtsJob = null
            activeAiJob?.cancel()
            activeAiJob = null
            listeningJob?.cancel()
            listeningJob = null
            voiceRecognizer.stopListening()

            val errMessage = error.message ?: friendlyMessage
            AuraLogger.e(LogCategory.AI, "[AI] Error state: $errMessage")
            updateState { VoiceAssistantState.Error(errMessage) }
        }
    }

    private suspend fun processQuery(text: String) {
        val requestId = ++activeRequestId
        isRequestInProgress = true
        AuraLogger.i(LogCategory.AI, "[AI] Request START id=$requestId query=\"$text\"")

        visualizerController.updateMicrophoneAmplitude(0f)
        updateState { VoiceAssistantState.Processing(text) }

        activeAiStreamJob?.cancel()
        activeAiStreamJob = launchInScope {
            var hasStartedSpeaking = false
            val fullResponseBuilder = StringBuilder()

            try {
                conversationManager.processUserMessageStream(text)
                    .collect { chunk ->
                        // Verify request wasn't cancelled or superseded
                        if (requestId != activeRequestId || !isActive) {
                            AuraLogger.w(LogCategory.AI, "[AI] Stale stream chunk discarded for Req #$requestId (active is #$activeRequestId)")
                            return@collect
                        }

                        val trimmedChunk = chunk.trim()
                        if (trimmedChunk.isEmpty()) return@collect

                        fullResponseBuilder.append(trimmedChunk).append(" ")
                        val currentText = fullResponseBuilder.toString().trim()

                        // Update subtitle progressively as chunks stream in
                        updateJarvisSubtitle(currentText, isComplete = false, requestId = requestId)

                        // Transition to SPEAKING as soon as the first complete sentence chunk arrives
                        if (!hasStartedSpeaking) {
                            hasStartedSpeaking = true
                            AuraLogger.i(LogCategory.VOICE, "[VOICE] STATE transition: PROCESSING -> SPEAKING [Req #$requestId]")
                            updateState { VoiceAssistantState.Speaking(text, currentText) }
                            startInterruptionMonitoring(text)
                        } else {
                            updateState { VoiceAssistantState.Speaking(text, currentText) }
                        }

                        player.enqueue(trimmedChunk, requestId)
                    }

                // Stream finished: signal queue player that no more sentences are coming
                if (requestId == activeRequestId && isActive) {
                    if (hasStartedSpeaking) {
                        player.finishEnqueuing(requestId)
                    } else {
                        AuraLogger.w(LogCategory.VOICE, "[JARVIS] Stream finished with no speech chunks")
                        handlePlaybackFinished(requestId)
                    }
                }
            } catch (e: CancellationException) {
                AuraLogger.i(LogCategory.AI, "[AI] Stream cancelled for Req #$requestId")
                throw e
            } catch (error: AuraError) {
                if (requestId == activeRequestId && isActive) {
                    handleProcessingError(error, text, requestId)
                } else {
                    AuraLogger.w(LogCategory.AI, "[AI] Stale AuraError ignored for Req #$requestId")
                }
            } catch (e: Exception) {
                if (requestId == activeRequestId && isActive) {
                    handleProcessingError(e, text, requestId)
                } else {
                    AuraLogger.w(LogCategory.AI, "[AI] Stale Exception ignored for Req #$requestId")
                }
            } finally {
                AuraLogger.i(LogCategory.AI, "[AI] Request END id=$requestId")
            }
        }
    }

    private fun handlePlaybackFinished(callbackRequestId: Long = activeRequestId) {
        if (callbackRequestId != activeRequestId) {
            AuraLogger.w(LogCategory.VOICE, "[TTS_QUEUE] Stale playback completion ignored: Req #$callbackRequestId != active #$activeRequestId")
            return
        }

        stopSpeakingCadenceAndInterruption()
        isRequestInProgress = false

        launchInScope {
            if (callbackRequestId != activeRequestId) {
                AuraLogger.w(LogCategory.VOICE, "[TTS_QUEUE] Stale playback completion coroutine cancelled: Req #$callbackRequestId != active #$activeRequestId")
                return@launchInScope
            }

            val isContinuous = isContinuousModeEnabled()
            if (isContinuous && (uiState.value is VoiceAssistantState.Speaking || uiState.value is VoiceAssistantState.Processing)) {
                AuraLogger.i(LogCategory.VOICE, "[VOICE] STATE transition: ${uiState.value::class.simpleName} -> LISTENING")
                AuraLogger.i(LogCategory.VOICE, "[JARVIS] TTS completed normally. Automatically returning to continuous LISTENING.")
                updateState { VoiceAssistantState.Listening("") }
                startListening()
            } else if (uiState.value is VoiceAssistantState.Speaking || uiState.value is VoiceAssistantState.Processing) {
                AuraLogger.i(LogCategory.VOICE, "[VOICE] STATE transition: ${uiState.value::class.simpleName} -> IDLE")
                updateState { VoiceAssistantState.Idle }
            }
        }
    }

    /**
     * Organic, deterministic speech amplitude cadence.
     * Generates a realistic syllabic rhythm (~4-5 syllables/sec) modulated by sentence cadence.
     * Fluctuate orange core strictly with JARVIS's speech, never user's microphone.
     */
    private fun startSpeechCadenceSimulation(text: String, requestId: Long = 0L) {
        speechCadenceJob?.cancel()
        speechCadenceJob = launchInScope {
            var step = 0
            val totalSteps = (text.length * 4).coerceAtLeast(30)
            while (isActive && player.isPlaying && (requestId == 0L || requestId == activeRequestId)) {
                val t = step * 0.15f
                val harmonic = (sin(t * 5.0) * 0.35 + sin(t * 11.0) * 0.15 + 0.5).toFloat()
                visualizerController.updateSpeechAmplitude(harmonic.coerceIn(0.15f, 0.95f))
                delay(50L)
                step++
                if (step > totalSteps * 2) step = 0
            }
            if (requestId == 0L || requestId == activeRequestId) {
                visualizerController.updateSpeechAmplitude(0f)
            }
        }
    }

    private suspend fun isContinuousModeEnabled(): Boolean {
        return try {
            val token = securityManager.getSecureToken("continuous_conversation")
            if (token != null) {
                token == "true"
            } else {
                defaultContinuousConversation
            }
        } catch (e: Exception) {
            defaultContinuousConversation
        }
    }

    fun onPause() {
        AuraLogger.i(LogCategory.VOICE, "[JARVIS] Lifecycle onPause: checking background voice persistence...")

        // If background persistent voice session is running, retain the microphone and voice session!
        if (VoiceSessionCoordinator.isBackgroundPersistenceActive) {
            AuraLogger.i(LogCategory.VOICE, "[JARVIS] Background voice session active. Retaining voice session.")
            return
        }

        voiceRecognizer.cancel()
        listeningJob?.cancel()
        listeningJob = null

        stopSpeakingInternal()

        val lastState = uiState.value
        if (lastState is VoiceAssistantState.Listening ||
            lastState is VoiceAssistantState.Processing ||
            lastState is VoiceAssistantState.Speaking
        ) {
            updateState { VoiceAssistantState.Paused }
        }
    }

    fun onResume() {
        AuraLogger.i(LogCategory.VOICE, "[JARVIS] Lifecycle onResume: checking state restoration...")
        val lastState = uiState.value
        if (lastState is VoiceAssistantState.Paused) {
            updateState { VoiceAssistantState.Idle }
            launchInScope {
                if (isContinuousModeEnabled()) {
                    startListening()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelListening()
        stopSpeakingInternal()
    }
}
