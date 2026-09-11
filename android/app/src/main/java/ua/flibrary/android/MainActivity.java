package ua.flibrary.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int OPEN_INPX_REQUEST = 1001;
    private static final int LIST_LIMIT = 100;

    static {
        System.loadLibrary("flibrary_android");
    }

    private native String nativeStatus();
    private native String nativeImportInpx(int fd, CatalogDatabase database);

    private TextView status;
    private EditText searchInput;
    private LinearLayout results;
    private CatalogDatabase catalogDatabase;

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
        status.setText("Books: " + catalogDatabase.getBookCount());
        status.setTextSize(14);
        status.setPadding(0, dp(8), 0, dp(8));
        content.addView(status);

        Button importButton = new Button(this);
        importButton.setText("Import INPX");
        importButton.setOnClickListener(v -> openInpxDocument());
        content.addView(importButton);

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

    private void openInpxDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, OPEN_INPX_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != OPEN_INPX_REQUEST || resultCode != RESULT_OK || data == null) return;

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

        String displayName = queryDisplayName(uri);
        boolean transactionStarted = false;
        boolean success = false;
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                status.setText("Cannot open selected document");
                return;
            }

            status.setText("Importing " + displayName + "…");
            catalogDatabase.beginNativeImport();
            transactionStarted = true;

            String result = nativeImportInpx(pfd.getFd(), catalogDatabase);
            success = result != null && result.startsWith("OK:");
            status.setText(result == null ? "Native import failed" : result);
        } catch (IOException | RuntimeException e) {
            status.setText("Import failed: " + e.getMessage());
        } finally {
            if (transactionStarted) {
                try {
                    catalogDatabase.finishNativeImport(success);
                } catch (RuntimeException e) {
                    status.setText("Database finalize failed: " + e.getMessage());
                    success = false;
                }
            }
            if (success) {
                status.setText("Imported. Books: " + catalogDatabase.getBookCount());
                showBooks(catalogDatabase.listBooks(LIST_LIMIT), "Books");
            }
        }
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
                .setPositiveButton("Close", null);
        if (!book.author.isEmpty()) {
            builder.setNeutralButton("Author", (dialog, which) ->
                    showBooks(catalogDatabase.booksByAuthor(book.author, LIST_LIMIT), book.author));
        }
        if (!book.series.isEmpty()) {
            builder.setNegativeButton("Series", (dialog, which) ->
                    showBooks(catalogDatabase.booksBySeries(book.series, LIST_LIMIT), book.series));
        }
        builder.show();
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
        if (catalogDatabase != null) catalogDatabase.close();
        super.onDestroy();
    }
}
