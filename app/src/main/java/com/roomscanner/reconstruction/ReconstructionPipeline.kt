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

/**
 * Orchestrates the 3D reconstruction pipeline
 *
 * Simplified approach leveraging ARCore:
 * 1. Load keyframes with ARCore poses (already metric)
 * 2. Fuse ARCore depth maps into dense point cloud
 * 3. Generate mesh using marching cubes or ball pivoting
 * 4. Texture mesh from keyframe images
 * 5. Export to OBJ format
 */
class ReconstructionPipeline(private val context: Context) {

    private val repository = ScanRepository.getInstance(context)

    private val _progress = MutableStateFlow(ReconstructionProgress())
    val progress: StateFlow<ReconstructionProgress> = _progress.asStateFlow()

    /**
     * Run full reconstruction pipeline
     */
    suspend fun reconstructScan(scan: Scan): Result<File> = withContext(Dispatchers.Default) {
        try {
            Log.i(TAG, "Starting reconstruction for scan: ${scan.name}")
            updateProgress("Initializing", 0f)

            // Update scan status
            repository.updateScanStatus(scan, ScanStatus.PROCESSING)

            // Step 1: Load keyframes
            updateProgress("Loading keyframes", 0.1f)
            val keyframes = repository.loadKeyframes(scan)
            Log.d(TAG, "Loaded ${keyframes.size} keyframes")

            if (keyframes.isEmpty()) {
                throw IllegalStateException("No keyframes found in scan")
            }

            // Step 2: Generate mesh from reference depth map
            updateProgress("Generating mesh", 0.2f)
            val meshData = fuseDepthMaps(scan, keyframes)
            Log.i(TAG, "Generated mesh with ${meshData.vertices.size} vertices, ${meshData.faces.size} faces")

            // Convert to Mesh format
            val mesh = Mesh(meshData.vertices, meshData.faces)

            // Step 3: Texture mesh (colors already in vertices)
            updateProgress("Finalizing", 0.7f)
            Log.i(TAG, "Mesh complete with vertex colors")

            // Step 4: Export
            updateProgress("Exporting", 0.9f)
            val meshFile = exportMesh(scan, mesh)
            Log.i(TAG, "Exported mesh to: ${meshFile.absolutePath}")

            // Update scan status
            repository.updateScanStatus(
                scan.copy(meshPath = meshFile.absolutePath),
                ScanStatus.COMPLETED,
                progress = 1.0f
            )

            updateProgress("Complete", 1.0f)
            Result.success(meshFile)

        } catch (e: Exception) {
            Log.e(TAG, "Reconstruction failed", e)
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
     * Fuse ARCore depth maps into dense point cloud
     * Uses grid-based approach from reference keyframe for proper mesh structure
     */
    private suspend fun fuseDepthMaps(
        scan: Scan,
        keyframes: List<com.roomscanner.data.Keyframe>
    ): MeshData = withContext(Dispatchers.Default) {
        // Use middle keyframe as reference for mesh structure
        val referenceIdx = keyframes.size / 2
        val referenceKeyframe = keyframes[referenceIdx]

        Log.d(TAG, "Using keyframe $referenceIdx as reference mesh")

        // Load reference depth and RGB
        val depthPath = referenceKeyframe.depthPath ?: throw IllegalStateException("No depth data")
        val depthBitmap = BitmapFactory.decodeFile(depthPath) ?: throw IllegalStateException("Failed to load depth")
        val rgbBitmap = BitmapFactory.decodeFile(referenceKeyframe.imagePath) ?: throw IllegalStateException("Failed to load RGB")

        val width = depthBitmap.width
        val height = depthBitmap.height
        val pose = referenceKeyframe.pose

        // ARCore camera intrinsics (approximate - should use actual from ARCore)
        val fx = width * 0.8f
        val fy = height * 0.8f
        val cx = width / 2f
        val cy = height / 2f

        val vertices = mutableListOf<Vertex>()
        val faces = mutableListOf<Face>()
        val vertexGrid = Array(height) { IntArray(width) { -1 } } // Track vertex indices

        updateProgress("Generating mesh from reference depth", 0.3f)

        // Step 1: Create vertices from depth map grid
        val step = 2 // Downsample for performance (every 2nd pixel)

        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                // Get depth value
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
                val worldPoint = transformPoint(xCam, yCam, zCam, pose)

                // Get RGB color
                val rgbX = (x.toFloat() / width * rgbBitmap.width).toInt().coerceIn(0, rgbBitmap.width - 1)
                val rgbY = (y.toFloat() / height * rgbBitmap.height).toInt().coerceIn(0, rgbBitmap.height - 1)
                val color = rgbBitmap.getPixel(rgbX, rgbY)

                // Add vertex
                val vertexIdx = vertices.size
                vertices.add(
                    Vertex(
                        x = worldPoint[0],
                        y = worldPoint[1],
                        z = worldPoint[2],
                        r = (color shr 16) and 0xFF,
                        g = (color shr 8) and 0xFF,
                        b = color and 0xFF
                    )
                )
                vertexGrid[y][x] = vertexIdx
            }
        }

        updateProgress("Creating mesh triangles", 0.5f)

        // Step 2: Create triangles from grid structure
        for (y in 0 until height - step step step) {
            for (x in 0 until width - step step step) {
                val v00 = vertexGrid[y][x]
                val v10 = vertexGrid[y][x + step]
                val v01 = vertexGrid[y + step][x]
                val v11 = vertexGrid[y + step][x + step]

                // Only create triangles if all 4 corners have valid depth
                if (v00 >= 0 && v10 >= 0 && v01 >= 0 && v11 >= 0) {
                    // Check if quad is reasonable size (not stretched)
                    if (isValidQuad(vertices[v00], vertices[v10], vertices[v01], vertices[v11])) {
                        // Create two triangles for the quad
                        faces.add(Face(v00, v10, v11))
                        faces.add(Face(v00, v11, v01))
                    }
                }
            }
        }

        rgbBitmap.recycle()
        depthBitmap.recycle()

        Log.i(TAG, "Created mesh with ${vertices.size} vertices, ${faces.size} faces from reference keyframe")

        MeshData(vertices, faces)
    }

    /**
     * Check if quad is valid (edges not too stretched)
     */
    private fun isValidQuad(v00: Vertex, v10: Vertex, v01: Vertex, v11: Vertex): Boolean {
        val maxEdge = 0.3f // 30cm max edge length

        val d01 = distance(v00, v10)
        val d02 = distance(v00, v01)
        val d13 = distance(v10, v11)
        val d23 = distance(v01, v11)

        return d01 < maxEdge && d02 < maxEdge && d13 < maxEdge && d23 < maxEdge
    }

    /**
     * Transform point from camera space to world space using pose
     */
    private fun transformPoint(x: Float, y: Float, z: Float, pose: com.roomscanner.data.Keyframe.Pose): FloatArray {
        // Create rotation matrix from quaternion
        val qx = pose.qx
        val qy = pose.qy
        val qz = pose.qz
        val qw = pose.qw

        // Quaternion to rotation matrix
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
     * Calculate distance between two vertices
     */
    private fun distance(v1: Vertex, v2: Vertex): Float {
        val dx = v1.x - v2.x
        val dy = v1.y - v2.y
        val dz = v1.z - v2.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Export mesh to OBJ format
     */
    private fun exportMesh(scan: Scan, mesh: Mesh): File {
        val outputDir = File(scan.scanDirectory, "output")
        outputDir.mkdirs()

        val objFile = File(outputDir, "model.obj")

        objFile.bufferedWriter().use { writer ->
            // Write header
            writer.write("# Room Scanner PoC\n")
            writer.write("# Vertices: ${mesh.vertices.size}\n")
            writer.write("# Faces: ${mesh.faces.size}\n\n")

            // Write vertices with colors
            mesh.vertices.forEach { vertex ->
                writer.write("v ${vertex.x} ${vertex.y} ${vertex.z} ")
                writer.write("${vertex.r / 255f} ${vertex.g / 255f} ${vertex.b / 255f}\n")
            }

            // Write faces (if any)
            mesh.faces.forEach { face ->
                writer.write("f ${face.v1 + 1} ${face.v2 + 1} ${face.v3 + 1}\n")
            }
        }

        Log.i(TAG, "Wrote OBJ file: ${objFile.absolutePath}")
        return objFile
    }

    private fun updateProgress(stage: String, progress: Float) {
        _progress.value = ReconstructionProgress(stage, progress)
    }

    companion object {
        private const val TAG = "ReconstructionPipeline"
    }
}

/**
 * Reconstruction progress state
 */
data class ReconstructionProgress(
    val stage: String = "",
    val progress: Float = 0f
)

/**
 * Intermediate mesh data from depth processing
 */
data class MeshData(
    val vertices: List<Vertex>,
    val faces: List<Face>
)

/**
 * Mesh vertex
 */
data class Vertex(
    val x: Float,
    val y: Float,
    val z: Float,
    val r: Int = 255,
    val g: Int = 255,
    val b: Int = 255
)

/**
 * Mesh face (triangle)
 */
data class Face(
    val v1: Int,  // Vertex indices
    val v2: Int,
    val v3: Int
)

/**
 * 3D Mesh
 */
data class Mesh(
    val vertices: List<Vertex>,
    val faces: List<Face>
)
