package com.roomscanner.viewer

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * Simple OBJ file loader for textured meshes
 * Supports vertex colors (v x y z r g b) format
 */
class OBJLoader {

    data class Mesh(
        val vertices: FloatArray,      // x, y, z
        val colors: FloatArray,        // r, g, b (0-1 range)
        val normals: FloatArray,       // nx, ny, nz
        val indices: IntArray,         // triangle indices
        val bounds: BoundingBox
    )

    data class BoundingBox(
        val minX: Float, val minY: Float, val minZ: Float,
        val maxX: Float, val maxY: Float, val maxZ: Float
    ) {
        val centerX: Float get() = (minX + maxX) / 2f
        val centerY: Float get() = (minY + maxY) / 2f
        val centerZ: Float get() = (minZ + maxZ) / 2f
        val sizeX: Float get() = maxX - minX
        val sizeY: Float get() = maxY - minY
        val sizeZ: Float get() = maxZ - minZ
        val maxSize: Float get() = maxOf(sizeX, sizeY, sizeZ)
    }

    /**
     * Load OBJ file from disk
     */
    fun loadOBJ(file: File): Mesh {
        Log.d(TAG, "Loading OBJ file: ${file.absolutePath}")

        val vertices = mutableListOf<Float>()
        val colors = mutableListOf<Float>()
        val normals = mutableListOf<Float>()
        val indices = mutableListOf<Int>()

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        var maxZ = Float.MIN_VALUE

        BufferedReader(FileReader(file)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { parseLine(it, vertices, colors, indices, normals) }

                // Update bounds as we parse vertices
                if (vertices.size >= 3) {
                    val lastIndex = vertices.size - 3
                    val x = vertices[lastIndex]
                    val y = vertices[lastIndex + 1]
                    val z = vertices[lastIndex + 2]

                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    minZ = minOf(minZ, z)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                    maxZ = maxOf(maxZ, z)
                }
            }
        }

        Log.d(TAG, "Loaded ${vertices.size / 3} vertices, ${indices.size / 3} triangles")

        // If no normals were provided, compute them
        if (normals.isEmpty()) {
            Log.d(TAG, "Computing vertex normals...")
            computeNormals(vertices, indices, normals)
        }

        // If no colors, use default white
        if (colors.isEmpty()) {
            Log.d(TAG, "No vertex colors found, using default white")
            repeat(vertices.size / 3) {
                colors.add(1f)
                colors.add(1f)
                colors.add(1f)
            }
        }

        val bounds = BoundingBox(minX, minY, minZ, maxX, maxY, maxZ)
        Log.d(TAG, "Mesh bounds: ${bounds.sizeX}x${bounds.sizeY}x${bounds.sizeZ}")

        return Mesh(
            vertices = vertices.toFloatArray(),
            colors = colors.toFloatArray(),
            normals = normals.toFloatArray(),
            indices = indices.toIntArray(),
            bounds = bounds
        )
    }

    private fun parseLine(
        line: String,
        vertices: MutableList<Float>,
        colors: MutableList<Float>,
        indices: MutableList<Int>,
        normals: MutableList<Float>
    ) {
        val parts = line.trim().split("\\s+".toRegex())
        if (parts.isEmpty()) return

        when (parts[0]) {
            "v" -> {
                // Vertex: v x y z [r g b]
                if (parts.size >= 4) {
                    vertices.add(parts[1].toFloat())
                    vertices.add(parts[2].toFloat())
                    vertices.add(parts[3].toFloat())

                    // Vertex colors (optional)
                    if (parts.size >= 7) {
                        colors.add(parts[4].toFloat())
                        colors.add(parts[5].toFloat())
                        colors.add(parts[6].toFloat())
                    }
                }
            }
            "vn" -> {
                // Vertex normal: vn nx ny nz
                if (parts.size >= 4) {
                    normals.add(parts[1].toFloat())
                    normals.add(parts[2].toFloat())
                    normals.add(parts[3].toFloat())
                }
            }
            "f" -> {
                // Face: f v1 v2 v3 or f v1/vt1/vn1 v2/vt2/vn2 v3/vt3/vn3
                if (parts.size >= 4) {
                    // Parse vertex indices (OBJ is 1-indexed, we need 0-indexed)
                    val v1 = parts[1].split("/")[0].toInt() - 1
                    val v2 = parts[2].split("/")[0].toInt() - 1
                    val v3 = parts[3].split("/")[0].toInt() - 1

                    indices.add(v1)
                    indices.add(v2)
                    indices.add(v3)
                }
            }
        }
    }

    /**
     * Compute per-vertex normals from triangle faces
     */
    private fun computeNormals(
        vertices: List<Float>,
        indices: List<Int>,
        normals: MutableList<Float>
    ) {
        val vertexCount = vertices.size / 3

        // Initialize normals to zero
        val normalAccum = FloatArray(vertices.size) { 0f }

        // Accumulate face normals for each vertex
        for (i in indices.indices step 3) {
            val i0 = indices[i]
            val i1 = indices[i + 1]
            val i2 = indices[i + 2]

            // Get vertices
            val v0x = vertices[i0 * 3]
            val v0y = vertices[i0 * 3 + 1]
            val v0z = vertices[i0 * 3 + 2]

            val v1x = vertices[i1 * 3]
            val v1y = vertices[i1 * 3 + 1]
            val v1z = vertices[i1 * 3 + 2]

            val v2x = vertices[i2 * 3]
            val v2y = vertices[i2 * 3 + 1]
            val v2z = vertices[i2 * 3 + 2]

            // Compute edge vectors
            val e1x = v1x - v0x
            val e1y = v1y - v0y
            val e1z = v1z - v0z

            val e2x = v2x - v0x
            val e2y = v2y - v0y
            val e2z = v2z - v0z

            // Compute face normal (cross product)
            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x

            // Accumulate to vertex normals
            normalAccum[i0 * 3] += nx
            normalAccum[i0 * 3 + 1] += ny
            normalAccum[i0 * 3 + 2] += nz

            normalAccum[i1 * 3] += nx
            normalAccum[i1 * 3 + 1] += ny
            normalAccum[i1 * 3 + 2] += nz

            normalAccum[i2 * 3] += nx
            normalAccum[i2 * 3 + 1] += ny
            normalAccum[i2 * 3 + 2] += nz
        }

        // Normalize all vertex normals
        for (i in 0 until vertexCount) {
            val nx = normalAccum[i * 3]
            val ny = normalAccum[i * 3 + 1]
            val nz = normalAccum[i * 3 + 2]

            val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            if (length > 0.0001f) {
                normals.add(nx / length)
                normals.add(ny / length)
                normals.add(nz / length)
            } else {
                normals.add(0f)
                normals.add(1f)
                normals.add(0f)
            }
        }
    }

    companion object {
        private const val TAG = "OBJLoader"
    }
}
