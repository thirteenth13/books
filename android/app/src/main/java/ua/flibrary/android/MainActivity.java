package ua.flibrary.android;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;

public final class MainActivity extends Activity {
    private static final int OPEN_INPX_REQUEST = 1001;

    static {
        System.loadLibrary("flibrary_android");
    }

    private native String nativeStatus();
    private native String nativeProbeInpx(int fd, String displayName);

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("FLibrary Android");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);

        status = new TextView(this);
        status.setText(nativeStatus());
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, padding, 0, padding);

        Button openInpx = new Button(this);
        openInpx.setText("Open INPX");
        openInpx.setOnClickListener(v -> openInpxDocument());

        root.addView(title);
        root.addView(status);
        root.addView(openInpx);
        setContentView(root);
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
            // Some document providers grant access only for the current session.
        }

        String displayName = queryDisplayName(uri);
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                status.setText("Cannot open selected document");
                return;
            }
            status.setText(nativeProbeInpx(pfd.getFd(), displayName));
        } catch (IOException | SecurityException e) {
            status.setText("Open failed: " + e.getMessage());
        }
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
}
