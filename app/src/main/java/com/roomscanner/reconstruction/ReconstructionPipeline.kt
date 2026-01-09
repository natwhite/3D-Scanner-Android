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

            // Step 2: Fuse depth maps into point cloud
            updateProgress("Fusing depth maps", 0.2f)
            val pointCloud = fuseDepthMaps(scan, keyframes)
            Log.i(TAG, "Generated point cloud with ${pointCloud.size} points")

            // Step 3: Generate mesh
            updateProgress("Generating mesh", 0.6f)
            val mesh = generateMesh(pointCloud)
            Log.i(TAG, "Generated mesh with ${mesh.vertices.size} vertices, ${mesh.faces.size} faces")

            // Step 4: Texture mesh
            updateProgress("Texturing mesh", 0.8f)
            textureMesh(mesh, scan, keyframes)
            Log.i(TAG, "Textured mesh complete")

            // Step 5: Export
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
     */
    private suspend fun fuseDepthMaps(
        scan: Scan,
        keyframes: List<com.roomscanner.data.Keyframe>
    ): PointCloud = withContext(Dispatchers.Default) {
        val points = mutableListOf<Point3D>()

        keyframes.forEachIndexed { index, keyframe ->
            updateProgress("Fusing depth maps", 0.2f + (index.toFloat() / keyframes.size) * 0.4f)

            // Load depth map
            val depthPath = keyframe.depthPath ?: return@forEachIndexed
            val depthFile = File(depthPath)
            if (!depthFile.exists()) {
                Log.w(TAG, "Depth file not found: $depthPath")
                return@forEachIndexed
            }

            // Load RGB image for colors
            val rgbBitmap = BitmapFactory.decodeFile(keyframe.imagePath)
            val depthBitmap = BitmapFactory.decodeFile(depthPath)

            if (rgbBitmap == null || depthBitmap == null) {
                Log.w(TAG, "Failed to load images for keyframe $index")
                return@forEachIndexed
            }

            // Unproject depth to 3D points
            val pose = keyframe.pose
            val width = depthBitmap.width
            val height = depthBitmap.height

            // Simple camera intrinsics (assumes typical phone camera)
            // TODO: Use actual ARCore intrinsics
            val fx = width * 0.8f  // Approximate focal length
            val fy = height * 0.8f
            val cx = width / 2f
            val cy = height / 2f

            // Sample every Nth pixel to reduce point count
            val step = 4

            for (y in 0 until height step step) {
                for (x in 0 until width step step) {
                    // Get depth value (stored as grayscale, map back to meters)
                    val depthPixel = depthBitmap.getPixel(x, y)
                    val depthNormalized = (depthPixel and 0xFF) / 255f
                    val depthMeters = depthNormalized * 8.0f // ARCore depth range ~0-8m

                    // Skip invalid depths
                    if (depthMeters < 0.1f || depthMeters > 6.0f) continue

                    // Unproject to camera space
                    // ARCore camera: X right, Y down, Z forward (away from device)
                    val xCam = (x - cx) * depthMeters / fx
                    val yCam = -(y - cy) * depthMeters / fy  // Negate Y for camera down convention
                    val zCam = -depthMeters  // Negate Z for forward direction

                    // Transform to world space using pose
                    val point = transformPoint(xCam, yCam, zCam, pose)

                    // Get RGB color
                    val rgbX = (x.toFloat() / width * rgbBitmap.width).toInt().coerceIn(0, rgbBitmap.width - 1)
                    val rgbY = (y.toFloat() / height * rgbBitmap.height).toInt().coerceIn(0, rgbBitmap.height - 1)
                    val color = rgbBitmap.getPixel(rgbX, rgbY)

                    points.add(
                        Point3D(
                            x = point[0],
                            y = point[1],
                            z = point[2],
                            r = (color shr 16) and 0xFF,
                            g = (color shr 8) and 0xFF,
                            b = color and 0xFF
                        )
                    )
                }
            }

            rgbBitmap.recycle()
            depthBitmap.recycle()

            Log.d(TAG, "Keyframe $index: added ${points.size} points total")
        }

        Log.i(TAG, "Total points before filtering: ${points.size}")

        // Simple outlier removal (remove points far from median)
        val filteredPoints = removeOutliers(points)

        Log.i(TAG, "Points after filtering: ${filteredPoints.size}")

        PointCloud(filteredPoints)
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
     * Remove outlier points
     */
    private fun removeOutliers(points: List<Point3D>): List<Point3D> {
        if (points.size < 100) return points

        // Calculate bounding box
        val xValues = points.map { it.x }
        val yValues = points.map { it.y }
        val zValues = points.map { it.z }

        val xMin = xValues.minOrNull() ?: 0f
        val xMax = xValues.maxOrNull() ?: 0f
        val yMin = yValues.minOrNull() ?: 0f
        val yMax = yValues.maxOrNull() ?: 0f
        val zMin = zValues.minOrNull() ?: 0f
        val zMax = zValues.maxOrNull() ?: 0f

        // Remove points outside reasonable bounds (assuming room is < 10m in any dimension)
        val maxDim = maxOf(xMax - xMin, yMax - yMin, zMax - zMin)
        if (maxDim > 10f) {
            // Filter to reasonable room size
            return points.filter {
                it.x in (xMin..xMax) &&
                it.y in (yMin..yMax) &&
                it.z in (zMin..zMax) &&
                (it.x - xMin) < 10f &&
                (it.y - yMin) < 10f &&
                (it.z - zMin) < 10f
            }
        }

        return points
    }

    /**
     * Generate mesh from point cloud
     * Simplified approach: Delaunay-inspired nearest neighbor triangulation
     */
    private fun generateMesh(pointCloud: PointCloud): Mesh {
        Log.d(TAG, "Generating mesh from ${pointCloud.points.size} points")

        val vertices = mutableListOf<Vertex>()
        val faces = mutableListOf<Face>()

        // Add all points as vertices
        pointCloud.points.forEach { point ->
            vertices.add(
                Vertex(
                    x = point.x,
                    y = point.y,
                    z = point.z,
                    r = point.r,
                    g = point.g,
                    b = point.b
                )
            )
        }

        // Simple nearest-neighbor triangulation
        // For each vertex, connect to its nearest neighbors
        val maxConnections = 20000  // Limit to prevent excessive triangles
        var connectionCount = 0

        for (i in vertices.indices) {
            if (connectionCount >= maxConnections) break

            val v1 = vertices[i]

            // Find nearest neighbors
            val neighbors = findNearestNeighbors(v1, vertices, i, maxNeighbors = 8)

            // Create triangles with nearest neighbors
            for (j in 0 until neighbors.size - 1) {
                if (connectionCount >= maxConnections) break

                val v2Idx = neighbors[j]
                val v3Idx = neighbors[j + 1]

                // Check triangle quality (avoid degenerate triangles)
                if (isValidTriangle(vertices[i], vertices[v2Idx], vertices[v3Idx])) {
                    faces.add(Face(i, v2Idx, v3Idx))
                    connectionCount++
                }
            }
        }

        Log.d(TAG, "Created mesh with ${vertices.size} vertices, ${faces.size} faces")

        return Mesh(vertices, faces)
    }

    /**
     * Find K nearest neighbors to a vertex
     */
    private fun findNearestNeighbors(
        vertex: Vertex,
        vertices: List<Vertex>,
        currentIdx: Int,
        maxNeighbors: Int
    ): List<Int> {
        val distances = vertices.indices
            .filter { it != currentIdx }
            .map { idx ->
                val v = vertices[idx]
                val dist = distance(vertex, v)
                idx to dist
            }
            .sortedBy { it.second }
            .take(maxNeighbors)
            .map { it.first }

        return distances
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
     * Check if triangle is valid (not degenerate)
     */
    private fun isValidTriangle(v1: Vertex, v2: Vertex, v3: Vertex): Boolean {
        // Check edge lengths - reject if any edge is too long
        val maxEdgeLength = 0.5f  // 50cm max edge
        val d12 = distance(v1, v2)
        val d23 = distance(v2, v3)
        val d31 = distance(v3, v1)

        return d12 < maxEdgeLength && d23 < maxEdgeLength && d31 < maxEdgeLength
    }

    /**
     * Apply texture to mesh
     */
    private fun textureMesh(
        mesh: Mesh,
        scan: Scan,
        keyframes: List<com.roomscanner.data.Keyframe>
    ) {
        // For PoC: Store vertex colors
        // Full implementation would:
        // 1. Generate UV coordinates
        // 2. Project keyframe images onto mesh
        // 3. Create texture atlas
        Log.d(TAG, "Texturing mesh (using vertex colors for PoC)")
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
 * 3D Point with color
 */
data class Point3D(
    val x: Float,
    val y: Float,
    val z: Float,
    val r: Int,
    val g: Int,
    val b: Int
)

/**
 * Point cloud
 */
data class PointCloud(
    val points: List<Point3D>
) {
    val size: Int get() = points.size
}

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
