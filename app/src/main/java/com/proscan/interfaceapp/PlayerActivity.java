package com.proscan.interfaceapp;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerActivity extends Activity {

    private static final String GDRIVE_ROOT_FOLDER_ID = "1gToQyY6YPOk_3muiQSY5WLy7jTSvXbTv";
    private WebView playerWeb;
    private String serverBaseUrl = "";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService audioFetchExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextToSpeech tts = null;
    private boolean ttsReady = false;
    private MediaPlayer nativePlayer = null;
    private boolean isFetchingDrive = false;
    private float[] currentEnvelope = new float[0];

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            try {
                if (nativePlayer != null && nativePlayer.isPlaying() && playerWeb != null) {
                    int posMs = nativePlayer.getCurrentPosition();
                    int durMs = Math.max(1, nativePlayer.getDuration());
                    int sec = posMs / 1000;
                    int tot = Math.max(1, durMs / 1000);
                    float prog = Math.min(1f, Math.max(0f, (float) posMs / (float) durMs));

                    float level = 0.25f;
                    if (currentEnvelope.length > 0) {
                        int envIdx = Math.min(currentEnvelope.length - 1, (int) (prog * currentEnvelope.length));
                        level = currentEnvelope[envIdx];
                    }

                    playerWeb.evaluateJavascript(
                            "if(window.onNativeTelemetry)window.onNativeTelemetry(" + sec + "," + tot + "," + prog + "," + level + ");",
                            null
                    );
                    mainHandler.postDelayed(this, 60);
                }
            } catch (Exception ignored) {}
        }
    };

    private final Runnable autoSyncRunnable = new Runnable() {
        @Override
        public void run() {
            loadLiveGoogleDriveRecordings();
            mainHandler.postDelayed(this, 12000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        hideSystemUI();

        serverBaseUrl = getIntent().getStringExtra("server_url");
        if (serverBaseUrl == null) serverBaseUrl = "";
        serverBaseUrl = serverBaseUrl.replaceAll("/+$", "");

        try {
            tts = new TextToSpeech(getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(Locale.UK);
                    tts.setSpeechRate(1.05f);
                    ttsReady = true;
                }
            });
        } catch (Exception ignored) {}

        playerWeb = new WebView(this);
        playerWeb.setBackgroundColor(Color.BLACK);

        WebSettings s = playerWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(playerWeb, true);

        playerWeb.addJavascriptInterface(new PlayerBridge(), "PlayerBridge");
        playerWeb.setWebChromeClient(new WebChromeClient());
        playerWeb.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectReactiveDecksAndTheme();
                loadLiveGoogleDriveRecordings();
                mainHandler.removeCallbacks(autoSyncRunnable);
                mainHandler.postDelayed(autoSyncRunnable, 12000);
            }
        });

        setContentView(playerWeb);
        playerWeb.loadUrl("file:///android_asset/player_ui.html");
    }

    private void injectReactiveDecksAndTheme() {
        SharedPreferences prefs = getSharedPreferences("proscan_prefs", MODE_PRIVATE);
        String backupJson = prefs.getString("last_backup_json", "");
        String safeBackup = JSONObject.quote(backupJson);

        StringBuilder js = new StringBuilder();
        js.append("(function(){\n");
        js.append("  try {\n");
        js.append("    var raw = ").append(safeBackup).append(";\n");
        js.append("    if (raw) {\n");
        js.append("      var b = JSON.parse(raw);\n");
        js.append("      if (b.web_storage) {\n");
        js.append("        Object.keys(b.web_storage).forEach(function(k){ localStorage.setItem(k, b.web_storage[k]); });\n");
        js.append("        var sIdx = parseInt(localStorage.getItem('proscan_skin_index')||'0',10)||0;\n");
        js.append("        var dIdx = parseInt(localStorage.getItem('proscan_display_index')||'0',10)||0;\n");
        js.append("        var bIdx = parseInt(localStorage.getItem('proscan_bg_index')||'0',10)||0;\n");
        js.append("        if (typeof skinIds !== 'undefined') document.documentElement.setAttribute('data-skin', skinIds[sIdx % skinIds.length]);\n");
        js.append("        if (typeof dispIds !== 'undefined') document.documentElement.setAttribute('data-display', dispIds[dIdx % dispIds.length]);\n");
        js.append("        if (typeof bgIds !== 'undefined') document.documentElement.setAttribute('data-bg', bgIds[bIdx % bgIds.length]);\n");
        js.append("        var br = (localStorage.getItem('proscan_custom_brand_v1')||'PROSCAN').trim();\n");
        js.append("        var bs = document.getElementById('brand-logo-slot');\n");
        js.append("        if (bs) bs.innerHTML = '<div class=\"brand-henus\"><span class=\"henus-mark\">'+br+'</span><span class=\"henus-sub\">ARCHIVE</span></div>';\n");
        js.append("      }\n");
        js.append("    }\n");
        js.append("  } catch(e) {}\n");

        js.append("  var nameHdr = document.querySelector('.screen-log-header span:last-child');\n");
        js.append("  if (nameHdr) nameHdr.innerText = 'NAME';\n");

        js.append("  var st = document.createElement('style');\n");
        js.append("  st.textContent = '' +\n");
        /* Clean Fade-In instead of 3D Flip */
        js.append("    '@keyframes smoothFadeIn{0%{opacity:0;}100%{opacity:1;}}' +\n");
        js.append("    '#device-wrapper{animation:smoothFadeIn 0.25s ease-out forwards;transition:opacity 0.22s ease!important;}' +\n");
        js.append("    '#device-wrapper.fading-out{opacity:0!important;}' +\n");
        js.append("    '.scanner-display{height:245px!important;}.top-controls-container{height:26px!important;}.buttons-box{gap:8px!important;}#mech-bay{height:224px!important;}' +\n");
        js.append("    '#playlist-box{overflow-y:scroll!important;-webkit-overflow-scrolling:touch!important;touch-action:pan-y!important;overscroll-behavior:contain!important;}' +\n");
        js.append("    '.rec-row{touch-action:pan-y!important;}' +\n");
        js.append("    '.rec-row.selected{outline:1px dashed var(--log-text,#38bdf8)!important;background:rgba(255,255,255,0.14)!important;font-weight:800!important;}' +\n");
        js.append("    '.unread-dot{display:inline-block;width:6px;height:6px;border-radius:50%;background:var(--log-text,#39ff14);margin-right:4px;vertical-align:middle;}' +\n");
        js.append("    '.top-row-5{display:grid!important;grid-template-columns:repeat(5,minmax(0,1fr))!important;gap:5px!important;height:28px!important;}' +\n");
        js.append("    '.filter-row-5{display:grid!important;grid-template-columns:88px 1fr 62px 32px 46px!important;gap:5px!important;height:27px!important;}' +\n");
        /* Upgraded Vinyl Turntable Layout */
        js.append("    '#deck-vinyl{background:radial-gradient(circle at 46% 54%,#1e2229 0%,#0c0e12 85%)!important;}' +\n");
        js.append("    '.tt-platter{position:absolute!important;left:90px!important;top:38px!important;width:140px!important;height:140px!important;box-shadow:0 8px 20px rgba(0,0,0,0.95),0 0 0 3px #18181b!important;}' +\n");
        js.append("    '.tt-record{width:128px!important;height:128px!important;}' +\n");
        js.append("    '.tt-strobe{position:absolute!important;left:76px!important;top:72px!important;bottom:auto!important;width:9px!important;height:14px!important;border-radius:3px!important;background:linear-gradient(180deg,#ff6600,#cc2200)!important;border:1px solid #ffb380!important;box-shadow:0 0 12px #ff4400,0 0 20px #ff1100!important;z-index:8!important;}' +\n");
        js.append("    '.tt-arm-base{top:34px!important;right:54px!important;width:38px!important;height:38px!important;box-shadow:0 4px 10px #000,inset 0 1px 2px rgba(255,255,255,0.6)!important;z-index:9!important;}' +\n");
        js.append("    '.tt-tonearm{top:50px!important;right:70px!important;height:118px!important;transform-origin:top center!important;transform:rotate(6deg);transition:transform 0.2s linear!important;z-index:10!important;}' +\n");
        /* Upgraded Nagra Reel-to-Reel Layout */
        js.append("    '.r2r-stage{width:94%!important;height:100%!important;padding:30px 18px 0!important;display:flex!important;justify-content:space-between!important;align-items:flex-start!important;}' +\n");
        js.append("    '.r2r-reel{width:110px!important;height:110px!important;margin-top:0!important;box-shadow:0 6px 14px rgba(0,0,0,0.95)!important;}' +\n");
        js.append("    '.r2r-tape-left{bottom:62px!important;left:56px!important;width:96px!important;transform:rotate(24deg)!important;}' +\n");
        js.append("    '.r2r-tape-right{bottom:62px!important;right:56px!important;width:96px!important;transform:rotate(-24deg)!important;}' +\n");
        js.append("    '.r2r-headblock{bottom:42px!important;width:92px!important;height:28px!important;background:linear-gradient(180deg,#e4e4e7 0%,#71717a 48%,#27272a 100%)!important;border:1px solid #09090b!important;box-shadow:0 4px 10px #000!important;}' +\n");
        js.append("    '.r2r-lower-bridge{position:absolute;bottom:28px;left:12px;right:12px;height:40px;border-radius:6px;background:linear-gradient(180deg,#272b32 0%,#14171c 100%);border:1px solid rgba(255,255,255,0.12);box-shadow:inset 0 2px 5px rgba(0,0,0,0.85),0 2px 6px #000;display:flex;justify-content:space-between;align-items:center;padding:0 10px;z-index:8;pointer-events:none;}' +\n");
        /* Upgraded SIGINT Hacker Canvas Layout */
        js.append("    '#hack-canvas{width:95%!important;height:152px!important;margin-top:6px!important;border-radius:8px!important;border:1px solid rgba(57,255,20,0.45)!important;box-shadow:inset 0 0 24px rgba(57,255,20,0.22),0 0 12px rgba(0,0,0,0.9)!important;}' +\n");
        js.append("    '.deck-track-badge{position:absolute;top:6px;left:10px;right:10px;z-index:14;display:flex;justify-content:space-between;padding:2px 8px;border-radius:4px;background:rgba(0,0,0,0.78);border:1px solid rgba(255,255,255,0.16);font-family:monospace;font-size:9.5px;font-weight:800;color:#38bdf8;pointer-events:none;}' +\n");
        js.append("    '.hw-side-panel{position:absolute;z-index:11;display:flex;flex-direction:column;gap:5px;align-items:center;}' +\n");
        js.append("    '.hw-pill{padding:2px 6px;border-radius:3px;background:linear-gradient(180deg,#2e343d,#15181c);border:1px solid #08090b;font-family:monospace;font-size:7.5px;font-weight:800;color:#94a3b8;box-shadow:0 1px 2px #000;letter-spacing:0.4px;}' +\n");
        js.append("    '.hw-fader-track{width:12px;height:86px;border-radius:3px;background:repeating-linear-gradient(180deg,#1e293b 0px,#1e293b 1px,#080a0c 1px,#080a0c 7px);border:1px solid #334155;position:relative;box-shadow:inset 0 2px 4px #000;}' +\n");
        js.append("    '.hw-fader-knob{position:absolute;top:36px;left:-3px;width:16px;height:11px;border-radius:2px;background:linear-gradient(180deg,#f8fafc,#94a3b8 45%,#334155 55%,#cbd5e1);border:1px solid #000;box-shadow:0 2px 4px rgba(0,0,0,0.85);}' +\n");
        js.append("    '.hw-vu-dial{width:54px;height:30px;border-radius:5px;background:radial-gradient(circle at 50% 95%,#fef9c3 0%,#fde047 55%,#ca8a04 100%);border:2px solid #090a0c;position:relative;overflow:hidden;box-shadow:inset 0 2px 5px rgba(0,0,0,0.65),0 1px 0 rgba(255,255,255,0.15);}' +\n");
        js.append("    '.hw-vu-dial::before{content:\"dB  -20 -10  0  +3\";position:absolute;top:3px;left:3px;right:3px;font-family:monospace;font-size:5px;font-weight:900;color:#1e293b;text-align:center;letter-spacing:-0.3px;}' +\n");
        js.append("    '.hw-vu-needle{position:absolute;bottom:-2px;left:25px;width:1.8px;height:25px;background:#dc2626;transform-origin:bottom center;transform:rotate(-35deg);transition:transform 0.06s ease;box-shadow:0 0 2px rgba(0,0,0,0.5);}' +\n");
        js.append("    '#cd-laser-sled{position:absolute;width:12px;height:12px;border-radius:50%;background:radial-gradient(circle,#ff2222 20%,rgba(255,0,0,0.7) 55%,transparent 100%);box-shadow:0 0 10px #ff0000;z-index:2;pointer-events:none;transition:transform 0.2s linear;}' +\n");
        js.append("    '#reactive-vu-strip{position:absolute;bottom:5px;left:50%;transform:translateX(-50%);display:flex;gap:3px;z-index:12;background:rgba(0,0,0,0.78);padding:3px 7px;border-radius:4px;border:1px solid rgba(255,255,255,0.14);}' +\n");
        js.append("    '.rvu-seg{width:6px;height:5px;border-radius:1px;background:#1e293b;}';\n");
        js.append("  document.head.appendChild(st);\n");
package com.proscan.interfaceapp;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerActivity extends Activity {

    private static final String GDRIVE_ROOT_FOLDER_ID = "1gToQyY6YPOk_3muiQSY5WLy7jTSvXbTv";
    private WebView playerWeb;
    private String serverBaseUrl = "";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService audioFetchExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextToSpeech tts = null;
    private boolean ttsReady = false;
    private MediaPlayer nativePlayer = null;
    private boolean isFetchingDrive = false;
    private float[] currentEnvelope = new float[0];

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            try {
                if (nativePlayer != null && nativePlayer.isPlaying() && playerWeb != null) {
                    int posMs = nativePlayer.getCurrentPosition();
                    int durMs = Math.max(1, nativePlayer.getDuration());
                    int sec = posMs / 1000;
                    int tot = Math.max(1, durMs / 1000);
                    float prog = Math.min(1f, Math.max(0f, (float) posMs / (float) durMs));

                    float level = 0.25f;
                    if (currentEnvelope.length > 0) {
                        int envIdx = Math.min(currentEnvelope.length - 1, (int) (prog * currentEnvelope.length));
                        level = currentEnvelope[envIdx];
                    }

                    playerWeb.evaluateJavascript(
                            "if(window.onNativeTelemetry)window.onNativeTelemetry(" + sec + "," + tot + "," + prog + "," + level + ");",
                            null
                    );
                    mainHandler.postDelayed(this, 60);
                }
            } catch (Exception ignored) {}
        }
    };

    private final Runnable autoSyncRunnable = new Runnable() {
        @Override
        public void run() {
            loadLiveGoogleDriveRecordings();
            mainHandler.postDelayed(this, 12000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        hideSystemUI();

        serverBaseUrl = getIntent().getStringExtra("server_url");
        if (serverBaseUrl == null) serverBaseUrl = "";
        serverBaseUrl = serverBaseUrl.replaceAll("/+$", "");

        try {
            tts = new TextToSpeech(getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(Locale.UK);
                    tts.setSpeechRate(1.05f);
                    ttsReady = true;
                }
            });
        } catch (Exception ignored) {}

        playerWeb = new WebView(this);
        playerWeb.setBackgroundColor(Color.BLACK);

        WebSettings s = playerWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(playerWeb, true);

        playerWeb.addJavascriptInterface(new PlayerBridge(), "PlayerBridge");
        playerWeb.setWebChromeClient(new WebChromeClient());
        playerWeb.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectReactiveDecksAndTheme();
                loadLiveGoogleDriveRecordings();
                mainHandler.removeCallbacks(autoSyncRunnable);
                mainHandler.postDelayed(autoSyncRunnable, 12000);
            }
        });

        setContentView(playerWeb);
        playerWeb.loadUrl("file:///android_asset/player_ui.html");
    }

    private void injectReactiveDecksAndTheme() {
        SharedPreferences prefs = getSharedPreferences("proscan_prefs", MODE_PRIVATE);
        String backupJson = prefs.getString("last_backup_json", "");
        String safeBackup = JSONObject.quote(backupJson);

        StringBuilder js = new StringBuilder();
        js.append("(function(){\n");
        js.append("  try {\n");
        js.append("    var raw = ").append(safeBackup).append(";\n");
        js.append("    if (raw) {\n");
        js.append("      var b = JSON.parse(raw);\n");
        js.append("      if (b.web_storage) {\n");
        js.append("        Object.keys(b.web_storage).forEach(function(k){ localStorage.setItem(k, b.web_storage[k]); });\n");
        js.append("        var sIdx = parseInt(localStorage.getItem('proscan_skin_index')||'0',10)||0;\n");
        js.append("        var dIdx = parseInt(localStorage.getItem('proscan_display_index')||'0',10)||0;\n");
        js.append("        var bIdx = parseInt(localStorage.getItem('proscan_bg_index')||'0',10)||0;\n");
        js.append("        if (typeof skinIds !== 'undefined') document.documentElement.setAttribute('data-skin', skinIds[sIdx % skinIds.length]);\n");
        js.append("        if (typeof dispIds !== 'undefined') document.documentElement.setAttribute('data-display', dispIds[dIdx % dispIds.length]);\n");
        js.append("        if (typeof bgIds !== 'undefined') document.documentElement.setAttribute('data-bg', bgIds[bIdx % bgIds.length]);\n");
        js.append("        var br = (localStorage.getItem('proscan_custom_brand_v1')||'PROSCAN').trim();\n");
        js.append("        var bs = document.getElementById('brand-logo-slot');\n");
        js.append("        if (bs) bs.innerHTML = '<div class=\"brand-henus\"><span class=\"henus-mark\">'+br+'</span><span class=\"henus-sub\">ARCHIVE</span></div>';\n");
        js.append("      }\n");
        js.append("    }\n");
        js.append("  } catch(e) {}\n");

        js.append("  var nameHdr = document.querySelector('.screen-log-header span:last-child');\n");
        js.append("  if (nameHdr) nameHdr.innerText = 'NAME';\n");

        js.append("  var st = document.createElement('style');\n");
        js.append("  st.textContent = '' +\n");
        /* Clean Fade-In instead of 3D Flip */
        js.append("    '@keyframes smoothFadeIn{0%{opacity:0;}100%{opacity:1;}}' +\n");
        js.append("    '#device-wrapper{animation:smoothFadeIn 0.25s ease-out forwards;transition:opacity 0.22s ease!important;}' +\n");
        js.append("    '#device-wrapper.fading-out{opacity:0!important;}' +\n");
        js.append("    '.scanner-display{height:245px!important;}.top-controls-container{height:26px!important;}.buttons-box{gap:8px!important;}#mech-bay{height:224px!important;}' +\n");
        js.append("    '#playlist-box{overflow-y:scroll!important;-webkit-overflow-scrolling:touch!important;touch-action:pan-y!important;overscroll-behavior:contain!important;}' +\n");
        js.append("    '.rec-row{touch-action:pan-y!important;}' +\n");
        js.append("    '.rec-row.selected{outline:1px dashed var(--log-text,#38bdf8)!important;background:rgba(255,255,255,0.14)!important;font-weight:800!important;}' +\n");
        js.append("    '.unread-dot{display:inline-block;width:6px;height:6px;border-radius:50%;background:var(--log-text,#39ff14);margin-right:4px;vertical-align:middle;}' +\n");
        js.append("    '.top-row-5{display:grid!important;grid-template-columns:repeat(5,minmax(0,1fr))!important;gap:5px!important;height:28px!important;}' +\n");
        js.append("    '.filter-row-5{display:grid!important;grid-template-columns:88px 1fr 62px 32px 46px!important;gap:5px!important;height:27px!important;}' +\n");
        /* Upgraded Vinyl Turntable Layout */
        js.append("    '#deck-vinyl{background:radial-gradient(circle at 46% 54%,#1e2229 0%,#0c0e12 85%)!important;}' +\n");
        js.append("    '.tt-platter{position:absolute!important;left:90px!important;top:38px!important;width:140px!important;height:140px!important;box-shadow:0 8px 20px rgba(0,0,0,0.95),0 0 0 3px #18181b!important;}' +\n");
        js.append("    '.tt-record{width:128px!important;height:128px!important;}' +\n");
        js.append("    '.tt-strobe{position:absolute!important;left:76px!important;top:72px!important;bottom:auto!important;width:9px!important;height:14px!important;border-radius:3px!important;background:linear-gradient(180deg,#ff6600,#cc2200)!important;border:1px solid #ffb380!important;box-shadow:0 0 12px #ff4400,0 0 20px #ff1100!important;z-index:8!important;}' +\n");
        js.append("    '.tt-arm-base{top:34px!important;right:54px!important;width:38px!important;height:38px!important;box-shadow:0 4px 10px #000,inset 0 1px 2px rgba(255,255,255,0.6)!important;z-index:9!important;}' +\n");
        js.append("    '.tt-tonearm{top:50px!important;right:70px!important;height:118px!important;transform-origin:top center!important;transform:rotate(6deg);transition:transform 0.2s linear!important;z-index:10!important;}' +\n");
        /* Upgraded Nagra Reel-to-Reel Layout */
        js.append("    '.r2r-stage{width:94%!important;height:100%!important;padding:30px 18px 0!important;display:flex!important;justify-content:space-between!important;align-items:flex-start!important;}' +\n");
        js.append("    '.r2r-reel{width:110px!important;height:110px!important;margin-top:0!important;box-shadow:0 6px 14px rgba(0,0,0,0.95)!important;}' +\n");
        js.append("    '.r2r-tape-left{bottom:62px!important;left:56px!important;width:96px!important;transform:rotate(24deg)!important;}' +\n");
        js.append("    '.r2r-tape-right{bottom:62px!important;right:56px!important;width:96px!important;transform:rotate(-24deg)!important;}' +\n");
        js.append("    '.r2r-headblock{bottom:42px!important;width:92px!important;height:28px!important;background:linear-gradient(180deg,#e4e4e7 0%,#71717a 48%,#27272a 100%)!important;border:1px solid #09090b!important;box-shadow:0 4px 10px #000!important;}' +\n");
        js.append("    '.r2r-lower-bridge{position:absolute;bottom:28px;left:12px;right:12px;height:40px;border-radius:6px;background:linear-gradient(180deg,#272b32 0%,#14171c 100%);border:1px solid rgba(255,255,255,0.12);box-shadow:inset 0 2px 5px rgba(0,0,0,0.85),0 2px 6px #000;display:flex;justify-content:space-between;align-items:center;padding:0 10px;z-index:8;pointer-events:none;}' +\n");
        /* Upgraded SIGINT Hacker Canvas Layout */
        js.append("    '#hack-canvas{width:95%!important;height:152px!important;margin-top:6px!important;border-radius:8px!important;border:1px solid rgba(57,255,20,0.45)!important;box-shadow:inset 0 0 24px rgba(57,255,20,0.22),0 0 12px rgba(0,0,0,0.9)!important;}' +\n");
        js.append("    '.deck-track-badge{position:absolute;top:6px;left:10px;right:10px;z-index:14;display:flex;justify-content:space-between;padding:2px 8px;border-radius:4px;background:rgba(0,0,0,0.78);border:1px solid rgba(255,255,255,0.16);font-family:monospace;font-size:9.5px;font-weight:800;color:#38bdf8;pointer-events:none;}' +\n");
        js.append("    '.hw-side-panel{position:absolute;z-index:11;display:flex;flex-direction:column;gap:5px;align-items:center;}' +\n");
        js.append("    '.hw-pill{padding:2px 6px;border-radius:3px;background:linear-gradient(180deg,#2e343d,#15181c);border:1px solid #08090b;font-family:monospace;font-size:7.5px;font-weight:800;color:#94a3b8;box-shadow:0 1px 2px #000;letter-spacing:0.4px;}' +\n");
        js.append("    '.hw-fader-track{width:12px;height:86px;border-radius:3px;background:repeating-linear-gradient(180deg,#1e293b 0px,#1e293b 1px,#080a0c 1px,#080a0c 7px);border:1px solid #334155;position:relative;box-shadow:inset 0 2px 4px #000;}' +\n");
        js.append("    '.hw-fader-knob{position:absolute;top:36px;left:-3px;width:16px;height:11px;border-radius:2px;background:linear-gradient(180deg,#f8fafc,#94a3b8 45%,#334155 55%,#cbd5e1);border:1px solid #000;box-shadow:0 2px 4px rgba(0,0,0,0.85);}' +\n");
        js.append("    '.hw-vu-dial{width:54px;height:30px;border-radius:5px;background:radial-gradient(circle at 50% 95%,#fef9c3 0%,#fde047 55%,#ca8a04 100%);border:2px solid #090a0c;position:relative;overflow:hidden;box-shadow:inset 0 2px 5px rgba(0,0,0,0.65),0 1px 0 rgba(255,255,255,0.15);}' +\n");
        js.append("    '.hw-vu-dial::before{content:\"dB  -20 -10  0  +3\";position:absolute;top:3px;left:3px;right:3px;font-family:monospace;font-size:5px;font-weight:900;color:#1e293b;text-align:center;letter-spacing:-0.3px;}' +\n");
        js.append("    '.hw-vu-needle{position:absolute;bottom:-2px;left:25px;width:1.8px;height:25px;background:#dc2626;transform-origin:bottom center;transform:rotate(-35deg);transition:transform 0.06s ease;box-shadow:0 0 2px rgba(0,0,0,0.5);}' +\n");
        js.append("    '#cd-laser-sled{position:absolute;width:12px;height:12px;border-radius:50%;background:radial-gradient(circle,#ff2222 20%,rgba(255,0,0,0.7) 55%,transparent 100%);box-shadow:0 0 10px #ff0000;z-index:2;pointer-events:none;transition:transform 0.2s linear;}' +\n");
        js.append("    '#reactive-vu-strip{position:absolute;bottom:5px;left:50%;transform:translateX(-50%);display:flex;gap:3px;z-index:12;background:rgba(0,0,0,0.78);padding:3px 7px;border-radius:4px;border:1px solid rgba(255,255,255,0.14);}' +\n");
        js.append("    '.rvu-seg{width:6px;height:5px;border-radius:1px;background:#1e293b;}';\n");
        js.append("  document.head.appendChild(st);\n");