package co.aura.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class OfflineRecognizer : VoiceRecognizer {
    override fun startListening(): Flow<String> = flow { emit("Offline speech") }
    override fun stopListening() {}
    override fun cancel() {}
}
