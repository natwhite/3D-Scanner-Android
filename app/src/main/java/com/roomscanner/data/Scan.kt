package com.roomscanner.data

import java.io.File
import java.util.Date
import java.util.UUID

/**
 * Represents a single room scan session
 */
data class Scan(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Room Scan",
    val timestamp: Date = Date(),
    val keyframeCount: Int = 0,
    val status: ScanStatus = ScanStatus.CAPTURING,
    val scanDirectory: File,
    val thumbnailPath: String? = null,
    val meshPath: String? = null,
    val processingProgress: Float = 0f,
    val errorMessage: String? = null
)

enum class ScanStatus {
    CAPTURING,      // Currently capturing keyframes
    CAPTURED,       // Capture complete, ready for processing
    PROCESSING,     // Reconstruction in progress
    COMPLETED,      // Successfully processed
    FAILED,         // Processing failed
    CANCELLED       // User cancelled
}

/**
 * Represents a single keyframe in a scan
 */
data class Keyframe(
    val id: Int,
    val timestamp: Long,
    val imagePath: String,
    val depthPath: String? = null,
    val pose: Pose
) {
    data class Pose(
        val tx: Float,
        val ty: Float,
        val tz: Float,
        val qx: Float,
        val qy: Float,
        val qz: Float,
        val qw: Float
    ) {
        fun toFloatArray(): FloatArray {
            return floatArrayOf(tx, ty, tz, qx, qy, qz, qw)
        }

        companion object {
            fun fromFloatArray(array: FloatArray): Pose {
                require(array.size == 7) { "Pose array must have 7 elements" }
                return Pose(
                    tx = array[0],
                    ty = array[1],
                    tz = array[2],
                    qx = array[3],
                    qy = array[4],
                    qz = array[5],
                    qw = array[6]
                )
            }

            fun fromARCorePose(arPose: com.google.ar.core.Pose): Pose {
                return Pose(
                    tx = arPose.tx(),
                    ty = arPose.ty(),
                    tz = arPose.tz(),
                    qx = arPose.rotationQuaternion[0],
                    qy = arPose.rotationQuaternion[1],
                    qz = arPose.rotationQuaternion[2],
                    qw = arPose.rotationQuaternion[3]
                )
            }
        }
    }
}

/**
 * Processing stage information
 */
data class ProcessingStage(
    val name: String,
    val progress: Float,
    val isComplete: Boolean = false,
    val message: String = ""
)

/**
 * Reconstruction result
 */
data class ReconstructionResult(
    val success: Boolean,
    val meshFile: File? = null,
    val textureFile: File? = null,
    val pointCount: Int = 0,
    val triangleCount: Int = 0,
    val processingTimeMs: Long = 0,
    val errorMessage: String? = null
)
