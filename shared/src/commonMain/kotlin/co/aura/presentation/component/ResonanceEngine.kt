package co.aura.presentation.component

import androidx.compose.runtime.Immutable
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * An individual 3D spherical wavefront expanding radially through the neural volume.
 */
@Immutable
data class ActiveResonanceWave(
    val radius: Float,
    val intensity: Float,
    val speed: Float = 1.8f
)

/**
 * State container produced per frame by the ResonanceEngine.
 */
@Immutable
data class ResonanceState(
    val smoothedAmplitude: Float,
    val resonance: Float,
    val activeWaves: List<ActiveResonanceWave>,
    val coreGlowScale: Float,
    val filamentVibrationIntensity: Float,
    val photonSpeedMultiplier: Float
)

/**
 * Physics and resonance engine converting JARVIS's raw TTS audio amplitude
 * into multi-layer volumetric physical displacement and expanding 3D shockwaves.
 */
class ResonanceEngine(
    private val attackSpeed: Float = 0.35f,
    private val releaseSpeed: Float = 0.08f
) {
    private var smoothedAmp: Float = 0f
    private var lastRawAmp: Float = 0f
    private val activeWaves = mutableListOf<ActiveResonanceWave>()
    private var lastPeakTime = 0L

    /**
     * Updates the engine state for the current animation frame.
     *
     * @param rawSpeechAmp Raw speech amplitude from JARVIS TTS [0..1]
     * @param isSpeaking Whether assistant state is currently Speaking
     * @param dt Elapsed seconds since last frame
     * @param currentTimeMs Current frame timestamp in milliseconds
     */
    fun update(
        rawSpeechAmp: Float,
        isSpeaking: Boolean,
        dt: Float,
        currentTimeMs: Long
    ): ResonanceState {
        val targetAmp = if (isSpeaking) rawSpeechAmp.coerceIn(0f, 1f) else 0f

        // Asymmetric attack/release envelope smoothing
        if (targetAmp > smoothedAmp) {
            smoothedAmp += (targetAmp - smoothedAmp) * (attackSpeed * (dt * 60f).coerceIn(0.5f, 2f))
        } else {
            smoothedAmp += (targetAmp - smoothedAmp) * (releaseSpeed * (dt * 60f).coerceIn(0.5f, 2f))
        }
        smoothedAmp = smoothedAmp.coerceIn(0f, 1f)

        // Non-linear organic perception curve
        val resonance = smoothedAmp.pow(0.7f)

        // Peak detection for spawning 3D spherical resonance wavefronts
        val ampDelta = targetAmp - lastRawAmp
        if (isSpeaking && targetAmp > 0.28f && ampDelta > 0.12f && (currentTimeMs - lastPeakTime) > 160L) {
            if (activeWaves.size < 5) {
                activeWaves.add(
                    ActiveResonanceWave(
                        radius = 0.08f,
                        intensity = (resonance * 1.35f).coerceIn(0.3f, 1.0f),
                        speed = 1.6f + resonance * 0.8f
                    )
                )
                lastPeakTime = currentTimeMs
            }
        }
        lastRawAmp = targetAmp

        // Advance active 3D resonance waves
        val maxWaveRadius = 1.35f
        val iterator = activeWaves.listIterator()
        while (iterator.hasNext()) {
            val wave = iterator.next()
            val newRadius = wave.radius + wave.speed * dt
            if (newRadius >= maxWaveRadius) {
                iterator.remove()
            } else {
                // Attenuate intensity as the wave expands through the volume
                val progress = newRadius / maxWaveRadius
                val newIntensity = wave.intensity * (1f - progress * progress).coerceAtLeast(0f)
                iterator.set(wave.copy(radius = newRadius, intensity = newIntensity))
            }
        }

        val coreGlow = 1f + resonance * 0.75f
        val filamentVibe = resonance * 0.024f
        val photonSpeed = 1f + resonance * 2.8f

        return ResonanceState(
            smoothedAmplitude = smoothedAmp,
            resonance = resonance,
            activeWaves = activeWaves.toList(),
            coreGlowScale = coreGlow,
            filamentVibrationIntensity = filamentVibe,
            photonSpeedMultiplier = photonSpeed
        )
    }

    /**
     * Calculates the local displacement multiplier and illumination boost for a particle
     * based on active 3D spherical resonance waves.
     */
    fun calculateWaveInfluence(radialDist: Float, waves: List<ActiveResonanceWave>): Pair<Float, Float> {
        if (waves.isEmpty()) return 0f to 0f

        var totalDisplacement = 0f
        var totalIllumination = 0f
        val waveWidth = 0.14f

        for (wave in waves) {
            val diff = radialDist - wave.radius
            val gaussian = exp(-(diff * diff) / (2f * waveWidth * waveWidth))
            totalDisplacement += gaussian * wave.intensity * 0.18f
            totalIllumination += gaussian * wave.intensity * 1.4f
        }

        return totalDisplacement to totalIllumination.coerceAtMost(2.0f)
    }

    /**
     * Calculates dynamic orbital deformation factor under speech resonance.
     */
    fun calculateOrbitalDeformation(streamId: Int, resonance: Float, timeSeconds: Float): Float {
        if (resonance <= 0.01f) return 0f
        return sin(timeSeconds * 14f + streamId * 1.45f) * resonance * 0.07f
    }

    /**
     * Instantly collapses all active resonance waves and resets amplitude (e.g. upon user interruption).
     */
    fun collapseImmediately() {
        smoothedAmp = 0f
        lastRawAmp = 0f
        activeWaves.clear()
    }
}
