package co.aura.presentation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import co.aura.presentation.navigation.Routes
import co.aura.presentation.screen.*
import co.aura.presentation.theme.AuraTheme
import co.aura.presentation.viewmodel.VoiceAssistantViewModel
import org.koin.compose.getKoin
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun App(onRequestAudioPermission: () -> Unit = {}) {
    AuraTheme {
        val navController = rememberNavController()
        val koin = getKoin()
        val voiceViewModel: VoiceAssistantViewModel = viewModel {
            koin.get<VoiceAssistantViewModel>()
        }

        NavHost(
            navController = navController,
            startDestination = Routes.SPLASH
        ) {
            composable(Routes.SPLASH) {
                SplashScreen(
                    onNavigateToDashboard = {
                        navController.navigate(Routes.DASHBOARD) {
                            popUpTo(Routes.SPLASH) { inclusive = true }
                        }
                    }
                )
            }
            composable(Routes.AUTH) {
                AuthScreen(
                    onLoginSuccess = {
                        navController.navigate(Routes.DASHBOARD) {
                            popUpTo(Routes.AUTH) { inclusive = true }
                        }
                    }
                )
            }
            composable(Routes.DASHBOARD) {
                VoiceAssistantScreen(
                    viewModel = voiceViewModel,
                    onRequestPermission = onRequestAudioPermission,
                    onNavigateToMemory = {
                        navController.navigate(Routes.MEMORY_MAP)
                    },
                    onNavigateToSettings = {
                        navController.navigate(Routes.SETTINGS)
                    }
                )
            }
            composable(Routes.MEMORY_MAP) {
                MemoryMapScreen(
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = {
                        navController.popBackStack()
                    },
                    onNavigateToMemory = {
                        navController.navigate(Routes.MEMORY_MAP)
                    }
                )
            }
        }
    }
}
