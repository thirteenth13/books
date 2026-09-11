package ua.flibrary.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CatalogDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "flibrary.db";
    private static final int DB_VERSION = 3;
    private static final char FIELD_SEPARATOR = '\u001f';
    private static final String BOOK_COLUMNS =
            "id, author, genre, title, series, series_no, file_name, extension, language, book_year, library_id, folder ";
    private static final String FAVORITE_KEY_SQL =
            "CASE WHEN library_id IS NOT NULL AND library_id <> '' THEN 'id:' || library_id " +
            "ELSE 'file:' || IFNULL(folder,'') || char(31) || IFNULL(file_name,'') END";

    public CatalogDatabase(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "author TEXT, genre TEXT, title TEXT NOT NULL, series TEXT, series_no TEXT," +
                "file_name TEXT, file_size TEXT, library_id TEXT, deleted_flag TEXT," +
                "extension TEXT, book_date TEXT, folder TEXT, language TEXT, library_rate TEXT," +
                "keywords TEXT, book_year TEXT, source_library TEXT," +
                "title_key TEXT, author_key TEXT, series_key TEXT)");
        createIndexes(db);
        createFavorites(db);
    }

    private void createIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_books_title ON books(title COLLATE NOCASE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_books_author ON books(author COLLATE NOCASE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_books_series ON books(series COLLATE NOCASE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_books_library_id ON books(library_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_books_title_key ON books(title_key)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_books_author_key ON books(author_key)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_books_series_key ON books(series_key)");
    }

    private void createFavorites(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS favorites (book_key TEXT PRIMARY KEY, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_favorites_created ON favorites(created_at DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE books ADD COLUMN title_key TEXT");
            db.execSQL("ALTER TABLE books ADD COLUMN author_key TEXT");
            db.execSQL("ALTER TABLE books ADD COLUMN series_key TEXT");
            createIndexes(db);
            backfillSearchKeys(db);
        }
        if (oldVersion < 3) createFavorites(db);
    }

    private void backfillSearchKeys(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT id, title, author, series FROM books", null)) {
            while (cursor.moveToNext()) {
                ContentValues values = new ContentValues(3);
                values.put("title_key", searchKey(cursor.getString(1)));
                values.put("author_key", searchKey(cursor.getString(2)));
                values.put("series_key", searchKey(cursor.getString(3)));
                db.update("books", values, "id = ?", new String[]{Long.toString(cursor.getLong(0))});
            }
        }
    }

    private static String searchKey(String value) {
        if (value == null || value.isEmpty()) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private static String favoriteKey(BookItem book) {
        if (book.libraryId != null && !book.libraryId.isEmpty()) return "id:" + book.libraryId;
        return "file:" + book.folder + FIELD_SEPARATOR + book.fileName;
    }

    public boolean isFavorite(BookItem book) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT 1 FROM favorites WHERE book_key = ? LIMIT 1", new String[]{favoriteKey(book)})) {
            return cursor.moveToFirst();
        }
    }

    public boolean toggleFavorite(BookItem book) {
        SQLiteDatabase db = getWritableDatabase();
        String key = favoriteKey(book);
        if (isFavorite(book)) {
            db.delete("favorites", "book_key = ?", new String[]{key});
            return false;
        }
        ContentValues values = new ContentValues(2);
        values.put("book_key", key);
        values.put("created_at", System.currentTimeMillis());
        db.insertWithOnConflict("favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }

    public List<BookItem> listFavorites(int limit, int offset) {
        String sql = "SELECT " + BOOK_COLUMNS + "FROM books WHERE " + FAVORITE_KEY_SQL +
                " IN (SELECT book_key FROM favorites) ORDER BY " +
                "(SELECT created_at FROM favorites WHERE book_key = " + FAVORITE_KEY_SQL + ") DESC, id LIMIT ? OFFSET ?";
        return queryBooks(sql, new String[]{Integer.toString(limit), Integer.toString(offset)});
    }

    public void beginNativeImport() { SQLiteDatabase db = getWritableDatabase(); db.beginTransaction(); db.delete("books", null, null); }
    public void insertNativeBatch(String[] rows) {
        SQLiteDatabase db = getWritableDatabase();
        for (String row : rows) {
            String[] f = row.split(String.valueOf(FIELD_SEPARATOR), -1);
            if (f.length < 17 || f[2].isEmpty()) continue;
            ContentValues values = new ContentValues(20);
            values.put("author", f[0]); values.put("genre", f[1]); values.put("title", f[2]); values.put("series", f[3]); values.put("series_no", f[4]);
            values.put("file_name", f[5]); values.put("file_size", f[6]); values.put("library_id", f[7]); values.put("deleted_flag", f[8]); values.put("extension", f[9]);
            values.put("book_date", f[10]); values.put("folder", f[11]); values.put("language", f[12]); values.put("library_rate", f[13]); values.put("keywords", f[14]);
            values.put("book_year", f[15]); values.put("source_library", f[16]); values.put("author_key", searchKey(f[0])); values.put("title_key", searchKey(f[2])); values.put("series_key", searchKey(f[3]));
            db.insertOrThrow("books", null, values);
        }
    }
    public void finishNativeImport(boolean success) { SQLiteDatabase db=getWritableDatabase(); if(!db.inTransaction())return; if(success)db.setTransactionSuccessful(); db.endTransaction(); }
    public long getBookCount() { try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM books",null)){return c.moveToFirst()?c.getLong(0):0;} }
    public List<BookItem> listBooks(int limit){return listBooks(limit,0);} public List<BookItem> listBooks(int limit,int offset){return queryBooks("SELECT "+BOOK_COLUMNS+"FROM books ORDER BY title COLLATE NOCASE, id LIMIT ? OFFSET ?",new String[]{Integer.toString(limit),Integer.toString(offset)});}
    public List<BookItem> searchBooks(String query,int limit){return searchBooks(query,limit,0);} public List<BookItem> searchBooks(String query,int limit,int offset){String n="%"+searchKey(query)+"%";return queryBooks("SELECT "+BOOK_COLUMNS+"FROM books WHERE title_key LIKE ? OR author_key LIKE ? OR series_key LIKE ? ORDER BY title COLLATE NOCASE, id LIMIT ? OFFSET ?",new String[]{n,n,n,Integer.toString(limit),Integer.toString(offset)});}
    public List<BookItem> booksByAuthor(String author,int limit){return booksByAuthor(author,limit,0);} public List<BookItem> booksByAuthor(String author,int limit,int offset){return queryBooks("SELECT "+BOOK_COLUMNS+"FROM books WHERE author_key = ? ORDER BY title COLLATE NOCASE, id LIMIT ? OFFSET ?",new String[]{searchKey(author),Integer.toString(limit),Integer.toString(offset)});}
    public List<BookItem> booksBySeries(String series,int limit){return booksBySeries(series,limit,0);} public List<BookItem> booksBySeries(String series,int limit,int offset){return queryBooks("SELECT "+BOOK_COLUMNS+"FROM books WHERE series_key = ? ORDER BY CAST(series_no AS INTEGER), title COLLATE NOCASE, id LIMIT ? OFFSET ?",new String[]{searchKey(series),Integer.toString(limit),Integer.toString(offset)});}
    public List<String> listAuthors(int limit){return listAuthors(limit,0);} public List<String> listAuthors(int limit,int offset){return queryNames("author",limit,offset);} public List<String> listSeries(int limit){return listSeries(limit,0);} public List<String> listSeries(int limit,int offset){return queryNames("series",limit,offset);}
    private List<String> queryNames(String column,int limit,int offset){ArrayList<String> result=new ArrayList<>();String sql="SELECT "+column+", COUNT(*) AS n FROM books WHERE "+column+" IS NOT NULL AND "+column+" <> '' GROUP BY "+column+" ORDER BY "+column+" COLLATE NOCASE LIMIT ? OFFSET ?";try(Cursor c=getReadableDatabase().rawQuery(sql,new String[]{Integer.toString(limit),Integer.toString(offset)})){while(c.moveToNext())result.add(c.getString(0)+" ("+c.getLong(1)+")");}return result;}
    private List<BookItem> queryBooks(String sql,String[] args){ArrayList<BookItem> result=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery(sql,args)){while(c.moveToNext())result.add(new BookItem(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6),c.getString(7),c.getString(8),c.getString(9),c.getString(10),c.getString(11)));}return result;}
    public List<String> search(String query,int limit){ArrayList<String> result=new ArrayList<>();for(BookItem b:searchBooks(query,limit)){StringBuilder line=new StringBuilder(b.title);if(!b.author.isEmpty())line.append(" — ").append(b.author);if(!b.series.isEmpty())line.append("\n  ").append(b.series);if(!b.extension.isEmpty())line.append(" [").append(b.extension).append(']');result.add(line.toString());}return result;}
}
