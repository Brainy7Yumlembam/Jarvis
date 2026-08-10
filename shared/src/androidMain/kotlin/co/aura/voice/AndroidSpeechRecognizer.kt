package co.aura.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

class AndroidSpeechRecognizer(
    private val context: Context
) : VoiceRecognizer {
    
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)

    override fun startListening(): Flow<String> = callbackFlow {
        mainScope.launch {
            if (speechRecognizer != null) {
                speechRecognizer?.destroy()
            }
            
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer = recognizer
            
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    AuraLogger.i(LogCategory.VOICE, "Speech recognizer ready for input")
                }
                override fun onBeginningOfSpeech() {
                    AuraLogger.i(LogCategory.VOICE, "User began speaking")
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    AuraLogger.i(LogCategory.VOICE, "User finished speaking")
                }
                
                override fun onError(error: Int) {
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
                    AuraLogger.e(LogCategory.VOICE, "Speech recognizer error: $errorMessage")
                    close(Exception(errorMessage))
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val finalResult = matches?.firstOrNull() ?: ""
                    AuraLogger.i(LogCategory.VOICE, "Final speech recognition result: $finalResult")
                    trySend(finalResult)
                    close()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partialResult = matches?.firstOrNull() ?: ""
                    if (partialResult.isNotBlank()) {
                        AuraLogger.i(LogCategory.VOICE, "Partial speech result: $partialResult")
                        trySend(partialResult)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            
            recognizer.startListening(intent)
        }
        
        awaitClose {
            mainScope.launch {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
                speechRecognizer = null
            }
        }
    }

    override fun stopListening() {
        mainScope.launch {
            speechRecognizer?.stopListening()
        }
    }

    override fun cancel() {
        mainScope.launch {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }
}
