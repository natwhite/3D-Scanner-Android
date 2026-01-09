package com.roomscanner.reconstruction

/**
 * JNI bridge to native reconstruction code
 */
object ReconstructionNative {

    init {
        System.loadLibrary("roomscanner")
    }

    /**
     * Get native library version info
     */
    external fun getVersionInfo(): String

    /**
     * Extract ORB features from an image
     * @param imagePath Path to the image file
     * @param maxFeatures Maximum number of features to extract
     * @return Number of features extracted
     */
    external fun extractFeatures(imagePath: String, maxFeatures: Int): Int

    /**
     * Match features between two images
     * @param imageId1 First image ID
     * @param imageId2 Second image ID
     * @return Number of matches found
     */
    external fun matchFeatures(imageId1: Int, imageId2: Int): Int

    /**
     * Run bundle adjustment on a scan
     * @param scanDirectory Directory containing scan data
     * @return true if successful
     */
    external fun runBundleAdjustment(scanDirectory: String): Boolean

    /**
     * Generate mesh from point cloud
     * @param pointCloudPath Path to point cloud file
     * @param outputMeshPath Path for output mesh file
     * @return true if successful
     */
    external fun generateMesh(pointCloudPath: String, outputMeshPath: String): Boolean
}
