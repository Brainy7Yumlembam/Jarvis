package co.aura.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import co.aura.core.di.initKoinHelper
import co.aura.presentation.App

fun main() = application {
    // Initialize Dependency Injection
    initKoinHelper()
    
    Window(
        onCloseRequest = ::exitApplication,
        title = "JARVIS Assistant"
    ) {
        App()
    }
}
