package com.roomscanner.capture

import android.util.Log
import com.roomscanner.data.Keyframe
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Determines when to capture keyframes based on camera movement
 */
class KeyframeSelector(
    private val distanceThreshold: Float = 0.15f,  // 15cm (reduced for higher resolution)
    private val rotationThreshold: Float = 8f,     // 8 degrees (reduced for higher resolution)
    private val timeThreshold: Long = 1000L        // 1 second (reduced for higher resolution)
) {

    private var lastKeyframePose: Keyframe.Pose? = null
    private var lastKeyframeTime: Long = 0
    private var frameCount = 0

    /**
     * Check if a new keyframe should be captured
     */
    fun shouldCaptureKeyframe(
        currentPose: Keyframe.Pose,
        currentTime: Long = System.currentTimeMillis()
    ): Boolean {

        val prevPose = lastKeyframePose

        // Always capture first frame
        if (prevPose == null) {
            lastKeyframePose = currentPose
            lastKeyframeTime = currentTime
            frameCount = 0
            Log.d(TAG, "Capturing first keyframe")
            return true
        }

        // Calculate distance moved
        val distance = computeDistance(prevPose, currentPose)

        // Calculate rotation angle
        val angle = computeRotationAngle(prevPose, currentPose)

        // Time since last keyframe
        val timeDelta = currentTime - lastKeyframeTime

        // Capture if moved enough OR rotated enough OR enough time passed
        val shouldCapture = distance > distanceThreshold ||
                           angle > rotationThreshold ||
                           timeDelta > timeThreshold

        if (shouldCapture) {
            lastKeyframePose = currentPose
            lastKeyframeTime = currentTime
            frameCount++

            val reason = when {
                distance > distanceThreshold -> "distance: ${String.format("%.2f", distance)}m"
                angle > rotationThreshold -> "rotation: ${String.format("%.1f", angle)}°"
                else -> "time: ${timeDelta}ms"
            }
            Log.d(TAG, "Keyframe trigger - $reason")
        }

        return shouldCapture
    }

    /**
     * Get number of keyframes captured
     */
    fun getKeyframeCount(): Int = frameCount

    /**
     * Reset the selector
     */
    fun reset() {
        lastKeyframePose = null
        lastKeyframeTime = 0
        frameCount = 0
    }

    /**
     * Compute Euclidean distance between two poses
     */
    private fun computeDistance(pose1: Keyframe.Pose, pose2: Keyframe.Pose): Float {
        val dx = pose2.tx - pose1.tx
        val dy = pose2.ty - pose1.ty
        val dz = pose2.tz - pose1.tz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Compute rotation angle between two poses (in degrees)
     */
    private fun computeRotationAngle(pose1: Keyframe.Pose, pose2: Keyframe.Pose): Float {
        // Quaternion dot product
        val dot = pose1.qx * pose2.qx +
                  pose1.qy * pose2.qy +
                  pose1.qz * pose2.qz +
                  pose1.qw * pose2.qw

        // Clamp to avoid numerical issues
        val clampedDot = dot.coerceIn(-1f, 1f)

        // Angle = 2 * arccos(dot)
        val angleRad = 2 * acos(kotlin.math.abs(clampedDot))

        // Convert to degrees
        return Math.toDegrees(angleRad.toDouble()).toFloat()
    }

    /**
     * Get current movement metrics
     */
    fun getMovementMetrics(currentPose: Keyframe.Pose): MovementMetrics? {
        val prevPose = lastKeyframePose ?: return null

        return MovementMetrics(
            distance = computeDistance(prevPose, currentPose),
            rotation = computeRotationAngle(prevPose, currentPose),
            timeSinceLastKeyframe = System.currentTimeMillis() - lastKeyframeTime
        )
    }

    companion object {
        private const val TAG = "KeyframeSelector"
    }
}

/**
 * Movement metrics for UI feedback
 */
data class MovementMetrics(
    val distance: Float,
    val rotation: Float,
    val timeSinceLastKeyframe: Long
)
