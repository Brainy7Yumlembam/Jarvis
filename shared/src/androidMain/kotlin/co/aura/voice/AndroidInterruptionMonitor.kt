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

/**
 * Android implementation of [InterruptionMonitor] using speech recognition with echo-suppression
 * and graceful fallback when concurrent audio capture is restricted by the device HAL.
 */
class AndroidInterruptionMonitor(
    private val context: Context,
    private val interruptionDetector: InterruptionDetector = DefaultInterruptionDetector()
) : InterruptionMonitor {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    // Mute system and notification streams during monitoring starts to suppress earcons
    // Note: STREAM_MUSIC is intentionally NOT muted here to preserve JARVIS TTS speech playback.
    private val earconStreams = intArrayOf(
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_NOTIFICATION
    )

    private val mutedStreams = mutableSetOf<Int>()

    private val unmuteRunnable = Runnable {
        unmuteStreams()
    }

    @Synchronized
    private fun muteStreams() {
        val am = audioManager ?: return
        mainHandler.removeCallbacks(unmuteRunnable)
        for (stream in earconStreams) {
            try {
                if (!am.isStreamMute(stream)) {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                    mutedStreams.add(stream)
                }
            } catch (e: Exception) {
                // Safeguard against OEM restrictions
            }
        }
    }

    @Synchronized
    private fun unmuteStreams() {
        val am = audioManager ?: return
        mainHandler.removeCallbacks(unmuteRunnable)
        if (mutedStreams.isEmpty()) return
        for (stream in mutedStreams) {
            try {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: Exception) {
                // Safe fallback
            }
        }
        mutedStreams.clear()
    }

    @Volatile
    override var isMonitoring: Boolean = false
        private set

    @Volatile
    override var isSupportedOnPlatform: Boolean = false
        private set

    private var activeSpeakingText: String = ""
    private var onInterruptionCallback: ((String) -> Unit)? = null
    private var consecutiveErrors = 0
    private val maxConsecutiveErrors = 3

    private fun ensureRecognizer(): SpeechRecognizer {
        val current = recognizer
        if (current != null) return current
        val newRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = newRecognizer
        return newRecognizer
    }

    private fun createIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }

    override fun startMonitoring(currentSpeakingText: String, onInterruption: (phrase: String) -> Unit) {
        // SpeechRecognizer cannot be safely run concurrently during speaker audio playback without emitting Google earcons
        return
    }

    private fun startListeningInternal() {
        if (!isMonitoring) return

        try {
            val rec = ensureRecognizer()
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    unmuteStreams()
                }

                override fun onBeginningOfSpeech() {
                    AuraLogger.d(LogCategory.VOICE, "[JARVIS] Interruption monitor: sound detected while speaking")
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    unmuteStreams()
                    if (!isMonitoring) return

                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission error"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                        else -> "Error ($error)"
                    }

                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        // Normal timeouts while JARVIS is speaking - resume monitoring
                        consecutiveErrors = 0
                        scheduleRestart(150L)
                    } else if (error == SpeechRecognizer.ERROR_AUDIO || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        consecutiveErrors++
                        if (consecutiveErrors >= maxConsecutiveErrors) {
                            // Hardware/HAL does not allow microphone capture while speaker is active
                            AuraLogger.w(
                                LogCategory.VOICE,
                                "[JARVIS] Android audio HAL blocked simultaneous mic capture ($errorMessage). Standing by for manual barge-in or future VAD."
                            )
                            isSupportedOnPlatform = false
                            stopMonitoring()
                        } else {
                            scheduleRestart(300L)
                        }
                    } else {
                        consecutiveErrors++
                        if (consecutiveErrors < maxConsecutiveErrors) {
                            scheduleRestart(400L)
                        } else {
                            stopMonitoring()
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    unmuteStreams()
                    if (!isMonitoring) return
                    consecutiveErrors = 0
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    handleDetectedText(text)

                    if (isMonitoring) {
                        scheduleRestart(150L)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (!isMonitoring) return
                    consecutiveErrors = 0
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    handleDetectedText(text)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            muteStreams()
            rec.startListening(createIntent())
            mainHandler.postDelayed(unmuteRunnable, 1200L)
        } catch (e: Exception) {
            unmuteStreams()
            AuraLogger.w(LogCategory.VOICE, "[JARVIS] Failed to start interruption monitor: ${e.message}")
            stopMonitoring()
        }
    }

    private fun handleDetectedText(text: String) {
        if (text.isBlank() || !isMonitoring) return

        if (interruptionDetector.detect(text)) {
            // Check for self-echo if JARVIS is reciting text containing command words
            if (!isSelfEcho(text, activeSpeakingText)) {
                AuraLogger.i(LogCategory.VOICE, "[JARVIS] INTERRUPTION DETECTED: \"$text\"")
                val callback = onInterruptionCallback
                stopMonitoring()
                callback?.invoke(text)
            }
        }
    }

    private fun isSelfEcho(detectedText: String, currentSpeakingText: String): Boolean {
        val candidate = detectedText.trim().lowercase()
        val speaking = currentSpeakingText.trim().lowercase()

        // Standalone user commands are always treated as deliberate user interruption
        val explicitCommands = setOf("stop", "wait", "hold on", "shut up", "be quiet", "cancel")
        if (explicitCommands.contains(candidate)) {
            return false
        }

        return speaking.contains(candidate)
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!isMonitoring) return
        mainHandler.postDelayed({
            if (isMonitoring) {
                try {
                    startListeningInternal()
                } catch (e: Exception) {
                    AuraLogger.e(LogCategory.VOICE, "[JARVIS] Exception restarting interruption monitor", e)
                }

            }
        }, delayMs)
    }

    override fun stopMonitoring() {
        isMonitoring = false
        activeSpeakingText = ""
        onInterruptionCallback = null
        mainHandler.removeCallbacks(unmuteRunnable)
        mainHandler.post {
            try {
                recognizer?.cancel()
                recognizer?.destroy()
                recognizer = null
            } catch (e: Exception) {
                // Ignore
            } finally {
                unmuteStreams()
            }
        }
    }
}
