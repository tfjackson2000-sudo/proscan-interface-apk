package com.proscan.interfaceapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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
    private String activeBaseUrl = "";

    private MediaPlayer bgPlayer = null;
    private boolean isBgStreamPlaying = false;
    private PowerManager.WakeLock cpuWakeLock = null;
    private WifiManager.WifiLock wifiLock = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();

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
                /* Keep WebView timers alive if background stream is active */
                if (isBgStreamPlaying) {
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

    private String getActiveStreamUrl() {
        String base = activeBaseUrl;
        if (base == null || base.isEmpty()) {
            base = webView.getUrl();
        }
        if (base == null || base.isEmpty()) return "";
        base = base.replaceAll("/+$", "");
        return base + "/stream";
    }

    private void acquireBackgroundLocks() {
        try {
            if (cpuWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProScan:BgAudioLock");
                    cpuWakeLock.setReferenceCounted(false);
                }
            }
            if (cpuWakeLock != null && !cpuWakeLock.isHeld()) {
                cpuWakeLock.acquire();
            }
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ProScan:BgWifiLock");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseBackgroundLocks() {
        try {
            if (cpuWakeLock != null && cpuWakeLock.isHeld()) cpuWakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {}
    }

    private void startNativeBackgroundStream() {
        stopNativeBackgroundStream();
        String streamUrl = getActiveStreamUrl();
        if (streamUrl.isEmpty()) {
            Toast.makeText(this, "Stream URL not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            acquireBackgroundLocks();
            bgPlayer = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                bgPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            }
            bgPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            bgPlayer.setDataSource(streamUrl);
            bgPlayer.setOnPreparedListener(mp -> {
                mp.start();
                isBgStreamPlaying = true;
                updateVlcBtnState(true);
                Toast.makeText(MainActivity.this, "Background Stream ON (Plays with screen off)", Toast.LENGTH_SHORT).show();
            });
            bgPlayer.setOnErrorListener((mp, what, extra) -> {
                stopNativeBackgroundStream();
                Toast.makeText(MainActivity.this, "Stream error — Hold VLC button to open VLC app", Toast.LENGTH_LONG).show();
                return true;
            });
            bgPlayer.prepareAsync();
            updateVlcBtnState(true);
        } catch (Exception e) {
            stopNativeBackgroundStream();
            launchExternalVlcApp();
        }
    }

    private void stopNativeBackgroundStream() {
        isBgStreamPlaying = false;
        if (bgPlayer != null) {
            try {
                if (bgPlayer.isPlaying()) bgPlayer.stop();
            } catch (Exception ignored) {}
            try {
                bgPlayer.reset();
                bgPlayer.release();
            } catch (Exception ignored) {}
            bgPlayer = null;
        }
        releaseBackgroundLocks();
        updateVlcBtnState(false);
    }

    private void updateVlcBtnState(final boolean active) {
        mainHandler.post(() -> {
            if (webView != null) {
                String js = "var vb=document.getElementById('btn-vlc-toggle');if(vb){vb.classList.toggle('active'," + active + ");}";
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private void launchExternalVlcApp() {
        stopNativeBackgroundStream();
        String streamUrl = getActiveStreamUrl();
        if (streamUrl.isEmpty()) return;
        try {
            Intent vlcIntent = new Intent(Intent.ACTION_VIEW);
            vlcIntent.setPackage("org.videolan.vlc");
            vlcIntent.setDataAndTypeAndNormalize(Uri.parse(streamUrl), "audio/*");
            vlcIntent.putExtra("title", "ProScan Radio Stream");
            startActivity(vlcIntent);
            Toast.makeText(this, "Opening in VLC: " + streamUrl, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(streamUrl));
                startActivity(fallback);
            } catch (Exception ex) {
                Toast.makeText(this, "VLC App not found", Toast.LENGTH_SHORT).show();
            }
        }
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
        public void toggleVlcStream() {
            mainHandler.post(() -> {
                if (isBgStreamPlaying || bgPlayer != null) {
                    stopNativeBackgroundStream();
                    Toast.makeText(MainActivity.this, "Background Stream OFF", Toast.LENGTH_SHORT).show();
                } else {
                    startNativeBackgroundStream();
                }
            });
        }

        @JavascriptInterface
        public void openExternalVlc() {
            mainHandler.post(() -> launchExternalVlcApp());
        }

        @JavascriptInterface
        public void exitApp() {
            mainHandler.post(() -> closeAndExitApp());
        }
    }

    private void closeAndExitApp() {
        stopNativeBackgroundStream();
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

        /* 1. Split the AUDIO button on Android into [AUDIO | VLC] */
        js.append("    var audioBtn = document.getElementById('btn-audio-toggle');\n");
        js.append("    if (audioBtn && !document.getElementById('btn-vlc-toggle')) {\n");
        js.append("      var splitWrap = document.createElement('div');\n");
        js.append("      splitWrap.className = 'split-btn-group';\n");
        js.append("      audioBtn.parentNode.insertBefore(splitWrap, audioBtn);\n");
        js.append("      splitWrap.appendChild(audioBtn);\n");
        js.append("      var vlcBtn = document.createElement('button');\n");
        js.append("      vlcBtn.id = 'btn-vlc-toggle';\n");
        js.append("      vlcBtn.type = 'button';\n");
        js.append("      vlcBtn.className = 'btn state-btn';\n");
        js.append("      vlcBtn.innerHTML = '<span class=\"inline-led\" id=\"vlc-inline-led\"></span> VLC';\n");
        js.append("      splitWrap.appendChild(vlcBtn);\n");
        js.append("      var vTimer = null, vLong = false;\n");
        js.append("      vlcBtn.addEventListener('pointerdown', function() {\n");
        js.append("        clearTimeout(vTimer); vLong = false;\n");
        js.append("        vTimer = setTimeout(function() {\n");
        js.append("          vTimer = null; vLong = true;\n");
        js.append("          if (navigator.vibrate) navigator.vibrate(40);\n");
        js.append("          if (window.AndroidApp) window.AndroidApp.openExternalVlc();\n");
        js.append("        }, 500);\n");
        js.append("      });\n");
        js.append("      vlcBtn.addEventListener('pointerup', function() {\n");
        js.append("        var wasActive = vTimer !== null; clearTimeout(vTimer); vTimer = null;\n");
        js.append("        if (wasActive && !vLong && window.AndroidApp) window.AndroidApp.toggleVlcStream();\n");
        js.append("      });\n");
        js.append("      vlcBtn.addEventListener('pointerleave', function() { clearTimeout(vTimer); vTimer = null; });\n");
        js.append("      vlcBtn.addEventListener('pointercancel', function() { clearTimeout(vTimer); vTimer = null; });\n");
        js.append("    }\n");

        /* 2. Inject the dedicated Hardware EXIT button to the right of the RX & LOG LEDs */
        js.append("    var ledsRight = document.querySelector('.top-leds-right');\n");
        js.append("    if (ledsRight && !document.getElementById('btn-hardware-exit')) {\n");
        js.append("      var st = document.createElement('style');\n");
        js.append("      st.textContent = '#btn-hardware-exit{display:inline-flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;background:transparent;border:none;padding:0;margin:0 0 0 2px;cursor:pointer;touch-action:manipulation;}#btn-hardware-exit .exit-cap{width:24px;height:11px;border-radius:4px;background:linear-gradient(180deg,#b91c1c,#7f1d1d);border:1px solid #450a0a;box-shadow:0 1px 2px rgba(0,0,0,0.75),inset 0 1px 0 rgba(255,255,255,0.28);display:flex;align-items:center;justify-content:center;}#btn-hardware-exit .exit-cap::after{content:\"\";width:8px;height:2px;border-radius:1px;background:rgba(255,220,220,0.85);}#btn-hardware-exit:active .exit-cap{transform:translateY(1px);filter:brightness(1.2);}';\n");
        js.append("      document.head.appendChild(st);\n");
        js.append("      var exitBtn = document.createElement('button');\n");
        js.append("      exitBtn.id = 'btn-hardware-exit';\n");
        js.append("      exitBtn.type = 'button';\n");
        js.append("      exitBtn.innerHTML = '<div class=\"exit-cap\"></div><span class=\"rx-label\">EXIT</span>';\n");
        js.append("      ledsRight.appendChild(exitBtn);\n");
        js.append("      var exTimer = null, exLong = false;\n");
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
        js.append("        }, 500);\n");
        js.append("      });\n");
        js.append("      exitBtn.addEventListener('pointerup', function() {\n");
        js.append("        var wasActive = exTimer !== null; clearTimeout(exTimer); exTimer = null;\n");
        js.append("        if (wasActive && !exLong && typeof showDisplayHud === 'function') showDisplayHud('HOLD EXIT TO QUIT', 1200);\n");
        js.append("      });\n");
        js.append("      exitBtn.addEventListener('pointerleave', function() { clearTimeout(exTimer); exTimer = null; });\n");
        js.append("      exitBtn.addEventListener('pointercancel', function() { clearTimeout(exTimer); exTimer = null; });\n");
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
            activeBaseUrl = localUrl;
            webView.loadUrl(localUrl);
            return;
        }
        if (localUrl.isEmpty() && !tailscaleUrl.isEmpty()) {
            activeBaseUrl = tailscaleUrl;
            webView.loadUrl(tailscaleUrl);
            return;
        }
        Toast.makeText(this, "Checking Local Wi-Fi...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            boolean localReachable = isUrlReachable(localUrl, 1500);
            mainHandler.post(() -> {
                if (localReachable) {
                    activeBaseUrl = localUrl;
                    Toast.makeText(this, "Connected via Local Wi-Fi", Toast.LENGTH_SHORT).show();
                    webView.loadUrl(localUrl);
                } else {
                    activeBaseUrl = tailscaleUrl;
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