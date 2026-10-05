package co.aura.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * Visualizer abstraction providing speech amplitude for the JARVIS AI core.
 */
interface SpeechVisualizer {
    val speechAmplitude: StateFlow<Float>
    fun startSpeaking(text: String)
    fun stopSpeaking()
}

/**
 * Organic, deterministic speech amplitude generator based on human vocal cadence and text timing.
 * Uses a multi-harmonic vocal envelope synchronized with speech playback rather than random noise.
 */
class SyntheticSpeechVisualizer(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : SpeechVisualizer {

    private val _speechAmplitude = MutableStateFlow(0f)
    override val speechAmplitude: StateFlow<Float> = _speechAmplitude.asStateFlow()

    private var cadenceJob: Job? = null

    override fun startSpeaking(text: String) {
        stopSpeaking()
        cadenceJob = scope.launch {
            var step = 0
            val totalSteps = (text.length * 4).coerceAtLeast(40)
            while (isActive) {
                val t = step * 0.15f
                // Multi-harmonic vocal envelope simulating natural pitch and syllable cadence
                val harmonic = (sin(t * 5.0) * 0.35 + sin(t * 11.0) * 0.18 + 0.48).toFloat()
                _speechAmplitude.value = harmonic.coerceIn(0.12f, 0.95f)
                delay(50L)
                step++
                if (step > totalSteps * 3) step = 0
            }
        }
    }

    override fun stopSpeaking() {
        cadenceJob?.cancel()
        cadenceJob = null
        _speechAmplitude.value = 0f
    }
}
