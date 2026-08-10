package co.aura.voice

import kotlinx.coroutines.flow.Flow

interface VoiceRecognizer {
    fun startListening(): Flow<String>
    fun stopListening()
    fun cancel()
}
