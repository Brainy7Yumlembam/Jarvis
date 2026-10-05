package co.aura.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.aura.presentation.theme.AuraPrimary
import co.aura.presentation.theme.JarvisBackground
import co.aura.presentation.theme.JarvisHologram
import co.aura.presentation.theme.JarvisOrangeBright
import co.aura.presentation.theme.JarvisOrangeLight
import co.aura.presentation.viewmodel.SubtitleItem
import co.aura.presentation.viewmodel.SubtitleSpeaker
import co.aura.presentation.viewmodel.SubtitleUiState

/**
 * Cinematic holographic subtitle display for JARVIS.
 * Displays real-time user speech recognition and streaming assistant responses.
 * Bounded to prevent covering the 3D neural brain core.
 */
@Composable
fun SubtitlePanel(
    subtitleState: SubtitleUiState,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    // Auto-scroll to bottom as new text streams in or speech updates
    LaunchedEffect(subtitleState.history.size, subtitleState.currentJarvisSubtitle, subtitleState.currentUserSubtitle) {
        if (subtitleState.history.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    AnimatedVisibility(
        visible = subtitleState.history.isNotEmpty(),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .heightIn(max = 160.dp)
                .background(
                    color = JarvisBackground.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(12.dp)
                )
                .border(
                    width = 0.8.dp,
                    color = JarvisHologram.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                subtitleState.history.forEach { item ->
                    SubtitleMessageRow(item = item)
                }
            }
        }
    }
}

@Composable
private fun SubtitleMessageRow(item: SubtitleItem) {
    val isUser = item.speaker == SubtitleSpeaker.USER
    val labelColor = if (isUser) AuraPrimary else JarvisOrangeBright
    val labelText = if (isUser) "YOU" else "JARVIS"
    val textColor = if (isUser) Color(0xFFE8EEF5) else JarvisOrangeLight

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = labelText,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp
            ),
            color = labelColor
        )
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                lineHeight = 18.sp
            ),
            color = textColor,
            softWrap = true
        )
    }
}
