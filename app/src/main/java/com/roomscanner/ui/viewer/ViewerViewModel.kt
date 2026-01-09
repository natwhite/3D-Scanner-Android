package com.roomscanner.ui.viewer

import android.app.Application
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roomscanner.data.Scan
import com.roomscanner.data.ScanRepository
import com.roomscanner.data.ScanStatus
import com.roomscanner.reconstruction.ReconstructionPipeline
import com.roomscanner.reconstruction.ReconstructionProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel for 3D viewer screen
 */
class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ScanRepository.getInstance(application)
    private val reconstructionPipeline = ReconstructionPipeline(application)

    private val _pointCount = MutableStateFlow(0)
    val pointCount: StateFlow<Int> = _pointCount.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _reconstructionProgress = MutableStateFlow(ReconstructionProgress())
    val reconstructionProgress: StateFlow<ReconstructionProgress> = _reconstructionProgress.asStateFlow()

    private val _meshFile = MutableStateFlow<File?>(null)
    val meshFile: StateFlow<File?> = _meshFile.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * Load scan and analyze data
     */
    suspend fun loadScan(scan: Scan) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Loading scan: ${scan.name}")

        // Load keyframes
        val keyframes = repository.loadKeyframes(scan)
        Log.d(TAG, "Loaded ${keyframes.size} keyframes")

        // Count points from depth maps
        var totalPoints = 0
        keyframes.forEach { keyframe ->
            keyframe.depthPath?.let { depthPath ->
                val depthFile = File(depthPath)
                if (depthFile.exists()) {
                    // Decode depth image to count pixels
                    val bitmap = BitmapFactory.decodeFile(depthPath)
                    if (bitmap != null) {
                        totalPoints += bitmap.width * bitmap.height
                        bitmap.recycle()
                    }
                }
            }
        }

        Log.i(TAG, "Total depth points: $totalPoints")
        _pointCount.value = totalPoints
        _isLoading.value = false

        // Check if already processed
        scan.meshPath?.let { meshPath ->
            val file = File(meshPath)
            if (file.exists()) {
                _meshFile.value = file
            }
        }
    }

    /**
     * Start reconstruction process
     */
    fun startReconstruction(scan: Scan) {
        if (_isProcessing.value) {
            Log.w(TAG, "Reconstruction already in progress")
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            _isProcessing.value = true
            _errorMessage.value = null

            try {
                Log.i(TAG, "Starting reconstruction for scan: ${scan.name}")

                // Collect progress updates
                launch {
                    reconstructionPipeline.progress.collect { progress ->
                        _reconstructionProgress.value = progress
                    }
                }

                // Run reconstruction
                val result = reconstructionPipeline.reconstructScan(scan)

                result.fold(
                    onSuccess = { meshFile ->
                        Log.i(TAG, "Reconstruction successful: ${meshFile.absolutePath}")
                        _meshFile.value = meshFile
                        _isProcessing.value = false
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Reconstruction failed", error)
                        _errorMessage.value = error.message ?: "Reconstruction failed"
                        _isProcessing.value = false
                    }
                )

            } catch (e: Exception) {
                Log.e(TAG, "Reconstruction error", e)
                _errorMessage.value = e.message ?: "Unknown error"
                _isProcessing.value = false
            }
        }
    }

    companion object {
        private const val TAG = "ViewerViewModel"
    }
}
