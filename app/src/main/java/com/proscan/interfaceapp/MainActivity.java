package com.proscan.interfaceapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {

    private WebView webView;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private String embeddedUiBase64 = "";

    private final String localUrl = "http://192.168.1.31:5000";
    private final String tailscaleUrl = "http://100.85.223.120:5000";
    private boolean triedTailscale = false;
    private boolean pageLoaded = false;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());

    @SuppressLint({"SetJavaScriptEnabled", "WakelockTimeout"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );
        hideSystemUI();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProScan::AudioWakeLock");
            wakeLock.setReferenceCounted(false);
        }

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ProScan::AudioWifiLock");
            wifiLock.setReferenceCounted(false);
        }

        loadEmbeddedUiAsset();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#08090c"));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageLoaded = true;
                timeoutHandler.removeCallbacksAndMessages(null);
                injectCustomUiAndButtons();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && !triedTailscale) {
                    switchToTailscale();
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (!triedTailscale) {
                    switchToTailscale();
                }
            }
        });

        timeoutHandler.postDelayed(() -> {
            if (!pageLoaded && !triedTailscale) {
                switchToTailscale();
            }
        }, 4500);

        webView.loadUrl(localUrl);
    }

    private void switchToTailscale() {
        triedTailscale = true;
        timeoutHandler.removeCallbacksAndMessages(null);
        runOnUiThread(() -> {
            if (webView != null) {
                webView.stopLoading();
                webView.loadUrl(tailscaleUrl);
            }
        });
    }

    private void loadEmbeddedUiAsset() {
        try {
            InputStream is = getAssets().open("custom_ui.html");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] data = new byte[4096];
            int nRead;
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            is.close();
            embeddedUiBase64 = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) {
        }
    }

    private void acquireLocks() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
            Intent serviceIntent = new Intent(this, AudioKeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            stopService(new Intent(this, AudioKeepAliveService.class));
        } catch (Exception ignored) {
        }
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void setAudioLock(boolean active) {
            if (active) {
                acquireLocks();
            } else {
                releaseLocks();
            }
        }

        @JavascriptInterface
        public void setVlcMode(boolean active) {
            if (active) {
                acquireLocks();
                runOnUiThread(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            } else {
                runOnUiThread(() -> getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            }
        }

        @JavascriptInterface
        public void closeApp() {
            releaseLocks();
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.loadUrl("about:blank");
                    webView.destroy();
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAndRemoveTask();
                } else {
                    finish();
                }
                System.exit(0);
            });
        }
    }

    private void injectCustomUiAndButtons() {
        StringBuilder js = new StringBuilder();
        js.append("(function() {\n");
        js.append("try {\n");

        if (embeddedUiBase64 != null && !embeddedUiBase64.isEmpty()) {
            js.append("  if (!document.getElementById('custom-scanner-ui-wrapper')) {\n");
            js.append("    var rawHtml = decodeURIComponent(escape(window.atob('").append(embeddedUiBase64).append("')));\n");
            js.append("    var parser = new DOMParser();\n");
            js.append("    var doc = parser.parseFromString(rawHtml, 'text/html');\n");
            js.append("    var styles = doc.querySelectorAll('style, link[rel=\"stylesheet\"]');\n");
            js.append("    for (var i = 0; i < styles.length; i++) {\n");
            js.append("      document.head.appendChild(styles[i].cloneNode(true));\n");
            js.append("    }\n");
            js.append("    var wrapper = doc.getElementById('custom-scanner-ui-wrapper');\n");
            js.append("    if (wrapper) {\n");
            js.append("      document.body.appendChild(wrapper.cloneNode(true));\n");
            js.append("    }\n");
            js.append("    var scripts = doc.body.querySelectorAll('script');\n");
            js.append("    for (var j = 0; j < scripts.length; j++) {\n");
            js.append("      var s = document.createElement('script');\n");
            js.append("      s.text = scripts[j].text || scripts[j].textContent || scripts[j].innerHTML;\n");
            js.append("      document.body.appendChild(s);\n");
            js.append("    }\n");
            js.append("  }\n");
        }

        appendAudioAndExitButtonsJs(js);
        appendTapeDeckJs(js);

        js.append("} catch(e) {}\n");
        js.append("})();");

        webView.evaluateJavascript(js.toString(), null);
    }

    private void appendAudioAndExitButtonsJs(StringBuilder js) {
        js.append("  var audioBtn = document.getElementById('custom-audio-btn');\n");
        js.append("  if (audioBtn && !audioBtn.dataset.vlcBound) {\n");
        js.append("    audioBtn.dataset.vlcBound = '1';\n");
        js.append("    var pressTimer = null;\n");
        js.append("    var longTriggered = false;\n");
        js.append("    audioBtn.addEventListener('touchstart', function() {\n");
        js.append("      longTriggered = false;\n");
        js.append("      pressTimer = setTimeout(function() {\n");
        js.append("        longTriggered = true;\n");
        js.append("        var isVlc = audioBtn.dataset.vlcMode === '1';\n");
        js.append("        if (!isVlc) {\n");
        js.append("          audioBtn.dataset.vlcMode = '1';\n");
        js.append("          if (!audioBtn.classList.contains('active')) audioBtn.click();\n");
        js.append("          var txt = audioBtn.querySelector('.led-text');\n");
        js.append("          if (txt) txt.innerHTML = '&#x1F6A7; VLC';\n");
        js.append("          if (window.AndroidBridge) window.AndroidBridge.setVlcMode(true);\n");
        js.append("        } else {\n");
        js.append("          audioBtn.dataset.vlcMode = '0';\n");
        js.append("          var txt2 = audioBtn.querySelector('.led-text');\n");
        js.append("          if (txt2) txt2.textContent = 'AUDIO';\n");
        js.append("          if (window.AndroidBridge) window.AndroidBridge.setVlcMode(false);\n");
        js.append("        }\n");
        js.append("      }, 600);\n");
        js.append("    }, {passive: true});\n");
        js.append("    audioBtn.addEventListener('touchend', function(e) {\n");
        js.append("      if (pressTimer) clearTimeout(pressTimer);\n");
        js.append("      if (longTriggered) { e.preventDefault(); e.stopPropagation(); }\n");
        js.append("    });\n");
        js.append("    audioBtn.addEventListener('click', function() {\n");
        js.append("      setTimeout(function() {\n");
        js.append("        var active = audioBtn.classList.contains('active');\n");
        js.append("        if (window.AndroidBridge) window.AndroidBridge.setAudioLock(active);\n");
        js.append("        if (!active) {\n");
        js.append("          audioBtn.dataset.vlcMode = '0';\n");
        js.append("          var txt = audioBtn.querySelector('.led-text');\n");
        js.append("          if (txt) txt.textContent = 'AUDIO';\n");
        js.append("          if (window.AndroidBridge) window.AndroidBridge.setVlcMode(false);\n");
        js.append("        }\n");
        js.append("      }, 150);\n");
        js.append("    });\n");
        js.append("  }\n");

        js.append("  var topControls = document.querySelector('.top-controls');\n");
        js.append("  if (topControls && !document.getElementById('custom-exit-btn')) {\n");
        js.append("    var exitBtn = document.createElement('button');\n");
        js.append("    exitBtn.id = 'custom-exit-btn';\n");
        js.append("    exitBtn.className = 'led-btn';\n");
        js.append("    exitBtn.style.marginLeft = '4px';\n");
        js.append("    exitBtn.style.borderColor = '#662222';\n");
        js.append("    exitBtn.innerHTML = '<div class=\"led-indicator\" style=\"background:#ff3333;box-shadow:0 0 6px #ff3333;\"></div><span class=\"led-text\" style=\"color:#ff7777;\">EXIT</span>';\n");
        js.append("    exitBtn.addEventListener('click', function() {\n");
        js.append("      if (window.AndroidBridge && window.AndroidBridge.closeApp) {\n");
        js.append("        window.AndroidBridge.closeApp();\n");
        js.append("      }\n");
        js.append("    });\n");
        js.append("    topControls.appendChild(exitBtn);\n");
        js.append("  }\n");
    }

    private void appendTapeDeckJs(StringBuilder js) {
        js.append("  var topControls = document.querySelector('.top-controls');\n");
        js.append("  if (topControls && !document.getElementById('custom-tape-btn')) {\n");
        js.append("    var FOLDER_ID = '1Per729uk121192-G7N82X_y99X19t0mG';\n");
        js.append("    var apiKey = localStorage.getItem('gdrive_api_key') || '';\n");
        js.append("    var tracks = [];\n");
        js.append("    var currentIdx = -1;\n");
        js.append("    var player = new Audio();\n");
        js.append("    if (!document.getElementById('tape-deck-styles')) {\n");
        js.append("      var st = document.createElement('style');\n");
        js.append("      st.id = 'tape-deck-styles';\n");
        js.append("      st.textContent = [\n");
        js.append("        '#custom-tape-btn.active .led-indicator { background: #00ff66 !important; box-shadow: 0 0 8px #00ff66 !important; }',\n");
        js.append("        '#custom-tape-btn.active .led-text { color: #00ff66 !important; }',\n");
        js.append("        '#tape-deck-modal { display: none; position: absolute; top: 0; left: 0; right: 0; bottom: 0; background: rgba(8, 12, 10, 0.96); z-index: 9999; flex-direction: column; padding: 10px; box-sizing: border-box; font-family: monospace; color: #00ff66; border: 2px solid #00ff66; }',\n");
        js.append("        '#tape-deck-modal.open { display: flex; }',\n");
        js.append("        '.td-header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #00ff66; padding-bottom: 6px; margin-bottom: 6px; font-size: 15px; font-weight: bold; }',\n");
        js.append("        '.td-mini-btn { background: #112211; color: #00ff66; border: 1px solid #00ff66; padding: 3px 8px; font-family: monospace; font-size: 13px; cursor: pointer; margin-left: 4px; }',\n");
        js.append("        '.td-status-bar { background: #051005; border: 1px solid #005522; padding: 6px; margin-bottom: 6px; display: flex; justify-content: space-between; align-items: center; font-size: 13px; }',\n");
        js.append("        '.td-track-title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 72%; }',\n");
        js.append("        '.td-controls { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; margin-bottom: 8px; }',\n");
        js.append("        '.td-ctrl-btn { background: #112211; color: #00ff66; border: 1px solid #00aa44; padding: 8px 4px; font-family: monospace; font-size: 14px; font-weight: bold; cursor: pointer; text-align: center; }',\n");
        js.append("        '.td-ctrl-btn.playing { background: #00ff66; color: #000; }',\n");
        js.append("        '.td-list { flex: 1; overflow-y: auto; border: 1px solid #004411; background: #030803; padding: 4px; }',\n");
        js.append("        '.td-item { padding: 7px 6px; border-bottom: 1px solid #0a220a; cursor: pointer; display: flex; justify-content: space-between; font-size: 13px; color: #88cc88; }',\n");
        js.append("        '.td-item.selected { background: #003311; color: #ffffff; font-weight: bold; border-left: 3px solid #00ff66; }'\n");
        js.append("      ].join(' ');\n");
        js.append("      document.head.appendChild(st);\n");
        js.append("    }\n");
        js.append("    var tapeBtn = document.createElement('button');\n");
        js.append("    tapeBtn.id = 'custom-tape-btn';\n");
        js.append("    tapeBtn.className = 'led-btn';\n");
        js.append("    tapeBtn.innerHTML = '<div class=\"led-indicator\"></div><span class=\"led-text\">TAPE</span>';\n");
        js.append("    topControls.insertBefore(tapeBtn, topControls.firstChild);\n");
        js.append("    var modal = document.createElement('div');\n");
        js.append("    modal.id = 'tape-deck-modal';\n");
        js.append("    modal.innerHTML = [\n");
        js.append("      '<div class=\"td-header\">',\n");
        js.append("      '  <span>ARCHIVE TAPE DECK</span>',\n");
        js.append("      '  <div>',\n");
        js.append("      '    <button type=\"button\" class=\"td-mini-btn\" id=\"td-key-btn\">API KEY</button>',\n");
        js.append("      '    <button type=\"button\" class=\"td-mini-btn\" id=\"td-refresh-btn\">SYNC</button>',\n");
        js.append("      '    <button type=\"button\" class=\"td-mini-btn\" id=\"td-close-btn\">X</button>',\n");
        js.append("      '  </div>',\n");
        js.append("      '</div>',\n");
        js.append("      '<div class=\"td-status-bar\">',\n");
        js.append("      '  <div class=\"td-track-title\" id=\"td-now-playing\">READY</div>',\n");
        js.append("      '  <div id=\"td-timer\">00:00 / 00:00</div>',\n");
        js.append("      '</div>',\n");
        js.append("      '<div class=\"td-controls\">',\n");
        js.append("      '  <button type=\"button\" class=\"td-ctrl-btn\" id=\"td-prev-btn\">|&lt;&lt;</button>',\n");
        js.append("      '  <button type=\"button\" class=\"td-ctrl-btn\" id=\"td-play-btn\">PLAY</button>',\n");
        js.append("      '  <button type=\"button\" class=\"td-ctrl-btn\" id=\"td-stop-btn\">STOP</button>',\n");
        js.append("      '  <button type=\"button\" class=\"td-ctrl-btn\" id=\"td-next-btn\">&gt;&gt;|</button>',\n");
        js.append("      '</div>',\n");
        js.append("      '<div class=\"td-list\" id=\"td-file-list\">Tap API KEY to configure Google Drive access.</div>'\n");
        js.append("    ].join('');\n");
        js.append("    var screenHost = document.querySelector('.bezel') || document.getElementById('lcd-screen') || document.querySelector('.crt-frame') || document.querySelector('.chassis') || document.body;\n");
        js.append("    if (getComputedStyle(screenHost).position === 'static') { screenHost.style.position = 'relative'; }\n");
        js.append("    screenHost.appendChild(modal);\n");
        js.append("    var nowPlayingEl = modal.querySelector('#td-now-playing');\n");
        js.append("    var timerEl = modal.querySelector('#td-timer');\n");
        js.append("    var listEl = modal.querySelector('#td-file-list');\n");
        js.append("    var playBtn = modal.querySelector('#td-play-btn');\n");
        js.append("    function fmtTime(sec) {\n");
        js.append("      if (!sec || isNaN(sec)) return '00:00';\n");
        js.append("      var m = Math.floor(sec / 60);\n");
        js.append("      var s = Math.floor(sec % 60);\n");
        js.append("      return (m < 10 ? '0' + m : m) + ':' + (s < 10 ? '0' + s : s);\n");
        js.append("    }\n");
        js.append("    function cleanTitle(name) {\n");
        js.append("      return (name || '').replace('.mp3', '').replace('.MP3', '').replace('.wav', '').replace('.WAV', '');\n");
        js.append("    }\n");
        js.append("    function renderTracks() {\n");
        js.append("      listEl.innerHTML = '';\n");
        js.append("      if (!tracks.length) { listEl.textContent = 'No audio recordings found in Drive folder.'; return; }\n");
        js.append("      tracks.forEach(function(item, idx) {\n");
        js.append("        var row = document.createElement('div');\n");
        js.append("        row.className = 'td-item' + (idx === currentIdx ? ' selected' : '');\n");
        js.append("        var dateStr = item.createdTime ? new Date(item.createdTime).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'}) : '';\n");
        js.append("        row.innerHTML = '<span>' + cleanTitle(item.name) + '</span><span>' + dateStr + '</span>';\n");
        js.append("        row.addEventListener('click', function() { startTrack(idx); });\n");
        js.append("        listEl.appendChild(row);\n");
        js.append("      });\n");
        js.append("    }\n");
        js.append("    function fetchRecordings() {\n");
        js.append("      if (!apiKey) { listEl.textContent = 'Missing Google Drive API Key. Tap [API KEY] above.'; return; }\n");
        js.append("      listEl.textContent = 'Loading recordings from Google Drive...';\n");
        js.append("      var q = encodeURIComponent(\"'\" + FOLDER_ID + \"' in parents and trashed = false\");\n");
        js.append("      var url = 'https://www.googleapis.com/drive/v3/files?q=' + q + '&fields=files(id,name,createdTime,mimeType)&orderBy=createdTime+desc&pageSize=100&key=' + encodeURIComponent(apiKey);\n");
        js.append("      fetch(url).then(function(r) { return r.json(); }).then(function(data) {\n");
        js.append("        if (data.error) { listEl.textContent = 'Drive Error: ' + (data.error.message || 'Check API Key'); return; }\n");
        js.append("        tracks = (data.files || []).filter(function(f) {\n");
        js.append("          var n = (f.name || '').toLowerCase();\n");
        js.append("          return n.endsWith('.mp3') || n.endsWith('.wav') || n.endsWith('.m4a') || n.endsWith('.ogg') || (f.mimeType && f.mimeType.indexOf('audio/') === 0);\n");
        js.append("        });\n");
        js.append("        renderTracks();\n");
        js.append("      }).catch(function() { listEl.textContent = 'Network error connecting to Google Drive.'; });\n");
        js.append("    }\n");
        js.append("    function startTrack(idx) {\n");
        js.append("      if (idx < 0 || idx >= tracks.length) return;\n");
        js.append("      currentIdx = idx;\n");
        js.append("      var t = tracks[idx];\n");
        js.append("      nowPlayingEl.textContent = cleanTitle(t.name);\n");
        js.append("      player.src = 'https://www.googleapis.com/drive/v3/files/' + t.id + '?alt=media&key=' + encodeURIComponent(apiKey);\n");
        js.append("      player.play().then(function() {\n");
        js.append("        playBtn.textContent = 'PAUSE';\n");
        js.append("        playBtn.classList.add('playing');\n");
        js.append("      }).catch(function() { nowPlayingEl.textContent = 'ERR: CANNOT STREAM FILE'; });\n");
        js.append("      renderTracks();\n");
        js.append("    }\n");
        js.append("    tapeBtn.addEventListener('click', function() {\n");
        js.append("      var isOpen = modal.classList.toggle('open');\n");
        js.append("      tapeBtn.classList.toggle('active', isOpen);\n");
        js.append("      if (isOpen && tracks.length === 0 && apiKey) { fetchRecordings(); }\n");
        js.append("    });\n");
        js.append("    modal.querySelector('#td-close-btn').addEventListener('click', function() {\n");
        js.append("      modal.classList.remove('open');\n");
        js.append("      tapeBtn.classList.remove('active');\n");
        js.append("    });\n");
        js.append("    modal.querySelector('#td-key-btn').addEventListener('click', function() {\n");
        js.append("      var entered = prompt('Enter Google Drive API Key:', apiKey);\n");
        js.append("      if (entered !== null) {\n");
        js.append("        apiKey = entered.trim();\n");
        js.append("        localStorage.setItem('gdrive_api_key', apiKey);\n");
        js.append("        fetchRecordings();\n");
        js.append("      }\n");
        js.append("    });\n");
        js.append("    modal.querySelector('#td-refresh-btn').addEventListener('click', fetchRecordings);\n");
        js.append("    playBtn.addEventListener('click', function() {\n");
        js.append("      if (!player.src && tracks.length > 0) { startTrack(0); }\n");
        js.append("      else if (player.paused) { player.play(); playBtn.textContent = 'PAUSE'; playBtn.classList.add('playing'); }\n");
        js.append("      else { player.pause(); playBtn.textContent = 'PLAY'; playBtn.classList.remove('playing'); }\n");
        js.append("    });\n");
        js.append("    modal.querySelector('#td-stop-btn').addEventListener('click', function() {\n");
        js.append("      player.pause(); player.currentTime = 0; playBtn.textContent = 'PLAY'; playBtn.classList.remove('playing');\n");
        js.append("    });\n");
        js.append("    modal.querySelector('#td-prev-btn').addEventListener('click', function() { if (currentIdx > 0) startTrack(currentIdx - 1); });\n");
        js.append("    modal.querySelector('#td-next-btn').addEventListener('click', function() { if (currentIdx + 1 < tracks.length) startTrack(currentIdx + 1); });\n");
        js.append("    player.addEventListener('timeupdate', function() { timerEl.textContent = fmtTime(player.currentTime) + ' / ' + fmtTime(player.duration); });\n");
        js.append("    player.addEventListener('ended', function() {\n");
        js.append("      if (currentIdx + 1 < tracks.length) { startTrack(currentIdx + 1); }\n");
        js.append("      else { playBtn.textContent = 'PLAY'; playBtn.classList.remove('playing'); }\n");
        js.append("    });\n");
        js.append("  }\n");
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseLocks();
    }
}