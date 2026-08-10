package co.aura.android

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import co.aura.presentation.App

class MainActivity : ComponentActivity() {
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
}
