package com.proscan.interfaceapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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

public class MainActivity extends Activity {

    private static final int REQ_LOAD_JSON = 901;
    private static final String GDRIVE_ROOT_FOLDER_ID = "1gToQyY6YPOk_3muiQSY5WLy7jTSvXbTv";

    private WebView webView;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService audioFetchExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean userWakeLockEnabled = false;
    private boolean bgAudioModeEnabled = false;
    private String activeServerUrl = "";
    private TextToSpeech tts = null;
    private boolean ttsReady = false;

    private MediaPlayer archivePlayer = null;
    private boolean isFetchingDrive = false;
    private float[] currentEnvelope = new float[0];

    private final Runnable periodicInjector = new Runnable() {
        @Override
        public void run() {
            if (webView != null) {
                injectEnhancements();
            }
            mainHandler.postDelayed(this, 1800);
        }
    };

    private final Runnable archiveProgressTicker = new Runnable() {
        @Override
        public void run() {
            try {
                if (archivePlayer != null && archivePlayer.isPlaying() && webView != null) {
                    int posMs = archivePlayer.getCurrentPosition();
                    int durMs = Math.max(1, archivePlayer.getDuration());
                    int sec = posMs / 1000;
                    int tot = Math.max(1, durMs / 1000);
                    float prog = Math.min(1f, Math.max(0f, (float) posMs / (float) durMs));
                    float level = 0.25f;
                    if (currentEnvelope.length > 0) {
                        int envIdx = Math.min(currentEnvelope.length - 1, (int) (prog * currentEnvelope.length));
                        level = currentEnvelope[envIdx];
                    }
                    webView.evaluateJavascript(
                            "if(window.onArchiveTelemetry)window.onArchiveTelemetry(" + sec + "," + tot + "," + prog + "," + level + ");",
                            null
                    );
                    mainHandler.postDelayed(this, 60);
                }
            } catch (Exception ignored) {}
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

        try {
            tts = new TextToSpeech(getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(Locale.UK);
                    tts.setSpeechRate(1.05f);
                    ttsReady = true;
                }
            });
        } catch (Exception ignored) {}

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
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                else startService(svc);
            } else {
                stopService(svc);
            }
        } catch (Exception ignored) {}
    }

    private void stopArchivePlayer() {
        mainHandler.removeCallbacks(archiveProgressTicker);
        if (archivePlayer != null) {
            try { if (archivePlayer.isPlaying()) archivePlayer.stop(); } catch (Exception ignored) {}
            try { archivePlayer.reset(); archivePlayer.release(); } catch (Exception ignored) {}
            archivePlayer = null;
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

    private void playDriveIdInArchive(final String fileId) {
        stopArchivePlayer();
        final String urlStr = "https://drive.usercontent.google.com/download?id=" + fileId + "&export=download";
        audioFetchExecutor.execute(() -> {
            try {
                File tempMp3 = new File(getCacheDir(), "archive_track.mp3");
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
                        stopArchivePlayer();
                        currentEnvelope = env;
                        archivePlayer = new MediaPlayer();
                        if (Build.VERSION.SDK_INT >= 21) {
                            archivePlayer.setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build());
                        }
                        FileInputStream fis = new FileInputStream(tempMp3);
                        archivePlayer.setDataSource(fis.getFD());
                        fis.close();
                        archivePlayer.setOnPreparedListener(mp -> {
                            mp.start();
                            if (webView != null) webView.evaluateJavascript("if(window.onArchivePlayStarted)window.onArchivePlayStarted();", null);
                            mainHandler.post(archiveProgressTicker);
                        });
                        archivePlayer.setOnCompletionListener(mp -> {
                            stopArchivePlayer();
                            if (webView != null) webView.evaluateJavascript("if(window.onArchivePlayEnded)window.onArchivePlayEnded();", null);
                        });
                        archivePlayer.prepareAsync();
                    } catch (Exception e) {
                        if (webView != null) webView.evaluateJavascript("if(window.onArchivePlayError)window.onArchivePlayError('PLAY ERROR');", null);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (webView != null) webView.evaluateJavascript("if(window.onArchivePlayError)window.onArchivePlayError('STREAM ERROR');", null);
                });
            }
        });
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
                    if (webView != null) {
                        webView.evaluateJavascript("if(window.onDriveRecordingsLoaded)window.onDriveRecordingsLoaded(" + escapedJson + ");", null);
                    }
                });
            } catch (Exception ignored) {
                isFetchingDrive = false;
            }
        });
    }
private void saveBackupJsonToDownloads(String webStorageJson, boolean showToast) {
        try {
            JSONObject root = new JSONObject();
            root.put("local_url", prefs.getString("local_url", ""));
            root.put("tailscale_url", prefs.getString("tailscale_url", ""));
            root.put("web_storage", webStorageJson != null ? new JSONObject(webStorageJson) : new JSONObject());
            byte[] bytes = root.toString(2).getBytes(StandardCharsets.UTF_8);

            prefs.edit().putString("last_backup_json", root.toString()).apply();

            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    getContentResolver().delete(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                            new String[]{"proscan_presets_backup.json"}
                    );
                } catch (Exception ignored) {}

                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, "proscan_presets_backup.json");
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(bytes);
                        os.close();
                    }
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File file = new File(dir, "proscan_presets_backup.json");
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(bytes);
                fos.close();
            }

            if (showToast) {
                Toast.makeText(this, "Saved to Downloads/proscan_presets_backup.json", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            if (showToast) {
                Toast.makeText(this, "Backup error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void restoreFromJsonString(String jsonStr) {
        try {
            JSONObject root = new JSONObject(jsonStr);
            String loc = root.optString("local_url", "");
            String ts = root.optString("tailscale_url", "");
            JSONObject webStorage = root.optJSONObject("web_storage");

            if (!loc.isEmpty() || !ts.isEmpty()) {
                prefs.edit().putString("local_url", loc).putString("tailscale_url", ts).apply();
            }
            if (webStorage != null) {
                prefs.edit().putString("shared_web_storage", webStorage.toString()).apply();
            }

            Toast.makeText(this, "Presets & IPs Restored! Loading...", Toast.LENGTH_SHORT).show();
            String activeLoc = prefs.getString("local_url", "");
            String activeTs = prefs.getString("tailscale_url", "");
            if (!activeLoc.isEmpty() || !activeTs.isEmpty()) {
                connectSmart(activeLoc, activeTs);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Invalid backup JSON file", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LOAD_JSON && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                InputStream is = getContentResolver().openInputStream(data.getData());
                if (is != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    restoreFromJsonString(sb.toString());
                }
            } catch (Exception e) {
                Toast.makeText(this, "Failed to read backup file", Toast.LENGTH_SHORT).show();
            }
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
        public void speakChannel(final String text) {
            mainHandler.post(() -> {
                if (tts != null && ttsReady && text != null && !text.trim().isEmpty()) {
                    if (Build.VERSION.SDK_INT >= 21) {
                        Bundle params = new Bundle();
                        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.35f);
                        tts.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, params, "proscan_tts");
                    } else {
                        tts.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, null);
                    }
                }
            });
        }

        /* Syncs presets & theme across BOTH Home Wi-Fi and Tailscale IPs automatically! */
        @JavascriptInterface
        public void syncActiveThemeInMemory(final String webStorageJson) {
            try {
                if (webStorageJson != null && webStorageJson.length() > 10) {
                    prefs.edit().putString("shared_web_storage", webStorageJson).apply();
                }
                JSONObject root = new JSONObject();
                root.put("local_url", prefs.getString("local_url", ""));
                root.put("tailscale_url", prefs.getString("tailscale_url", ""));
                root.put("web_storage", webStorageJson != null ? new JSONObject(webStorageJson) : new JSONObject());
                prefs.edit().putString("last_backup_json", root.toString()).apply();
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void openArchivePlayer() {
            mainHandler.post(() -> {
                String base = activeServerUrl;
                if (base == null || base.isEmpty()) base = webView.getUrl();
                Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                i.putExtra("server_url", base != null ? base : "");
                startActivity(i);
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            });
        }

        @JavascriptInterface
        public void autoSavePresetsJson(final String webStorageJson, final boolean notifyUser) {
            mainHandler.post(() -> {
                if (webStorageJson != null && webStorageJson.length() > 10) {
                    prefs.edit().putString("shared_web_storage", webStorageJson).apply();
                }
                saveBackupJsonToDownloads(webStorageJson, notifyUser);
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
        stopArchivePlayer();
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Exception ignored) {}
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
                /* Immediately hide hit badge & sync presets across Wi-Fi / Tailscale IPs at 0ms! */
                injectEnhancements();
                mainHandler.postDelayed(() -> injectEnhancements(), 400);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectEnhancements();
            }
        });

        mainHandler.removeCallbacks(periodicInjector);
        mainHandler.postDelayed(periodicInjector, 900);
    }
private void injectEnhancements() {
        String sharedStorage = prefs.getString("shared_web_storage", "");
        String safeShared = JSONObject.quote(sharedStorage);

        StringBuilder js = new StringBuilder();
        js.append("(function() {\n");
        js.append("  try {\n");

        /* 1. Immediately hide .freq-hit-badge so it never flashes on load! */
        js.append("    if (document.head && !document.getElementById('proscan-v7-upgrade-css')) {\n");
        js.append("      var st = document.createElement('style');\n");
        js.append("      st.id = 'proscan-v7-upgrade-css';\n");
        js.append("      st.textContent = '' +\n");
        js.append("        '#custom-scanner-ui-wrapper{perspective:1400px!important;}' +\n");
        js.append("        '#device-wrapper{transition:transform 0.38s cubic-bezier(0.4,0,0.2,1)!important;transform-style:preserve-3d!important;}' +\n");
        js.append("        '#device-wrapper.flipping-out{transform:rotateY(90deg) scale(0.95)!important;}' +\n");
        js.append("        '.buttons-box{border:1px solid rgba(0,0,0,0.48)!important;border-radius:12px!important;padding:7px!important;background:rgba(0,0,0,0.22)!important;box-shadow:inset 0 2px 6px rgba(0,0,0,0.75),0 1px 0 rgba(255,255,255,0.09)!important;}' +\n");
        js.append("        '.freq-hit-badge{display:none!important;}' +\n");
        js.append("        '#mini-log-table th,#mini-log-table td{padding:3px 2px 3px 4px!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(1),#mini-log-table td:nth-child(1){width:19%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(2),#mini-log-table td:nth-child(2){width:20%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(3),#mini-log-table td:nth-child(3){width:13%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(4),#mini-log-table td:nth-child(4){width:14%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(5),#mini-log-table td:nth-child(5){width:34%!important;}' +\n");
        js.append("        '#mini-log-table tbody tr{cursor:pointer;}#mini-log-table tbody tr:active{background:rgba(255,255,255,0.15)!important;}' +\n");
        js.append("        'html[data-skin=\"whiteline\"]{--ui-bg:radial-gradient(circle at 50% 12%,#26292e 0%,#121417 65%,#070809 100%)!important;--ui-bevel:inset 0 2px 3px rgba(255,255,255,0.35),inset 0 -3px 6px rgba(0,0,0,0.9)!important;--btn-bg:linear-gradient(180deg,#2e3238 0%,#181a1e 55%,#0d0e11 100%)!important;--btn-border:1px solid rgba(255,255,255,0.75)!important;--btn-shadow:0 3px 0 #94a3b8,0 4px 6px rgba(0,0,0,0.85),inset 0 1px 1px rgba(255,255,255,0.45)!important;--btn-shadow-active:0 0 0 #94a3b8,inset 0 2px 5px rgba(0,0,0,0.95)!important;--btn-active-transform:translateY(3px)!important;}' +\n");
        js.append("        'html[data-skin=\"tron\"]{--ui-bg:radial-gradient(circle at 50% 12%,#0c2e36 0%,#06161a 65%,#02080a 100%)!important;--ui-bevel:inset 0 2px 3px rgba(0,255,255,0.4),inset 0 -3px 6px rgba(0,0,0,0.9)!important;--btn-bg:linear-gradient(180deg,#103c47 0%,#071f26 55%,#031014 100%)!important;--btn-border:1px solid #00ffff!important;--btn-shadow:0 3px 0 #008b8b,0 4px 7px rgba(0,0,0,0.85),inset 0 1px 1px rgba(180,255,255,0.45)!important;--btn-shadow-active:0 0 0 #008b8b,inset 0 2px 5px rgba(0,0,0,0.95)!important;--btn-active-transform:translateY(3px)!important;}' +\n");
        js.append("        'html[data-skin=\"neongreen\"]{--ui-bg:radial-gradient(circle at 50% 12%,#11330d 0%,#081906 65%,#030a02 100%)!important;--ui-bevel:inset 0 2px 3px rgba(57,255,20,0.4),inset 0 -3px 6px rgba(0,0,0,0.9)!important;--btn-bg:linear-gradient(180deg,#184212 0%,#0b2108 55%,#051104 100%)!important;--btn-border:1px solid #39ff14!important;--btn-shadow:0 3px 0 #1b8a08,0 4px 7px rgba(0,0,0,0.85),inset 0 1px 1px rgba(190,255,180,0.45)!important;--btn-shadow-active:0 0 0 #1b8a08,inset 0 2px 5px rgba(0,0,0,0.95)!important;--btn-active-transform:translateY(3px)!important;}' +\n");
        js.append("        'html[data-skin=\"amber\"]{--ui-bg:radial-gradient(circle at 50% 12%,#332406 0%,#1a1203 65%,#0a0701 100%)!important;--ui-bevel:inset 0 2px 3px rgba(255,176,0,0.4),inset 0 -3px 6px rgba(0,0,0,0.9)!important;--btn-bg:linear-gradient(180deg,#422f08 0%,#211704 55%,#120c02 100%)!important;--btn-border:1px solid #ffb000!important;--btn-shadow:0 3px 0 #996900,0 4px 7px rgba(0,0,0,0.85),inset 0 1px 1px rgba(255,230,170,0.45)!important;--btn-shadow-active:0 0 0 #996900,inset 0 2px 5px rgba(0,0,0,0.95)!important;--btn-active-transform:translateY(3px)!important;}' +\n");
        js.append("        '.top-leds-left{gap:10px!important;}' +\n");
        js.append("        '#voice-switch-track{width:26px;height:11px;border-radius:999px;background:rgba(0,0,0,0.55);border:1px solid rgba(255,255,255,0.16);box-shadow:inset 0 1px 3px rgba(0,0,0,0.85),0 1px 0 rgba(255,255,255,0.08);padding:1px;display:flex;align-items:center;position:relative;flex-shrink:0;}' +\n");
        js.append("        '#voice-switch-thumb{width:11px;height:7px;border-radius:999px;background:var(--btn-bg);border:var(--btn-border);box-shadow:0 1px 2px rgba(0,0,0,0.75),inset 0 1px 0 rgba(255,255,255,0.2);transform:translateX(0);transition:transform 0.22s cubic-bezier(0.4,0,0.2,1);display:flex;align-items:center;justify-content:center;}' +\n");
        js.append("        '#voice-switch-thumb::after{content:\"\";width:2px;height:3px;border-radius:1px;background:rgba(255,255,255,0.22);}' +\n");
        js.append("        '#btn-voice-toggle.active #voice-switch-thumb{transform:translateX(11px);}' +\n");
        js.append("        '#voice-led{flex-shrink:0;width:10px;height:10px;border-radius:50%;background-color:var(--led-off-bg,#1a1a1a);border:var(--led-off-border,2px solid #000);box-shadow:var(--led-off-shadow,inset 1px 1px 3px rgba(0,0,0,0.8),0 1px 1px rgba(255,255,255,0.1));transition:all 0.3s ease;}' +\n");
        js.append("        '@keyframes voice-led-pulse-green{0%,100%{background-color:#10b981;border-color:#a7f3d0;box-shadow:inset 1px 1px 2px rgba(255,255,255,0.9),0 0 0 1px rgba(0,0,0,0.65),0 0 8px #10b981,0 0 14px rgba(16,185,129,0.75);}50%{background-color:#065f46;border-color:#059669;box-shadow:inset 1px 1px 2px rgba(255,255,255,0.35),0 0 0 1px rgba(0,0,0,0.65),0 0 2px rgba(16,185,129,0.25);}}' +\n");
        js.append("        '#btn-voice-toggle.active #voice-led{border-style:solid!important;border-width:2px!important;animation:voice-led-pulse-green 3.8s ease-in-out infinite!important;}' +\n");
        js.append("        '#btn-hardware-exit{display:inline-flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;background:transparent;border:none;padding:0;margin:0 0 0 2px;cursor:pointer;touch-action:manipulation;}#btn-hardware-exit .exit-cap{width:24px;height:11px;border-radius:4px;background:linear-gradient(180deg,#b91c1c,#7f1d1d);border:1px solid #450a0a;box-shadow:0 1px 2px rgba(0,0,0,0.75),inset 0 1px 0 rgba(255,255,255,0.28);display:flex;align-items:center;justify-content:center;}#btn-hardware-exit .exit-cap::after{content:\"\";width:8px;height:2px;border-radius:1px;background:rgba(255,220,220,0.85);}#btn-hardware-exit:active .exit-cap{transform:translateY(1px);filter:brightness(1.2);}';\n");
        js.append("      document.head.appendChild(st);\n");
        js.append("    }\n");

        /* 2. Cross-IP Preset & Theme Sync (Restores presets when switching between Home Wi-Fi & Tailscale!) */
        js.append("    if (!window.__crossIpSynced) {\n");
        js.append("      window.__crossIpSynced = true;\n");
        js.append("      var sharedRaw = ").append(safeShared).append(";\n");
        js.append("      if (sharedRaw && sharedRaw.length > 10) {\n");
        js.append("        var sharedObj = JSON.parse(sharedRaw);\n");
        js.append("        var localPresets = localStorage.getItem('proscan_theme_presets_v1');\n");
        js.append("        if ((!localPresets || localPresets === '{}') && sharedObj['proscan_theme_presets_v1']) {\n");
        js.append("          Object.keys(sharedObj).forEach(function(k){ localStorage.setItem(k, sharedObj[k]); });\n");
        js.append("          if (typeof applySkin === 'function') applySkin(parseInt(localStorage.getItem('proscan_skin_index')||'0',10)||0);\n");
        js.append("          if (typeof applyDisplay === 'function') applyDisplay(parseInt(localStorage.getItem('proscan_display_index')||'0',10)||0);\n");
        js.append("          if (typeof applyBg === 'function') applyBg(parseInt(localStorage.getItem('proscan_bg_index')||'0',10)||0);\n");
        js.append("          if (typeof renderCustomBrand === 'function') renderCustomBrand(localStorage.getItem('proscan_custom_brand_v1')||'');\n");
        js.append("        }\n");
        js.append("      }\n");
        js.append("    }\n");

        js.append("    var dw = document.getElementById('device-wrapper');\n");
        js.append("    if (dw) dw.classList.remove('flipping-out');\n");

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

        js.append("    window.__syncThemeInMem = function() {\n");
        js.append("      try {\n");
        js.append("        var dump = {};\n");
        js.append("        for (var i = 0; i < localStorage.length; i++) {\n");
        js.append("          var k = localStorage.key(i);\n");
        js.append("          if (k && k.indexOf('proscan_') === 0) dump[k] = localStorage.getItem(k);\n");
        js.append("        }\n");
        js.append("        if (window.AndroidApp && window.AndroidApp.syncActiveThemeInMemory) {\n");
        js.append("          window.AndroidApp.syncActiveThemeInMemory(JSON.stringify(dump));\n");
        js.append("        }\n");
        js.append("      } catch (e) {}\n");
        js.append("    };\n");
        js.append("    window.__syncThemeInMem();\n");

        js.append("    if (!window.__presetAutoBackupHooked) {\n");
        js.append("      window.__presetAutoBackupHooked = true;\n");
        js.append("      window.__exportAllProscanStorage = function(notify) {\n");
        js.append("        try {\n");
        js.append("          var dump = {};\n");
        js.append("          for (var i = 0; i < localStorage.length; i++) {\n");
        js.append("            var k = localStorage.key(i);\n");
        js.append("            if (k && k.indexOf('proscan_') === 0) dump[k] = localStorage.getItem(k);\n");
        js.append("          }\n");
        js.append("          if (window.AndroidApp && window.AndroidApp.autoSavePresetsJson) {\n");
        js.append("            window.AndroidApp.autoSavePresetsJson(JSON.stringify(dump), Boolean(notify));\n");
        js.append("          }\n");
        js.append("        } catch (e) {}\n");
        js.append("      };\n");
        js.append("      if (typeof window.savePresetToSlot === 'function') {\n");
        js.append("        var origSave = window.savePresetToSlot;\n");
        js.append("        window.savePresetToSlot = function(slot, snap) {\n");
        js.append("          origSave(slot, snap);\n");
        js.append("          window.__exportAllProscanStorage(false);\n");
        js.append("        };\n");
        js.append("      }\n");
        js.append("    }\n");

        /* Merge LOG IN / LOG OUT into 1 Smart Button & Convert 2nd Button to PLAYER with 3D Flip Animation! */
        js.append("    var loginBtn = document.getElementById('proscan-login-open');\n");
        js.append("    var logoutBtn = document.getElementById('proscan-logout-button');\n");
        js.append("    if (loginBtn && logoutBtn && !loginBtn.__mergedAuthHooked) {\n");
        js.append("      var newAuthBtn = loginBtn.cloneNode(true);\n");
        js.append("      newAuthBtn.__mergedAuthHooked = true;\n");
        js.append("      loginBtn.parentNode.replaceChild(newAuthBtn, loginBtn);\n");
        js.append("      function isSuperUserLoggedIn() {\n");
        js.append("        return (document.body.innerText || '').indexOf('Logged In: Super User') !== -1;\n");
        js.append("      }\n");
        js.append("      function syncAuthBtnLabel() {\n");
        js.append("        newAuthBtn.textContent = isSuperUserLoggedIn() ? 'LOG OUT' : 'LOG IN';\n");
        js.append("      }\n");
        js.append("      syncAuthBtnLabel();\n");
        js.append("      setInterval(syncAuthBtnLabel, 1000);\n");
        js.append("      newAuthBtn.addEventListener('click', function() {\n");
        js.append("        if (isSuperUserLoggedIn()) {\n");
        js.append("          var origOut = document.getElementById('input3');\n");
        js.append("          if (origOut) origOut.click();\n");
        js.append("          var p = document.getElementById('proscan-login-panel');\n");
        js.append("          if (p) p.hidden = true;\n");
        js.append("        } else {\n");
        js.append("          var p = document.getElementById('proscan-login-panel');\n");
        js.append("          var pw = document.getElementById('proscan-password');\n");
        js.append("          if (p) p.hidden = false;\n");
        js.append("          if (pw) pw.focus();\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("      var playerBtn = logoutBtn.cloneNode(true);\n");
        js.append("      playerBtn.id = 'btn-open-player-app';\n");
        js.append("      playerBtn.textContent = 'PLAYER';\n");
        js.append("      logoutBtn.parentNode.replaceChild(playerBtn, logoutBtn);\n");
        js.append("      playerBtn.addEventListener('click', function() {\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(25);\n");
        js.append("        if (window.__syncThemeInMem) window.__syncThemeInMem();\n");
        js.append("        var wrap = document.getElementById('device-wrapper');\n");
        js.append("        if (wrap) wrap.classList.add('flipping-out');\n");
        js.append("        setTimeout(function() {\n");
        js.append("          if (window.AndroidApp && window.AndroidApp.openArchivePlayer) {\n");
        js.append("            window.AndroidApp.openArchivePlayer();\n");
        js.append("          }\n");
        js.append("        }, 280);\n");
        js.append("      });\n");
        js.append("    }\n");

        /* Add VOICE Switch + LED next to WAKE */
        js.append("    var ledsLeft = document.querySelector('.top-leds-left');\n");
        js.append("    if (ledsLeft && !document.getElementById('btn-voice-toggle')) {\n");
        js.append("      window.__voiceReadoutEnabled = false;\n");
        js.append("      window.__lastSpokenChannel = '';\n");
        js.append("      window.__lastSpokenTime = 0;\n");
        js.append("      function speakTextNow(txt) {\n");
        js.append("        if (window.AndroidApp && typeof window.AndroidApp.speakChannel === 'function') {\n");
        js.append("          window.AndroidApp.speakChannel(txt);\n");
        js.append("        }\n");
        js.append("      }\n");
        js.append("      var vBtn = document.createElement('button');\n");
        js.append("      vBtn.id = 'btn-voice-toggle';\n");
        js.append("      vBtn.className = 'wake-btn-wrapper';\n");
        js.append("      vBtn.type = 'button';\n");
        js.append("      vBtn.innerHTML = '<div class=\"wake-switch-col\"><div id=\"voice-switch-track\"><div id=\"voice-switch-thumb\"></div></div><span class=\"rx-label\">VOICE</span></div><div id=\"voice-led\"></div>';\n");
        js.append("      vBtn.addEventListener('click', function() {\n");
        js.append("        window.__voiceReadoutEnabled = !window.__voiceReadoutEnabled;\n");
        js.append("        vBtn.classList.toggle('active', window.__voiceReadoutEnabled);\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(25);\n");
        js.append("        if (window.__voiceReadoutEnabled) {\n");
        js.append("          speakTextNow('Voice readout on');\n");
        js.append("          if (typeof showDisplayHud === 'function') showDisplayHud('VOICE READOUT: ON', 1400);\n");
        js.append("        } else {\n");
        js.append("          if (typeof showDisplayHud === 'function') showDisplayHud('VOICE READOUT: OFF', 1400);\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("      ledsLeft.appendChild(vBtn);\n");
        js.append("      var logBody = document.getElementById('mini-log-data');\n");
        js.append("      if (logBody) {\n");
        js.append("        new MutationObserver(function() {\n");
        js.append("          if (!window.__voiceReadoutEnabled) return;\n");
        js.append("          var newRow = logBody.querySelector('tr.new-log-row');\n");
        js.append("          if (newRow && newRow.children.length >= 5) {\n");
        js.append("            var nameTd = newRow.children[4];\n");
        js.append("            var rawTitle = nameTd.getAttribute('title') || nameTd.innerText || '';\n");
        js.append("            var closeParen = rawTitle.indexOf(')');\n");
        js.append("            var chName = (closeParen !== -1 ? rawTitle.slice(closeParen + 1) : rawTitle).trim();\n");
        js.append("            if (!chName || chName === '---') chName = (newRow.children[1].innerText || '').trim();\n");
        js.append("            var now = Date.now();\n");
        js.append("            if (chName && (chName !== window.__lastSpokenChannel || (now - window.__lastSpokenTime) > 15000)) {\n");
        js.append("              window.__lastSpokenChannel = chName;\n");
        js.append("              window.__lastSpokenTime = now;\n");
        js.append("              setTimeout(function() {\n");
        js.append("                if (window.__voiceReadoutEnabled) speakTextNow(chName);\n");
        js.append("              }, 350);\n");
        js.append("            }\n");
        js.append("          }\n");
        js.append("        }).observe(logBody, { childList: true });\n");
        js.append("      }\n");
        js.append("    }\n");

        /* Tap Log Row to Flash Full Name + Hit Count on HUD */
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
        js.append("        var closeParen = rawTitle.indexOf(')');\n");
        js.append("        var cleanName = (closeParen !== -1 ? rawTitle.slice(closeParen + 1) : rawTitle).trim();\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(15);\n");
        js.append("        if (typeof showDisplayHud === 'function') {\n");
        js.append("          showDisplayHud(cleanName + ' • HITS: ' + hitsText, 2200);\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("    }\n");

        /* Single AUDIO button */
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

        /* Hardware EXIT button */
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

    private void showPresetsBackupMenu() {
        String[] options = new String[]{
                "Save Backup to Downloads (proscan_presets_backup.json)",
                "Load Backup File from Downloads"
        };
        new AlertDialog.Builder(this)
                .setTitle("Presets & IP Backup (JSON)")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        if (webView != null) {
                            webView.evaluateJavascript("if(window.__exportAllProscanStorage)window.__exportAllProscanStorage(true);", null);
                        }
                    } else if (which == 1) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        startActivityForResult(intent, REQ_LOAD_JSON);
                    }
                })
                .setNegativeButton("Back", null)
                .show();
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

        builder.setNeutralButton("Presets JSON", (dialog, which) -> showPresetsBackupMenu());

        if (!isFirstRun) {
            builder.setNegativeButton("Cancel", null);
        }
        builder.show();
    }

    private void connectSmart(String localUrl, String tailscaleUrl) {
        if (!localUrl.isEmpty() && tailscaleUrl.isEmpty()) {
            activeServerUrl = localUrl;
            webView.loadUrl(localUrl);
            return;
        }
        if (localUrl.isEmpty() && !tailscaleUrl.isEmpty()) {
            activeServerUrl = tailscaleUrl;
            webView.loadUrl(tailscaleUrl);
            return;
        }
        Toast.makeText(this, "Checking Local Wi-Fi...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            boolean localReachable = isUrlReachable(localUrl, 1500);
            mainHandler.post(() -> {
                if (localReachable) {
                    activeServerUrl = localUrl;
                    Toast.makeText(this, "Connected via Local Wi-Fi", Toast.LENGTH_SHORT).show();
                    webView.loadUrl(localUrl);
                } else {
                    activeServerUrl = tailscaleUrl;
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
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.evaluateJavascript("var dw=document.getElementById('device-wrapper');if(dw)dw.classList.remove('flipping-out');", null);
        }
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