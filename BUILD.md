# Build Instructions - Room Scanner PoC

## Prerequisites

### Required Software
- **Android Studio**: Hedgehog (2023.1.1) or newer
- **JDK**: 17 or newer
- **Android SDK**: API 34 (Android 14)
- **Android NDK**: r26 or newer
- **CMake**: 3.22.1 or newer

### Required Hardware
- **Development**: Any modern computer with 16GB+ RAM
- **Testing Device**: ARCore-compatible Android device
  - Android 10+ (API 29)
  - ARCore support (check: https://developers.google.com/ar/devices)
  - Recommended: Google Pixel 6+, Samsung Galaxy S21+

## Setup Instructions

### 1. Clone the Repository

```bash
git clone <repository-url>
cd 3D-Scanner-Android
```

### 2. Open in Android Studio

1. Launch Android Studio
2. Select "Open an Existing Project"
3. Navigate to the `3D-Scanner-Android` directory
4. Click "OK"

### 3. Install SDK Components

Android Studio should prompt you to install missing components. If not:

1. Go to **Tools → SDK Manager**
2. Under **SDK Platforms**, ensure Android 14.0 (API 34) is installed
3. Under **SDK Tools**, install:
   - Android SDK Build-Tools 34
   - NDK (Side by side) - version 26.1.10909125
   - CMake - version 3.22.1
   - Android SDK Platform-Tools
4. Click "Apply" and "OK"

### 4. Sync Gradle

1. Android Studio should automatically sync Gradle
2. If not, click **File → Sync Project with Gradle Files**
3. Wait for the sync to complete (may take 5-10 minutes first time)

### 5. Configure Device

#### Physical Device (Recommended)

1. Enable Developer Options on your Android device:
   - Go to **Settings → About Phone**
   - Tap **Build Number** 7 times
   - Developer options will appear

2. Enable USB Debugging:
   - Go to **Settings → Developer Options**
   - Enable **USB Debugging**

3. Connect device via USB
4. Accept USB debugging prompt on device

#### Emulator (Limited - No ARCore)

Note: ARCore does not work on emulators, so you can only test non-AR features.

1. Go to **Tools → Device Manager**
2. Click **Create Device**
3. Select a device with Play Store support
4. Select system image with API 29+ and Google APIs
5. Finish setup

### 6. Install ARCore

On your test device:

1. Open Google Play Store
2. Search for "Google Play Services for AR"
3. Install/Update ARCore
4. Verify installation: Settings → Apps → Google Play Services for AR

## Building the App

### Build Variants

- **Debug**: Development build with logging
- **Release**: Optimized build (requires signing)

### Build via Android Studio

1. Select your device from the device dropdown
2. Click the **Run** button (green play icon) or press **Shift+F10**
3. App will build and install on the device
4. Grant camera permissions when prompted

### Build via Command Line

```bash
# Debug build
./gradlew assembleDebug

# Install on device
./gradlew installDebug

# Build and install
./gradlew installDebug

# Clean build
./gradlew clean assembleDebug
```

APK location: `app/build/outputs/apk/debug/app-debug.apk`

### Build Release APK

```bash
./gradlew assembleRelease
```

Note: Release builds require signing configuration (not included in PoC).

## Project Structure

```
3D-Scanner-Android/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/roomscanner/
│   │   │   │   ├── data/              # Data models and repository
│   │   │   │   ├── capture/           # ARCore capture logic
│   │   │   │   ├── reconstruction/    # Processing pipeline
│   │   │   │   ├── ui/                # Compose UI screens
│   │   │   │   └── viewer/            # 3D model viewer
│   │   │   ├── cpp/                   # Native C++ code
│   │   │   │   ├── CMakeLists.txt
│   │   │   │   ├── native-lib.cpp
│   │   │   │   └── reconstruction_bridge.cpp
│   │   │   ├── res/                   # Android resources
│   │   │   └── AndroidManifest.xml
│   │   └── test/                      # Unit tests
│   └── build.gradle.kts
├── gradle/                             # Gradle wrapper
├── build.gradle.kts                    # Root build config
├── settings.gradle.kts
└── BUILD.md                            # This file
```

## Running the App

### First Launch

1. Launch the app on your ARCore-compatible device
2. Grant camera permission when prompted
3. Grant storage permission if prompted

### Using the App

1. **Scans List**: Main screen showing all your scans
   - Tap **+** button to start new scan

2. **Capture Screen**:
   - Point camera at the room
   - Tap the **Record** button (red circle)
   - Move slowly around the room (< 1 m/s)
   - Watch tracking indicator (green = good)
   - Frames counter shows progress
   - Tap **Stop** button (red square) when done
   - Minimum ~50 frames recommended

3. **Processing** (Coming Soon):
   - After capturing, processing will run
   - Takes 6-10 minutes for typical room
   - Progress shown in real-time

4. **Viewing** (Coming Soon):
   - View 3D mesh after processing
   - Orbit, pan, zoom controls
   - Export to OBJ format

## Troubleshooting

### Common Issues

#### "ARCore not supported"
- **Solution**: Device may not support ARCore
- **Check**: https://developers.google.com/ar/devices
- **Alternative**: Try a different device

#### "Camera permission denied"
- **Solution**: Grant permission in Settings → Apps → Room Scanner → Permissions

#### Build fails with "NDK not found"
- **Solution**: Install NDK via SDK Manager (Tools → SDK Manager → SDK Tools → NDK)

#### Gradle sync fails
- **Solution 1**: File → Invalidate Caches → Invalidate and Restart
- **Solution 2**: Delete `.gradle` folder and sync again
- **Solution 3**: Check internet connection (Gradle downloads dependencies)

#### App crashes on launch
- **Check**: Logcat for error messages (View → Tool Windows → Logcat)
- **Common cause**: Missing ARCore installation
- **Solution**: Install "Google Play Services for AR" from Play Store

#### Tracking is poor
- **Improve**:
  - Ensure good lighting
  - Move slower
  - Avoid blank walls
  - Point at textured surfaces

#### "Could not load library roomscanner"
- **Solution**: Rebuild native libraries:
  ```bash
  ./gradlew clean
  ./gradlew assembleDebug
  ```

### Viewing Logs

In Android Studio:

1. Open Logcat: **View → Tool Windows → Logcat**
2. Filter by tag:
   - `RoomScanner-Native`: Native code logs
   - `ARCore`: ARCore logs
   - `RoomScanner`: App logs

Command line:
```bash
adb logcat -s RoomScanner:D ARCore:D
```

### Debug Native Code

1. Set breakpoints in C++ files
2. Run → Debug 'app'
3. Use LLDB debugger in Android Studio

## Development Workflow

### Making Changes

1. Make code changes
2. Sync Gradle if needed (File → Sync Project)
3. Build and run
4. Test on device

### Adding Native Libraries

1. Download library (e.g., OpenCV Android SDK)
2. Extract to `app/src/main/cpp/libs/`
3. Update `CMakeLists.txt`:
   ```cmake
   add_library(opencv SHARED IMPORTED)
   set_target_properties(opencv PROPERTIES
       IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/libs/opencv/libopencv_java4.so)

   target_link_libraries(roomscanner opencv)
   ```
4. Rebuild project

### Running Tests

```bash
# Unit tests
./gradlew test

# Instrumented tests (on device)
./gradlew connectedAndroidTest
```

## Performance Profiling

### CPU Profiler

1. Run → Profile 'app'
2. Select CPU profiler
3. Record session while scanning
4. Analyze hot paths

### Memory Profiler

1. Run → Profile 'app'
2. Select Memory profiler
3. Monitor heap during capture
4. Check for leaks

## Known Limitations (PoC)

- No actual reconstruction pipeline yet (placeholders)
- No 3D viewer implementation yet
- No export functionality yet
- OpenCV not integrated (TODO)
- Ceres Solver not integrated (TODO)
- Open3D not integrated (TODO)

See IMPLEMENTATION_STATUS.md for detailed progress.

## Next Steps

1. Integrate OpenCV for feature extraction
2. Integrate Ceres Solver for bundle adjustment
3. Integrate Open3D for mesh generation
4. Implement processing pipeline
5. Add 3D viewer
6. Add export functionality

## Getting Help

- **Documentation**: See POC_IMPLEMENTATION_PLAN.md and QUICKSTART_GUIDE.md
- **Issues**: Check existing issues or create new one
- **ARCore Docs**: https://developers.google.com/ar/develop
- **Android Docs**: https://developer.android.com

## License

To be determined.

---

**Last Updated**: 2026-01-09
**Build Status**: ✅ Compiles and runs (Weeks 1-2 complete)
