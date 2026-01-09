package com.roomscanner.ui.viewer

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roomscanner.data.Scan
import com.roomscanner.viewer.Mesh3DViewer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    scanId: String,
    activity: Activity,
    viewModel: ViewerViewModel = viewModel(),
    onBack: () -> Unit
) {
    val scan by viewModel.scan.collectAsState()
    val pointCount by viewModel.pointCount.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val progress by viewModel.reconstructionProgress.collectAsState()
    val meshFile by viewModel.meshFile.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val scope = rememberCoroutineScope()

    // Load scan data by ID
    LaunchedEffect(scanId) {
        scope.launch {
            try {
                viewModel.loadScanById(scanId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load scan", e)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(scan?.name ?: "Loading...") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading || scan == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                errorMessage != null && !isProcessing && meshFile == null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Error Loading Scan",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = errorMessage ?: "Unknown error",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                isProcessing -> {
                    // Show processing UI
                    ProcessingView(
                        progress = progress,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                meshFile != null -> {
                    // Show completed mesh
                    MeshCompletedView(
                        scan = scan!!,
                        meshFile = meshFile!!,
                        pointCount = pointCount,
                        activity = activity
                    )
                }
                else -> {
                    // Show ready to process
                    ReadyToProcessView(
                        scan = scan!!,
                        pointCount = pointCount,
                        errorMessage = errorMessage,
                        onProcessClick = {
                            viewModel.startReconstruction(scan!!)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ReadyToProcessView(
    scan: Scan,
    pointCount: Int,
    errorMessage: String?,
    onProcessClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Info panel
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Scan Ready to Process",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${scan.keyframeCount} keyframes captured",
                    style = MaterialTheme.typography.bodySmall
                )
                if (pointCount > 0) {
                    Text(
                        text = "${pointCount.formatWithCommas()} depth points",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Main content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Build,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Ready to Generate 3D Model",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "This will process your scan into a 3D mesh",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onProcessClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(Icons.Default.Build, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start Processing")
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Previous error: $errorMessage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Processing Steps:",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "• Fuse ARCore depth maps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "• Generate dense point cloud",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "• Create 3D mesh",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "• Apply textures",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "• Export to OBJ format",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun ProcessingView(
    progress: com.roomscanner.reconstruction.ReconstructionProgress,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        CircularProgressIndicator(
            progress = progress.progress,
            modifier = Modifier.size(120.dp),
            strokeWidth = 8.dp
        )

        Text(
            text = progress.stage,
            style = MaterialTheme.typography.titleLarge
        )

        Text(
            text = "${(progress.progress * 100).toInt()}%",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )

        LinearProgressIndicator(
            progress = progress.progress,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = "Please wait while we process your scan...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshCompletedView(
    scan: Scan,
    meshFile: java.io.File,
    pointCount: Int,
    activity: Activity
) {
    var showWireframe by remember { mutableStateOf(false) }
    var showTexture by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 3D Viewer
        Mesh3DViewer(
            meshFile = meshFile,
            modifier = Modifier.fillMaxSize(),
            showWireframe = showWireframe,
            showTexture = showTexture
        )

        // Top controls bar
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Wireframe toggle
                    FilterChip(
                        selected = showWireframe,
                        onClick = { showWireframe = !showWireframe },
                        label = { Text("Wireframe") }
                    )

                    // Texture toggle
                    FilterChip(
                        selected = showTexture,
                        onClick = { showTexture = !showTexture },
                        label = { Text("Colors") }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Info button
                    IconButton(onClick = { showInfo = !showInfo }) {
                        Icon(Icons.Default.CheckCircle, "Info")
                    }

                    // Share button
                    IconButton(onClick = {
                        shareOBJFile(activity, meshFile)
                    }) {
                        Icon(Icons.Default.Share, "Share")
                    }
                }
            }
        }

        // Info panel (bottom sheet style)
        if (showInfo) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Model Details",
                            style = MaterialTheme.typography.titleMedium
                        )
                        TextButton(onClick = { showInfo = false }) {
                            Text("Close")
                        }
                    }

                    Divider()

                    DetailRow("Keyframes", "${scan.keyframeCount}")
                    DetailRow("Depth Points", pointCount.formatWithCommas())
                    DetailRow("Format", "OBJ (Wavefront)")
                    DetailRow("File Size", "${meshFile.length() / 1024} KB")

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Controls:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Drag to rotate\n• Two fingers to pan\n• Pinch to zoom\n• Double tap to reset",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun shareOBJFile(activity: Activity, file: java.io.File) {
    try {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "model/obj"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "3D Room Scan")
            putExtra(Intent.EXTRA_TEXT, "Sharing 3D model from Room Scanner")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        activity.startActivity(Intent.createChooser(shareIntent, "Share 3D Model"))
    } catch (e: Exception) {
        Log.e("ViewerScreen", "Error sharing file", e)
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun Int.formatWithCommas(): String {
    return String.format("%,d", this)
}

private const val TAG = "ViewerScreen"
