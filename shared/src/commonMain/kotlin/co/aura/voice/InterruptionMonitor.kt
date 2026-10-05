package co.aura.voice

/**
 * Abstraction for detecting user barge-in interruptions (e.g. "stop", "wait") while JARVIS is speaking.
 * Implementations may use speech recognition, acoustic VAD, wake-word engines, or platform-specific audio pipelines.
 */
interface InterruptionMonitor {
    /**
     * Whether interruption monitoring is currently running.
     */
    val isMonitoring: Boolean

    /**
     * Whether this platform/device natively supports audio capture during playback.
     */
    val isSupportedOnPlatform: Boolean get() = true

    /**
     * Start monitoring for user interruption commands while JARVIS is speaking [currentSpeakingText].
     * Invokes [onInterruption] when an interruption command is detected.
     */
    fun startMonitoring(currentSpeakingText: String, onInterruption: (phrase: String) -> Unit)

    /**
     * Stop monitoring for user interruption.
     */
    fun stopMonitoring()
}

/**
 * No-op interruption monitor for platforms/environments without simultaneous capture support.
 */
class NoOpInterruptionMonitor : InterruptionMonitor {
    override val isMonitoring: Boolean = false
    override val isSupportedOnPlatform: Boolean = false
    override fun startMonitoring(currentSpeakingText: String, onInterruption: (phrase: String) -> Unit) {}
    override fun stopMonitoring() {}
}
