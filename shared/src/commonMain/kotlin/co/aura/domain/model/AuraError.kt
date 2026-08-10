package co.aura.domain.model

sealed class AuraError(override val message: String) : Exception(message) {
    class SpeechRecognitionError(message: String) : AuraError(message)
    class MicrophonePermissionDenied(message: String = "Microphone permission denied.") : AuraError(message)
    class AIRequestError(message: String) : AuraError(message)
    class AINetworkError(message: String) : AuraError(message)
    class AIRateLimitError(message: String) : AuraError(message)
    class AIResponseError(message: String) : AuraError(message)
    class TTSError(message: String) : AuraError(message)
    class UnknownAssistantError(message: String) : AuraError(message)
}
