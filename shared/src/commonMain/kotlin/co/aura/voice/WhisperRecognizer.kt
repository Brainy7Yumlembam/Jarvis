package co.aura.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class WhisperRecognizer : VoiceRecognizer {
    override fun startListening(): Flow<String> = flow { emit("Whisper speech") }
    override fun stopListening() {}
    override fun cancel() {}
}
