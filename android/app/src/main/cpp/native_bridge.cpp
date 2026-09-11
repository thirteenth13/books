#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_ua_flibrary_android_MainActivity_nativeStatus(JNIEnv* env, jobject) {
    return env->NewStringUTF("C++/JNI bridge is running (arm64-v8a)");
}
