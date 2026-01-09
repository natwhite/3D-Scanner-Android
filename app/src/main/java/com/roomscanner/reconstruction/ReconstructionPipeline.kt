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
     * Fuse ARCore depth maps into dense point cloud with mesh
     * Uses all keyframes and creates triangles via nearest-neighbor connectivity
     */
    private suspend fun fuseDepthMaps(
        scan: Scan,
        keyframes: List<com.roomscanner.data.Keyframe>
    ): MeshData = withContext(Dispatchers.Default) {
        Log.d(TAG, "Fusing ${keyframes.size} keyframes into mesh")

        val vertices = mutableListOf<Vertex>()
        val faces = mutableListOf<Face>()

        // Process each keyframe
        keyframes.forEachIndexed { index, keyframe ->
            updateProgress("Processing keyframe ${index + 1}/${keyframes.size}", 0.3f + (index.toFloat() / keyframes.size) * 0.4f)

            // Load depth and RGB
            val depthPath = keyframe.depthPath ?: return@forEachIndexed
            val depthBitmap = BitmapFactory.decodeFile(depthPath) ?: return@forEachIndexed
            val rgbBitmap = BitmapFactory.decodeFile(keyframe.imagePath) ?: return@forEachIndexed

            val width = depthBitmap.width
            val height = depthBitmap.height
            val pose = keyframe.pose

            // ARCore camera intrinsics (approximate)
            val fx = width * 0.8f
            val fy = height * 0.8f
            val cx = width / 2f
            val cy = height / 2f

            // Grid to track vertex indices for this frame
            val vertexGrid = Array(height) { IntArray(width) { -1 } }

            // Step 1: Create vertices from depth map (denser sampling)
            val step = 4 // Sample every 4th pixel for good coverage

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

            // Step 2: Create triangles from this frame's grid
            for (y in 0 until height - step step step) {
                for (x in 0 until width - step step step) {
                    val v00 = vertexGrid[y][x]
                    val v10 = vertexGrid[y][x + step]
                    val v01 = vertexGrid[y + step][x]
                    val v11 = vertexGrid[y + step][x + step]

                    // Create triangles if we have valid vertices
                    if (v00 >= 0 && v10 >= 0 && v11 >= 0) {
                        // First triangle: v00, v10, v11
                        if (isValidTriangle(vertices[v00], vertices[v10], vertices[v11])) {
                            faces.add(Face(v00, v10, v11))
                        }
                    }

                    if (v00 >= 0 && v11 >= 0 && v01 >= 0) {
                        // Second triangle: v00, v11, v01
                        if (isValidTriangle(vertices[v00], vertices[v11], vertices[v01])) {
                            faces.add(Face(v00, v11, v01))
                        }
                    }
                }
            }

            rgbBitmap.recycle()
            depthBitmap.recycle()

            Log.d(TAG, "Keyframe $index: ${vertices.size} total vertices, ${faces.size} total faces")
        }

        Log.i(TAG, "Created mesh with ${vertices.size} vertices, ${faces.size} faces from ${keyframes.size} keyframes")

        MeshData(vertices, faces)
    }

    /**
     * Check if triangle is valid (not too stretched)
     */
    private fun isValidTriangle(v0: Vertex, v1: Vertex, v2: Vertex): Boolean {
        val maxEdge = 0.5f // 50cm max edge length (relaxed)

        val d01 = distance(v0, v1)
        val d12 = distance(v1, v2)
        val d20 = distance(v2, v0)

        return d01 < maxEdge && d12 < maxEdge && d20 < maxEdge
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
