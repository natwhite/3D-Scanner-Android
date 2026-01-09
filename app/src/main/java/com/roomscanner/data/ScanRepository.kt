package com.roomscanner.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.util.Log
import androidx.core.graphics.scale
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Repository for managing scan data persistence
 */
class ScanRepository(private val context: Context) {

    private val scansDir = File(context.filesDir, "scans")
    private val gson = Gson()

    init {
        if (!scansDir.exists()) {
            scansDir.mkdirs()
        }
    }

    /**
     * Create a new scan directory
     */
    suspend fun createScan(): Scan = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val scanId = "scan_$timestamp"
        val scanDir = File(scansDir, scanId)

        scanDir.mkdirs()
        File(scanDir, "frames").mkdirs()
        File(scanDir, "depth").mkdirs()
        File(scanDir, "output").mkdirs()

        val scan = Scan(
            id = scanId,
            name = "Room Scan ${SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(timestamp))}",
            timestamp = Date(timestamp),
            scanDirectory = scanDir,
            status = ScanStatus.CAPTURING
        )

        saveScanMetadata(scan)
        Log.i(TAG, "Created new scan: ${scan.name} at ${scanDir.absolutePath}")
        scan
    }

    /**
     * Save keyframe to disk
     */
    suspend fun saveKeyframe(
        scan: Scan,
        frameId: Int,
        image: ByteArray,
        depth: ByteArray?,
        pose: Keyframe.Pose
    ): Keyframe = withContext(Dispatchers.IO) {

        val framesDir = File(scan.scanDirectory, "frames")
        val depthDir = File(scan.scanDirectory, "depth")

        // Save RGB image
        val imagePath = File(framesDir, "frame_$frameId.jpg")
        FileOutputStream(imagePath).use { it.write(image) }

        // Save depth if available
        val depthPath = depth?.let {
            val path = File(depthDir, "depth_$frameId.png")
            FileOutputStream(path).use { out -> out.write(it) }
            path.absolutePath
        }

        // Create keyframe
        val keyframe = Keyframe(
            id = frameId,
            timestamp = System.currentTimeMillis(),
            imagePath = imagePath.absolutePath,
            depthPath = depthPath,
            pose = pose
        )

        // Save keyframe metadata
        val keyframeFile = File(scan.scanDirectory, "keyframes.json")
        val keyframes = loadKeyframes(scan).toMutableList()
        keyframes.add(keyframe)
        keyframeFile.writeText(gson.toJson(keyframes))

        Log.d(TAG, "Saved keyframe #$frameId to ${imagePath.name} ${if (depthPath != null) "+ depth" else ""}")
        keyframe
    }

    /**
     * Load all keyframes for a scan
     */
    suspend fun loadKeyframes(scan: Scan): List<Keyframe> = withContext(Dispatchers.IO) {
        val keyframeFile = File(scan.scanDirectory, "keyframes.json")
        if (!keyframeFile.exists()) {
            return@withContext emptyList()
        }

        try {
            val json = keyframeFile.readText()
            gson.fromJson(json, Array<Keyframe>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Save scan metadata
     */
    suspend fun saveScanMetadata(scan: Scan) = withContext(Dispatchers.IO) {
        val metadataFile = File(scan.scanDirectory, "metadata.json")
        metadataFile.writeText(gson.toJson(scan))
    }

    /**
     * Load scan metadata
     */
    suspend fun loadScan(scanId: String): Scan? = withContext(Dispatchers.IO) {
        val scanDir = File(scansDir, scanId)
        if (!scanDir.exists()) return@withContext null

        val metadataFile = File(scanDir, "metadata.json")
        if (!metadataFile.exists()) return@withContext null

        try {
            gson.fromJson(metadataFile.readText(), Scan::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * List all scans
     */
    suspend fun listScans(): List<Scan> = withContext(Dispatchers.IO) {
        scansDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                try {
                    val metadataFile = File(dir, "metadata.json")
                    if (metadataFile.exists()) {
                        gson.fromJson(metadataFile.readText(), Scan::class.java)
                    } else null
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    /**
     * Delete a scan
     */
    suspend fun deleteScan(scan: Scan): Boolean = withContext(Dispatchers.IO) {
        scan.scanDirectory.deleteRecursively()
    }

    /**
     * Update scan status
     */
    suspend fun updateScanStatus(
        scan: Scan,
        status: ScanStatus,
        progress: Float = 0f,
        errorMessage: String? = null
    ): Scan = withContext(Dispatchers.IO) {
        val updatedScan = scan.copy(
            status = status,
            processingProgress = progress,
            errorMessage = errorMessage
        )
        saveScanMetadata(updatedScan)
        updatedScan
    }

    /**
     * Save thumbnail for a scan
     */
    suspend fun saveThumbnail(scan: Scan, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        val thumbnailFile = File(scan.scanDirectory, "thumbnail.jpg")
        val scaled = bitmap.scale(256, 256)

        FileOutputStream(thumbnailFile).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }

        val updatedScan = scan.copy(thumbnailPath = thumbnailFile.absolutePath)
        saveScanMetadata(updatedScan)
    }

    /**
     * Convert Image to JPEG bytes
     */
    fun imageToJPEG(image: Image, quality: Int = 90): ByteArray {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // Decode and re-encode as JPEG if needed
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val outputStream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)

        return outputStream.toByteArray()
    }

    /**
     * Convert depth Image to PNG bytes
     */
    fun depthToPNG(depthImage: Image): ByteArray {
        val width = depthImage.width
        val height = depthImage.height
        val buffer = depthImage.planes[0].buffer

        // Create bitmap from depth data
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        for (i in pixels.indices) {
            val depth = buffer.getShort(i * 2).toInt() and 0xFFFF
            val normalized = (depth / 8000f * 255).toInt().coerceIn(0, 255)
            pixels[i] = (255 shl 24) or (normalized shl 16) or (normalized shl 8) or normalized
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val outputStream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)

        return outputStream.toByteArray()
    }

    companion object {
        private const val TAG = "ScanRepository"

        @Volatile
        private var instance: ScanRepository? = null

        fun getInstance(context: Context): ScanRepository {
            return instance ?: synchronized(this) {
                instance ?: ScanRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
