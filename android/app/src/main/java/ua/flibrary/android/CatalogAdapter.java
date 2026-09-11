package ua.flibrary.android;

import android.graphics.Typeface;
import android.text.TextUtils;
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

    CatalogAdapter(MainActivity activity, BookClickListener bookClickListener, NameClickListener nameClickListener) {
        this.activity = activity; this.bookClickListener = bookClickListener; this.nameClickListener = nameClickListener; setHasStableIds(true);
    }
    void showBooks(List<BookItem> value){mode=value.isEmpty()?Mode.EMPTY:Mode.BOOKS;books=new ArrayList<>(value);names=Collections.emptyList();emptyText="Нічого не знайдено";notifyDataSetChanged();}
    void appendBooks(List<BookItem> value){if(value.isEmpty())return;if(mode!=Mode.BOOKS){showBooks(value);return;}int start=books.size();books.addAll(value);notifyItemRangeInserted(start,value.size());}
    void showNames(List<String> value,String empty){mode=value.isEmpty()?Mode.EMPTY:Mode.NAMES;names=new ArrayList<>(value);books=Collections.emptyList();emptyText=empty;notifyDataSetChanged();}
    void appendNames(List<String> value){if(value.isEmpty()||mode!=Mode.NAMES)return;int start=names.size();names.addAll(value);notifyItemRangeInserted(start,value.size());}
    int dataSize(){return mode==Mode.BOOKS?books.size():mode==Mode.NAMES?names.size():0;}
    @Override public long getItemId(int p){return mode==Mode.BOOKS?books.get(p).id:mode==Mode.NAMES?names.get(p).hashCode():Long.MIN_VALUE;}
    @Override public int getItemCount(){return mode==Mode.BOOKS?books.size():mode==Mode.NAMES?names.size():1;}

    @NonNull @Override public RowHolder onCreateViewHolder(@NonNull ViewGroup parent,int viewType){
        LinearLayout card=new LinearLayout(parent.getContext());card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(activity.dp(12),activity.dp(7),activity.dp(12),activity.dp(7));card.setBackgroundResource(R.drawable.bg_book_row);
        RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);params.bottomMargin=activity.dp(5);card.setLayoutParams(params);
        TextView title=textView(15,R.color.text_primary);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);card.addView(title,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView author=textView(13,R.color.text_secondary);author.setMaxLines(1);author.setEllipsize(TextUtils.TruncateAt.END);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);ap.topMargin=activity.dp(1);card.addView(author,ap);
        LinearLayout meta=new LinearLayout(parent.getContext());meta.setOrientation(LinearLayout.HORIZONTAL);meta.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);mp.topMargin=activity.dp(2);card.addView(meta,mp);
        TextView series=textView(11.5f,R.color.text_secondary);series.setMaxLines(1);series.setEllipsize(TextUtils.TruncateAt.END);meta.addView(series,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        TextView badges=textView(10.5f,R.color.primary);badges.setTypeface(Typeface.DEFAULT,Typeface.BOLD);badges.setGravity(Gravity.END);meta.addView(badges,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        return new RowHolder(card,title,author,series,badges);
    }
    private TextView textView(float size,int color){TextView v=new TextView(activity);v.setTextSize(size);v.setTextColor(activity.getColor(color));v.setIncludeFontPadding(false);return v;}

    @Override public void onBindViewHolder(@NonNull RowHolder h,int p){
        h.itemView.setOnClickListener(null);h.title.setTextColor(activity.getColor(R.color.text_primary));h.author.setTextColor(activity.getColor(R.color.text_secondary));h.series.setTextColor(activity.getColor(R.color.text_secondary));h.badges.setTextColor(activity.getColor(R.color.primary));h.itemView.setBackgroundResource(R.drawable.bg_book_row);h.title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);h.title.setTextSize(15);
        if(mode==Mode.BOOKS){BookItem b=books.get(p);h.title.setText(b.title);h.author.setText(b.author.isEmpty()?"Невідомий автор":humanizeAuthor(b.author));h.author.setVisibility(View.VISIBLE);
            StringBuilder s=new StringBuilder();if(!b.series.isEmpty()){s.append(b.series);if(!b.seriesNumber.isEmpty())s.append(" · #").append(b.seriesNumber);}else if(!b.genre.isEmpty())s.append(humanizeGenre(b.genre));h.series.setText(s);h.series.setVisibility(s.length()==0?View.GONE:View.VISIBLE);
            StringBuilder badges=new StringBuilder();if(!b.extension.isEmpty())badges.append(b.extension.toUpperCase(Locale.ROOT));if(!b.language.isEmpty()){if(badges.length()>0)badges.append(" · ");badges.append(b.language.toUpperCase(Locale.ROOT));}if(!b.year.isEmpty()){if(badges.length()>0)badges.append(" · ");badges.append(b.year);}h.badges.setText(badges);h.badges.setVisibility(badges.length()==0?View.GONE:View.VISIBLE);h.itemView.setOnClickListener(v->bookClickListener.onBookClick(b));return;}
        h.title.setTypeface(Typeface.DEFAULT,Typeface.NORMAL);h.author.setVisibility(View.GONE);h.series.setVisibility(View.GONE);h.badges.setVisibility(View.GONE);
        if(mode==Mode.NAMES){String d=names.get(p);h.title.setText(humanizeNameRow(d));h.title.setTextSize(15);h.itemView.setOnClickListener(v->nameClickListener.onNameClick(d));return;}
        h.title.setText(emptyText);h.title.setTextSize(15);h.title.setTextColor(activity.getColor(R.color.text_secondary));h.itemView.setBackground(null);
    }

    static String humanizeAuthor(String value){
        String v=value.trim();
        while(v.endsWith(":")||v.endsWith(";"))v=v.substring(0,v.length()-1).trim();
        v=v.replace(',', ' ').replaceAll("\\s+"," ");
        return v;
    }
    private static String humanizeGenre(String value){return value.replace(":"," · ").replace('_',' ').trim();}
    private static String humanizeNameRow(String value){int marker=value.lastIndexOf(" (");String name=marker>0&&value.endsWith(")")?value.substring(0,marker):value;String count=marker>0&&value.endsWith(")")?value.substring(marker):"";return humanizeAuthor(name)+count;}

    static final class RowHolder extends RecyclerView.ViewHolder{final TextView title,author,series,badges;RowHolder(@NonNull View itemView,TextView title,TextView author,TextView series,TextView badges){super(itemView);this.title=title;this.author=author;this.series=series;this.badges=badges;}}
}
