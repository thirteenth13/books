#include <jni.h>

#include <string>

#include "InpxConstant.h"

namespace {
std::string BuildStatus() {
    std::string status = "C++/JNI bridge is running (arm64-v8a)";
    status += "\nFLibrary INPX core headers connected";
    status += "\nINPX extension: ";
    status += INPX_EXT;
    status += " | INP extension: ";
    status += INP_EXT;
    return status;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_ua_flibrary_android_MainActivity_nativeStatus(JNIEnv* env, jobject) {
    const auto status = BuildStatus();
    return env->NewStringUTF(status.c_str());
}
