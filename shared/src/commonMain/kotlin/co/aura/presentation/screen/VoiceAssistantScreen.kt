package co.aura.presentation.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.aura.presentation.component.JarvisCore
import co.aura.presentation.component.SubtitlePanel
import co.aura.presentation.theme.JarvisBackground
import co.aura.presentation.theme.JarvisGlow
import co.aura.presentation.theme.JarvisHologram
import co.aura.presentation.theme.JarvisOrangeBright
import co.aura.presentation.theme.JarvisOrangeCore
import co.aura.presentation.theme.JarvisOrangeDark
import co.aura.presentation.theme.JarvisOrangeDeep
import co.aura.presentation.theme.JarvisOrangeLight
import co.aura.presentation.theme.JarvisSurfaceDark
import co.aura.presentation.viewmodel.VoiceAssistantState
import co.aura.presentation.viewmodel.VoiceAssistantViewModel

/**
 * Cinematic 3D Holographic AI Core Interface for JARVIS.
 *
 * Designed with a strict minimalist aesthetic:
 * - 90% visual AI core floating in deep space
 * - Zero text-heavy HUD, zero diagnostic dashboard readouts
 * - No permanent text buttons; subtle icon-only controls
 * - Real-time holographic subtitle display at the bottom
 * - Directly interactive 3D neural brain core
 */
@Composable
fun VoiceAssistantScreen(
    viewModel: VoiceAssistantViewModel,
    onRequestPermission: () -> Unit,
    onNavigateToMemory: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val visualizerState by viewModel.visualizerState.collectAsStateWithLifecycle()
    val subtitleState by viewModel.subtitleState.collectAsStateWithLifecycle()

    // Optional transcript overlay, hidden by default for cinematic immersion
    var showTranscriptOverlay by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisBackground)
    ) {
        // ── Deep Space Atmospheric Canvas ───────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val center = Offset(w / 2f, h / 2f)

            // Deep space core glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        JarvisGlow.copy(alpha = 0.08f),
                        JarvisOrangeDeep.copy(alpha = 0.03f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = w * 0.70f
                ),
                radius = w * 0.70f,
                center = center
            )

            // Minimalist hairline technical corner reticles
            val crossSize = 10f
            val margin = 24f
            val reticleColor = JarvisHologram.copy(alpha = 0.18f)

            // Top-left
            drawLine(reticleColor, Offset(margin - crossSize, margin), Offset(margin + crossSize, margin), 1f)
            drawLine(reticleColor, Offset(margin, margin - crossSize), Offset(margin, margin + crossSize), 1f)

            // Top-right
            drawLine(reticleColor, Offset(w - margin - crossSize, margin), Offset(w - margin + crossSize, margin), 1f)
            drawLine(reticleColor, Offset(w - margin, margin - crossSize), Offset(w - margin, margin + crossSize), 1f)

            // Bottom-left
            drawLine(reticleColor, Offset(margin - crossSize, h - margin), Offset(margin + crossSize, h - margin), 1f)
            drawLine(reticleColor, Offset(margin, h - margin - crossSize), Offset(margin, h - margin + crossSize), 1f)

            // Bottom-right
            drawLine(reticleColor, Offset(w - margin - crossSize, h - margin), Offset(w - margin + crossSize, h - margin), 1f)
            drawLine(reticleColor, Offset(w - margin, h - margin - crossSize), Offset(w - margin, h - margin + crossSize), 1f)
        }

        // ── Main UI Layout ──────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Top Navigation Bar: Minimal Icon-Only Holographic Controls ─
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Icon: Memory Map Navigation (Constellation Glyph)
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(JarvisSurfaceDark.copy(alpha = 0.50f), CircleShape)
                        .border(0.8.dp, JarvisHologram.copy(alpha = 0.30f), CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onNavigateToMemory
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val p1 = Offset(c.x - 5f, c.y - 4f)
                        val p2 = Offset(c.x + 5f, c.y - 2f)
                        val p3 = Offset(c.x, c.y + 5f)

                        drawLine(JarvisHologram.copy(alpha = 0.7f), p1, p2, 1.2f)
                        drawLine(JarvisHologram.copy(alpha = 0.7f), p2, p3, 1.2f)
                        drawLine(JarvisHologram.copy(alpha = 0.7f), p3, p1, 1.2f)

                        drawCircle(JarvisOrangeBright, 2f, p1)
                        drawCircle(JarvisOrangeBright, 2.4f, p2)
                        drawCircle(JarvisOrangeLight, 2f, p3)
                    }
                }

                // Center Title: J.A.R.V.I.S.
                Text(
                    text = "J.A.R.V.I.S.",
                    style = MaterialTheme.typography.titleMedium.copy(
                        letterSpacing = 6.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp
                    ),
                    color = JarvisOrangeBright,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showTranscriptOverlay = !showTranscriptOverlay }
                    )
                )

                // Right Icon: Settings Navigation (Technical Cog Glyph)
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(JarvisSurfaceDark.copy(alpha = 0.50f), CircleShape)
                        .border(0.8.dp, JarvisHologram.copy(alpha = 0.30f), CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onNavigateToSettings
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(18.dp)) {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(JarvisHologram.copy(alpha = 0.8f), 6f, c, style = Stroke(1.4f))
                        drawCircle(JarvisOrangeBright, 2f, c)

                        // 4 technical notches
                        drawLine(JarvisHologram.copy(alpha = 0.8f), Offset(c.x - 8f, c.y), Offset(c.x - 6f, c.y), 1.5f)
                        drawLine(JarvisHologram.copy(alpha = 0.8f), Offset(c.x + 6f, c.y), Offset(c.x + 8f, c.y), 1.5f)
                        drawLine(JarvisHologram.copy(alpha = 0.8f), Offset(c.x, c.y - 8f), Offset(c.x, c.y - 6f), 1.5f)
                        drawLine(JarvisHologram.copy(alpha = 0.8f), Offset(c.x, c.y + 6f), Offset(c.x, c.y + 8f), 1.5f)
                    }
                }
            }

            // ── Center: 3D Volumetric Neural AI Core ────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                JarvisCore(
                    voiceState = state,
                    speechAmplitude = visualizerState.speechAmplitude,
                    coreSize = 600.dp,
                    onCoreClick = {
                        when (val s = state) {
                            is VoiceAssistantState.Speaking -> {
                                // Tap core to immediately interrupt speech & listen
                                viewModel.interruptJarvis()
                            }
                            is VoiceAssistantState.Listening -> {
                                if (s.partialTranscript.isNotBlank()) {
                                    viewModel.finishUserSpeech(s.partialTranscript)
                                } else {
                                    viewModel.cancelListening()
                                }
                            }
                            is VoiceAssistantState.Idle, is VoiceAssistantState.Paused, is VoiceAssistantState.Error, is VoiceAssistantState.WakeWordMonitoring -> {
                                onRequestPermission()
                                viewModel.startListening()
                            }
                            else -> {}
                        }
                    }
                )
            }

            // ── Bottom Area: Subtitle Display & Minimal Controls ────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Real-time Subtitle Display
                SubtitlePanel(subtitleState = subtitleState)

                // Optional Debug / Transcript Panel (Hidden by default for cinematic immersion)
                if (showTranscriptOverlay) {
                    val activeTranscript = when (val s = state) {
                        is VoiceAssistantState.Listening -> s.partialTranscript
                        is VoiceAssistantState.Processing -> s.userTranscript
                        is VoiceAssistantState.Speaking -> s.aiResponse
                        is VoiceAssistantState.WakeWordDetected -> "Wake word detected: ${s.wakePhrase}"
                        is VoiceAssistantState.WakeWordMonitoring -> "Say \"Wake up, JARVIS\""
                        else -> ""
                    }

                    AnimatedVisibility(
                        visible = activeTranscript.isNotBlank(),
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.88f)
                                .background(
                                    color = JarvisBackground.copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .border(
                                    width = 0.8.dp,
                                    color = JarvisHologram.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = activeTranscript,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp
                                ),
                                color = when (state) {
                                    is VoiceAssistantState.Speaking -> JarvisOrangeBright
                                    is VoiceAssistantState.Listening -> JarvisOrangeLight
                                    else -> Color.LightGray
                                },
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Error Message Display (fallback for non-recoverable error states)
                if (state is VoiceAssistantState.Error) {
                    Text(
                        text = (state as VoiceAssistantState.Error).errorMessage,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Color(0xFFFF5252),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                // Ultra-Subtle Icon-Only Fallback Control (No Text Buttons)
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            color = JarvisSurfaceDark.copy(alpha = 0.40f),
                            shape = CircleShape
                        )
                        .border(
                            width = 0.8.dp,
                            color = when (state) {
                                is VoiceAssistantState.Speaking -> JarvisOrangeBright.copy(alpha = 0.70f)
                                is VoiceAssistantState.Listening -> JarvisOrangeLight.copy(alpha = 0.50f)
                                else -> JarvisHologram.copy(alpha = 0.25f)
                            },
                            shape = CircleShape
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                when (val s = state) {
                                    is VoiceAssistantState.Speaking -> viewModel.interruptJarvis()
                                    is VoiceAssistantState.Listening -> {
                                        if (s.partialTranscript.isNotBlank()) {
                                            viewModel.finishUserSpeech(s.partialTranscript)
                                        } else {
                                            viewModel.cancelListening()
                                        }
                                    }
                                    is VoiceAssistantState.Idle, is VoiceAssistantState.Paused, is VoiceAssistantState.Error, is VoiceAssistantState.WakeWordMonitoring -> {
                                        onRequestPermission()
                                        viewModel.startListening()
                                    }
                                    else -> {}
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(20.dp)) {
                        val c = Offset(size.width / 2f, size.height / 2f)
                        when (state) {
                            is VoiceAssistantState.Speaking -> {
                                // Stop / Interrupt icon glyph (small square)
                                drawRect(
                                    color = JarvisOrangeBright,
                                    topLeft = Offset(c.x - 4.5f, c.y - 4.5f),
                                    size = androidx.compose.ui.geometry.Size(9f, 9f)
                                )
                            }
                            is VoiceAssistantState.Listening -> {
                                // Listening / Active pulse glyph (circle + sound ring)
                                drawCircle(JarvisOrangeLight, 3.5f, c)
                                drawCircle(
                                    color = JarvisOrangeLight.copy(alpha = 0.6f),
                                    radius = 7.5f,
                                    center = c,
                                    style = Stroke(1.2f)
                                )
                            }
                            else -> {
                                // Ready / Idle icon glyph (power ring with central spark)
                                drawCircle(
                                    color = JarvisHologram.copy(alpha = 0.5f),
                                    radius = 6.5f,
                                    center = c,
                                    style = Stroke(1.2f)
                                )
                                drawCircle(JarvisOrangeCore.copy(alpha = 0.8f), 2f, c)
                            }
                        }
                    }
                }
            }
        }
    }
}
