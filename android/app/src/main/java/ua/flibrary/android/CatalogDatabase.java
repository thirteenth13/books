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

    public List<String> search(String query, int limit) {
        ArrayList<String> result = new ArrayList<>();
        String needle = "%" + query.trim() + "%";
        String sql = "SELECT title, author, series, extension FROM books " +
                "WHERE title LIKE ? COLLATE NOCASE OR author LIKE ? COLLATE NOCASE OR series LIKE ? COLLATE NOCASE " +
                "ORDER BY title COLLATE NOCASE LIMIT ?";
        String[] args = {needle, needle, needle, Integer.toString(limit)};
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, args)) {
            while (cursor.moveToNext()) {
                String title = cursor.getString(0);
                String author = cursor.getString(1);
                String series = cursor.getString(2);
                String extension = cursor.getString(3);
                StringBuilder line = new StringBuilder(title == null ? "" : title);
                if (author != null && !author.isEmpty()) line.append(" — ").append(author);
                if (series != null && !series.isEmpty()) line.append("\n  ").append(series);
                if (extension != null && !extension.isEmpty()) line.append(" [").append(extension).append(']');
                result.add(line.toString());
            }
        }
        return result;
    }
}
