package com.les4a.carnet;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Coque native du carnet de bord.
 *
 * Les fichiers de l'application vivent dans app/src/main/assets/www et sont
 * servis sur https://appassets.androidplatform.net/ plutot qu'en file:// :
 * l'origine est alors stable et securisee, ce qui rend le stockage local
 * fiable et conserve d'une version a l'autre.
 *
 * Une WebView ignore par defaut les champs de fichier et les telechargements.
 * Deux ponts les retablissent :
 *   - onShowFileChooser ouvre le selecteur de fichiers, pour l'import ;
 *   - AndroidBridge.save ecrit l'export dans le dossier Telechargements,
 *     via un script injecte qui detourne les liens de telechargement.
 */
public class MainActivity extends AppCompatActivity {

    private static final String START =
            "https://appassets.androidplatform.net/assets/www/index.html?native=1";

    /** detourne les telechargements declenches par la page vers le pont natif */
    private static final String HOOK =
            "(function(){"
          + "if(window.__pontAndroid)return;window.__pontAndroid=1;"
          + "var o=HTMLAnchorElement.prototype.click;"
          + "HTMLAnchorElement.prototype.click=function(){"
          + "  try{"
          + "    var h=this.getAttribute('href')||'',d=this.getAttribute('download');"
          + "    if(d&&h.indexOf('blob:')===0){"
          + "      fetch(h).then(function(r){return r.blob()}).then(function(b){"
          + "        var fr=new FileReader();"
          + "        fr.onload=function(){AndroidBridge.save(d,String(fr.result))};"
          + "        fr.readAsText(b);"
          + "      }).catch(function(e){AndroidBridge.toast('Export impossible : '+e)});"
          + "      return;"
          + "    }"
          + "  }catch(e){}"
          + "  return o.apply(this,arguments);"
          + "};"
          + "})();";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    private final ActivityResultLauncher<Intent> picker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                if (fileCallback == null) return;
                fileCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(r.getResultCode(), r.getData()));
                fileCallback = null;
            });

    /** expose a la page sous le nom AndroidBridge */
    public class Pont {
        @JavascriptInterface
        public void save(String name, String content) {
            final String msg = ecrire(name, content);
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    /** ecrit le fichier dans Telechargements ; renvoie le message a afficher */
    private String ecrire(String name, String content) {
        if (name == null || name.isEmpty()) name = "carnet.json";
        final byte[] data = content.getBytes(StandardCharsets.UTF_8);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE,
                        name.endsWith(".json") ? "application/json" : "text/plain");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return "Enregistrement refuse par le systeme.";
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) return "Enregistrement refuse par le systeme.";
                os.write(data);
                os.close();
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (dir != null && !dir.exists()) dir.mkdirs();
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(data);
                fos.close();
            }
            return "Enregistre dans Telechargements : " + name;
        } catch (Exception e) {
            return "Echec de l'enregistrement : " + e.getMessage();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setStatusBarColor(Color.parseColor("#0E2340"));
        getWindow().setNavigationBarColor(Color.parseColor("#0E2340"));

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.addJavascriptInterface(new Pont(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(HOOK, null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                String h = u.getHost();
                if (h != null && h.equals("appassets.androidplatform.net")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    Intent i = params.createIntent();
                    // certains explorateurs masquent les .json si le type est trop strict
                    i.setType("*/*");
                    picker.launch(i);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this,
                            "Aucun explorateur de fichiers disponible.", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) web.goBack();
                else finish();
            }
        });

        if (state != null) web.restoreState(state);
        else web.loadUrl(START);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
