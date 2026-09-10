package de.begriffwerk.app;

import android.app.*;
import android.os.Bundle;
import android.database.Cursor;
import android.database.sqlite.*;
import android.webkit.*;
import android.widget.FrameLayout;
import org.json.*;
import java.io.*;
import java.util.*;

/** Single WebView UI with a directly callable native SQLite learning service. */
public final class NativeActivity extends Activity {
    private static final String ORIGIN="https://appassets.androidplatform.net";
    private WebView web;
    private SQLiteDatabase database;
    private QuizEngine service;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try{
            openDatabase();service=new QuizEngine(new AndroidStore());
            web=new WebView(this);web.setBackgroundColor(0xfff6f7f3);
            web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
            web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
            web.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
            web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            web.addJavascriptInterface(new Bridge(),"AndroidApp");
            web.setWebChromeClient(new WebChromeClient());
            web.setWebViewClient(new WebViewClient(){
                @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !allowed(r.getUrl().toString());}
                @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                    if(!allowed(r.getUrl().toString()))return blocked();
                    String p=r.getUrl().getPath();if(p.equals("/"))p="/index.html";
                    try{
                        String type=p.endsWith(".js")?"text/javascript":p.endsWith(".css")?"text/css":"text/html";
                        WebResourceResponse response=new WebResourceResponse(type,"UTF-8",getAssets().open(p.substring(1)));
                        Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-store");
                        headers.put("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; frame-src 'none'");response.setResponseHeaders(headers);return response;
                    }catch(Exception e){return blocked();}
                }
            });
            FrameLayout layout=new FrameLayout(this);
            layout.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i.consumeSystemWindowInsets();});
            layout.addView(web,new FrameLayout.LayoutParams(-1,-1));setContentView(layout);
            web.loadUrl(ORIGIN+"/index.html?v=1.0.2");
        }catch(Exception e){new AlertDialog.Builder(this).setTitle("Begriffwerk 1.0.2 – Startfehler").setMessage(String.valueOf(e.getMessage())).setPositiveButton("Schließen",(d,w)->finish()).show();}
    }
    private boolean allowed(String url){if(!url.startsWith(ORIGIN+"/"))return false;String p=android.net.Uri.parse(url).getPath();return Arrays.asList("/","/index.html","/app.js","/styles.css","/native-api.js").contains(p);}
    private WebResourceResponse blocked(){return new WebResourceResponse("text/plain","UTF-8",403,"Forbidden",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
    public final class Bridge {
        @JavascriptInterface public String version(){return "1.0.2";}
        @JavascriptInterface public String call(String path,String body){
            if(path==null||body==null||path.length()>4000||body.length()>16384)return "{\"ok\":false,\"error\":\"Ungültige Anfrage (1.0.2)\"}";
            // Return the result through the same native call; no evaluateJavascript callback.
            return service.call(path,body);
        }
    }
    private void copySeed(File file)throws Exception{try(InputStream in=getAssets().open("seed.sqlite");FileOutputStream out=new FileOutputStream(file)){byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);out.getFD().sync();}}
    private void openDatabase()throws Exception{
        File file=getDatabasePath("learning.sqlite");file.getParentFile().mkdirs();if(!file.exists())copySeed(file);
        database=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READWRITE);database.execSQL("PRAGMA foreign_keys=ON");
        File seed=new File(getCacheDir(),"packaged-seed.sqlite");copySeed(seed);database.execSQL("ATTACH DATABASE ? AS seed",new Object[]{seed.getPath()});
        database.beginTransaction();try{for(String table:new String[]{"sources","sections","terms","term_sections"})database.execSQL("INSERT OR IGNORE INTO main."+table+" SELECT * FROM seed."+table);database.execSQL("INSERT OR IGNORE INTO main.progress(term_id) SELECT id FROM seed.terms");database.setTransactionSuccessful();}finally{database.endTransaction();database.execSQL("DETACH DATABASE seed");}database.enableWriteAheadLogging();
    }
    private final class AndroidStore implements QuizEngine.Store {
        public List<JSONObject> query(String sql,Object... args)throws Exception{
            String[] bind=new String[args.length];for(int i=0;i<args.length;i++)bind[i]=args[i]==null?null:args[i].toString();List<JSONObject> rows=new ArrayList<>();
            try(Cursor c=database.rawQuery(sql,bind)){while(c.moveToNext()){JSONObject row=new JSONObject();for(int i=0;i<c.getColumnCount();i++){Object value=JSONObject.NULL;switch(c.getType(i)){case Cursor.FIELD_TYPE_INTEGER:value=c.getLong(i);break;case Cursor.FIELD_TYPE_FLOAT:value=c.getDouble(i);break;case Cursor.FIELD_TYPE_STRING:value=c.getString(i);break;}row.put(c.getColumnName(i),value);}rows.add(row);}}return rows;
        }
        public void execute(String sql,Object... args){database.execSQL(sql,args);}
        public void begin(){database.beginTransactionNonExclusive();}
        public void commit(){database.setTransactionSuccessful();database.endTransaction();}
        public void rollback(){database.endTransaction();}
    }
    @Override public void onBackPressed(){
        if(web==null){finish();return;}
        web.evaluateJavascript("document.querySelector('[data-action=close]') ? (document.querySelector('[data-action=close]').click(), 'closed') : document.querySelector('nav button.selected')?.dataset.page || ''",value->{if("\"closed\"".equals(value))return;if(!"\"dashboard\"".equals(value))web.evaluateJavascript("document.querySelector('[data-page=dashboard]').click()",null);else new AlertDialog.Builder(this).setTitle("Begriffwerk schließen?").setMessage("Dein Lernfortschritt ist gespeichert.").setNegativeButton("Weiterlernen",null).setPositiveButton("Schließen",(d,w)->finish()).show();});
    }
    @Override protected void onDestroy(){if(web!=null){web.removeJavascriptInterface("AndroidApp");web.destroy();}if(service!=null){synchronized(service){database.close();}}else if(database!=null)database.close();super.onDestroy();}
}
