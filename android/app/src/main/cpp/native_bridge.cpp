#include <jni.h>
#include <unistd.h>
#include <zlib.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <limits>
#include <set>
#include <string>
#include <string_view>
#include <vector>

#include "InpxConstant.h"
#include "inpx_book_record.h"

namespace {
constexpr std::uint32_t ZIP_LOCAL_FILE_SIGNATURE = 0x04034b50;
constexpr std::uint32_t ZIP_CENTRAL_FILE_SIGNATURE = 0x02014b50;
constexpr std::uint32_t ZIP_EOCD_SIGNATURE = 0x06054b50;
constexpr std::uint32_t ZIP64_EOCD_SIGNATURE = 0x06064b50;
constexpr std::uint32_t ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
constexpr std::uint16_t ZIP64_EXTRA_ID = 0x0001;
constexpr std::size_t ZIP_EOCD_MIN_SIZE = 22;
constexpr std::size_t ZIP64_EOCD_MIN_SIZE = 56;
constexpr std::size_t ZIP64_LOCATOR_SIZE = 20;
constexpr std::size_t ZIP_MAX_COMMENT = 65535;
constexpr std::size_t ZIP_CENTRAL_HEADER_SIZE = 46;
constexpr std::size_t ZIP_LOCAL_HEADER_SIZE = 30;
constexpr std::size_t MAX_DISPLAY_ENTRIES = 20;
constexpr std::size_t MAX_BOOK_PREVIEW = 8;
constexpr std::size_t MAX_INP_UNCOMPRESSED = 64 * 1024 * 1024;
constexpr std::size_t MAX_CATALOG_BOOKS = 2'000'000;
constexpr std::uint64_t MAX_ZIP_ENTRIES = 1'000'000;
constexpr std::size_t IMPORT_BATCH_SIZE = 500;
constexpr char SQLITE_FIELD_SEPARATOR = '\x1f';
constexpr std::string_view STRUCTURE_INFO_NAME = "structure.info";

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

bool ToOffset(std::uint64_t value, off_t& out) {
    if (value > static_cast<std::uint64_t>(std::numeric_limits<off_t>::max())) return false;
    out = static_cast<off_t>(value);
    return true;
}

bool ReadExactAt(int fd, void* buffer, std::size_t size, std::uint64_t offset) {
    off_t base = 0;
    if (!ToOffset(offset, base)) return false;
    auto* out = static_cast<std::uint8_t*>(buffer);
    std::size_t done = 0;
    while (done < size) {
        const auto count = pread(fd, out + done, size - done, base + static_cast<off_t>(done));
        if (count <= 0) return false;
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
    status += "\nZIP deflate + ZIP64 support: zlib";
    status += "\nDynamic structure.info field layout enabled";
    status += "\nFull multi-INP catalog scan enabled";
    status += "\nSQLite batch import bridge enabled";
    return status;
}

bool HasZipSignature(int fd) {
    std::array<std::uint8_t, 4> signature{};
    if (!ReadExactAt(fd, signature.data(), signature.size(), 0)) return false;
    return ReadLe32(signature.data()) == ZIP_LOCAL_FILE_SIGNATURE ||
           ReadLe32(signature.data()) == ZIP_EOCD_SIGNATURE;
}

bool EndsWithIgnoreCase(const std::string& value, const std::string& suffix) {
    if (value.size() < suffix.size()) return false;
    const auto offset = value.size() - suffix.size();
    for (std::size_t i = 0; i < suffix.size(); ++i) {
        const auto a = static_cast<unsigned char>(value[offset + i]);
        const auto b = static_cast<unsigned char>(suffix[i]);
        if (std::tolower(a) != std::tolower(b)) return false;
    }
    return true;
}

bool EqualsIgnoreCase(std::string_view a, std::string_view b) {
    if (a.size() != b.size()) return false;
    for (std::size_t i = 0; i < a.size(); ++i) {
        if (std::tolower(static_cast<unsigned char>(a[i])) !=
            std::tolower(static_cast<unsigned char>(b[i]))) return false;
    }
    return true;
}

struct ZipEntry {
    std::string name;
    std::uint16_t flags{0};
    std::uint16_t method{0};
    std::uint64_t compressedSize{0};
    std::uint64_t uncompressedSize{0};
    std::uint64_t localHeaderOffset{0};
};

struct ZipIndex {
    bool ok{false};
    bool zip64{false};
    std::string error;
    std::uint64_t totalEntries{0};
    std::vector<ZipEntry> entries;
};

bool ReadZip64Extra(const std::vector<std::uint8_t>& extra, bool needUncompressed,
        bool needCompressed, bool needOffset, bool needDisk,
        std::uint64_t& uncompressed, std::uint64_t& compressed,
        std::uint64_t& localOffset, std::uint32_t& diskStart) {
    std::size_t cursor = 0;
    while (cursor + 4 <= extra.size()) {
        const auto id = ReadLe16(extra.data() + cursor);
        const auto length = ReadLe16(extra.data() + cursor + 2);
        cursor += 4;
        if (cursor + length > extra.size()) return false;
        if (id != ZIP64_EXTRA_ID) {
            cursor += length;
            continue;
        }
        std::size_t p = cursor;
        const std::size_t end = cursor + length;
        auto read64 = [&](std::uint64_t& value) {
            if (p + 8 > end) return false;
            value = ReadLe64(extra.data() + p);
            p += 8;
            return true;
        };
        auto read32 = [&](std::uint32_t& value) {
            if (p + 4 > end) return false;
            value = ReadLe32(extra.data() + p);
            p += 4;
            return true;
        };
        if (needUncompressed && !read64(uncompressed)) return false;
        if (needCompressed && !read64(compressed)) return false;
        if (needOffset && !read64(localOffset)) return false;
        if (needDisk && !read32(diskStart)) return false;
        return true;
    }
    return !(needUncompressed || needCompressed || needOffset || needDisk);
}

ZipIndex ReadZipIndex(int fd) {
    ZipIndex result;
    const off_t fileSizeSigned = lseek(fd, 0, SEEK_END);
    if (fileSizeSigned < static_cast<off_t>(ZIP_EOCD_MIN_SIZE)) {
        result.error = fileSizeSigned < 0
                ? "Document provider does not expose a seekable file descriptor"
                : "ZIP archive is too small";
        return result;
    }
    const auto fileSize = static_cast<std::uint64_t>(fileSizeSigned);
    const std::size_t tailSize = static_cast<std::size_t>(std::min<std::uint64_t>(
            fileSize, ZIP_EOCD_MIN_SIZE + ZIP_MAX_COMMENT));
    const std::uint64_t tailOffset = fileSize - tailSize;
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
    const std::uint16_t diskNumber16 = ReadLe16(footer + 4);
    const std::uint16_t centralDisk16 = ReadLe16(footer + 6);
    const std::uint16_t entriesOnDisk16 = ReadLe16(footer + 8);
    const std::uint16_t totalEntries16 = ReadLe16(footer + 10);
    const std::uint32_t centralSize32 = ReadLe32(footer + 12);
    const std::uint32_t centralOffset32 = ReadLe32(footer + 16);
    std::uint64_t totalEntries = totalEntries16;
    std::uint64_t centralSize = centralSize32;
    std::uint64_t centralOffset = centralOffset32;
    std::uint32_t diskNumber = diskNumber16;
    std::uint32_t centralDisk = centralDisk16;
    std::uint64_t entriesOnDisk = entriesOnDisk16;
    const bool needsZip64 = diskNumber16 == 0xffff || centralDisk16 == 0xffff ||
            entriesOnDisk16 == 0xffff || totalEntries16 == 0xffff ||
            centralSize32 == 0xffffffffu || centralOffset32 == 0xffffffffu;
    if (needsZip64) {
        const std::uint64_t eocdAbsolute = tailOffset + eocd;
        if (eocdAbsolute < ZIP64_LOCATOR_SIZE) {
            result.error = "ZIP64 locator is missing";
            return result;
        }
        std::array<std::uint8_t, ZIP64_LOCATOR_SIZE> locator{};
        if (!ReadExactAt(fd, locator.data(), locator.size(), eocdAbsolute - ZIP64_LOCATOR_SIZE) ||
            ReadLe32(locator.data()) != ZIP64_LOCATOR_SIGNATURE) {
            result.error = "ZIP64 locator was not found";
            return result;
        }
        const std::uint32_t zip64Disk = ReadLe32(locator.data() + 4);
        const std::uint64_t zip64Offset = ReadLe64(locator.data() + 8);
        const std::uint32_t totalDisks = ReadLe32(locator.data() + 16);
        if (zip64Disk != 0 || totalDisks != 1) {
            result.error = "Multi-volume ZIP64/INPX archives are not supported";
            return result;
        }
        std::array<std::uint8_t, ZIP64_EOCD_MIN_SIZE> zip64{};
        if (!ReadExactAt(fd, zip64.data(), zip64.size(), zip64Offset) ||
            ReadLe32(zip64.data()) != ZIP64_EOCD_SIGNATURE) {
            result.error = "ZIP64 end-of-central-directory record was not found";
            return result;
        }
        diskNumber = ReadLe32(zip64.data() + 16);
        centralDisk = ReadLe32(zip64.data() + 20);
        entriesOnDisk = ReadLe64(zip64.data() + 24);
        totalEntries = ReadLe64(zip64.data() + 32);
        centralSize = ReadLe64(zip64.data() + 40);
        centralOffset = ReadLe64(zip64.data() + 48);
        result.zip64 = true;
    }
    if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk != totalEntries) {
        result.error = "Multi-volume ZIP/INPX archives are not supported yet";
        return result;
    }
    if (totalEntries > MAX_ZIP_ENTRIES || totalEntries > std::numeric_limits<std::size_t>::max()) {
        result.error = "ZIP contains too many entries";
        return result;
    }
    if (centralOffset > fileSize || centralSize > fileSize - centralOffset) {
        result.error = "Invalid ZIP central directory bounds";
        return result;
    }
    std::uint64_t cursor = centralOffset;
    std::array<std::uint8_t, ZIP_CENTRAL_HEADER_SIZE> header{};
    result.entries.reserve(static_cast<std::size_t>(totalEntries));
    for (std::uint64_t i = 0; i < totalEntries; ++i) {
        if (!ReadExactAt(fd, header.data(), header.size(), cursor) ||
            ReadLe32(header.data()) != ZIP_CENTRAL_FILE_SIGNATURE) {
            result.error = "Invalid ZIP central directory entry";
            return result;
        }
        ZipEntry entry;
        entry.flags = ReadLe16(header.data() + 8);
        entry.method = ReadLe16(header.data() + 10);
        const std::uint32_t compressed32 = ReadLe32(header.data() + 20);
        const std::uint32_t uncompressed32 = ReadLe32(header.data() + 24);
        const std::uint16_t nameLength = ReadLe16(header.data() + 28);
        const std::uint16_t extraLength = ReadLe16(header.data() + 30);
        const std::uint16_t commentLength = ReadLe16(header.data() + 32);
        const std::uint16_t diskStart16 = ReadLe16(header.data() + 34);
        const std::uint32_t localOffset32 = ReadLe32(header.data() + 42);
        entry.compressedSize = compressed32;
        entry.uncompressedSize = uncompressed32;
        entry.localHeaderOffset = localOffset32;
        std::uint32_t diskStart = diskStart16;
        entry.name.resize(nameLength);
        const std::uint64_t nameOffset = cursor + ZIP_CENTRAL_HEADER_SIZE;
        if (nameLength > 0 && !ReadExactAt(fd, entry.name.data(), entry.name.size(), nameOffset)) {
            result.error = "Cannot read ZIP entry name";
            return result;
        }
        std::vector<std::uint8_t> extra(extraLength);
        if (extraLength > 0 && !ReadExactAt(fd, extra.data(), extra.size(), nameOffset + nameLength)) {
            result.error = "Cannot read ZIP entry extra data";
            return result;
        }
        const bool needUncompressed = uncompressed32 == 0xffffffffu;
        const bool needCompressed = compressed32 == 0xffffffffu;
        const bool needOffset = localOffset32 == 0xffffffffu;
        const bool needDisk = diskStart16 == 0xffff;
        if ((needUncompressed || needCompressed || needOffset || needDisk) &&
            !ReadZip64Extra(extra, needUncompressed, needCompressed, needOffset, needDisk,
                    entry.uncompressedSize, entry.compressedSize, entry.localHeaderOffset, diskStart)) {
            result.error = "Invalid ZIP64 entry metadata";
            return result;
        }
        if (diskStart != 0) {
            result.error = "Multi-volume ZIP entries are not supported";
            return result;
        }
        const std::uint64_t recordSize = ZIP_CENTRAL_HEADER_SIZE +
                static_cast<std::uint64_t>(nameLength) + extraLength + commentLength;
        if (cursor > centralOffset + centralSize || recordSize > centralOffset + centralSize - cursor) {
            result.error = "ZIP central directory entry exceeds bounds";
            return result;
        }
        result.entries.push_back(std::move(entry));
        cursor += recordSize;
    }
    result.totalEntries = totalEntries;
    result.ok = true;
    return result;
}

bool ExtractEntry(int fd, const ZipEntry& entry, std::string& output, std::string& error) {
    if ((entry.flags & 0x0001u) != 0) {
        error = "Encrypted ZIP entries are not supported";
        return false;
    }
    if (entry.uncompressedSize > MAX_INP_UNCOMPRESSED) {
        error = "ZIP entry is too large";
        return false;
    }
    if (entry.compressedSize > std::numeric_limits<std::size_t>::max() ||
        entry.compressedSize > std::numeric_limits<uInt>::max()) {
        error = "Compressed ZIP entry is too large";
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
    const std::uint64_t dataOffset = entry.localHeaderOffset + ZIP_LOCAL_HEADER_SIZE +
            static_cast<std::uint64_t>(nameLength) + extraLength;
    std::vector<std::uint8_t> compressed(static_cast<std::size_t>(entry.compressedSize));
    if (!compressed.empty() && !ReadExactAt(fd, compressed.data(), compressed.size(), dataOffset)) {
        error = "Cannot read compressed ZIP data";
        return false;
    }
    output.assign(static_cast<std::size_t>(entry.uncompressedSize), '\0');
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
        error = "zlib failed to decompress ZIP entry";
        return false;
    }
    output.resize(stream.total_out);
    return true;
}

std::string TrimAscii(std::string_view value) {
    std::size_t start = 0;
    std::size_t end = value.size();
    while (start < end && std::isspace(static_cast<unsigned char>(value[start]))) ++start;
    while (end > start && std::isspace(static_cast<unsigned char>(value[end - 1]))) --end;
    return std::string(value.substr(start, end - start));
}

std::string UpperAscii(std::string value) {
    for (char& c : value) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
    return value;
}

bool MapFieldName(std::string_view raw, std::size_t index, flibrary::android::FieldLayout& layout) {
    const std::string name = UpperAscii(TrimAscii(raw));
    using flibrary::android::BookField;
    if (name == "AUTHOR") layout.Set(BookField::Author, index);
    else if (name == "GENRE") layout.Set(BookField::Genre, index);
    else if (name == "TITLE") layout.Set(BookField::Title, index);
    else if (name == "SERIES") layout.Set(BookField::Series, index);
    else if (name == "SERNO") layout.Set(BookField::SeriesNumber, index);
    else if (name == "FILE") layout.Set(BookField::File, index);
    else if (name == "SIZE") layout.Set(BookField::Size, index);
    else if (name == "LIBID") layout.Set(BookField::LibraryId, index);
    else if (name == "DEL") layout.Set(BookField::Deleted, index);
    else if (name == "EXT") layout.Set(BookField::Extension, index);
    else if (name == "DATE") layout.Set(BookField::Date, index);
    else if (name == "FOLDER") layout.Set(BookField::Folder, index);
    else if (name == "LANG") layout.Set(BookField::Language, index);
    else if (name == "LIBRATE") layout.Set(BookField::LibraryRate, index);
    else if (name == "KEYWORDS") layout.Set(BookField::Keywords, index);
    else if (name == "YEAR") layout.Set(BookField::Year, index);
    else if (name == "SOURCELIB") layout.Set(BookField::SourceLibrary, index);
    else return false;
    return true;
}

flibrary::android::FieldLayout ReadFieldLayout(int fd, const ZipIndex& index, bool* usedStructure = nullptr) {
    flibrary::android::FieldLayout layout;
    if (usedStructure) *usedStructure = false;
    const ZipEntry* structureEntry = nullptr;
    for (const auto& entry : index.entries) {
        const auto slash = entry.name.find_last_of("/\\");
        const std::string_view base = slash == std::string::npos
                ? std::string_view(entry.name)
                : std::string_view(entry.name).substr(slash + 1);
        if (EqualsIgnoreCase(base, STRUCTURE_INFO_NAME)) {
            structureEntry = &entry;
            break;
        }
    }
    if (structureEntry == nullptr) return layout;
    std::string structure;
    std::string error;
    if (!ExtractEntry(fd, *structureEntry, structure, error)) return layout;
    flibrary::android::FieldLayout parsed;
    parsed.Clear();
    std::size_t fieldIndex = 0;
    std::size_t start = 0;
    while (start <= structure.size()) {
        const auto end = structure.find(';', start);
        const std::string_view token(structure.data() + start,
                (end == std::string::npos ? structure.size() : end) - start);
        if (!TrimAscii(token).empty()) {
            MapFieldName(token, fieldIndex, parsed);
            ++fieldIndex;
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
    if (parsed.Get(flibrary::android::BookField::Title) == flibrary::android::kMissingField) return layout;
    if (usedStructure) *usedStructure = true;
    return parsed;
}

struct CatalogSummary {
    std::size_t books{0};
    std::size_t inpParsed{0};
    std::size_t inpFailed{0};
    std::set<std::string> authors;
    std::set<std::string> series;
    std::set<std::string> languages;
    std::vector<flibrary::android::BookRecord> preview;
    std::vector<std::string> errors;
};

void ParseInp(std::string_view inp, CatalogSummary& catalog, const flibrary::android::FieldLayout& layout) {
    std::size_t cursor = 0;
    while (cursor < inp.size() && catalog.books < MAX_CATALOG_BOOKS) {
        auto lineEnd = inp.find('\n', cursor);
        if (lineEnd == std::string_view::npos) lineEnd = inp.size();
        auto line = inp.substr(cursor, lineEnd - cursor);
        if (!line.empty() && line.back() == '\r') line.remove_suffix(1);
        flibrary::android::BookRecord book;
        if (flibrary::android::ParseBookRecord(line, book, layout)) {
            ++catalog.books;
            if (!book.author.empty()) catalog.authors.insert(book.author);
            if (!book.series.empty()) catalog.series.insert(book.series);
            if (!book.language.empty()) catalog.languages.insert(book.language);
            if (catalog.preview.size() < MAX_BOOK_PREVIEW) catalog.preview.push_back(std::move(book));
        }
        cursor = lineEnd + 1;
    }
}

CatalogSummary ParseCatalog(int fd, const ZipIndex& index, const flibrary::android::FieldLayout& layout) {
    CatalogSummary catalog;
    for (const auto& entry : index.entries) {
        if (!EndsWithIgnoreCase(entry.name, INP_EXT)) continue;
        std::string inp;
        std::string error;
        if (!ExtractEntry(fd, entry, inp, error)) {
            ++catalog.inpFailed;
            if (catalog.errors.size() < 3) catalog.errors.push_back(entry.name + ": " + error);
            continue;
        }
        ++catalog.inpParsed;
        ParseInp(inp, catalog, layout);
        if (catalog.books >= MAX_CATALOG_BOOKS) break;
    }
    return catalog;
}

std::string CatalogText(const CatalogSummary& catalog) {
    std::string result;
    result += "\n\nCatalog scan:";
    result += "\nBooks: " + std::to_string(catalog.books);
    result += "\nINP parsed: " + std::to_string(catalog.inpParsed);
    if (catalog.inpFailed > 0) result += " | failed: " + std::to_string(catalog.inpFailed);
    result += "\nAuthors: " + std::to_string(catalog.authors.size());
    result += "\nSeries: " + std::to_string(catalog.series.size());
    result += "\nLanguages: " + std::to_string(catalog.languages.size());
    result += "\n\nBooks preview:";
    for (const auto& book : catalog.preview) result += "\n• " + flibrary::android::BookSummary(book);
    if (catalog.preview.empty()) result += "\nNo recognizable book records found";
    if (!catalog.errors.empty()) {
        result += "\n\nWarnings:";
        for (const auto& error : catalog.errors) result += "\n• " + error;
    }
    if (catalog.books >= MAX_CATALOG_BOOKS) result += "\nCatalog safety limit reached";
    return result;
}

std::string EncodeBookRow(const flibrary::android::BookRecord& b) {
    const std::array<const std::string*, 17> fields = {
        &b.author, &b.genre, &b.title, &b.series, &b.seriesNumber, &b.file, &b.size,
        &b.libraryId, &b.deleted, &b.extension, &b.date, &b.folder, &b.language,
        &b.libraryRate, &b.keywords, &b.year, &b.sourceLibrary
    };
    std::string row;
    for (std::size_t i = 0; i < fields.size(); ++i) {
        if (i) row += SQLITE_FIELD_SEPARATOR;
        row += *fields[i];
    }
    return row;
}

bool FlushImportBatch(JNIEnv* env, jobject database, jmethodID insertMethod, std::vector<std::string>& batch) {
    if (batch.empty()) return true;
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return false;
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(batch.size()), stringClass, nullptr);
    if (array == nullptr) return false;
    for (jsize i = 0; i < static_cast<jsize>(batch.size()); ++i) {
        jstring value = env->NewStringUTF(batch[static_cast<std::size_t>(i)].c_str());
        if (value == nullptr) {
            env->DeleteLocalRef(array);
            return false;
        }
        env->SetObjectArrayElement(array, i, value);
        env->DeleteLocalRef(value);
    }
    env->CallVoidMethod(database, insertMethod, array);
    env->DeleteLocalRef(array);
    batch.clear();
    return !env->ExceptionCheck();
}

std::string ImportCatalogToDatabase(JNIEnv* env, int fd, jobject database) {
    const auto index = ReadZipIndex(fd);
    if (!index.ok) return "ERROR: " + index.error;
    bool usedStructure = false;
    const auto layout = ReadFieldLayout(fd, index, &usedStructure);
    jclass dbClass = env->GetObjectClass(database);
    if (dbClass == nullptr) return "ERROR: cannot access CatalogDatabase";
    jmethodID insertMethod = env->GetMethodID(dbClass, "insertNativeBatch", "([Ljava/lang/String;)V");
    if (insertMethod == nullptr) return "ERROR: CatalogDatabase.insertNativeBatch is missing";
    std::vector<std::string> batch;
    batch.reserve(IMPORT_BATCH_SIZE);
    std::size_t imported = 0;
    std::size_t inpParsed = 0;
    std::size_t inpFailed = 0;
    for (const auto& entry : index.entries) {
        if (!EndsWithIgnoreCase(entry.name, INP_EXT)) continue;
        std::string inp;
        std::string error;
        if (!ExtractEntry(fd, entry, inp, error)) {
            ++inpFailed;
            continue;
        }
        ++inpParsed;
        std::size_t cursor = 0;
        while (cursor < inp.size() && imported < MAX_CATALOG_BOOKS) {
            auto lineEnd = inp.find('\n', cursor);
            if (lineEnd == std::string::npos) lineEnd = inp.size();
            std::string_view line(inp.data() + cursor, lineEnd - cursor);
            if (!line.empty() && line.back() == '\r') line.remove_suffix(1);
            flibrary::android::BookRecord book;
            if (flibrary::android::ParseBookRecord(line, book, layout)) {
                batch.push_back(EncodeBookRow(book));
                ++imported;
                if (batch.size() >= IMPORT_BATCH_SIZE && !FlushImportBatch(env, database, insertMethod, batch)) {
                    return "ERROR: SQLite batch insert failed";
                }
            }
            cursor = lineEnd + 1;
        }
        if (imported >= MAX_CATALOG_BOOKS) break;
    }
    if (!FlushImportBatch(env, database, insertMethod, batch)) return "ERROR: SQLite final batch insert failed";
    return "OK: imported " + std::to_string(imported) + " books from " +
           std::to_string(inpParsed) + " INP files" +
           (usedStructure ? " using structure.info" : " using default field layout") +
           (index.zip64 ? " (ZIP64)" : "") +
           (inpFailed ? " (failed INP: " + std::to_string(inpFailed) + ")" : "");
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

extern "C" JNIEXPORT jstring JNICALL
Java_ua_flibrary_android_MainActivity_nativeStatus(JNIEnv* env, jobject) {
    const auto status = BuildStatus();
    return env->NewStringUTF(status.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_ua_flibrary_android_MainActivity_nativeProbeInpx(JNIEnv* env, jobject, jint fd, jstring displayName) {
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
    bool usedStructure = false;
    const auto layout = ReadFieldLayout(fd, index, &usedStructure);
    std::size_t inpCount = 0;
    for (const auto& entry : index.entries) if (EndsWithIgnoreCase(entry.name, INP_EXT)) ++inpCount;
    output += inpxName ? "INPX container detected" : "ZIP container detected (filename is not .inpx)";
    output += "\nEntries: " + std::to_string(index.totalEntries);
    output += index.zip64 ? " (ZIP64)" : "";
    output += "\n.INP files: " + std::to_string(inpCount);
    output += usedStructure ? "\nField layout: structure.info" : "\nField layout: default";
    output += "\n\nArchive contents:";
    const auto displayCount = std::min<std::size_t>(index.entries.size(), MAX_DISPLAY_ENTRIES);
    for (std::size_t i = 0; i < displayCount; ++i) output += "\n• " + index.entries[i].name;
    if (index.entries.size() > displayCount) output += "\n… +" + std::to_string(index.entries.size() - displayCount) + " more";
    if (inpCount > 0) output += CatalogText(ParseCatalog(fd, index, layout));
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_ua_flibrary_android_MainActivity_nativeImportInpx(JNIEnv* env, jobject, jint fd, jobject database) {
    if (database == nullptr) return env->NewStringUTF("ERROR: database is null");
    const auto result = ImportCatalogToDatabase(env, fd, database);
    if (env->ExceptionCheck()) return nullptr;
    return env->NewStringUTF(result.c_str());
}
