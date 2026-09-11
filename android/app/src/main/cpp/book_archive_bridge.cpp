#include <jni.h>
#include <unistd.h>
#include <zlib.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace {
constexpr std::uint32_t ZIP_LOCAL_FILE_SIGNATURE = 0x04034b50;
constexpr std::uint32_t ZIP_CENTRAL_FILE_SIGNATURE = 0x02014b50;
constexpr std::uint32_t ZIP_EOCD_SIGNATURE = 0x06054b50;
constexpr std::uint32_t ZIP64_EOCD_SIGNATURE = 0x06064b50;
constexpr std::uint32_t ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
constexpr std::uint16_t ZIP64_EXTRA_ID = 0x0001;
constexpr std::size_t ZIP_EOCD_MIN_SIZE = 22;
constexpr std::size_t ZIP_MAX_COMMENT = 65535;
constexpr std::size_t ZIP_CENTRAL_HEADER_SIZE = 46;
constexpr std::size_t ZIP_LOCAL_HEADER_SIZE = 30;
constexpr std::size_t ZIP64_LOCATOR_SIZE = 20;
constexpr std::size_t ZIP64_EOCD_MIN_SIZE = 56;
constexpr std::uint64_t MAX_BOOK_SIZE = 128ull * 1024ull * 1024ull;
constexpr std::uint64_t MAX_ENTRIES = 2'000'000;

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

std::uint64_t ReadLe64(const std::uint8_t* p) {
    return static_cast<std::uint64_t>(ReadLe32(p)) |
           (static_cast<std::uint64_t>(ReadLe32(p + 4)) << 32);
}

bool ReadExactAt(int fd, void* buffer, std::size_t size, std::uint64_t offset) {
    if (offset > static_cast<std::uint64_t>(std::numeric_limits<off_t>::max())) return false;
    auto* out = static_cast<std::uint8_t*>(buffer);
    std::size_t done = 0;
    while (done < size) {
        const auto current = offset + done;
        if (current > static_cast<std::uint64_t>(std::numeric_limits<off_t>::max())) return false;
        const auto count = pread(fd, out + done, size - done, static_cast<off_t>(current));
        if (count <= 0) return false;
        done += static_cast<std::size_t>(count);
    }
    return true;
}

std::string Lower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

struct Entry {
    std::string name;
    std::uint16_t flags{0};
    std::uint16_t method{0};
    std::uint64_t compressedSize{0};
    std::uint64_t uncompressedSize{0};
    std::uint64_t localHeaderOffset{0};
};

struct CentralDirectory {
    std::uint64_t count{0};
    std::uint64_t offset{0};
    std::uint64_t size{0};
};

bool ReadCentralDirectoryInfo(int fd, std::uint64_t fileSize, CentralDirectory& central) {
    if (fileSize < ZIP_EOCD_MIN_SIZE) return false;

    const std::size_t tailSize = static_cast<std::size_t>(
            std::min<std::uint64_t>(fileSize, ZIP_EOCD_MIN_SIZE + ZIP_MAX_COMMENT));
    const std::uint64_t tailOffset = fileSize - tailSize;
    std::vector<std::uint8_t> tail(tailSize);
    if (!ReadExactAt(fd, tail.data(), tail.size(), tailOffset)) return false;

    std::size_t eocd = tail.size();
    for (std::size_t i = tail.size() - ZIP_EOCD_MIN_SIZE + 1; i-- > 0;) {
        if (ReadLe32(tail.data() + i) == ZIP_EOCD_SIGNATURE) {
            eocd = i;
            break;
        }
    }
    if (eocd == tail.size()) return false;

    const auto* footer = tail.data() + eocd;
    const std::uint16_t diskNumber = ReadLe16(footer + 4);
    const std::uint16_t centralDisk = ReadLe16(footer + 6);
    const std::uint16_t entriesOnDisk = ReadLe16(footer + 8);
    const std::uint16_t totalEntries16 = ReadLe16(footer + 10);
    const std::uint32_t centralSize32 = ReadLe32(footer + 12);
    const std::uint32_t centralOffset32 = ReadLe32(footer + 16);

    const bool needsZip64 = totalEntries16 == 0xffffu ||
            centralSize32 == 0xffffffffu || centralOffset32 == 0xffffffffu;

    if (!needsZip64) {
        if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk != totalEntries16) return false;
        central.count = totalEntries16;
        central.size = centralSize32;
        central.offset = centralOffset32;
        return central.offset + central.size <= fileSize;
    }

    const std::uint64_t eocdAbsolute = tailOffset + eocd;
    if (eocdAbsolute < ZIP64_LOCATOR_SIZE) return false;

    std::array<std::uint8_t, ZIP64_LOCATOR_SIZE> locator{};
    if (!ReadExactAt(fd, locator.data(), locator.size(), eocdAbsolute - ZIP64_LOCATOR_SIZE) ||
        ReadLe32(locator.data()) != ZIP64_LOCATOR_SIGNATURE) {
        return false;
    }
    if (ReadLe32(locator.data() + 4) != 0 || ReadLe32(locator.data() + 16) != 1) return false;

    const std::uint64_t zip64Offset = ReadLe64(locator.data() + 8);
    std::array<std::uint8_t, ZIP64_EOCD_MIN_SIZE> zip64{};
    if (!ReadExactAt(fd, zip64.data(), zip64.size(), zip64Offset) ||
        ReadLe32(zip64.data()) != ZIP64_EOCD_SIGNATURE) {
        return false;
    }
    if (ReadLe32(zip64.data() + 16) != 0 || ReadLe32(zip64.data() + 20) != 0) return false;

    const std::uint64_t entriesDisk64 = ReadLe64(zip64.data() + 24);
    central.count = ReadLe64(zip64.data() + 32);
    central.size = ReadLe64(zip64.data() + 40);
    central.offset = ReadLe64(zip64.data() + 48);
    if (entriesDisk64 != central.count || central.count > MAX_ENTRIES) return false;
    return central.offset <= fileSize && central.size <= fileSize - central.offset;
}

bool ApplyZip64Extra(const std::vector<std::uint8_t>& extra,
                     bool needUncompressed,
                     bool needCompressed,
                     bool needOffset,
                     std::uint64_t& uncompressed,
                     std::uint64_t& compressed,
                     std::uint64_t& localOffset) {
    std::size_t cursor = 0;
    while (cursor + 4 <= extra.size()) {
        const std::uint16_t id = ReadLe16(extra.data() + cursor);
        const std::uint16_t length = ReadLe16(extra.data() + cursor + 2);
        cursor += 4;
        if (cursor + length > extra.size()) return false;
        if (id == ZIP64_EXTRA_ID) {
            std::size_t p = cursor;
            const std::size_t end = cursor + length;
            if (needUncompressed) {
                if (p + 8 > end) return false;
                uncompressed = ReadLe64(extra.data() + p);
                p += 8;
            }
            if (needCompressed) {
                if (p + 8 > end) return false;
                compressed = ReadLe64(extra.data() + p);
                p += 8;
            }
            if (needOffset) {
                if (p + 8 > end) return false;
                localOffset = ReadLe64(extra.data() + p);
            }
            return true;
        }
        cursor += length;
    }
    return !(needUncompressed || needCompressed || needOffset);
}

bool ReadIndex(int fd, std::vector<Entry>& entries) {
    const off_t rawFileSize = lseek(fd, 0, SEEK_END);
    if (rawFileSize < 0) return false;
    const std::uint64_t fileSize = static_cast<std::uint64_t>(rawFileSize);

    CentralDirectory central;
    if (!ReadCentralDirectoryInfo(fd, fileSize, central) || central.count > MAX_ENTRIES) return false;

    std::uint64_t cursor = central.offset;
    std::array<std::uint8_t, ZIP_CENTRAL_HEADER_SIZE> header{};
    entries.clear();
    entries.reserve(static_cast<std::size_t>(central.count));

    for (std::uint64_t i = 0; i < central.count; ++i) {
        if (!ReadExactAt(fd, header.data(), header.size(), cursor) ||
            ReadLe32(header.data()) != ZIP_CENTRAL_FILE_SIGNATURE) {
            return false;
        }

        Entry entry;
        entry.flags = ReadLe16(header.data() + 8);
        entry.method = ReadLe16(header.data() + 10);
        const std::uint32_t compressed32 = ReadLe32(header.data() + 20);
        const std::uint32_t uncompressed32 = ReadLe32(header.data() + 24);
        const std::uint16_t nameLength = ReadLe16(header.data() + 28);
        const std::uint16_t extraLength = ReadLe16(header.data() + 30);
        const std::uint16_t commentLength = ReadLe16(header.data() + 32);
        const std::uint32_t localOffset32 = ReadLe32(header.data() + 42);

        entry.compressedSize = compressed32;
        entry.uncompressedSize = uncompressed32;
        entry.localHeaderOffset = localOffset32;

        entry.name.resize(nameLength);
        if (nameLength > 0 && !ReadExactAt(fd, entry.name.data(), nameLength,
                cursor + ZIP_CENTRAL_HEADER_SIZE)) {
            return false;
        }

        std::vector<std::uint8_t> extra(extraLength);
        if (extraLength > 0 && !ReadExactAt(fd, extra.data(), extra.size(),
                cursor + ZIP_CENTRAL_HEADER_SIZE + nameLength)) {
            return false;
        }

        const bool needUncompressed = uncompressed32 == 0xffffffffu;
        const bool needCompressed = compressed32 == 0xffffffffu;
        const bool needOffset = localOffset32 == 0xffffffffu;
        if ((needUncompressed || needCompressed || needOffset) &&
            !ApplyZip64Extra(extra, needUncompressed, needCompressed, needOffset,
                    entry.uncompressedSize, entry.compressedSize, entry.localHeaderOffset)) {
            return false;
        }

        entries.push_back(std::move(entry));
        const std::uint64_t advance = ZIP_CENTRAL_HEADER_SIZE +
                static_cast<std::uint64_t>(nameLength) + extraLength + commentLength;
        if (cursor > fileSize || advance > fileSize - cursor) return false;
        cursor += advance;
    }
    return true;
}

bool Extract(int fd, const Entry& entry, std::string& output) {
    if ((entry.flags & 0x0001u) != 0) return false;
    if (entry.uncompressedSize > MAX_BOOK_SIZE) return false;
    if (entry.compressedSize > static_cast<std::uint64_t>(std::numeric_limits<std::size_t>::max())) return false;

    std::array<std::uint8_t, ZIP_LOCAL_HEADER_SIZE> header{};
    if (!ReadExactAt(fd, header.data(), header.size(), entry.localHeaderOffset) ||
        ReadLe32(header.data()) != ZIP_LOCAL_FILE_SIGNATURE) {
        return false;
    }

    const std::uint16_t nameLength = ReadLe16(header.data() + 26);
    const std::uint16_t extraLength = ReadLe16(header.data() + 28);
    const std::uint64_t dataOffset = entry.localHeaderOffset +
            ZIP_LOCAL_HEADER_SIZE + nameLength + extraLength;

    std::vector<std::uint8_t> compressed(static_cast<std::size_t>(entry.compressedSize));
    if (!compressed.empty() && !ReadExactAt(fd, compressed.data(), compressed.size(), dataOffset)) {
        return false;
    }

    if (entry.method == 0) {
        if (entry.compressedSize != entry.uncompressedSize) return false;
        output.assign(reinterpret_cast<const char*>(compressed.data()), compressed.size());
        return true;
    }
    if (entry.method != 8) return false;

    output.assign(static_cast<std::size_t>(entry.uncompressedSize), '\0');
    z_stream stream{};
    stream.next_in = compressed.data();
    stream.avail_in = static_cast<uInt>(compressed.size());
    stream.next_out = reinterpret_cast<Bytef*>(output.data());
    stream.avail_out = static_cast<uInt>(output.size());
    if (inflateInit2(&stream, -MAX_WBITS) != Z_OK) return false;
    const int code = inflate(&stream, Z_FINISH);
    inflateEnd(&stream);
    if (code != Z_STREAM_END) return false;
    output.resize(stream.total_out);
    return true;
}

std::string JStringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ua_flibrary_android_MainActivity_nativeExtractBook(
        JNIEnv* env,
        jobject,
        jint fd,
        jstring fileName,
        jstring extension) {
    const std::string file = JStringToUtf8(env, fileName);
    const std::string ext = JStringToUtf8(env, extension);
    if (file.empty()) return nullptr;

    std::vector<Entry> entries;
    if (!ReadIndex(fd, entries)) return nullptr;

    const std::string wanted = Lower(file);
    const std::string withExt = ext.empty() || file.find('.') != std::string::npos
            ? wanted
            : Lower(file + "." + ext);

    const Entry* match = nullptr;
    for (const auto& entry : entries) {
        const std::string name = Lower(entry.name);
        if (name == wanted || name == withExt) {
            match = &entry;
            break;
        }
    }
    if (match == nullptr) return nullptr;

    std::string output;
    if (!Extract(fd, *match, output)) return nullptr;

    jbyteArray array = env->NewByteArray(static_cast<jsize>(output.size()));
    if (array == nullptr) return nullptr;
    if (!output.empty()) {
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(output.size()),
                reinterpret_cast<const jbyte*>(output.data()));
    }
    return array;
}
