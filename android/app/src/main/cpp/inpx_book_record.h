#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace flibrary::android {

constexpr char kInpFieldSeparator = '\x04';
constexpr std::size_t kInpBookFieldCount = 17;

enum class BookField : std::size_t {
    Author = 0,
    Genre,
    Title,
    Series,
    SeriesNumber,
    File,
    Size,
    LibraryId,
    Deleted,
    Extension,
    Date,
    Folder,
    Language,
    LibraryRate,
    Keywords,
    Year,
    SourceLibrary,
};

struct BookRecord {
    std::string author;
    std::string genre;
    std::string title;
    std::string series;
    std::string seriesNumber;
    std::string file;
    std::string size;
    std::string libraryId;
    std::string deleted;
    std::string extension;
    std::string date;
    std::string folder;
    std::string language;
    std::string libraryRate;
    std::string keywords;
    std::string year;
    std::string sourceLibrary;
};

inline bool IsValidUtf8(std::string_view value) {
    const auto* bytes = reinterpret_cast<const unsigned char*>(value.data());
    std::size_t i = 0;
    while (i < value.size()) {
        const unsigned char lead = bytes[i];
        if (lead <= 0x7f) {
            ++i;
            continue;
        }

        std::size_t length = 0;
        std::uint32_t codePoint = 0;
        if ((lead & 0xe0) == 0xc0) {
            length = 2;
            codePoint = lead & 0x1f;
            if (codePoint < 2) return false; // overlong ASCII
        } else if ((lead & 0xf0) == 0xe0) {
            length = 3;
            codePoint = lead & 0x0f;
        } else if ((lead & 0xf8) == 0xf0) {
            length = 4;
            codePoint = lead & 0x07;
        } else {
            return false;
        }

        if (i + length > value.size()) return false;
        for (std::size_t j = 1; j < length; ++j) {
            const unsigned char next = bytes[i + j];
            if ((next & 0xc0) != 0x80) return false;
            codePoint = (codePoint << 6) | (next & 0x3f);
        }

        if ((length == 3 && codePoint < 0x800) ||
            (length == 4 && codePoint < 0x10000) ||
            codePoint > 0x10ffff ||
            (codePoint >= 0xd800 && codePoint <= 0xdfff)) {
            return false;
        }
        i += length;
    }
    return true;
}

inline void AppendUtf8(std::string& output, std::uint32_t codePoint) {
    if (codePoint <= 0x7f) {
        output.push_back(static_cast<char>(codePoint));
    } else if (codePoint <= 0x7ff) {
        output.push_back(static_cast<char>(0xc0 | (codePoint >> 6)));
        output.push_back(static_cast<char>(0x80 | (codePoint & 0x3f)));
    } else {
        output.push_back(static_cast<char>(0xe0 | (codePoint >> 12)));
        output.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (codePoint & 0x3f)));
    }
}

inline std::uint32_t Cp1251CodePoint(unsigned char byte) {
    if (byte < 0x80) return byte;
    if (byte >= 0xc0) return 0x0410u + (byte - 0xc0u);

    static constexpr std::array<std::uint16_t, 64> table = {
        0x0402, 0x0403, 0x201a, 0x0453, 0x201e, 0x2026, 0x2020, 0x2021,
        0x20ac, 0x2030, 0x0409, 0x2039, 0x040a, 0x040c, 0x040b, 0x040f,
        0x0452, 0x2018, 0x2019, 0x201c, 0x201d, 0x2022, 0x2013, 0x2014,
        0xfffd, 0x2122, 0x0459, 0x203a, 0x045a, 0x045c, 0x045b, 0x045f,
        0x00a0, 0x040e, 0x045e, 0x0408, 0x00a4, 0x0490, 0x00a6, 0x00a7,
        0x0401, 0x00a9, 0x0404, 0x00ab, 0x00ac, 0x00ad, 0x00ae, 0x0407,
        0x00b0, 0x00b1, 0x0406, 0x0456, 0x0491, 0x00b5, 0x00b6, 0x00b7,
        0x0451, 0x2116, 0x0454, 0x00bb, 0x0458, 0x0405, 0x0455, 0x0457,
    };
    return table[byte - 0x80u];
}

inline std::string Cp1251ToUtf8(std::string_view value) {
    std::string output;
    output.reserve(value.size() * 2);
    for (unsigned char byte : value) {
        AppendUtf8(output, Cp1251CodePoint(byte));
    }
    return output;
}

inline std::string NormalizeInpText(std::string_view value) {
    if (value.empty()) return {};
    return IsValidUtf8(value) ? std::string(value) : Cp1251ToUtf8(value);
}

inline std::vector<std::string_view> SplitInpFields(std::string_view line) {
    std::vector<std::string_view> fields;
    fields.reserve(kInpBookFieldCount);

    std::size_t start = 0;
    while (start <= line.size()) {
        const auto end = line.find(kInpFieldSeparator, start);
        fields.push_back(line.substr(
                start,
                end == std::string_view::npos ? line.size() - start : end - start));
        if (end == std::string_view::npos) {
            break;
        }
        start = end + 1;
    }
    return fields;
}

inline std::string CopyField(
        const std::vector<std::string_view>& fields,
        BookField field) {
    const auto index = static_cast<std::size_t>(field);
    return index < fields.size() ? NormalizeInpText(fields[index]) : std::string{};
}

inline bool ParseBookRecord(std::string_view line, BookRecord& book) {
    const auto fields = SplitInpFields(line);
    if (fields.size() < 3) {
        return false;
    }

    book.author = CopyField(fields, BookField::Author);
    book.genre = CopyField(fields, BookField::Genre);
    book.title = CopyField(fields, BookField::Title);
    book.series = CopyField(fields, BookField::Series);
    book.seriesNumber = CopyField(fields, BookField::SeriesNumber);
    book.file = CopyField(fields, BookField::File);
    book.size = CopyField(fields, BookField::Size);
    book.libraryId = CopyField(fields, BookField::LibraryId);
    book.deleted = CopyField(fields, BookField::Deleted);
    book.extension = CopyField(fields, BookField::Extension);
    book.date = CopyField(fields, BookField::Date);
    book.folder = CopyField(fields, BookField::Folder);
    book.language = CopyField(fields, BookField::Language);
    book.libraryRate = CopyField(fields, BookField::LibraryRate);
    book.keywords = CopyField(fields, BookField::Keywords);
    book.year = CopyField(fields, BookField::Year);
    book.sourceLibrary = CopyField(fields, BookField::SourceLibrary);

    return !book.title.empty();
}

inline std::string BookSummary(const BookRecord& book) {
    std::string result = book.title;
    if (!book.author.empty()) {
        result += " — ";
        result += book.author;
    }
    if (!book.series.empty()) {
        result += "\n  Series: ";
        result += book.series;
        if (!book.seriesNumber.empty()) {
            result += " #";
            result += book.seriesNumber;
        }
    }
    if (!book.genre.empty()) {
        result += "\n  Genre: ";
        result += book.genre;
    }
    if (!book.language.empty()) {
        result += " | Lang: ";
        result += book.language;
    }
    if (!book.extension.empty()) {
        result += " | ";
        result += book.extension;
    }
    if (!book.libraryId.empty()) {
        result += " | ID: ";
        result += book.libraryId;
    }
    return result;
}

} // namespace flibrary::android
