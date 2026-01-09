package com.roomscanner.viewer

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Composable 3D mesh viewer with orbit camera controls
 */
@Composable
fun Mesh3DViewer(
    meshFile: File,
    modifier: Modifier = Modifier,
    showWireframe: Boolean = false,
    showTexture: Boolean = true,
    onResetCamera: () -> Unit = {}
) {
    var resetTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        // Allow external reset trigger
        onResetCamera.invoke()
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            Mesh3DView(context, meshFile).apply {
                this.showWireframe = showWireframe
                this.showTexture = showTexture
            }
        },
        update = { view ->
            view.showWireframe = showWireframe
            view.showTexture = showTexture
            if (resetTrigger > 0) {
                view.resetCamera()
            }
        }
    )
}

/**
 * GLSurfaceView for rendering 3D mesh
 */
private class Mesh3DView(
    context: Context,
    private val meshFile: File
) : GLSurfaceView(context) {

    private val renderer: Mesh3DRenderer
    private val gestureDetector: GestureDetector
    private val scaleGestureDetector: ScaleGestureDetector

    var showWireframe: Boolean
        get() = renderer.meshRenderer.showWireframe
        set(value) {
            renderer.meshRenderer.showWireframe = value
            requestRender()
        }

    var showTexture: Boolean
        get() = renderer.meshRenderer.showTexture
        set(value) {
            renderer.meshRenderer.showTexture = value
            requestRender()
        }

    init {
        setEGLContextClientVersion(2)

        renderer = Mesh3DRenderer(meshFile)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY

        // Gesture detection for orbit
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e2.pointerCount == 1) {
                    // Single finger: orbit
                    renderer.orbit(-distanceX * 0.2f, distanceY * 0.2f)
                    requestRender()
                    return true
                } else if (e2.pointerCount == 2) {
                    // Two fingers: pan
                    renderer.pan(-distanceX, distanceY)
                    requestRender()
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetCamera()
                return true
            }
        })

        // Scale detection for pinch zoom
        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                renderer.zoom(1f / detector.scaleFactor)
                requestRender()
                return true
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = scaleGestureDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return handled || super.onTouchEvent(event)
    }

    fun resetCamera() {
        renderer.resetCamera()
        requestRender()
    }
}

/**
 * OpenGL renderer for 3D data (mesh or Gaussian splats)
 */
private class Mesh3DRenderer(
    private val meshFile: File
) : GLSurfaceView.Renderer {

    val meshRenderer = MeshRenderer()
    private val gaussianSplatRenderer = GaussianSplatRenderer()
    private val objLoader = OBJLoader()

    private var mesh: OBJLoader.Mesh? = null
    private var camera: OrbitCamera? = null
    private var isGaussianSplat = false

    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    @Volatile
    private var surfaceWidth = 0
    @Volatile
    private var surfaceHeight = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.95f, 0.95f, 0.95f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LESS)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)

        try {
            // Detect file type
            isGaussianSplat = meshFile.extension.lowercase() == "ply"

            if (isGaussianSplat) {
                // Load Gaussian Splats
                Log.d(TAG, "Loading Gaussian Splats from ${meshFile.absolutePath}")
                gaussianSplatRenderer.createOnGlThread()
                gaussianSplatRenderer.loadPLY(meshFile)

                // Initialize camera with default bounds for splats
                val bounds = OBJLoader.BoundingBox(
                    minX = -2f, maxX = 2f,
                    minY = -2f, maxY = 2f,
                    minZ = -2f, maxZ = 2f
                )
                camera = OrbitCamera(bounds)
                Log.d(TAG, "Gaussian Splats loaded successfully")
            } else {
                // Load traditional mesh
                Log.d(TAG, "Loading mesh from ${meshFile.absolutePath}")
                mesh = objLoader.loadOBJ(meshFile)

                // Initialize renderer
                meshRenderer.createOnGlThread()
                meshRenderer.loadMesh(mesh!!)

                // Initialize camera
                camera = OrbitCamera(mesh!!.bounds)
                Log.d(TAG, "Mesh loaded successfully")
            }

            // Set up model matrix (identity - data is already in world space)
            Matrix.setIdentityM(modelMatrix, 0)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load 3D data", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)

        camera?.updateProjection(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        camera?.let { cam ->
            val vpMatrix = cam.getViewProjectionMatrix()
            val cameraPos = cam.getCameraPosition()

            // Compute MVP matrix
            val mvpMatrix = FloatArray(16)
            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

            // Extract view matrix for Gaussian Splatting
            Matrix.setLookAtM(
                viewMatrix, 0,
                cameraPos[0], cameraPos[1], cameraPos[2],
                cam.target[0], cam.target[1], cam.target[2],
                0f, 1f, 0f
            )

            // Draw based on data type
            if (isGaussianSplat) {
                gaussianSplatRenderer.draw(mvpMatrix, viewMatrix)
            } else {
                meshRenderer.draw(mvpMatrix, modelMatrix, cameraPos)
            }
        }
    }

    fun orbit(deltaAzimuth: Float, deltaElevation: Float) {
        camera?.orbit(deltaAzimuth, deltaElevation)
    }

    fun pan(deltaX: Float, deltaY: Float) {
        camera?.pan(deltaX, deltaY)
    }

    fun zoom(factor: Float) {
        camera?.zoom(factor)
    }

    fun resetCamera() {
        camera?.reset()
    }

    companion object {
        private const val TAG = "Mesh3DRenderer"
    }
}
