package com.roomscanner

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.roomscanner.ui.RoomScannerApp
import com.roomscanner.ui.theme.RoomScannerTheme

class MainActivity : ComponentActivity() {

    private var hasCameraPermission by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions
        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.READ_MEDIA_IMAGES
            )
        )

        setContent {
            RoomScannerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RoomScannerApp(
                        activity = this,
                        hasCameraPermission = hasCameraPermission,
                        onRequestPermission = {
                            requestPermissionLauncher.launch(
                                arrayOf(Manifest.permission.CAMERA)
                            )
                        }
                    )
                }
            }
        }
    }
}
