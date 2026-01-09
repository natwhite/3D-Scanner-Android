package com.roomscanner.reconstruction

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.roomscanner.data.Scan
import com.roomscanner.data.ScanRepository
import com.roomscanner.data.ScanStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * Gaussian Splatting reconstruction pipeline
 * Simplified approach optimized for mobile:
 * 1. Generate 3D Gaussians from ARCore depth maps
 * 2. Each depth pixel becomes a Gaussian primitive
 * 3. Store as PLY file for rendering
 */
class GaussianSplattingPipeline(private val context: Context) {

    private val repository = ScanRepository.getInstance(context)

    private val _progress = MutableStateFlow(ReconstructionProgress())
    val progress: StateFlow<ReconstructionProgress> = _progress.asStateFlow()

    /**
     * Run Gaussian Splatting reconstruction
     */
    suspend fun reconstructScan(scan: Scan): Result<File> = withContext(Dispatchers.Default) {
        try {
            Log.i(TAG, "Starting Gaussian Splatting reconstruction for: ${scan.name}")
            updateProgress("Initializing", 0f)

            repository.updateScanStatus(scan, ScanStatus.PROCESSING)

            // Load keyframes
            updateProgress("Loading keyframes", 0.1f)
            val keyframes = repository.loadKeyframes(scan)
            Log.d(TAG, "Loaded ${keyframes.size} keyframes")

            if (keyframes.isEmpty()) {
                throw IllegalStateException("No keyframes found in scan")
            }

            // Generate Gaussians from all keyframes
            updateProgress("Generating Gaussians", 0.2f)
            val gaussians = generateGaussians(scan, keyframes)
            Log.i(TAG, "Generated ${gaussians.size} Gaussians")

            // Export to PLY
            updateProgress("Exporting", 0.9f)
            val plyFile = exportToPLY(scan, gaussians)
            Log.i(TAG, "Exported to: ${plyFile.absolutePath}")

            // Update scan status
            repository.updateScanStatus(
                scan.copy(meshPath = plyFile.absolutePath),
                ScanStatus.COMPLETED,
                progress = 1.0f
            )

            updateProgress("Complete", 1.0f)
            Result.success(plyFile)

        } catch (e: Exception) {
            Log.e(TAG, "Gaussian Splatting failed", e)
            repository.updateScanStatus(
                scan,
                ScanStatus.FAILED,
                errorMessage = e.message
            )
            updateProgress("Failed: ${e.message}", 0f)
            Result.failure(e)
        }
    }

    /**
     * Generate 3D Gaussians from ARCore depth maps
     */
    private suspend fun generateGaussians(
        scan: Scan,
        keyframes: List<com.roomscanner.data.Keyframe>
    ): List<Gaussian> = withContext(Dispatchers.Default) {
        val gaussians = mutableListOf<Gaussian>()

        keyframes.forEachIndexed { index, keyframe ->
            updateProgress(
                "Processing keyframe $index",
                0.2f + (index.toFloat() / keyframes.size) * 0.7f
            )

            // Load depth and RGB
            val depthPath = keyframe.depthPath ?: return@forEachIndexed
            val depthBitmap = BitmapFactory.decodeFile(depthPath) ?: return@forEachIndexed
            val rgbBitmap = BitmapFactory.decodeFile(keyframe.imagePath) ?: return@forEachIndexed

            val width = depthBitmap.width
            val height = depthBitmap.height
            val pose = keyframe.pose

            // Camera intrinsics
            val fx = width * 0.8f
            val fy = height * 0.8f
            val cx = width / 2f
            val cy = height / 2f

            // Sample every Nth pixel (adaptive based on depth)
            val step = 3 // Higher step = fewer Gaussians, better performance

            for (y in 0 until height step step) {
                for (x in 0 until width step step) {
                    // Get depth
                    val depthPixel = depthBitmap.getPixel(x, y)
                    val depthNormalized = (depthPixel and 0xFF) / 255f
                    val depthMeters = depthNormalized * 8.0f

                    // Skip invalid depths
                    if (depthMeters < 0.1f || depthMeters > 6.0f) continue

                    // Unproject to camera space
                    val xCam = (x - cx) * depthMeters / fx
                    val yCam = -(y - cy) * depthMeters / fy
                    val zCam = -depthMeters

                    // Transform to world space
                    val worldPos = transformPoint(xCam, yCam, zCam, pose)

                    // Get color
                    val rgbX = (x.toFloat() / width * rgbBitmap.width).toInt()
                        .coerceIn(0, rgbBitmap.width - 1)
                    val rgbY = (y.toFloat() / height * rgbBitmap.height).toInt()
                        .coerceIn(0, rgbBitmap.height - 1)
                    val color = rgbBitmap.getPixel(rgbX, rgbY)

                    // Calculate Gaussian scale based on pixel footprint
                    // Larger for distant pixels (covers more space)
                    val pixelSize = depthMeters / fx
                    val scale = pixelSize * 1.5f // 1.5x overlap for smooth coverage

                    // Create Gaussian
                    gaussians.add(
                        Gaussian(
                            position = FloatArray(3) {
                                when (it) {
                                    0 -> worldPos[0]
                                    1 -> worldPos[1]
                                    else -> worldPos[2]
                                }
                            },
                            scale = FloatArray(3) { scale }, // Isotropic for now
                            rotation = FloatArray(4) { i ->
                                // Identity quaternion (no rotation)
                                if (i == 3) 1f else 0f
                            },
                            color = FloatArray(3) {
                                when (it) {
                                    0 -> ((color shr 16) and 0xFF) / 255f
                                    1 -> ((color shr 8) and 0xFF) / 255f
                                    else -> (color and 0xFF) / 255f
                                }
                            },
                            opacity = 0.8f // High opacity for solid surfaces
                        )
                    )
                }
            }

            rgbBitmap.recycle()
            depthBitmap.recycle()

            Log.d(TAG, "Keyframe $index: ${gaussians.size} total Gaussians")
        }

        gaussians
    }

    /**
     * Transform point from camera to world space
     */
    private fun transformPoint(
        x: Float,
        y: Float,
        z: Float,
        pose: com.roomscanner.data.Keyframe.Pose
    ): FloatArray {
        // Quaternion to rotation matrix
        val qx = pose.qx
        val qy = pose.qy
        val qz = pose.qz
        val qw = pose.qw

        val r00 = 1 - 2 * (qy * qy + qz * qz)
        val r01 = 2 * (qx * qy - qz * qw)
        val r02 = 2 * (qx * qz + qy * qw)

        val r10 = 2 * (qx * qy + qz * qw)
        val r11 = 1 - 2 * (qx * qx + qz * qz)
        val r12 = 2 * (qy * qz - qx * qw)

        val r20 = 2 * (qx * qz - qy * qw)
        val r21 = 2 * (qy * qz + qx * qw)
        val r22 = 1 - 2 * (qx * qx + qy * qy)

        // Apply rotation
        val xRot = r00 * x + r01 * y + r02 * z
        val yRot = r10 * x + r11 * y + r12 * z
        val zRot = r20 * x + r21 * y + r22 * z

        // Apply translation
        return floatArrayOf(
            xRot + pose.tx,
            yRot + pose.ty,
            zRot + pose.tz
        )
    }

    /**
     * Export Gaussians to PLY format
     * Using ASCII format for simplicity and mobile compatibility
     */
    private fun exportToPLY(scan: Scan, gaussians: List<Gaussian>): File {
        val outputDir = File(scan.scanDirectory, "output")
        outputDir.mkdirs()

        val plyFile = File(outputDir, "gaussians.ply")

        plyFile.bufferedWriter().use { writer ->
            // PLY header
            writer.write("ply\n")
            writer.write("format ascii 1.0\n")
            writer.write("comment Gaussian Splatting data\n")
            writer.write("element vertex ${gaussians.size}\n")

            // Position
            writer.write("property float x\n")
            writer.write("property float y\n")
            writer.write("property float z\n")

            // Normal (unused but kept for compatibility)
            writer.write("property float nx\n")
            writer.write("property float ny\n")
            writer.write("property float nz\n")

            // Color
            writer.write("property uchar red\n")
            writer.write("property uchar green\n")
            writer.write("property uchar blue\n")

            // Scale
            writer.write("property float scale_0\n")
            writer.write("property float scale_1\n")
            writer.write("property float scale_2\n")

            // Rotation (quaternion)
            writer.write("property float rot_0\n")
            writer.write("property float rot_1\n")
            writer.write("property float rot_2\n")
            writer.write("property float rot_3\n")

            // Opacity
            writer.write("property float opacity\n")

            writer.write("end_header\n")

            // Write Gaussian data as ASCII
            gaussians.forEach { g ->
                // Position
                writer.write("${g.position[0]} ${g.position[1]} ${g.position[2]} ")

                // Normal (zeros)
                writer.write("0.0 0.0 0.0 ")

                // Color (as integers 0-255)
                val r = (g.color[0] * 255).toInt().coerceIn(0, 255)
                val gColor = (g.color[1] * 255).toInt().coerceIn(0, 255)
                val b = (g.color[2] * 255).toInt().coerceIn(0, 255)
                writer.write("$r $gColor $b ")

                // Scale
                writer.write("${g.scale[0]} ${g.scale[1]} ${g.scale[2]} ")

                // Rotation (quaternion)
                writer.write("${g.rotation[0]} ${g.rotation[1]} ${g.rotation[2]} ${g.rotation[3]} ")

                // Opacity
                writer.write("${g.opacity}\n")
            }
        }

        Log.i(TAG, "Wrote PLY file: ${plyFile.absolutePath} with ${gaussians.size} Gaussians")
        return plyFile
    }

    private fun updateProgress(stage: String, progress: Float) {
        _progress.value = ReconstructionProgress(stage, progress)
    }

    companion object {
        private const val TAG = "GaussianSplatting"
    }
}

/**
 * 3D Gaussian primitive
 */
data class Gaussian(
    val position: FloatArray,    // [x, y, z]
    val scale: FloatArray,        // [sx, sy, sz]
    val rotation: FloatArray,     // [qx, qy, qz, qw] quaternion
    val color: FloatArray,        // [r, g, b] normalized 0-1
    val opacity: Float            // alpha 0-1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Gaussian
        return position.contentEquals(other.position) &&
                scale.contentEquals(other.scale) &&
                rotation.contentEquals(other.rotation) &&
                color.contentEquals(other.color) &&
                opacity == other.opacity
    }

    override fun hashCode(): Int {
        var result = position.contentHashCode()
        result = 31 * result + scale.contentHashCode()
        result = 31 * result + rotation.contentHashCode()
        result = 31 * result + color.contentHashCode()
        result = 31 * result + opacity.hashCode()
        return result
    }
}
