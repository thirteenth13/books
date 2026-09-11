#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <string>
#include <vector>

#include "InpxConstant.h"

namespace {
constexpr std::uint32_t ZIP_LOCAL_FILE_SIGNATURE = 0x04034b50;
constexpr std::uint32_t ZIP_CENTRAL_FILE_SIGNATURE = 0x02014b50;
constexpr std::uint32_t ZIP_EOCD_SIGNATURE = 0x06054b50;
constexpr std::size_t ZIP_EOCD_MIN_SIZE = 22;
constexpr std::size_t ZIP_MAX_COMMENT = 65535;
constexpr std::size_t ZIP_CENTRAL_HEADER_SIZE = 46;
constexpr std::size_t MAX_DISPLAY_ENTRIES = 20;

std::uint16_t ReadLe16(const std::uint8_t* p) {
    return static_cast<std::uint16_t>(p[0]) |
           (static_cast<std::uint16_t>(p[1]) << 8);
}

std::uint32_t ReadLe32(const std::uint8_t* p) {
    return static_cast<std::uint32_t>(p[0]) |
           (static_cast<std::uint32_t>(p[1]) << 8) |
           (static_cast<std::uint32_t>(p[2]) << 16) |
           (static_cast<std::uint32_t>(p[3]) << 24);
}

bool ReadExactAt(int fd, void* buffer, std::size_t size, off_t offset) {
    auto* out = static_cast<std::uint8_t*>(buffer);
    std::size_t done = 0;
    while (done < size) {
        const auto count = pread(fd, out + done, size - done, offset + static_cast<off_t>(done));
        if (count <= 0) {
            return false;
        }
        done += static_cast<std::size_t>(count);
    }
    return true;
}

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
    if (!ReadExactAt(fd, signature.data(), signature.size(), 0)) {
        return false;
    }
    return ReadLe32(signature.data()) == ZIP_LOCAL_FILE_SIGNATURE ||
           ReadLe32(signature.data()) == ZIP_EOCD_SIGNATURE;
}

bool EndsWithIgnoreCase(const std::string& value, const std::string& suffix) {
    if (value.size() < suffix.size()) {
        return false;
    }
    const auto offset = value.size() - suffix.size();
    for (std::size_t i = 0; i < suffix.size(); ++i) {
        const auto a = static_cast<unsigned char>(value[offset + i]);
        const auto b = static_cast<unsigned char>(suffix[i]);
        if (std::tolower(a) != std::tolower(b)) {
            return false;
        }
    }
    return true;
}

struct ZipIndex {
    bool ok{false};
    std::string error;
    std::uint16_t totalEntries{0};
    std::uint32_t centralOffset{0};
    std::vector<std::string> names;
};

ZipIndex ReadZipIndex(int fd) {
    ZipIndex result;

    const off_t fileSize = lseek(fd, 0, SEEK_END);
    if (fileSize < static_cast<off_t>(ZIP_EOCD_MIN_SIZE)) {
        result.error = fileSize < 0
                ? "Document provider does not expose a seekable file descriptor"
                : "ZIP archive is too small";
        return result;
    }

    const std::size_t tailSize = static_cast<std::size_t>(std::min<off_t>(
            fileSize, static_cast<off_t>(ZIP_EOCD_MIN_SIZE + ZIP_MAX_COMMENT)));
    const off_t tailOffset = fileSize - static_cast<off_t>(tailSize);
    std::vector<std::uint8_t> tail(tailSize);
    if (!ReadExactAt(fd, tail.data(), tail.size(), tailOffset)) {
        result.error = "Cannot read ZIP end-of-central-directory record";
        return result;
    }

    std::size_t eocd = tail.size();
    for (std::size_t i = tail.size() - ZIP_EOCD_MIN_SIZE + 1; i-- > 0;) {
        if (ReadLe32(tail.data() + i) == ZIP_EOCD_SIGNATURE) {
            eocd = i;
            break;
        }
    }
    if (eocd == tail.size()) {
        result.error = "ZIP central directory was not found";
        return result;
    }

    const auto* footer = tail.data() + eocd;
    const std::uint16_t diskNumber = ReadLe16(footer + 4);
    const std::uint16_t centralDisk = ReadLe16(footer + 6);
    const std::uint16_t entriesOnDisk = ReadLe16(footer + 8);
    result.totalEntries = ReadLe16(footer + 10);
    const std::uint32_t centralSize = ReadLe32(footer + 12);
    result.centralOffset = ReadLe32(footer + 16);

    if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk != result.totalEntries) {
        result.error = "Multi-volume ZIP/INPX archives are not supported yet";
        return result;
    }

    const std::uint64_t centralEnd = static_cast<std::uint64_t>(result.centralOffset) + centralSize;
    if (centralEnd > static_cast<std::uint64_t>(fileSize)) {
        result.error = "Invalid ZIP central directory bounds";
        return result;
    }

    off_t cursor = static_cast<off_t>(result.centralOffset);
    std::array<std::uint8_t, ZIP_CENTRAL_HEADER_SIZE> header{};
    result.names.reserve(std::min<std::size_t>(result.totalEntries, MAX_DISPLAY_ENTRIES));

    for (std::uint16_t entry = 0; entry < result.totalEntries; ++entry) {
        if (!ReadExactAt(fd, header.data(), header.size(), cursor)) {
            result.error = "Cannot read ZIP central directory entry";
            return result;
        }
        if (ReadLe32(header.data()) != ZIP_CENTRAL_FILE_SIGNATURE) {
            result.error = "Invalid ZIP central directory entry signature";
            return result;
        }

        const std::uint16_t nameLength = ReadLe16(header.data() + 28);
        const std::uint16_t extraLength = ReadLe16(header.data() + 30);
        const std::uint16_t commentLength = ReadLe16(header.data() + 32);

        if (nameLength > 0 && result.names.size() < MAX_DISPLAY_ENTRIES) {
            std::string name(nameLength, '\0');
            if (!ReadExactAt(fd, name.data(), name.size(), cursor + ZIP_CENTRAL_HEADER_SIZE)) {
                result.error = "Cannot read ZIP entry name";
                return result;
            }
            result.names.push_back(std::move(name));
        }

        cursor += static_cast<off_t>(ZIP_CENTRAL_HEADER_SIZE) +
                  nameLength + extraLength + commentLength;
    }

    result.ok = true;
    return result;
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
    const bool inpxName = EndsWithIgnoreCase(name, INPX_EXT);

    std::string output = "Selected: " + (name.empty() ? std::string("document") : name) + "\n";

    if (!zipSignature) {
        output += "Not an INPX-compatible ZIP archive";
        return env->NewStringUTF(output.c_str());
    }

    const auto index = ReadZipIndex(fd);
    if (!index.ok) {
        output += "ZIP detected, but index read failed: " + index.error;
        return env->NewStringUTF(output.c_str());
    }

    std::size_t inpCount = 0;
    for (const auto& entryName : index.names) {
        if (EndsWithIgnoreCase(entryName, INP_EXT)) {
            ++inpCount;
        }
    }

    output += inpxName ? "INPX container detected" : "ZIP container detected (filename is not .inpx)";
    output += "\nEntries: " + std::to_string(index.totalEntries);
    output += "\n.INP files shown: " + std::to_string(inpCount);
    output += "\n\nArchive contents:";

    for (const auto& entryName : index.names) {
        output += "\n• " + entryName;
    }
    if (index.totalEntries > index.names.size()) {
        output += "\n… +" + std::to_string(index.totalEntries - index.names.size()) + " more";
    }

    return env->NewStringUTF(output.c_str());
}
