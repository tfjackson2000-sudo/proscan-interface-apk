package com.proscan.interfaceapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_LOAD_JSON = 901;
    private WebView webView;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean userWakeLockEnabled = false;
    private boolean bgAudioModeEnabled = false;
    private String activeServerUrl = "";
    private TextToSpeech tts = null;
    private boolean ttsReady = false;

    private final Runnable periodicInjector = new Runnable() {
        @Override
        public void run() {
            if (webView != null) {
                injectEnhancements();
            }
            mainHandler.postDelayed(this, 1800);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
            } catch (Exception ignored) {}
        }

        prefs = getSharedPreferences("proscan_prefs", MODE_PRIVATE);

        try {
            tts = new TextToSpeech(getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(Locale.UK);
                    tts.setSpeechRate(1.05f);
                    ttsReady = true;
                }
            });
        } catch (Exception ignored) {}

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        webView = new WebView(this) {
            @Override
            public void setKeepScreenOn(boolean keepScreenOn) {
                super.setKeepScreenOn(userWakeLockEnabled);
            }

            @Override
            protected void onWindowVisibilityChanged(int visibility) {
                if (bgAudioModeEnabled) {
                    super.onWindowVisibilityChanged(View.VISIBLE);
                } else {
                    super.onWindowVisibilityChanged(visibility);
                }
            }
        };
        webView.setKeepScreenOn(false);
        setupWebView();
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        setContentView(root);

        String localUrl = prefs.getString("local_url", "");
        String tailscaleUrl = prefs.getString("tailscale_url", "");

        if (localUrl.isEmpty() && tailscaleUrl.isEmpty()) {
            showSetupDialog(true);
        } else {
            connectSmart(localUrl, tailscaleUrl);
        }
    }

    private void setBgServiceRunning(boolean enable) {
        bgAudioModeEnabled = enable;
        Intent svc = new Intent(this, AudioKeepAliveService.class);
        try {
            if (enable) {
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
            } else {
                stopService(svc);
            }
        } catch (Exception ignored) {}
    }

    private void saveBackupJsonToDownloads(String webStorageJson, boolean showToast) {
        try {
            JSONObject root = new JSONObject();
            root.put("local_url", prefs.getString("local_url", ""));
            root.put("tailscale_url", prefs.getString("tailscale_url", ""));
            root.put("web_storage", webStorageJson != null ? new JSONObject(webStorageJson) : new JSONObject());
            byte[] bytes = root.toString(2).getBytes(StandardCharsets.UTF_8);

            prefs.edit().putString("last_backup_json", root.toString()).apply();

            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    getContentResolver().delete(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                            new String[]{"proscan_presets_backup.json"}
                    );
                } catch (Exception ignored) {}

                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, "proscan_presets_backup.json");
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(bytes);
                        os.close();
                    }
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File file = new File(dir, "proscan_presets_backup.json");
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(bytes);
                fos.close();
            }

            if (showToast) {
                Toast.makeText(this, "Saved to Downloads/proscan_presets_backup.json", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            if (showToast) {
                Toast.makeText(this, "Backup error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void restoreFromJsonString(String jsonStr) {
        try {
            JSONObject root = new JSONObject(jsonStr);
            String loc = root.optString("local_url", "");
            String ts = root.optString("tailscale_url", "");
            JSONObject webStorage = root.optJSONObject("web_storage");

            if (!loc.isEmpty() || !ts.isEmpty()) {
                prefs.edit().putString("local_url", loc).putString("tailscale_url", ts).apply();
            }
            if (webStorage != null) {
                prefs.edit().putString("pending_web_restore", webStorage.toString()).apply();
            }

            Toast.makeText(this, "Presets & IPs Restored! Loading...", Toast.LENGTH_SHORT).show();
            String activeLoc = prefs.getString("local_url", "");
            String activeTs = prefs.getString("tailscale_url", "");
            if (!activeLoc.isEmpty() || !activeTs.isEmpty()) {
                connectSmart(activeLoc, activeTs);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Invalid backup JSON file", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LOAD_JSON && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                InputStream is = getContentResolver().openInputStream(data.getData());
                if (is != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    restoreFromJsonString(sb.toString());
                }
            } catch (Exception e) {
                Toast.makeText(this, "Failed to read backup file", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public class WebAppBridge {
        @JavascriptInterface
        public void setKeepScreenOn(final boolean keepOn) {
            mainHandler.post(() -> {
                userWakeLockEnabled = keepOn;
                if (keepOn) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    webView.setKeepScreenOn(true);
                    Toast.makeText(MainActivity.this, "Screen Wake: ON", Toast.LENGTH_SHORT).show();
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    webView.setKeepScreenOn(false);
                    Toast.makeText(MainActivity.this, "Screen Wake: OFF", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void setBackgroundAudioMode(final boolean enabled) {
            mainHandler.post(() -> {
                setBgServiceRunning(enabled);
                if (enabled) {
                    Toast.makeText(MainActivity.this, "VLC / Screen-Off Stream: ON", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void speakChannel(final String text) {
            mainHandler.post(() -> {
                if (tts != null && ttsReady && text != null && !text.trim().isEmpty()) {
                    if (Build.VERSION.SDK_INT >= 21) {
                        Bundle params = new Bundle();
                        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.35f);
                        tts.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, params, "proscan_tts");
                    } else {
                        tts.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, null);
                    }
                }
            });
        }

        @JavascriptInterface
        public void syncActiveThemeInMemory(final String webStorageJson) {
            try {
                JSONObject root = new JSONObject();
                root.put("local_url", prefs.getString("local_url", ""));
                root.put("tailscale_url", prefs.getString("tailscale_url", ""));
                root.put("web_storage", webStorageJson != null ? new JSONObject(webStorageJson) : new JSONObject());
                prefs.edit().putString("last_backup_json", root.toString()).apply();
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void openArchivePlayer() {
            mainHandler.post(() -> {
                String base = activeServerUrl;
                if (base == null || base.isEmpty()) base = webView.getUrl();
                Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                i.putExtra("server_url", base != null ? base : "");
                startActivity(i);
            });
        }

        @JavascriptInterface
        public void autoSavePresetsJson(final String webStorageJson, final boolean notifyUser) {
            mainHandler.post(() -> saveBackupJsonToDownloads(webStorageJson, notifyUser));
        }

        @JavascriptInterface
        public void showSystemBars() {
            mainHandler.post(() -> {
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                mainHandler.postDelayed(() -> hideSystemUI(), 4500);
            });
        }

        @JavascriptInterface
        public void minimizeApp() {
            mainHandler.post(() -> moveTaskToBack(true));
        }

        @JavascriptInterface
        public void exitApp() {
            mainHandler.post(() -> closeAndExitApp());
        }
    }
private void closeAndExitApp() {
        setBgServiceRunning(false);
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Exception ignored) {}
        try {
            if (webView != null) {
                webView.loadUrl("about:blank");
                webView.stopLoading();
                webView.destroy();
            }
        } catch (Exception ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
        System.exit(0);
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new WebAppBridge(), "AndroidApp");
        webView.setBackgroundColor(Color.BLACK);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectEnhancements();
            }
        });

        mainHandler.removeCallbacks(periodicInjector);
        mainHandler.postDelayed(periodicInjector, 1200);
    }

    private void injectEnhancements() {
        String pendingRestore = prefs.getString("pending_web_restore", "");
        if (!pendingRestore.isEmpty()) {
            prefs.edit().remove("pending_web_restore").apply();
            String safeJson = JSONObject.quote(pendingRestore);
            String restoreJs = "(function(){try{var d=JSON.parse(" + safeJson + ");Object.keys(d).forEach(function(k){localStorage.setItem(k,d[k]);});window.location.reload();}catch(e){}})();";
            webView.evaluateJavascript(restoreJs, null);
            return;
        }

        StringBuilder js = new StringBuilder();
        js.append("(function() {\n");
        js.append("  try {\n");
        js.append("    document.querySelectorAll('video').forEach(function(v) {\n");
        js.append("      try { v.pause(); v.srcObject = null; v.removeAttribute('src'); v.load(); v.remove(); } catch(e) {}\n");
        js.append("    });\n");
        js.append("    window.prepareWakeLockMedia = function() {};\n");
        js.append("    window.requestScreenWake = function() { if (window.AndroidApp) window.AndroidApp.setKeepScreenOn(true); };\n");
        js.append("    window.releaseScreenWake = function() { if (window.AndroidApp) window.AndroidApp.setKeepScreenOn(false); };\n");
        js.append("    window.toggleWakeLock = function() {\n");
        js.append("      var btn = document.getElementById('btn-wake-toggle');\n");
        js.append("      isWakeLockActive = !isWakeLockActive;\n");
        js.append("      if (isWakeLockActive) {\n");
        js.append("        if (btn) { btn.classList.add('active'); btn.setAttribute('aria-pressed', 'true'); }\n");
        js.append("        if (window.AndroidApp) window.AndroidApp.setKeepScreenOn(true);\n");
        js.append("      } else {\n");
        js.append("        if (btn) { btn.classList.remove('active'); btn.setAttribute('aria-pressed', 'false'); }\n");
        js.append("        if (window.AndroidApp) window.AndroidApp.setKeepScreenOn(false);\n");
        js.append("      }\n");
        js.append("    };\n");

        js.append("    window.__syncThemeInMem = function() {\n");
        js.append("      try {\n");
        js.append("        var dump = {};\n");
        js.append("        for (var i = 0; i < localStorage.length; i++) {\n");
        js.append("          var k = localStorage.key(i);\n");
        js.append("          if (k && k.indexOf('proscan_') === 0) dump[k] = localStorage.getItem(k);\n");
        js.append("        }\n");
        js.append("        if (window.AndroidApp && window.AndroidApp.syncActiveThemeInMemory) {\n");
        js.append("          window.AndroidApp.syncActiveThemeInMemory(JSON.stringify(dump));\n");
        js.append("        }\n");
        js.append("      } catch (e) {}\n");
        js.append("    };\n");
        js.append("    window.__syncThemeInMem();\n");

        js.append("    if (!window.__presetAutoBackupHooked) {\n");
        js.append("      window.__presetAutoBackupHooked = true;\n");
        js.append("      window.__exportAllProscanStorage = function(notify) {\n");
        js.append("        try {\n");
        js.append("          var dump = {};\n");
        js.append("          for (var i = 0; i < localStorage.length; i++) {\n");
        js.append("            var k = localStorage.key(i);\n");
        js.append("            if (k && k.indexOf('proscan_') === 0) dump[k] = localStorage.getItem(k);\n");
        js.append("          }\n");
        js.append("          if (window.AndroidApp && window.AndroidApp.autoSavePresetsJson) {\n");
        js.append("            window.AndroidApp.autoSavePresetsJson(JSON.stringify(dump), Boolean(notify));\n");
        js.append("          }\n");
        js.append("        } catch (e) {}\n");
        js.append("      };\n");
        js.append("      if (typeof window.savePresetToSlot === 'function') {\n");
        js.append("        var origSave = window.savePresetToSlot;\n");
        js.append("        window.savePresetToSlot = function(slot, snap) {\n");
        js.append("          origSave(slot, snap);\n");
        js.append("          window.__exportAllProscanStorage(false);\n");
        js.append("        };\n");
        js.append("      }\n");
        js.append("    }\n");

        /* Merge LOG IN / LOG OUT into 1 Smart Button & Convert 2nd Button to PLAYER */
        js.append("    var loginBtn = document.getElementById('proscan-login-open');\n");
        js.append("    var logoutBtn = document.getElementById('proscan-logout-button');\n");
        js.append("    if (loginBtn && logoutBtn && !loginBtn.__mergedAuthHooked) {\n");
        js.append("      var newAuthBtn = loginBtn.cloneNode(true);\n");
        js.append("      newAuthBtn.__mergedAuthHooked = true;\n");
        js.append("      loginBtn.parentNode.replaceChild(newAuthBtn, loginBtn);\n");
        js.append("      function isSuperUserLoggedIn() {\n");
        js.append("        return (document.body.innerText || '').indexOf('Logged In: Super User') !== -1;\n");
        js.append("      }\n");
        js.append("      function syncAuthBtnLabel() {\n");
        js.append("        newAuthBtn.textContent = isSuperUserLoggedIn() ? 'LOG OUT' : 'LOG IN';\n");
        js.append("      }\n");
        js.append("      syncAuthBtnLabel();\n");
        js.append("      setInterval(syncAuthBtnLabel, 1000);\n");
        js.append("      newAuthBtn.addEventListener('click', function() {\n");
        js.append("        if (isSuperUserLoggedIn()) {\n");
        js.append("          var origOut = document.getElementById('input3');\n");
        js.append("          if (origOut) origOut.click();\n");
        js.append("          var p = document.getElementById('proscan-login-panel');\n");
        js.append("          if (p) p.hidden = true;\n");
        js.append("        } else {\n");
        js.append("          var p = document.getElementById('proscan-login-panel');\n");
        js.append("          var pw = document.getElementById('proscan-password');\n");
        js.append("          if (p) p.hidden = false;\n");
        js.append("          if (pw) pw.focus();\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("      var playerBtn = logoutBtn.cloneNode(true);\n");
        js.append("      playerBtn.id = 'btn-open-player-app';\n");
        js.append("      playerBtn.textContent = 'PLAYER';\n");
        js.append("      logoutBtn.parentNode.replaceChild(playerBtn, logoutBtn);\n");
        js.append("      playerBtn.addEventListener('click', function() {\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(25);\n");
        js.append("        if (window.__syncThemeInMem) window.__syncThemeInMem();\n");
        js.append("        if (window.AndroidApp && window.AndroidApp.openArchivePlayer) {\n");
        js.append("          window.AndroidApp.openArchivePlayer();\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("    }\n");

        js.append("    if (!document.getElementById('proscan-v6-upgrade-css')) {\n");
        js.append("      var st = document.createElement('style');\n");
        js.append("      st.id = 'proscan-v6-upgrade-css';\n");
        js.append("      st.textContent = '' +\n");
        js.append("        '.buttons-box{border:1px solid rgba(0,0,0,0.48)!important;border-radius:12px!important;padding:7px!important;background:rgba(0,0,0,0.22)!important;box-shadow:inset 0 2px 6px rgba(0,0,0,0.75),0 1px 0 rgba(255,255,255,0.09)!important;}' +\n");
        js.append("        '.freq-hit-badge{display:none!important;}' +\n");
        js.append("        '#mini-log-table th,#mini-log-table td{padding:3px 2px 3px 4px!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(1),#mini-log-table td:nth-child(1){width:19%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(2),#mini-log-table td:nth-child(2){width:20%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(3),#mini-log-table td:nth-child(3){width:13%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(4),#mini-log-table td:nth-child(4){width:14%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(5),#mini-log-table td:nth-child(5){width:34%!important;}' +\n");
        js.append("        '#mini-log-table tbody tr{cursor:pointer;}#mini-log-table tbody tr:active{background:rgba(255,255,255,0.15)!important;}' +\n");
        js.append("        'html[data-skin=\"whiteline\"]{--ui-bg:radial-gradient(circle at 50% 12%,#26292e 0%,#121417 65%,#070809 100%)!important;--ui-bevel:inset 0 2px 3px rgba(255,255,255,0.35),inset 0 -3px 6px rgba(0,0,0,0.9)!important;--btn-bg:linear-gradient(180deg,#2e3238 0%,#181a1e 55%,#0d0e11 100%)!important;--btn-border:1px solid rgba(255,255,255,0.75)!important;--btn-shadow:0 3px 0 #94a3b8,0 4px 6px rgba(0,0,0,0.85),inset 0 1px 1px rgba(255,255,255,0.45)!important;--btn-shadow-active:0 0 0 #94a3b8,inset 0 2px 5px rgba(0,0,0,0.95)!important;--btn-active-transform:translateY(3px)!important;}' +\n");
        js.append("        'html[data-skin=\"tron\"]{--ui-bg:radial-gradient(circle at 50% 12%,#0c2e36 0%,#06161a 65%,#02080a 100%)!important;--ui-bevel:inset 0 2px 3px rgba(0,255,255,0.4),inset 0 -3px 6px rgba(0,0,0,0.9)!important;--btn-bg:linear-gradient(180deg,#103c47 0%,#071f26 55%,#031014 100%)!important;--btn-border:1px solid #00ffff!important;--btn-shadow:0 3px 0 #008b8b,0 4px 7px rgba(0,0,0,0.85),inset 0 1px 1px rgba(180,255,255,0.45)!important;--btn-shadow-active:0 0 0 #008b8b,inset 0 2px 5px rgba(0,0,0,0.95)!important;--btn-active-transform:translateY(3px)!important;}' +\n");
        js.append("        'html[data-skin=\"neongreen\"]{--ui-bg:radial-gradient(circle at 50% 12%,#11330d 0%,#081906 65%,#030a02 100%)!important;--ui-bevel:inset 0 2px 3px rgba(57,255,20,0.4),inset 0 -3px 6px rgba(0,0,0,0.9)!important;--btn-bg:linear-gradient(180deg,#184212 0%,#0b2108 55%,#051104 100%)!important;--btn-border:1px solid #39ff14!important;--btn-shadow:0 3px 0 #1b8a08,0 4px 7px rgba(0,0,0,0.85),inset 0 1px 1px rgba(190,255,180,0.45)!important;--btn-shadow-active:0 0 0 #1b8a08,inset 0 2px 5px rgba(0,0,0,0.95)!important;--btn-active-transform:translateY(3px)!important;}' +\n");
        js.append("        'html[data-skin=\"amber\"]{--ui-bg:radial-gradient(circle at 50% 12%,#332406 0%,#1a1203 65%,#0a0701 100%)!important;--ui-bevel:inset 0 2px 3px rgba(255,176,0,0.4),inset 0 -3px 6px rgba(0,0,0,0.9)!important;--btn-bg:linear-gradient(180deg,#422f08 0%,#211704 55%,#120c02 100%)!important;--btn-border:1px solid #ffb000!important;--btn-shadow:0 3px 0 #996900,0 4px 7px rgba(0,0,0,0.85),inset 0 1px 1px rgba(255,230,170,0.45)!important;--btn-shadow-active:0 0 0 #996900,inset 0 2px 5px rgba(0,0,0,0.95)!important;--btn-active-transform:translateY(3px)!important;}' +\n");
        js.append("        '.top-leds-left{gap:10px!important;}' +\n");
        js.append("        '#voice-switch-track{width:26px;height:11px;border-radius:999px;background:rgba(0,0,0,0.55);border:1px solid rgba(255,255,255,0.16);box-shadow:inset 0 1px 3px rgba(0,0,0,0.85),0 1px 0 rgba(255,255,255,0.08);padding:1px;display:flex;align-items:center;position:relative;flex-shrink:0;}' +\n");
        js.append("        '#voice-switch-thumb{width:11px;height:7px;border-radius:999px;background:var(--btn-bg);border:var(--btn-border);box-shadow:0 1px 2px rgba(0,0,0,0.75),inset 0 1px 0 rgba(255,255,255,0.2);transform:translateX(0);transition:transform 0.22s cubic-bezier(0.4,0,0.2,1);display:flex;align-items:center;justify-content:center;}' +\n");
        js.append("        '#voice-switch-thumb::after{content:\"\";width:2px;height:3px;border-radius:1px;background:rgba(255,255,255,0.22);}' +\n");
        js.append("        '#btn-voice-toggle.active #voice-switch-thumb{transform:translateX(11px);}' +\n");
        js.append("        '#voice-led{flex-shrink:0;width:10px;height:10px;border-radius:50%;background-color:var(--led-off-bg,#1a1a1a);border:var(--led-off-border,2px solid #000);box-shadow:var(--led-off-shadow,inset 1px 1px 3px rgba(0,0,0,0.8),0 1px 1px rgba(255,255,255,0.1));transition:all 0.3s ease;}' +\n");
        js.append("        '@keyframes voice-led-pulse-green{0%,100%{background-color:#10b981;border-color:#a7f3d0;box-shadow:inset 1px 1px 2px rgba(255,255,255,0.9),0 0 0 1px rgba(0,0,0,0.65),0 0 8px #10b981,0 0 14px rgba(16,185,129,0.75);}50%{background-color:#065f46;border-color:#059669;box-shadow:inset 1px 1px 2px rgba(255,255,255,0.35),0 0 0 1px rgba(0,0,0,0.65),0 0 2px rgba(16,185,129,0.25);}}' +\n");
        js.append("        '#btn-voice-toggle.active #voice-led{border-style:solid!important;border-width:2px!important;animation:voice-led-pulse-green 3.8s ease-in-out infinite!important;}' +\n");
        js.append("        '#btn-hardware-exit{display:inline-flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;background:transparent;border:none;padding:0;margin:0 0 0 2px;cursor:pointer;touch-action:manipulation;}#btn-hardware-exit .exit-cap{width:24px;height:11px;border-radius:4px;background:linear-gradient(180deg,#b91c1c,#7f1d1d);border:1px solid #450a0a;box-shadow:0 1px 2px rgba(0,0,0,0.75),inset 0 1px 0 rgba(255,255,255,0.28);display:flex;align-items:center;justify-content:center;}#btn-hardware-exit .exit-cap::after{content:\"\";width:8px;height:2px;border-radius:1px;background:rgba(255,220,220,0.85);}#btn-hardware-exit:active .exit-cap{transform:translateY(1px);filter:brightness(1.2);}';\n");
        js.append("      document.head.appendChild(st);\n");
        js.append("    }\n");

        /* Add VOICE Switch + LED next to WAKE */
        js.append("    var ledsLeft = document.querySelector('.top-leds-left');\n");
        js.append("    if (ledsLeft && !document.getElementById('btn-voice-toggle')) {\n");
        js.append("      window.__voiceReadoutEnabled = false;\n");
        js.append("      window.__lastSpokenChannel = '';\n");
        js.append("      window.__lastSpokenTime = 0;\n");
        js.append("      function speakTextNow(txt) {\n");
        js.append("        if (window.AndroidApp && typeof window.AndroidApp.speakChannel === 'function') {\n");
        js.append("          window.AndroidApp.speakChannel(txt);\n");
        js.append("        }\n");
        js.append("      }\n");
        js.append("      var vBtn = document.createElement('button');\n");
        js.append("      vBtn.id = 'btn-voice-toggle';\n");
        js.append("      vBtn.className = 'wake-btn-wrapper';\n");
        js.append("      vBtn.type = 'button';\n");
        js.append("      vBtn.innerHTML = '<div class=\"wake-switch-col\"><div id=\"voice-switch-track\"><div id=\"voice-switch-thumb\"></div></div><span class=\"rx-label\">VOICE</span></div><div id=\"voice-led\"></div>';\n");
        js.append("      vBtn.addEventListener('click', function() {\n");
        js.append("        window.__voiceReadoutEnabled = !window.__voiceReadoutEnabled;\n");
        js.append("        vBtn.classList.toggle('active', window.__voiceReadoutEnabled);\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(25);\n");
        js.append("        if (window.__voiceReadoutEnabled) {\n");
        js.append("          speakTextNow('Voice readout on');\n");
        js.append("          if (typeof showDisplayHud === 'function') showDisplayHud('VOICE READOUT: ON', 1400);\n");
        js.append("        } else {\n");
        js.append("          if (typeof showDisplayHud === 'function') showDisplayHud('VOICE READOUT: OFF', 1400);\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("      ledsLeft.appendChild(vBtn);\n");
        js.append("      var logBody = document.getElementById('mini-log-data');\n");
        js.append("      if (logBody) {\n");
        js.append("        new MutationObserver(function() {\n");
        js.append("          if (!window.__voiceReadoutEnabled) return;\n");
        js.append("          var newRow = logBody.querySelector('tr.new-log-row');\n");
        js.append("          if (newRow && newRow.children.length >= 5) {\n");
        js.append("            var nameTd = newRow.children[4];\n");
        js.append("            var rawTitle = nameTd.getAttribute('title') || nameTd.innerText || '';\n");
        js.append("            var closeParen = rawTitle.indexOf(')');\n");
        js.append("            var chName = (closeParen !== -1 ? rawTitle.slice(closeParen + 1) : rawTitle).trim();\n");
        js.append("            if (!chName || chName === '---') chName = (newRow.children[1].innerText || '').trim();\n");
        js.append("            var now = Date.now();\n");
        js.append("            if (chName && (chName !== window.__lastSpokenChannel || (now - window.__lastSpokenTime) > 15000)) {\n");
        js.append("              window.__lastSpokenChannel = chName;\n");
        js.append("              window.__lastSpokenTime = now;\n");
        js.append("              setTimeout(function() {\n");
        js.append("                if (window.__voiceReadoutEnabled) speakTextNow(chName);\n");
        js.append("              }, 350);\n");
        js.append("            }\n");
        js.append("          }\n");
        js.append("        }).observe(logBody, { childList: true });\n");
        js.append("      }\n");
        js.append("    }\n");

        /* Tap Log Row to Flash Full Name + Hit Count on HUD */
        js.append("    var logTb = document.getElementById('mini-log-data');\n");
        js.append("    if (logTb && !logTb.__hitHudHooked) {\n");
        js.append("      logTb.__hitHudHooked = true;\n");
        js.append("      logTb.addEventListener('click', function(e) {\n");
        js.append("        var tr = e.target.closest('tr');\n");
        js.append("        if (!tr || tr.children.length < 5) return;\n");
        js.append("        var nameTd = tr.children[4];\n");
        js.append("        var badge = nameTd.querySelector('.freq-hit-badge');\n");
        js.append("        var hitsText = badge ? badge.innerText.replace('×', '') : '1';\n");
        js.append("        var rawTitle = nameTd.getAttribute('title') || nameTd.innerText || '';\n");
        js.append("        var closeParen = rawTitle.indexOf(')');\n");
        js.append("        var cleanName = (closeParen !== -1 ? rawTitle.slice(closeParen + 1) : rawTitle).trim();\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(15);\n");
        js.append("        if (typeof showDisplayHud === 'function') {\n");
        js.append("          showDisplayHud(cleanName + ' • HITS: ' + hitsText, 2200);\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("    }\n");

        /* Single AUDIO button */
        js.append("    var oldAudioBtn = document.getElementById('btn-audio-toggle');\n");
        js.append("    if (oldAudioBtn && !oldAudioBtn.__vlcUnifiedHooked) {\n");
        js.append("      var aBtn = oldAudioBtn.cloneNode(true);\n");
        js.append("      aBtn.__vlcUnifiedHooked = true;\n");
        js.append("      oldAudioBtn.parentNode.replaceChild(aBtn, oldAudioBtn);\n");
        js.append("      var isVlcMode = false;\n");
        js.append("      function renderAudioLabel() {\n");
        js.append("        var cone = isVlcMode ? ' <span style=\"color:#ff8c00;font-size:10px;margin-left:2px;\">&#9650;</span>' : '';\n");
        js.append("        aBtn.innerHTML = '<span class=\"inline-led\" id=\"audio-inline-led\"></span> AUDIO' + cone;\n");
        js.append("      }\n");
        js.append("      function setAudioPlaying(play, vlcBg) {\n");
        js.append("        var player = document.getElementById('audio_player');\n");
        js.append("        isVlcMode = Boolean(play && vlcBg);\n");
        js.append("        if (play) {\n");
        js.append("          isConnected = true;\n");
        js.append("          if (player && player.paused) { player.play(); if (typeof setupAudioDetection === 'function') setupAudioDetection(); }\n");
        js.append("          aBtn.classList.add('active');\n");
        js.append("          if (window.AndroidApp) window.AndroidApp.setBackgroundAudioMode(isVlcMode);\n");
        js.append("        } else {\n");
        js.append("          isConnected = false; isVlcMode = false;\n");
        js.append("          if (player && !player.paused) player.pause();\n");
        js.append("          aBtn.classList.remove('active');\n");
        js.append("          if (window.AndroidApp) window.AndroidApp.setBackgroundAudioMode(false);\n");
        js.append("        }\n");
        js.append("        renderAudioLabel();\n");
        js.append("      }\n");
        js.append("      var aTimer = null, aLong = false;\n");
        js.append("      aBtn.addEventListener('pointerdown', function() {\n");
        js.append("        clearTimeout(aTimer); aLong = false;\n");
        js.append("        aTimer = setTimeout(function() {\n");
        js.append("          aTimer = null; aLong = true;\n");
        js.append("          if (navigator.vibrate) navigator.vibrate(40);\n");
        js.append("          if (!isConnected) setAudioPlaying(true, true);\n");
        js.append("          else setAudioPlaying(true, !isVlcMode);\n");
        js.append("        }, 500);\n");
        js.append("      });\n");
        js.append("      aBtn.addEventListener('pointerup', function() {\n");
        js.append("        var wasActive = aTimer !== null; clearTimeout(aTimer); aTimer = null;\n");
        js.append("        if (wasActive && !aLong) {\n");
        js.append("          if (!isConnected) setAudioPlaying(true, false);\n");
        js.append("          else setAudioPlaying(false, false);\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("      aBtn.addEventListener('pointerleave', function() { clearTimeout(aTimer); aTimer = null; });\n");
        js.append("      aBtn.addEventListener('pointercancel', function() { clearTimeout(aTimer); aTimer = null; });\n");
        js.append("    }\n");

        /* Hardware EXIT button */
        js.append("    var ledsRight = document.querySelector('.top-leds-right');\n");
        js.append("    if (ledsRight && !document.getElementById('btn-hardware-exit')) {\n");
        js.append("      var exitBtn = document.createElement('button');\n");
        js.append("      exitBtn.id = 'btn-hardware-exit';\n");
        js.append("      exitBtn.type = 'button';\n");
        js.append("      exitBtn.innerHTML = '<div class=\"exit-cap\"></div><span class=\"rx-label\">EXIT</span>';\n");
        js.append("      ledsRight.appendChild(exitBtn);\n");
        js.append("      var exTimer = null, exLong = false, lastTapTime = 0;\n");
        js.append("      exitBtn.addEventListener('pointerdown', function() {\n");
        js.append("        clearTimeout(exTimer); exLong = false;\n");
        js.append("        exTimer = setTimeout(function() {\n");
        js.append("          exTimer = null; exLong = true;\n");
        js.append("          if (navigator.vibrate) navigator.vibrate([45, 60, 55]);\n");
        js.append("          if (typeof isConnected !== 'undefined' && isConnected) {\n");
        js.append("            var p = document.getElementById('audio_player'); if (p && !p.paused) p.pause();\n");
        js.append("            isConnected = false; var ab = document.getElementById('btn-audio-toggle'); if (ab) ab.classList.remove('active');\n");
        js.append("          }\n");
        js.append("          if (typeof isDisplayOn !== 'undefined' && isDisplayOn && typeof toggleDisplay === 'function') toggleDisplay();\n");
        js.append("          setTimeout(function() { if (window.AndroidApp && window.AndroidApp.exitApp) window.AndroidApp.exitApp(); }, 550);\n");
        js.append("        }, 550);\n");
        js.append("      });\n");
        js.append("      exitBtn.addEventListener('pointerup', function() {\n");
        js.append("        var wasActive = exTimer !== null; clearTimeout(exTimer); exTimer = null;\n");
        js.append("        if (wasActive && !exLong) {\n");
        js.append("          var now = Date.now();\n");
        js.append("          if (now - lastTapTime < 420) {\n");
        js.append("            lastTapTime = 0;\n");
        js.append("            if (window.AndroidApp && window.AndroidApp.minimizeApp) window.AndroidApp.minimizeApp();\n");
        js.append("          } else {\n");
        js.append("            lastTapTime = now;\n");
        js.append("            if (window.AndroidApp && window.AndroidApp.showSystemBars) window.AndroidApp.showSystemBars();\n");
        js.append("          }\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("      exitBtn.addEventListener('pointerleave', function() { clearTimeout(exTimer); exTimer = null; });\n");
        js.append("      exitBtn.addEventListener('pointercancel', function() { clearTimeout(exTimer); exTimer = null; });\n");
        js.append("    }\n");

        /* Update GUIDE */
        js.append("    var mBody = document.querySelector('#proscan-manual-panel .manual-body');\n");
        js.append("    if (mBody && !document.getElementById('manual-v6-sec')) {\n");
        js.append("      var sec = document.createElement('div');\n");
        js.append("      sec.id = 'manual-v6-sec';\n");
        js.append("      sec.className = 'manual-section';\n");
        js.append("      sec.innerHTML = '<h3>7. Archive Player, Voice Readout &amp; Exit Controls</h3><ul class=\"manual-list\">' +\n");
        js.append("        '<li><span class=\"manual-key\">LOG IN / LOG OUT</span> Single smart button that switches between Log In and Log Out automatically.</li>' +\n");
        js.append("        '<li><span class=\"manual-key\">PLAYER</span> Opens the dedicated <strong>Archive Recordings Player</strong> matching your active radio theme. Tap a recording to select/highlight it, then press <strong>PLAY</strong>.</li>' +\n");
        js.append("        '<li><span class=\"manual-key\">VOICE</span> <strong>Slide Switch &amp; Green Pulsating LED:</strong> Announces the Channel Name out loud via Text-To-Speech whenever a new transmission is logged.</li>' +\n");
        js.append("        '<li><span class=\"manual-key\">AUDIO</span> <strong>Short Tap:</strong> Turns normal radio audio ON/OFF. <strong>Long Press (0.5s):</strong> Switches to <strong>VLC / Screen-Off Mode</strong> (<span class=\"manual-key\">AUDIO &#9650;</span>).</li>' +\n");
        js.append("        '<li><span class=\"manual-key\">EXIT</span> <strong>Single Tap</strong> shows Android bars; <strong>Double Tap</strong> minimizes app; <strong>Long Press (0.5s)</strong> powers down and closes app.</li></ul>';\n");
        js.append("      mBody.appendChild(sec);\n");
        js.append("    }\n");
        js.append("  } catch (e) {}\n");
        js.append("})();");

        webView.evaluateJavascript(js.toString(), null);
    }

    private String normalizeUrl(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        return u;
    }

    private void showPresetsBackupMenu() {
        String[] options = new String[]{
                "Save Backup to Downloads (proscan_presets_backup.json)",
                "Load Backup File from Downloads"
        };
        new AlertDialog.Builder(this)
                .setTitle("Presets & IP Backup (JSON)")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        if (webView != null) {
                            webView.evaluateJavascript("if(window.__exportAllProscanStorage)window.__exportAllProscanStorage(true);", null);
                        }
                    } else if (which == 1) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        startActivityForResult(intent, REQ_LOAD_JSON);
                    }
                })
                .setNegativeButton("Back", null)
                .show();
    }

    private void showSetupDialog(boolean isFirstRun) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        TextView label1 = new TextView(this);
        label1.setText("Local Wi-Fi URL (e.g. 192.168.1.31:5000):");
        layout.addView(label1);

        EditText localInput = new EditText(this);
        localInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        localInput.setHint("http://192.168.1.31:5000");
        localInput.setText(prefs.getString("local_url", ""));
        layout.addView(localInput);

        TextView label2 = new TextView(this);
        label2.setText("\nTailscale URL (e.g. 100.85.223.120:5000):");
        layout.addView(label2);

        EditText tsInput = new EditText(this);
        tsInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        tsInput.setHint("http://100.85.223.120:5000");
        tsInput.setText(prefs.getString("tailscale_url", ""));
        layout.addView(tsInput);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("ProScan Connection Setup");
        builder.setView(layout);
        builder.setCancelable(!isFirstRun);

        builder.setPositiveButton("Save & Connect", (dialog, which) -> {
            String loc = normalizeUrl(localInput.getText().toString());
            String ts = normalizeUrl(tsInput.getText().toString());
            if (loc.isEmpty() && ts.isEmpty()) {
                Toast.makeText(this, "Please enter at least one URL", Toast.LENGTH_LONG).show();
                showSetupDialog(true);
                return;
            }
            prefs.edit().putString("local_url", loc).putString("tailscale_url", ts).apply();
            connectSmart(loc, ts);
        });

        builder.setNeutralButton("Presets JSON", (dialog, which) -> showPresetsBackupMenu());

        if (!isFirstRun) {
            builder.setNegativeButton("Cancel", null);
        }
        builder.show();
    }

    private void connectSmart(String localUrl, String tailscaleUrl) {
        if (!localUrl.isEmpty() && tailscaleUrl.isEmpty()) {
            activeServerUrl = localUrl;
            webView.loadUrl(localUrl);
            return;
        }
        if (localUrl.isEmpty() && !tailscaleUrl.isEmpty()) {
            activeServerUrl = tailscaleUrl;
            webView.loadUrl(tailscaleUrl);
            return;
        }
        Toast.makeText(this, "Checking Local Wi-Fi...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            boolean localReachable = isUrlReachable(localUrl, 1500);
            mainHandler.post(() -> {
                if (localReachable) {
                    activeServerUrl = localUrl;
                    Toast.makeText(this, "Connected via Local Wi-Fi", Toast.LENGTH_SHORT).show();
                    webView.loadUrl(localUrl);
                } else {
                    activeServerUrl = tailscaleUrl;
                    Toast.makeText(this, "Switching to Tailscale...", Toast.LENGTH_SHORT).show();
                    webView.loadUrl(tailscaleUrl);
                }
            });
        });
    }

    private boolean isUrlReachable(String urlStr, int timeoutMs) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return (code >= 200 && code < 500);
        } catch (Exception e) {
            return false;
        }
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    public void onBackPressed() {
        showSetupDialog(false);
    }
}