package com.roomscanner.capture

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.TrackingState
import com.roomscanner.data.Scan
import com.roomscanner.data.ScanRepository
import com.roomscanner.data.ScanStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for capture screen
 */
class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ScanRepository.getInstance(application)
    private val keyframeSelector = KeyframeSelector()

    private val _uiState = MutableStateFlow<CaptureUiState>(CaptureUiState.Initializing)
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    private var currentScan: Scan? = null
    private var arCoreManager: ARCoreManager? = null
    private var isCapturing = false

    /**
     * Initialize ARCore
     */
    fun initializeARCore(manager: ARCoreManager) {
        arCoreManager = manager

        val result = manager.initialize()
        result.fold(
            onSuccess = {
                _uiState.value = CaptureUiState.Ready
            },
            onFailure = { error ->
                _uiState.value = CaptureUiState.Error(error.message ?: "ARCore initialization failed")
            }
        )
    }

    /**
     * Start scanning
     */
    fun startScanning() {
        if (isCapturing) return

        viewModelScope.launch {
            try {
                // Create new scan
                val scan = repository.createScan()
                currentScan = scan

                isCapturing = true
                keyframeSelector.reset()

                _uiState.value = CaptureUiState.Capturing(
                    frameCount = 0,
                    trackingState = TrackingState.TRACKING
                )
            } catch (e: Exception) {
                _uiState.value = CaptureUiState.Error(e.message ?: "Failed to start scanning")
            }
        }
    }

    /**
     * Stop scanning
     */
    fun stopScanning() {
        if (!isCapturing) return

        viewModelScope.launch {
            isCapturing = false

            currentScan?.let { scan ->
                val frameCount = keyframeSelector.getKeyframeCount()

                if (frameCount > 0) {
                    val updatedScan = repository.updateScanStatus(
                        scan,
                        ScanStatus.CAPTURED
                    ).copy(keyframeCount = frameCount)

                    repository.saveScanMetadata(updatedScan)

                    _uiState.value = CaptureUiState.ScanComplete(updatedScan)
                } else {
                    // No frames captured, delete scan
                    repository.deleteScan(scan)
                    _uiState.value = CaptureUiState.Ready
                }
            }
        }
    }

    /**
     * Process AR frame
     */
    fun processFrame(arFrame: ARFrame) {
        if (!isCapturing) {
            arFrame.release()
            return
        }

        val scan = currentScan ?: run {
            arFrame.release()
            return
        }

        // Update tracking state
        val currentState = _uiState.value
        if (currentState is CaptureUiState.Capturing &&
            currentState.trackingState != arFrame.camera.trackingState) {

            _uiState.value = currentState.copy(
                trackingState = arFrame.camera.trackingState
            )
        }

        // Check if we should capture this frame
        if (keyframeSelector.shouldCaptureKeyframe(arFrame.cameraPose)) {
            captureKeyframe(scan, arFrame)
        } else {
            arFrame.release()
        }
    }

    /**
     * Capture a keyframe
     */
    private fun captureKeyframe(scan: Scan, arFrame: ARFrame) {
        viewModelScope.launch {
            try {
                val frameId = keyframeSelector.getKeyframeCount() - 1

                // Convert images to bytes
                val imageBytes = withContext(Dispatchers.Default) {
                    repository.imageToJPEG(arFrame.cameraImage)
                }

                val depthBytes = arFrame.depthImage?.let { depth ->
                    withContext(Dispatchers.Default) {
                        repository.depthToPNG(depth)
                    }
                }

                // Save keyframe
                repository.saveKeyframe(
                    scan = scan,
                    frameId = frameId,
                    image = imageBytes,
                    depth = depthBytes,
                    pose = arFrame.cameraPose
                )

                // Update UI
                val currentState = _uiState.value
                if (currentState is CaptureUiState.Capturing) {
                    _uiState.value = currentState.copy(
                        frameCount = keyframeSelector.getKeyframeCount()
                    )
                }

            } catch (e: Exception) {
                // Log error but continue capturing
                e.printStackTrace()
            } finally {
                arFrame.release()
            }
        }
    }

    /**
     * Cancel current scan
     */
    fun cancelScan() {
        viewModelScope.launch {
            isCapturing = false

            currentScan?.let { scan ->
                repository.updateScanStatus(scan, ScanStatus.CANCELLED)
                repository.deleteScan(scan)
            }

            currentScan = null
            keyframeSelector.reset()
            _uiState.value = CaptureUiState.Ready
        }
    }

    override fun onCleared() {
        super.onCleared()
        isCapturing = false
    }
}

/**
 * UI State for capture screen
 */
sealed class CaptureUiState {
    object Initializing : CaptureUiState()
    object Ready : CaptureUiState()

    data class Capturing(
        val frameCount: Int,
        val trackingState: TrackingState
    ) : CaptureUiState()

    data class ScanComplete(val scan: Scan) : CaptureUiState()
    data class Error(val message: String) : CaptureUiState()
}
