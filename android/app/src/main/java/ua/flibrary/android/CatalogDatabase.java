package ua.flibrary.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class CatalogDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "flibrary.db";
    private static final int DB_VERSION = 1;
    private static final char FIELD_SEPARATOR = '\u001f';
    private static final String BOOK_COLUMNS =
            "id, author, genre, title, series, series_no, file_name, extension, language, book_year, library_id, folder ";

    public CatalogDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "author TEXT, genre TEXT, title TEXT NOT NULL, series TEXT, series_no TEXT," +
                "file_name TEXT, file_size TEXT, library_id TEXT, deleted_flag TEXT," +
                "extension TEXT, book_date TEXT, folder TEXT, language TEXT, library_rate TEXT," +
                "keywords TEXT, book_year TEXT, source_library TEXT)");
        db.execSQL("CREATE INDEX idx_books_title ON books(title COLLATE NOCASE)");
        db.execSQL("CREATE INDEX idx_books_author ON books(author COLLATE NOCASE)");
        db.execSQL("CREATE INDEX idx_books_series ON books(series COLLATE NOCASE)");
        db.execSQL("CREATE INDEX idx_books_library_id ON books(library_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS books");
        onCreate(db);
    }

    public void beginNativeImport() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        db.delete("books", null, null);
    }

    public void insertNativeBatch(String[] rows) {
        SQLiteDatabase db = getWritableDatabase();
        for (String row : rows) {
            String[] f = row.split(String.valueOf(FIELD_SEPARATOR), -1);
            if (f.length < 17 || f[2].isEmpty()) continue;

            ContentValues values = new ContentValues(17);
            values.put("author", f[0]);
            values.put("genre", f[1]);
            values.put("title", f[2]);
            values.put("series", f[3]);
            values.put("series_no", f[4]);
            values.put("file_name", f[5]);
            values.put("file_size", f[6]);
            values.put("library_id", f[7]);
            values.put("deleted_flag", f[8]);
            values.put("extension", f[9]);
            values.put("book_date", f[10]);
            values.put("folder", f[11]);
            values.put("language", f[12]);
            values.put("library_rate", f[13]);
            values.put("keywords", f[14]);
            values.put("book_year", f[15]);
            values.put("source_library", f[16]);
            db.insertOrThrow("books", null, values);
        }
    }

    public void finishNativeImport(boolean success) {
        SQLiteDatabase db = getWritableDatabase();
        if (!db.inTransaction()) return;
        if (success) db.setTransactionSuccessful();
        db.endTransaction();
    }

    public long getBookCount() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM books", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    public List<BookItem> listBooks(int limit) {
        String sql = "SELECT " + BOOK_COLUMNS +
                "FROM books ORDER BY title COLLATE NOCASE LIMIT ?";
        return queryBooks(sql, new String[]{Integer.toString(limit)});
    }

    public List<BookItem> searchBooks(String query, int limit) {
        String needle = "%" + query.trim() + "%";
        String sql = "SELECT " + BOOK_COLUMNS +
                "FROM books WHERE title LIKE ? COLLATE NOCASE OR author LIKE ? COLLATE NOCASE OR series LIKE ? COLLATE NOCASE " +
                "ORDER BY title COLLATE NOCASE LIMIT ?";
        return queryBooks(sql, new String[]{needle, needle, needle, Integer.toString(limit)});
    }

    public List<BookItem> booksByAuthor(String author, int limit) {
        String sql = "SELECT " + BOOK_COLUMNS +
                "FROM books WHERE author = ? COLLATE NOCASE ORDER BY title COLLATE NOCASE LIMIT ?";
        return queryBooks(sql, new String[]{author, Integer.toString(limit)});
    }

    public List<BookItem> booksBySeries(String series, int limit) {
        String sql = "SELECT " + BOOK_COLUMNS +
                "FROM books WHERE series = ? COLLATE NOCASE ORDER BY CAST(series_no AS INTEGER), title COLLATE NOCASE LIMIT ?";
        return queryBooks(sql, new String[]{series, Integer.toString(limit)});
    }

    public List<String> listAuthors(int limit) {
        return queryNames("author", limit);
    }

    public List<String> listSeries(int limit) {
        return queryNames("series", limit);
    }

    private List<String> queryNames(String column, int limit) {
        ArrayList<String> result = new ArrayList<>();
        String sql = "SELECT " + column + ", COUNT(*) AS n FROM books " +
                "WHERE " + column + " IS NOT NULL AND " + column + " <> '' " +
                "GROUP BY " + column + " ORDER BY " + column + " COLLATE NOCASE LIMIT ?";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{Integer.toString(limit)})) {
            while (cursor.moveToNext()) {
                result.add(cursor.getString(0) + " (" + cursor.getLong(1) + ")");
            }
        }
        return result;
    }

    private List<BookItem> queryBooks(String sql, String[] args) {
        ArrayList<BookItem> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, args)) {
            while (cursor.moveToNext()) {
                result.add(new BookItem(
                        cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                        cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7),
                        cursor.getString(8), cursor.getString(9), cursor.getString(10), cursor.getString(11)));
            }
        }
        return result;
    }

    public List<String> search(String query, int limit) {
        ArrayList<String> result = new ArrayList<>();
        for (BookItem book : searchBooks(query, limit)) {
            StringBuilder line = new StringBuilder(book.title);
            if (!book.author.isEmpty()) line.append(" — ").append(book.author);
            if (!book.series.isEmpty()) line.append("\n  ").append(book.series);
            if (!book.extension.isEmpty()) line.append(" [").append(book.extension).append(']');
            result.add(line.toString());
        }
        return result;
    }
}
