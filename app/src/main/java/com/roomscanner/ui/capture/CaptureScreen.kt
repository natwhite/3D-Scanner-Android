package com.roomscanner.ui.capture

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.sceneform.ArSceneView
import com.roomscanner.capture.ARCoreManager
import com.roomscanner.capture.CaptureUiState
import com.roomscanner.capture.CaptureViewModel
import com.roomscanner.data.Scan

@Composable
fun CaptureScreen(
    activity: Activity,
    viewModel: CaptureViewModel = viewModel(),
    onComplete: (Scan) -> Unit,
    onCancel: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var arSceneView by remember { mutableStateOf<ArSceneView?>(null) }
    var arCoreManager by remember { mutableStateOf<ARCoreManager?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current

    // Handle lifecycle
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    arCoreManager?.resume()
                    try {
                        arSceneView?.resume()
                    } catch (e: CameraNotAvailableException) {
                        e.printStackTrace()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    arSceneView?.pause()
                    arCoreManager?.pause()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    arSceneView?.destroy()
                    arCoreManager?.destroy()
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Handle UI state changes
    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is CaptureUiState.ScanComplete -> {
                onComplete(state.scan)
            }
            else -> {}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // AR Scene View
        AndroidView(
            factory = { context ->
                ArSceneView(context).apply {
                    arSceneView = this
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    // Initialize ARCore
                    val manager = ARCoreManager(activity)
                    arCoreManager = manager
                    viewModel.initializeARCore(manager)

                    // Set up frame listener
                    scene.addOnUpdateListener {
                        manager.update()?.let { arFrame ->
                            viewModel.processFrame(arFrame)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay UI
        CaptureOverlay(
            uiState = uiState,
            onStart = { viewModel.startScanning() },
            onStop = { viewModel.stopScanning() },
            onCancel = {
                viewModel.cancelScan()
                onCancel()
            }
        )
    }
}

@Composable
fun CaptureOverlay(
    uiState: CaptureUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Top bar with status
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            color = Color.Black.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Tracking status
                    when (uiState) {
                        is CaptureUiState.Capturing -> {
                            TrackingStatusIndicator(uiState.trackingState)
                        }
                        else -> {
                            Text(
                                text = "Initializing...",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    // Cancel button
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = Color.White
                        )
                    }
                }

                // Frame count
                if (uiState is CaptureUiState.Capturing) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Frames: ${uiState.frameCount}",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Move slowly around the room",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Bottom controls
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            color = Color.Black.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                when (uiState) {
                    is CaptureUiState.Ready -> {
                        // Start button
                        FilledIconButton(
                            onClick = onStart,
                            modifier = Modifier.size(72.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                Icons.Default.FiberManualRecord,
                                contentDescription = "Start Scanning",
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                    is CaptureUiState.Capturing -> {
                        // Stop button
                        FilledIconButton(
                            onClick = onStop,
                            modifier = Modifier.size(72.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop Scanning",
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    is CaptureUiState.Error -> {
                        Text(
                            text = "Error: ${uiState.message}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun TrackingStatusIndicator(trackingState: TrackingState) {
    val (color, text) = when (trackingState) {
        TrackingState.TRACKING -> Color(0xFF4CAF50) to "Tracking: Good"
        TrackingState.PAUSED -> Color(0xFFFFC107) to "Tracking: Limited"
        TrackingState.STOPPED -> Color(0xFFF44336) to "Tracking: Lost"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, CircleShape)
        )
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
