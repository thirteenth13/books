package ua.flibrary.android;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
        if (mode != Mode.BOOKS) { showBooks(value); return; }
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
        if (value.isEmpty() || mode != Mode.NAMES) return;
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
        LinearLayout card = new LinearLayout(parent.getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(activity.dp(13), activity.dp(9), activity.dp(13), activity.dp(9));
        card.setBackgroundResource(R.drawable.bg_book_row);
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = activity.dp(6);
        card.setLayoutParams(params);

        TextView title = textView(16, R.color.text_primary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView author = textView(14, R.color.text_secondary);
        author.setMaxLines(1);
        author.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams authorParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        authorParams.topMargin = activity.dp(2);
        card.addView(author, authorParams);

        LinearLayout metaRow = new LinearLayout(parent.getContext());
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams metaRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaRowParams.topMargin = activity.dp(4);
        card.addView(metaRow, metaRowParams);

        TextView series = textView(12, R.color.text_secondary);
        series.setMaxLines(1);
        series.setEllipsize(android.text.TextUtils.TruncateAt.END);
        metaRow.addView(series, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView badges = textView(11, R.color.primary);
        badges.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badges.setGravity(Gravity.END);
        metaRow.addView(badges, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return new RowHolder(card, title, author, series, badges);
    }

    private TextView textView(float size, int color) {
        TextView view = new TextView(activity);
        view.setTextSize(size);
        view.setTextColor(activity.getColor(color));
        view.setIncludeFontPadding(false);
        return view;
    }

    @Override
    public void onBindViewHolder(@NonNull RowHolder holder, int position) {
        holder.itemView.setOnClickListener(null);
        holder.title.setTextColor(activity.getColor(R.color.text_primary));
        holder.author.setTextColor(activity.getColor(R.color.text_secondary));
        holder.series.setTextColor(activity.getColor(R.color.text_secondary));
        holder.badges.setTextColor(activity.getColor(R.color.primary));
        holder.itemView.setBackgroundResource(R.drawable.bg_book_row);

        if (mode == Mode.BOOKS) {
            BookItem book = books.get(position);
            holder.title.setText(book.title);
            holder.author.setText(book.author.isEmpty() ? "Невідомий автор" : book.author);
            holder.author.setVisibility(View.VISIBLE);

            StringBuilder series = new StringBuilder();
            if (!book.series.isEmpty()) {
                series.append(book.series);
                if (!book.seriesNumber.isEmpty()) series.append("  •  #").append(book.seriesNumber);
            } else if (!book.genre.isEmpty()) {
                series.append(book.genre);
            }
            holder.series.setText(series);
            holder.series.setVisibility(series.length() == 0 ? View.GONE : View.VISIBLE);

            StringBuilder badges = new StringBuilder();
            if (!book.extension.isEmpty()) badges.append(book.extension.toUpperCase(Locale.ROOT));
            if (!book.language.isEmpty()) {
                if (badges.length() > 0) badges.append("  •  ");
                badges.append(book.language.toUpperCase(Locale.ROOT));
            }
            if (!book.year.isEmpty()) {
                if (badges.length() > 0) badges.append("  •  ");
                badges.append(book.year);
            }
            holder.badges.setText(badges);
            holder.badges.setVisibility(badges.length() == 0 ? View.GONE : View.VISIBLE);
            holder.itemView.setOnClickListener(v -> bookClickListener.onBookClick(book));
            return;
        }

        holder.title.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        holder.author.setVisibility(View.GONE);
        holder.series.setVisibility(View.GONE);
        holder.badges.setVisibility(View.GONE);

        if (mode == Mode.NAMES) {
            String display = names.get(position);
            holder.title.setText(display);
            holder.title.setTextSize(16);
            holder.itemView.setOnClickListener(v -> nameClickListener.onNameClick(display));
            return;
        }

        holder.title.setText(emptyText);
        holder.title.setTextSize(15);
        holder.title.setTextColor(activity.getColor(R.color.text_secondary));
        holder.itemView.setBackground(null);
    }

    static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView author;
        final TextView series;
        final TextView badges;

        RowHolder(@NonNull View itemView, TextView title, TextView author,
                  TextView series, TextView badges) {
            super(itemView);
            this.title = title;
            this.author = author;
            this.series = series;
            this.badges = badges;
        }
    }
}
