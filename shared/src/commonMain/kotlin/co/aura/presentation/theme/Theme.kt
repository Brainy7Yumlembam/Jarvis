package co.aura.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AuraPrimary = Color(0xFF00E6FF) // Glowing Cyan
val AuraSecondary = Color(0xFF7000FF) // Deep Space Violet
val AuraBackgroundDark = Color(0xFF0B0A13) // Galactic Void
val AuraSurfaceDark = Color(0xFF161525)

private val DarkColorScheme = darkColorScheme(
    primary = AuraPrimary,
    secondary = AuraSecondary,
    background = AuraBackgroundDark,
    surface = AuraSurfaceDark,
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color(0xFFECEFF4),
    onSurface = Color(0xFFECEFF4)
)

private val LightColorScheme = lightColorScheme(
    primary = AuraPrimary,
    secondary = AuraSecondary,
    background = Color(0xFFF4F6FA),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color(0xFF1E1E2E),
    onSurface = Color(0xFF1E1E2E)
)

@Composable
fun AuraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
