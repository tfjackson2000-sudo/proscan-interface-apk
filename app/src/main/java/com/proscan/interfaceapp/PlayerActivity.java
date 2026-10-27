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

        /* Fix Smooth Touch Scrolling on #playlist-box + Remove (HOLD=DEL) header text */
        js.append("  var nameHdr = document.querySelector('.screen-log-header span:last-child');\n");
        js.append("  if (nameHdr) nameHdr.innerText = 'NAME';\n");

        js.append("  var st = document.createElement('style');\n");
        js.append("  st.textContent = '' +\n");
        js.append("    '#custom-scanner-ui-wrapper{perspective:1400px!important;}' +\n");
        js.append("    '@keyframes flipInFromRadio{0%{transform:rotateY(-90deg) scale(0.95);}100%{transform:rotateY(0deg) scale(1);}}' +\n");
        js.append("    '#device-wrapper{animation:flipInFromRadio 0.36s cubic-bezier(0.22,1,0.36,1) forwards;transition:transform 0.32s ease!important;transform-style:preserve-3d!important;}' +\n");
        js.append("    '#device-wrapper.flipping-back{transform:rotateY(-90deg) scale(0.95)!important;}' +\n");
        js.append("    '.scanner-display{height:245px!important;}.top-controls-container{height:26px!important;}.buttons-box{gap:8px!important;}#mech-bay{height:224px!important;}' +\n");
        js.append("    '#playlist-box{overflow-y:scroll!important;-webkit-overflow-scrolling:touch!important;touch-action:pan-y!important;overscroll-behavior:contain!important;}' +\n");
        js.append("    '.rec-row{touch-action:pan-y!important;}' +\n");
        js.append("    '.rec-row.selected{outline:1px dashed var(--log-text,#38bdf8)!important;background:rgba(255,255,255,0.14)!important;font-weight:800!important;}' +\n");
        js.append("    '.unread-dot{display:inline-block;width:6px;height:6px;border-radius:50%;background:var(--log-text,#39ff14);margin-right:4px;vertical-align:middle;}' +\n");
        js.append("    '.top-row-5{display:grid!important;grid-template-columns:repeat(5,minmax(0,1fr))!important;gap:5px!important;height:28px!important;}' +\n");
        js.append("    '.filter-row-5{display:grid!important;grid-template-columns:88px 1fr 62px 32px 46px!important;gap:5px!important;height:27px!important;}' +\n");
        js.append("    '.tt-platter{left:-34px!important;width:164px!important;height:164px!important;}.tt-record{width:152px!important;height:152px!important;}' +\n");
        js.append("    '.tt-arm-base{top:18px!important;right:44px!important;}.tt-tonearm{top:34px!important;right:58px!important;height:126px!important;transform-origin:top center!important;transform:rotate(4deg);transition:transform 0.2s linear!important;}' +\n");
        js.append("    '.deck-track-badge{position:absolute;top:7px;left:10px;right:10px;z-index:14;display:flex;justify-content:space-between;padding:2px 8px;border-radius:4px;background:rgba(0,0,0,0.72);border:1px solid rgba(255,255,255,0.16);font-family:monospace;font-size:9.5px;font-weight:800;color:#38bdf8;pointer-events:none;}' +\n");
        js.append("    '.hw-side-panel{position:absolute;z-index:6;display:flex;flex-direction:column;gap:6px;align-items:center;}' +\n");
        js.append("    '.hw-pill{padding:2px 6px;border-radius:3px;background:linear-gradient(180deg,#2e343d,#15181c);border:1px solid #08090b;font-family:monospace;font-size:7.5px;font-weight:800;color:#94a3b8;box-shadow:0 1px 2px #000;}' +\n");
        js.append("    '.hw-fader-track{width:10px;height:86px;border-radius:3px;background:#080a0c;border:1px solid #334155;position:relative;box-shadow:inset 0 2px 4px #000;}' +\n");
        js.append("    '.hw-fader-knob{position:absolute;top:36px;left:-3px;width:14px;height:10px;border-radius:2px;background:linear-gradient(180deg,#e2e8f0,#64748b);border:1px solid #000;}' +\n");
        js.append("    '.hw-vu-dial{width:46px;height:28px;border-radius:6px 6px 2px 2px;background:radial-gradient(circle at 50% 90%,#fef08a,#ca8a04);border:2px solid #18181b;position:relative;overflow:hidden;box-shadow:inset 0 2px 4px rgba(0,0,0,0.6);}' +\n");
        js.append("    '.hw-vu-needle{position:absolute;bottom:-2px;left:21px;width:2px;height:24px;background:#b91c1c;transform-origin:bottom center;transform:rotate(-35deg);transition:transform 0.06s ease;}' +\n");
        js.append("    '#cd-laser-sled{position:absolute;width:12px;height:12px;border-radius:50%;background:radial-gradient(circle,#ff2222 20%,rgba(255,0,0,0.7) 55%,transparent 100%);box-shadow:0 0 10px #ff0000;z-index:2;pointer-events:none;transition:transform 0.2s linear;}' +\n");
        js.append("    '#reactive-vu-strip{position:absolute;bottom:6px;left:50%;transform:translateX(-50%);display:flex;gap:3px;z-index:12;background:rgba(0,0,0,0.7);padding:3px 7px;border-radius:4px;border:1px solid rgba(255,255,255,0.14);}' +\n");
        js.append("    '.rvu-seg{width:6px;height:5px;border-radius:1px;background:#1e293b;}';\n");
        js.append("  document.head.appendChild(st);\n");
/* Add LOOP button to top row + 3-Way Filter Button (ALL -> NEW [Since Last Visit] -> UNPLAYED) */
        js.append("  var topRow = document.getElementById('btn-back-radio').parentNode;\n");
        js.append("  if (topRow && !document.getElementById('btn-mode-loop2')) {\n");
        js.append("    topRow.className = 'top-row-5';\n");
        js.append("    var lpBtn = document.createElement('button');\n");
        js.append("    lpBtn.id = 'btn-mode-loop2'; lpBtn.className = 'btn'; lpBtn.type = 'button';\n");
        js.append("    lpBtn.innerText = 'LOOP: OFF';\n");
        js.append("    topRow.appendChild(lpBtn);\n");
        js.append("    window.__isLoopMode = false;\n");
        js.append("    lpBtn.addEventListener('click', function() {\n");
        js.append("      window.__isLoopMode = !window.__isLoopMode;\n");
        js.append("      lpBtn.classList.toggle('active', window.__isLoopMode);\n");
        js.append("      lpBtn.innerText = window.__isLoopMode ? 'LOOP: ON' : 'LOOP: OFF';\n");
        js.append("    });\n");
        js.append("  }\n");

        /* Track Seen Recordings from Previous Visits so 'NEW' only shows recordings since your last visit! */
        js.append("  window.__seenOnPrevVisit = JSON.parse(localStorage.getItem('proscan_seen_visit_ids_v1') || '{}');\n");
        js.append("  window.__playedDriveIds = JSON.parse(localStorage.getItem('proscan_played_recs_v1') || '{}');\n");
        js.append("  window.__filterMode = 0;\n"); /* 0 = ALL, 1 = NEW (Since Last Visit), 2 = UNPLAYED */

        js.append("  var fRow = document.getElementById('date-select').parentNode;\n");
        js.append("  if (fRow && !document.getElementById('btn-filter-unread')) {\n");
        js.append("    fRow.className = 'filter-row-5';\n");
        js.append("    var uBtn = document.createElement('button');\n");
        js.append("    uBtn.id = 'btn-filter-unread'; uBtn.className = 'btn'; uBtn.type = 'button';\n");
        js.append("    uBtn.innerText = 'ALL';\n");
        js.append("    fRow.insertBefore(uBtn, document.getElementById('btn-refresh'));\n");
        js.append("    uBtn.addEventListener('click', function() {\n");
        js.append("      window.__filterMode = (window.__filterMode + 1) % 3;\n");
        js.append("      uBtn.classList.toggle('active', window.__filterMode !== 0);\n");
        js.append("      uBtn.innerText = window.__filterMode === 0 ? 'ALL' : (window.__filterMode === 1 ? 'NEW' : 'UNPLAY');\n");
        js.append("      window.renderPlaylist();\n");
        js.append("    });\n");
        js.append("  }\n");

        /* Add Track Name Badge + Turntable/CDJ/Nagra Hardware Controls (WITHOUT the ugly Cassette SVG line!) */
        js.append("  var mb = document.getElementById('mech-bay');\n");
        js.append("  if (mb && !document.getElementById('deck-global-badge')) {\n");
        js.append("    var bdg = document.createElement('div'); bdg.id = 'deck-global-badge'; bdg.className = 'deck-track-badge';\n");
        js.append("    bdg.innerHTML = '<span id=\"badge-ch-name\">READY // SELECT TRACK</span><span id=\"badge-ch-freq\">---.----</span>';\n");
        js.append("    mb.appendChild(bdg);\n");
        js.append("    var vu = document.createElement('div'); vu.id = 'reactive-vu-strip';\n");
        js.append("    var segs = ''; for (var s=0; s<14; s++) segs += '<div class=\"rvu-seg\"></div>';\n");
        js.append("    vu.innerHTML = segs; mb.appendChild(vu);\n");

        js.append("    var cdWell = document.querySelector('.cd-well');\n");
        js.append("    if (cdWell) {\n");
        js.append("      var sled = document.createElement('div'); sled.id = 'cd-laser-sled';\n");
        js.append("      cdWell.appendChild(sled);\n");
        js.append("      var dCd = document.getElementById('deck-cd');\n");
        js.append("      var leftWing = document.createElement('div'); leftWing.className = 'hw-side-panel'; leftWing.style.left = '14px';\n");
        js.append("      leftWing.innerHTML = '<div class=\"hw-pill\">MASTER TEMPO</div><div class=\"hw-pill\">JOG: VINYL</div><div class=\"hw-pill\">CUE / LOOP</div><div class=\"hw-vu-dial\"><div class=\"hw-vu-needle\" id=\"cd-vu-l\"></div></div>';\n");
        js.append("      var rightWing = document.createElement('div'); rightWing.className = 'hw-side-panel'; rightWing.style.right = '16px';\n");
        js.append("      rightWing.innerHTML = '<div class=\"hw-pill\">TEMPO ±8%</div><div class=\"hw-fader-track\"><div class=\"hw-fader-knob\"></div></div>';\n");
        js.append("      dCd.appendChild(leftWing); dCd.appendChild(rightWing);\n");
        js.append("    }\n");

        js.append("    var dVinyl = document.getElementById('deck-vinyl');\n");
        js.append("    if (dVinyl) {\n");
        js.append("      var ttLeft = document.createElement('div'); ttLeft.className = 'hw-side-panel'; ttLeft.style.cssText = 'left:12px;bottom:14px;';\n");
        js.append("      ttLeft.innerHTML = '<div class=\"hw-pill\">START/STOP</div><div style=\"display:flex;gap:3px;\"><div class=\"hw-pill\" style=\"color:#38bdf8;\">33</div><div class=\"hw-pill\">45</div></div>';\n");
        js.append("      var ttRight = document.createElement('div'); ttRight.className = 'hw-side-panel'; ttRight.style.cssText = 'right:12px;bottom:16px;';\n");
        js.append("      ttRight.innerHTML = '<div class=\"hw-pill\">PITCH</div><div class=\"hw-fader-track\" style=\"height:72px;\"><div class=\"hw-fader-knob\" style=\"top:30px;\"></div></div>';\n");
        js.append("      dVinyl.appendChild(ttLeft); dVinyl.appendChild(ttRight);\n");
        js.append("    }\n");

        js.append("    var rStage = document.querySelector('.r2r-stage');\n");
        js.append("    if (rStage) {\n");
        js.append("      rStage.style.paddingTop = '26px';\n");
        js.append("      var reels = rStage.querySelectorAll('.r2r-reel');\n");
        js.append("      if (reels.length >= 2) { reels[0].id = 'r2r-reel-left'; reels[1].id = 'r2r-reel-right'; }\n");
        js.append("      var meters = document.createElement('div');\n");
        js.append("      meters.style.cssText = 'position:absolute;bottom:12px;left:16px;right:16px;display:flex;justify-content:space-between;align-items:center;pointer-events:none;z-index:8;';\n");
        js.append("      meters.innerHTML = '<div class=\"hw-vu-dial\"><div class=\"hw-vu-needle\" id=\"r2r-vu-l\"></div></div><div class=\"hw-vu-dial\"><div class=\"hw-vu-needle\" id=\"r2r-vu-r\"></div></div>';\n");
        js.append("      rStage.appendChild(meters);\n");
        js.append("    }\n");
        js.append("  }\n");

        js.append("  window.__audioLevel = 0;\n");
        js.append("  window.__audioProgress = 0;\n");

        js.append("  window.onNativeTelemetry = function(sec, totSec, prog, level) {\n");
        js.append("    window.__audioProgress = prog || 0;\n");
        js.append("    window.__audioLevel = level || 0;\n");
        js.append("    if (typeof window.onNativeTimeUpdate === 'function') window.onNativeTimeUpdate(sec, totSec);\n");

        js.append("    var arm = document.querySelector('.tt-tonearm');\n");
        js.append("    if (arm) arm.style.transform = 'rotate(' + (26 + window.__audioProgress * 19).toFixed(2) + 'deg)';\n");

        /* Cassette Spools Shrink/Grow cleanly without the ugly line */
        js.append("    var spL = document.querySelector('.c-spool.left'), spR = document.querySelector('.c-spool.right');\n");
        js.append("    if (spL && spR) {\n");
        js.append("      var szL = 64 - (window.__audioProgress * 22), szR = 42 + (window.__audioProgress * 22);\n");
        js.append("      spL.style.width = szL + 'px'; spL.style.height = szL + 'px';\n");
        js.append("      spR.style.width = szR + 'px'; spR.style.height = szR + 'px';\n");
        js.append("    }\n");

        js.append("    var rrL = document.getElementById('r2r-reel-left'), rrR = document.getElementById('r2r-reel-right');\n");
        js.append("    if (rrL && rrR) {\n");
        js.append("      var pL = Math.round(72 - window.__audioProgress * 32), pR = Math.round(40 + window.__audioProgress * 32);\n");
        js.append("      rrL.style.boxShadow = 'inset 0 0 0 ' + (30 - pL/3) + 'px #18181b, 0 6px 14px rgba(0,0,0,0.9)';\n");
        js.append("      rrR.style.boxShadow = 'inset 0 0 0 ' + (30 - pR/3) + 'px #18181b, 0 6px 14px rgba(0,0,0,0.9)';\n");
        js.append("    }\n");
        js.append("    var deg = -38 + (window.__audioLevel * 76);\n");
        js.append("    ['cd-vu-l','r2r-vu-l','r2r-vu-r'].forEach(function(id, i){\n");
        js.append("      var ndl = document.getElementById(id);\n");
        js.append("      if (ndl) ndl.style.transform = 'rotate(' + (deg + (i%2===0?2:-2)).toFixed(1) + 'deg)';\n");
        js.append("    });\n");

        js.append("    var sled = document.getElementById('cd-laser-sled');\n");
        js.append("    if (sled) {\n");
        js.append("      var dist = -22 - (window.__audioProgress * 36);\n");
        js.append("      sled.style.transform = 'translateX(' + dist.toFixed(1) + 'px) scale(' + (0.9 + window.__audioLevel*0.5).toFixed(2) + ')';\n");
        js.append("    }\n");

        js.append("    var activeSegs = Math.round(window.__audioLevel * 14);\n");
        js.append("    document.querySelectorAll('#reactive-vu-strip .rvu-seg').forEach(function(seg, idx) {\n");
        js.append("      if (idx < activeSegs) {\n");
        js.append("        var col = idx >= 11 ? '#ef4444' : (idx >= 8 ? '#facc15' : '#22c55e');\n");
        js.append("        seg.style.background = col; seg.style.boxShadow = '0 0 5px ' + col;\n");
        js.append("      } else {\n");
        js.append("        seg.style.background = '#1e293b'; seg.style.boxShadow = 'none';\n");
        js.append("      }\n");
        js.append("    });\n");
        js.append("  };\n");

        /* Full-Height Oscilloscope Without Bottom Bar Graph for Hacker Deck */
        js.append("  var hCanvas = document.getElementById('hack-canvas');\n");
        js.append("  if (hCanvas) {\n");
        js.append("    var hCtx = hCanvas.getContext('2d'), hPhase = 0;\n");
        js.append("    function drawCleanScope() {\n");
        js.append("      requestAnimationFrame(drawCleanScope);\n");
        js.append("      if (document.documentElement.getAttribute('data-deck') !== 'hack') return;\n");
        js.append("      hCtx.clearRect(0, 0, hCanvas.width, hCanvas.height);\n");
        js.append("      var lvl = isPlayingNow ? window.__audioLevel : 0;\n");
        js.append("      hCtx.strokeStyle = 'rgba(57,255,20,0.28)'; hCtx.lineWidth = 1;\n");
        js.append("      hCtx.beginPath(); hCtx.moveTo(0, 75); hCtx.lineTo(hCanvas.width, 75); hCtx.stroke();\n");
        js.append("      hCtx.strokeStyle = '#39ff14'; hCtx.lineWidth = 2.4;\n");
        js.append("      hCtx.beginPath();\n");
        js.append("      for (var x = 0; x < hCanvas.width; x += 2) {\n");
        js.append("        var env = Math.sin((x / hCanvas.width) * Math.PI);\n");
        js.append("        var w1 = Math.sin(x * 0.09 + hPhase) * Math.cos(x * 0.035 - hPhase * 0.8);\n");
        js.append("        var w2 = Math.sin(x * 0.22 - hPhase * 1.4) * 0.35;\n");
        js.append("        var y = 75 + ((w1 + w2) * env * (lvl * 56));\n");
        js.append("        if (x === 0) hCtx.moveTo(x, y); else hCtx.lineTo(x, y);\n");
        js.append("      }\n");
        js.append("      hCtx.stroke();\n");
        js.append("      hPhase += 0.26 + (lvl * 0.4);\n");
        js.append("    }\n");
        js.append("    drawCleanScope();\n");
        js.append("  }\n");

        /* Tap to Select/Highlight Only + Smooth Scrolling + 'NEW Since Last Visit' Filter */
        js.append("  window.selectIndexOnly = function(idx) {\n");
        js.append("    if (idx < 0 || idx >= filteredRecs.length) return;\n");
        js.append("    curIdx = idx;\n");
        js.append("    var r = filteredRecs[idx];\n");
        js.append("    document.getElementById('track-main-readout').innerText = 'READY: ' + r.name + ' • ' + r.freq;\n");
        js.append("    document.getElementById('cassette-lbl-left').innerText = r.name;\n");
        js.append("    document.getElementById('cassette-lbl-right').innerText = r.freq + ' (' + r.timeStr + ')';\n");
        js.append("    var bCh = document.getElementById('badge-ch-name'), bFr = document.getElementById('badge-ch-freq');\n");
        js.append("    if (bCh) bCh.innerText = r.name + ' (' + r.timeStr + ')';\n");
        js.append("    if (bFr) bFr.innerText = r.freq;\n");
        js.append("    document.getElementById('time-cur').innerText = '00:00';\n");
        js.append("    document.getElementById('time-tot').innerText = r.dur || '00:03';\n");
        js.append("    document.querySelectorAll('.rec-row').forEach(function(el, i){ el.classList.toggle('selected', i === idx); });\n");
        js.append("  };\n");

        /* Save all current IDs as 'seen' when leaving the player so next visit only marks newer arrivals as NEW! */
        js.append("  window.markCurrentListAsVisited = function() {\n");
        js.append("    try {\n");
        js.append("      allRecs.forEach(function(r){ window.__seenOnPrevVisit[r.fileId || r.fileName] = true; });\n");
        js.append("      localStorage.setItem('proscan_seen_visit_ids_v1', JSON.stringify(window.__seenOnPrevVisit));\n");
        js.append("    } catch(e) {}\n");
        js.append("  };\n");

        js.append("  window.renderPlaylist = function() {\n");
        js.append("    var box = document.getElementById('playlist-box');\n");
        js.append("    var selDate = document.getElementById('date-select').value;\n");
        js.append("    var q = (document.getElementById('search-box').value || '').trim().toLowerCase();\n");
        js.append("    var prevScroll = box.scrollTop;\n");
        js.append("    var hasPrevVisitData = Object.keys(window.__seenOnPrevVisit).length > 0;\n");
        js.append("    if (!hasPrevVisitData && allRecs.length > 0) {\n");
        js.append("      /* First time ever opening: mark older items as seen so only the newest 10 show as NEW */ \n");
        js.append("      for (var k = 10; k < allRecs.length; k++) {\n");
        js.append("        window.__seenOnPrevVisit[allRecs[k].fileId || allRecs[k].fileName] = true;\n");
        js.append("      }\n");
        js.append("    }\n");
        js.append("    filteredRecs = allRecs.filter(function(r) {\n");
        js.append("      var id = r.fileId || r.fileName;\n");
        js.append("      if (window.__filterMode === 1 && window.__seenOnPrevVisit[id]) return false;\n");
        js.append("      if (window.__filterMode === 2 && window.__playedDriveIds[id]) return false;\n");
        js.append("      if (selDate && r.ukDate !== selDate) return false;\n");
        js.append("      if (q && !r.name.toLowerCase().includes(q) && !r.freq.toLowerCase().includes(q) && !r.timeStr.toLowerCase().includes(q)) return false;\n");
        js.append("      return true;\n");
        js.append("    });\n");
        js.append("    var newSinceVisit = allRecs.filter(function(r){ return !window.__seenOnPrevVisit[r.fileId || r.fileName]; }).length;\n");
        js.append("    document.getElementById('rec-count-hud').innerText = filteredRecs.length + ' (' + newSinceVisit + ' NEW)';\n");
        js.append("    if (!filteredRecs.length) {\n");
        js.append("      box.innerHTML = '<div style=\"padding:14px;text-align:center;opacity:0.6;font-size:10px;\">No matching recordings.</div>';\n");
        js.append("      return;\n");
        js.append("    }\n");
        js.append("    var html = '';\n");
        js.append("    for (var i = 0; i < filteredRecs.length; i++) {\n");
        js.append("      var r = filteredRecs[i];\n");
        js.append("      var id = r.fileId || r.fileName;\n");
        js.append("      var isNewArrival = !window.__seenOnPrevVisit[id] && !window.__playedDriveIds[id];\n");
        js.append("      var dot = isNewArrival ? '<span class=\"unread-dot\"></span>' : '';\n");
        js.append("      var cls = ((r.fileId && r.fileId === window.__activePlayingFileId && isPlayingNow) ? 'playing ' : '') + (i === curIdx ? 'selected' : '');\n");
        js.append("      html += '<div class=\"rec-row ' + cls + '\" data-idx=\"' + i + '\">' +\n");
        js.append("        '<span>' + r.ukDate + '</span><span>' + r.timeStr + '</span><span>' + r.freq + '</span><span>' + r.dur + '</span>' +\n");
        js.append("        '<span title=\"' + r.name + '\">' + dot + r.name + '</span></div>';\n");
        js.append("    }\n");
        js.append("    box.innerHTML = html; box.scrollTop = prevScroll;\n");
        js.append("    box.querySelectorAll('.rec-row').forEach(function(el) {\n");
        js.append("      el.addEventListener('click', function() {\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(15);\n");
        js.append("        window.selectIndexOnly(parseInt(el.getAttribute('data-idx'), 10));\n");
        js.append("      });\n");
        js.append("    });\n");
        js.append("  };\n");

        js.append("  var origPlayIndex = window.playIndex;\n");
        js.append("  window.playIndex = function(idx) {\n");
        js.append("    if (idx < 0 || idx >= filteredRecs.length) return;\n");
        js.append("    var r = filteredRecs[idx];\n");
        js.append("    var id = r.fileId || r.fileName;\n");
        js.append("    window.__playedDriveIds[id] = true;\n");
        js.append("    window.__seenOnPrevVisit[id] = true;\n");
        js.append("    localStorage.setItem('proscan_played_recs_v1', JSON.stringify(window.__playedDriveIds));\n");
        js.append("    localStorage.setItem('proscan_seen_visit_ids_v1', JSON.stringify(window.__seenOnPrevVisit));\n");
        js.append("    var bCh = document.getElementById('badge-ch-name'), bFr = document.getElementById('badge-ch-freq');\n");
        js.append("    if (bCh) bCh.innerText = 'PLAYING: ' + r.name + ' (' + r.timeStr + ')';\n");
        js.append("    if (bFr) bFr.innerText = r.freq;\n");
        js.append("    origPlayIndex(idx);\n");
        js.append("  };\n");

        js.append("  window.onNativePlayEnded = function() {\n");
        js.append("    setPlayingUI(false); renderPlaylist();\n");
        js.append("    if (window.__isLoopMode && curIdx >= 0) playIndex(curIdx);\n");
        js.append("    else if (isCont && curIdx > 0) playIndex(curIdx - 1);\n");
        js.append("  };\n");

        js.append("  var oldPlayBtn = document.getElementById('btn-play');\n");
        js.append("  if (oldPlayBtn) {\n");
        js.append("    var newPlayBtn = oldPlayBtn.cloneNode(true);\n");
        js.append("    oldPlayBtn.parentNode.replaceChild(newPlayBtn, oldPlayBtn);\n");
        js.append("    newPlayBtn.addEventListener('click', function() {\n");
        js.append("      if (curIdx === -1 && filteredRecs.length > 0) curIdx = 0;\n");
        js.append("      if (curIdx >= 0 && curIdx < filteredRecs.length) {\n");
        js.append("        var r = filteredRecs[curIdx];\n");
        js.append("        if (window.__activePlayingFileId === r.fileId && isPlayingNow) {\n");
        js.append("          if (window.PlayerBridge) window.PlayerBridge.togglePause();\n");
        js.append("        } else {\n");
        js.append("          playIndex(curIdx);\n");
        js.append("        }\n");
        js.append("      }\n");
        js.append("    });\n");
        js.append("  }\n");

        js.append("  var backBtn = document.getElementById('btn-back-radio');\n");
        js.append("  if (backBtn) {\n");
        js.append("    var newBack = backBtn.cloneNode(true);\n");
        js.append("    backBtn.parentNode.replaceChild(newBack, backBtn);\n");
        js.append("    newBack.addEventListener('click', function() {\n");
        js.append("      if (navigator.vibrate) navigator.vibrate(20);\n");
        js.append("      if (window.markCurrentListAsVisited) window.markCurrentListAsVisited();\n");
        js.append("      var dw = document.getElementById('device-wrapper');\n");
        js.append("      if (dw) dw.classList.add('flipping-back');\n");
        js.append("      setTimeout(function() { if (window.PlayerBridge) window.PlayerBridge.closePlayer(); }, 260);\n");
        js.append("    });\n");
        js.append("  }\n");
        js.append("})();");

        if (playerWeb != null) playerWeb.evaluateJavascript(js.toString(), null);
    }

    private void stopNativePlayer() {
        mainHandler.removeCallbacks(progressTicker);
        if (nativePlayer != null) {
            try { if (nativePlayer.isPlaying()) nativePlayer.stop(); } catch (Exception ignored) {}
            try { nativePlayer.reset(); nativePlayer.release(); } catch (Exception ignored) {}
            nativePlayer = null;
        }
    }

    private float[] extractMp3AmplitudeEnvelope(File mp3File) {
        int buckets = 240;
        float[] env = new float[buckets];
        try {
            long len = mp3File.length();
            if (len <= 512) return env;
            FileInputStream fis = new FileInputStream(mp3File);
            byte[] data = new byte[(int) Math.min(len, 2 * 1024 * 1024)];
            int read = fis.read(data);
            fis.close();
            if (read <= 256) return env;
            int startOffset = Math.min(read / 10, 1024);
            int sliceLen = Math.max(1, (read - startOffset) / buckets);
            float minVal = Float.MAX_VALUE, maxVal = Float.MIN_VALUE;
            for (int b = 0; b < buckets; b++) {
                int s = startOffset + b * sliceLen;
                int end = Math.min(read - 1, s + sliceLen);
                long energy = 0;
                int zeroPad = 0;
                for (int i = s; i < end; i++) {
                    int v = data[i] & 0xFF;
                    if (v == 0x00 || v == 0xFF || v == 0xAA || v == 0x55) zeroPad++;
                    energy += Math.abs((data[i] & 0xFF) - (data[i + 1] & 0xFF));
                }
                int count = Math.max(1, end - s);
                float padRatio = (float) zeroPad / (float) count;
                float rawScore = ((float) energy / (float) count) * (1.0f - padRatio * 0.85f);
                env[b] = rawScore;
                if (rawScore < minVal) minVal = rawScore;
                if (rawScore > maxVal) maxVal = rawScore;
            }
            float range = Math.max(1f, maxVal - minVal);
            for (int b = 0; b < buckets; b++) {
                float norm = (env[b] - minVal) / range;
                norm = (float) Math.pow(norm, 1.55);
                env[b] = Math.min(1f, Math.max(0.02f, norm));
            }
        } catch (Exception ignored) {}
        return env;
    }

    private void playDriveFileIdNatively(final String fileId) {
        stopNativePlayer();
        final String urlStr = "https://drive.usercontent.google.com/download?id=" + fileId + "&export=download";
        audioFetchExecutor.execute(() -> {
            try {
                File tempMp3 = new File(getCacheDir(), "active_track.mp3");
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(9000);
                conn.setReadTimeout(9000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/124.0.0.0 Mobile");
                InputStream is = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(tempMp3);
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                fos.close();
                is.close();
                conn.disconnect();
                final float[] env = extractMp3AmplitudeEnvelope(tempMp3);
                mainHandler.post(() -> {
                    try {
                        stopNativePlayer();
                        currentEnvelope = env;
                        nativePlayer = new MediaPlayer();
                        if (Build.VERSION.SDK_INT >= 21) {
                            nativePlayer.setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build());
                        }
                        FileInputStream fis = new FileInputStream(tempMp3);
                        nativePlayer.setDataSource(fis.getFD());
                        fis.close();
                        nativePlayer.setOnPreparedListener(mp -> {
                            mp.start();
                            if (playerWeb != null) playerWeb.evaluateJavascript("if(window.onNativePlayStarted)window.onNativePlayStarted();", null);
                            mainHandler.post(progressTicker);
                        });
                        nativePlayer.setOnCompletionListener(mp -> {
                            stopNativePlayer();
                            if (playerWeb != null) playerWeb.evaluateJavascript("if(window.onNativePlayEnded)window.onNativePlayEnded();", null);
                        });
                        nativePlayer.prepareAsync();
                    } catch (Exception e) {
                        if (playerWeb != null) playerWeb.evaluateJavascript("if(window.onNativePlayError)window.onNativePlayError('PLAY ERROR');", null);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (playerWeb != null) playerWeb.evaluateJavascript("if(window.onNativePlayError)window.onNativePlayError('STREAM ERROR');", null);
                });
            }
        });
    }

    public class PlayerBridge {
        @JavascriptInterface
        public String getServerUrl() { return serverBaseUrl; }

        @JavascriptInterface
        public void refreshRecordings() {
            mainHandler.post(() -> Toast.makeText(PlayerActivity.this, "Syncing latest files from Google Drive...", Toast.LENGTH_SHORT).show());
            loadLiveGoogleDriveRecordings();
        }

        @JavascriptInterface
        public void playDriveIdDirect(final String fileId) {
            mainHandler.post(() -> playDriveFileIdNatively(fileId));
        }

        @JavascriptInterface
        public void seekToSeconds(final int sec) {
            mainHandler.post(() -> {
                try { if (nativePlayer != null) nativePlayer.seekTo(sec * 1000); } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void stopAudio() { mainHandler.post(() -> stopNativePlayer()); }

        @JavascriptInterface
        public void togglePause() {
            mainHandler.post(() -> {
                try {
                    if (nativePlayer != null) {
                        if (nativePlayer.isPlaying()) {
                            nativePlayer.pause();
                            if (playerWeb != null) playerWeb.evaluateJavascript("if(typeof setPlayingUI==='function')setPlayingUI(false);renderPlaylist();", null);
                        } else {
                            nativePlayer.start();
                            if (playerWeb != null) playerWeb.evaluateJavascript("if(typeof setPlayingUI==='function')setPlayingUI(true);renderPlaylist();", null);
                            mainHandler.post(progressTicker);
                        }
                    }
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void openGoogleDriveFolder() {
            mainHandler.post(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com/drive/folders/" + GDRIVE_ROOT_FOLDER_ID));
                    startActivity(i);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void speakCaption(final String text) {
            mainHandler.post(() -> {
                if (tts != null && ttsReady && text != null && !text.trim().isEmpty()) {
                    if (Build.VERSION.SDK_INT >= 21) {
                        Bundle params = new Bundle();
                        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.35f);
                        tts.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, params, "player_tts");
                    } else {
                        tts.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, null);
                    }
                }
            });
        }

        @JavascriptInterface
        public void closePlayer() {
            mainHandler.post(() -> {
                stopNativePlayer();
                finish();
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            });
        }
    }

    private String fetchUrlText(String urlStr) {
        try {
            String sep = urlStr.contains("?") ? "&" : "?";
            URL url = new URL(urlStr + sep + "_t=" + System.currentTimeMillis());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(9000);
            conn.setReadTimeout(9000);
            conn.setUseCaches(false);
            conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate");
            conn.setRequestProperty("Pragma", "no-cache");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, String> parseDriveFolderAllItems(String folderId) {
        Map<String, String> map = new LinkedHashMap<>();
        if (folderId == null || folderId.isEmpty()) return map;
        String html1 = fetchUrlText("https://drive.google.com/embeddedfolderview?id=" + folderId);
        if (!html1.isEmpty()) {
            Pattern p1 = Pattern.compile("id=\"entry-([A-Za-z0-9_-]{15,})\"[\\s\\S]*?<div class=\"flip-entry-title\">([^<]+)</div>");
            Matcher m1 = p1.matcher(html1);
            while (m1.find()) {
                map.put(m1.group(2).trim().replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\""), m1.group(1).trim());
            }
        }
        String html2 = fetchUrlText("https://drive.google.com/drive/folders/" + folderId);
        if (!html2.isEmpty()) {
            String unescaped = html2.replace("\\u0022", "\"").replace("\\\"", "\"");
            Pattern p2 = Pattern.compile("\"([A-Za-z0-9_-]{25,44})\"[^\\[]{0,140}?\"([^\"]+\\.mp3)\"");
            Matcher m2 = p2.matcher(unescaped);
            while (m2.find()) map.put(m2.group(2).trim().replace("&amp;", "&").replace("&#39;", "'"), m2.group(1).trim());
            Pattern p3 = Pattern.compile("\"([A-Za-z0-9_-]{25,44})\"[^\\[]{0,140}?\"(\\d{2}-\\d{2}-\\d{2}|Scan)\"");
            Matcher m3 = p3.matcher(unescaped);
            while (m3.find()) map.put(m3.group(2).trim(), m3.group(1).trim());
        }
        return map;
    }

    private static class RecItem implements Comparable<RecItem> {
        String fileId, fileName, dateStr, timeStr, freq, dur, name, sortKey;
        @Override
        public int compareTo(RecItem o) { return o.sortKey.compareTo(this.sortKey); }
    }

    private RecItem parseMp3Filename(String dateFolder, String fileName, String fileId) {
        String base = fileName.endsWith(".mp3") ? fileName.substring(0, fileName.length() - 4) : fileName;
        Pattern pat = Pattern.compile("^(.*?)\\s+-\\s+(\\d+\\.\\d+)\\s+-\\s+(\\d{2})-(\\d{2})-(\\d{2})(?:.*?TO\\s+(\\d{2})-(\\d{2})-(\\d{2}))?");
        Matcher m = pat.matcher(base);
        RecItem item = new RecItem();
        item.fileId = fileId;
        item.fileName = fileName;
        item.dateStr = dateFolder;
        if (m.find()) {
            item.name = m.group(1).trim();
            item.freq = m.group(2).trim();
            String h1 = m.group(3), m1 = m.group(4), s1 = m.group(5);
            item.timeStr = h1 + ":" + m1 + ":" + s1;
            item.dur = "00:03";
            if (m.group(6) != null) {
                try {
                    int t1 = Integer.parseInt(h1) * 3600 + Integer.parseInt(m1) * 60 + Integer.parseInt(s1);
                    int t2 = Integer.parseInt(m.group(6)) * 3600 + Integer.parseInt(m.group(7)) * 60 + Integer.parseInt(m.group(8));
                    int diff = t2 - t1;
                    if (diff < 0) diff += 86400;
                    item.dur = String.format(Locale.US, "%02d:%02d", diff / 60, diff % 60);
                } catch (Exception ignored) {}
            }
        } else {
            String[] parts = base.split(" - ");
            item.name = parts.length > 0 ? parts[0].trim() : base;
            item.freq = parts.length > 1 ? parts[1].trim() : "---.----";
            item.timeStr = parts.length > 2 ? parts[2].replace('-', ':').trim() : "00:00:00";
            item.dur = "--:--";
        }
        String[] dp = dateFolder.split("-");
        String isoDate = dp.length == 3 ? (dp[2] + "-" + dp[0] + "-" + dp[1]) : dateFolder;
        item.sortKey = isoDate + " " + item.timeStr + " " + fileName;
        return item;
    }

    private void loadLiveGoogleDriveRecordings() {
        if (isFetchingDrive) return;
        isFetchingDrive = true;
        executor.execute(() -> {
            try {
                Map<String, String> rootItems = parseDriveFolderAllItems(GDRIVE_ROOT_FOLDER_ID);
                List<String> dateFolders = new ArrayList<>();
                for (String title : rootItems.keySet()) {
                    if (title.matches("\\d{2}-\\d{2}-\\d{2}")) dateFolders.add(title);
                }
                Collections.sort(dateFolders, (a, b) -> {
                    String[] pa = a.split("-"), pb = b.split("-");
                    String ka = pa.length == 3 ? (pa[2] + pa[0] + pa[1]) : a;
                    String kb = pb.length == 3 ? (pb[2] + pb[0] + pb[1]) : b;
                    return kb.compareTo(ka);
                });
                List<RecItem> allItems = new ArrayList<>();
                int maxDates = Math.min(dateFolders.size(), 3);
                for (int i = 0; i < maxDates; i++) {
                    String dFolder = dateFolders.get(i);
                    String dId = rootItems.get(dFolder);
                    if (dId == null) continue;
                    Map<String, String> dateChildren = parseDriveFolderAllItems(dId);
                    for (Map.Entry<String, String> e : dateChildren.entrySet()) {
                        if (e.getKey().toLowerCase().endsWith(".mp3")) {
                            allItems.add(parseMp3Filename(dFolder, e.getKey(), e.getValue()));
                        } else {
                            Map<String, String> subChildren = parseDriveFolderAllItems(e.getValue());
                            for (Map.Entry<String, String> se : subChildren.entrySet()) {
                                if (se.getKey().toLowerCase().endsWith(".mp3")) {
                                    allItems.add(parseMp3Filename(dFolder, se.getKey(), se.getValue()));
                                }
                            }
                        }
                    }
                }
                Collections.sort(allItems);
                JSONArray arr = new JSONArray();
                for (RecItem it : allItems) {
                    JSONObject o = new JSONObject();
                    o.put("fileId", it.fileId);
                    o.put("fileName", it.fileName);
                    o.put("dateStr", it.dateStr);
                    o.put("timeStr", it.timeStr);
                    o.put("freq", it.freq);
                    o.put("dur", it.dur);
                    o.put("name", it.name);
                    arr.put(o);
                }
                final String escapedJson = JSONObject.quote(arr.toString());
                mainHandler.post(() -> {
                    isFetchingDrive = false;
                    if (playerWeb != null) {
                        playerWeb.evaluateJavascript("if(window.onDriveRecordingsLoaded)window.onDriveRecordingsLoaded(" + escapedJson + ");", null);
                    }
                });
            } catch (Exception ignored) {
                isFetchingDrive = false;
            }
        });
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
    protected void onPause() {
        super.onPause();
        if (playerWeb != null) {
            playerWeb.evaluateJavascript("if(window.markCurrentListAsVisited)window.markCurrentListAsVisited();", null);
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(autoSyncRunnable);
        stopNativePlayer();
        try {
            if (tts != null) { tts.stop(); tts.shutdown(); }
            if (playerWeb != null) { playerWeb.loadUrl("about:blank"); playerWeb.destroy(); }
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}