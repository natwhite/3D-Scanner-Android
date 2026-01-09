package com.roomscanner.viewer

import android.opengl.Matrix
import kotlin.math.cos
import kotlin.math.sin

/**
 * Orbit camera controller for 3D viewing
 * Supports orbit (rotation around target), pan, and zoom
 */
class OrbitCamera(
    private val bounds: OBJLoader.BoundingBox
) {
    // Camera state
    private var distance: Float = bounds.maxSize * 2.5f
    private var azimuth: Float = 45f  // Horizontal angle (degrees)
    private var elevation: Float = 30f // Vertical angle (degrees)

    // Target point (center of bounding box)
    private var targetX: Float = bounds.centerX
    private var targetY: Float = bounds.centerY
    private var targetZ: Float = bounds.centerZ

    // Pan offset
    private var panX: Float = 0f
    private var panY: Float = 0f

    // Zoom limits
    private val minDistance = bounds.maxSize * 0.5f
    private val maxDistance = bounds.maxSize * 10f

    // Matrices
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)

    /**
     * Get camera position in world space
     */
    fun getCameraPosition(): FloatArray {
        val azimuthRad = Math.toRadians(azimuth.toDouble()).toFloat()
        val elevationRad = Math.toRadians(elevation.toDouble()).toFloat()

        val x = targetX + panX + distance * cos(elevationRad) * cos(azimuthRad)
        val y = targetY + panY + distance * sin(elevationRad)
        val z = targetZ + distance * cos(elevationRad) * sin(azimuthRad)

        return floatArrayOf(x, y, z)
    }

    /**
     * Orbit camera (rotate around target)
     */
    fun orbit(deltaAzimuth: Float, deltaElevation: Float) {
        azimuth += deltaAzimuth
        elevation = (elevation + deltaElevation).coerceIn(-89f, 89f)
    }

    /**
     * Pan camera (move target point)
     */
    fun pan(deltaX: Float, deltaY: Float) {
        val azimuthRad = Math.toRadians(azimuth.toDouble()).toFloat()

        // Pan in camera space
        val right = floatArrayOf(
            -sin(azimuthRad),
            0f,
            cos(azimuthRad)
        )

        val up = floatArrayOf(0f, 1f, 0f)

        val panScale = distance * 0.001f

        panX += (right[0] * deltaX + up[0] * deltaY) * panScale
        panY += (right[1] * deltaX + up[1] * deltaY) * panScale
    }

    /**
     * Zoom camera (change distance)
     */
    fun zoom(factor: Float) {
        distance = (distance * factor).coerceIn(minDistance, maxDistance)
    }

    /**
     * Reset camera to default position
     */
    fun reset() {
        distance = bounds.maxSize * 2.5f
        azimuth = 45f
        elevation = 30f
        panX = 0f
        panY = 0f
    }

    /**
     * Update projection matrix
     */
    fun updateProjection(width: Int, height: Int) {
        val ratio = width.toFloat() / height
        val near = bounds.maxSize * 0.1f
        val far = bounds.maxSize * 20f

        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, near, far)
    }

    /**
     * Get view-projection matrix
     */
    fun getViewProjectionMatrix(): FloatArray {
        // Calculate camera position
        val camPos = getCameraPosition()

        // Look at target
        Matrix.setLookAtM(
            viewMatrix, 0,
            camPos[0], camPos[1], camPos[2],  // eye
            targetX + panX, targetY + panY, targetZ,  // center
            0f, 1f, 0f  // up
        )

        // Combine with projection
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        return vpMatrix
    }
}
