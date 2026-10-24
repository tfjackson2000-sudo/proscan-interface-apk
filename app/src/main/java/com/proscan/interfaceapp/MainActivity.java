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

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private WebView webView;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean userWakeLockEnabled = false;
    private boolean bgAudioModeEnabled = false;

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
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectEnhancements();
            }
        });

        mainHandler.removeCallbacks(periodicInjector);
        mainHandler.postDelayed(periodicInjector, 1200);
    }

    private void injectEnhancements() {
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

        /* 1. Uniform Recessed Keypad Well + True 3D Contoured Neon Skins + Hide Inline Hit Badge for Full Name Width */
        js.append("    if (!document.getElementById('proscan-v3-upgrade-css')) {\n");
        js.append("      var oldSt = document.getElementById('proscan-v2-upgrade-css'); if (oldSt) oldSt.remove();\n");
        js.append("      var st = document.createElement('style');\n");
        js.append("      st.id = 'proscan-v3-upgrade-css';\n");
        js.append("      st.textContent = '' +\n");
        js.append("        '.buttons-box{border:1px solid rgba(0,0,0,0.48)!important;border-radius:12px!important;padding:7px!important;background:rgba(0,0,0,0.22)!important;box-shadow:inset 0 2px 6px rgba(0,0,0,0.75),0 1px 0 rgba(255,255,255,0.09)!important;}' +\n");
        js.append("        '.freq-hit-badge{display:none!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(1),#mini-log-table td:nth-child(1){width:20%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(2),#mini-log-table td:nth-child(2){width:21%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(3),#mini-log-table td:nth-child(3){width:10%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(4),#mini-log-table td:nth-child(4){width:12%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(5),#mini-log-table td:nth-child(5){width:37%!important;}' +\n");
        js.append("        '#mini-log-table tbody tr{cursor:pointer;}#mini-log-table tbody tr:active{background:rgba(255,255,255,0.15)!important;}' +\n");
        js.append("        'html[data-skin=\"whiteline\"]{--ui-bg:radial-gradient(circle at 50% 12%,#26292e 0%,#121417 65%,#070809 100%)!important;--ui-bevel:inset 0 2px 3px rgba(255,255,255,0.35),inset 0 -3px 6px rgba(0,0,0,0.9)!important;--btn-bg:linear-gradient(180deg,#2e3238 0%,#181a1e 55%,#0d0e11 100%)!important;--btn-border:1px solid rgba(255,255,255,0.75)!important;--btn-shadow:0 3px 0 #94a3b8,0 4px 6px rgba(0,0,0,0.85),inset 0 1px 1px rgba(255,255,255,0.45)!important;--btn-shadow-active:0 0 0 #94a3b8,inset 0 2px 5px rgba(0,0,0,0.95)!important;--btn-active-transform:translateY(3px)!important;}' +\n");
        js.append("        'html[data-skin=\"tron\"]{--ui-bg:radial-gradient(circle at 50% 12%,#0c2e36 0%,#06161a 65%,#02080a 100%)!important;--ui-bevel:inset 0 2px 3px rgba(0,255,255,0.4),inset 0 -3px 6px rgba(0,0,0,0.9)!important;--btn-bg:linear-gradient(180deg,#103c47 0%,#071f26 55%,#031014 100%)!important;--btn-border:1px solid #00ffff!important;--btn-shadow:0 3px 0 #008b8b,0 4px 7px rgba(0,0,0,0.85),inset 0 1px 1px rgba(180,255,255,0.45)!important;--btn-shadow-active:0 0 0 #008b8b,inset 0 2px 5px rgba(0,0,0,0.95)!important;--btn-active-transform:translateY(3px)!important;}' +\n");
        js.append("        'html[data-skin=\"neongreen\"]{--ui-bg:radial-gradient(circle at 50% 12%,#11330d 0%,#081906 65%,#030a02 100%)!important;--ui-bevel:inset 0 2px 3px rgba(57,255,20,0.4),inset 0 -3px 6px rgba(0,0,0,0.9)!important;--btn-bg:linear-gradient(180deg,#184212 0%,#0b2108 55%,#051104 100%)!important;--btn-border:1px solid #39ff14!important;--btn-shadow:0 3px 0 #1b8a08,0 4px 7px rgba(0,0,0,0.85),inset 0 1px 1px rgba(190,255,180,0.45)!important;--btn-shadow-active:0 0 0 #1b8a08,inset 0 2px 5px rgba(0,0,0,0.95)!important;--btn-active-transform:translateY(3px)!important;}' +\n");
        js.append("        'html[data-skin=\"amber\"]{--ui-bg:radial-gradient(circle at 50% 12%,#332406 0%,#1a1203 65%,#0a0701 100%)!important;--ui-bevel:inset 0 2px 3px rgba(255,176,0,0.4),inset 0 -3px 6px rgba(0,0,0,0.9)!important;--btn-bg:linear-gradient(180deg,#422f08 0%,#211704 55%,#120c02 100%)!important;--btn-border:1px solid #ffb000!important;--btn-shadow:0 3px 0 #996900,0 4px 7px rgba(0,0,0,0.85),inset 0 1px 1px rgba(255,230,170,0.45)!important;--btn-shadow-active:0 0 0 #996900,inset 0 2px 5px rgba(0,0,0,0.95)!important;--btn-active-transform:translateY(3px)!important;}' +\n");
        js.append("        '#btn-hardware-exit{display:inline-flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;background:transparent;border:none;padding:0;margin:0 0 0 2px;cursor:pointer;touch-action:manipulation;}#btn-hardware-exit .exit-cap{width:24px;height:11px;border-radius:4px;background:linear-gradient(180deg,#b91c1c,#7f1d1d);border:1px solid #450a0a;box-shadow:0 1px 2px rgba(0,0,0,0.75),inset 0 1px 0 rgba(255,255,255,0.28);display:flex;align-items:center;justify-content:center;}#btn-hardware-exit .exit-cap::after{content:\"\";width:8px;height:2px;border-radius:1px;background:rgba(255,220,220,0.85);}#btn-hardware-exit:active .exit-cap{transform:translateY(1px);filter:brightness(1.2);}';\n");
        js.append("      document.head.appendChild(st);\n");
        js.append("    }\n");

        /* 2. Tap Any Log Row to Flash Full Channel Name + Hit Count on the Top-Left HUD! */
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
        js.append("        var cleanName = rawTitle.replace(/^\\([^)]+\\)\\s*/, '').trim();\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(15);\n");
        js.append("        if (typeof showDisplayHud === 'function') {\n");
        js.append("          showDisplayHud(cleanName + ' • HITS: ' + hitsText, 2200);\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("    }\n");

        /* 3. Single AUDIO button (Short Tap = Normal Audio / Off, Long Press = VLC Screen-Off Mode) */
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

        /* 4. Hardware EXIT button: 1 Tap = Show System Bars, 2 Quick Taps = Minimize, Long Press = Power Off & Exit */
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

        /* 5. Update GUIDE with the new Audio, Log-Tap Hit Counter & Exit Controls */
        js.append("    var mBody = document.querySelector('#proscan-manual-panel .manual-body');\n");
        js.append("    if (mBody && !document.getElementById('manual-v3-sec')) {\n");
        js.append("      var oldSec = document.getElementById('manual-v2-sec'); if (oldSec) oldSec.remove();\n");
        js.append("      var sec = document.createElement('div');\n");
        js.append("      sec.id = 'manual-v3-sec';\n");
        js.append("      sec.className = 'manual-section';\n");
        js.append("      sec.innerHTML = '<h3>7. Screen-Off Audio, Hit Counter &amp; Exit Button</h3><ul class=\"manual-list\">' +\n");
        js.append("        '<li><span class=\"manual-key\">AUDIO</span> <strong>Short Tap:</strong> Turns normal radio audio ON or OFF. <strong>Long Press (0.5s):</strong> Switches to <strong>VLC / Screen-Off Mode</strong> (<span class=\"manual-key\">AUDIO &#9650;</span>) so audio keeps playing when your phone screen is locked. Long-press again to return to normal mode.</li>' +\n");
        js.append("        '<li><strong>Log Row Hit Counter:</strong> Tap any row in the Live History Log to display the full Channel Name and its total Hit Count in the top-left screen HUD without cluttering the log column.</li>' +\n");
        js.append("        '<li><span class=\"manual-key\">EXIT</span> (Next to RX/LOG LEDs): <strong>Single Tap</strong> reveals Android top/bottom system bars; <strong>Double Tap</strong> minimizes the app to the background; <strong>Long Press (0.5s)</strong> powers down the radio and closes the app.</li></ul>';\n");
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