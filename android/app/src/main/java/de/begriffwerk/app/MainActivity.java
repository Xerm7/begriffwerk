package de.begriffwerk.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.graphics.Color;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.UUID;

/** Offline Android host. UI and learning engine run in separate JavaScript realms.
 * Only the private engine can access SQLite; the UI gets checked API responses.
 */
public class MainActivity extends Activity {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> queued = new ArrayDeque<>();
    private WebView ui;
    private WebView engine;
    private SQLiteDatabase database;
    private boolean engineReady;
    private String engineFailure;
    private volatile boolean destroyed;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            android.content.pm.PackageInfo webViewPackage=WebView.getCurrentWebViewPackage();
            if(webViewPackage==null||Integer.parseInt(webViewPackage.versionName.split("\\.")[0])<100)
                throw new IllegalStateException("Bitte Android System WebView / Chrome aktualisieren (Version 100 oder neuer).");
            openDatabase();
            FrameLayout layout = new FrameLayout(this);
            layout.setBackgroundColor(Color.rgb(246,247,243));
            // Android 15 edge-to-edge: keep all quiz controls outside system bars.
            layout.setOnApplyWindowInsetsListener((v,insets)->{
                v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
                return insets.consumeSystemWindowInsets();
            });
            engine = createWebView(true);
            engine.addJavascriptInterface(new DatabaseBridge(), "NativeDatabase");
            layout.addView(engine, new FrameLayout.LayoutParams(1,1));
            // Keep the engine attached/visible behind the full-size UI: some WebViews
            // defer work for views marked INVISIBLE during their initial load.
            ui = createWebView(false);
            ui.addJavascriptInterface(new FrontendBridge(), "AndroidApp");
            layout.addView(ui,new FrameLayout.LayoutParams(-1,-1));
            setContentView(layout);
            engine.loadUrl(ORIGIN+"/engine/index.html");
            ui.loadUrl(ORIGIN+"/index.html");
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Begriffwerk kann nicht starten")
                .setMessage("Die lokale Datenbank konnte nicht geöffnet werden.\n"+e.getMessage())
                .setPositiveButton("Schließen",(d,w)->finish()).show();
        }
    }

    private WebView createWebView(boolean privateEngine) {
        WebView view = new WebView(this);
        view.setBackgroundColor(Color.rgb(246,247,243));
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(!privateEngine);
        view.getSettings().setAllowFileAccess(false);
        view.getSettings().setAllowContentAccess(false);
        view.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        view.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onConsoleMessage(ConsoleMessage msg){
                if(msg.messageLevel()==ConsoleMessage.MessageLevel.ERROR){
                    android.util.Log.e("Begriffwerk", (privateEngine?"Engine: ":"UI: ")+msg.message());
                    if(privateEngine)failEngine(msg.message());
                }
                return true;
            }
        });
        view.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){
                return !allowed(request.getUrl().toString(), privateEngine);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request){
                String address=request.getUrl().toString();
                if(!allowed(address,privateEngine)) return response(403,"Forbidden","text/plain",new byte[0]);
                String path=request.getUrl().getPath();
                if(path==null||path.equals("/"))path="/index.html";
                // No local file paths or remote URLs are ever passed to AssetManager.
                if(path.contains("..")||path.contains("\\"))return response(403,"Forbidden","text/plain",new byte[0]);
                try{
                    String mime=path.endsWith(".js")?"text/javascript":path.endsWith(".css")?"text/css":"text/html";
                    WebResourceResponse result = new WebResourceResponse(mime,"UTF-8",getAssets().open(path.substring(1)));
                    result.setResponseHeaders(Collections.singletonMap("Content-Security-Policy",
                        "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; frame-src 'none'"));
                    return result;
                }catch(Exception e){return response(404,"Not Found","text/plain",new byte[0]);}
            }
        });
        return view;
    }

    private boolean allowed(String address, boolean privateEngine){
        if(!address.startsWith(ORIGIN+"/"))return false;
        String path=android.net.Uri.parse(address).getPath();
        if(path==null)return false;
        if(privateEngine)return path.startsWith("/engine/") && (path.endsWith(".js")||path.endsWith(".html"));
        return path.equals("/")||path.equals("/index.html")||path.equals("/app.js")||path.equals("/native-api.js")||path.equals("/styles.css");
    }

    private WebResourceResponse response(int status,String reason,String mime,byte[] content){
        return new WebResourceResponse(mime,"UTF-8",status,reason,Collections.emptyMap(),new ByteArrayInputStream(content));
    }

    private void copySeed(File target) throws Exception {
        try(InputStream in=getAssets().open("seed.sqlite");FileOutputStream out=new FileOutputStream(target)){
            byte[] buffer=new byte[32768];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
            out.getFD().sync();
        }
    }

    private void openDatabase() throws Exception {
        File target=getDatabasePath("learning.sqlite");target.getParentFile().mkdirs();
        if(!target.exists())copySeed(target);
        database=SQLiteDatabase.openDatabase(target.getPath(),null,SQLiteDatabase.OPEN_READWRITE);
        database.execSQL("PRAGMA foreign_keys=ON");
        // Add new packaged terms on upgrades without replacing answers or schedules.
        File seed=new File(getCacheDir(),"packaged-seed.sqlite");copySeed(seed);
        database.execSQL("ATTACH DATABASE ? AS seed",new Object[]{seed.getPath()});
        database.beginTransaction();
        try{
            for(String table:new String[]{"sources","sections","terms","term_sections"})
                database.execSQL("INSERT OR IGNORE INTO main."+table+" SELECT * FROM seed."+table);
            database.execSQL("INSERT OR IGNORE INTO main.progress(term_id) SELECT id FROM seed.terms");
            database.setTransactionSuccessful();
        }finally{database.endTransaction();database.execSQL("DETACH DATABASE seed");}
        database.enableWriteAheadLogging();
    }

    public final class FrontendBridge {
        @JavascriptInterface public void request(String id,String path,String body){
            if(id==null||path==null||body==null||id.length()>100||path.length()>4000||body.length()>16384)return;
            if(!path.startsWith("/api/")){deliver(id,"{\"ok\":false,\"error\":\"Ungültiger Endpunkt\"}");return;}
            try{
                // Parse before embedding: JavaScript injection through request bodies is impossible.
                Object parsed=new org.json.JSONTokener(body).nextValue();
                if(parsed!=JSONObject.NULL && !(parsed instanceof JSONObject))throw new Exception("Invalid request");
                String script="window.dispatchNative("+JSONObject.quote(id)+","+JSONObject.quote(path)+","+parsed.toString()+");";
                main.post(()->{if(destroyed)return;if(engineFailure!=null){deliver(id,"{\"ok\":false,\"error\":"+JSONObject.quote(engineFailure)+"}");}else if(engineReady)engine.evaluateJavascript(script,null);else queued.add(script);});
            }catch(Exception e){deliver(id,"{\"ok\":false,\"error\":\"Ungültige Anfrage\"}");}
        }
    }

    public final class DatabaseBridge {
        @JavascriptInterface public void failed(String message){failEngine(message);}
        @JavascriptInterface public String uuid(){return UUID.randomUUID().toString();}
        @JavascriptInterface public void ready(){main.post(()->{if(destroyed)return;engineReady=true;while(!queued.isEmpty())engine.evaluateJavascript(queued.remove(),null);});}
        @JavascriptInterface public void respond(String id,String envelope){deliver(id,envelope);}
        @JavascriptInterface public String sql(String mode,String sql,String params){
            JSONObject envelope=new JSONObject();
            try{
                JSONArray args=new JSONArray(params);Object result=JSONObject.NULL;
                if(mode.equals("exec")){
                    if(sql.startsWith("BEGIN"))database.beginTransactionNonExclusive();
                    else if(sql.equals("COMMIT")){database.setTransactionSuccessful();database.endTransaction();}
                    else if(sql.equals("ROLLBACK"))database.endTransaction();
                    else database.execSQL(sql);
                }else if(mode.equals("all")||mode.equals("get")){
                    String[] bindings=new String[args.length()];
                    for(int i=0;i<args.length();i++)bindings[i]=args.isNull(i)?null:args.get(i).toString();
                    JSONArray rows=new JSONArray();
                    try(Cursor c=database.rawQuery(sql,bindings)){
                        while(c.moveToNext()){
                            JSONObject row=new JSONObject();
                            for(int i=0;i<c.getColumnCount();i++){
                                Object value=JSONObject.NULL;
                                switch(c.getType(i)){
                                    case Cursor.FIELD_TYPE_INTEGER:value=c.getLong(i);break;
                                    case Cursor.FIELD_TYPE_FLOAT:value=c.getDouble(i);break;
                                    case Cursor.FIELD_TYPE_STRING:value=c.getString(i);break;
                                }
                                row.put(c.getColumnName(i),value);
                            }
                            rows.put(row);if(mode.equals("get"))break;
                        }
                    }
                    result=mode.equals("get")?(rows.length()>0?rows.get(0):JSONObject.NULL):rows;
                }else if(mode.equals("run")){
                    try(SQLiteStatement statement=database.compileStatement(sql)){
                        for(int i=0;i<args.length();i++){
                            Object a=args.get(i);
                            if(a==JSONObject.NULL)statement.bindNull(i+1);
                            else if(a instanceof Double||a instanceof Float)statement.bindDouble(i+1,((Number)a).doubleValue());
                            else if(a instanceof Number)statement.bindLong(i+1,((Number)a).longValue());
                            else statement.bindString(i+1,a.toString());
                        }
                        statement.execute();
                    }
                }else throw new IllegalArgumentException("Unknown database command");
                envelope.put("ok",true);envelope.put("result",result);
            }catch(Exception e){
                android.util.Log.e("Begriffwerk","SQLite operation failed",e);
                try{envelope.put("ok",false);envelope.put("error",e.getMessage());}catch(Exception ignored){}
            }
            return envelope.toString();
        }
    }

    private void failEngine(String message){
        main.post(()->{
            if(destroyed)return;
            engineFailure="Lernmodul konnte nicht starten: "+message;
            queued.clear();
            ui.evaluateJavascript("window.__androidEngineFailed && window.__androidEngineFailed("+JSONObject.quote(engineFailure)+");",null);
        });
    }

    private void deliver(String id,String envelope){
        try{
            String safe=new JSONObject(envelope).toString();
            main.post(()->{if(!destroyed)ui.evaluateJavascript("window.__androidResponse("+JSONObject.quote(id)+","+safe+");",null);});
        }catch(Exception e){android.util.Log.e("Begriffwerk","Invalid engine response",e);}
    }

    @Override public void onBackPressed(){
        if(ui==null){super.onBackPressed();return;}
        ui.evaluateJavascript("document.querySelector('[data-action=close]') ? (document.querySelector('[data-action=close]').click(), 'closed') : document.querySelector('nav button.selected')?.dataset.page || ''",value->{
            if("\"closed\"".equals(value))return;
            if(!"\"dashboard\"".equals(value))ui.evaluateJavascript("document.querySelector('[data-page=dashboard]').click()",null);
            else new AlertDialog.Builder(this).setTitle("Begriffwerk schließen?")
                .setMessage("Dein Lernfortschritt ist gespeichert.").setNegativeButton("Weiterlernen",null)
                .setPositiveButton("Schließen",(d,w)->finish()).show();
        });
    }

    @Override protected void onDestroy(){
        destroyed=true;queued.clear();
        if(ui!=null)ui.destroy();if(engine!=null)engine.destroy();
        // Let in-flight bridge calls finish; SQLite commits each answer before returning.
        super.onDestroy();
    }
}
