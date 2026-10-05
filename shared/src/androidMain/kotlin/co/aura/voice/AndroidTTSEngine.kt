package co.aura.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import co.aura.core.logging.AuraLogger
import co.aura.core.logging.LogCategory
import co.aura.security.SecurityManager
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AndroidTTSEngine(
    private val context: Context,
    private val securityManager: SecurityManager
) : TextToSpeechEngine {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val initLock = Any()

    private val utteranceCounter = AtomicLong(0)
    private val activeUtteranceId = AtomicReference<String?>(null)
    private val activeContinuation = AtomicReference<CancellableContinuation<Boolean>?>(null)

    init {
        AuraLogger.i(LogCategory.VOICE, "Initializing Android TextToSpeech Engine...")
        tts = TextToSpeech(context) { status ->
            synchronized(initLock) {
                if (status == TextToSpeech.SUCCESS) {
                    isInitialized = true
                    AuraLogger.i(LogCategory.VOICE, "TextToSpeech engine initialized successfully.")
                    val localTts = tts
                    if (localTts != null) {
                        try {
                            val audioAttributes = android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                            localTts.setAudioAttributes(audioAttributes)
                            selectVoice(localTts, null, "en-GB")
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

    override suspend fun speak(text: String): Boolean {
        // Read configuration safely before blocking lock
        val rate = try {
            securityManager.getSecureToken("voice_speech_rate")?.toFloatOrNull() ?: 1.0f
        } catch (e: Exception) {
            1.0f
        }

        val pitch = try {
            securityManager.getSecureToken("voice_pitch")?.toFloatOrNull() ?: 1.0f
        } catch (e: Exception) {
            1.0f
        }

        val language = try {
            securityManager.getSecureToken("voice_language") ?: "en-GB"
        } catch (e: Exception) {
            "en-GB"
        }

        val preferredVoiceName = try {
            securityManager.getSecureToken("preferred_voice_name")
        } catch (e: Exception) {
            null
        }

        return suspendCancellableCoroutine { continuation ->
            synchronized(initLock) {
                val localTts = tts
                if (localTts == null || !isInitialized) {
                    AuraLogger.e(LogCategory.VOICE, "TextToSpeech engine is not initialized yet.")
                    continuation.resume(false)
                    return@suspendCancellableCoroutine
                }

                // Stop any previous speech
                localTts.stop()
                activeContinuation.getAndSet(null)?.let { prevContinuation ->
                    if (prevContinuation.isActive) {
                        prevContinuation.resume(false)
                    }
                }

                val utteranceId = "utt_${utteranceCounter.incrementAndGet()}_${System.currentTimeMillis()}"
                activeUtteranceId.set(utteranceId)
                activeContinuation.set(continuation)

                continuation.invokeOnCancellation {
                    synchronized(initLock) {
                        if (activeUtteranceId.get() == utteranceId) {
                            activeUtteranceId.set(null)
                            activeContinuation.set(null)
                            tts?.stop()
                        }
                    }
                }

                localTts.setSpeechRate(rate)
                localTts.setPitch(pitch)
                selectVoice(localTts, preferredVoiceName, language)

                localTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        if (id == activeUtteranceId.get()) {
                            AuraLogger.i(LogCategory.VOICE, "TTS started speaking: $id")
                        }
                    }

                    override fun onDone(id: String?) {
                        if (id == activeUtteranceId.get()) {
                            AuraLogger.i(LogCategory.VOICE, "TTS completed speaking: $id")
                            activeUtteranceId.set(null)
                            activeContinuation.getAndSet(null)?.let { cont ->
                                if (cont.isActive) cont.resume(true)
                            }
                        }
                    }

                    override fun onError(id: String?) {
                        if (id == activeUtteranceId.get()) {
                            AuraLogger.e(LogCategory.VOICE, "TTS error occurred: $id")
                            activeUtteranceId.set(null)
                            activeContinuation.getAndSet(null)?.let { cont ->
                                if (cont.isActive) cont.resume(false)
                            }
                        }
                    }
                })

                AuraLogger.i(LogCategory.VOICE, "Vocalizing text [$utteranceId]: $text")
                val result = localTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (result != TextToSpeech.SUCCESS) {
                    AuraLogger.e(LogCategory.VOICE, "TextToSpeech.speak failed with code: $result")
                    activeUtteranceId.set(null)
                    activeContinuation.getAndSet(null)
                    continuation.resume(false)
                }
            }
        }
    }

    override fun stop() {
        synchronized(initLock) {
            activeUtteranceId.set(null)
            activeContinuation.getAndSet(null)?.let { cont ->
                if (cont.isActive) {
                    cont.resume(false)
                }
            }
            tts?.stop()
        }
    }

    override fun isSpeaking(): Boolean {
        synchronized(initLock) {
            return (activeUtteranceId.get() != null) || (tts?.isSpeaking ?: false)
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
            return
        }

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

        if (selectedVoice != null) {
            tts.voice = selectedVoice
        } else {
            val locale = when (preferredLocale) {
                "en-GB" -> Locale.UK
                "en-US" -> Locale.US
                else -> Locale.UK
            }
            tts.setLanguage(locale)
        }
    }

    fun release() {
        synchronized(initLock) {
            stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
            AuraLogger.i(LogCategory.VOICE, "TextToSpeech engine resources released.")
        }
    }
}
