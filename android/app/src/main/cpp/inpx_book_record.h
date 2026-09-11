#pragma once

#include <array>
#include <cstddef>
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
    return index < fields.size() ? std::string(fields[index]) : std::string{};
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
