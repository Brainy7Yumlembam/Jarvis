package co.aura.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import co.aura.security.SecurityManager
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AndroidTTSEngine(
    private val context: Context,
    private val securityManager: SecurityManager
) : TextToSpeechEngine {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val initLock = Any()

    init {
        AuraLogger.i(LogCategory.VOICE, "Initializing Android TextToSpeech Engine...")
        tts = TextToSpeech(context) { status ->
            synchronized(initLock) {
                if (status == TextToSpeech.SUCCESS) {
                    isInitialized = true
                    AuraLogger.i(LogCategory.VOICE, "TextToSpeech engine initialized successfully.")
                    // Pre-select the best voice on initialization
                    val localTts = tts
                    if (localTts != null) {
                        try {
                            val language = kotlinx.coroutines.runBlocking {
                                securityManager.getSecureToken("voice_language") ?: "en-GB"
                            }
                            val preferredVoiceName = kotlinx.coroutines.runBlocking {
                                securityManager.getSecureToken("preferred_voice_name")
                            }
                            selectVoice(localTts, preferredVoiceName, language)
                        } catch (e: Exception) {
                            // Ignore
                        }
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
            
            // Prevent overlapping speech
            localTts.stop()

            // Fetch speed rate, pitch, and language settings dynamically
            val rate = try {
                kotlinx.coroutines.runBlocking {
                    securityManager.getSecureToken("voice_speech_rate")?.toFloatOrNull() ?: 1.0f
                }
            } catch (e: Exception) {
                1.0f
            }

            val pitch = try {
                kotlinx.coroutines.runBlocking {
                    securityManager.getSecureToken("voice_pitch")?.toFloatOrNull() ?: 1.0f
                }
            } catch (e: Exception) {
                1.0f
            }

            val language = try {
                kotlinx.coroutines.runBlocking {
                    securityManager.getSecureToken("voice_language") ?: "en-GB"
                }
            } catch (e: Exception) {
                "en-GB"
            }

            val preferredVoiceName = try {
                kotlinx.coroutines.runBlocking {
                    securityManager.getSecureToken("preferred_voice_name")
                }
            } catch (e: Exception) {
                null
            }

            localTts.setSpeechRate(rate)
            localTts.setPitch(pitch)
            selectVoice(localTts, preferredVoiceName, language)
            
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

    private fun selectVoice(tts: TextToSpeech, preferredVoiceName: String?, preferredLocale: String) {
        val voices = try { tts.voices } catch (e: Exception) { null }
        if (voices.isNullOrEmpty()) {
            val locale = when (preferredLocale) {
                "en-GB" -> Locale.UK
                "en-US" -> Locale.US
                else -> Locale.UK
            }
            tts.setLanguage(locale)
            AuraLogger.w(LogCategory.VOICE, "No voices available, default locale set: $locale")
            kotlinx.coroutines.runBlocking {
                securityManager.saveSecureToken("selected_voice_name", "System Default")
            }
            return
        }

        // Map native Android voices to TtsVoiceInfo
        val mappedVoices = voices.map { voice ->
            TtsVoiceInfo(
                name = voice.name ?: "",
                localeLanguage = voice.locale?.language ?: "",
                localeCountry = voice.locale?.country ?: "",
                isNetworkRequired = voice.isNetworkConnectionRequired,
                features = voice.features ?: emptySet()
            )
        }

        val bestMapped = VoiceSelector.selectBestJarvisVoice(mappedVoices, preferredVoiceName, preferredLocale)
        val selectedVoice = voices.firstOrNull { it.name == bestMapped?.name }

        // Diagnostic logging (Requirement 5)
        for (v in voices) {
            val isSelected = selectedVoice != null && v.name == selectedVoice.name
            AuraLogger.i(LogCategory.VOICE, "Available TTS voice:\nname = ${v.name}\nlocale = ${v.locale}\nselected = $isSelected")
        }

        if (selectedVoice != null) {
            tts.voice = selectedVoice
            AuraLogger.i(LogCategory.VOICE, "Selected JARVIS voice:\nname = ${selectedVoice.name}")
            kotlinx.coroutines.runBlocking {
                securityManager.saveSecureToken("selected_voice_name", selectedVoice.name)
            }
        } else {
            val locale = when (preferredLocale) {
                "en-GB" -> Locale.UK
                "en-US" -> Locale.US
                else -> Locale.UK
            }
            tts.setLanguage(locale)
            AuraLogger.w(LogCategory.VOICE, "Failed to select persistent voice, default English locale set: $locale")
            kotlinx.coroutines.runBlocking {
                securityManager.saveSecureToken("selected_voice_name", "System Default")
            }
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
