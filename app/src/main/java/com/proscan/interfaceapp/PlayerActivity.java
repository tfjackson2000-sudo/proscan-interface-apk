package com.proscan.interfaceapp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
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

    private final Map<String, String> dateFolderIdCache = new HashMap<>();
    private final Map<String, String> scanFolderIdCache = new HashMap<>();
    private final Map<String, String> mp3FileIdCache = new HashMap<>();

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
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(playerWeb, true);

        playerWeb.addJavascriptInterface(new PlayerBridge(), "PlayerBridge");
        playerWeb.setWebChromeClient(new WebChromeClient());
        playerWeb.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                fetchRecordingDataFromServer();
            }
        });

        setContentView(playerWeb);
        playerWeb.loadUrl("file:///android_asset/player_ui.html");
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
        public void resolveAndPlayOnDrive(final String dateFolder, final String subFolder, final String fileName) {
            executor.execute(() -> {
                String fileId = findGoogleDriveFileId(dateFolder, subFolder, fileName);
                mainHandler.post(() -> {
                    if (playerWeb == null) return;
                    if (fileId != null && !fileId.isEmpty()) {
                        String streamUrl = "https://drive.google.com/uc?export=download&id=" + fileId;
                        String safeUrl = JSONObject.quote(streamUrl);
                        String safeId = JSONObject.quote(fileId);
                        playerWeb.evaluateJavascript("if(window.onDriveStreamResolved)window.onDriveStreamResolved(" + safeUrl + "," + safeId + ");", null);
                    } else {
                        playerWeb.evaluateJavascript("if(window.onDriveStreamResolved)window.onDriveStreamResolved('','');", null);
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
                } catch (Exception e) {
                    Toast.makeText(PlayerActivity.this, "Could not open Google Drive", Toast.LENGTH_SHORT).show();
                }
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
            mainHandler.post(() -> finish());
        }
    }

    private String fetchUrlText(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
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

    private Map<String, String> parseDriveEmbeddedFolderView(String folderId) {
        Map<String, String> map = new HashMap<>();
        if (folderId == null || folderId.isEmpty()) return map;
        String html = fetchUrlText("https://drive.google.com/embeddedfolderview?id=" + folderId + "#list");
        if (html.isEmpty()) return map;

        Pattern entryPat = Pattern.compile("id=\"entry-([A-Za-z0-9_-]{15,})\"[\\s\\S]*?<div class=\"flip-entry-title\">([^<]+)</div>");
        Matcher m = entryPat.matcher(html);
        while (m.find()) {
            String id = m.group(1).trim();
            String title = m.group(2).trim()
                    .replace("&amp;", "&")
                    .replace("&#39;", "'")
                    .replace("&quot;", "\"");
            map.put(title, id);
        }
        return map;
    }

    private String findGoogleDriveFileId(String dateFolder, String subFolder, String fileName) {
        if (mp3FileIdCache.containsKey(fileName)) {
            return mp3FileIdCache.get(fileName);
        }

        if (dateFolderIdCache.isEmpty()) {
            dateFolderIdCache.putAll(parseDriveEmbeddedFolderView(GDRIVE_ROOT_FOLDER_ID));
            for (Map.Entry<String, String> e : dateFolderIdCache.entrySet()) {
                if (e.getKey().endsWith(".mp3")) {
                    mp3FileIdCache.put(e.getKey(), e.getValue());
                }
            }
            if (mp3FileIdCache.containsKey(fileName)) return mp3FileIdCache.get(fileName);
        }

        String dId = dateFolderIdCache.get(dateFolder);
        if (dId == null) {
            for (Map.Entry<String, String> e : dateFolderIdCache.entrySet()) {
                if (e.getKey().equalsIgnoreCase(dateFolder)) {
                    dId = e.getValue();
                    break;
                }
            }
        }
        if (dId == null) return "";

        String scanCacheKey = dateFolder + "/" + subFolder;
        String targetFolderId = scanFolderIdCache.get(scanCacheKey);
        if (targetFolderId == null) {
            Map<String, String> childrenOfDate = parseDriveEmbeddedFolderView(dId);
            for (Map.Entry<String, String> e : childrenOfDate.entrySet()) {
                if (e.getKey().endsWith(".mp3")) {
                    mp3FileIdCache.put(e.getKey(), e.getValue());
                } else if (e.getKey().equalsIgnoreCase(subFolder)) {
                    targetFolderId = e.getValue();
                    scanFolderIdCache.put(scanCacheKey, targetFolderId);
                }
            }
            if (mp3FileIdCache.containsKey(fileName)) return mp3FileIdCache.get(fileName);
        }

        if (targetFolderId != null) {
            Map<String, String> mp3sInSub = parseDriveEmbeddedFolderView(targetFolderId);
            mp3FileIdCache.putAll(mp3sInSub);
        }

        if (mp3FileIdCache.containsKey(fileName)) {
            return mp3FileIdCache.get(fileName);
        }
        for (Map.Entry<String, String> e : mp3FileIdCache.entrySet()) {
            if (e.getKey().equalsIgnoreCase(fileName)) {
                return e.getValue();
            }
        }
        return "";
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
