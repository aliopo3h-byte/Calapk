package com.ledgerbook.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** يعرض صفحة التطبيق (assets/index.html) ويوفّر لها الحفظ والمشاركة والرجوع. */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 21;
    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";

    private WebView web;
    private ValueCallback<Uri[]> chooser;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try {
                    if ("tel".equals(u.getScheme())) {
                        startActivity(new Intent(Intent.ACTION_DIAL, u));
                    } else {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    }
                } catch (Exception e) {
                    // no app can open this link
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "اختر الملف"), REQ_FILE);
                } catch (Exception e) {
                    chooser = null;
                    cb.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl(HOME);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && chooser != null) {
            chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            chooser = null;
        }
    }

    /** زر الرجوع: تتعامل الصفحة معه أولاً (إغلاق نافذة أو العودة لشاشة)، وإلا يُخفى التطبيق. */
    @Override
    public void onBackPressed() {
        if (web == null) {
            moveTaskToBack(true);
            return;
        }
        web.evaluateJavascript("(window.onBack && window.onBack()) ? 'y' : 'n'", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if (v == null || !v.contains("y")) moveTaskToBack(true);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** الجسر بين الصفحة وهذا التطبيق (يُستدعى من JavaScript باسم Android) */
    private class Bridge {
        @JavascriptInterface
        public boolean saveFile(String b64, String name, String mime, boolean share) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                String safe = name == null ? "file" : name.replace("/", "_").replace("\\", "_");
                if (share || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    File dir = new File(getCacheDir(), "share");
                    dir.mkdirs();
                    File f = new File(dir, safe);
                    try (OutputStream o = new FileOutputStream(f)) {
                        o.write(data);
                    }
                    final Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", f);
                    final String m = mime;
                    runOnUiThread(() -> {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType(m);
                        i.putExtra(Intent.EXTRA_STREAM, uri);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(i, null));
                    });
                    return true;
                }
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DaftarAlHisabat");
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) return false;
                try (OutputStream o = getContentResolver().openOutputStream(u)) {
                    o.write(data);
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void shareText(final String t) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, t);
                startActivity(Intent.createChooser(i, null));
            });
        }

        @JavascriptInterface
        public boolean isDark() {
            return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        }

        @JavascriptInterface
        public void setBars(final boolean dark) {
            runOnUiThread(() -> {
                getWindow().setStatusBarColor(dark ? 0xFF0B3B37 : 0xFF0F766E);
                getWindow().setNavigationBarColor(dark ? 0xFF16201F : 0xFFF4F6F3);
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsetsController c = getWindow().getInsetsController();
                    if (c != null) {
                        c.setSystemBarsAppearance(dark ? 0 : WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                    }
                } else {
                    View d = getWindow().getDecorView();
                    int f = d.getSystemUiVisibility();
                    d.setSystemUiVisibility(dark ? (f & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
                            : (f | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
                }
            });
        }
    }
}
