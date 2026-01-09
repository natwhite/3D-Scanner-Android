package com.roomscanner.viewer

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * OpenGL ES 2.0 renderer for 3D meshes with lighting
 */
class MeshRenderer {

    private var shaderProgram = 0
    private var wireframeProgram = 0

    // Attribute locations
    private var positionHandle = 0
    private var colorHandle = 0
    private var normalHandle = 0

    // Uniform locations
    private var mvpMatrixHandle = 0
    private var modelMatrixHandle = 0
    private var lightPosHandle = 0
    private var viewPosHandle = 0

    // Wireframe handles
    private var wireframePositionHandle = 0
    private var wireframeColorHandle = 0
    private var wireframeMVPHandle = 0

    // Buffers
    private var vertexBuffer: FloatBuffer? = null
    private var colorBuffer: FloatBuffer? = null
    private var normalBuffer: FloatBuffer? = null
    private var indexBuffer: IntBuffer? = null
    private var lineIndexBuffer: IntBuffer? = null

    private var indexCount = 0
    private var lineIndexCount = 0

    var showWireframe = false
    var showTexture = true

    // Vertex shader with Phong lighting
    private val vertexShaderCode = """
        attribute vec3 aPosition;
        attribute vec3 aColor;
        attribute vec3 aNormal;

        uniform mat4 uMVPMatrix;
        uniform mat4 uModelMatrix;
        uniform vec3 uLightPos;
        uniform vec3 uViewPos;

        varying vec3 vColor;
        varying vec3 vNormal;
        varying vec3 vFragPos;

        void main() {
            gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            vFragPos = vec3(uModelMatrix * vec4(aPosition, 1.0));
            vNormal = mat3(uModelMatrix) * aNormal;
            vColor = aColor;
        }
    """.trimIndent()

    // Fragment shader with Phong lighting
    private val fragmentShaderCode = """
        precision mediump float;

        varying vec3 vColor;
        varying vec3 vNormal;
        varying vec3 vFragPos;

        uniform vec3 uLightPos;
        uniform vec3 uViewPos;

        void main() {
            // Ambient
            float ambientStrength = 0.3;
            vec3 ambient = ambientStrength * vColor;

            // Diffuse
            vec3 norm = normalize(vNormal);
            vec3 lightDir = normalize(uLightPos - vFragPos);
            float diff = max(dot(norm, lightDir), 0.0);
            vec3 diffuse = diff * vColor;

            // Specular
            float specularStrength = 0.5;
            vec3 viewDir = normalize(uViewPos - vFragPos);
            vec3 reflectDir = reflect(-lightDir, norm);
            float spec = pow(max(dot(viewDir, reflectDir), 0.0), 32.0);
            vec3 specular = specularStrength * spec * vec3(1.0, 1.0, 1.0);

            vec3 result = ambient + diffuse + specular;
            gl_FragColor = vec4(result, 1.0);
        }
    """.trimIndent()

    // Wireframe shaders (simple, no lighting)
    private val wireframeVertexShaderCode = """
        attribute vec3 aPosition;
        uniform mat4 uMVPMatrix;

        void main() {
            gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
        }
    """.trimIndent()

    private val wireframeFragmentShaderCode = """
        precision mediump float;
        uniform vec4 uColor;

        void main() {
            gl_FragColor = uColor;
        }
    """.trimIndent()

    /**
     * Initialize OpenGL resources
     */
    fun createOnGlThread() {
        // Create main shader program
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        shaderProgram = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        // Get attribute locations
        positionHandle = GLES20.glGetAttribLocation(shaderProgram, "aPosition")
        colorHandle = GLES20.glGetAttribLocation(shaderProgram, "aColor")
        normalHandle = GLES20.glGetAttribLocation(shaderProgram, "aNormal")

        // Get uniform locations
        mvpMatrixHandle = GLES20.glGetUniformLocation(shaderProgram, "uMVPMatrix")
        modelMatrixHandle = GLES20.glGetUniformLocation(shaderProgram, "uModelMatrix")
        lightPosHandle = GLES20.glGetUniformLocation(shaderProgram, "uLightPos")
        viewPosHandle = GLES20.glGetUniformLocation(shaderProgram, "uViewPos")

        // Create wireframe shader program
        val wireframeVS = loadShader(GLES20.GL_VERTEX_SHADER, wireframeVertexShaderCode)
        val wireframeFS = loadShader(GLES20.GL_FRAGMENT_SHADER, wireframeFragmentShaderCode)

        wireframeProgram = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, wireframeVS)
            GLES20.glAttachShader(it, wireframeFS)
            GLES20.glLinkProgram(it)
        }

        wireframePositionHandle = GLES20.glGetAttribLocation(wireframeProgram, "aPosition")
        wireframeMVPHandle = GLES20.glGetUniformLocation(wireframeProgram, "uMVPMatrix")
        wireframeColorHandle = GLES20.glGetUniformLocation(wireframeProgram, "uColor")

        Log.d(TAG, "Shader programs created successfully")
    }

    /**
     * Load mesh data into GPU buffers
     */
    fun loadMesh(mesh: OBJLoader.Mesh) {
        Log.d(TAG, "Loading mesh with ${mesh.vertices.size / 3} vertices")

        // Create vertex buffer
        vertexBuffer = ByteBuffer.allocateDirect(mesh.vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(mesh.vertices)
            .position(0) as FloatBuffer

        // Create color buffer
        colorBuffer = ByteBuffer.allocateDirect(mesh.colors.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(mesh.colors)
            .position(0) as FloatBuffer

        // Create normal buffer
        normalBuffer = ByteBuffer.allocateDirect(mesh.normals.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(mesh.normals)
            .position(0) as FloatBuffer

        // Create index buffer for triangles
        indexBuffer = ByteBuffer.allocateDirect(mesh.indices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
            .put(mesh.indices)
            .position(0) as IntBuffer

        indexCount = mesh.indices.size

        // Create line indices for wireframe (edges of triangles)
        val lineIndices = mutableListOf<Int>()
        for (i in mesh.indices.indices step 3) {
            val i0 = mesh.indices[i]
            val i1 = mesh.indices[i + 1]
            val i2 = mesh.indices[i + 2]

            // Add three edges
            lineIndices.add(i0)
            lineIndices.add(i1)

            lineIndices.add(i1)
            lineIndices.add(i2)

            lineIndices.add(i2)
            lineIndices.add(i0)
        }

        lineIndexBuffer = ByteBuffer.allocateDirect(lineIndices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
            .put(lineIndices.toIntArray())
            .position(0) as IntBuffer

        lineIndexCount = lineIndices.size

        Log.d(TAG, "Mesh loaded: $indexCount indices, $lineIndexCount line indices")
    }

    /**
     * Draw the mesh
     */
    fun draw(mvpMatrix: FloatArray, modelMatrix: FloatArray, cameraPos: FloatArray) {
        if (vertexBuffer == null) return

        // Light position (above and to the right of camera)
        val lightPos = floatArrayOf(
            cameraPos[0] + 2f,
            cameraPos[1] + 3f,
            cameraPos[2] + 2f
        )

        if (showTexture && !showWireframe) {
            // Draw solid mesh with lighting
            GLES20.glUseProgram(shaderProgram)

            // Enable attributes
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glEnableVertexAttribArray(colorHandle)
            GLES20.glEnableVertexAttribArray(normalHandle)

            // Bind buffers
            vertexBuffer?.position(0)
            GLES20.glVertexAttribPointer(
                positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer
            )

            colorBuffer?.position(0)
            GLES20.glVertexAttribPointer(
                colorHandle, 3, GLES20.GL_FLOAT, false, 0, colorBuffer
            )

            normalBuffer?.position(0)
            GLES20.glVertexAttribPointer(
                normalHandle, 3, GLES20.GL_FLOAT, false, 0, normalBuffer
            )

            // Set uniforms
            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(modelMatrixHandle, 1, false, modelMatrix, 0)
            GLES20.glUniform3fv(lightPosHandle, 1, lightPos, 0)
            GLES20.glUniform3fv(viewPosHandle, 1, cameraPos, 0)

            // Draw triangles
            indexBuffer?.position(0)
            GLES20.glDrawElements(
                GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_INT, indexBuffer
            )

            // Disable attributes
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(colorHandle)
            GLES20.glDisableVertexAttribArray(normalHandle)
        }

        if (showWireframe) {
            // Draw wireframe
            GLES20.glUseProgram(wireframeProgram)

            GLES20.glEnableVertexAttribArray(wireframePositionHandle)

            vertexBuffer?.position(0)
            GLES20.glVertexAttribPointer(
                wireframePositionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer
            )

            GLES20.glUniformMatrix4fv(wireframeMVPHandle, 1, false, mvpMatrix, 0)
            GLES20.glUniform4f(wireframeColorHandle, 0f, 0f, 0f, 1f) // Black wireframe

            GLES20.glLineWidth(1.5f)

            lineIndexBuffer?.position(0)
            GLES20.glDrawElements(
                GLES20.GL_LINES, lineIndexCount, GLES20.GL_UNSIGNED_INT, lineIndexBuffer
            )

            GLES20.glDisableVertexAttribArray(wireframePositionHandle)
        }
    }

    /**
     * Load and compile a shader
     */
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
        private const val TAG = "MeshRenderer"
    }
}
