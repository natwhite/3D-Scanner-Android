package com.roomscanner.ui.viewer

import android.app.Application
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roomscanner.data.Scan
import com.roomscanner.data.ScanRepository
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

    private val _pointCount = MutableStateFlow(0)
    val pointCount: StateFlow<Int> = _pointCount.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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
    }

    companion object {
        private const val TAG = "ViewerViewModel"
    }
}
