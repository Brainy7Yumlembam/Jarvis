package co.aura.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class GoogleSpeechRecognizer : VoiceRecognizer {
    override fun startListening(): Flow<String> = flow { emit("Google Speech") }
    override fun stopListening() {}
    override fun cancel() {}
}
