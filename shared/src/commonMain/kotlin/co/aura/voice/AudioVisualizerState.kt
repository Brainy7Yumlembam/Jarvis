package co.aura.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Encapsulates distinct audio visualization amplitudes:
 * - [microphoneAmplitude]: Real-time user microphone input level (used when LISTENING)
 * - [speechAmplitude]: Assistant speech output level (used when SPEAKING)
 */
data class AudioVisualizationState(
    val microphoneAmplitude: Float = 0f,
    val speechAmplitude: Float = 0f
)

interface AudioVisualizerController {
    val visualizationState: StateFlow<AudioVisualizationState>
    fun updateMicrophoneAmplitude(amplitude: Float)
    fun updateSpeechAmplitude(amplitude: Float)
    fun reset()
}

class DefaultAudioVisualizerController : AudioVisualizerController {
    private val _state = MutableStateFlow(AudioVisualizationState())
    override val visualizationState: StateFlow<AudioVisualizationState> = _state.asStateFlow()

    override fun updateMicrophoneAmplitude(amplitude: Float) {
        _state.value = _state.value.copy(microphoneAmplitude = amplitude.coerceIn(0f, 1f))
    }

    override fun updateSpeechAmplitude(amplitude: Float) {
        _state.value = _state.value.copy(speechAmplitude = amplitude.coerceIn(0f, 1f))
    }

    override fun reset() {
        _state.value = AudioVisualizationState()
    }
}
