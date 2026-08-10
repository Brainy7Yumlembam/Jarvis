package co.aura.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DesktopSpeechRecognizer : VoiceRecognizer {
    override fun startListening(): Flow<String> = flow {
        emit("Desktop mocked transcript speech")
    }

    override fun stopListening() {}

    override fun cancel() {}
}
