#include <jni.h>
#include <unistd.h>

#include <array>
#include <cstdint>
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

bool HasZipSignature(int fd) {
    std::array<std::uint8_t, 4> signature{};
    const auto read = pread(fd, signature.data(), signature.size(), 0);
    if (read != static_cast<ssize_t>(signature.size())) {
        return false;
    }

    if (signature[0] != 0x50 || signature[1] != 0x4b) {
        return false;
    }

    const bool localFile = signature[2] == 0x03 && signature[3] == 0x04;
    const bool emptyZip = signature[2] == 0x05 && signature[3] == 0x06;
    const bool spanning = signature[2] == 0x07 && signature[3] == 0x08;
    return localFile || emptyZip || spanning;
}

std::string JStringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }

    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }

    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_ua_flibrary_android_MainActivity_nativeStatus(JNIEnv* env, jobject) {
    const auto status = BuildStatus();
    return env->NewStringUTF(status.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_ua_flibrary_android_MainActivity_nativeProbeInpx(
        JNIEnv* env,
        jobject,
        jint fd,
        jstring displayName) {
    const auto name = JStringToUtf8(env, displayName);
    const bool zipSignature = HasZipSignature(fd);
    const bool inpxName = name.size() >= 5 &&
            name.compare(name.size() - 5, 5, INPX_EXT) == 0;

    std::string result = "Selected: " + (name.empty() ? std::string("document") : name);
    result += "\n";

    if (!zipSignature) {
        result += "Not an INPX-compatible ZIP archive";
    } else if (!inpxName) {
        result += "ZIP archive detected, but filename does not end with .inpx";
    } else {
        result += "INPX container detected";
        result += "\nNext step: enumerate .inp entries and metadata";
    }

    return env->NewStringUTF(result.c_str());
}
