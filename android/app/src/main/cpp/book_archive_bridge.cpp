#include <jni.h>
#include <unistd.h>
#include <zlib.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <string>
#include <vector>

namespace {
constexpr std::uint32_t ZIP_LOCAL_FILE_SIGNATURE = 0x04034b50;
constexpr std::uint32_t ZIP_CENTRAL_FILE_SIGNATURE = 0x02014b50;
constexpr std::uint32_t ZIP_EOCD_SIGNATURE = 0x06054b50;
constexpr std::size_t ZIP_EOCD_MIN_SIZE = 22;
constexpr std::size_t ZIP_MAX_COMMENT = 65535;
constexpr std::size_t ZIP_CENTRAL_HEADER_SIZE = 46;
constexpr std::size_t ZIP_LOCAL_HEADER_SIZE = 30;
constexpr std::size_t MAX_BOOK_SIZE = 128 * 1024 * 1024;

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
    std::uint16_t method{0};
    std::uint32_t compressedSize{0};
    std::uint32_t uncompressedSize{0};
    std::uint32_t localHeaderOffset{0};
};

bool ReadIndex(int fd, std::vector<Entry>& entries) {
    const off_t fileSize = lseek(fd, 0, SEEK_END);
    if (fileSize < static_cast<off_t>(ZIP_EOCD_MIN_SIZE)) return false;

    const std::size_t tailSize = static_cast<std::size_t>(
            std::min<off_t>(fileSize, static_cast<off_t>(ZIP_EOCD_MIN_SIZE + ZIP_MAX_COMMENT)));
    const off_t tailOffset = fileSize - static_cast<off_t>(tailSize);
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
    const std::uint16_t count = ReadLe16(footer + 10);
    const std::uint32_t centralOffset = ReadLe32(footer + 16);

    off_t cursor = static_cast<off_t>(centralOffset);
    std::array<std::uint8_t, ZIP_CENTRAL_HEADER_SIZE> header{};
    entries.reserve(count);
    for (std::uint16_t i = 0; i < count; ++i) {
        if (!ReadExactAt(fd, header.data(), header.size(), cursor) ||
            ReadLe32(header.data()) != ZIP_CENTRAL_FILE_SIGNATURE) {
            return false;
        }
        Entry entry;
        entry.method = ReadLe16(header.data() + 10);
        entry.compressedSize = ReadLe32(header.data() + 20);
        entry.uncompressedSize = ReadLe32(header.data() + 24);
        const std::uint16_t nameLength = ReadLe16(header.data() + 28);
        const std::uint16_t extraLength = ReadLe16(header.data() + 30);
        const std::uint16_t commentLength = ReadLe16(header.data() + 32);
        entry.localHeaderOffset = ReadLe32(header.data() + 42);
        entry.name.resize(nameLength);
        if (nameLength > 0 && !ReadExactAt(fd, entry.name.data(), nameLength,
                cursor + static_cast<off_t>(ZIP_CENTRAL_HEADER_SIZE))) {
            return false;
        }
        entries.push_back(std::move(entry));
        cursor += static_cast<off_t>(ZIP_CENTRAL_HEADER_SIZE) + nameLength + extraLength + commentLength;
    }
    return true;
}

bool Extract(int fd, const Entry& entry, std::string& output) {
    if (entry.uncompressedSize > MAX_BOOK_SIZE) return false;

    std::array<std::uint8_t, ZIP_LOCAL_HEADER_SIZE> header{};
    if (!ReadExactAt(fd, header.data(), header.size(), entry.localHeaderOffset) ||
        ReadLe32(header.data()) != ZIP_LOCAL_FILE_SIGNATURE) {
        return false;
    }

    const std::uint16_t nameLength = ReadLe16(header.data() + 26);
    const std::uint16_t extraLength = ReadLe16(header.data() + 28);
    const off_t dataOffset = static_cast<off_t>(entry.localHeaderOffset) +
            ZIP_LOCAL_HEADER_SIZE + nameLength + extraLength;

    std::vector<std::uint8_t> compressed(entry.compressedSize);
    if (!compressed.empty() && !ReadExactAt(fd, compressed.data(), compressed.size(), dataOffset)) {
        return false;
    }

    if (entry.method == 0) {
        if (entry.compressedSize != entry.uncompressedSize) return false;
        output.assign(reinterpret_cast<const char*>(compressed.data()), compressed.size());
        return true;
    }
    if (entry.method != 8) return false;

    output.assign(entry.uncompressedSize, '\0');
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
