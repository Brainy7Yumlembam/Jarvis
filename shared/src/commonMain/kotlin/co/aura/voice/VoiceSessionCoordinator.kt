package co.aura.voice

/**
 * Shared coordinator for voice session lifecycle.
 * Coordinates background persistence and wake-word monitoring between platform services
 * (such as Android Foreground Service) and the shared VoiceAssistantViewModel.
 */
object VoiceSessionCoordinator {
    @Volatile
    var isBackgroundPersistenceActive: Boolean = false

    @Volatile
    var isWakeWordMonitoringActive: Boolean = false

    var onWakeWordToggled: ((Boolean) -> Unit)? = null
    var onStatusChanged: ((String) -> Unit)? = null
}
