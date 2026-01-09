package com.roomscanner.ui.capture

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.roomscanner.capture.ARCoreManager
import com.roomscanner.capture.CaptureUiState
import com.roomscanner.capture.CaptureViewModel
import com.roomscanner.data.Scan
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

@Composable
fun CaptureScreen(
    activity: Activity,
    viewModel: CaptureViewModel = viewModel(),
    onComplete: (Scan) -> Unit,
    onCancel: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var surfaceView by remember { mutableStateOf<GLSurfaceView?>(null) }
    var arCoreManager by remember { mutableStateOf<ARCoreManager?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    var shouldResumeOnSurfaceCreated by remember { mutableStateOf(true) } // Start in resumed state

    // Handle lifecycle
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    Log.d(TAG, "Lifecycle ON_RESUME")
                    shouldResumeOnSurfaceCreated = true
                    surfaceView?.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    Log.d(TAG, "Lifecycle ON_PAUSE")
                    shouldResumeOnSurfaceCreated = false
                    surfaceView?.onPause()
                    arCoreManager?.pause()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    Log.d(TAG, "Lifecycle ON_DESTROY")
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
        // AR Surface View
        AndroidView(
            factory = { context ->
                GLSurfaceView(context).apply {
                    surfaceView = this
                    preserveEGLContextOnPause = true
                    setEGLContextClientVersion(2)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)

                    // Initialize ARCore
                    val manager = ARCoreManager(activity)
                    arCoreManager = manager
                    viewModel.initializeARCore(manager)

                    // Set up GL renderer for ARCore
                    setRenderer(object : GLSurfaceView.Renderer {
                        private var cameraTextureId = -1

                        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                            Log.d(TAG, "GL Surface Created")

                            // Set clear color
                            GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

                            // Create camera texture for ARCore
                            val textures = IntArray(1)
                            GLES20.glGenTextures(1, textures, 0)
                            cameraTextureId = textures[0]

                            // Set up the texture for external use (camera)
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cameraTextureId)
                            GLES20.glTexParameteri(
                                GLES20.GL_TEXTURE_2D,
                                GLES20.GL_TEXTURE_MIN_FILTER,
                                GLES20.GL_LINEAR
                            )
                            GLES20.glTexParameteri(
                                GLES20.GL_TEXTURE_2D,
                                GLES20.GL_TEXTURE_MAG_FILTER,
                                GLES20.GL_LINEAR
                            )

                            // Set camera texture for ARCore
                            manager.setCameraTextureName(cameraTextureId)

                            // Resume ARCore session now that GL surface is ready
                            if (shouldResumeOnSurfaceCreated) {
                                manager.resume()
                            }
                        }

                        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                            Log.d(TAG, "GL Surface Changed: ${width}x${height}")
                            GLES20.glViewport(0, 0, width, height)

                            // Get display rotation
                            val displayRotation = when (activity.windowManager.defaultDisplay.rotation) {
                                Surface.ROTATION_0 -> 0
                                Surface.ROTATION_90 -> 1
                                Surface.ROTATION_180 -> 2
                                Surface.ROTATION_270 -> 3
                                else -> 0
                            }

                            // Set display geometry for ARCore
                            manager.setDisplayGeometry(displayRotation, width, height)
                        }

                        override fun onDrawFrame(gl: GL10?) {
                            // Clear screen
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

                            // Update ARCore and process frame
                            manager.update()?.let { arFrame ->
                                viewModel.processFrame(arFrame)
                            }
                        }
                    })

                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
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

private const val TAG = "CaptureScreen"
