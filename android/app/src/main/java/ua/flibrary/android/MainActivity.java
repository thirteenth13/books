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
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

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

    static {
        System.loadLibrary("flibrary_android");
    }

    private native String nativeStatus();
    private native String nativeImportInpx(int fd, CatalogDatabase database);
    private native byte[] nativeExtractBook(int fd, String fileName, String extension);

    private TextView status;
    private EditText searchInput;
    private LinearLayout results;
    private CatalogDatabase catalogDatabase;
    private BookItem pendingBook;
    private Button importButton;
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        catalogDatabase = new CatalogDatabase(this);

        int padding = dp(16);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("FLibrary Android");
        title.setTextSize(27);
        title.setGravity(Gravity.CENTER);
        content.addView(title);

        status = new TextView(this);
        updateStatus();
        status.setTextSize(14);
        status.setPadding(0, dp(8), 0, dp(8));
        content.addView(status);

        LinearLayout setup = new LinearLayout(this);
        setup.setOrientation(LinearLayout.HORIZONTAL);

        importButton = new Button(this);
        importButton.setText("Import INPX");
        importButton.setOnClickListener(v -> openInpxDocument());
        setup.addView(importButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button folderButton = new Button(this);
        folderButton.setText("Library folder");
        folderButton.setOnClickListener(v -> chooseLibraryFolder());
        setup.addView(folderButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        content.addView(setup);

        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);

        Button books = new Button(this);
        books.setText("Books");
        books.setOnClickListener(v -> showBooks(catalogDatabase.listBooks(LIST_LIMIT), "Books"));
        navigation.addView(books, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button authors = new Button(this);
        authors.setText("Authors");
        authors.setOnClickListener(v -> showNameList(catalogDatabase.listAuthors(LIST_LIMIT), true));
        navigation.addView(authors, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button series = new Button(this);
        series.setText("Series");
        series.setOnClickListener(v -> showNameList(catalogDatabase.listSeries(LIST_LIMIT), false));
        navigation.addView(series, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        content.addView(navigation);

        searchInput = new EditText(this);
        searchInput.setHint("Title, author or series");
        searchInput.setSingleLine(true);
        content.addView(searchInput);

        Button search = new Button(this);
        search.setText("Search");
        search.setOnClickListener(v -> runSearch());
        content.addView(search);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(0, dp(8), 0, 0);
        content.addView(results);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);
        setContentView(scrollView);

        showBooks(catalogDatabase.listBooks(LIST_LIMIT), "Books");
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void updateStatus() {
        status.setText("Books: " + catalogDatabase.getBookCount() +
                " • Library folder: " + (getLibraryTreeUri() == null ? "not selected" : "ready"));
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
            try {
                getContentResolver().takePersistableUriPermission(tree, flags);
            } catch (SecurityException ignored) {
            }
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
            status.setText("No document selected");
            return;
        }

        final int flags = data.getFlags() &
                (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ignored) {
        }

        startInpxImport(uri, queryDisplayName(uri));
    }

    private void startInpxImport(Uri uri, String displayName) {
        importButton.setEnabled(false);
        status.setText("Importing " + displayName + "…");

        importExecutor.execute(() -> {
            boolean transactionStarted = false;
            boolean success = false;
            String message;

            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
                if (pfd == null) {
                    message = "Cannot open selected document";
                } else {
                    catalogDatabase.beginNativeImport();
                    transactionStarted = true;
                    String result = nativeImportInpx(pfd.getFd(), catalogDatabase);
                    success = result != null && result.startsWith("OK:");
                    message = result == null ? "Native import failed" : result;
                }
            } catch (IOException | RuntimeException e) {
                message = "Import failed: " + e.getMessage();
            }

            if (transactionStarted) {
                try {
                    catalogDatabase.finishNativeImport(success);
                } catch (RuntimeException e) {
                    message = "Database finalize failed: " + e.getMessage();
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
                    showBooks(catalogDatabase.listBooks(LIST_LIMIT), "Books");
                } else {
                    status.setText(finalMessage);
                }
            });
        });
    }

    private void runSearch() {
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            showBooks(catalogDatabase.listBooks(LIST_LIMIT), "Books");
            return;
        }
        showBooks(catalogDatabase.searchBooks(query, LIST_LIMIT), "Search: " + query);
    }

    private void showBooks(List<BookItem> books, String heading) {
        results.removeAllViews();
        addHeading(heading + " • " + books.size());
        for (BookItem book : books) {
            TextView row = new TextView(this);
            row.setText(book.title + (book.subtitle().isEmpty() ? "" : "\n" + book.subtitle()));
            row.setTextSize(16);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setOnClickListener(v -> showBookDetails(book));
            results.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        if (books.isEmpty()) addEmpty("No books found");
    }

    private void showNameList(List<String> names, boolean authors) {
        results.removeAllViews();
        addHeading((authors ? "Authors" : "Series") + " • " + names.size());
        for (String display : names) {
            String name = stripCount(display);
            TextView row = new TextView(this);
            row.setText(display);
            row.setTextSize(17);
            row.setPadding(dp(10), dp(12), dp(10), dp(12));
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setOnClickListener(v -> {
                List<BookItem> books = authors
                        ? catalogDatabase.booksByAuthor(name, LIST_LIMIT)
                        : catalogDatabase.booksBySeries(name, LIST_LIMIT);
                showBooks(books, name);
            });
            results.addView(row);
        }
        if (names.isEmpty()) addEmpty(authors ? "No authors" : "No series");
    }

    private String stripCount(String display) {
        int marker = display.lastIndexOf(" (");
        return marker > 0 && display.endsWith(")") ? display.substring(0, marker) : display;
    }

    private void addHeading(String text) {
        TextView heading = new TextView(this);
        heading.setText(text);
        heading.setTextSize(20);
        heading.setPadding(0, dp(8), 0, dp(8));
        results.addView(heading);
    }

    private void addEmpty(String text) {
        TextView empty = new TextView(this);
        empty.setText(text);
        empty.setTextSize(15);
        empty.setPadding(0, dp(12), 0, dp(12));
        results.addView(empty);
    }

    private void showBookDetails(BookItem book) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(book.title)
                .setMessage(book.details())
                .setPositiveButton("Open", (dialog, which) -> openBook(book));
        if (!book.author.isEmpty()) {
            builder.setNeutralButton("Author", (dialog, which) ->
                    showBooks(catalogDatabase.booksByAuthor(book.author, LIST_LIMIT), book.author));
        }
        if (!book.series.isEmpty()) {
            builder.setNegativeButton("Series", (dialog, which) ->
                    showBooks(catalogDatabase.booksBySeries(book.series, LIST_LIMIT), book.series));
        } else {
            builder.setNegativeButton("Close", null);
        }
        builder.show();
    }

    private Uri getLibraryTreeUri() {
        String value = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_LIBRARY_TREE, null);
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
            showError("Catalog record does not contain archive/file information.");
            return;
        }

        DocumentFile archive = findArchive(book);
        if (archive == null) {
            showError("Archive not found: " + book.folder +
                    "\nSelect the library root or the folder that contains FLibrary archives.");
            return;
        }

        status.setText("Opening " + book.title + "…");
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(archive.getUri(), "r")) {
            if (pfd == null) {
                showError("Cannot open archive: " + book.folder);
                return;
            }
            byte[] data = nativeExtractBook(pfd.getFd(), book.fileName, book.extension);
            if (data == null || data.length == 0) {
                showError("Book file was not found in archive: " + book.fileName);
                return;
            }

            File booksDir = new File(getCacheDir(), "books");
            if (!booksDir.exists() && !booksDir.mkdirs()) {
                showError("Cannot create book cache folder.");
                return;
            }
            File output = new File(booksDir, safeFileName(book.outputFileName()));
            try (FileOutputStream stream = new FileOutputStream(output, false)) {
                stream.write(data);
            }

            Uri contentUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".files", output);
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(contentUri, mimeType(book.extension))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(view, "Open book"));
            updateStatus();
        } catch (ActivityNotFoundException e) {
            showError("No application is installed that can open " +
                    book.extension.toUpperCase(Locale.ROOT) + " files.");
        } catch (IOException | RuntimeException e) {
            showError("Open failed: " + e.getMessage());
        }
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
        new AlertDialog.Builder(this)
                .setTitle("FLibrary")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
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
        importExecutor.shutdownNow();
        if (catalogDatabase != null) catalogDatabase.close();
        super.onDestroy();
    }
}
