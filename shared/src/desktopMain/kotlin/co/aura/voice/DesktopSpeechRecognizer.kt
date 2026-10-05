package co.aura.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DesktopSpeechRecognizer : VoiceRecognizer {
    override fun startListeningEvents(): Flow<RecognitionEvent> = flow {
        emit(RecognitionEvent.SpeechStarted)
        emit(RecognitionEvent.PartialResult("Desktop mocked transcript speech"))
        emit(RecognitionEvent.SpeechEnded)
        emit(RecognitionEvent.FinalResult("Desktop mocked transcript speech"))
    }

    override fun stopListening() {}

    override fun cancel() {}
}
