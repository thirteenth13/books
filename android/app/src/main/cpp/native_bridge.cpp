#include <jni.h>
#include <unistd.h>
#include <zlib.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

#include "InpxConstant.h"
#include "inpx_book_record.h"

namespace {
constexpr std::uint32_t ZIP_LOCAL_FILE_SIGNATURE = 0x04034b50;
constexpr std::uint32_t ZIP_CENTRAL_FILE_SIGNATURE = 0x02014b50;
constexpr std::uint32_t ZIP_EOCD_SIGNATURE = 0x06054b50;
constexpr std::size_t ZIP_EOCD_MIN_SIZE = 22;
constexpr std::size_t ZIP_MAX_COMMENT = 65535;
constexpr std::size_t ZIP_CENTRAL_HEADER_SIZE = 46;
constexpr std::size_t ZIP_LOCAL_HEADER_SIZE = 30;
constexpr std::size_t MAX_DISPLAY_ENTRIES = 20;
constexpr std::size_t MAX_BOOK_PREVIEW = 8;
constexpr std::size_t MAX_INP_UNCOMPRESSED = 64 * 1024 * 1024;

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
    status += "\nZIP deflate support: zlib";
    status += "\nTyped INPX book model: 17 fields";
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

struct ZipEntry {
    std::string name;
    std::uint16_t method{0};
    std::uint32_t compressedSize{0};
    std::uint32_t uncompressedSize{0};
    std::uint32_t localHeaderOffset{0};
};

struct ZipIndex {
    bool ok{false};
    std::string error;
    std::uint16_t totalEntries{0};
    std::vector<ZipEntry> entries;
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
    const std::uint32_t centralOffset = ReadLe32(footer + 16);

    if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk != result.totalEntries) {
        result.error = "Multi-volume ZIP/INPX archives are not supported yet";
        return result;
    }

    const std::uint64_t centralEnd = static_cast<std::uint64_t>(centralOffset) + centralSize;
    if (centralEnd > static_cast<std::uint64_t>(fileSize)) {
        result.error = "Invalid ZIP central directory bounds";
        return result;
    }

    off_t cursor = static_cast<off_t>(centralOffset);
    std::array<std::uint8_t, ZIP_CENTRAL_HEADER_SIZE> header{};
    result.entries.reserve(result.totalEntries);

    for (std::uint16_t i = 0; i < result.totalEntries; ++i) {
        if (!ReadExactAt(fd, header.data(), header.size(), cursor)) {
            result.error = "Cannot read ZIP central directory entry";
            return result;
        }
        if (ReadLe32(header.data()) != ZIP_CENTRAL_FILE_SIGNATURE) {
            result.error = "Invalid ZIP central directory entry signature";
            return result;
        }

        ZipEntry entry;
        entry.method = ReadLe16(header.data() + 10);
        entry.compressedSize = ReadLe32(header.data() + 20);
        entry.uncompressedSize = ReadLe32(header.data() + 24);
        const std::uint16_t nameLength = ReadLe16(header.data() + 28);
        const std::uint16_t extraLength = ReadLe16(header.data() + 30);
        const std::uint16_t commentLength = ReadLe16(header.data() + 32);
        entry.localHeaderOffset = ReadLe32(header.data() + 42);

        entry.name.resize(nameLength);
        if (nameLength > 0 &&
            !ReadExactAt(fd, entry.name.data(), entry.name.size(), cursor + ZIP_CENTRAL_HEADER_SIZE)) {
            result.error = "Cannot read ZIP entry name";
            return result;
        }

        result.entries.push_back(std::move(entry));
        cursor += static_cast<off_t>(ZIP_CENTRAL_HEADER_SIZE) +
                  nameLength + extraLength + commentLength;
    }

    result.ok = true;
    return result;
}

bool ExtractEntry(int fd, const ZipEntry& entry, std::string& output, std::string& error) {
    if (entry.uncompressedSize > MAX_INP_UNCOMPRESSED) {
        error = "INP entry is too large for preview";
        return false;
    }

    std::array<std::uint8_t, ZIP_LOCAL_HEADER_SIZE> header{};
    if (!ReadExactAt(fd, header.data(), header.size(), entry.localHeaderOffset) ||
        ReadLe32(header.data()) != ZIP_LOCAL_FILE_SIGNATURE) {
        error = "Cannot read ZIP local header";
        return false;
    }

    const std::uint16_t nameLength = ReadLe16(header.data() + 26);
    const std::uint16_t extraLength = ReadLe16(header.data() + 28);
    const off_t dataOffset = static_cast<off_t>(entry.localHeaderOffset) +
                             ZIP_LOCAL_HEADER_SIZE + nameLength + extraLength;

    std::vector<std::uint8_t> compressed(entry.compressedSize);
    if (!compressed.empty() && !ReadExactAt(fd, compressed.data(), compressed.size(), dataOffset)) {
        error = "Cannot read compressed INP data";
        return false;
    }

    output.assign(entry.uncompressedSize, '\0');
    if (entry.method == 0) {
        if (entry.compressedSize != entry.uncompressedSize) {
            error = "Stored ZIP entry has inconsistent size";
            return false;
        }
        output.assign(reinterpret_cast<const char*>(compressed.data()), compressed.size());
        return true;
    }

    if (entry.method != 8) {
        error = "Unsupported ZIP compression method: " + std::to_string(entry.method);
        return false;
    }

    z_stream stream{};
    stream.next_in = compressed.data();
    stream.avail_in = static_cast<uInt>(compressed.size());
    stream.next_out = reinterpret_cast<Bytef*>(output.data());
    stream.avail_out = static_cast<uInt>(output.size());

    if (inflateInit2(&stream, -MAX_WBITS) != Z_OK) {
        error = "zlib initialization failed";
        return false;
    }
    const int code = inflate(&stream, Z_FINISH);
    inflateEnd(&stream);

    if (code != Z_STREAM_END) {
        error = "zlib failed to decompress INP entry";
        return false;
    }
    output.resize(stream.total_out);
    return true;
}

std::string PreviewBooks(std::string_view inp) {
    std::string result;
    std::size_t cursor = 0;
    std::size_t shown = 0;
    std::size_t parsed = 0;

    while (cursor < inp.size()) {
        auto lineEnd = inp.find('\n', cursor);
        if (lineEnd == std::string_view::npos) {
            lineEnd = inp.size();
        }
        auto line = inp.substr(cursor, lineEnd - cursor);
        if (!line.empty() && line.back() == '\r') {
            line.remove_suffix(1);
        }

        flibrary::android::BookRecord book;
        if (flibrary::android::ParseBookRecord(line, book)) {
            ++parsed;
            if (shown < MAX_BOOK_PREVIEW) {
                result += "\n• ";
                result += flibrary::android::BookSummary(book);
                ++shown;
            }
        }
        cursor = lineEnd + 1;
    }

    if (parsed == 0) {
        return "\nNo recognizable book records found in the first INP file";
    }

    result += "\n\nParsed records in this INP: " + std::to_string(parsed);
    if (parsed > shown) {
        result += "\nPreview shows first " + std::to_string(shown) + " books";
    }
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

    const ZipEntry* firstInp = nullptr;
    std::size_t inpCount = 0;
    for (const auto& entry : index.entries) {
        if (EndsWithIgnoreCase(entry.name, INP_EXT)) {
            ++inpCount;
            if (firstInp == nullptr) {
                firstInp = &entry;
            }
        }
    }

    output += inpxName ? "INPX container detected" : "ZIP container detected (filename is not .inpx)";
    output += "\nEntries: " + std::to_string(index.totalEntries);
    output += "\n.INP files: " + std::to_string(inpCount);
    output += "\n\nArchive contents:";

    const auto displayCount = std::min<std::size_t>(index.entries.size(), MAX_DISPLAY_ENTRIES);
    for (std::size_t i = 0; i < displayCount; ++i) {
        output += "\n• " + index.entries[i].name;
    }
    if (index.entries.size() > displayCount) {
        output += "\n… +" + std::to_string(index.entries.size() - displayCount) + " more";
    }

    if (firstInp != nullptr) {
        std::string inp;
        std::string error;
        output += "\n\nFirst INP: " + firstInp->name;
        if (ExtractEntry(fd, *firstInp, inp, error)) {
            output += "\nBooks preview:";
            output += PreviewBooks(inp);
        } else {
            output += "\nCannot extract INP: " + error;
        }
    }

    return env->NewStringUTF(output.c_str());
}
