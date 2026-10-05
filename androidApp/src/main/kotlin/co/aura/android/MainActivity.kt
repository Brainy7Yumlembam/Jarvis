package co.aura.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import co.aura.presentation.App
import co.aura.presentation.viewmodel.VoiceAssistantViewModel
import co.aura.voice.JarvisVoiceService
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {
    private val voiceViewModel: VoiceAssistantViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val notificationPermissionLauncher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }
        } else null

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                JarvisVoiceService.start(this)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        JarvisVoiceService.onStopRequested = {
            voiceViewModel.interruptJarvis()
            voiceViewModel.cancelListening()
        }

        setContent {
            App(onRequestAudioPermission = {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            })
        }

        // Start the persistent foreground service if audio permission is already present
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            JarvisVoiceService.start(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        voiceViewModel.onResume()
    }

    override fun onPause() {
        super.onPause()
        // Do NOT destroy voice session if background voice persistence is active
        if (!JarvisVoiceService.isRunning) {
            voiceViewModel.onPause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            JarvisVoiceService.stop(this)
            voiceViewModel.onPause()
        }
    }
}
