package ua.flibrary.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
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
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends Activity {
    private static final int OPEN_INPX_REQUEST = 1001;
    private static final int OPEN_LIBRARY_FOLDER_REQUEST = 1002;
    private static final int PAGE_SIZE = 100;
    private static final long SEARCH_DEBOUNCE_MS = 400;
    private static final String PREFS = "flibrary";
    private static final String PREF_LIBRARY_TREE = "library_tree";

    static { System.loadLibrary("flibrary_android"); }
    private native String nativeStatus();
    private native String nativeImportInpx(int fd, CatalogDatabase database);
    private native byte[] nativeExtractBook(int fd, String fileName, String extension);

    private TextView status, resultsHeading;
    private EditText searchInput;
    private RecyclerView resultsList;
    private CatalogAdapter catalogAdapter;
    private CatalogDatabase catalogDatabase;
    private BookItem pendingBook;
    private Button importButton;
    private boolean namesAreAuthors, pageLoading, hasMorePages;
    private String pageHeading = "Книги";
    private BookPageSource bookPageSource;
    private NamePageSource namePageSource;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService queryWorker = Executors.newSingleThreadExecutor();
    private final AtomicLong queryGeneration = new AtomicLong();
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final Runnable liveSearchRunnable = () -> { if (searchInput == null) return; String q = searchInput.getText().toString().trim(); if (q.isEmpty()) startBookPaging("Книги", catalogDatabase::listBooks); else if (q.length() >= 2) runSearch(); };
    private interface BookPageSource { List<BookItem> load(int limit, int offset); }
    private interface NamePageSource { List<String> load(int limit, int offset); }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main); catalogDatabase = new CatalogDatabase(this);
        status = findViewById(R.id.status); resultsHeading = findViewById(R.id.results_heading); searchInput = findViewById(R.id.search_input); resultsList = findViewById(R.id.results_list); importButton = findViewById(R.id.import_button);
        catalogAdapter = new CatalogAdapter(this, this::showBookDetails, this::openName); LinearLayoutManager layoutManager = new LinearLayoutManager(this); resultsList.setLayoutManager(layoutManager); resultsList.setAdapter(catalogAdapter);
        resultsList.addOnScrollListener(new RecyclerView.OnScrollListener() { @Override public void onScrolled(RecyclerView r, int dx, int dy) { if (dy <= 0 || pageLoading || !hasMorePages) return; int total = layoutManager.getItemCount(), last = layoutManager.findLastVisibleItemPosition(); if (total > 0 && last >= total - 12) loadNextPage(); } });
        Button folderButton = findViewById(R.id.folder_button), settingsButton = findViewById(R.id.settings_button), booksButton = findViewById(R.id.books_button), authorsButton = findViewById(R.id.authors_button), seriesButton = findViewById(R.id.series_button), searchButton = findViewById(R.id.search_button);
        importButton.setOnClickListener(v -> openInpxDocument()); folderButton.setOnClickListener(v -> chooseLibraryFolder()); settingsButton.setOnClickListener(v -> showLibrarySettings());
        booksButton.setOnClickListener(v -> startBookPaging("Книги", catalogDatabase::listBooks)); authorsButton.setOnClickListener(v -> startNamePaging(true)); seriesButton.setOnClickListener(v -> startNamePaging(false));
        searchButton.setOnClickListener(v -> { searchHandler.removeCallbacks(liveSearchRunnable); runSearch(); });
        searchInput.setOnEditorActionListener((v, actionId, event) -> { if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) { searchHandler.removeCallbacks(liveSearchRunnable); runSearch(); return true; } return false; });
        searchInput.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int a,int b,int c){} public void onTextChanged(CharSequence s,int a,int b,int c){ searchHandler.removeCallbacks(liveSearchRunnable); String v=s==null?"":s.toString().trim(); if(v.isEmpty()||v.length()>=2) searchHandler.postDelayed(liveSearchRunnable,SEARCH_DEBOUNCE_MS);} public void afterTextChanged(Editable s){} });
        updateStatus(); startBookPaging("Книги", catalogDatabase::listBooks);
    }
    int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density); }
    private void showLibrarySettings() { String folderState=getLibraryTreeUri()==null?"не вибрана":"вибрана"; new AlertDialog.Builder(this).setTitle("Налаштування бібліотеки").setMessage("Книг у каталозі: "+catalogDatabase.getBookCount()+"\nПапка бібліотеки: "+folderState).setItems(new String[]{"Імпортувати каталог INPX","Вибрати папку бібліотеки"},(d,w)->{if(w==0)openInpxDocument();else chooseLibraryFolder();}).setNegativeButton("Закрити",null).show(); }
    private void updateStatus() { status.setText("Книг: "+catalogDatabase.getBookCount()+"  •  Папка: "+(getLibraryTreeUri()==null?"не вибрана":"готова")); }
    private void openInpxDocument() { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); startActivityForResult(i,OPEN_INPX_REQUEST); }
    private void chooseLibraryFolder() { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION); startActivityForResult(i,OPEN_LIBRARY_FOLDER_REQUEST); }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){ super.onActivityResult(requestCode,resultCode,data); if(resultCode!=RESULT_OK||data==null)return; if(requestCode==OPEN_LIBRARY_FOLDER_REQUEST){Uri tree=data.getData();if(tree==null)return;int flags=data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION;try{getContentResolver().takePersistableUriPermission(tree,flags);}catch(SecurityException ignored){}getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(PREF_LIBRARY_TREE,tree.toString()).apply();updateStatus();if(pendingBook!=null){BookItem b=pendingBook;pendingBook=null;openBook(b);}return;}if(requestCode!=OPEN_INPX_REQUEST)return;Uri uri=data.getData();if(uri==null){status.setText("Документ не вибрано");return;}final int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);try{getContentResolver().takePersistableUriPermission(uri,flags);}catch(SecurityException ignored){}startInpxImport(uri,queryDisplayName(uri));}
    private void startInpxImport(Uri uri,String displayName){importButton.setEnabled(false);queryGeneration.incrementAndGet();status.setText("Імпортую "+displayName+"…");worker.execute(()->{boolean started=false,success=false;String message;try(ParcelFileDescriptor pfd=getContentResolver().openFileDescriptor(uri,"r")){if(pfd==null)message="Не вдалося відкрити вибраний INPX";else{catalogDatabase.beginNativeImport();started=true;String result=nativeImportInpx(pfd.getFd(),catalogDatabase);success=result!=null&&result.startsWith("OK:");message=result==null?"Помилка нативного імпорту":result;}}catch(IOException|RuntimeException e){message="Помилка імпорту: "+safeMessage(e);}if(started)try{catalogDatabase.finishNativeImport(success);}catch(RuntimeException e){message="Помилка завершення бази: "+safeMessage(e);success=false;}final boolean ok=success;final String msg=message;runOnUiThread(()->{if(isFinishing()||isDestroyed())return;importButton.setEnabled(true);if(ok){updateStatus();startBookPaging("Книги",catalogDatabase::listBooks);}else{status.setText(msg);showErrorDialog(msg);}});});}
    private void startBookPaging(String heading,BookPageSource source){long gen=queryGeneration.incrementAndGet();pageHeading=heading;bookPageSource=source;namePageSource=null;pageLoading=true;hasMorePages=false;resultsHeading.setText(heading+"  •  …");queryWorker.execute(()->{if(gen!=queryGeneration.get())return;try{List<BookItem> page=source.load(PAGE_SIZE,0);runOnUiThread(()->{if(isFinishing()||isDestroyed()||gen!=queryGeneration.get())return;catalogAdapter.showBooks(page);resultsHeading.setText(heading+"  •  "+page.size());resultsList.scrollToPosition(0);pageLoading=false;hasMorePages=page.size()==PAGE_SIZE;});}catch(RuntimeException e){postCatalogError(gen,e);}});}
    private void startNamePaging(boolean authors){long gen=queryGeneration.incrementAndGet();namesAreAuthors=authors;pageHeading=authors?"Автори":"Серії";bookPageSource=null;namePageSource=authors?catalogDatabase::listAuthors:catalogDatabase::listSeries;pageLoading=true;hasMorePages=false;resultsHeading.setText(pageHeading+"  •  …");NamePageSource source=namePageSource;queryWorker.execute(()->{if(gen!=queryGeneration.get())return;try{List<String> page=source.load(PAGE_SIZE,0);runOnUiThread(()->{if(isFinishing()||isDestroyed()||gen!=queryGeneration.get())return;catalogAdapter.showNames(page,authors?"Авторів не знайдено":"Серій не знайдено");resultsHeading.setText(pageHeading+"  •  "+page.size());resultsList.scrollToPosition(0);pageLoading=false;hasMorePages=page.size()==PAGE_SIZE;});}catch(RuntimeException e){postCatalogError(gen,e);}});}
    private void loadNextPage(){if(pageLoading||!hasMorePages)return;final long gen=queryGeneration.get();final int offset=catalogAdapter.dataSize();final BookPageSource books=bookPageSource;final NamePageSource names=namePageSource;if(books==null&&names==null)return;pageLoading=true;queryWorker.execute(()->{if(gen!=queryGeneration.get())return;try{if(books!=null){List<BookItem> page=books.load(PAGE_SIZE,offset);runOnUiThread(()->{if(isFinishing()||isDestroyed()||gen!=queryGeneration.get())return;catalogAdapter.appendBooks(page);pageLoading=false;hasMorePages=page.size()==PAGE_SIZE;resultsHeading.setText(pageHeading+"  •  "+catalogAdapter.dataSize());});}else{List<String> page=names.load(PAGE_SIZE,offset);runOnUiThread(()->{if(isFinishing()||isDestroyed()||gen!=queryGeneration.get())return;catalogAdapter.appendNames(page);pageLoading=false;hasMorePages=page.size()==PAGE_SIZE;resultsHeading.setText(pageHeading+"  •  "+catalogAdapter.dataSize());});}}catch(RuntimeException e){postCatalogError(gen,e);}});}
    private void postCatalogError(long gen,RuntimeException e){runOnUiThread(()->{if(isFinishing()||isDestroyed()||gen!=queryGeneration.get())return;pageLoading=false;hasMorePages=false;showError("Помилка каталогу: "+safeMessage(e));});}
    private void runSearch(){String q=searchInput.getText().toString().trim();if(q.isEmpty()){startBookPaging("Книги",catalogDatabase::listBooks);return;}startBookPaging("Пошук: "+q,(limit,offset)->catalogDatabase.searchBooks(q,limit,offset));}
    private void openName(String display){String name=stripCount(display);if(namesAreAuthors)startBookPaging(name,(limit,offset)->catalogDatabase.booksByAuthor(name,limit,offset));else startBookPaging(name,(limit,offset)->catalogDatabase.booksBySeries(name,limit,offset));}
    private String stripCount(String display){int marker=display.lastIndexOf(" (");return marker>0&&display.endsWith(")")?display.substring(0,marker):display;}
    private void showBookDetails(BookItem book){BookDetailsPage.show(this,book,()->openBook(book),()->startBookPaging(book.author,(l,o)->catalogDatabase.booksByAuthor(book.author,l,o)),()->startBookPaging(book.series,(l,o)->catalogDatabase.booksBySeries(book.series,l,o)));}
    private Uri getLibraryTreeUri(){String value=getSharedPreferences(PREFS,MODE_PRIVATE).getString(PREF_LIBRARY_TREE,null);return value==null?null:Uri.parse(value);}
    private DocumentFile findArchive(BookItem book){Uri treeUri=getLibraryTreeUri();if(treeUri==null||book.folder.isEmpty())return null;DocumentFile root=DocumentFile.fromTreeUri(this,treeUri);if(root==null)return null;DocumentFile archive=findArchiveFrom(root,book.folder);if(archive!=null)return archive;DocumentFile archives=findChildIgnoreCase(root,"archives");if(archives!=null&&archives.isDirectory()){archive=findArchiveFrom(archives,book.folder);if(archive!=null)return archive;}String normalized=normalizeArchivePath(book.folder);int slash=normalized.lastIndexOf('/');String baseName=slash>=0?normalized.substring(slash+1):normalized;archive=findArchiveFrom(root,baseName);if(archive!=null)return archive;return archives==null?null:findArchiveFrom(archives,baseName);}
    private DocumentFile findArchiveFrom(DocumentFile root,String rawPath){String path=normalizeArchivePath(rawPath);if(path.isEmpty())return null;DocumentFile current=root;String[] parts=path.split("/");for(String part:parts){if(part.isEmpty())continue;current=findChildIgnoreCase(current,part);if(current==null)return null;}if(current.isFile())return current;String last=parts.length==0?path:parts[parts.length-1];if(!last.toLowerCase(Locale.ROOT).endsWith(".zip")){DocumentFile withZip=findChildIgnoreCase(root,path+".zip");if(withZip!=null&&withZip.isFile())return withZip;}return null;}
    private String normalizeArchivePath(String value){String path=value.replace('\\','/').trim();while(path.startsWith("/"))path=path.substring(1);if(path.regionMatches(true,0,"archives/",0,9))path=path.substring(9);return path;}
    private DocumentFile findChildIgnoreCase(DocumentFile parent,String name){DocumentFile exact=parent.findFile(name);if(exact!=null)return exact;for(DocumentFile child:parent.listFiles())if(name.equalsIgnoreCase(child.getName()))return child;return null;}
    private void openBook(BookItem book){Uri treeUri=getLibraryTreeUri();if(treeUri==null){pendingBook=book;new AlertDialog.Builder(this).setTitle("Папка бібліотеки не вибрана").setMessage("Виберіть папку, де лежать архіви книг. Доступ буде збережено для наступних запусків.").setPositiveButton("Вибрати папку",(d,w)->chooseLibraryFolder()).setNegativeButton("Скасувати",null).show();return;}status.setText("Відкриваю: "+book.title+"…");worker.execute(()->{DocumentFile archive=findArchive(book);if(archive==null){postOpenError("Не знайдено архів: "+book.folder);return;}try(ParcelFileDescriptor pfd=getContentResolver().openFileDescriptor(archive.getUri(),"r")){if(pfd==null){postOpenError("Не вдалося відкрити архів: "+archive.getName());return;}byte[] bytes=nativeExtractBook(pfd.getFd(),book.fileName,book.extension);if(bytes==null||bytes.length==0){postOpenError("Не вдалося витягнути книгу з архіву");return;}File out=writeBookToCache(book,bytes);runOnUiThread(()->{if(isFinishing()||isDestroyed())return;updateStatus();openCachedBook(out,book.extension);});}catch(IOException|RuntimeException e){postOpenError("Помилка відкриття книги: "+safeMessage(e));}});}
    private void postOpenError(String message){runOnUiThread(()->{if(isFinishing()||isDestroyed())return;updateStatus();showErrorDialog(message);});}
    private File writeBookToCache(BookItem book,byte[] bytes)throws IOException{File dir=new File(getCacheDir(),"opened_books");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Не вдалося створити кеш книг");String base=book.fileName.isEmpty()?"book-"+book.id:book.fileName;String ext=book.extension.isEmpty()?"fb2":book.extension.toLowerCase(Locale.ROOT);if(!base.toLowerCase(Locale.ROOT).endsWith("."+ext))base+="."+ext;File out=new File(dir,sanitizeFileName(base));try(FileOutputStream stream=new FileOutputStream(out)){stream.write(bytes);}return out;}
    private String sanitizeFileName(String value){return value.replaceAll("[\\\\/:*?\"<>|]","_");}
    private void openCachedBook(File file,String extension){Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",file);Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(uri,mimeForExtension(extension));intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivity(Intent.createChooser(intent,"Відкрити книгу"));}catch(ActivityNotFoundException e){showErrorDialog("На пристрої немає застосунку для формату "+extension.toUpperCase(Locale.ROOT));}}
    private String mimeForExtension(String extension){String ext=extension==null?"":extension.toLowerCase(Locale.ROOT);switch(ext){case"fb2":return"application/x-fictionbook+xml";case"epub":return"application/epub+zip";case"pdf":return"application/pdf";case"mobi":return"application/x-mobipocket-ebook";case"djvu":case"djv":return"image/vnd.djvu";case"txt":return"text/plain";case"rtf":return"application/rtf";default:return"application/octet-stream";}}
    private String queryDisplayName(Uri uri){try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst()){int index=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(index>=0)return cursor.getString(index);}}catch(RuntimeException ignored){}return uri.getLastPathSegment()==null?"INPX":uri.getLastPathSegment();}
    private void showError(String message){status.setText(message);showErrorDialog(message);} private void showErrorDialog(String message){new AlertDialog.Builder(this).setTitle("FLibrary").setMessage(message).setPositiveButton("OK",null).show();} private String safeMessage(Throwable error){String m=error.getMessage();return m==null||m.isEmpty()?error.getClass().getSimpleName():m;}
    @Override protected void onDestroy(){searchHandler.removeCallbacks(liveSearchRunnable);queryGeneration.incrementAndGet();worker.shutdownNow();queryWorker.shutdownNow();if(catalogDatabase!=null)catalogDatabase.close();super.onDestroy();}
}
