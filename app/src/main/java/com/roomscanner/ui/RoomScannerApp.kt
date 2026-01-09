package com.roomscanner.ui

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.roomscanner.ui.capture.CaptureScreen
import com.roomscanner.ui.scans.ScansListScreen

@Composable
fun RoomScannerApp(
    activity: Activity,
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    val navController = rememberNavController()

    if (!hasCameraPermission) {
        PermissionScreen(onRequestPermission = onRequestPermission)
        return
    }

    NavHost(
        navController = navController,
        startDestination = "scans"
    ) {
        composable("scans") {
            ScansListScreen(
                onNewScan = {
                    navController.navigate("capture")
                },
                onScanClick = { scan ->
                    // TODO: Navigate to viewer
                }
            )
        }

        composable("capture") {
            CaptureScreen(
                activity = activity,
                onComplete = { scan ->
                    navController.popBackStack()
                    // TODO: Navigate to processing
                },
                onCancel = {
                    navController.popBackStack()
                }
            )
        }
    }
}

@Composable
fun PermissionScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Camera Permission Required",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "This app needs camera access to scan rooms",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onRequestPermission) {
                Text("Grant Permission")
            }
        }
    }
}
