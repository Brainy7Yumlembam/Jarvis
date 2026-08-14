package co.aura.android

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import co.aura.presentation.App

import co.aura.presentation.viewmodel.VoiceAssistantViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {
    private val voiceViewModel: VoiceAssistantViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            // Permission result handled; voice state validates permission reactively
        }

        setContent {
            App(onRequestAudioPermission = {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            })
        }
    }

    override fun onResume() {
        super.onResume()
        voiceViewModel.onResume()
    }

    override fun onPause() {
        super.onPause()
        voiceViewModel.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceViewModel.onPause()
    }
}
