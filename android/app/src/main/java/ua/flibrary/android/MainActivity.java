package ua.flibrary.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int OPEN_INPX_REQUEST = 1001;
    private static final int OPEN_LIBRARY_FOLDER_REQUEST = 1002;
    private static final int LIST_LIMIT = 100;
    private static final String PREFS = "flibrary";
    private static final String PREF_LIBRARY_TREE = "library_tree";

    static { System.loadLibrary("flibrary_android"); }

    private native String nativeStatus();
    private native String nativeImportInpx(int fd, CatalogDatabase database);
    private native byte[] nativeExtractBook(int fd, String fileName, String extension);

    private TextView status;
    private TextView resultsHeading;
    private EditText searchInput;
    private RecyclerView resultsList;
    private CatalogAdapter catalogAdapter;
    private CatalogDatabase catalogDatabase;
    private BookItem pendingBook;
    private Button importButton;
    private boolean namesAreAuthors;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        catalogDatabase = new CatalogDatabase(this);

        status = findViewById(R.id.status);
        resultsHeading = findViewById(R.id.results_heading);
        searchInput = findViewById(R.id.search_input);
        resultsList = findViewById(R.id.results_list);
        importButton = findViewById(R.id.import_button);

        catalogAdapter = new CatalogAdapter(this, this::showBookDetails, this::openName);
        resultsList.setLayoutManager(new LinearLayoutManager(this));
        resultsList.setHasFixedSize(false);
        resultsList.setAdapter(catalogAdapter);

        Button folderButton = findViewById(R.id.folder_button);
        Button booksButton = findViewById(R.id.books_button);
        Button authorsButton = findViewById(R.id.authors_button);
        Button seriesButton = findViewById(R.id.series_button);
        Button searchButton = findViewById(R.id.search_button);

        importButton.setOnClickListener(v -> openInpxDocument());
        folderButton.setOnClickListener(v -> chooseLibraryFolder());
        booksButton.setOnClickListener(v -> showBooks(catalogDatabase.listBooks(LIST_LIMIT), "Книги"));
        authorsButton.setOnClickListener(v -> showNameList(catalogDatabase.listAuthors(LIST_LIMIT), true));
        seriesButton.setOnClickListener(v -> showNameList(catalogDatabase.listSeries(LIST_LIMIT), false));
        searchButton.setOnClickListener(v -> runSearch());
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                runSearch();
                return true;
            }
            return false;
        });

        updateStatus();
        showBooks(catalogDatabase.listBooks(LIST_LIMIT), "Книги");
    }

    int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void updateStatus() {
        status.setText("Книг: " + catalogDatabase.getBookCount() +
                "  •  Папка: " + (getLibraryTreeUri() == null ? "не вибрана" : "готова"));
    }

    private void openInpxDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, OPEN_INPX_REQUEST);
    }

    private void chooseLibraryFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, OPEN_LIBRARY_FOLDER_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == OPEN_LIBRARY_FOLDER_REQUEST) {
            Uri tree = data.getData();
            if (tree == null) return;
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            try { getContentResolver().takePersistableUriPermission(tree, flags); }
            catch (SecurityException ignored) {}
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_LIBRARY_TREE, tree.toString()).apply();
            updateStatus();
            if (pendingBook != null) {
                BookItem book = pendingBook;
                pendingBook = null;
                openBook(book);
            }
            return;
        }

        if (requestCode != OPEN_INPX_REQUEST) return;
        Uri uri = data.getData();
        if (uri == null) {
            status.setText("Документ не вибрано");
            return;
        }

        final int flags = data.getFlags() &
                (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(uri, flags); }
        catch (SecurityException ignored) {}
        startInpxImport(uri, queryDisplayName(uri));
    }

    private void startInpxImport(Uri uri, String displayName) {
        importButton.setEnabled(false);
        status.setText("Імпортую " + displayName + "…");

        worker.execute(() -> {
            boolean transactionStarted = false;
            boolean success = false;
            String message;

            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd == null) {
                    message = "Не вдалося відкрити вибраний INPX";
                } else {
                    catalogDatabase.beginNativeImport();
                    transactionStarted = true;
                    String result = nativeImportInpx(pfd.getFd(), catalogDatabase);
                    success = result != null && result.startsWith("OK:");
                    message = result == null ? "Помилка нативного імпорту" : result;
                }
            } catch (IOException | RuntimeException e) {
                message = "Помилка імпорту: " + safeMessage(e);
            }

            if (transactionStarted) {
                try { catalogDatabase.finishNativeImport(success); }
                catch (RuntimeException e) {
                    message = "Помилка завершення бази: " + safeMessage(e);
                    success = false;
                }
            }

            final boolean importSucceeded = success;
            final String finalMessage = message;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                importButton.setEnabled(true);
                if (importSucceeded) {
                    updateStatus();
                    showBooks(catalogDatabase.listBooks(LIST_LIMIT), "Книги");
                } else {
                    status.setText(finalMessage);
                    showErrorDialog(finalMessage);
                }
            });
        });
    }

    private void runSearch() {
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            showBooks(catalogDatabase.listBooks(LIST_LIMIT), "Книги");
            return;
        }
        showBooks(catalogDatabase.searchBooks(query, LIST_LIMIT), "Пошук: " + query);
    }

    private void showBooks(List<BookItem> books, String heading) {
        resultsHeading.setText(heading + "  •  " + books.size());
        catalogAdapter.showBooks(books);
        resultsList.scrollToPosition(0);
    }

    private void showNameList(List<String> names, boolean authors) {
        namesAreAuthors = authors;
        resultsHeading.setText((authors ? "Автори" : "Серії") + "  •  " + names.size());
        catalogAdapter.showNames(names, authors ? "Авторів не знайдено" : "Серій не знайдено");
        resultsList.scrollToPosition(0);
    }

    private void openName(String display) {
        String name = stripCount(display);
        List<BookItem> books = namesAreAuthors
                ? catalogDatabase.booksByAuthor(name, LIST_LIMIT)
                : catalogDatabase.booksBySeries(name, LIST_LIMIT);
        showBooks(books, name);
    }

    private String stripCount(String display) {
        int marker = display.lastIndexOf(" (");
        return marker > 0 && display.endsWith(")") ? display.substring(0, marker) : display;
    }

    private void showBookDetails(BookItem book) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(book.title)
                .setMessage(book.details())
                .setPositiveButton("Відкрити", (dialog, which) -> openBook(book));
        if (!book.author.isEmpty()) {
            builder.setNeutralButton("Автор", (dialog, which) ->
                    showBooks(catalogDatabase.booksByAuthor(book.author, LIST_LIMIT), book.author));
        }
        if (!book.series.isEmpty()) {
            builder.setNegativeButton("Серія", (dialog, which) ->
                    showBooks(catalogDatabase.booksBySeries(book.series, LIST_LIMIT), book.series));
        } else {
            builder.setNegativeButton("Закрити", null);
        }
        builder.show();
    }

    private Uri getLibraryTreeUri() {
        String value = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LIBRARY_TREE, null);
        return value == null ? null : Uri.parse(value);
    }

    private DocumentFile findArchive(BookItem book) {
        Uri treeUri = getLibraryTreeUri();
        if (treeUri == null || book.folder.isEmpty()) return null;
        DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
        if (root == null) return null;

        DocumentFile archive = findArchiveFrom(root, book.folder);
        if (archive != null) return archive;

        DocumentFile archives = findChildIgnoreCase(root, "archives");
        if (archives != null && archives.isDirectory()) {
            archive = findArchiveFrom(archives, book.folder);
            if (archive != null) return archive;
        }

        String normalized = normalizeArchivePath(book.folder);
        int slash = normalized.lastIndexOf('/');
        String baseName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        archive = findArchiveFrom(root, baseName);
        if (archive != null) return archive;
        return archives == null ? null : findArchiveFrom(archives, baseName);
    }

    private DocumentFile findArchiveFrom(DocumentFile root, String rawPath) {
        String path = normalizeArchivePath(rawPath);
        if (path.isEmpty()) return null;
        DocumentFile current = root;
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; ++i) {
            String part = parts[i];
            if (part.isEmpty() || ".".equals(part)) continue;
            DocumentFile next = findChildIgnoreCase(current, part);
            if (next == null && i == parts.length - 1 &&
                    !part.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                next = findChildIgnoreCase(current, part + ".zip");
            }
            if (next == null) return null;
            current = next;
        }
        return current.isFile() ? current : null;
    }

    private DocumentFile findChildIgnoreCase(DocumentFile parent, String name) {
        DocumentFile direct = parent.findFile(name);
        if (direct != null) return direct;
        for (DocumentFile child : parent.listFiles()) {
            String childName = child.getName();
            if (childName != null && childName.equalsIgnoreCase(name)) return child;
        }
        return null;
    }

    private String normalizeArchivePath(String value) {
        String path = value == null ? "" : value.trim().replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);
        while (path.startsWith("./")) path = path.substring(2);
        if (path.toLowerCase(Locale.ROOT).startsWith("archives/")) {
            path = path.substring("archives/".length());
        }
        return path;
    }

    private void openBook(BookItem book) {
        if (getLibraryTreeUri() == null) {
            pendingBook = book;
            chooseLibraryFolder();
            return;
        }
        if (book.folder.isEmpty() || book.fileName.isEmpty()) {
            showError("У записі каталогу немає даних про архів або файл книги.");
            return;
        }
        status.setText("Відкриваю «" + book.title + "»…");
        worker.execute(() -> openBookInBackground(book));
    }

    private void openBookInBackground(BookItem book) {
        try {
            DocumentFile archive = findArchive(book);
            if (archive == null) {
                postError("Архів не знайдено: " + book.folder +
                        "\nВибери корінь бібліотеки або папку з архівами FLibrary.");
                return;
            }

            File output;
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(archive.getUri(), "r")) {
                if (pfd == null) {
                    postError("Не вдалося відкрити архів: " + book.folder);
                    return;
                }
                byte[] data = nativeExtractBook(pfd.getFd(), book.fileName, book.extension);
                if (data == null || data.length == 0) {
                    postError("Файл книги не знайдено в архіві: " + book.fileName);
                    return;
                }

                File booksDir = new File(getCacheDir(), "books");
                if (!booksDir.exists() && !booksDir.mkdirs()) {
                    postError("Не вдалося створити кеш для книг.");
                    return;
                }
                output = new File(booksDir, safeFileName(book.outputFileName()));
                try (FileOutputStream stream = new FileOutputStream(output, false)) { stream.write(data); }
            }

            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".files", output);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Intent view = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(contentUri, mimeType(book.extension))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(Intent.createChooser(view, "Відкрити книгу"));
                    updateStatus();
                } catch (ActivityNotFoundException e) {
                    showError("Немає застосунку для відкриття ." + book.extension.toLowerCase(Locale.ROOT));
                }
            });
        } catch (IOException | RuntimeException e) {
            postError("Помилка відкриття: " + safeMessage(e));
        }
    }

    private void postError(String message) {
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) showError(message); });
    }

    private String safeFileName(String value) {
        String safe = value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? "book.bin" : safe;
    }

    private String mimeType(String extension) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        switch (ext) {
            case "epub": return "application/epub+zip";
            case "fb2": return "application/x-fictionbook+xml";
            case "pdf": return "application/pdf";
            case "txt": return "text/plain";
            case "mobi": return "application/x-mobipocket-ebook";
            default: return "application/octet-stream";
        }
    }

    private void showError(String message) {
        status.setText(message);
        showErrorDialog(message);
    }

    private void showErrorDialog(String message) {
        new AlertDialog.Builder(this).setTitle("FLibrary").setMessage(message)
                .setPositiveButton("OK", null).show();
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null) return name;
                }
            }
        }
        return uri.getLastPathSegment() == null ? "document" : uri.getLastPathSegment();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        if (catalogDatabase != null) catalogDatabase.close();
        super.onDestroy();
    }
}
