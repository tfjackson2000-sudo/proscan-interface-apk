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

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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

    private final Map<String, String> dateFolderIdCache = new HashMap<>();
    private final Map<String, String> scanFolderIdCache = new HashMap<>();
    private final Map<String, String> mp3FileIdCache = new HashMap<>();

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
                fetchRecordingDataFromServer();
            }
        });

        setContentView(playerWeb);
        playerWeb.loadUrl("file:///android_asset/player_ui.html");
    }

    private void injectNativePlayerHooks() {
        String js = "(function(){" +
                "window.onNativeTimeUpdate=function(sec){" +
                "  var el=document.getElementById('time-counter');" +
                "  if(el)el.innerText=String(Math.floor(sec/60)).padStart(2,'0')+':'+String(sec%60).padStart(2,'0');" +
                "};" +
                "window.onNativePlayStarted=function(){" +
                "  if(typeof setPlayingUI==='function')setPlayingUI(true);" +
                "  if(typeof renderPlaylist==='function')renderPlaylist();" +
                "};" +
                "window.onNativePlayEnded=function(){" +
                "  if(typeof setPlayingUI==='function')setPlayingUI(false);" +
                "  if(typeof isLoop!=='undefined'&&isLoop&&curIdx>=0)playIndex(curIdx);" +
                "  else if(typeof isCont!=='undefined'&&isCont&&curIdx+1<filteredRecs.length)playIndex(curIdx+1);" +
                "};" +
                "window.onNativePlayError=function(msg){" +
                "  if(typeof setPlayingUI==='function')setPlayingUI(false);" +
                "  var b=document.getElementById('banner-name');if(b)b.innerText=msg||'NOT IN G-DRIVE YET';" +
                "};" +
                "window.startAudioPlayback=function(r){" +
                "  var bs=String.fromCharCode(92),f=(r.folderPath||'').split(bs).join('/'),ps=f.split('/').filter(Boolean);" +
                "  var sub=ps.length>=1?ps[ps.length-1]:'Scan',dt=ps.length>=2?ps[ps.length-2]:r.dateStr;" +
                "  var b=document.getElementById('banner-name');if(b)b.innerText='LOADING G-DRIVE: '+r.name;" +
                "  if(window.PlayerBridge&&window.PlayerBridge.resolveAndPlayOnDrive){" +
                "    window.PlayerBridge.resolveAndPlayOnDrive(dt,sub,r.fileName);" +
                "  }" +
                "};" +
                "var stopB=document.getElementById('btn-stop');" +
                "if(stopB)stopB.addEventListener('click',function(){if(window.PlayerBridge&&window.PlayerBridge.stopAudio)window.PlayerBridge.stopAudio();});" +
                "var playB=document.getElementById('btn-play');" +
                "if(playB)playB.addEventListener('click',function(){if(curIdx!==-1&&window.PlayerBridge&&window.PlayerBridge.togglePause)window.PlayerBridge.togglePause();});" +
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
            fetchRecordingDataFromServer();
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
        public void resolveAndPlayOnDrive(final String dateFolder, final String subFolder, final String fileName) {
            executor.execute(() -> {
                String fileId = findGoogleDriveFileId(dateFolder, subFolder, fileName);
                mainHandler.post(() -> {
                    if (fileId != null && !fileId.isEmpty()) {
                        playDriveFileIdNatively(fileId);
                    } else if (playerWeb != null) {
                        playerWeb.evaluateJavascript("if(window.onNativePlayError)window.onNativePlayError('NOT FOUND ON G-DRIVE');", null);
                    }
                });
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
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(9000);
            conn.setReadTimeout(9000);
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
        Map<String, String> map = new HashMap<>();
        if (folderId == null || folderId.isEmpty()) return map;

        /* 1. Parse embeddedfolderview */
        String html1 = fetchUrlText("https://drive.google.com/embeddedfolderview?id=" + folderId + "#list");
        if (!html1.isEmpty()) {
            Pattern p1 = Pattern.compile("id=\"entry-([A-Za-z0-9_-]{15,})\"[\\s\\S]*?<div class=\"flip-entry-title\">([^<]+)</div>");
            Matcher m1 = p1.matcher(html1);
            while (m1.find()) {
                String id = m1.group(1).trim();
                String title = m1.group(2).trim().replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"");
                map.put(title, id);
            }
        }

        /* 2. Also parse full drive/folders page (contains JSON array of more files beyond the 50 limit) */
        String html2 = fetchUrlText("https://drive.google.com/drive/folders/" + folderId);
        if (!html2.isEmpty()) {
            String unescaped = html2.replace("\\u0022", "\"").replace("\\\"", "\"");
            Pattern p2 = Pattern.compile("\"([A-Za-z0-9_-]{25,44})\"[^\\[]{0,120}?\"([^\"]+\\.mp3)\"");
            Matcher m2 = p2.matcher(unescaped);
            while (m2.find()) {
                map.put(m2.group(2).trim(), m2.group(1).trim());
            }
            Pattern p3 = Pattern.compile("\"([A-Za-z0-9_-]{25,44})\"[^\\[]{0,120}?\"(\\d{2}-\\d{2}-\\d{2}|Scan)\"");
            Matcher m3 = p3.matcher(unescaped);
            while (m3.find()) {
                map.put(m3.group(2).trim(), m3.group(1).trim());
            }
        }
        return map;
    }

    private String matchNameInMap(Map<String, String> map, String targetName) {
        if (map.containsKey(targetName)) return map.get(targetName);
        String prefix = targetName.length() > 22 ? targetName.substring(0, 22).toLowerCase() : targetName.toLowerCase();
        for (Map.Entry<String, String> e : map.entrySet()) {
            String k = e.getKey();
            if (k.equalsIgnoreCase(targetName) || k.toLowerCase().startsWith(prefix)) {
                return e.getValue();
            }
        }
        return null;
    }

    private String findGoogleDriveFileId(String dateFolder, String subFolder, String fileName) {
        String cached = matchNameInMap(mp3FileIdCache, fileName);
        if (cached != null) return cached;

        if (dateFolderIdCache.isEmpty()) {
            dateFolderIdCache.putAll(parseDriveFolderAllItems(GDRIVE_ROOT_FOLDER_ID));
        }

        String dId = matchNameInMap(dateFolderIdCache, dateFolder);
        if (dId == null) return "";

        String scanCacheKey = dateFolder + "/" + subFolder;
        String targetFolderId = scanFolderIdCache.get(scanCacheKey);
        if (targetFolderId == null) {
            Map<String, String> childrenOfDate = parseDriveFolderAllItems(dId);
            for (Map.Entry<String, String> e : childrenOfDate.entrySet()) {
                if (e.getKey().endsWith(".mp3")) {
                    mp3FileIdCache.put(e.getKey(), e.getValue());
                }
            }
            targetFolderId = matchNameInMap(childrenOfDate, subFolder);
            if (targetFolderId != null) {
                scanFolderIdCache.put(scanCacheKey, targetFolderId);
            }
        }

        if (targetFolderId != null) {
            Map<String, String> mp3sInSub = parseDriveFolderAllItems(targetFolderId);
            mp3FileIdCache.putAll(mp3sInSub);
        }

        String found = matchNameInMap(mp3FileIdCache, fileName);
        return found != null ? found : "";
    }

    private void fetchRecordingDataFromServer() {
        if (serverBaseUrl.isEmpty()) return;
        executor.execute(() -> {
            try {
                URL url = new URL(serverBaseUrl + "/Recording%20Data.txt");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setUseCaches(false);
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null && count < 350) {
                    sb.append(line).append("\n");
                    count++;
                }
                reader.close();
                conn.disconnect();
                final String escaped = JSONObject.quote(sb.toString());
                mainHandler.post(() -> {
                    if (playerWeb != null) {
                        playerWeb.evaluateJavascript("if(window.onRecordingDataLoaded)window.onRecordingDataLoaded(" + escaped + ");", null);
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