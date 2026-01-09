package com.roomscanner.viewer

import android.opengl.GLES20
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Simplified Gaussian Splat renderer for mobile (OpenGL ES 2.0)
 * Uses billboarding (quads facing camera) for each Gaussian
 * Pre-generates quad vertices on CPU (no gl_VertexID needed)
 */
class GaussianSplatRenderer {

    private var program = 0
    private var positionHandle = 0
    private var cornerHandle = 0
    private var colorHandle = 0
    private var scaleHandle = 0
    private var opacityHandle = 0
    private var mvpMatrixHandle = 0
    private var viewMatrixHandle = 0

    // Vertex data buffers (4 vertices per Gaussian)
    private var vertexBuffer: FloatBuffer? = null  // Gaussian centers (repeated 4x)
    private var cornerBuffer: FloatBuffer? = null   // Corner offsets (-1,-1), (1,-1), (1,1), (-1,1)
    private var colorBuffer: FloatBuffer? = null    // Colors (repeated 4x)
    private var scaleBuffer: FloatBuffer? = null    // Scales (repeated 4x)
    private var opacityBuffer: FloatBuffer? = null  // Opacities (repeated 4x)
    private var indexBuffer: ShortBuffer? = null    // Triangle indices

    private var vertexCount = 0
    private var indexCount = 0

    // Billboard vertex shader - uses corner attribute instead of gl_VertexID
    private val vertexShaderCode = """
        attribute vec3 aPosition;    // Gaussian center
        attribute vec2 aCorner;      // Corner offset (-1 to 1)
        attribute vec3 aColor;       // RGB color
        attribute float aScale;      // Uniform scale
        attribute float aOpacity;    // Alpha

        uniform mat4 uMVPMatrix;
        uniform mat4 uViewMatrix;

        varying vec3 vColor;
        varying float vOpacity;
        varying vec2 vTexCoord;

        void main() {
            // Extract camera right and up vectors from view matrix
            vec3 cameraRight = vec3(uViewMatrix[0][0], uViewMatrix[1][0], uViewMatrix[2][0]);
            vec3 cameraUp = vec3(uViewMatrix[0][1], uViewMatrix[1][1], uViewMatrix[2][1]);

            // Create billboard vertex
            vec3 billboardPos = aPosition +
                                (cameraRight * aCorner.x + cameraUp * aCorner.y) * aScale;

            gl_Position = uMVPMatrix * vec4(billboardPos, 1.0);

            vColor = aColor;
            vOpacity = aOpacity;
            vTexCoord = aCorner * 0.5 + 0.5; // 0-1 range
        }
    """.trimIndent()

    // Fragment shader with Gaussian falloff
    private val fragmentShaderCode = """
        precision mediump float;

        varying vec3 vColor;
        varying float vOpacity;
        varying vec2 vTexCoord;

        void main() {
            // Gaussian falloff from center
            vec2 coord = vTexCoord * 2.0 - 1.0; // -1 to 1
            float dist = length(coord);

            // Gaussian function: exp(-dist^2 / 2)
            float gaussian = exp(-dist * dist * 2.0);

            // Apply Gaussian falloff to opacity
            float alpha = vOpacity * gaussian;

            // Discard very transparent pixels
            if (alpha < 0.01) discard;

            gl_FragColor = vec4(vColor, alpha);
        }
    """.trimIndent()

    /**
     * Initialize OpenGL resources
     */
    fun createOnGlThread() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        // Get attribute/uniform locations
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        cornerHandle = GLES20.glGetAttribLocation(program, "aCorner")
        colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        scaleHandle = GLES20.glGetAttribLocation(program, "aScale")
        opacityHandle = GLES20.glGetAttribLocation(program, "aOpacity")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        viewMatrixHandle = GLES20.glGetUniformLocation(program, "uViewMatrix")

        Log.d(TAG, "Gaussian Splat shader created successfully")
    }

    /**
     * Load Gaussians from PLY file and generate quad vertices
     */
    fun loadPLY(file: File) {
        Log.d(TAG, "Loading Gaussians from: ${file.absolutePath}")

        // Parse PLY header to get vertex count
        val lines = file.readLines()
        var gaussianCount = 0
        var headerEnd = 0

        for ((index, line) in lines.withIndex()) {
            if (line.startsWith("element vertex")) {
                gaussianCount = line.split(" ")[2].toInt()
            }
            if (line == "end_header") {
                headerEnd = index
                break
            }
        }

        Log.d(TAG, "PLY contains $gaussianCount Gaussians")

        // Parse Gaussian data
        val gaussians = mutableListOf<GaussianData>()

        for (i in (headerEnd + 1) until minOf(headerEnd + 1 + gaussianCount, lines.size)) {
            val parts = lines[i].split(" ")
            if (parts.size >= 14) {
                gaussians.add(
                    GaussianData(
                        position = floatArrayOf(parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat()),
                        color = floatArrayOf(parts[6].toInt() / 255f, parts[7].toInt() / 255f, parts[8].toInt() / 255f),
                        scale = (parts[9].toFloat() + parts[10].toFloat() + parts[11].toFloat()) / 3f,
                        opacity = parts[13].toFloat()
                    )
                )
            }
        }

        Log.d(TAG, "Parsed ${gaussians.size} Gaussians, generating quads...")

        // Generate quad vertices (4 vertices per Gaussian)
        val vertices = mutableListOf<Float>()
        val corners = mutableListOf<Float>()
        val colors = mutableListOf<Float>()
        val scales = mutableListOf<Float>()
        val opacities = mutableListOf<Float>()
        val indices = mutableListOf<Short>()

        // Quad corner offsets
        val quadCorners = arrayOf(
            floatArrayOf(-1f, -1f),  // Bottom-left
            floatArrayOf(1f, -1f),   // Bottom-right
            floatArrayOf(1f, 1f),    // Top-right
            floatArrayOf(-1f, 1f)    // Top-left
        )

        gaussians.forEachIndexed { gaussianIdx, gaussian ->
            val baseIdx = (gaussianIdx * 4).toShort()

            // Create 4 vertices for this Gaussian
            for (cornerIdx in 0..3) {
                // Position (Gaussian center, same for all 4 vertices)
                vertices.add(gaussian.position[0])
                vertices.add(gaussian.position[1])
                vertices.add(gaussian.position[2])

                // Corner offset
                corners.add(quadCorners[cornerIdx][0])
                corners.add(quadCorners[cornerIdx][1])

                // Color (same for all 4 vertices)
                colors.add(gaussian.color[0])
                colors.add(gaussian.color[1])
                colors.add(gaussian.color[2])

                // Scale (same for all 4 vertices)
                scales.add(gaussian.scale)

                // Opacity (same for all 4 vertices)
                opacities.add(gaussian.opacity)
            }

            // Create two triangles for the quad
            // Triangle 1: 0, 1, 2
            indices.add(baseIdx)
            indices.add((baseIdx + 1).toShort())
            indices.add((baseIdx + 2).toShort())

            // Triangle 2: 0, 2, 3
            indices.add(baseIdx)
            indices.add((baseIdx + 2).toShort())
            indices.add((baseIdx + 3).toShort())
        }

        vertexCount = vertices.size / 3
        indexCount = indices.size

        // Create GPU buffers
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices.toFloatArray())
            .position(0) as FloatBuffer

        cornerBuffer = ByteBuffer.allocateDirect(corners.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(corners.toFloatArray())
            .position(0) as FloatBuffer

        colorBuffer = ByteBuffer.allocateDirect(colors.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(colors.toFloatArray())
            .position(0) as FloatBuffer

        scaleBuffer = ByteBuffer.allocateDirect(scales.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(scales.toFloatArray())
            .position(0) as FloatBuffer

        opacityBuffer = ByteBuffer.allocateDirect(opacities.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(opacities.toFloatArray())
            .position(0) as FloatBuffer

        indexBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(indices.toShortArray())
            .position(0) as ShortBuffer

        Log.d(TAG, "Generated $vertexCount vertices ($indexCount indices) for ${gaussians.size} Gaussians")
    }

    /**
     * Draw Gaussian Splats
     */
    fun draw(mvpMatrix: FloatArray, viewMatrix: FloatArray) {
        if (vertexCount == 0) return

        GLES20.glUseProgram(program)

        // Enable blending for transparency
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Disable depth writes (but keep depth test for sorting)
        GLES20.glDepthMask(false)

        // Enable attributes
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(cornerHandle)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glEnableVertexAttribArray(scaleHandle)
        GLES20.glEnableVertexAttribArray(opacityHandle)

        // Bind buffers
        vertexBuffer?.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        cornerBuffer?.position(0)
        GLES20.glVertexAttribPointer(cornerHandle, 2, GLES20.GL_FLOAT, false, 0, cornerBuffer)

        colorBuffer?.position(0)
        GLES20.glVertexAttribPointer(colorHandle, 3, GLES20.GL_FLOAT, false, 0, colorBuffer)

        scaleBuffer?.position(0)
        GLES20.glVertexAttribPointer(scaleHandle, 1, GLES20.GL_FLOAT, false, 0, scaleBuffer)

        opacityBuffer?.position(0)
        GLES20.glVertexAttribPointer(opacityHandle, 1, GLES20.GL_FLOAT, false, 0, opacityBuffer)

        // Set uniforms
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(viewMatrixHandle, 1, false, viewMatrix, 0)

        // Draw triangles
        indexBuffer?.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

        // Restore state
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(cornerHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
        GLES20.glDisableVertexAttribArray(scaleHandle)
        GLES20.glDisableVertexAttribArray(opacityHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)

            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val error = GLES20.glGetShaderInfoLog(shader)
                Log.e(TAG, "Shader compilation failed: $error")
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Shader compilation failed")
            }
        }
    }

    private data class GaussianData(
        val position: FloatArray,
        val color: FloatArray,
        val scale: Float,
        val opacity: Float
    )

    companion object {
        private const val TAG = "GaussianSplatRenderer"
    }
}
