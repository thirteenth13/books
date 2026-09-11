package ua.flibrary.android;

import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class CatalogAdapter extends RecyclerView.Adapter<CatalogAdapter.RowHolder> {
    interface BookClickListener { void onBookClick(BookItem book); }
    interface NameClickListener { void onNameClick(String name); }

    private enum Mode { BOOKS, NAMES, EMPTY }

    private final MainActivity activity;
    private final BookClickListener bookClickListener;
    private final NameClickListener nameClickListener;
    private Mode mode = Mode.EMPTY;
    private List<BookItem> books = Collections.emptyList();
    private List<String> names = Collections.emptyList();
    private String emptyText = "Нічого не знайдено";

    CatalogAdapter(MainActivity activity,
                   BookClickListener bookClickListener,
                   NameClickListener nameClickListener) {
        this.activity = activity;
        this.bookClickListener = bookClickListener;
        this.nameClickListener = nameClickListener;
        setHasStableIds(true);
    }

    void showBooks(List<BookItem> value) {
        mode = value.isEmpty() ? Mode.EMPTY : Mode.BOOKS;
        books = new ArrayList<>(value);
        names = Collections.emptyList();
        emptyText = "Нічого не знайдено";
        notifyDataSetChanged();
    }

    void appendBooks(List<BookItem> value) {
        if (value.isEmpty()) return;
        if (mode != Mode.BOOKS) {
            showBooks(value);
            return;
        }
        int start = books.size();
        books.addAll(value);
        notifyItemRangeInserted(start, value.size());
    }

    void showNames(List<String> value, String empty) {
        mode = value.isEmpty() ? Mode.EMPTY : Mode.NAMES;
        names = new ArrayList<>(value);
        books = Collections.emptyList();
        emptyText = empty;
        notifyDataSetChanged();
    }

    void appendNames(List<String> value) {
        if (value.isEmpty()) return;
        if (mode != Mode.NAMES) return;
        int start = names.size();
        names.addAll(value);
        notifyItemRangeInserted(start, value.size());
    }

    int dataSize() {
        if (mode == Mode.BOOKS) return books.size();
        if (mode == Mode.NAMES) return names.size();
        return 0;
    }

    @Override
    public long getItemId(int position) {
        if (mode == Mode.BOOKS) return books.get(position).id;
        if (mode == Mode.NAMES) return names.get(position).hashCode();
        return Long.MIN_VALUE;
    }

    @Override
    public int getItemCount() {
        if (mode == Mode.BOOKS) return books.size();
        if (mode == Mode.NAMES) return names.size();
        return 1;
    }

    @NonNull
    @Override
    public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        TextView row = new TextView(parent.getContext());
        row.setTextColor(activity.getColor(R.color.text_primary));
        row.setTextSize(16);
        row.setLineSpacing(0, 1.08f);
        row.setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12));
        row.setBackgroundResource(R.drawable.bg_book_row);
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = activity.dp(8);
        row.setLayoutParams(params);
        return new RowHolder(row);
    }

    @Override
    public void onBindViewHolder(@NonNull RowHolder holder, int position) {
        TextView row = holder.row;
        row.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        row.setTextColor(activity.getColor(R.color.text_primary));
        row.setTextSize(16);
        row.setBackgroundResource(R.drawable.bg_book_row);
        row.setOnClickListener(null);

        if (mode == Mode.BOOKS) {
            BookItem book = books.get(position);
            String subtitle = book.subtitle();
            row.setText(book.title + (subtitle.isEmpty() ? "" : "\n" + subtitle));
            row.setOnClickListener(v -> bookClickListener.onBookClick(book));
            return;
        }

        if (mode == Mode.NAMES) {
            String display = names.get(position);
            row.setText(display);
            row.setTextSize(17);
            row.setOnClickListener(v -> nameClickListener.onNameClick(display));
            return;
        }

        row.setText(emptyText);
        row.setTextSize(15);
        row.setTextColor(activity.getColor(R.color.text_secondary));
        row.setBackground(null);
    }

    static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView row;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            row = (TextView) itemView;
        }
    }
}
