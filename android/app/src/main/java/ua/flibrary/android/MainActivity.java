package ua.flibrary.android;

import android.app.Activity;
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

import java.io.IOException;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int OPEN_INPX_REQUEST = 1001;

    static {
        System.loadLibrary("flibrary_android");
    }

    private native String nativeStatus();
    private native String nativeProbeInpx(int fd, String displayName);
    private native String nativeImportInpx(int fd, CatalogDatabase database);

    private TextView status;
    private EditText searchInput;
    private CatalogDatabase catalogDatabase;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        catalogDatabase = new CatalogDatabase(this);

        int padding = (int) (20 * getResources().getDisplayMetrics().density);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("FLibrary Android");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        content.addView(title);

        status = new TextView(this);
        status.setText(nativeStatus() + "\nStored books: " + catalogDatabase.getBookCount());
        status.setTextSize(15);
        status.setPadding(0, padding, 0, padding);
        content.addView(status);

        Button openInpx = new Button(this);
        openInpx.setText("Import INPX");
        openInpx.setOnClickListener(v -> openInpxDocument());
        content.addView(openInpx);

        searchInput = new EditText(this);
        searchInput.setHint("Title, author or series");
        searchInput.setSingleLine(true);
        content.addView(searchInput);

        Button search = new Button(this);
        search.setText("Search catalog");
        search.setOnClickListener(v -> runSearch());
        content.addView(search);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);
        setContentView(scrollView);
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
        if (requestCode != OPEN_INPX_REQUEST || resultCode != RESULT_OK || data == null) {
            return;
        }

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
            // Some providers grant access only for the current session.
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
            status.setText((result == null ? "Native import failed" : result) +
                    "\nStored books: " + (success ? catalogDatabase.getBookCount() : 0));
        } catch (IOException | RuntimeException e) {
            status.setText("Import failed: " + e.getMessage());
        } finally {
            if (transactionStarted) {
                try {
                    catalogDatabase.finishNativeImport(success);
                } catch (RuntimeException e) {
                    status.setText("Database finalize failed: " + e.getMessage());
                }
            }
            if (success) {
                status.append("\nStored books: " + catalogDatabase.getBookCount());
            }
        }
    }

    private void runSearch() {
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            status.setText("Enter a title, author or series.\nStored books: " + catalogDatabase.getBookCount());
            return;
        }

        List<String> results = catalogDatabase.search(query, 50);
        StringBuilder text = new StringBuilder();
        text.append("Search: ").append(query)
                .append("\nResults: ").append(results.size())
                .append("\nStored books: ").append(catalogDatabase.getBookCount());
        for (String result : results) {
            text.append("\n\n• ").append(result);
        }
        status.setText(text.toString());
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null) {
                        return name;
                    }
                }
            }
        }
        return uri.getLastPathSegment() == null ? "document" : uri.getLastPathSegment();
    }

    @Override
    protected void onDestroy() {
        if (catalogDatabase != null) {
            catalogDatabase.close();
        }
        super.onDestroy();
    }
}
