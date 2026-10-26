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

    /* 50ms (20fps) real-time telemetry ticker for Progress (0..1) + Real Audio Envelope Amplitude (0..1) */
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

        String js = "(function(){" +
                "try {" +
                "  var raw = " + safeBackup + ";" +
                "  if (raw) {" +
                "    var b = JSON.parse(raw);" +
                "    if (b.web_storage) {" +
                "      Object.keys(b.web_storage).forEach(function(k){ localStorage.setItem(k, b.web_storage[k]); });" +
                "      var sIdx = parseInt(localStorage.getItem('proscan_skin_index')||'0',10)||0;" +
                "      var dIdx = parseInt(localStorage.getItem('proscan_display_index')||'0',10)||0;" +
                "      var bIdx = parseInt(localStorage.getItem('proscan_bg_index')||'0',10)||0;" +
                "      if (typeof skinIds !== 'undefined') document.documentElement.setAttribute('data-skin', skinIds[sIdx % skinIds.length]);" +
                "      if (typeof dispIds !== 'undefined') document.documentElement.setAttribute('data-display', dispIds[dIdx % dispIds.length]);" +
                "      if (typeof bgIds !== 'undefined') document.documentElement.setAttribute('data-bg', bgIds[bIdx % bgIds.length]);" +
                "      var br = (localStorage.getItem('proscan_custom_brand_v1')||'PROSCAN').trim();" +
                "      var bs = document.getElementById('brand-logo-slot');" +
                "      if (bs) bs.innerHTML = '<div class=\"brand-henus\"><span class=\"henus-mark\">'+br+'</span><span class=\"henus-sub\">ARCHIVE</span></div>';" +
                "    }" +
                "  }" +
                "} catch(e) {}" +

                "var st = document.createElement('style');" +
                "st.textContent = '" +
                "  .scanner-display { height: 245px !important; }" +
                "  .top-controls-container { height: 26px !important; }" +
                "  .buttons-box { gap: 10px !important; }" +
                "  #mech-bay { height: 232px !important; }" +
                "  .rec-row.selected { outline: 1px dashed var(--log-text, #38bdf8) !important; background: rgba(255,255,255,0.14) !important; font-weight: 800 !important; }" +
                /* Fix Vinyl Tonearm geometry so needle sits right over the record grooves and sweeps inward */
                "  .tt-platter { left: -24px !important; width: 162px !important; height: 162px !important; }" +
                "  .tt-record { width: 150px !important; height: 150px !important; }" +
                "  .tt-arm-base { top: 18px !important; right: 32px !important; }" +
                "  .tt-tonearm { top: 34px !important; right: 46px !important; height: 124px !important; transform-origin: top center !important; transform: rotate(4deg); transition: transform 0.25s linear !important; }" +
                /* Add Reactive LED VU Meter Strip inside Cassette, CD, and Reel decks */
                "  #reactive-vu-strip { position: absolute; bottom: 6px; left: 50%; transform: translateX(-50%); display: flex; gap: 3px; z-index: 12; background: rgba(0,0,0,0.65); padding: 3px 7px; border-radius: 4px; border: 1px solid rgba(255,255,255,0.14); }" +
                "  .rvu-seg { width: 6px; height: 5px; border-radius: 1px; background: #1e293b; transition: background 0.05s ease, box-shadow 0.05s ease; }" +
                "';" +
                "document.head.appendChild(st);" +

                /* Add 14-segment reactive LED VU meter bar to the bottom of the mechanical bay */
                "var mb = document.getElementById('mech-bay');" +
                "if (mb && !document.getElementById('reactive-vu-strip')) {" +
                "  var vu = document.createElement('div');" +
                "  vu.id = 'reactive-vu-strip';" +
                "  var segs = ''; for (var s=0; s<14; s++) segs += '<div class=\"rvu-seg\"></div>';" +
                "  vu.innerHTML = segs;" +
                "  mb.appendChild(vu);" +
                "}" +

                "window.__audioLevel = 0;" +
                "window.__audioProgress = 0;" +

                /* Real-time telemetry callback driving all 5 decks! */
                "window.onNativeTelemetry = function(sec, totSec, prog, level) {" +
                "  window.__audioProgress = prog || 0;" +
                "  window.__audioLevel = level || 0;" +
                "  if (typeof window.onNativeTimeUpdate === 'function') window.onNativeTimeUpdate(sec, totSec);" +

                /* 1. Vinyl Tonearm: 26deg (outer groove at 0%) -> 45deg (inner groove at 100%) */
                "  var arm = document.querySelector('.tt-tonearm');" +
                "  if (arm) {" +
                "    var angle = 26 + (window.__audioProgress * 19);" +
                "    arm.style.transform = 'rotate(' + angle.toFixed(2) + 'deg)';" +
                "  }" +
                /* Pulse Vinyl strobe with audio level */
                "  var strb = document.querySelector('.tt-strobe');" +
                "  if (strb) strb.style.opacity = (0.55 + window.__audioLevel * 0.45).toFixed(2);" +

                /* 2. Cassette Tape Spools: Left spool shrinks (64px -> 44px), Right spool grows (44px -> 64px) */
                "  var spL = document.querySelector('.c-spool.left');" +
                "  var spR = document.querySelector('.c-spool.right');" +
                "  if (spL && spR) {" +
                "    var szL = 64 - (window.__audioProgress * 20);" +
                "    var szR = 44 + (window.__audioProgress * 20);" +
                "    spL.style.width = szL + 'px'; spL.style.height = szL + 'px';" +
                "    spR.style.width = szR + 'px'; spR.style.height = szR + 'px';" +
                "  }" +

                /* 3. CD Laser Pickup Glint: Pulses & tracks with audio */
                "  var cdDisc = document.querySelector('.cd-disc');" +
                "  if (cdDisc) {" +
                "    var glow = Math.round(6 + window.__audioLevel * 18);" +
                "    cdDisc.style.boxShadow = '0 0 ' + glow + 'px rgba(56,189,248,' + (0.25 + window.__audioLevel * 0.65).toFixed(2) + ')';" +
                "  }" +

                /* 4. 14-Segment LED VU Meter Bar (Active across Tape, CD, Vinyl, and Reel!) */
                "  var activeSegs = Math.round(window.__audioLevel * 14);" +
                "  document.querySelectorAll('#reactive-vu-strip .rvu-seg').forEach(function(seg, idx) {" +
                "    if (idx < activeSegs) {" +
                "      var col = idx >= 11 ? '#ef4444' : (idx >= 8 ? '#facc15' : '#22c55e');" +
                "      seg.style.background = col;" +
                "      seg.style.boxShadow = '0 0 5px ' + col;" +
                "    } else {" +
                "      seg.style.background = '#1e293b';" +
                "      seg.style.boxShadow = 'none';" +
                "    }" +
                "  });" +
                "};" +

                /* Reset Tonearm & VU meter when stopped/ended */
                "var origSetPlaying = window.setPlayingUI;" +
                "window.setPlayingUI = function(playing) {" +
                "  if (origSetPlaying) origSetPlaying(playing);" +
                "  if (!playing) {" +
                "    window.__audioLevel = 0;" +
                "    var arm = document.querySelector('.tt-tonearm');" +
                "    if (arm) arm.style.transform = 'rotate(4deg)';" +
                "    document.querySelectorAll('#reactive-vu-strip .rvu-seg').forEach(function(seg) {" +
                "      seg.style.background = '#1e293b'; seg.style.boxShadow = 'none';" +
                "    });" +
                "  }" +
                "};" +

                /* Override Hacker Oscilloscope to use REAL window.__audioLevel from the MP3! */
                "var hCanvas = document.getElementById('hack-canvas');" +
                "if (hCanvas) {" +
                "  var hCtx = hCanvas.getContext('2d');" +
                "  var hPhase = 0;" +
                "  function drawRealAudioScope() {" +
                "    requestAnimationFrame(drawRealAudioScope);" +
                "    if (document.documentElement.getAttribute('data-deck') !== 'hack') return;" +
                "    hCtx.clearRect(0, 0, hCanvas.width, hCanvas.height);" +
                "    var lvl = isPlayingNow ? window.__audioLevel : 0;" +
                "    hCtx.strokeStyle = '#39ff14';" +
                "    hCtx.lineWidth = 2.2;" +
                "    hCtx.beginPath();" +
                "    for (var x = 0; x < hCanvas.width; x += 3) {" +
                "      var env = Math.sin((x / hCanvas.width) * Math.PI);" +
                "      var wave = Math.sin(x * 0.08 + hPhase) * Math.cos(x * 0.03 - hPhase * 0.7);" +
                "      var y = 50 + (wave * env * (lvl * 44));" +
                "      if (x === 0) hCtx.moveTo(x, y); else hCtx.lineTo(x, y);" +
                "    }" +
                "    hCtx.stroke();" +
                "    hCtx.fillStyle = 'rgba(57,255,20,0.8)';" +
                "    for (var b = 0; b < 24; b++) {" +
                "      var bandFactor = Math.abs(Math.sin(b * 0.55 + hPhase * 0.4));" +
                "      var bh = lvl > 0.02 ? Math.max(3, Math.floor(lvl * bandFactor * 48)) : 2;" +
                "      hCtx.fillRect(10 + b * 15, 134 - bh, 11, bh);" +
                "    }" +
                "    hPhase += 0.28 + (lvl * 0.35);" +
                "  }" +
                "  drawRealAudioScope();" +
                "}" +

                /* Tap Row = Highlight/Select First; Press PLAY = Play Selected Row */
                "window.selectIndexOnly = function(idx) {" +
                "  if (idx < 0 || idx >= filteredRecs.length) return;" +
                "  curIdx = idx;" +
                "  var r = filteredRecs[idx];" +
                "  var ro = document.getElementById('track-main-readout') || document.getElementById('banner-name');" +
                "  if (ro) ro.innerText = 'READY: ' + r.name + ' • ' + r.freq;" +
                "  var cl = document.getElementById('cassette-lbl-left'); if (cl) cl.innerText = r.name;" +
                "  var cr = document.getElementById('cassette-lbl-right'); if (cr) cr.innerText = r.freq + ' (' + r.timeStr + ')';" +
                "  var tc = document.getElementById('time-cur') || document.getElementById('time-counter'); if (tc) tc.innerText = '00:00';" +
                "  var tt = document.getElementById('time-tot'); if (tt) tt.innerText = r.dur || '00:03';" +
                "  document.querySelectorAll('.rec-row').forEach(function(el, i){" +
                "    el.classList.toggle('selected', i === idx);" +
                "  });" +
                "};" +
                "var origRender = window.renderPlaylist;" +
                "window.renderPlaylist = function() {" +
                "  origRender();" +
                "  var box = document.getElementById('playlist-box');" +
                "  box.querySelectorAll('.rec-row').forEach(function(el, i) {" +
                "    if (i === curIdx) el.classList.add('selected');" +
                "    var clone = el.cloneNode(true);" +
                "    el.parentNode.replaceChild(clone, el);" +
                "    var rTimer = null, rLong = false, startY = 0;" +
                "    clone.addEventListener('pointerdown', function(ev) {" +
                "      clearTimeout(rTimer); rLong = false; startY = ev.clientY || 0;" +
                "      var idx = parseInt(clone.getAttribute('data-idx'), 10);" +
                "      rTimer = setTimeout(function() {" +
                "        rTimer = null; rLong = true;" +
                "        if (navigator.vibrate) navigator.vibrate([35, 50, 35]);" +
                "        var target = filteredRecs[idx];" +
                "        if (target) {" +
                "          window.__deletedDriveIds[target.fileId || target.fileName] = true;" +
                "          localStorage.setItem('proscan_deleted_recs_v1', JSON.stringify(window.__deletedDriveIds));" +
                "          var ro = document.getElementById('track-main-readout') || document.getElementById('banner-name');" +
                "          if (ro) ro.innerText = 'DELETED: ' + target.name;" +
                "          window.renderPlaylist();" +
                "        }" +
                "      }, 550);" +
                "    });" +
                "    clone.addEventListener('pointermove', function(ev) {" +
                "      if (rTimer && Math.abs((ev.clientY || 0) - startY) > 10) { clearTimeout(rTimer); rTimer = null; }" +
                "    });" +
                "    clone.addEventListener('pointerup', function() {" +
                "      var wasTap = rTimer !== null; clearTimeout(rTimer); rTimer = null;" +
                "      if (wasTap && !rLong) {" +
                "        if (navigator.vibrate) navigator.vibrate(15);" +
                "        window.selectIndexOnly(parseInt(clone.getAttribute('data-idx'), 10));" +
                "      }" +
                "    });" +
                "    clone.addEventListener('pointercancel', function() { clearTimeout(rTimer); rTimer = null; });" +
                "  });" +
                "};" +
                "var oldPlayBtn = document.getElementById('btn-play');" +
                "if (oldPlayBtn) {" +
                "  var newPlayBtn = oldPlayBtn.cloneNode(true);" +
                "  oldPlayBtn.parentNode.replaceChild(newPlayBtn, oldPlayBtn);" +
                "  newPlayBtn.addEventListener('click', function() {" +
                "    if (curIdx === -1 && filteredRecs.length > 0) curIdx = 0;" +
                "    if (curIdx >= 0 && curIdx < filteredRecs.length) {" +
                "      var r = filteredRecs[curIdx];" +
                "      if (window.__activePlayingFileId === r.fileId && (typeof isPlayingNow !== 'undefined' ? isPlayingNow : window.__isNativePlaying)) {" +
                "        if (window.PlayerBridge) window.PlayerBridge.togglePause();" +
                "      } else {" +
                "        playIndex(curIdx);" +
                "      }" +
                "    }" +
                "  });" +
                "}" +
                "})();";

        if (playerWeb != null) playerWeb.evaluateJavascript(js, null);
    }

    private void stopNativePlayer() {
        mainHandler.removeCallbacks(progressTicker);
        if (nativePlayer != null) {
            try {
                if (nativePlayer.isPlaying()) nativePlayer.stop();
            } catch (Exception ignored) {}
            try {
                nativePlayer.reset();
                nativePlayer.release();
            } catch (Exception ignored) {}
            nativePlayer = null;
        }
    }

    /* Extract 250-bucket audio amplitude envelope directly from the downloaded MP3 file bytes */
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

            /* Skip ID3 header if present and measure frame bit-reservoir / byte variance per time slice */
            int startOffset = Math.min(read / 10, 1024);
            int sliceLen = Math.max(1, (read - startOffset) / buckets);
            float minVal = Float.MAX_VALUE;
            float maxVal = Float.MIN_VALUE;

            for (int b = 0; b < buckets; b++) {
                int s = startOffset + b * sliceLen;
                int end = Math.min(read - 1, s + sliceLen);
                long energy = 0;
                int zeroPad = 0;
                for (int i = s; i < end; i++) {
                    int v = data[i] & 0xFF;
                    if (v == 0x00 || v == 0xFF || v == 0xAA || v == 0x55) {
                        zeroPad++;
                    }
                    int diff = Math.abs((data[i] & 0xFF) - (data[i + 1] & 0xFF));
                    energy += diff;
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
                /* Expand dynamic range so quiet frames drop near 0 and voice peaks hit 0.95 */
                norm = (float) Math.pow(norm, 1.6);
                env[b] = Math.min(1f, Math.max(0.03f, norm));
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
                while ((n = is.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
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
                            if (playerWeb != null) {
                                playerWeb.evaluateJavascript("if(window.onNativePlayStarted)window.onNativePlayStarted();", null);
                            }
                            mainHandler.post(progressTicker);
                        });
                        nativePlayer.setOnCompletionListener(mp -> {
                            stopNativePlayer();
                            if (playerWeb != null) {
                                playerWeb.evaluateJavascript("if(window.onNativePlayEnded)window.onNativePlayEnded();", null);
                            }
                        });
                        nativePlayer.prepareAsync();
                    } catch (Exception e) {
                        if (playerWeb != null) {
                            playerWeb.evaluateJavascript("if(window.onNativePlayError)window.onNativePlayError('PLAY ERROR');", null);
                        }
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (playerWeb != null) {
                        playerWeb.evaluateJavascript("if(window.onNativePlayError)window.onNativePlayError('STREAM ERROR');", null);
                    }
                });
            }
        });
    }

    public class PlayerBridge {
        @JavascriptInterface
        public String getServerUrl() {
            return serverBaseUrl;
        }

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
                try {
                    if (nativePlayer != null) {
                        nativePlayer.seekTo(sec * 1000);
                    }
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void stopAudio() {
            mainHandler.post(() -> stopNativePlayer());
        }

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
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
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
                String id = m1.group(1).trim();
                String title = m1.group(2).trim().replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"");
                map.put(title, id);
            }
        }

        String html2 = fetchUrlText("https://drive.google.com/drive/folders/" + folderId);
        if (!html2.isEmpty()) {
            String unescaped = html2.replace("\\u0022", "\"").replace("\\\"", "\"");
            Pattern p2 = Pattern.compile("\"([A-Za-z0-9_-]{25,44})\"[^\\[]{0,140}?\"([^\"]+\\.mp3)\"");
            Matcher m2 = p2.matcher(unescaped);
            while (m2.find()) {
                String id = m2.group(1).trim();
                String title = m2.group(2).trim().replace("&amp;", "&").replace("&#39;", "'");
                map.put(title, id);
            }
            Pattern p3 = Pattern.compile("\"([A-Za-z0-9_-]{25,44})\"[^\\[]{0,140}?\"(\\d{2}-\\d{2}-\\d{2}|Scan)\"");
            Matcher m3 = p3.matcher(unescaped);
            while (m3.find()) {
                map.put(m3.group(2).trim(), m3.group(1).trim());
            }
        }
        return map;
    }

    private static class RecItem implements Comparable<RecItem> {
        String fileId;
        String fileName;
        String dateStr;
        String timeStr;
        String freq;
        String dur;
        String name;
        String sortKey;

        @Override
        public int compareTo(RecItem o) {
            return o.sortKey.compareTo(this.sortKey);
        }
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
                    if (title.matches("\\d{2}-\\d{2}-\\d{2}")) {
                        dateFolders.add(title);
                    }
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
                    o.put("folderPath", it.dateStr + "/Scan");
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
    protected void onDestroy() {
        mainHandler.removeCallbacks(autoSyncRunnable);
        stopNativePlayer();
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
            if (playerWeb != null) {
                playerWeb.loadUrl("about:blank");
                playerWeb.destroy();
            }
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}