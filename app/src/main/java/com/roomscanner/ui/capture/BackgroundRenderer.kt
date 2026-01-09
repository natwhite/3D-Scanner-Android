package com.roomscanner.ui.capture

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders the ARCore camera background
 * Simplified version of ARCore's BackgroundRenderer
 */
class BackgroundRenderer {
    private var quadProgram = 0
    private var quadPositionAttrib = 0
    private var quadTexCoordAttrib = 0
    private var quadSplitterUniform = 0

    private lateinit var quadVertices: FloatBuffer
    private lateinit var quadTexCoord: FloatBuffer
    private lateinit var quadTexCoordTransformed: FloatBuffer

    private var cameraTextureId = -1

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
               gl_Position = a_Position;
               v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """

        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f,  // Bottom left
            1.0f, -1.0f,   // Bottom right
            -1.0f, 1.0f,   // Top left
            1.0f, 1.0f     // Top right
        )

        private val QUAD_TEXCOORDS = floatArrayOf(
            0.0f, 1.0f,  // Bottom left
            1.0f, 1.0f,  // Bottom right
            0.0f, 0.0f,  // Top left
            1.0f, 0.0f   // Top right
        )
    }

    fun createOnGlThread(): Int {
        // Create shader program
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        quadProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(quadProgram, vertexShader)
        GLES20.glAttachShader(quadProgram, fragmentShader)
        GLES20.glLinkProgram(quadProgram)
        GLES20.glUseProgram(quadProgram)

        quadPositionAttrib = GLES20.glGetAttribLocation(quadProgram, "a_Position")
        quadTexCoordAttrib = GLES20.glGetAttribLocation(quadProgram, "a_TexCoord")
        quadSplitterUniform = GLES20.glGetUniformLocation(quadProgram, "sTexture")

        // Create buffers
        quadVertices = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(QUAD_COORDS)
        quadVertices.position(0)

        quadTexCoord = ByteBuffer.allocateDirect(QUAD_TEXCOORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(QUAD_TEXCOORDS)
        quadTexCoord.position(0)

        quadTexCoordTransformed = ByteBuffer.allocateDirect(QUAD_TEXCOORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        // Create camera texture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )

        return cameraTextureId
    }

    fun draw(frame: Frame) {
        // Update texture coordinates from ARCore
        frame.transformCoordinates2d(
            com.google.ar.core.Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            quadVertices,
            com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED,
            quadTexCoordTransformed
        )

        // Draw the background
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(quadProgram)

        // Bind texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(quadSplitterUniform, 0)

        // Set attributes
        GLES20.glVertexAttribPointer(
            quadPositionAttrib,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            quadVertices
        )
        GLES20.glVertexAttribPointer(
            quadTexCoordAttrib,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            quadTexCoordTransformed
        )

        GLES20.glEnableVertexAttribArray(quadPositionAttrib)
        GLES20.glEnableVertexAttribArray(quadTexCoordAttrib)

        // Draw quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Restore state
        GLES20.glDisableVertexAttribArray(quadPositionAttrib)
        GLES20.glDisableVertexAttribArray(quadTexCoordAttrib)

        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}
