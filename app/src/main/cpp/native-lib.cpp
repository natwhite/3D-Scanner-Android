#include <jni.h>
#include <string>
#include <android/log.h>

#define TAG "RoomScanner-Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_roomscanner_reconstruction_ReconstructionNative_getVersionInfo(
        JNIEnv* env,
        jobject /* this */) {
    std::string version = "Room Scanner PoC v0.1.0";
    LOGD("Native library loaded: %s", version.c_str());
    return env->NewStringUTF(version.c_str());
}
