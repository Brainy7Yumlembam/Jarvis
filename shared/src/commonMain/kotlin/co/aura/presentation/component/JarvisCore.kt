package co.aura.presentation.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.aura.presentation.theme.JarvisBackground
import co.aura.presentation.theme.JarvisGlow
import co.aura.presentation.theme.JarvisHologram
import co.aura.presentation.theme.JarvisOrangeBright
import co.aura.presentation.theme.JarvisOrangeCore
import co.aura.presentation.theme.JarvisOrangeDark
import co.aura.presentation.theme.JarvisOrangeDeep
import co.aura.presentation.theme.JarvisOrangeLight
import co.aura.presentation.viewmodel.VoiceAssistantState
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Authentic JARVIS 3D Holographic AI Core.
 *
 * Faithfully reproduces the visual language of the Avengers reference image:
 * - Dense, volumetric spherical matrix with controlled visual chaos
 * - 10+ multi-plane 3D curved orbital streams and fragmented arcs
 * - Thousands of luminous particles across 5 distinct categories
 * - Central incandescent white-amber energy nucleus with radiating tendrils
 * - Spatially propagating speech resonance waves (NOT uniform scaling)
 * - True 3D perspective with depth sorting and split-depth occlusion
 */
@Composable
fun JarvisCore(
    voiceState: VoiceAssistantState,
    speechAmplitude: Float,
    modifier: Modifier = Modifier,
    coreSize: Dp = 600.dp,
    onCoreClick: () -> Unit = {}
) {
    // 1. Stable, precomputed 3D holographic AI core topology (~2600 particles, 10 orbital streams, ~1500 filaments)
    val topology = remember { NeuralBrainTopology.generate(targetCount = 2600) }

    // 2. Multi-layer physics & spatial speech resonance engine
    val resonanceEngine = remember { ResonanceEngine() }

    // Coroutine scope for animations and parallax damping
    val scope = rememberCoroutineScope()

    // Interactive Camera Parallax offsets
    val manualYaw = remember { Animatable(0f) }
    val manualPitch = remember { Animatable(0f) }

    // Continuous clock driven by Compose frame loop
    var frameTimeSeconds by remember { mutableStateOf(0f) }
    var lastFrameNanos by remember { mutableStateOf(0L) }
    var currentResonanceState by remember {
        mutableStateOf(
            ResonanceState(
                smoothedAmplitude = 0f,
                resonance = 0f,
                activeWaves = emptyList(),
                coreGlowScale = 1f,
                filamentVibrationIntensity = 0f,
                photonSpeedMultiplier = 1f
            )
        )
    }

    // Instant collapse on state change away from Speaking or on Interruption
    LaunchedEffect(voiceState) {
        if (voiceState !is VoiceAssistantState.Speaking) {
            resonanceEngine.collapseImmediately()
        }
    }

    // High-performance animation clock & resonance update loop
    LaunchedEffect(voiceState, speechAmplitude) {
        while (isActive) {
            withFrameNanos { frameNanos ->
                if (lastFrameNanos == 0L) lastFrameNanos = frameNanos
                val dt = ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastFrameNanos = frameNanos

                val speedFactor = when (voiceState) {
                    is VoiceAssistantState.Processing -> 2.6f
                    is VoiceAssistantState.Speaking -> 1.7f
                    is VoiceAssistantState.Listening -> 1.0f
                    else -> 0.75f
                }
                frameTimeSeconds += dt * speedFactor

                val isSpeaking = voiceState is VoiceAssistantState.Speaking
                currentResonanceState = resonanceEngine.update(
                    rawSpeechAmp = speechAmplitude,
                    isSpeaking = isSpeaking,
                    dt = dt,
                    currentTimeMs = frameNanos / 1_000_000L
                )
            }
        }
    }

    // Subtle scanning phase for listening state
    val infiniteTransition = rememberInfiniteTransition()
    val listeningScanPhase by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = modifier
            .size(coreSize)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCoreClick
            )
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        scope.launch { manualYaw.animateTo(0f, spring(stiffness = 70f, dampingRatio = 0.75f)) }
                        scope.launch { manualPitch.animateTo(0f, spring(stiffness = 70f, dampingRatio = 0.75f)) }
                    },
                    onDragCancel = {
                        scope.launch { manualYaw.animateTo(0f, spring(stiffness = 70f, dampingRatio = 0.75f)) }
                        scope.launch { manualPitch.animateTo(0f, spring(stiffness = 70f, dampingRatio = 0.75f)) }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            val newYaw = (manualYaw.value + dragAmount.x * 0.006f).coerceIn(-1.5f, 1.5f)
                            val newPitch = (manualPitch.value - dragAmount.y * 0.006f).coerceIn(-0.95f, 0.95f)
                            manualYaw.snapTo(newYaw)
                            manualPitch.snapTo(newPitch)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = (size.minDimension / 2f) * 0.70f
            val cameraDist = baseRadius * 3.6f

            val resState = currentResonanceState
            val resonance = resState.resonance

            // Autonomous 3D Gyroscopic Rotation + Parallax
            // Multi-axis rotation ensures continuous volumetric perspective shifts
            val autoYaw = frameTimeSeconds * 0.16f
            val autoPitch = sin(frameTimeSeconds * 0.20f) * 0.16f
            val autoRoll = sin(frameTimeSeconds * 0.12f) * 0.08f

            val totalYaw = autoYaw + manualYaw.value
            val totalPitch = autoPitch + manualPitch.value
            val totalRoll = autoRoll

            val isSpeaking = voiceState is VoiceAssistantState.Speaking
            val isProcessing = voiceState is VoiceAssistantState.Processing

            // 1. Transform and Project all particles into screen space
            val particles = topology.particles
            val projectedNodes = ArrayList<ProjectedPoint>(particles.size)
            val nodeIlluminations = FloatArray(particles.size)

            for (i in particles.indices) {
                val p = particles[i]

                // Spatially propagating resonance wave influence
                val (waveDisp, waveIllum) = resonanceEngine.calculateWaveInfluence(p.radialDistance, resState.activeWaves)

                // Localized physical displacement (NOT uniform object scaling)
                val localizedDisp = p.displacementBias * resonance * 0.14f + waveDisp

                // Micro-tremor along neural pathways
                val tremor = if (isSpeaking && resonance > 0.10f) {
                    sin(frameTimeSeconds * 44f + p.id * 0.9f) * resonance * 0.015f
                } else 0f

                val scaleX = baseRadius * (1f + localizedDisp + tremor)
                val scaleY = baseRadius * (1f + localizedDisp + tremor)
                val scaleZ = baseRadius * (1f + localizedDisp + tremor)

                // Optional stream rotational drift for particles attached to an orbital stream
                var rotatedBase = p.basePos
                if (p.streamId >= 0 && p.streamId < topology.streams.size) {
                    val stream = topology.streams[p.streamId]
                    val streamDrift = stream.rotationSpeed * frameTimeSeconds * 0.15f
                    rotatedBase = rotatedBase.rotate(0f, streamDrift, 0f)
                }

                // Global 3D camera rotation
                val rotated = rotatedBase.rotate(pitch = totalPitch, yaw = totalYaw, roll = totalRoll)
                val worldPos = Point3D(rotated.x * scaleX, rotated.y * scaleY, rotated.z * scaleZ)

                // Perspective projection with zero-division protection
                val projected = worldPos.project(center.x, center.y, cameraDist)
                projectedNodes.add(projected)

                // Dynamic node brightness calculation
                val baseBrightness = p.baseBrightness
                val dynamicIllum = (baseBrightness + waveIllum + resonance * 0.45f).coerceIn(0.2f, 3.0f)
                nodeIlluminations[i] = dynamicIllum
            }

            // Depth partitioning: Background (z < 0) vs Foreground (z >= 0)
            val bgIndices = ArrayList<Int>(particles.size / 2)
            val fgIndices = ArrayList<Int>(particles.size / 2)
            for (i in projectedNodes.indices) {
                if (projectedNodes[i].depth < 0) {
                    bgIndices.add(i)
                } else {
                    fgIndices.add(i)
                }
            }

            // ── PIPELINE STEP 1: Deep Space Volumetric Atmospheric Bloom ───
            drawDeepSpaceAtmosphere(
                center = center,
                radius = baseRadius * (1.75f + resonance * 0.45f),
                voiceState = voiceState,
                resonance = resonance
            )

            // ── PIPELINE STEP 2: Background Orbital Arcs & Streams (z < 0) ───
            drawCurvedOrbitalStreams(
                center = center,
                radius = baseRadius,
                cameraDist = cameraDist,
                streams = topology.streams,
                renderForeground = false,
                pitch = totalPitch,
                yaw = totalYaw,
                roll = totalRoll,
                frameTime = frameTimeSeconds,
                resonance = resonance,
                resonanceEngine = resonanceEngine
            )

            // ── PIPELINE STEP 3: Background Synaptic Filaments (z < 0) ───
            drawSynapticFilaments(
                filaments = topology.filaments,
                projectedNodes = projectedNodes,
                renderForeground = false,
                resonanceState = resState,
                frameTime = frameTimeSeconds,
                voiceState = voiceState,
                maxRadius = baseRadius
            )

            // ── PIPELINE STEP 4: Background Particles (z < 0) ───
            drawVolumetricParticles(
                particles = particles,
                indices = bgIndices,
                projectedNodes = projectedNodes,
                illuminations = nodeIlluminations,
                maxRadius = baseRadius,
                resonance = resonance,
                isSpeaking = isSpeaking
            )

            // ── PIPELINE STEP 5: Central Incandescent Energy Nucleus & 3D Wave Shells ───
            drawIncandescentNucleus(
                center = center,
                radius = baseRadius * (0.36f + resonance * 0.18f),
                activeWaves = resState.activeWaves,
                voiceState = voiceState,
                resonance = resonance,
                frameTime = frameTimeSeconds
            )

            // ── PIPELINE STEP 6: Foreground Synaptic Filaments (z >= 0) ───
            drawSynapticFilaments(
                filaments = topology.filaments,
                projectedNodes = projectedNodes,
                renderForeground = true,
                resonanceState = resState,
                frameTime = frameTimeSeconds,
                voiceState = voiceState,
                maxRadius = baseRadius
            )

            // ── PIPELINE STEP 7: Foreground Particles (z >= 0) ───
            drawVolumetricParticles(
                particles = particles,
                indices = fgIndices,
                projectedNodes = projectedNodes,
                illuminations = nodeIlluminations,
                maxRadius = baseRadius,
                resonance = resonance,
                isSpeaking = isSpeaking
            )

            // ── PIPELINE STEP 8: Travelling Synaptic Photon Packets ───
            drawTravellingPhotons(
                filaments = topology.filaments,
                projectedNodes = projectedNodes,
                frameTime = frameTimeSeconds,
                resonanceState = resState,
                voiceState = voiceState,
                maxRadius = baseRadius
            )

            // ── PIPELINE STEP 9: Foreground Orbital Arcs & Streams (z >= 0) ───
            drawCurvedOrbitalStreams(
                center = center,
                radius = baseRadius,
                cameraDist = cameraDist,
                streams = topology.streams,
                renderForeground = true,
                pitch = totalPitch,
                yaw = totalYaw,
                roll = totalRoll,
                frameTime = frameTimeSeconds,
                resonance = resonance,
                resonanceEngine = resonanceEngine
            )

            // ── PIPELINE STEP 10: Subtle Listening Scan Plane ───
            if (voiceState is VoiceAssistantState.Listening) {
                drawSubtleListeningScan(center, baseRadius, listeningScanPhase)
            }
        }
    }
}

/**
 * Deep volumetric atmospheric bloom providing the cinematic dark amber glow.
 */
private fun DrawScope.drawDeepSpaceAtmosphere(
    center: Offset,
    radius: Float,
    voiceState: VoiceAssistantState,
    resonance: Float
) {
    val bloomColor = if (voiceState is VoiceAssistantState.Error) Color(0xFFFF2222) else JarvisOrangeCore
    val alpha = (0.22f + resonance * 0.30f).coerceAtMost(0.60f)

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                bloomColor.copy(alpha = alpha),
                JarvisGlow.copy(alpha = alpha * 0.40f),
                JarvisOrangeDeep.copy(alpha = alpha * 0.15f),
                Color.Transparent
            ),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

/**
 * Central incandescent white-hot amber energy nucleus with radiating curved tendrils.
 */
private fun DrawScope.drawIncandescentNucleus(
    center: Offset,
    radius: Float,
    activeWaves: List<ActiveResonanceWave>,
    voiceState: VoiceAssistantState,
    resonance: Float,
    frameTime: Float
) {
    val (baseGlow, hotspot) = when (voiceState) {
        is VoiceAssistantState.Error -> Color(0xFFFF3333) to Color(0xFFFFCCCC)
        is VoiceAssistantState.Processing -> JarvisOrangeBright to Color.White
        is VoiceAssistantState.Speaking -> JarvisOrangeLight to Color.White
        else -> JarvisOrangeCore to Color(0xFFFFF6E5)
    }

    // Concentrated molten plasma core
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                hotspot.copy(alpha = (0.88f + resonance * 0.12f).coerceAtMost(1f)),
                JarvisOrangeLight.copy(alpha = 0.70f),
                baseGlow.copy(alpha = 0.50f),
                JarvisOrangeDeep.copy(alpha = 0.25f),
                Color.Transparent
            ),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )

    // Inner radiant spark
    val sparkRadius = radius * (0.28f + resonance * 0.32f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.95f),
                hotspot.copy(alpha = 0.60f),
                Color.Transparent
            ),
            center = center,
            radius = sparkRadius
        ),
        radius = sparkRadius,
        center = center
    )

    // Radiating curved energy spokes (visible in reference image core)
    val spokes = 8
    for (s in 0 until spokes) {
        val angle = (s * 2f * PI / spokes).toFloat() + frameTime * 0.45f
        val spokeLen = radius * (0.85f + sin(frameTime * 4f + s * 1.5f) * 0.15f + resonance * 0.35f)
        val endX = center.x + cos(angle) * spokeLen
        val endY = center.y + sin(angle) * spokeLen

        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(Color.White.copy(alpha = 0.80f), JarvisOrangeBright.copy(alpha = 0.35f), Color.Transparent),
                start = center,
                end = Offset(endX, endY)
            ),
            start = center,
            end = Offset(endX, endY),
            strokeWidth = 1.4f,
            cap = StrokeCap.Round
        )
    }

    // 3D Expanding Spherical Shockwave Shells
    for (wave in activeWaves) {
        val waveScreenRadius = wave.radius * radius * 2.8f
        val waveAlpha = (wave.intensity * 0.75f).coerceIn(0f, 0.90f)
        if (waveAlpha > 0.04f) {
            drawCircle(
                color = Color.White.copy(alpha = waveAlpha * 0.85f),
                radius = waveScreenRadius,
                center = center,
                style = Stroke(width = 1.6f + wave.intensity * 2.2f)
            )
            drawCircle(
                color = JarvisOrangeBright.copy(alpha = waveAlpha * 0.45f),
                radius = waveScreenRadius * 1.05f,
                center = center,
                style = Stroke(width = 3.4f)
            )
        }
    }
}

/**
 * 10+ Multi-plane 3D curved orbital streams and fragmented arcs.
 */
private fun DrawScope.drawCurvedOrbitalStreams(
    center: Offset,
    radius: Float,
    cameraDist: Float,
    streams: List<OrbitalStream>,
    renderForeground: Boolean,
    pitch: Float,
    yaw: Float,
    roll: Float,
    frameTime: Float,
    resonance: Float,
    resonanceEngine: ResonanceEngine
) {
    for (stream in streams) {
        val segs = 36
        val deformation = resonanceEngine.calculateOrbitalDeformation(stream.id, resonance, frameTime)
        val streamAngle = stream.rotationSpeed * frameTime * 0.4f
        val effectiveR = radius * stream.radiusRatio * (1f + deformation)

        for (s in 0 until segs) {
            // Partial arc span with organic gaps matching reference
            val fraction = s.toFloat() / segs
            if (fraction > stream.arcSpan) continue
            // Organic gap breaks along the arc
            if (s % 5 == 0) continue

            val a1 = streamAngle + stream.arcOffset + (fraction * 2f * PI.toFloat())
            val a2 = streamAngle + stream.arcOffset + (((s + 1).toFloat() / segs) * 2f * PI.toFloat())

            val r1 = effectiveR * (1f + (stream.eccentricity - 1f) * cos(a1))
            val r2 = effectiveR * (1f + (stream.eccentricity - 1f) * cos(a2))

            val p1Stream = Point3D(r1 * cos(a1), r1 * sin(a1), 0f).rotate(stream.tiltX, stream.tiltY, stream.tiltZ)
            val p2Stream = Point3D(r2 * cos(a2), r2 * sin(a2), 0f).rotate(stream.tiltX, stream.tiltY, stream.tiltZ)

            val p1World = p1Stream.rotate(pitch, yaw, roll)
            val p2World = p2Stream.rotate(pitch, yaw, roll)

            val avgZ = (p1World.z + p2World.z) / 2f
            val isFg = avgZ >= 0f
            if (isFg != renderForeground) continue

            val proj1 = p1World.project(center.x, center.y, cameraDist)
            val proj2 = p2World.project(center.x, center.y, cameraDist)

            val depthFactor = ((avgZ / effectiveR) + 1f) / 2f
            val alpha = (0.14f + depthFactor * 0.60f) * (1f + resonance * 0.35f)
            val strokeW = (0.9f + depthFactor * 1.5f)

            val streamColor = if (depthFactor > 0.65f) JarvisOrangeBright else JarvisOrangeDark

            drawLine(
                color = streamColor.copy(alpha = alpha.coerceIn(0.05f, 0.90f)),
                start = Offset(proj1.screenX, proj1.screenY),
                end = Offset(proj2.screenX, proj2.screenY),
                strokeWidth = strokeW,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Depth-sorted volumetric particle rendering across all 5 particle kinds.
 */
private fun DrawScope.drawVolumetricParticles(
    particles: List<NeuralParticle>,
    indices: List<Int>,
    projectedNodes: List<ProjectedPoint>,
    illuminations: FloatArray,
    maxRadius: Float,
    resonance: Float,
    isSpeaking: Boolean
) {
    for (i in indices) {
        val p = particles[i]
        val proj = projectedNodes[i]
        val illum = illuminations[i]

        val d = ((proj.depth / maxRadius) + 1f) / 2f
        val clampedD = d.coerceIn(0f, 1f)

        // Depth & kind-dependent radius
        val nodeRadius = (p.baseRadius * (0.65f + clampedD * 0.85f) * proj.scale).coerceIn(0.7f, 8.5f)
        val alpha = (p.baseAlpha * (0.22f + clampedD * 0.78f) * illum * 0.65f).coerceIn(0.06f, 0.98f)

        // Color mapping from depth, kind, and speech energy
        val nodeColor = when {
            p.kind == ParticleKind.HOT_SPARK || illum > 1.8f -> Color.White
            clampedD > 0.68f -> if (isSpeaking && resonance > 0.25f) Color(0xFFFFF4E0) else JarvisOrangeLight
            clampedD > 0.35f -> JarvisOrangeBright
            else -> JarvisOrangeDark
        }

        drawCircle(
            color = nodeColor.copy(alpha = alpha),
            radius = nodeRadius,
            center = Offset(proj.screenX, proj.screenY)
        )

        // Radiant halo for hot sparks and bright foreground nodes
        if ((p.kind == ParticleKind.HOT_SPARK || clampedD > 0.70f) && alpha > 0.35f) {
            drawCircle(
                color = JarvisOrangeBright.copy(alpha = alpha * 0.35f),
                radius = nodeRadius * 2.5f,
                center = Offset(proj.screenX, proj.screenY)
            )
        }
    }
}

/**
 * Selective synaptic filaments and radial spokes with harmonic vibration.
 */
private fun DrawScope.drawSynapticFilaments(
    filaments: List<SynapticFilament>,
    projectedNodes: List<ProjectedPoint>,
    renderForeground: Boolean,
    resonanceState: ResonanceState,
    frameTime: Float,
    voiceState: VoiceAssistantState,
    maxRadius: Float
) {
    val isSpeaking = voiceState is VoiceAssistantState.Speaking
    val isProcessing = voiceState is VoiceAssistantState.Processing
    val resonance = resonanceState.resonance

    for (f in filaments) {
        val p1 = projectedNodes[f.nodeA]
        val p2 = projectedNodes[f.nodeB]

        val avgDepth = (p1.depth + p2.depth) / 2f
        val isFg = avgDepth >= 0f
        if (isFg != renderForeground) continue

        val d = ((avgDepth / maxRadius) + 1f) / 2f
        val clampedD = d.coerceIn(0f, 1f)

        val alphaMultiplier = if (isSpeaking) 1f + resonance * 0.85f else if (isProcessing) 1.35f else 0.85f
        val lineAlpha = (f.restingAlpha * (0.20f + clampedD * 0.80f) * alphaMultiplier).coerceIn(0.03f, 0.88f)
        val strokeWidth = (0.7f + clampedD * 1.2f) * (if (f.isRadialSpoke) 1.2f else 1.0f)

        val lineColor = when {
            f.isRadialSpoke -> JarvisOrangeLight
            clampedD > 0.60f -> JarvisOrangeBright
            else -> JarvisOrangeDark
        }

        // Harmonic micro-tremor during speech
        val (vibeX, vibeY) = if (isSpeaking && resonanceState.filamentVibrationIntensity > 0.002f) {
            val v = sin(frameTime * 38f + f.nodeA * 0.6f) * resonanceState.filamentVibrationIntensity * 8f
            v to -v * 0.5f
        } else {
            0f to 0f
        }

        drawLine(
            color = lineColor.copy(alpha = lineAlpha),
            start = Offset(p1.screenX + vibeX, p1.screenY + vibeY),
            end = Offset(p2.screenX - vibeX, p2.screenY - vibeY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Travelling photon packets along active filaments and radial spokes.
 */
private fun DrawScope.drawTravellingPhotons(
    filaments: List<SynapticFilament>,
    projectedNodes: List<ProjectedPoint>,
    frameTime: Float,
    resonanceState: ResonanceState,
    voiceState: VoiceAssistantState,
    maxRadius: Float
) {
    val isSpeaking = voiceState is VoiceAssistantState.Speaking
    val isProcessing = voiceState is VoiceAssistantState.Processing
    if (!isSpeaking && !isProcessing) return

    val speedMult = resonanceState.photonSpeedMultiplier
    val step = if (isSpeaking) 3 else 5

    for (i in filaments.indices step step) {
        val f = filaments[i]
        val p1 = projectedNodes[f.nodeA]
        val p2 = projectedNodes[f.nodeB]

        val avgDepth = (p1.depth + p2.depth) / 2f
        if (avgDepth < -maxRadius * 0.25f) continue

        val progress = (frameTime * 0.85f * speedMult + (i * 0.19f)) % 1f
        val px = p1.screenX + (p2.screenX - p1.screenX) * progress
        val py = p1.screenY + (p2.screenY - p1.screenY) * progress

        val photonAlpha = (0.75f + resonanceState.resonance * 0.25f).coerceAtMost(1f)
        val photonRadius = 1.8f + (resonanceState.resonance * 1.2f)

        drawCircle(
            color = Color.White.copy(alpha = photonAlpha),
            radius = photonRadius,
            center = Offset(px, py)
        )
        drawCircle(
            color = JarvisOrangeBright.copy(alpha = photonAlpha * 0.40f),
            radius = photonRadius * 2.2f,
            center = Offset(px, py)
        )
    }
}

/**
 * Subtle holographic scanning plane during LISTENING state.
 */
private fun DrawScope.drawSubtleListeningScan(
    center: Offset,
    radius: Float,
    phase: Float
) {
    val scanY = center.y + (phase * radius * 0.85f)
    val widthFactor = sqrt((1f - (phase * phase)).coerceAtLeast(0f))
    val halfWidth = radius * 1.05f * widthFactor

    if (halfWidth > 2f) {
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    JarvisOrangeBright.copy(alpha = 0.45f),
                    Color.White.copy(alpha = 0.75f),
                    JarvisOrangeBright.copy(alpha = 0.45f),
                    Color.Transparent
                ),
                startX = center.x - halfWidth,
                endX = center.x + halfWidth
            ),
            start = Offset(center.x - halfWidth, scanY),
            end = Offset(center.x + halfWidth, scanY),
            strokeWidth = 1.6f,
            cap = StrokeCap.Round
        )
    }
}
