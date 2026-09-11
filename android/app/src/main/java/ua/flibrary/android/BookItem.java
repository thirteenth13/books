package ua.flibrary.android;

public final class BookItem {
    public final long id;
    public final String author;
    public final String genre;
    public final String title;
    public final String series;
    public final String seriesNumber;
    public final String fileName;
    public final String extension;
    public final String language;
    public final String year;
    public final String libraryId;
    public final String folder;

    public BookItem(long id, String author, String genre, String title, String series,
                    String seriesNumber, String fileName, String extension,
                    String language, String year, String libraryId, String folder) {
        this.id = id;
        this.author = value(author);
        this.genre = value(genre);
        this.title = value(title);
        this.series = value(series);
        this.seriesNumber = value(seriesNumber);
        this.fileName = value(fileName);
        this.extension = value(extension);
        this.language = value(language);
        this.year = value(year);
        this.libraryId = value(libraryId);
        this.folder = value(folder);
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }

    public String subtitle() {
        StringBuilder text = new StringBuilder();
        if (!author.isEmpty()) text.append(author);
        if (!series.isEmpty()) {
            if (text.length() > 0) text.append(" • ");
            text.append(series);
            if (!seriesNumber.isEmpty()) text.append(" #").append(seriesNumber);
        }
        if (!extension.isEmpty()) {
            if (text.length() > 0) text.append(" • ");
            text.append(extension.toUpperCase());
        }
        return text.toString();
    }

    public String details() {
        StringBuilder text = new StringBuilder(title);
        if (!author.isEmpty()) text.append("\n\nAuthor: ").append(author);
        if (!series.isEmpty()) {
            text.append("\nSeries: ").append(series);
            if (!seriesNumber.isEmpty()) text.append(" #").append(seriesNumber);
        }
        if (!genre.isEmpty()) text.append("\nGenre: ").append(genre);
        if (!language.isEmpty()) text.append("\nLanguage: ").append(language);
        if (!year.isEmpty()) text.append("\nYear: ").append(year);
        if (!extension.isEmpty()) text.append("\nFormat: ").append(extension);
        if (!folder.isEmpty()) text.append("\nArchive: ").append(folder);
        if (!fileName.isEmpty()) text.append("\nFile: ").append(fileName);
        if (!libraryId.isEmpty()) text.append("\nLibrary ID: ").append(libraryId);
        return text.toString();
    }

    public String outputFileName() {
        if (fileName.isEmpty()) return "book" + (extension.isEmpty() ? "" : "." + extension);
        if (extension.isEmpty() || fileName.toLowerCase().endsWith("." + extension.toLowerCase())) {
            return fileName;
        }
        return fileName + "." + extension;
    }
}
