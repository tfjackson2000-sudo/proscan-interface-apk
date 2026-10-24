package com.proscan.interfaceapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private WebView webView;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String embeddedUiBase64 = null;
    private boolean userWakeLockEnabled = false;
    private boolean bgAudioModeEnabled = false;

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
        loadEmbeddedUiAsset();

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

    private void loadEmbeddedUiAsset() {
        try {
            InputStream is = getAssets().open("custom_ui.html");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[4096];
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            is.close();
            embeddedUiBase64 = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            embeddedUiBase64 = null;
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
        public void showSystemBars() {
            mainHandler.post(() -> {
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                /* Automatically re-hide bars after 4.5 seconds if user doesn't double-tap */
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
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                mainHandler.postDelayed(() -> injectCustomUiAndWakeBridge(), 800);
                mainHandler.postDelayed(() -> injectCustomUiAndWakeBridge(), 2000);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectCustomUiAndWakeBridge();
            }
        });
    }
private void injectCustomUiAndWakeBridge() {
        StringBuilder js = new StringBuilder();
        js.append("(function() {\n");

        if (embeddedUiBase64 != null && !embeddedUiBase64.isEmpty()) {
            js.append("  if (!document.getElementById('custom-scanner-ui-wrapper')) {\n");
            js.append("    try {\n");
            js.append("      var raw = atob('").append(embeddedUiBase64).append("');\n");
            js.append("      var bytes = new Uint8Array(raw.length);\n");
            js.append("      for (var i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);\n");
            js.append("      var html = new TextDecoder('utf-8').decode(bytes);\n");
            js.append("      var doc = new DOMParser().parseFromString(html, 'text/html');\n");
            js.append("      var htmlAttrs = doc.documentElement.attributes;\n");
            js.append("      for (var a = 0; a < htmlAttrs.length; a++) {\n");
            js.append("        document.documentElement.setAttribute(htmlAttrs[a].name, htmlAttrs[a].value);\n");
            js.append("      }\n");
            js.append("      doc.head.querySelectorAll('link, style, meta[name=\"viewport\"]').forEach(function(el) {\n");
            js.append("        document.head.appendChild(el.cloneNode(true));\n");
            js.append("      });\n");
            js.append("      Array.from(doc.body.children).forEach(function(el) {\n");
            js.append("        if (el.tagName.toLowerCase() !== 'script') document.body.appendChild(el.cloneNode(true));\n");
            js.append("      });\n");
            js.append("      doc.querySelectorAll('script').forEach(function(oldScript) {\n");
            js.append("        var newScript = document.createElement('script');\n");
            js.append("        newScript.textContent = oldScript.textContent;\n");
            js.append("        document.body.appendChild(newScript);\n");
            js.append("      });\n");
            js.append("      window.dispatchEvent(new Event('load'));\n");
            js.append("    } catch (e) {}\n");
            js.append("  }\n");
        }

        js.append("  try {\n");
        js.append("    if (typeof releaseScreenWake === 'function') releaseScreenWake();\n");
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

        /* Inject 3D Neon Skins, Uniform Recessed Tactical Keypad Well, Tape Deck CSS & Hardware Exit CSS */
        js.append("    if (!document.getElementById('proscan-v2-upgrade-css')) {\n");
        js.append("      var st = document.createElement('style');\n");
        js.append("      st.id = 'proscan-v2-upgrade-css';\n");
        js.append("      st.textContent = '' +\n");
        js.append("        '.buttons-box{border:1px solid rgba(0,0,0,0.48)!important;border-radius:12px!important;padding:7px!important;background:rgba(0,0,0,0.18)!important;box-shadow:inset 0 2px 5px rgba(0,0,0,0.65),0 1px 0 rgba(255,255,255,0.08)!important;}' +\n");
        js.append("        'html[data-skin=\"whiteline\"]{--buttons-box-border:1px solid rgba(255,255,255,0.45);--btn-bg:linear-gradient(180deg,#262626 0%,#121212 55%,#080808 100%);--btn-border:1px solid rgba(255,255,255,0.85);--btn-shadow:0 2px 0 #ffffff,0 4px 6px rgba(0,0,0,0.85),inset 0 1px 1px rgba(255,255,255,0.35);--btn-shadow-active:0 0 0 #ffffff,inset 0 2px 5px rgba(0,0,0,0.9);--btn-active-transform:translateY(2px);}' +\n");
        js.append("        'html[data-skin=\"tron\"]{--buttons-box-border:1px solid rgba(0,255,255,0.45);--btn-bg:linear-gradient(180deg,#082f38 0%,#04181d 55%,#020b0e 100%);--btn-border:1px solid #00ffff;--btn-shadow:0 2px 0 #00b3b3,0 3px 8px rgba(0,255,255,0.28),inset 0 1px 1px rgba(180,255,255,0.35);--btn-shadow-active:0 0 0 #00b3b3,inset 0 2px 5px rgba(0,0,0,0.9);--btn-active-transform:translateY(2px);}' +\n");
        js.append("        'html[data-skin=\"neongreen\"]{--buttons-box-border:1px solid rgba(57,255,20,0.45);--btn-bg:linear-gradient(180deg,#0d3309 0%,#061a04 55%,#030d02 100%);--btn-border:1px solid #39ff14;--btn-shadow:0 2px 0 #22a80b,0 3px 8px rgba(57,255,20,0.28),inset 0 1px 1px rgba(190,255,180,0.35);--btn-shadow-active:0 0 0 #22a80b,inset 0 2px 5px rgba(0,0,0,0.9);--btn-active-transform:translateY(2px);}' +\n");
        js.append("        'html[data-skin=\"amber\"]{--buttons-box-border:1px solid rgba(255,176,0,0.45);--btn-bg:linear-gradient(180deg,#332404 0%,#1a1202 55%,#0d0901 100%);--btn-border:1px solid #ffb000;--btn-shadow:0 2px 0 #b37b00,0 3px 8px rgba(255,176,0,0.28),inset 0 1px 1px rgba(255,230,170,0.35);--btn-shadow-active:0 0 0 #b37b00,inset 0 2px 5px rgba(0,0,0,0.9);--btn-active-transform:translateY(2px);}' +\n");
        js.append("        '.top-right-group.has-tape-btn{grid-template-columns:repeat(3,minmax(0,1fr))!important;}' +\n");
        js.append("        '#mini-log-table tbody tr{cursor:pointer;}#mini-log-table tbody tr:active{background:rgba(255,255,255,0.14)!important;}' +\n");
        js.append("        '#btn-hardware-exit{display:inline-flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;background:transparent;border:none;padding:0;margin:0 0 0 2px;cursor:pointer;touch-action:manipulation;}#btn-hardware-exit .exit-cap{width:24px;height:11px;border-radius:4px;background:linear-gradient(180deg,#b91c1c,#7f1d1d);border:1px solid #450a0a;box-shadow:0 1px 2px rgba(0,0,0,0.75),inset 0 1px 0 rgba(255,255,255,0.28);display:flex;align-items:center;justify-content:center;}#btn-hardware-exit .exit-cap::after{content:\"\";width:8px;height:2px;border-radius:1px;background:rgba(255,220,220,0.85);}#btn-hardware-exit:active .exit-cap{transform:translateY(1px);filter:brightness(1.2);}' +\n");
        js.append("        '#proscan-tape-panel{position:absolute;z-index:115;inset:10px;border:1px solid #525b66;border-radius:16px;background:linear-gradient(180deg,#1b1f24 0%,#0e1114 100%);box-shadow:0 14px 36px rgba(0,0,0,0.9),inset 0 1px 0 rgba(255,255,255,0.12);color:#e2e8f0;display:flex;flex-direction:column;overflow:hidden;}#proscan-tape-panel[hidden]{display:none!important;}' +\n");
        js.append("        '.tape-deck-well{margin:8px 12px 6px;padding:8px 10px;border-radius:12px;background:linear-gradient(145deg,#121518,#242930);border:2px solid #090b0d;box-shadow:inset 0 3px 8px rgba(0,0,0,0.9),0 1px 0 rgba(255,255,255,0.08);display:flex;flex-direction:column;gap:6px;flex-shrink:0;}' +\n");
        js.append("        '.cassette-shell{background:linear-gradient(180deg,#d97706 0%,#b45309 32%,#27272a 32%,#18181b 100%);border:2px solid #09090b;border-radius:8px;padding:5px 10px 6px;box-shadow:0 3px 6px rgba(0,0,0,0.7),inset 0 1px 0 rgba(255,255,255,0.35);position:relative;}' +\n");
        js.append("        '.cassette-label-top{display:flex;justify-content:space-between;align-items:center;font-family:monospace;font-size:10px;font-weight:800;color:#111827;background:rgba(254,243,199,0.92);padding:2px 6px;border-radius:4px;margin-bottom:5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}' +\n");
        js.append("        '.cassette-window{background:#090b0e;border:2px solid #3f3f46;border-radius:20px;height:44px;display:flex;align-items:center;justify-content:space-around;padding:0 14px;position:relative;box-shadow:inset 0 2px 6px rgba(0,0,0,0.95);}' +\n");
        js.append("        '.cassette-reel{width:32px;height:32px;border-radius:50%;border:3px solid #e2e8f0;background:conic-gradient(from 0deg,transparent 0deg 40deg,#e2e8f0 40deg 60deg,transparent 60deg 160deg,#e2e8f0 160deg 180deg,transparent 180deg 280deg,#e2e8f0 280deg 300deg,transparent 300deg 360deg),radial-gradient(circle,#090b0e 32%,#475569 34%,#1e293b 68%);box-shadow:0 0 4px rgba(0,0,0,0.8);}' +\n");
        js.append("        '@keyframes reelSpin{from{transform:rotate(0deg);}to{transform:rotate(-360deg);}}.cassette-reel.spinning{animation:reelSpin 1.8s linear infinite;}' +\n");
        js.append("        '.cassette-tape-mid{width:58px;height:18px;background:repeating-linear-gradient(0deg,#27170e 0px,#3b2314 2px,#1a0f09 4px);border-top:1px solid #57534e;border-bottom:1px solid #57534e;display:flex;align-items:center;justify-content:center;font-family:monospace;font-size:9px;color:#39ff14;text-shadow:0 0 4px rgba(57,255,20,0.7);}' +\n");
        js.append("        '.tape-transport-row{display:grid;grid-template-columns:repeat(5,1fr);gap:5px;}.tape-btn{height:28px;border-radius:5px;border:1px solid #090a0c;background:linear-gradient(180deg,#374151 0%,#1f2937 55%,#111827 100%);color:#e2e8f0;font-family:system-ui,sans-serif;font-size:9.5px;font-weight:800;cursor:pointer;box-shadow:0 2px 0 #070809,inset 0 1px 0 rgba(255,255,255,0.2);display:flex;align-items:center;justify-content:center;gap:3px;}.tape-btn:active,.tape-btn.active{transform:translateY(1px);background:linear-gradient(180deg,#1e293b,#0f172a);color:#38bdf8;border-color:#38bdf8;}' +\n");
        js.append("        '.tape-filter-bar{display:flex;gap:6px;padding:0 12px 6px;flex-shrink:0;}#tape-search-input{flex:1;height:26px;border-radius:6px;border:1px solid #475569;background:#0f1318;color:#f8fafc;padding:0 8px;font-size:11px;font-family:monospace;}' +\n");
        js.append("        '.tape-list-wrap{flex:1;overflow-y:auto;margin:0 12px 10px;border:1px solid rgba(255,255,255,0.1);border-radius:8px;background:#090c10;scrollbar-width:thin;scrollbar-color:#475569 #090c10;}.tape-row{display:grid;grid-template-columns:58px 66px 38px 1fr;gap:4px;align-items:center;padding:6px 8px;border-bottom:1px solid rgba(255,255,255,0.06);font-family:monospace;font-size:10px;color:#cbd5e1;cursor:pointer;}.tape-row:hover,.tape-row:active{background:rgba(56,189,248,0.12);}.tape-row.playing{background:rgba(57,255,20,0.16);color:#39ff14;font-weight:800;}.tape-row span{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}';\n");
        js.append("      document.head.appendChild(st);\n");
        js.append("    }\n");

        /* Single AUDIO button with Short Press (Normal Audio / Off) & Long Press (VLC Screen-Off Mode with Cone Icon) */
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

        /* Hardware EXIT button: 1 Tap = Show System Bars, 2 Quick Taps = Minimize App, Long Press = Power Off & Exit */
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

        /* Add TAPE Button + Retro Cassette Tape Deck Modal + Update GUIDE */
        js.append("    var topRightGroup = document.querySelector('.top-right-group');\n");
        js.append("    var dispToggleBtn = document.getElementById('btn-disp-toggle');\n");
        js.append("    if (topRightGroup && dispToggleBtn && !document.getElementById('btn-tape-open')) {\n");
        js.append("      topRightGroup.classList.add('has-tape-btn');\n");
        js.append("      var tapeBtn = document.createElement('button');\n");
        js.append("      tapeBtn.id = 'btn-tape-open';\n");
        js.append("      tapeBtn.className = 'btn state-btn';\n");
        js.append("      tapeBtn.type = 'button';\n");
        js.append("      tapeBtn.innerHTML = '<span class=\"inline-led\" id=\"tape-inline-led\"></span> TAPE';\n");
        js.append("      topRightGroup.insertBefore(tapeBtn, dispToggleBtn);\n");
        js.append("    }\n");
        js.append("    var scannerUi = document.getElementById('custom-scanner-ui');\n");
        js.append("    if (scannerUi && !document.getElementById('proscan-tape-panel')) {\n");
        js.append("      var tp = document.createElement('div');\n");
        js.append("      tp.id = 'proscan-tape-panel'; tp.hidden = true;\n");
        js.append("      tp.innerHTML = '<div class=\"manual-header\"><span class=\"manual-header-title\">PROSCAN TAPE ARCHIVE</span><button class=\"manual-close-btn\" type=\"button\" id=\"btn-tape-close\">CLOSE X</button></div>' +\n");
        js.append("        '<div class=\"tape-deck-well\"><div class=\"cassette-shell\"><div class=\"cassette-label-top\"><span id=\"tape-label-title\">SELECT A RECORDING BELOW</span><span id=\"tape-label-freq\">---.----</span></div>' +\n");
        js.append("        '<div class=\"cassette-window\"><div class=\"cassette-reel\" id=\"tape-reel-left\"></div><div class=\"cassette-tape-mid\" id=\"tape-counter-hud\">00:00</div><div class=\"cassette-reel\" id=\"tape-reel-right\"></div></div></div>' +\n");
        js.append("        '<div class=\"tape-transport-row\"><button class=\"tape-btn\" type=\"button\" id=\"tape-btn-prev\">|&lt;&lt; PREV</button><button class=\"tape-btn\" type=\"button\" id=\"tape-btn-play\">&#9654; PLAY</button><button class=\"tape-btn\" type=\"button\" id=\"tape-btn-stop\">&#9632; STOP</button><button class=\"tape-btn\" type=\"button\" id=\"tape-btn-next\">NEXT &gt;&gt;|</button><button class=\"tape-btn\" type=\"button\" id=\"tape-btn-auto\">AUTO</button></div></div>' +\n");
        js.append("        '<div class=\"tape-filter-bar\"><input id=\"tape-search-input\" type=\"text\" placeholder=\"Filter by channel or frequency...\" autocomplete=\"off\"><button class=\"tape-btn\" type=\"button\" id=\"tape-btn-refresh\" style=\"padding:0 10px;height:26px;\">&#8635;</button></div>' +\n");
        js.append("        '<div class=\"tape-list-wrap\" id=\"tape-list-container\"><div style=\"padding:18px;text-align:center;opacity:0.6;font-size:11px;\">Loading Recording Data.txt...</div></div>';\n");
        js.append("      ['pointerdown','pointerup','click','touchstart','touchend'].forEach(function(ev){tp.addEventListener(ev,function(e){e.stopPropagation();});});\n");
        js.append("      scannerUi.appendChild(tp);\n");
        js.append("      var tapeAudio = new Audio(), recs = [], filt = [], curIdx = -1, autoNext = false;\n");
        js.append("      function setSpin(s){var l=document.getElementById('tape-reel-left'),r=document.getElementById('tape-reel-right'),tb=document.getElementById('btn-tape-open'),pb=document.getElementById('tape-btn-play');if(l)l.classList.toggle('spinning',s);if(r)r.classList.toggle('spinning',s);if(tb)tb.classList.toggle('active',s);if(pb){pb.classList.toggle('active',s);pb.innerHTML=s?'|| PAUSE':'&#9654; PLAY';}}\n");
        js.append("      function duck(d){var lp=document.getElementById('audio_player');if(!lp)return;if(d){lp.__pv=lp.volume;lp.volume=0.08;}else if(typeof lp.__pv==='number'){lp.volume=lp.__pv;}}\n");
        js.append("      tapeAudio.addEventListener('timeupdate',function(){var c=document.getElementById('tape-counter-hud');if(!c)return;var s=Math.floor(tapeAudio.currentTime||0);c.innerText=String(Math.floor(s/60)).padStart(2,'0')+':'+String(s%60).padStart(2,'0');});\n");
        js.append("      tapeAudio.addEventListener('ended',function(){setSpin(false);duck(false);if(autoNext&&curIdx+1<filt.length)playIdx(curIdx+1);});\n");
        js.append("      tapeAudio.addEventListener('error',function(){setSpin(false);duck(false);var l=document.getElementById('tape-label-title');if(l)l.innerText='FILE NOT FOUND ON SERVER';});\n");
        js.append("      function buildUrl(r){var f=r.folderPath.replace(/\\\\/g,'/'),sub='',ix=f.toLowerCase().indexOf('/recordings/');if(ix!==-1)sub=f.slice(ix+12);else{var ps=f.split('/').filter(Boolean);if(ps.length>=2)sub=ps.slice(-2).join('/')+'/';}if(sub&&!sub.endsWith('/'))sub+='/';return ('Recordings/'+sub+r.fileName).split('/').map(encodeURIComponent).join('/');}\n");
        js.append("      function renderList(){var w=document.getElementById('tape-list-container');if(!w)return;var si=document.getElementById('tape-search-input'),q=((si&&si.value)||'').trim().toLowerCase();filt=recs.filter(function(r){return !q||r.name.toLowerCase().includes(q)||r.freq.toLowerCase().includes(q)||r.timeDisplay.toLowerCase().includes(q);});if(!filt.length){w.innerHTML='<div style=\"padding:18px;text-align:center;opacity:0.6;font-size:11px;\">No matching recordings found.</div>';return;}var h='';for(var i=0;i<filt.length;i++){var r=filt[i],ac=(i===curIdx&&!tapeAudio.paused)?'playing':'';h+='<div class=\"tape-row '+ac+'\" data-idx=\"'+i+'\"><span>'+r.timeDisplay+'</span><span>'+r.freq+'</span><span>'+r.dur+'</span><span title=\"'+r.name+'\">'+r.name+'</span></div>';}w.innerHTML=h;w.querySelectorAll('.tape-row').forEach(function(el){el.addEventListener('click',function(){playIdx(parseInt(el.getAttribute('data-idx'),10));});});}\n");
        js.append("      function playIdx(i){if(i<0||i>=filt.length)return;curIdx=i;var r=filt[i];var tl=document.getElementById('tape-label-title'),fl=document.getElementById('tape-label-freq');if(tl)tl.innerText=r.timeDisplay+' - '+r.name;if(fl)fl.innerText=r.freq;duck(true);tapeAudio.src=buildUrl(r);tapeAudio.play().then(function(){setSpin(true);renderList();if(typeof showDisplayHud==='function')showDisplayHud('TAPE: '+r.name,1600);}).catch(function(){setSpin(false);duck(false);});}\n");
        js.append("      function loadRecs(cb){fetch('Recording Data.txt',{cache:'no-store'}).then(function(r){return r.text();}).then(function(t){var ls=t.trim().split(/\\r?\\n/), out=[];for(var i=0;i<ls.length&&out.length<100;i++){var ln=ls[i].trim();if(!ln||ln.indexOf('.mp3')===-1)continue;var p=ln.split(',');if(p.length>=14){var d=(p[5]||'').trim();if(d.indexOf('00:')===0)d=d.slice(3);var sr=(p[11]||p[8]||'').trim().split(/\\s+/);out.push({fileName:(p[0]||'').trim(),freq:(p[3]||'').trim(),dur:d,folderPath:(p[10]||'').trim(),timeDisplay:sr[sr.length-1],name:(p[13]||'Unknown').trim()});}}recs=out;renderList();if(cb)cb();}).catch(function(){});}\n");
        js.append("      document.getElementById('btn-tape-open').addEventListener('click',function(){var o=tp.hidden;tp.hidden=!o;if(o)loadRecs();});\n");
        js.append("      document.getElementById('btn-tape-close').addEventListener('click',function(){tp.hidden=true;});\n");
        js.append("      document.getElementById('tape-btn-play').addEventListener('click',function(){if(curIdx===-1&&filt.length)playIdx(0);else if(tapeAudio.paused&&tapeAudio.src){duck(true);tapeAudio.play();setSpin(true);}else{tapeAudio.pause();setSpin(false);duck(false);}});\n");
        js.append("      document.getElementById('tape-btn-stop').addEventListener('click',function(){tapeAudio.pause();tapeAudio.currentTime=0;setSpin(false);duck(false);renderList();});\n");
        js.append("      document.getElementById('tape-btn-prev').addEventListener('click',function(){if(curIdx>0)playIdx(curIdx-1);});\n");
        js.append("      document.getElementById('tape-btn-next').addEventListener('click',function(){if(curIdx+1<filt.length)playIdx(curIdx+1);});\n");
        js.append("      document.getElementById('tape-btn-auto').addEventListener('click',function(){autoNext=!autoNext;this.classList.toggle('active',autoNext);});\n");
        js.append("      document.getElementById('tape-btn-refresh').addEventListener('click',function(){loadRecs();});\n");
        js.append("      document.getElementById('tape-search-input').addEventListener('input',function(){renderList();});\n");
        js.append("      var tb=document.getElementById('mini-log-data');if(tb){tb.addEventListener('click',function(e){var tr=e.target.closest('tr');if(!tr||tr.children.length<5)return;var ct=(tr.children[0].innerText||'').trim(),cf=(tr.children[1].innerText||'').trim();loadRecs(function(){var m=filt.findIndex(function(r){return r.freq===cf&&r.timeDisplay===ct;});if(m===-1)m=filt.findIndex(function(r){return r.freq===cf;});if(m!==-1)playIdx(m);else if(typeof showDisplayHud==='function')showDisplayHud('NO TAPE FOR '+cf,1300);});});}\n");
        js.append("      var mBody=document.querySelector('#proscan-manual-panel .manual-body');\n");
        js.append("      if(mBody&&!document.getElementById('manual-tape-exit-sec')){\n");
        js.append("        var sec=document.createElement('div');sec.id='manual-tape-exit-sec';sec.className='manual-section';\n");
        js.append("        sec.innerHTML='<h3>7. Retro Tape Deck, VLC Stream &amp; Exit Controls</h3><ul class=\"manual-list\">' +\n");
        js.append("          '<li><span class=\"manual-key\">AUDIO</span> <strong>Short Tap:</strong> Toggles normal radio audio ON/OFF. <strong>Long Press (0.5s):</strong> Enables <strong>VLC / Screen-Off Mode</strong> (<span class=\"manual-key\">AUDIO &#9650;</span>) so audio keeps playing when your phone screen is locked.</li>' +\n");
        js.append("          '<li><span class=\"manual-key\">TAPE</span> Opens the <strong>Retro Cassette Tape Deck</strong> to browse, filter, and play recordings from <code>Recording Data.txt</code>. You can also <strong>tap any row in the Live Log</strong> to replay that transmission!</li>' +\n");
        js.append("          '<li><span class=\"manual-key\">EXIT</span> (Top-Right LED Bar): <strong>Single Tap</strong> shows Android status/nav bars; <strong>Double Tap</strong> minimizes the app; <strong>Long Press (0.5s)</strong> powers down the radio and closes the app.</li></ul>';\n");
        js.append("        mBody.appendChild(sec);\n");
        js.append("      }\n");
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

        builder.setNeutralButton("Exit App", (dialog, which) -> closeAndExitApp());

        if (!isFirstRun) {
            builder.setNegativeButton("Cancel", null);
        }
        builder.show();
    }

    private void connectSmart(String localUrl, String tailscaleUrl) {
        if (!localUrl.isEmpty() && tailscaleUrl.isEmpty()) {
            webView.loadUrl(localUrl);
            return;
        }
        if (localUrl.isEmpty() && !tailscaleUrl.isEmpty()) {
            webView.loadUrl(tailscaleUrl);
            return;
        }
        Toast.makeText(this, "Checking Local Wi-Fi...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            boolean localReachable = isUrlReachable(localUrl, 1500);
            mainHandler.post(() -> {
                if (localReachable) {
                    Toast.makeText(this, "Connected via Local Wi-Fi", Toast.LENGTH_SHORT).show();
                    webView.loadUrl(localUrl);
                } else {
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