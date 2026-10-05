package co.aura.voice

/**
 * Shared coordinator for voice session lifecycle.
 * Coordinates background persistence between platform services (such as Android Foreground Service)
 * and the shared VoiceAssistantViewModel.
 */
object VoiceSessionCoordinator {
    @Volatile
    var isBackgroundPersistenceActive: Boolean = false
}
