package com.roomscanner.capture

import android.app.Activity
import android.media.Image
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import com.roomscanner.data.Keyframe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages ARCore session and tracking
 */
class ARCoreManager(private val activity: Activity) {

    private var session: Session? = null
    private val _trackingState = MutableStateFlow(TrackingState.PAUSED)
    val trackingState: StateFlow<TrackingState> = _trackingState.asStateFlow()

    private val _hasDepth = MutableStateFlow(false)
    val hasDepth: StateFlow<Boolean> = _hasDepth.asStateFlow()

    /**
     * Initialize ARCore session
     */
    fun initialize(): Result<Unit> {
        return try {
            // Check if ARCore is installed
            when (ArCoreApk.getInstance().requestInstall(activity, true)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    return Result.failure(Exception("ARCore installation requested"))
                }
                ArCoreApk.InstallStatus.INSTALLED -> {
                    // Continue
                }
                null -> {
                    return Result.failure(Exception("ARCore check failed"))
                }
            }

            // Create session
            session = Session(activity).apply {
                configure(
                    Config(this).apply {
                        // Enable depth for better reconstruction
                        depthMode = Config.DepthMode.AUTOMATIC

                        // Find planes for better tracking
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL

                        // Update mode
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE

                        // Enable auto focus
                        focusMode = Config.FocusMode.AUTO
                    }
                )
            }

            _hasDepth.value = session?.isDepthModeSupported(Config.DepthMode.AUTOMATIC) == true

            Result.success(Unit)
        } catch (e: UnavailableArcoreNotInstalledException) {
            Result.failure(Exception("ARCore not installed"))
        } catch (e: UnavailableApkTooOldException) {
            Result.failure(Exception("ARCore APK too old"))
        } catch (e: UnavailableSdkTooOldException) {
            Result.failure(Exception("Android SDK too old"))
        } catch (e: UnavailableDeviceNotCompatibleException) {
            Result.failure(Exception("Device not compatible with ARCore"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Resume ARCore session
     */
    fun resume() {
        session?.resume()
    }

    /**
     * Pause ARCore session
     */
    fun pause() {
        session?.pause()
    }

    /**
     * Destroy ARCore session
     */
    fun destroy() {
        session?.close()
        session = null
    }

    /**
     * Update ARCore and get current frame
     */
    fun update(): ARFrame? {
        val session = session ?: return null

        return try {
            val frame = session.update()
            val camera = frame.camera

            _trackingState.value = camera.trackingState

            if (camera.trackingState != TrackingState.TRACKING) {
                return null
            }

            ARFrame(
                frame = frame,
                camera = camera,
                cameraPose = Keyframe.Pose.fromARCorePose(camera.pose),
                depthImage = frame.acquireDepthImage16Bits(),
                cameraImage = frame.acquireCameraImage()
            )
        } catch (e: NotYetAvailableException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if ARCore is supported
     */
    companion object {
        fun isARCoreSupported(activity: Activity): Boolean {
            val availability = ArCoreApk.getInstance().checkAvailability(activity)
            return availability == ArCoreApk.Availability.SUPPORTED_INSTALLED
        }
    }
}

/**
 * Wrapper for ARCore frame data
 */
data class ARFrame(
    val frame: Frame,
    val camera: Camera,
    val cameraPose: Keyframe.Pose,
    val depthImage: Image?,
    val cameraImage: Image
) {
    fun release() {
        depthImage?.close()
        cameraImage.close()
    }
}

/**
 * Extension to get depth image
 */
private fun Frame.acquireDepthImage16Bits(): Image? {
    return try {
        acquireDepthImage16Bits()
    } catch (e: NotYetAvailableException) {
        null
    } catch (e: Exception) {
        null
    }
}
