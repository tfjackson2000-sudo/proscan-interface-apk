package com.proscan.interfaceapp;

import android.app.Activity;
import android.content.Intent;
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
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextToSpeech tts = null;
    private boolean ttsReady = false;
    private MediaPlayer nativePlayer = null;

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            try {
                if (nativePlayer != null && nativePlayer.isPlaying() && playerWeb != null) {
                    int sec = nativePlayer.getCurrentPosition() / 1000;
                    playerWeb.evaluateJavascript("if(window.onNativeTimeUpdate)window.onNativeTimeUpdate(" + sec + ");", null);
                    mainHandler.postDelayed(this, 400);
                }
            } catch (Exception ignored) {}
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
                injectNativePlayerHooks();
                loadLiveGoogleDriveRecordings();
            }
        });

        setContentView(playerWeb);
        playerWeb.loadUrl("file:///android_asset/player_ui.html");
    }

    private void injectNativePlayerHooks() {
        StringBuilder js = new StringBuilder();
        js.append("(function(){\n");
        js.append("  var st=document.createElement('style');\n");
        js.append("  st.textContent='.rec-row{grid-template-columns:52px 56px 64px 36px 1fr!important;}';\n");
        js.append("  document.head.appendChild(st);\n");

        /* Set CONT: OFF by default! */
        js.append("  isCont = false;\n");
        js.append("  var cBtn = document.getElementById('btn-mode-cont');\n");
        js.append("  if (cBtn) { cBtn.classList.remove('active'); cBtn.innerText = 'CONT: OFF'; }\n");

        /* Receive live recordings array directly from Google Drive crawler */
        js.append("  window.onDriveRecordingsLoaded=function(itemsJson){\n");
        js.append("    try {\n");
        js.append("      var arr = JSON.parse(itemsJson);\n");
        js.append("      allRecs = arr;\n");
        js.append("      var datesSet = {};\n");
        js.append("      arr.forEach(function(r){ if(r.dateStr) datesSet[r.dateStr]=true; });\n");
        js.append("      var dSel = document.getElementById('date-select');\n");
        js.append("      var curVal = dSel.value;\n");
        js.append("      var dHtml = '<option value=\"\">ALL DATES</option>';\n");
        js.append("      Object.keys(datesSet).forEach(function(d){ dHtml += '<option value=\"'+d+'\">'+d+'</option>'; });\n");
        js.append("      dSel.innerHTML = dHtml; dSel.value = curVal;\n");
        js.append("      renderPlaylist();\n");
        js.append("    } catch(e) {}\n");
        js.append("  };\n");

        js.append("  window.onNativeTimeUpdate=function(sec){\n");
        js.append("    var el=document.getElementById('time-counter');\n");
        js.append("    if(el)el.innerText=String(Math.floor(sec/60)).padStart(2,'0')+':'+String(sec%60).padStart(2,'0');\n");
        js.append("  };\n");
        js.append("  window.onNativePlayStarted=function(){\n");
        js.append("    if(curIdx>=0&&curIdx<filteredRecs.length){\n");
        js.append("      var r=filteredRecs[curIdx];\n");
        js.append("      var b=document.getElementById('banner-name');\n");
        js.append("      if(b)b.innerText='PLAYING: '+r.name+' ('+r.timeStr+')';\n");
        js.append("      var f=document.getElementById('banner-freq');\n");
        js.append("      if(f)f.innerText=r.freq;\n");
        js.append("    }\n");
        js.append("    if(typeof setPlayingUI==='function')setPlayingUI(true);\n");
        js.append("    if(typeof renderPlaylist==='function')renderPlaylist();\n");
        js.append("  };\n");
        js.append("  window.onNativePlayEnded=function(){\n");
        js.append("    if(typeof setPlayingUI==='function')setPlayingUI(false);\n");
        js.append("    if(typeof isLoop!=='undefined'&&isLoop&&curIdx>=0)playIndex(curIdx);\n");
        js.append("    else if(typeof isCont!=='undefined'&&isCont&&curIdx+1<filteredRecs.length)playIndex(curIdx+1);\n");
        js.append("  };\n");
        js.append("  window.onNativePlayError=function(msg){\n");
        js.append("    if(typeof setPlayingUI==='function')setPlayingUI(false);\n");
        js.append("    var b=document.getElementById('banner-name');if(b)b.innerText=msg||'STREAM ERROR';\n");
        js.append("  };\n");
        js.append("  window.startAudioResolution=function(r){\n");
        js.append("    var b=document.getElementById('banner-name');if(b)b.innerText='LOADING: '+r.name+' ('+r.timeStr+')';\n");
        js.append("    var f=document.getElementById('banner-freq');if(f)f.innerText=r.freq;\n");
        js.append("    if(r.fileId&&window.PlayerBridge&&window.PlayerBridge.playDriveIdDirect){\n");
        js.append("      window.PlayerBridge.playDriveIdDirect(r.fileId);\n");
        js.append("    }\n");
        js.append("  };\n");
        js.append("  var stopB=document.getElementById('btn-stop');\n");
        js.append("  if(stopB)stopB.addEventListener('click',function(){if(window.PlayerBridge&&window.PlayerBridge.stopAudio)window.PlayerBridge.stopAudio();});\n");
        js.append("  var playB=document.getElementById('btn-play');\n");
        js.append("  if(playB)playB.addEventListener('click',function(){if(curIdx!==-1&&window.PlayerBridge&&window.PlayerBridge.togglePause)window.PlayerBridge.togglePause();});\n");
        js.append("})();");

        if (playerWeb != null) playerWeb.evaluateJavascript(js.toString(), null);
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

    private void playDriveFileIdNatively(String fileId) {
        stopNativePlayer();
        String url = "https://drive.usercontent.google.com/download?id=" + fileId + "&export=download";
        try {
            nativePlayer = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= 21) {
                nativePlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            }
            nativePlayer.setDataSource(url);
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
            nativePlayer.setOnErrorListener((mp, what, extra) -> {
                stopNativePlayer();
                if (playerWeb != null) {
                    playerWeb.evaluateJavascript("if(window.onNativePlayError)window.onNativePlayError('STREAM ERROR');", null);
                }
                return true;
            });
            nativePlayer.prepareAsync();
        } catch (Exception e) {
            if (playerWeb != null) {
                playerWeb.evaluateJavascript("if(window.onNativePlayError)window.onNativePlayError('PLAY ERROR');", null);
            }
        }
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
                            if (playerWeb != null) playerWeb.evaluateJavascript("if(typeof setPlayingUI==='function')setPlayingUI(false);", null);
                        } else {
                            nativePlayer.start();
                            if (playerWeb != null) playerWeb.evaluateJavascript("if(typeof setPlayingUI==='function')setPlayingUI(true);", null);
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
        /* Format: Channel Name - 455.7500 - 12-42-31 - CC 3 TO 12-42-35.mp3 */
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

        /* Sort key YYYY-MM-DD HH:MM:SS for newest-first sorting */
        String[] dp = dateFolder.split("-");
        String isoDate = dp.length == 3 ? (dp[2] + "-" + dp[0] + "-" + dp[1]) : dateFolder;
        item.sortKey = isoDate + " " + item.timeStr + " " + fileName;
        return item;
    }

    private void loadLiveGoogleDriveRecordings() {
        executor.execute(() -> {
            try {
                Map<String, String> rootItems = parseDriveFolderAllItems(GDRIVE_ROOT_FOLDER_ID);
                List<String> dateFolders = new ArrayList<>();
                for (String title : rootItems.keySet()) {
                    if (title.matches("\\d{2}-\\d{2}-\\d{2}")) {
                        dateFolders.add(title);
                    }
                }
                /* Sort date folders newest first */
                Collections.sort(dateFolders, (a, b) -> {
                    String[] pa = a.split("-"), pb = b.split("-");
                    String ka = pa.length == 3 ? (pa[2] + pa[0] + pa[1]) : a;
                    String kb = pb.length == 3 ? (pb[2] + pb[0] + pb[1]) : b;
                    return kb.compareTo(ka);
                });

                List<RecItem> allItems = new ArrayList<>();
                /* Crawl the 3 most recent date folders on Google Drive */
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
                            /* Subfolder like Scan */
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
                    if (playerWeb != null) {
                        playerWeb.evaluateJavascript("if(window.onDriveRecordingsLoaded)window.onDriveRecordingsLoaded(" + escapedJson + ");", null);
                    }
                });
            } catch (Exception ignored) {}
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