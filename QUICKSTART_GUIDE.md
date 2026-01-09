# Indoor Room Scanner - Quick Start Guide

## Overview

This guide provides a quick reference for developers starting implementation of the indoor room scanning PoC.

## What You're Building

A **Proof of Concept Android app** that:
- ✅ Scans indoor rooms to create 3D models
- ✅ Uses ARCore for tracking and depth
- ✅ Produces high-quality textured meshes
- ✅ Works offline on the device
- ✅ Exports to OBJ format

**Timeline:** 8-10 weeks
**Team Size:** 1-2 developers
**Target:** Functional PoC, not production app

## PoC vs Full Implementation

| Feature | PoC (8-10 weeks) | Full (24 weeks) |
|---------|------------------|-----------------|
| **Scanning Modes** | Room only | Room, Object, Outdoor, Face |
| **UI Polish** | Basic functional | Polished, animated |
| **Editing Tools** | None | Crop, fill, smooth, measure |
| **Export Formats** | OBJ only | OBJ, PLY, GLTF, FBX, STL, USDZ |
| **Guidance** | Simple text | AR overlay with coverage map |
| **Processing** | Offline only | Offline + Cloud option |
| **Viewer** | Basic 3D view | Advanced with editing |
| **Quality** | High (same algorithms) | High + optimizations |

## Architecture at a Glance

```
User Scans Room → ARCore Tracks Camera → Keyframes Saved
                                              ↓
                                     Offline Processing
                                              ↓
                                     (Feature Matching)
                                              ↓
                                     (Structure-from-Motion)
                                              ↓
                                     (Dense Reconstruction)
                                              ↓
                                     (Mesh + Texture)
                                              ↓
                                     View in 3D → Export OBJ
```

## Core Technology Choices

### Why These Technologies?

**ARCore (not custom SLAM):**
- ✅ Mature, reliable 6DoF tracking
- ✅ Built-in depth estimation
- ✅ Already metric scale
- ✅ Saves 4+ weeks of SLAM development
- ❌ Requires ARCore-compatible device

**ORB Features (not SIFT):**
- ✅ Fast binary descriptors
- ✅ Works real-time on mobile
- ✅ Patent-free
- ❌ Less accurate than SIFT (but good enough)

**Poisson Reconstruction (not Delaunay):**
- ✅ Watertight meshes
- ✅ Handles noise well
- ✅ Good quality
- ❌ Slightly slower (acceptable for PoC)

**Offline Processing (not real-time):**
- ✅ Higher quality results
- ✅ Simpler implementation
- ✅ Lower memory pressure during capture
- ❌ User waits after scanning

## Week-by-Week Milestones

| Week | Goal | Key Deliverable |
|------|------|-----------------|
| 1 | Camera + ARCore | App captures keyframes with poses |
| 2 | Basic UI | Start/Stop recording, frame counter |
| 3 | Feature Processing | ORB extraction and matching works |
| 4 | Structure-from-Motion | Sparse point cloud generated |
| 5 | Dense Reconstruction | Dense point cloud from depth fusion |
| 6 | Mesh Generation | Watertight mesh created |
| 7 | Texturing & Export | Textured OBJ file exported |
| 8 | Viewing & Polish | Interactive 3D viewer works |
| 9-10 | Testing (Buffer) | Validated on 5 rooms, 5 devices |

## Day 1 Setup Checklist

### Prerequisites
- [ ] Android Studio Hedgehog or newer
- [ ] Android device with ARCore support (Pixel 6+ recommended)
- [ ] USB cable for debugging
- [ ] 10+ GB free disk space

### Project Setup
```bash
# 1. Clone or create project
git clone <repository-url>
cd 3D-Scanner-Android

# 2. Open in Android Studio
# File → Open → Select project folder

# 3. Install NDK and CMake
# Tools → SDK Manager → SDK Tools
# Check: NDK, CMake, LLDB

# 4. Sync Gradle
# File → Sync Project with Gradle Files
```

### Add Dependencies (build.gradle.kts)
```kotlin
// ARCore
implementation("com.google.ar:core:1.40.0")

// CameraX
implementation("androidx.camera:camera-camera2:1.3.0")
implementation("androidx.camera:camera-lifecycle:1.3.0")
implementation("androidx.camera:camera-view:1.3.0")

// Jetpack Compose
implementation(platform("androidx.compose:compose-bom:2023.10.01"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
implementation("androidx.activity:activity-compose:1.8.0")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

// OpenCV (download AAR from opencv.org)
implementation(files("libs/opencv-4.8.0.aar"))
```

### Request Permissions (AndroidManifest.xml)
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />

<uses-feature android:name="android.hardware.camera.ar" android:required="true"/>
<uses-feature android:name="android.hardware.camera.autofocus" />

<application>
    <meta-data
        android:name="com.google.ar.core"
        android:value="required" />
</application>
```

## Critical Code Snippets

### 1. ARCore Session Setup
```kotlin
class ARCoreManager(private val activity: Activity) {
    private var session: Session? = null

    fun initialize() {
        session = Session(activity).apply {
            configure(
                Config(this).apply {
                    // Enable depth for better reconstruction
                    depthMode = Config.DepthMode.AUTOMATIC
                    // Optimize for indoor
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                }
            )
        }
    }

    fun update(displayRotation: Int): Pair<Camera, Frame>? {
        val frame = session?.update() ?: return null
        val camera = frame.camera

        if (camera.trackingState != TrackingState.TRACKING) {
            return null // Bad tracking
        }

        return camera to frame
    }

    fun getCameraPose(camera: Camera): Pose {
        return camera.pose
    }
}
```

### 2. Keyframe Selection
```kotlin
class KeyframeSelector {
    private var lastKeyframePose: Pose? = null
    private var lastKeyframeTime: Long = 0

    fun shouldCaptureKeyframe(
        currentPose: Pose,
        currentTime: Long
    ): Boolean {
        val prevPose = lastKeyframePose ?: run {
            lastKeyframePose = currentPose
            lastKeyframeTime = currentTime
            return true // Always capture first frame
        }

        // Calculate distance moved
        val dx = currentPose.tx() - prevPose.tx()
        val dy = currentPose.ty() - prevPose.ty()
        val dz = currentPose.tz() - prevPose.tz()
        val distance = sqrt(dx*dx + dy*dy + dz*dz)

        // Calculate rotation angle
        val rotation = currentPose.rotationQuaternion
        val prevRotation = prevPose.rotationQuaternion
        val angle = computeRotationAngle(rotation, prevRotation)

        // Capture if moved 40cm OR rotated 15 degrees OR 2 seconds passed
        val shouldCapture = distance > 0.40f ||
                           angle > 15f ||
                           (currentTime - lastKeyframeTime) > 2000

        if (shouldCapture) {
            lastKeyframePose = currentPose
            lastKeyframeTime = currentTime
        }

        return shouldCapture
    }
}
```

### 3. Save Keyframe Data
```kotlin
data class Keyframe(
    val id: Int,
    val timestamp: Long,
    val imagePath: String,
    val depthPath: String?,
    val pose: FloatArray // 7 values: tx, ty, tz, qx, qy, qz, qw
)

class KeyframeStorage(private val scanDir: File) {

    suspend fun saveKeyframe(
        frameId: Int,
        image: Image,
        depth: Image?,
        pose: Pose
    ): Keyframe = withContext(Dispatchers.IO) {

        // Save RGB image
        val imagePath = File(scanDir, "frame_${frameId}.jpg")
        saveImageAsJPEG(image, imagePath)

        // Save depth (if available)
        val depthPath = depth?.let {
            val path = File(scanDir, "depth_${frameId}.png")
            saveDepthAsPNG(it, path)
            path.absolutePath
        }

        // Create keyframe record
        Keyframe(
            id = frameId,
            timestamp = System.currentTimeMillis(),
            imagePath = imagePath.absolutePath,
            depthPath = depthPath,
            pose = floatArrayOf(
                pose.tx(), pose.ty(), pose.tz(),
                pose.rotationQuaternion[0],
                pose.rotationQuaternion[1],
                pose.rotationQuaternion[2],
                pose.rotationQuaternion[3]
            )
        )
    }
}
```

### 4. JNI Bridge for Native Processing
```kotlin
// Kotlin side
object ReconstructionNative {
    external fun extractORBFeatures(
        imagePath: String,
        maxFeatures: Int
    ): FloatArray // Returns [x1,y1,x2,y2,...] and descriptors

    external fun matchFeatures(
        features1: FloatArray,
        features2: FloatArray
    ): IntArray // Returns pairs [idx1a, idx2a, idx1b, idx2b, ...]

    external fun bundleAdjustment(
        cameras: FloatArray,  // All camera poses
        points: FloatArray,   // All 3D points
        observations: IntArray // Which point seen in which camera
    ): FloatArray // Optimized cameras and points

    init {
        System.loadLibrary("reconstruction")
    }
}
```

```cpp
// C++ side (reconstruction.cpp)
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_roomscanner_ReconstructionNative_extractORBFeatures(
    JNIEnv* env,
    jobject,
    jstring imagePath,
    jint maxFeatures
) {
    // Load image
    const char* path = env->GetStringUTFChars(imagePath, nullptr);
    cv::Mat image = cv::imread(path, cv::IMREAD_GRAYSCALE);
    env->ReleaseStringUTFChars(imagePath, path);

    // Extract ORB features
    cv::Ptr<cv::ORB> orb = cv::ORB::create(maxFeatures);
    std::vector<cv::KeyPoint> keypoints;
    cv::Mat descriptors;
    orb->detectAndCompute(image, cv::noArray(), keypoints, descriptors);

    // Convert to float array and return
    // ... implementation details ...
}
```

## Common Pitfalls & Solutions

### Problem 1: ARCore Tracking Fails
**Symptoms:** Camera pose jumps, tracking state is PAUSED
**Solutions:**
- Move slower (< 1 m/s)
- Ensure good lighting
- Avoid textureless walls
- Show user tracking quality indicator
- Reject keyframes with poor tracking

### Problem 2: Out of Memory During Processing
**Symptoms:** App crashes with OOM when processing
**Solutions:**
- Downscale images to 1080p for feature extraction
- Process in batches (e.g., 20 images at a time)
- Use streaming reconstruction
- Monitor heap with `Debug.getNativeHeapAllocatedSize()`

### Problem 3: Slow Feature Matching
**Symptoms:** Matching takes > 5 minutes
**Solutions:**
- Only match nearby frames (use pose distance)
- Use vocabulary tree for candidate selection
- Limit matches per image (e.g., top 20 neighbors)
- Run on background thread

### Problem 4: Poor Mesh Quality
**Symptoms:** Holes, artifacts, missing surfaces
**Solutions:**
- Ensure good depth coverage during scan
- Check depth confidence values
- Increase point cloud density
- Tune Poisson octree depth (try 9, 10, 11)
- Filter statistical outliers before meshing

### Problem 5: Build Errors with Native Libraries
**Symptoms:** CMake errors, linking errors
**Solutions:**
- Ensure NDK version matches (r26+)
- Check CMakeLists.txt paths
- Verify library ABIs match (arm64-v8a)
- Clean and rebuild: Build → Clean Project

## Testing Strategy

### Unit Tests
```kotlin
// Test keyframe selection logic
@Test
fun `should capture keyframe after 40cm movement`() {
    val selector = KeyframeSelector()
    val pose1 = createPose(0f, 0f, 0f)
    val pose2 = createPose(0.5f, 0f, 0f) // 50cm moved

    selector.shouldCaptureKeyframe(pose1, 1000)
    val result = selector.shouldCaptureKeyframe(pose2, 1100)

    assertTrue(result)
}
```

### Integration Tests
```kotlin
@Test
fun `full reconstruction pipeline processes successfully`() = runBlocking {
    // Given: Sample scan data
    val scanDir = createTestScan()

    // When: Run reconstruction
    val pipeline = ReconstructionPipeline()
    val result = pipeline.process(scanDir)

    // Then: Check outputs
    assertTrue(result.isSuccess)
    assertTrue(result.meshFile.exists())
    assertTrue(result.meshFile.length() > 0)
}
```

### Manual Tests
1. **Small Office (3×4m):** Should work perfectly
2. **White Room:** Test tracking challenges
3. **Large Room (8×10m):** Test scalability
4. **Bright Room:** Test with windows
5. **Textured Room:** Test best-case

## Performance Monitoring

### Add Logging
```kotlin
class PerformanceMonitor {
    private val times = mutableMapOf<String, Long>()

    fun start(stage: String) {
        times[stage] = System.currentTimeMillis()
    }

    fun end(stage: String) {
        val duration = System.currentTimeMillis() - times[stage]!!
        Log.i("Performance", "$stage took ${duration}ms")

        // Save to file for analysis
        File(metricsDir, "performance.csv").appendText(
            "$stage,$duration\n"
        )
    }
}

// Usage
monitor.start("Feature Extraction")
extractFeatures(image)
monitor.end("Feature Extraction")
```

### Memory Monitoring
```kotlin
val runtime = Runtime.getRuntime()
val usedMemory = runtime.totalMemory() - runtime.freeMemory()
val maxMemory = runtime.maxMemory()
val memoryPercent = (usedMemory * 100) / maxMemory

Log.i("Memory", "Used: ${usedMemory/1024/1024}MB / ${maxMemory/1024/1024}MB ($memoryPercent%)")
```

## Debugging Tips

### Visualize Feature Matches
```kotlin
// Save debug image with matches drawn
fun visualizeMatches(img1: Mat, img2: Mat, matches: List<DMatch>) {
    val output = Mat()
    Features2d.drawMatches(
        img1, keypoints1,
        img2, keypoints2,
        matches, output
    )
    Imgcodecs.imwrite("/sdcard/matches.jpg", output)
}
```

### Export Sparse Point Cloud
```kotlin
// Export to PLY for inspection in CloudCompare
fun exportSparsePLY(points: List<Point3D>, file: File) {
    file.bufferedWriter().use { writer ->
        writer.write("ply\n")
        writer.write("format ascii 1.0\n")
        writer.write("element vertex ${points.size}\n")
        writer.write("property float x\n")
        writer.write("property float y\n")
        writer.write("property float z\n")
        writer.write("end_header\n")

        points.forEach { p ->
            writer.write("${p.x} ${p.y} ${p.z}\n")
        }
    }
}
```

### Check Mesh Quality
```kotlin
// Validate mesh is watertight
fun checkMeshQuality(mesh: TriangleMesh): MeshQuality {
    return MeshQuality(
        numVertices = mesh.vertices.size,
        numTriangles = mesh.triangles.size,
        isWatertight = mesh.isWatertight(),
        hasDegenerateTriangles = mesh.findDegenerateTriangles().isNotEmpty(),
        boundingBox = mesh.getBoundingBox()
    )
}
```

## Resources

### Documentation
- [ARCore Developer Guide](https://developers.google.com/ar/develop)
- [OpenCV Android Tutorial](https://docs.opencv.org/4.x/da/df6/tutorial_table_of_content_android.html)
- [Ceres Solver Documentation](http://ceres-solver.org/tutorial.html)
- [Open3D Documentation](http://www.open3d.org/docs/latest/)

### Sample Code
- [ARCore Examples](https://github.com/google-ar/arcore-android-sdk)
- [OpenCV Android Samples](https://github.com/opencv/opencv/tree/4.x/samples/android)

### Tools
- **CloudCompare:** View and analyze point clouds
- **MeshLab:** Inspect and repair meshes
- **Blender:** Final validation and editing
- **Android Profiler:** Performance monitoring

### Communities
- [ARCore Issue Tracker](https://github.com/google-ar/arcore-android-sdk/issues)
- [OpenCV Forum](https://forum.opencv.org/)
- [r/computervision](https://reddit.com/r/computervision)
- [Stack Overflow](https://stackoverflow.com/questions/tagged/arcore)

## Next Steps

1. **Day 1-2:** Set up project and dependencies
2. **Day 3:** Get basic ARCore tracking working
3. **Day 4-5:** Implement keyframe capture
4. **Week 2:** Build basic UI
5. **Week 3+:** Follow detailed roadmap in POC_IMPLEMENTATION_PLAN.md

## Getting Help

**Stuck on ARCore?** Check the [ARCore Issue Tracker](https://github.com/google-ar/arcore-android-sdk/issues)
**Native build issues?** Review [Android NDK Guide](https://developer.android.com/ndk/guides)
**Algorithm questions?** Refer to RESEARCH_FINDINGS.md
**Architecture questions?** See IMPLEMENTATION_PROPOSAL.md

## Quick Reference: Key Files

- `POC_IMPLEMENTATION_PLAN.md` - Full detailed plan (you are here summarized)
- `RESEARCH_FINDINGS.md` - Background research on 3D reconstruction
- `IMPLEMENTATION_PROPOSAL.md` - Full product proposal (24 weeks)
- `README.md` - Project overview

---

**Document Version:** 1.0
**Last Updated:** 2026-01-09
**Status:** Ready to Start Development

**Let's build this! 🚀**
