#include <jni.h>
#include <unistd.h>
#include <zlib.h>

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
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
constexpr std::size_t ZIP_EOCD_MIN_SIZE = 22;
constexpr std::size_t ZIP_MAX_COMMENT = 65535;
constexpr std::size_t ZIP_CENTRAL_HEADER_SIZE = 46;
constexpr std::size_t ZIP_LOCAL_HEADER_SIZE = 30;
constexpr std::size_t MAX_DISPLAY_ENTRIES = 20;
constexpr std::size_t MAX_BOOK_PREVIEW = 8;
constexpr std::size_t MAX_INP_UNCOMPRESSED = 64 * 1024 * 1024;
constexpr std::size_t MAX_CATALOG_BOOKS = 2'000'000;
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

std::string BuildStatus() {
    std::string status = "C++/JNI bridge is running (arm64-v8a)";
    status += "\nFLibrary INPX core headers connected";
    status += "\nINPX extension: ";
    status += INPX_EXT;
    status += " | INP extension: ";
    status += INP_EXT;
    status += "\nZIP deflate support: zlib";
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
        result.error = fileSize < 0 ? "Document provider does not expose a seekable file descriptor" : "ZIP archive is too small";
        return result;
    }

    const std::size_t tailSize = static_cast<std::size_t>(std::min<off_t>(fileSize, static_cast<off_t>(ZIP_EOCD_MIN_SIZE + ZIP_MAX_COMMENT)));
    const off_t tailOffset = fileSize - static_cast<off_t>(tailSize);
    std::vector<std::uint8_t> tail(tailSize);
    if (!ReadExactAt(fd, tail.data(), tail.size(), tailOffset)) {
        result.error = "Cannot read ZIP end-of-central-directory record";
        return result;
    }

    std::size_t eocd = tail.size();
    for (std::size_t i = tail.size() - ZIP_EOCD_MIN_SIZE + 1; i-- > 0;) {
        if (ReadLe32(tail.data() + i) == ZIP_EOCD_SIGNATURE) { eocd = i; break; }
    }
    if (eocd == tail.size()) { result.error = "ZIP central directory was not found"; return result; }

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
    if (static_cast<std::uint64_t>(centralOffset) + centralSize > static_cast<std::uint64_t>(fileSize)) {
        result.error = "Invalid ZIP central directory bounds";
        return result;
    }

    off_t cursor = static_cast<off_t>(centralOffset);
    std::array<std::uint8_t, ZIP_CENTRAL_HEADER_SIZE> header{};
    result.entries.reserve(result.totalEntries);
    for (std::uint16_t i = 0; i < result.totalEntries; ++i) {
        if (!ReadExactAt(fd, header.data(), header.size(), cursor) || ReadLe32(header.data()) != ZIP_CENTRAL_FILE_SIGNATURE) {
            result.error = "Invalid ZIP central directory entry";
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
        if (nameLength > 0 && !ReadExactAt(fd, entry.name.data(), entry.name.size(), cursor + ZIP_CENTRAL_HEADER_SIZE)) {
            result.error = "Cannot read ZIP entry name";
            return result;
        }
        result.entries.push_back(std::move(entry));
        cursor += static_cast<off_t>(ZIP_CENTRAL_HEADER_SIZE) + nameLength + extraLength + commentLength;
    }
    result.ok = true;
    return result;
}

bool ExtractEntry(int fd, const ZipEntry& entry, std::string& output, std::string& error) {
    if (entry.uncompressedSize > MAX_INP_UNCOMPRESSED) { error = "ZIP entry is too large"; return false; }
    std::array<std::uint8_t, ZIP_LOCAL_HEADER_SIZE> header{};
    if (!ReadExactAt(fd, header.data(), header.size(), entry.localHeaderOffset) || ReadLe32(header.data()) != ZIP_LOCAL_FILE_SIGNATURE) {
        error = "Cannot read ZIP local header"; return false;
    }
    const std::uint16_t nameLength = ReadLe16(header.data() + 26);
    const std::uint16_t extraLength = ReadLe16(header.data() + 28);
    const off_t dataOffset = static_cast<off_t>(entry.localHeaderOffset) + ZIP_LOCAL_HEADER_SIZE + nameLength + extraLength;
    std::vector<std::uint8_t> compressed(entry.compressedSize);
    if (!compressed.empty() && !ReadExactAt(fd, compressed.data(), compressed.size(), dataOffset)) { error = "Cannot read compressed ZIP data"; return false; }

    output.assign(entry.uncompressedSize, '\0');
    if (entry.method == 0) {
        if (entry.compressedSize != entry.uncompressedSize) { error = "Stored ZIP entry has inconsistent size"; return false; }
        output.assign(reinterpret_cast<const char*>(compressed.data()), compressed.size());
        return true;
    }
    if (entry.method != 8) { error = "Unsupported ZIP compression method: " + std::to_string(entry.method); return false; }

    z_stream stream{};
    stream.next_in = compressed.data();
    stream.avail_in = static_cast<uInt>(compressed.size());
    stream.next_out = reinterpret_cast<Bytef*>(output.data());
    stream.avail_out = static_cast<uInt>(output.size());
    if (inflateInit2(&stream, -MAX_WBITS) != Z_OK) { error = "zlib initialization failed"; return false; }
    const int code = inflate(&stream, Z_FINISH);
    inflateEnd(&stream);
    if (code != Z_STREAM_END) { error = "zlib failed to decompress ZIP entry"; return false; }
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
        if (EqualsIgnoreCase(base, STRUCTURE_INFO_NAME)) { structureEntry = &entry; break; }
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
        if (value == nullptr) { env->DeleteLocalRef(array); return false; }
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
        if (!ExtractEntry(fd, entry, inp, error)) { ++inpFailed; continue; }
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
    return "OK: imported " + std::to_string(imported) + " books from " + std::to_string(inpParsed) + " INP files" +
           (usedStructure ? " using structure.info" : " using default field layout") +
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
    if (!zipSignature) { output += "Not an INPX-compatible ZIP archive"; return env->NewStringUTF(output.c_str()); }

    const auto index = ReadZipIndex(fd);
    if (!index.ok) { output += "ZIP detected, but index read failed: " + index.error; return env->NewStringUTF(output.c_str()); }

    bool usedStructure = false;
    const auto layout = ReadFieldLayout(fd, index, &usedStructure);
    std::size_t inpCount = 0;
    for (const auto& entry : index.entries) if (EndsWithIgnoreCase(entry.name, INP_EXT)) ++inpCount;
    output += inpxName ? "INPX container detected" : "ZIP container detected (filename is not .inpx)";
    output += "\nEntries: " + std::to_string(index.totalEntries);
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
