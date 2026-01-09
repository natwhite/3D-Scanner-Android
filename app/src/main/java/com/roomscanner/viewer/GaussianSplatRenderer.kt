package com.roomscanner.viewer

import android.opengl.GLES20
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Simplified Gaussian Splat renderer for mobile
 * Uses billboarding (quads facing camera) for each Gaussian
 * Optimized for real-time performance on mobile GPUs
 */
class GaussianSplatRenderer {

    private var program = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var scaleHandle = 0
    private var opacityHandle = 0
    private var mvpMatrixHandle = 0
    private var viewMatrixHandle = 0

    // Gaussian data buffers
    private var positionBuffer: FloatBuffer? = null
    private var colorBuffer: FloatBuffer? = null
    private var scaleBuffer: FloatBuffer? = null
    private var opacityBuffer: FloatBuffer? = null

    private var gaussianCount = 0

    // Billboard vertex shader - creates quads facing camera
    private val vertexShaderCode = """
        attribute vec3 aPosition;    // Gaussian center
        attribute vec3 aColor;       // RGB color
        attribute float aScale;      // Uniform scale
        attribute float aOpacity;    // Alpha

        uniform mat4 uMVPMatrix;
        uniform mat4 uViewMatrix;

        varying vec3 vColor;
        varying float vOpacity;
        varying vec2 vTexCoord;

        void main() {
            // Billboard corner offset (creates quad)
            // gl_VertexID % 4 gives us corner index (0,1,2,3)
            float cornerIdx = mod(float(gl_VertexID), 4.0);
            vec2 corner = vec2(0.0);
            if (cornerIdx < 0.5) corner = vec2(-1.0, -1.0);      // Bottom-left
            else if (cornerIdx < 1.5) corner = vec2(1.0, -1.0);  // Bottom-right
            else if (cornerIdx < 2.5) corner = vec2(1.0, 1.0);   // Top-right
            else corner = vec2(-1.0, 1.0);                       // Top-left

            // Extract camera right and up vectors from view matrix
            vec3 cameraRight = vec3(uViewMatrix[0][0], uViewMatrix[1][0], uViewMatrix[2][0]);
            vec3 cameraUp = vec3(uViewMatrix[0][1], uViewMatrix[1][1], uViewMatrix[2][1]);

            // Create billboard vertex
            vec3 billboardPos = aPosition +
                                (cameraRight * corner.x + cameraUp * corner.y) * aScale;

            gl_Position = uMVPMatrix * vec4(billboardPos, 1.0);

            vColor = aColor;
            vOpacity = aOpacity;
            vTexCoord = corner * 0.5 + 0.5; // 0-1 range
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
        colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        scaleHandle = GLES20.glGetAttribLocation(program, "aScale")
        opacityHandle = GLES20.glGetAttribLocation(program, "aOpacity")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        viewMatrixHandle = GLES20.glGetUniformLocation(program, "uViewMatrix")

        Log.d(TAG, "Gaussian Splat shader created successfully")
    }

    /**
     * Load Gaussians from PLY file
     * Simplified: only reads positions, colors, scale, opacity
     */
    fun loadPLY(file: File) {
        Log.d(TAG, "Loading Gaussians from: ${file.absolutePath}")

        // Parse PLY header to get vertex count
        val lines = file.readLines()
        var vertexCount = 0
        var headerEnd = 0

        for ((index, line) in lines.withIndex()) {
            if (line.startsWith("element vertex")) {
                vertexCount = line.split(" ")[2].toInt()
            }
            if (line == "end_header") {
                headerEnd = index
                break
            }
        }

        Log.d(TAG, "PLY contains $vertexCount Gaussians")

        // For now, parse as ASCII (binary parsing would be faster)
        // Skip header, read vertex data
        val positions = mutableListOf<Float>()
        val colors = mutableListOf<Float>()
        val scales = mutableListOf<Float>()
        val opacities = mutableListOf<Float>()

        // Simple ASCII parsing for PoC
        // In production, should parse binary PLY properly
        for (i in (headerEnd + 1) until minOf(headerEnd + 1 + vertexCount, lines.size)) {
            val parts = lines[i].split(" ")
            if (parts.size >= 14) {
                // Position
                positions.add(parts[0].toFloat())
                positions.add(parts[1].toFloat())
                positions.add(parts[2].toFloat())

                // Color
                colors.add(parts[6].toFloat() / 255f)
                colors.add(parts[7].toFloat() / 255f)
                colors.add(parts[8].toFloat() / 255f)

                // Scale (average of 3 components)
                val scale = (parts[9].toFloat() + parts[10].toFloat() + parts[11].toFloat()) / 3f
                scales.add(scale)

                // Opacity
                opacities.add(parts[13].toFloat())
            }
        }

        gaussianCount = positions.size / 3

        // Create buffers
        positionBuffer = ByteBuffer.allocateDirect(positions.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(positions.toFloatArray())
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

        Log.d(TAG, "Loaded $gaussianCount Gaussians into GPU buffers")
    }

    /**
     * Draw Gaussian Splats
     */
    fun draw(mvpMatrix: FloatArray, viewMatrix: FloatArray) {
        if (gaussianCount == 0) return

        GLES20.glUseProgram(program)

        // Enable blending for transparency
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Disable depth writes (but keep depth test for sorting)
        GLES20.glDepthMask(false)

        // Enable attributes
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glEnableVertexAttribArray(scaleHandle)
        GLES20.glEnableVertexAttribArray(opacityHandle)

        // Bind buffers (per-Gaussian data)
        positionBuffer?.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, positionBuffer)

        colorBuffer?.position(0)
        GLES20.glVertexAttribPointer(colorHandle, 3, GLES20.GL_FLOAT, false, 0, colorBuffer)

        scaleBuffer?.position(0)
        GLES20.glVertexAttribPointer(scaleHandle, 1, GLES20.GL_FLOAT, false, 0, scaleBuffer)

        opacityBuffer?.position(0)
        GLES20.glVertexAttribPointer(opacityHandle, 1, GLES20.GL_FLOAT, false, 0, opacityBuffer)

        // Set uniforms
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(viewMatrixHandle, 1, false, viewMatrix, 0)

        // Draw billboards (4 vertices per Gaussian)
        // Using instancing would be better but requires OpenGL ES 3.0+
        // For now, generate 4 vertices per Gaussian in vertex shader
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, gaussianCount * 4)

        // Restore state
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glDisableVertexAttribArray(positionHandle)
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

    companion object {
        private const val TAG = "GaussianSplatRenderer"
    }
}
