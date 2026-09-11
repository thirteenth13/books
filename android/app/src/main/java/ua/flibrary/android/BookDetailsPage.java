package ua.flibrary.android;

import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class BookDetailsPage {
    interface Action { void run(); }
    interface ToggleAction { boolean run(); }

    private BookDetailsPage() {}

    static void show(MainActivity activity, BookItem book, boolean favorite,
                     Action openAction, Action authorAction, Action seriesAction,
                     ToggleAction favoriteAction) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(activity.getColor(R.color.app_background));

        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(activity.dp(18), activity.dp(18), activity.dp(18), activity.dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button back = new Button(activity);
        back.setText("← Назад");
        back.setAllCaps(false);
        back.setMinWidth(0);
        back.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(44));
        page.addView(back, backParams);

        TextView title = text(activity, 27, R.color.text_primary, true);
        title.setText(book.title);
        LinearLayout.LayoutParams titleParams = fullWidth();
        titleParams.topMargin = activity.dp(20);
        page.addView(title, titleParams);

        if (!book.author.isEmpty()) {
            Button author = actionButton(activity, book.author);
            LinearLayout.LayoutParams p = fullWidth();
            p.topMargin = activity.dp(12);
            page.addView(author, p);
            author.setOnClickListener(v -> {
                dialog.dismiss();
                authorAction.run();
            });
        } else {
            TextView unknown = text(activity, 16, R.color.text_secondary, false);
            unknown.setText("Невідомий автор");
            LinearLayout.LayoutParams p = fullWidth();
            p.topMargin = activity.dp(8);
            page.addView(unknown, p);
        }

        if (!book.series.isEmpty()) {
            String label = book.series + (book.seriesNumber.isEmpty() ? "" : "  ·  #" + book.seriesNumber);
            Button series = actionButton(activity, label);
            LinearLayout.LayoutParams p = fullWidth();
            p.topMargin = activity.dp(6);
            page.addView(series, p);
            series.setOnClickListener(v -> {
                dialog.dismiss();
                seriesAction.run();
            });
        }

        Button favoriteButton = new Button(activity);
        favoriteButton.setAllCaps(false);
        favoriteButton.setText(favorite ? "★ В обраному" : "☆ Додати в обране");
        LinearLayout.LayoutParams favoriteParams = fullWidth();
        favoriteParams.topMargin = activity.dp(16);
        favoriteParams.height = activity.dp(48);
        page.addView(favoriteButton, favoriteParams);
        favoriteButton.setOnClickListener(v -> {
            boolean nowFavorite = favoriteAction.run();
            favoriteButton.setText(nowFavorite ? "★ В обраному" : "☆ Додати в обране");
        });

        LinearLayout meta = new LinearLayout(activity);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12));
        meta.setBackgroundResource(R.drawable.bg_panel);
        LinearLayout.LayoutParams metaParams = fullWidth();
        metaParams.topMargin = activity.dp(16);
        page.addView(meta, metaParams);

        addMeta(activity, meta, "Жанр", book.genre);
        addMeta(activity, meta, "Мова", book.language.toUpperCase());
        addMeta(activity, meta, "Рік", book.year);
        addMeta(activity, meta, "Формат", book.extension.toUpperCase());
        addMeta(activity, meta, "ID бібліотеки", book.libraryId);

        if (!book.folder.isEmpty() || !book.fileName.isEmpty()) {
            TextView technical = text(activity, 12, R.color.text_secondary, false);
            StringBuilder value = new StringBuilder();
            if (!book.folder.isEmpty()) value.append("Архів: ").append(book.folder);
            if (!book.fileName.isEmpty()) {
                if (value.length() > 0) value.append('\n');
                value.append("Файл: ").append(book.fileName);
            }
            technical.setText(value);
            LinearLayout.LayoutParams p = fullWidth();
            p.topMargin = activity.dp(16);
            page.addView(technical, p);
        }

        Button open = new Button(activity);
        open.setText("Відкрити книгу");
        open.setAllCaps(false);
        open.setTextSize(17);
        open.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams openParams = fullWidth();
        openParams.height = activity.dp(54);
        openParams.topMargin = activity.dp(24);
        page.addView(open, openParams);
        open.setOnClickListener(v -> {
            dialog.dismiss();
            openAction.run();
        });

        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(activity.getColor(R.color.app_background)));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.CENTER);
        }
        dialog.setOnShowListener(ignored -> {
            Window shown = dialog.getWindow();
            if (shown != null) shown.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        });
        dialog.show();
    }

    private static Button actionButton(MainActivity activity, String label) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setMinWidth(0);
        return button;
    }

    private static void addMeta(MainActivity activity, LinearLayout parent, String label, String value) {
        if (value == null || value.isEmpty()) return;
        TextView row = text(activity, 15, R.color.text_primary, false);
        row.setText(label + ":  " + value);
        LinearLayout.LayoutParams p = fullWidth();
        p.bottomMargin = activity.dp(7);
        parent.addView(row, p);
    }

    private static TextView text(MainActivity activity, float size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setTextSize(size);
        view.setTextColor(activity.getColor(color));
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private static LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
