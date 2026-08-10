package co.aura.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AndroidTTSEngine(
    private val context: Context
) : TextToSpeechEngine {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val initLock = Any()

    init {
        AuraLogger.i(LogCategory.VOICE, "Initializing Android TextToSpeech Engine...")
        tts = TextToSpeech(context) { status ->
            synchronized(initLock) {
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(Locale.US)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        AuraLogger.e(LogCategory.VOICE, "US language package is missing or not supported on this device.")
                    } else {
                        isInitialized = true
                        AuraLogger.i(LogCategory.VOICE, "TextToSpeech engine initialized successfully.")
                    }
                } else {
                    AuraLogger.e(LogCategory.VOICE, "Failed to initialize TextToSpeech engine. Status: $status")
                }
            }
        }
    }

    override suspend fun speak(text: String): Boolean = suspendCoroutine { continuation ->
        synchronized(initLock) {
            val localTts = tts
            if (localTts == null || !isInitialized) {
                AuraLogger.e(LogCategory.VOICE, "TextToSpeech engine is not initialized yet.")
                continuation.resume(false)
                return@suspendCoroutine
            }
            
            val utteranceId = "utterance_${System.currentTimeMillis()}"
            localTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    AuraLogger.i(LogCategory.VOICE, "TTS started speaking: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    AuraLogger.i(LogCategory.VOICE, "TTS completed speaking: $utteranceId")
                    continuation.resume(true)
                }

                override fun onError(utteranceId: String?) {
                    AuraLogger.e(LogCategory.VOICE, "TTS error occurred during vocalization: $utteranceId")
                    continuation.resume(false)
                }
            })

            AuraLogger.i(LogCategory.VOICE, "Vocalizing text: $text")
            localTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    override fun stop() {
        synchronized(initLock) {
            tts?.stop()
        }
    }

    override fun isSpeaking(): Boolean {
        synchronized(initLock) {
            return tts?.isSpeaking ?: false
        }
    }

    fun release() {
        synchronized(initLock) {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
            AuraLogger.i(LogCategory.VOICE, "TextToSpeech engine resources released.")
        }
    }
}
