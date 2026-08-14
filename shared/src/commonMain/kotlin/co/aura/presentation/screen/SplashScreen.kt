package co.aura.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import co.aura.presentation.navigation.Routes
import co.aura.presentation.theme.AuraPrimary
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onNavigateToDashboard: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0A13)),
        contentAlignment = Alignment.Center
    ) {
        LaunchedEffect(Unit) {
            delay(1500)
            onNavigateToDashboard()
        }
        Text(text = "J A R V I S", color = AuraPrimary)
    }
}
