package com.proscan.interfaceapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
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

    private static final int REQ_LOAD_RADIO_JSON = 902;
    private static final String GDRIVE_ROOT_FOLDER_ID = "1gToQyY6YPOk_3muiQSY5WLy7jTSvXbTv";
    private WebView playerWeb;
    private SharedPreferences prefs;
    private AudioManager audioManager;
    private PowerManager.WakeLock cpuWakeLock = null;
    private WifiManager.WifiLock wifiLock = null;
    private boolean wakeLockOn = true;
    private String serverBaseUrl = "";
    private String initialStartMode = "archive";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService audioFetchExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextToSpeech tts = null;
    private boolean ttsReady = false;
    private MediaPlayer nativePlayer = null;
    private Equalizer speechEqualizer = null;
    private boolean clarityFilterEnabled = false;
    private boolean isFetchingDrive = false;
    private boolean isLiveRadioStream = false;
    private volatile int currentStreamReqId = 0;
    private float[] currentEnvelope = new float[0];

    private synchronized void setupEqualizer(int audioSessionId) {
        releaseEqualizer();
        try {
            speechEqualizer = new Equalizer(0, audioSessionId);
            speechEqualizer.setEnabled(clarityFilterEnabled);
            if (clarityFilterEnabled) {
                configureSpeechBands(speechEqualizer);
            }
        } catch (Exception ignored) {}
    }

    private void configureSpeechBands(Equalizer eq) {
        if (eq == null) return;
        try {
            short bands = eq.getNumberOfBands();
            short[] range = eq.getBandLevelRange();
            short minEq = range[0];
            short maxEq = range[1];
            for (short i = 0; i < bands; i++) {
                int hz = eq.getCenterFreq(i) / 1000;
                if (hz < 320) {
                    // Cut low rumble, motor drone, power hum (-7.0 dB)
                    eq.setBandLevel(i, (short) Math.max(minEq, -700));
                } else if (hz >= 1000 && hz <= 3600) {
                    // Boost speech presence & consonant clarity (+6.5 dB)
                    eq.setBandLevel(i, (short) Math.min(maxEq, 650));
                } else if (hz > 4500) {
                    // Attenuate harsh FM squelch hiss & static (-6.0 dB)
                    eq.setBandLevel(i, (short) Math.max(minEq, -600));
                } else {
                    eq.setBandLevel(i, (short) 0);
                }
            }
        } catch (Exception ignored) {}
    }

    private synchronized void releaseEqualizer() {
        if (speechEqualizer != null) {
            try { speechEqualizer.release(); } catch (Exception ignored) {}
            speechEqualizer = null;
        }
    }

    private synchronized void acquirePlaybackLocks() {
        try {
            if (cpuWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "proscan:radio_cpu_lock");
                    cpuWakeLock.setReferenceCounted(false);
                }
            }
            if (cpuWakeLock != null && !cpuWakeLock.isHeld()) {
                cpuWakeLock.acquire(12 * 60 * 60 * 1000L);
            }
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL;
                    wifiLock = wm.createWifiLock(mode, "proscan:radio_wifi_lock");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
            }
            Intent svc = new Intent(this, AudioKeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        } catch (Exception ignored) {}
    }

    private synchronized void releasePlaybackLocks() {
        try {
            if (cpuWakeLock != null && cpuWakeLock.isHeld()) cpuWakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            stopService(new Intent(this, AudioKeepAliveService.class));
        } catch (Exception ignored) {}
    }

    private void forceStopAllPlayerAudioOnDisconnect() {
        boolean wasPlaying = isLiveRadioStream || (nativePlayer != null && nativePlayer.isPlaying());
        stopNativePlayer();
        Runnable killWebAudio = () -> {
            stopNativePlayer();
            if (playerWeb != null) {
                playerWeb.evaluateJavascript(
                        "try{if(window.__stopAllRadioAndNativeAudio)window.__stopAllRadioAndNativeAudio();" +
                        "if(typeof setPlayingUI==='function')setPlayingUI(false);" +
                        "if(typeof renderPlaylist==='function')renderPlaylist();}catch(e){}",
                        null
                );
            }
        };
        mainHandler.post(killWebAudio);
        mainHandler.postDelayed(killWebAudio, 200);
        mainHandler.postDelayed(killWebAudio, 500);
        if (wasPlaying) {
            mainHandler.post(() -> Toast.makeText(PlayerActivity.this, "Headphones disconnected — Stream stopped", Toast.LENGTH_SHORT).show());
        }
    }

    private final BroadcastReceiver noisyAudioReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (isInitialStickyBroadcast()) return;
            String act = intent.getAction();
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(act)
                    || (Intent.ACTION_HEADSET_PLUG.equals(act) && intent.getIntExtra("state", -1) == 0)) {
                forceStopAllPlayerAudioOnDisconnect();
            }
        }
    };

    private final AudioDeviceCallback btDeviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            if (removedDevices == null) return;
            for (AudioDeviceInfo dev : removedDevices) {
                int t = dev.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET
                        || t == 26
                        || t == 27) {
                    forceStopAllPlayerAudioOnDisconnect();
                    break;
                }
            }
        }
    };

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            try {
                if (playerWeb != null && (isLiveRadioStream || (nativePlayer != null && nativePlayer.isPlaying()))) {
                    int sec = 0, tot = 0;
                    float prog = 0.5f, level = 0.35f;
                    if (isLiveRadioStream) {
                        sec = (int) ((System.currentTimeMillis() / 1000) % 3600);
                        tot = 3600;
                        prog = (sec % 60) / 60f;
                        double t = System.currentTimeMillis() / 140.0;
                        level = (float) (0.32 + 0.24 * Math.sin(t) * Math.cos(t * 0.43) + 0.15 * Math.sin(t * 2.1));
                        level = Math.min(0.95f, Math.max(0.12f, level));
                    } else if (nativePlayer != null && nativePlayer.isPlaying()) {
                        int posMs = nativePlayer.getCurrentPosition();
                        int durMs = Math.max(1, nativePlayer.getDuration());
                        sec = posMs / 1000;
                        tot = Math.max(1, durMs / 1000);
                        prog = Math.min(1f, Math.max(0f, (float) posMs / (float) durMs));
                        if (currentEnvelope.length > 0) {
                            int envIdx = Math.min(currentEnvelope.length - 1, (int) (prog * currentEnvelope.length));
                            level = currentEnvelope[envIdx];
                        }
                    } else {
                        return;
                    }
                    playerWeb.evaluateJavascript("if(window.onNativeTelemetry)window.onNativeTelemetry(" + sec + "," + tot + "," + prog + "," + level + ");", null);
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

        try {
            IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            f.addAction(Intent.ACTION_HEADSET_PLUG);
            registerReceiver(noisyAudioReceiver, f);
        } catch (Exception ignored) {}

        try {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null && Build.VERSION.SDK_INT >= 23) {
                audioManager.registerAudioDeviceCallback(btDeviceCallback, mainHandler);
            }
        } catch (Exception ignored) {}

        prefs = getSharedPreferences("proscan_prefs", MODE_PRIVATE);
        wakeLockOn = prefs.getBoolean("wake_lock_on", true);
        clarityFilterEnabled = prefs.getBoolean("clarity_filter_on", false);

        serverBaseUrl = getIntent().getStringExtra("server_url");
        if (serverBaseUrl == null) serverBaseUrl = "";
        serverBaseUrl = serverBaseUrl.replaceAll("/+$", "");

        initialStartMode = getIntent().getStringExtra("start_mode");
        if (initialStartMode == null) initialStartMode = "archive";

        try {
            tts = new TextToSpeech(getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(Locale.UK);
                    tts.setSpeechRate(1.05f);
                    ttsReady = true;
                }
            });
        } catch (Exception ignored) {}

        playerWeb = new WebView(this) {
            @Override
            public void setKeepScreenOn(boolean keepScreenOn) {
                super.setKeepScreenOn(wakeLockOn);
            }
            @Override
            protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(View.VISIBLE);
            }
        };
        playerWeb.setBackgroundColor(Color.BLACK);
        applyPlayerWakeLock(wakeLockOn);

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
private String getNativeBatteryStatus() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent b = registerReceiver(null, ifilter);
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                if (level >= 0 && scale > 0) {
                    int pct = Math.round((level * 100f) / scale);
                    return (charging ? "⚡" : "") + pct + "%";
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void showBothSystemBarsNow() {
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            );
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController ic = getWindow().getInsetsController();
                if (ic != null) {
                    ic.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
            }
        } catch (Exception ignored) {}
    }

    private void applyPlayerWakeLock(boolean keepOn) {
        wakeLockOn = keepOn;
        if (prefs != null) prefs.edit().putBoolean("wake_lock_on", keepOn).apply();
        if (keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (playerWeb != null) playerWeb.setKeepScreenOn(true);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (playerWeb != null) playerWeb.setKeepScreenOn(false);
        }
    }

    private void saveRadioStationsJsonToDownloads(String stationsJson, boolean showToast) {
        try {
            prefs.edit().putString("radio_stations_json", stationsJson).apply();
            JSONArray arr = new JSONArray(stationsJson);
            byte[] bytes = arr.toString(2).getBytes(StandardCharsets.UTF_8);
            boolean written = false;

            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    Uri targetUri = null;
                    Cursor cursor = getContentResolver().query(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            new String[]{MediaStore.MediaColumns._ID},
                            MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                            new String[]{"proscan_radio_stations.json"},
                            null
                    );
                    if (cursor != null) {
                        if (cursor.moveToFirst()) {
                            long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                            targetUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
                        }
                        cursor.close();
                    }

                    if (targetUri == null) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "proscan_radio_stations.json");
                        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                        targetUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    }

                    if (targetUri != null) {
                        OutputStream os = getContentResolver().openOutputStream(targetUri, "wt");
                        if (os != null) {
                            os.write(bytes);
                            os.close();
                            written = true;
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (!written) {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, "proscan_radio_stations.json");
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(bytes);
                fos.close();
                written = true;
            }

            if (showToast && written) {
                Toast.makeText(this, "Saved to Downloads/proscan_radio_stations.json", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            if (showToast) {
                Toast.makeText(this, "Save error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LOAD_RADIO_JSON && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                InputStream is = getContentResolver().openInputStream(data.getData());
                if (is != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    String raw = sb.toString().trim();
                    String stationsArrayStr = raw;
                    if (raw.startsWith("{")) {
                        JSONObject obj = new JSONObject(raw);
                        if (obj.has("web_storage")) {
                            stationsArrayStr = obj.getJSONObject("web_storage").optString("proscan_radio_stations_v3", "");
                        }
                    }
                    JSONArray testArr = new JSONArray(stationsArrayStr);
                    if (testArr.length() > 0) {
                        prefs.edit().putString("radio_stations_json", testArr.toString()).apply();
                        final String safeArr = JSONObject.quote(testArr.toString());
                        if (playerWeb != null) {
                            playerWeb.evaluateJavascript("if(window.onNativeStationsJsonLoaded)window.onNativeStationsJsonLoaded(" + safeArr + ");", null);
                        }
                        Toast.makeText(this, "Loaded " + testArr.length() + " Radio Stations from JSON!", Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (Exception e) {
                Toast.makeText(this, "Invalid Radio Stations JSON file", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showStationMenuDialog(final int curIdx, final String curName, final String curFreq, final String curUrl) {
        String[] items = new String[]{
                "Edit Selected Station (" + (curName != null && !curName.isEmpty() ? curName : "Station") + ")",
                "Save Stations JSON to Downloads (proscan_radio_stations.json)",
                "Load Stations JSON from Downloads",
                "Reset Stations to Default UK List"
        };
        new AlertDialog.Builder(this)
                .setTitle("Radio Station & JSON Manager")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        openAddOrEditStationDialog(curIdx, curName, curFreq, curUrl);
                    } else if (which == 1) {
                        if (playerWeb != null) {
                            playerWeb.evaluateJavascript("if(window.PlayerBridge)window.PlayerBridge.saveStationsJson(JSON.stringify(window.__radioStations), true);", null);
                        }
                    } else if (which == 2) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        startActivityForResult(intent, REQ_LOAD_RADIO_JSON);
                    } else if (which == 3) {
                        if (playerWeb != null) {
                            playerWeb.evaluateJavascript("if(window.__resetDefaultRadioStations)window.__resetDefaultRadioStations();", null);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void injectReactiveDecksAndTheme() {
        String backupJson = prefs.getString("last_backup_json", "");
        String safeBackup = JSONObject.quote(backupJson);
        String savedStationsJson = prefs.getString("radio_stations_json", "");
        String safeStations = JSONObject.quote(savedStationsJson);
        boolean wakePref = prefs.getBoolean("wake_lock_on", true);
        boolean openInRadio = "radio".equalsIgnoreCase(initialStartMode);

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
        js.append("        if (bs) bs.innerHTML = '<div class=\"brand-henus\"><span class=\"henus-mark\">'+br+'</span><span class=\"henus-sub\" id=\"brand-sub-mode\">ARCHIVE</span></div>';\n");
        js.append("      }\n");
        js.append("    }\n");
        js.append("    var nativeSt = ").append(safeStations).append(";\n");
        js.append("    if (nativeSt && nativeSt.length > 10) localStorage.setItem('proscan_radio_stations_v3', nativeSt);\n");
        js.append("  } catch(e) {}\n");

        js.append("  var nameHdr = document.querySelector('.screen-log-header span:last-child');\n");
        js.append("  if (nameHdr) nameHdr.innerText = 'NAME';\n");

        js.append("  var st = document.createElement('style');\n");
        js.append("  st.textContent = '' +\n");
        js.append("    '@keyframes smoothFadeIn{0%{opacity:0;}100%{opacity:1;}}' +\n");
        js.append("    '#device-wrapper{animation:smoothFadeIn 0.25s ease-out forwards;transition:opacity 0.2s ease!important;}' +\n");
        js.append("    '#device-wrapper.fading-out{opacity:0!important;}' +\n");
        js.append("    '.scanner-display{height:245px!important;}.top-controls-container{height:28px!important;}.buttons-box{gap:8px!important;}#mech-bay{height:224px!important;}' +\n");
        js.append("    '#playlist-box{overflow-y:scroll!important;-webkit-overflow-scrolling:touch!important;touch-action:pan-y!important;overscroll-behavior:contain!important;}' +\n");
        js.append("    '.rec-row{touch-action:pan-y!important;}' +\n");
        js.append("    '.rec-row.selected{outline:1px dashed var(--log-text,#38bdf8)!important;background:rgba(255,255,255,0.14)!important;font-weight:800!important;}' +\n");
        js.append("    '.radio-row{display:grid!important;grid-template-columns:36px 68px 48px 1fr!important;gap:4px!important;padding:4px 6px!important;border-bottom:1px solid var(--log-cell-border)!important;font-family:\"JetBrains Mono\",monospace!important;font-size:10px!important;color:var(--log-text)!important;align-items:center!important;text-align:left!important;cursor:pointer!important;touch-action:pan-y!important;}' +\n");
        js.append("    '.radio-row.playing{background:var(--log-text)!important;color:var(--display-bg)!important;font-weight:800!important;border-radius:3px!important;}' +\n");
        js.append("    '.radio-row.selected{outline:1px dashed var(--log-text,#38bdf8)!important;background:rgba(255,255,255,0.14)!important;font-weight:800!important;}' +\n");
        js.append("    '.unread-dot{display:inline-block;width:6px;height:6px;border-radius:50%;background:var(--log-text,#39ff14);margin-right:4px;vertical-align:middle;}' +\n");
        js.append("    '.top-row-6{display:grid!important;grid-template-columns:repeat(6,minmax(0,1fr))!important;gap:4px!important;height:28px!important;}' +\n");
        js.append("    '.top-row-6 .btn{font-size:8.5px!important;letter-spacing:-0.2px!important;padding:1px 2px!important;}' +\n");
        js.append("    '.filter-row-5{display:grid!important;grid-template-columns:88px 1fr 62px 32px 46px!important;gap:5px!important;height:27px!important;}' +\n");
        js.append("    '.radio-crud-bar{display:none;grid-template-columns:repeat(5,minmax(0,1fr));gap:5px;height:27px;}' +\n");
        js.append("    '.brand-henus{display:inline-flex!important;flex-direction:column!important;align-items:center!important;justify-content:center!important;gap:1px!important;line-height:1!important;white-space:nowrap!important;}' +\n");
        js.append("    '.brand-henus .henus-mark{font-size:10px!important;letter-spacing:1.5px!important;padding:0 4px!important;border-left:1.5px solid var(--brand-accent,#38bdf8)!important;border-right:1.5px solid var(--brand-accent,#38bdf8)!important;text-transform:uppercase!important;}' +\n");
        js.append("    '.brand-henus .henus-sub{font-size:6.5px!important;letter-spacing:2px!important;line-height:1!important;margin-top:1px!important;}' +\n");
        js.append("    '#deck-vinyl{background:radial-gradient(circle at 46% 54%,#1e2229 0%,#0c0e12 85%)!important;}' +\n");
        js.append("    '.tt-platter{position:absolute!important;left:90px!important;top:38px!important;width:140px!important;height:140px!important;box-shadow:0 8px 20px rgba(0,0,0,0.95),0 0 0 3px #18181b!important;}' +\n");
        js.append("    '.tt-record{width:128px!important;height:128px!important;}' +\n");
        js.append("    '.tt-strobe{position:absolute!important;left:76px!important;top:72px!important;bottom:auto!important;width:9px!important;height:14px!important;border-radius:3px!important;background:linear-gradient(180deg,#ff6600,#cc2200)!important;border:1px solid #ffb380!important;box-shadow:0 0 12px #ff4400,0 0 20px #ff1100!important;z-index:8!important;}' +\n");
        js.append("    '.tt-arm-base{top:34px!important;right:54px!important;width:38px!important;height:38px!important;box-shadow:0 4px 10px #000,inset 0 1px 2px rgba(255,255,255,0.6)!important;z-index:9!important;}' +\n");
        js.append("    '.tt-tonearm{top:50px!important;right:70px!important;height:118px!important;transform-origin:top center!important;transform:rotate(6deg);transition:transform 0.2s linear!important;z-index:10!important;}' +\n");
        js.append("    '.r2r-stage{width:94%!important;height:100%!important;padding:30px 18px 0!important;display:flex!important;justify-content:space-between!important;align-items:flex-start!important;}' +\n");
        js.append("    '.r2r-reel{width:110px!important;height:110px!important;margin-top:0!important;box-shadow:0 6px 14px rgba(0,0,0,0.95)!important;}' +\n");
        js.append("    '.r2r-tape-left{bottom:62px!important;left:56px!important;width:96px!important;transform:rotate(24deg)!important;}' +\n");
        js.append("    '.r2r-tape-right{bottom:62px!important;right:56px!important;width:96px!important;transform:rotate(-24deg)!important;}' +\n");
        js.append("    '.r2r-headblock{bottom:42px!important;width:92px!important;height:28px!important;background:linear-gradient(180deg,#e4e4e7 0%,#71717a 48%,#27272a 100%)!important;border:1px solid #09090b!important;box-shadow:0 4px 10px #000!important;}' +\n");
        js.append("    '.r2r-lower-bridge{position:absolute;bottom:28px;left:12px;right:12px;height:40px;border-radius:6px;background:linear-gradient(180deg,#272b32 0%,#14171c 100%);border:1px solid rgba(255,255,255,0.12);box-shadow:inset 0 2px 5px rgba(0,0,0,0.85),0 2px 6px #000;display:flex;justify-content:space-between;align-items:center;padding:0 10px;z-index:8;pointer-events:none;}' +\n");
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
        js.append("    '.rvu-seg{width:6px;height:5px;border-radius:1px;background:#1e293b;}' +\n");
        js.append("    '#player-wake-pill{display:inline-flex;align-items:center;gap:4px;cursor:pointer;padding:1px 5px;border-radius:999px;background:rgba(0,0,0,0.45);border:1px solid rgba(255,255,255,0.14);margin-right:5px;}' +\n");
        js.append("    '#player-wake-dot{width:7px;height:7px;border-radius:50%;background:#334155;transition:all 0.25s ease;}' +\n");
        js.append("    '#player-wake-pill.active #player-wake-dot{background:#10b981;box-shadow:0 0 6px #10b981;}' +\n");
        js.append("    '#btn-player-exit{display:inline-flex;flex-direction:column;align-items:center;justify-content:center;gap:1px;background:transparent;border:none;padding:0;margin-left:6px;cursor:pointer;touch-action:manipulation;}' +\n");
        js.append("    '#btn-player-exit .exit-cap{width:22px;height:10px;border-radius:4px;background:linear-gradient(180deg,#b91c1c,#7f1d1d);border:1px solid #450a0a;box-shadow:0 1px 2px rgba(0,0,0,0.75),inset 0 1px 0 rgba(255,255,255,0.28);display:flex;align-items:center;justify-content:center;}' +\n");
        js.append("    '#btn-player-exit .exit-cap::after{content:\"\";width:7px;height:2px;border-radius:1px;background:rgba(255,220,220,0.85);}' +\n");
        js.append("    '#btn-player-exit:active .exit-cap{transform:translateY(1px);filter:brightness(1.2);}' +\n");
        js.append("    '#player-bat-hud{display:inline-flex;align-items:center;gap:3px;font-family:\"JetBrains Mono\",monospace;font-size:10px;font-weight:800;color:var(--log-text);}' +\n");
        js.append("    '.bat-icon-box{display:inline-block;width:15px;height:8px;border:1.4px solid currentColor;border-radius:2px;padding:1px;position:relative;vertical-align:middle;margin-right:3px;}' +\n");
        js.append("    '.bat-icon-box::after{content:\"\";position:absolute;right:-3px;top:1.5px;width:1.5px;height:3px;background:currentColor;border-radius:0 1px 1px 0;}' +\n");
        js.append("    '.bat-icon-fill{display:block;height:100%;background:currentColor;width:80%;transition:width 0.3s ease;}';\n");
        js.append("  document.head.appendChild(st);\n");
js.append("  var pClk = document.getElementById('player-clock-hud');\n");
        js.append("  if (pClk && !document.getElementById('player-bat-hud')) {\n");
        js.append("    var topBar = pClk.parentNode;\n");
        js.append("    var pBat = document.createElement('span');\n");
        js.append("    pBat.id = 'player-bat-hud';\n");
        js.append("    pBat.innerHTML = '<span class=\"bat-icon-box\"><span class=\"bat-icon-fill\" id=\"player-bat-fill\"></span></span><span id=\"player-bat-txt\">--%</span>';\n");
        js.append("    topBar.insertBefore(pClk, topBar.firstChild);\n");
        js.append("    topBar.appendChild(pBat);\n");
        js.append("    function updatePlayerBattery() {\n");
        js.append("      if (window.PlayerBridge && typeof window.PlayerBridge.getBatteryStatus === 'function') {\n");
        js.append("        var bStr = window.PlayerBridge.getBatteryStatus() || '';\n");
        js.append("        var bTxt = document.getElementById('player-bat-txt');\n");
        js.append("        var bFill = document.getElementById('player-bat-fill');\n");
        js.append("        if (bTxt && bStr) bTxt.innerText = bStr;\n");
        js.append("        if (bFill && bStr) {\n");
        js.append("          var num = parseInt(bStr.replace(/[^0-9]/g, ''), 10) || 0;\n");
        js.append("          bFill.style.width = Math.max(8, Math.min(100, num)) + '%';\n");
        js.append("        }\n");
        js.append("      }\n");
        js.append("    }\n");
        js.append("    updatePlayerBattery();\n");
        js.append("    setInterval(updatePlayerBattery, 10000);\n");
        js.append("  }\n");

        js.append("  var recHud = document.getElementById('rec-count-hud');\n");
        js.append("  if (recHud && !document.getElementById('player-wake-pill')) {\n");
        js.append("    var wWrap = document.createElement('div');\n");
        js.append("    wWrap.style.cssText = 'display:flex;align-items:center;padding-left:2px;';\n");
        js.append("    var wPill = document.createElement('button');\n");
        js.append("    wPill.id = 'player-wake-pill'; wPill.type = 'button';\n");
        js.append("    wPill.className = ").append(wakePref ? "'active'" : "''").append(";\n");
        js.append("    wPill.innerHTML = '<span id=\"player-wake-dot\"></span><span style=\"font-size:7.5px;font-weight:900;color:var(--toggle-color);\">WAKE</span>';\n");
        js.append("    recHud.parentNode.insertBefore(wWrap, recHud);\n");
        js.append("    wWrap.appendChild(wPill);\n");
        js.append("    wWrap.appendChild(recHud);\n");
        js.append("    window.__playerWakeActive = ").append(wakePref).append(";\n");
        js.append("    wPill.addEventListener('click', function() {\n");
        js.append("      window.__playerWakeActive = !window.__playerWakeActive;\n");
        js.append("      wPill.classList.toggle('active', window.__playerWakeActive);\n");
        js.append("      if (navigator.vibrate) navigator.vibrate(20);\n");
        js.append("      if (window.PlayerBridge && window.PlayerBridge.setWakeLock) {\n");
        js.append("        window.PlayerBridge.setWakeLock(window.__playerWakeActive);\n");
        js.append("      }\n");
        js.append("    });\n");
        js.append("  }\n");

        js.append("  var contHud = document.getElementById('cont-status-hud');\n");
        js.append("  if (contHud && !document.getElementById('btn-player-exit')) {\n");
        js.append("    var rWrap = document.createElement('div');\n");
        js.append("    rWrap.style.cssText = 'justify-self:end;display:flex;align-items:center;padding-right:2px;';\n");
        js.append("    contHud.parentNode.insertBefore(rWrap, contHud);\n");
        js.append("    rWrap.appendChild(contHud);\n");
        js.append("    var exBtn = document.createElement('button');\n");
        js.append("    exBtn.id = 'btn-player-exit'; exBtn.type = 'button';\n");
        js.append("    exBtn.innerHTML = '<div class=\"exit-cap\"></div><span style=\"font-size:6.5px;font-weight:900;color:var(--toggle-color);letter-spacing:0.5px;\">EXIT</span>';\n");
        js.append("    rWrap.appendChild(exBtn);\n");
        js.append("    var exTimer = null, exLong = false, lastExTap = 0;\n");
        js.append("    exBtn.addEventListener('pointerdown', function() {\n");
        js.append("      clearTimeout(exTimer); exLong = false;\n");
        js.append("      exTimer = setTimeout(function() {\n");
        js.append("        exTimer = null; exLong = true;\n");
        js.append("        if (navigator.vibrate) navigator.vibrate([45, 60, 55]);\n");
        js.append("        if (window.__stopAllRadioAndNativeAudio) window.__stopAllRadioAndNativeAudio();\n");
        js.append("        var dw = document.getElementById('device-wrapper');\n");
        js.append("        if (dw) dw.classList.add('fading-out');\n");
        js.append("        setTimeout(function() { if (window.PlayerBridge && window.PlayerBridge.exitEntireApp) window.PlayerBridge.exitEntireApp(); }, 350);\n");
        js.append("      }, 550);\n");
        js.append("    });\n");
        js.append("    exBtn.addEventListener('pointerup', function() {\n");
        js.append("      var wasTap = exTimer !== null; clearTimeout(exTimer); exTimer = null;\n");
        js.append("      if (wasTap && !exLong) {\n");
        js.append("        var now = Date.now();\n");
        js.append("        if (now - lastExTap < 420) {\n");
        js.append("          lastExTap = 0;\n");
        js.append("          if (window.PlayerBridge && window.PlayerBridge.minimizeApp) window.PlayerBridge.minimizeApp();\n");
        js.append("        } else {\n");
        js.append("          lastExTap = now;\n");
        js.append("          if (window.PlayerBridge && window.PlayerBridge.showSystemBars) window.PlayerBridge.showSystemBars();\n");
        js.append("        }\n");
        js.append("      }\n");
        js.append("    });\n");
        js.append("    exBtn.addEventListener('pointerleave', function() { clearTimeout(exTimer); exTimer = null; });\n");
        js.append("    exBtn.addEventListener('pointercancel', function() { clearTimeout(exTimer); exTimer = null; });\n");
        js.append("  }\n");

        js.append("  window.__stopAllRadioAndNativeAudio = function() {\n");
        js.append("    window.__streamLaunchToken = (window.__streamLaunchToken || 0) + 1;\n");
        js.append("    if (window.PlayerBridge && window.PlayerBridge.stopAudio) {\n");
        js.append("      window.PlayerBridge.stopAudio();\n");
        js.append("    }\n");
        js.append("  };\n");

        js.append("  var backBtn = document.getElementById('btn-back-radio');\n");
        js.append("  var topRow = backBtn ? backBtn.parentNode : null;\n");
        js.append("  if (topRow && !document.getElementById('btn-switch-radio-archive')) {\n");
        js.append("    topRow.className = 'top-row-6';\n");
        js.append("    var newScannerBtn = backBtn.cloneNode(true);\n");
        js.append("    newScannerBtn.innerHTML = '&#9664; SCAN';\n");
        js.append("    topRow.replaceChild(newScannerBtn, backBtn);\n");
        js.append("    newScannerBtn.addEventListener('click', function() {\n");
        js.append("      if (navigator.vibrate) navigator.vibrate(20);\n");
        js.append("      window.__stopAllRadioAndNativeAudio();\n");
        js.append("      if (window.markCurrentListAsVisited) window.markCurrentListAsVisited();\n");
        js.append("      var dw = document.getElementById('device-wrapper');\n");
        js.append("      if (dw) dw.classList.add('fading-out');\n");
        js.append("      setTimeout(function() { if (window.PlayerBridge) window.PlayerBridge.closePlayer(); }, 180);\n");
        js.append("    });\n");

        js.append("    var modeSwitchBtn = document.createElement('button');\n");
        js.append("    modeSwitchBtn.id = 'btn-switch-radio-archive';\n");
        js.append("    modeSwitchBtn.className = 'btn';\n");
        js.append("    modeSwitchBtn.type = 'button';\n");
        js.append("    modeSwitchBtn.innerHTML = 'RADIO &#9654;';\n");
        js.append("    topRow.insertBefore(modeSwitchBtn, newScannerBtn.nextSibling);\n");
        js.append("    modeSwitchBtn.addEventListener('click', function() {\n");
        js.append("      if (navigator.vibrate) navigator.vibrate(20);\n");
        js.append("      window.setAppMode(!window.__isBroadcastRadioMode);\n");
        js.append("    });\n");

        js.append("    var oldContBtn = document.getElementById('btn-mode-cont');\n");
        js.append("    if (oldContBtn) {\n");
        js.append("      var cBtn = oldContBtn.cloneNode(true);\n");
        js.append("      oldContBtn.parentNode.replaceChild(cBtn, oldContBtn);\n");
        js.append("      window.__playModeState = 0;\n");
        js.append("      window.__isLoopMode = false;\n");
        js.append("      cBtn.addEventListener('click', function() {\n");
        js.append("        window.__playModeState = (window.__playModeState + 1) % 3;\n");
        js.append("        isCont = (window.__playModeState === 1);\n");
        js.append("        window.__isLoopMode = (window.__playModeState === 2);\n");
        js.append("        cBtn.classList.toggle('active', window.__playModeState !== 0);\n");
        js.append("        var lbl = window.__playModeState === 0 ? 'CONT: OFF' : (window.__playModeState === 1 ? 'CONT: ON' : 'LOOP: 1');\n");
        js.append("        cBtn.innerText = lbl;\n");
        js.append("        document.getElementById('cont-status-hud').innerText = lbl;\n");
        js.append("      });\n");
        js.append("    }\n");

        // Dedicated 1-Click Audio Clarity DSP Filter Button
        js.append("    var dspBtn = document.createElement('button');\n");
        js.append("    dspBtn.id = 'btn-dsp-filter';\n");
        js.append("    dspBtn.className = 'btn';\n");
        js.append("    dspBtn.type = 'button';\n");
        js.append("    function syncDspLabel(active) {\n");
        js.append("      dspBtn.classList.toggle('active', active);\n");
        js.append("      dspBtn.innerText = active ? 'DSP: ON' : 'DSP: OFF';\n");
        js.append("    }\n");
        js.append("    if (window.PlayerBridge && window.PlayerBridge.getClarityFilterState) {\n");
        js.append("      syncDspLabel(window.PlayerBridge.getClarityFilterState());\n");
        js.append("    } else { dspBtn.innerText = 'DSP: OFF'; }\n");
        js.append("    dspBtn.addEventListener('click', function() {\n");
        js.append("      if (navigator.vibrate) navigator.vibrate(25);\n");
        js.append("      if (window.PlayerBridge && window.PlayerBridge.toggleClarityFilter) {\n");
        js.append("        var state = window.PlayerBridge.toggleClarityFilter();\n");
        js.append("        syncDspLabel(state);\n");
        js.append("      }\n");
        js.append("    });\n");
        js.append("    topRow.appendChild(dspBtn);\n");
        js.append("  }\n");

        js.append("  var defaultStations = [\n");
        js.append("    { name: 'Radio City Swansea', freq: '1386 AM', band: 'LOCAL', url: 'https://radiocity1386am.radioca.st/stream' },\n");
        js.append("    { name: 'BBC Radio 5 Live', freq: '909 AM', band: 'BBC', url: 'https://lsn.lv/bbcradio.m3u8?station=bbc_radio_five_live&bitrate=128000' },\n");
        js.append("    { name: 'BBC Radio 5 Sports Extra', freq: 'DAB+', band: 'BBC', url: 'https://lsn.lv/bbcradio.m3u8?station=bbc_radio_five_live_sports_extra&bitrate=128000' },\n");
        js.append("    { name: 'talkSPORT', freq: '1089 AM', band: 'SPORT', url: 'https://radio.talksport.com/stream' },\n");
        js.append("    { name: 'talkSPORT 2', freq: 'DAB+', band: 'SPORT', url: 'https://radio.talksport.com/stream2' },\n");
        js.append("    { name: 'BBC Radio 1', freq: '97.9 FM', band: 'BBC', url: 'https://lsn.lv/bbcradio.m3u8?station=bbc_radio_one&bitrate=128000' },\n");
        js.append("    { name: 'BBC Radio 2', freq: '88.8 FM', band: 'BBC', url: 'https://lsn.lv/bbcradio.m3u8?station=bbc_radio_two&bitrate=128000' },\n");
        js.append("    { name: 'BBC Radio 3', freq: '90.9 FM', band: 'BBC', url: 'https://lsn.lv/bbcradio.m3u8?station=bbc_radio_three&bitrate=320000' },\n");
        js.append("    { name: 'BBC Radio 4', freq: '93.5 FM', band: 'BBC', url: 'https://lsn.lv/bbcradio.m3u8?station=bbc_radio_fourfm&bitrate=128000' },\n");
        js.append("    { name: 'BBC Radio 6 Music', freq: 'DAB+', band: 'BBC', url: 'https://lsn.lv/bbcradio.m3u8?station=bbc_6music&bitrate=128000' },\n");
        js.append("    { name: 'BBC World Service', freq: '198 LW', band: 'WORLD', url: 'https://stream.live.vc.bbcmedia.co.uk/bbc_world_service' },\n");
        js.append("    { name: 'BBC Radio Wales', freq: '882 AM', band: 'BBC', url: 'https://lsn.lv/bbcradio.m3u8?station=bbc_radio_wales_fm&bitrate=128000' }\n");
        js.append("  ];\n");
        js.append("  try {\n");
        js.append("    var savedSt = JSON.parse(localStorage.getItem('proscan_radio_stations_v3') || 'null');\n");
        js.append("    window.__radioStations = (Array.isArray(savedSt) && savedSt.length > 0) ? savedSt : defaultStations.slice();\n");
        js.append("    if (!window.__radioStations.some(function(s){ return s.url && s.url.indexOf('radiocity1386am') !== -1; })) {\n");
        js.append("      window.__radioStations.unshift(defaultStations[0]);\n");
        js.append("    }\n");
        js.append("  } catch(e) { window.__radioStations = defaultStations.slice(); }\n");

        js.append("  window.__saveRadioStations = function(exportToDownloads) {\n");
        js.append("    try {\n");
        js.append("      var sJson = JSON.stringify(window.__radioStations);\n");
        js.append("      localStorage.setItem('proscan_radio_stations_v3', sJson);\n");
        js.append("      if (window.PlayerBridge && window.PlayerBridge.saveStationsJson) {\n");
        js.append("        window.PlayerBridge.saveStationsJson(sJson, Boolean(exportToDownloads));\n");
        js.append("      }\n");
        js.append("    } catch(e) {}\n");
        js.append("  };\n");
        js.append("  window.__saveRadioStations(false);\n");

        js.append("  window.onNativeStationsJsonLoaded = function(jsonStr) {\n");
        js.append("    try {\n");
        js.append("      var arr = JSON.parse(jsonStr);\n");
        js.append("      if (Array.isArray(arr) && arr.length > 0) {\n");
        js.append("        window.__radioStations = arr;\n");
        js.append("        window.__radioIdx = 0;\n");
        js.append("        localStorage.setItem('proscan_radio_stations_v3', JSON.stringify(arr));\n");
        js.append("        window.renderPlaylist();\n");
        js.append("        window.selectRadioStationOnly(0);\n");
        js.append("      }\n");
        js.append("    } catch(e) {}\n");
        js.append("  };\n");

        js.append("  window.__resetDefaultRadioStations = function() {\n");
        js.append("    window.__radioStations = defaultStations.slice();\n");
        js.append("    window.__radioIdx = 0;\n");
        js.append("    window.__saveRadioStations(false);\n");
        js.append("    window.renderPlaylist();\n");
        js.append("    window.selectRadioStationOnly(0);\n");
        js.append("  };\n");

        js.append("  window.__isBroadcastRadioMode = false;\n");
        js.append("  window.__radioIdx = 0;\n");
        js.append("  window.__activeRadioUrl = '';\n");

        // Always plays live radio via native Android MediaPlayer with locks so screen sleep never stops it
        js.append("  window.__startHybridLiveStream = function(url) {\n");
        js.append("    window.__stopAllRadioAndNativeAudio();\n");
        js.append("    if (window.PlayerBridge && window.PlayerBridge.playLiveStreamUrl) {\n");
        js.append("      window.PlayerBridge.playLiveStreamUrl(url);\n");
        js.append("    }\n");
        js.append("  };\n");

        js.append("  window.__seenOnPrevVisit = JSON.parse(localStorage.getItem('proscan_seen_visit_ids_v1') || '{}');\n");
        js.append("  window.__playedDriveIds = JSON.parse(localStorage.getItem('proscan_played_recs_v1') || '{}');\n");
        js.append("  window.__filterMode = parseInt(localStorage.getItem('proscan_archive_filter_mode_v1') || '0', 10) || 0;\n");
        js.append("  if (window.__filterMode < 0 || window.__filterMode > 2) window.__filterMode = 0;\n");

        js.append("  var fRow = document.getElementById('date-select').parentNode;\n");
        js.append("  if (fRow && !document.getElementById('btn-filter-unread')) {\n");
        js.append("    fRow.className = 'filter-row-5';\n");
        js.append("    var uBtn = document.createElement('button');\n");
        js.append("    uBtn.id = 'btn-filter-unread'; uBtn.className = 'btn'; uBtn.type = 'button';\n");
        js.append("    uBtn.classList.toggle('active', window.__filterMode !== 0);\n");
        js.append("    uBtn.innerText = window.__filterMode === 0 ? 'ALL' : (window.__filterMode === 1 ? 'NEW' : 'UNPLAY');\n");
        js.append("    fRow.insertBefore(uBtn, document.getElementById('btn-refresh'));\n");
        js.append("    uBtn.addEventListener('click', function() {\n");
        js.append("      window.__filterMode = (window.__filterMode + 1) % 3;\n");
        js.append("      localStorage.setItem('proscan_archive_filter_mode_v1', String(window.__filterMode));\n");
        js.append("      uBtn.classList.toggle('active', window.__filterMode !== 0);\n");
        js.append("      uBtn.innerText = window.__filterMode === 0 ? 'ALL' : (window.__filterMode === 1 ? 'NEW' : 'UNPLAY');\n");
        js.append("      window.renderPlaylist();\n");
        js.append("    });\n");

        js.append("    var rBar = document.createElement('div');\n");
        js.append("    rBar.id = 'radio-crud-row'; rBar.className = 'radio-crud-bar';\n");
        js.append("    rBar.innerHTML = '<button class=\"btn\" type=\"button\" id=\"btn-rad-add\">+ ADD</button><button class=\"btn\" type=\"button\" id=\"btn-rad-edit\" title=\"Tap=Edit / Hold=JSON Backup\">EDIT/JSON</button><button class=\"btn\" type=\"button\" id=\"btn-rad-up\">&#9650; UP</button><button class=\"btn\" type=\"button\" id=\"btn-rad-dn\">&#9660; DN</button><button class=\"btn\" type=\"button\" id=\"btn-rad-del\">DEL</button>';\n");
        js.append("    fRow.parentNode.appendChild(rBar);\n");

        js.append("    document.getElementById('btn-rad-add').addEventListener('click', function() {\n");
        js.append("      if (window.PlayerBridge && window.PlayerBridge.showStationDialog) window.PlayerBridge.showStationDialog(-1, '', '', '');\n");
        js.append("    });\n");
        js.append("    document.getElementById('btn-rad-edit').addEventListener('click', function() {\n");
        js.append("      var stObj = window.__radioStations[window.__radioIdx] || {};\n");
        js.append("      if (window.PlayerBridge && window.PlayerBridge.openStationJsonMenu) {\n");
        js.append("        window.PlayerBridge.openStationJsonMenu(window.__radioIdx, stObj.name || '', stObj.freq || '', stObj.url || '');\n");
        js.append("      }\n");
        js.append("    });\n");
        js.append("    document.getElementById('btn-rad-up').addEventListener('click', function() {\n");
        js.append("      var idx = window.__radioIdx;\n");
        js.append("      if (idx <= 0 || idx >= window.__radioStations.length) return;\n");
        js.append("      if (navigator.vibrate) navigator.vibrate(15);\n");
        js.append("      var tmp = window.__radioStations[idx - 1];\n");
        js.append("      window.__radioStations[idx - 1] = window.__radioStations[idx];\n");
        js.append("      window.__radioStations[idx] = tmp;\n");
        js.append("      window.__radioIdx = idx - 1;\n");
        js.append("      window.__saveRadioStations(false);\n");
        js.append("      window.renderPlaylist();\n");
        js.append("    });\n");
        js.append("    document.getElementById('btn-rad-dn').addEventListener('click', function() {\n");
        js.append("      var idx = window.__radioIdx;\n");
        js.append("      if (idx < 0 || idx >= window.__radioStations.length - 1) return;\n");
        js.append("      if (navigator.vibrate) navigator.vibrate(15);\n");
        js.append("      var tmp = window.__radioStations[idx + 1];\n");
        js.append("      window.__radioStations[idx + 1] = window.__radioStations[idx];\n");
        js.append("      window.__radioStations[idx] = tmp;\n");
        js.append("      window.__radioIdx = idx + 1;\n");
        js.append("      window.__saveRadioStations(false);\n");
        js.append("      window.renderPlaylist();\n");
        js.append("    });\n");
        js.append("    document.getElementById('btn-rad-del').addEventListener('click', function() {\n");
        js.append("      if (window.__radioStations.length <= 1) return;\n");
        js.append("      if (navigator.vibrate) navigator.vibrate(25);\n");
        js.append("      window.__radioStations.splice(window.__radioIdx, 1);\n");
        js.append("      if (window.__radioIdx >= window.__radioStations.length) window.__radioIdx = window.__radioStations.length - 1;\n");
        js.append("      window.__saveRadioStations(false);\n");
        js.append("      window.renderPlaylist();\n");
        js.append("    });\n");
        js.append("  }\n");

        js.append("  window.onNativeStationSaved = function(editIdx, name, freq, url) {\n");
        js.append("    if (!name || !url) return;\n");
        js.append("    var item = { name: name.trim(), freq: (freq || 'LIVE').trim(), band: 'USER', url: url.trim() };\n");
        js.append("    if (editIdx >= 0 && editIdx < window.__radioStations.length) {\n");
        js.append("      window.__radioStations[editIdx] = item;\n");
        js.append("      window.__radioIdx = editIdx;\n");
        js.append("    } else {\n");
        js.append("      window.__radioStations.unshift(item);\n");
        js.append("      window.__radioIdx = 0;\n");
        js.append("    }\n");
        js.append("    window.__saveRadioStations(false);\n");
        js.append("    window.renderPlaylist();\n");
        js.append("    window.selectRadioStationOnly(window.__radioIdx);\n");
        js.append("  };\n");

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
        js.append("      var leftWing = document.createElement('div'); leftWing.className = 'hw-side-panel'; leftWing.style.cssText = 'left:12px;top:36px;';\n");
        js.append("      leftWing.innerHTML = '<div class=\"hw-pill\" style=\"color:#38bdf8;\">MASTER TEMPO</div><div class=\"hw-pill\">JOG: VINYL</div><div class=\"hw-pill\">CUE / LOOP</div><div class=\"hw-vu-dial\"><div class=\"hw-vu-needle\" id=\"cd-vu-l\"></div></div>';\n");
        js.append("      var rightWing = document.createElement('div'); rightWing.className = 'hw-side-panel'; rightWing.style.cssText = 'right:14px;top:36px;';\n");
        js.append("      rightWing.innerHTML = '<div class=\"hw-pill\">TEMPO ±8%</div><div class=\"hw-fader-track\"><div class=\"hw-fader-knob\"></div></div>';\n");
        js.append("      dCd.appendChild(leftWing); dCd.appendChild(rightWing);\n");
        js.append("    }\n");

        js.append("    var dVinyl = document.getElementById('deck-vinyl');\n");
        js.append("    if (dVinyl) {\n");
        js.append("      var ttTopLeft = document.createElement('div'); ttTopLeft.className = 'hw-side-panel'; ttTopLeft.style.cssText = 'left:8px;top:36px;align-items:flex-start;';\n");
        js.append("      ttTopLeft.innerHTML = '<div class=\"hw-pill\" style=\"font-size:6.5px;color:#cbd5e1;padding:2px 5px;\">SL-1200MK2</div><div style=\"display:flex;align-items:center;gap:4px;font-family:monospace;font-size:7px;color:#22c55e;font-weight:800;\"><span style=\"width:6px;height:6px;border-radius:50%;background:#22c55e;box-shadow:0 0 6px #22c55e;display:inline-block;\"></span>QUARTZ</div>';\n");
        js.append("      var ttLeft = document.createElement('div'); ttLeft.className = 'hw-side-panel'; ttLeft.style.cssText = 'left:8px;bottom:24px;align-items:flex-start;background:rgba(0,0,0,0.55);padding:4px 5px;border-radius:5px;border:1px solid rgba(255,255,255,0.1);';\n");
        js.append("      ttLeft.innerHTML = '<div class=\"hw-pill\" style=\"border-color:#38bdf8;color:#f8fafc;padding:2px 4px;font-size:6.5px;\">START/STOP</div><div style=\"display:flex;gap:3px;\"><div class=\"hw-pill\" style=\"color:#ef4444;border-color:#ef4444;font-size:6.5px;padding:1px 4px;\">33</div><div class=\"hw-pill\" style=\"font-size:6.5px;padding:1px 4px;\">45</div></div>';\n");
        js.append("      var ttRight = document.createElement('div'); ttRight.className = 'hw-side-panel'; ttRight.style.cssText = 'right:10px;bottom:26px;';\n");
        js.append("      ttRight.innerHTML = '<div class=\"hw-pill\">PITCH ±8</div><div class=\"hw-fader-track\" style=\"height:84px;\"><div class=\"hw-fader-knob\" style=\"top:36px;\"></div></div>';\n");
        js.append("      dVinyl.appendChild(ttTopLeft); dVinyl.appendChild(ttLeft); dVinyl.appendChild(ttRight);\n");
        js.append("    }\n");

        js.append("    var rStage = document.querySelector('.r2r-stage');\n");
        js.append("    if (rStage) {\n");
        js.append("      var reels = rStage.querySelectorAll('.r2r-reel');\n");
        js.append("      if (reels.length >= 2) { reels[0].id = 'r2r-reel-left'; reels[1].id = 'r2r-reel-right'; }\n");
        js.append("      var bridge = document.createElement('div'); bridge.className = 'r2r-lower-bridge';\n");
        js.append("      bridge.innerHTML = '' +\n");
        js.append("        '<div style=\"display:flex;align-items:center;gap:6px;\">' +\n");
        js.append("          '<div class=\"hw-vu-dial\"><div class=\"hw-vu-needle\" id=\"r2r-vu-l\"></div></div>' +\n");
        js.append("          '<div style=\"font-family:monospace;font-size:7px;font-weight:800;color:#94a3b8;line-height:1.2;\">CH 1<br><span style=\"color:#22c55e;\">BIAS</span></div>' +\n");
        js.append("        '</div>' +\n");
        js.append("        '<div style=\"font-family:monospace;font-size:7.5px;font-weight:900;color:#e2e8f0;letter-spacing:1px;text-align:center;\">NAGRA IV-S<br><span style=\"font-size:6.5px;color:#38bdf8;\">15 IPS // MASTER</span></div>' +\n");
        js.append("        '<div style=\"display:flex;align-items:center;gap:6px;\">' +\n");
        js.append("          '<div style=\"font-family:monospace;font-size:7px;font-weight:800;color:#94a3b8;line-height:1.2;text-align:right;\">CH 2<br><span style=\"color:#facc15;\">PEAK</span></div>' +\n");
        js.append("          '<div class=\"hw-vu-dial\"><div class=\"hw-vu-needle\" id=\"r2r-vu-r\"></div></div>' +\n");
        js.append("        '</div>';\n");
        js.append("      rStage.appendChild(bridge);\n");
        js.append("    }\n");
        js.append("  }\n");

        js.append("  window.__audioLevel = 0;\n");
        js.append("  window.__audioProgress = 0;\n");

        js.append("  window.onNativeTelemetry = function(sec, totSec, prog, level) {\n");
        js.append("    window.__audioProgress = prog || 0;\n");
        js.append("    window.__audioLevel = level || 0;\n");
        js.append("    if (!window.__isBroadcastRadioMode && typeof window.onNativeTimeUpdate === 'function') window.onNativeTimeUpdate(sec, totSec);\n");
        js.append("    if (window.__isBroadcastRadioMode) {\n");
        js.append("      document.getElementById('time-cur').innerText = fmtSec(sec);\n");
        js.append("      document.getElementById('time-tot').innerText = 'LIVE';\n");
        js.append("    }\n");
        js.append("    var arm = document.querySelector('.tt-tonearm');\n");
        js.append("    if (arm) arm.style.transform = 'rotate(' + (20 + window.__audioProgress * 18).toFixed(2) + 'deg)';\n");
        js.append("    var spL = document.querySelector('.c-spool.left'), spR = document.querySelector('.c-spool.right');\n");
        js.append("    if (spL && spR) {\n");
        js.append("      var szL = 64 - (window.__audioProgress * 22), szR = 42 + (window.__audioProgress * 22);\n");
        js.append("      spL.style.width = szL + 'px'; spL.style.height = szL + 'px';\n");
        js.append("      spR.style.width = szR + 'px'; spR.style.height = szR + 'px';\n");
        js.append("    }\n");
        js.append("    var rrL = document.getElementById('r2r-reel-left'), rrR = document.getElementById('r2r-reel-right');\n");
        js.append("    if (rrL && rrR) {\n");
        js.append("      var pL = Math.round(72 - window.__audioProgress * 32), pR = Math.round(40 + window.__audioProgress * 32);\n");
        js.append("      rrL.style.boxShadow = 'inset 0 0 0 ' + (28 - pL/3) + 'px #18181b, 0 6px 14px rgba(0,0,0,0.9)';\n");
        js.append("      rrR.style.boxShadow = 'inset 0 0 0 ' + (28 - pR/3) + 'px #18181b, 0 6px 14px rgba(0,0,0,0.9)';\n");
        js.append("    }\n");
        js.append("    var deg = -36 + (window.__audioLevel * 72);\n");
        js.append("    ['cd-vu-l','r2r-vu-l','r2r-vu-r'].forEach(function(id, i){\n");
        js.append("      var ndl = document.getElementById(id);\n");
        js.append("      if (ndl) ndl.style.transform = 'rotate(' + (deg + (i%2===0?3:-3)).toFixed(1) + 'deg)';\n");
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

        js.append("  var oldCanvas = document.getElementById('hack-canvas');\n");
        js.append("  if (oldCanvas) {\n");
        js.append("    var hCanvas = oldCanvas.cloneNode(true);\n");
        js.append("    oldCanvas.parentNode.replaceChild(hCanvas, oldCanvas);\n");
        js.append("    var hCtx = hCanvas.getContext('2d'), hPhase = 0, smoothLvl = 0.02;\n");
        js.append("    var barHeights = [], peaks = [];\n");
        js.append("    for (var p=0; p<28; p++) { barHeights.push(3); peaks.push(3); }\n");
        js.append("    function drawProSigintScope() {\n");
        js.append("      requestAnimationFrame(drawProSigintScope);\n");
        js.append("      if (document.documentElement.getAttribute('data-deck') !== 'hack') return;\n");
        js.append("      var W = hCanvas.width, H = hCanvas.height;\n");
        js.append("      hCtx.clearRect(0, 0, W, H);\n");
        js.append("      var rawTarget = isPlayingNow ? Math.max(0.06, window.__audioLevel * 0.85) : 0.015;\n");
        js.append("      smoothLvl += (rawTarget - smoothLvl) * 0.12;\n");
        js.append("      hCtx.strokeStyle = 'rgba(57,255,20,0.11)'; hCtx.lineWidth = 1;\n");
        js.append("      hCtx.beginPath();\n");
        js.append("      for (var gx = 0; gx < W; gx += 38) { hCtx.moveTo(gx, 0); hCtx.lineTo(gx, H); }\n");
        js.append("      for (var gy = 0; gy < H; gy += 28) { hCtx.moveTo(0, gy); hCtx.lineTo(W, gy); }\n");
        js.append("      hCtx.stroke();\n");
        js.append("      hCtx.fillStyle = '#38bdf8'; hCtx.font = 'bold 8.5px monospace';\n");
        js.append("      var dbm = isPlayingNow ? (-52 + Math.round(smoothLvl * 38)) : -98;\n");
        js.append("      hCtx.fillText('RF: ' + dbm + ' dBm  //  BW: 12.5kHz  //  DEMOD: NFM', 8, 12);\n");
        js.append("      hCtx.fillStyle = isPlayingNow ? '#39ff14' : '#64748b';\n");
        js.append("      hCtx.fillText(isPlayingNow ? 'CARRIER: LOCKED' : 'CARRIER: STANDBY', W - 96, 12);\n");
        js.append("      hCtx.strokeStyle = 'rgba(56,189,248,0.38)'; hCtx.lineWidth = 1.4;\n");
        js.append("      hCtx.beginPath();\n");
        js.append("      for (var x2 = 0; x2 < W; x2 += 3) {\n");
        js.append("        var env2 = Math.sin((x2 / W) * Math.PI);\n");
        js.append("        var y2 = 54 + Math.sin(x2 * 0.045 - hPhase * 0.65) * env2 * (smoothLvl * 26);\n");
        js.append("        if (x2 === 0) hCtx.moveTo(x2, y2); else hCtx.lineTo(x2, y2);\n");
        js.append("      }\n");
        js.append("      hCtx.stroke();\n");
        js.append("      hCtx.strokeStyle = '#39ff14'; hCtx.lineWidth = 2.2;\n");
        js.append("      hCtx.beginPath();\n");
        js.append("      for (var x = 0; x < W; x += 2) {\n");
        js.append("        var env = Math.sin((x / W) * Math.PI);\n");
        js.append("        var w1 = Math.sin(x * 0.04 + hPhase) * Math.cos(x * 0.018 - hPhase * 0.45);\n");
        js.append("        var w2 = Math.sin(x * 0.09 - hPhase * 0.8) * 0.25;\n");
        js.append("        var y = 54 + ((w1 + w2) * env * (smoothLvl * 36));\n");
        js.append("        if (x === 0) hCtx.moveTo(x, y); else hCtx.lineTo(x, y);\n");
        js.append("      }\n");
        js.append("      hCtx.stroke();\n");
        js.append("      var barW = 10, gap = 3, startX = 10, baseY = H - 6;\n");
        js.append("      for (var b = 0; b < 28; b++) {\n");
        js.append("        var bell = Math.sin(((b + 1) / 29) * Math.PI);\n");
        js.append("        var wave = 0.55 + 0.45 * Math.sin(b * 0.42 + hPhase * 0.85) * Math.cos(b * 0.21 - hPhase * 0.5);\n");
        js.append("        var goalH = isPlayingNow ? Math.max(3, Math.min(42, bell * wave * smoothLvl * 52)) : 2.5;\n");
        js.append("        barHeights[b] += (goalH - barHeights[b]) * 0.14;\n");
        js.append("        var curH = barHeights[b];\n");
        js.append("        if (curH >= peaks[b]) peaks[b] = curH;\n");
        js.append("        else peaks[b] = Math.max(2.5, peaks[b] - 0.18);\n");
        js.append("        var bx = startX + b * (barW + gap);\n");
        js.append("        var grad = hCtx.createLinearGradient(0, baseY - 42, 0, baseY);\n");
        js.append("        grad.addColorStop(0, '#facc15'); grad.addColorStop(0.4, '#22c55e'); grad.addColorStop(1, '#15803d');\n");
        js.append("        hCtx.fillStyle = grad;\n");
        js.append("        hCtx.fillRect(bx, baseY - curH, barW, curH);\n");
        js.append("        hCtx.fillStyle = 'rgba(56,189,248,0.85)';\n");
        js.append("        hCtx.fillRect(bx, baseY - peaks[b] - 2, barW, 1.5);\n");
        js.append("      }\n");
        js.append("      hPhase += isPlayingNow ? (0.055 + smoothLvl * 0.065) : 0.02;\n");
        js.append("    }\n");
        js.append("    drawProSigintScope();\n");
        js.append("  }\n");
js.append("  window.selectRadioStationOnly = function(idx) {\n");
        js.append("    if (idx < 0 || idx >= window.__radioStations.length) return;\n");
        js.append("    window.__radioIdx = idx;\n");
        js.append("    var stObj = window.__radioStations[idx];\n");
        js.append("    document.getElementById('track-main-readout').innerText = 'TUNED: ' + stObj.name + ' • ' + stObj.freq;\n");
        js.append("    document.getElementById('cassette-lbl-left').innerText = stObj.name;\n");
        js.append("    document.getElementById('cassette-lbl-right').innerText = stObj.freq + ' (LIVE)';\n");
        js.append("    var bCh = document.getElementById('badge-ch-name'), bFr = document.getElementById('badge-ch-freq');\n");
        js.append("    if (bCh) bCh.innerText = 'TUNER // ' + stObj.name;\n");
        js.append("    if (bFr) bFr.innerText = stObj.freq;\n");
        js.append("    document.querySelectorAll('.radio-row').forEach(function(el, i){ el.classList.toggle('selected', i === idx); });\n");
        js.append("  };\n");

        js.append("  window.playRadioStationIndex = function(idx) {\n");
        js.append("    if (idx < 0 || idx >= window.__radioStations.length) return;\n");
        js.append("    window.__stopAllRadioAndNativeAudio();\n");
        js.append("    window.__radioIdx = idx;\n");
        js.append("    var stObj = window.__radioStations[idx];\n");
        js.append("    window.__activeRadioUrl = stObj.url;\n");
        js.append("    document.getElementById('track-main-readout').innerText = 'LIVE: ' + stObj.name + ' • ' + stObj.freq;\n");
        js.append("    document.getElementById('cassette-lbl-left').innerText = stObj.name;\n");
        js.append("    document.getElementById('cassette-lbl-right').innerText = stObj.freq + ' (ON AIR)';\n");
        js.append("    var bCh = document.getElementById('badge-ch-name'), bFr = document.getElementById('badge-ch-freq');\n");
        js.append("    if (bCh) bCh.innerText = 'ON AIR: ' + stObj.name;\n");
        js.append("    if (bFr) bFr.innerText = stObj.freq;\n");
        js.append("    window.renderPlaylist();\n");
        js.append("    var myLaunch = window.__streamLaunchToken;\n");
        js.append("    if (isTtsCaption && window.PlayerBridge) {\n");
        js.append("      window.PlayerBridge.speakCaption(stObj.name);\n");
        js.append("      setTimeout(function() {\n");
        js.append("        if (window.__streamLaunchToken === myLaunch) window.__startHybridLiveStream(stObj.url);\n");
        js.append("      }, 1300);\n");
        js.append("    } else {\n");
        js.append("      window.__startHybridLiveStream(stObj.url);\n");
        js.append("    }\n");
        js.append("  };\n");

        js.append("  window.setAppMode = function(radioMode, skipAnim) {\n");
        js.append("    var dw = document.getElementById('device-wrapper');\n");
        js.append("    if (dw && !skipAnim) dw.classList.add('fading-out');\n");
        js.append("    setTimeout(function() {\n");
        js.append("      window.__isBroadcastRadioMode = Boolean(radioMode);\n");
        js.append("      window.__stopAllRadioAndNativeAudio();\n");
        js.append("      setPlayingUI(false);\n");
        js.append("      var swBtn = document.getElementById('btn-switch-radio-archive');\n");
        js.append("      var subBrand = document.getElementById('brand-sub-mode');\n");
        js.append("      var hdr = document.querySelector('.screen-log-header');\n");
        js.append("      var fRowEl = document.getElementById('date-select').parentNode;\n");
        js.append("      var cRowEl = document.getElementById('radio-crud-row');\n");
        js.append("      if (window.__isBroadcastRadioMode) {\n");
        js.append("        if (swBtn) swBtn.innerHTML = 'ARCHIVE';\n");
        js.append("        if (subBrand) subBrand.innerText = 'TUNER';\n");
        js.append("        document.getElementById('deck-mode-hud').innerText = 'BROADCAST // DAB+ FM TUNER';\n");
        js.append("        if (hdr) {\n");
        js.append("          hdr.style.gridTemplateColumns = '36px 68px 48px 1fr';\n");
        js.append("          hdr.innerHTML = '<span>CH</span><span>BAND</span><span>MODE</span><span>STATION NAME</span>';\n");
        js.append("        }\n");
        js.append("        if (fRowEl) fRowEl.style.display = 'none';\n");
        js.append("        if (cRowEl) cRowEl.style.display = 'grid';\n");
        js.append("        window.renderPlaylist();\n");
        js.append("        window.selectRadioStationOnly(window.__radioIdx || 0);\n");
        js.append("      } else {\n");
        js.append("        if (swBtn) swBtn.innerHTML = 'RADIO &#9654;';\n");
        js.append("        if (subBrand) subBrand.innerText = 'ARCHIVE';\n");
        js.append("        if (typeof applyDeck === 'function') applyDeck(deckIdx);\n");
        js.append("        if (hdr) {\n");
        js.append("          hdr.style.gridTemplateColumns = '62px 52px 58px 34px 1fr';\n");
        js.append("          hdr.innerHTML = '<span>DATE</span><span>TIME</span><span>FREQ</span><span>DUR</span><span>NAME</span>';\n");
        js.append("        }\n");
        js.append("        if (fRowEl) fRowEl.style.display = 'grid';\n");
        js.append("        if (cRowEl) cRowEl.style.display = 'none';\n");
        js.append("        window.__activePlayingFileId = '';\n");
        js.append("        window.renderPlaylist();\n");
        js.append("        if (curIdx >= 0 && curIdx < filteredRecs.length) {\n");
        js.append("          window.selectIndexOnly(curIdx);\n");
        js.append("        } else if (filteredRecs.length > 0) {\n");
        js.append("          window.selectIndexOnly(0);\n");
        js.append("        } else {\n");
        js.append("          document.getElementById('track-main-readout').innerText = 'SELECT A RECORDING';\n");
        js.append("          document.getElementById('cassette-lbl-left').innerText = 'PROSCAN C-90';\n");
        js.append("          document.getElementById('cassette-lbl-right').innerText = 'HI-BIAS CrO2';\n");
        js.append("          var bCh = document.getElementById('badge-ch-name'), bFr = document.getElementById('badge-ch-freq');\n");
        js.append("          if (bCh) bCh.innerText = 'READY // SELECT TRACK';\n");
        js.append("          if (bFr) bFr.innerText = '---.----';\n");
        js.append("          document.getElementById('time-cur').innerText = '00:00';\n");
        js.append("          document.getElementById('time-tot').innerText = '00:00';\n");
        js.append("        }\n");
        js.append("      }\n");
        js.append("      if (dw) dw.classList.remove('fading-out');\n");
        js.append("    }, skipAnim ? 0 : 150);\n");
        js.append("  };\n");

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

        js.append("  window.markCurrentListAsVisited = function() {\n");
        js.append("    try {\n");
        js.append("      allRecs.forEach(function(r){ window.__seenOnPrevVisit[r.fileId || r.fileName] = true; });\n");
        js.append("      localStorage.setItem('proscan_seen_visit_ids_v1', JSON.stringify(window.__seenOnPrevVisit));\n");
        js.append("    } catch(e) {}\n");
        js.append("  };\n");

        js.append("  window.renderPlaylist = function() {\n");
        js.append("    var box = document.getElementById('playlist-box');\n");
        js.append("    var prevScroll = box.scrollTop;\n");
        js.append("    if (window.__isBroadcastRadioMode) {\n");
        js.append("      document.getElementById('rec-count-hud').innerText = window.__radioStations.length + ' STATIONS';\n");
        js.append("      var rHtml = '';\n");
        js.append("      for (var j = 0; j < window.__radioStations.length; j++) {\n");
        js.append("        var st = window.__radioStations[j];\n");
        js.append("        var isPl = (st.url === window.__activeRadioUrl && isPlayingNow);\n");
        js.append("        var rCls = (isPl ? 'playing ' : '') + (j === window.__radioIdx ? 'selected' : '');\n");
        js.append("        var chNum = 'P' + String(j + 1).padStart(2, '0');\n");
        js.append("        rHtml += '<div class=\"radio-row ' + rCls + '\" data-ridx=\"' + j + '\">' +\n");
        js.append("          '<span>' + chNum + '</span><span>' + (st.freq || 'DAB+') + '</span><span>LIVE</span>' +\n");
        js.append("          '<span title=\"' + st.name + '\">' + st.name + '</span></div>';\n");
        js.append("      }\n");
        js.append("      box.innerHTML = rHtml; box.scrollTop = prevScroll;\n");
        js.append("      box.querySelectorAll('.radio-row').forEach(function(el) {\n");
        js.append("        el.addEventListener('click', function() {\n");
        js.append("          if (window.__playlistIsDragging) return;\n");
        js.append("          if (navigator.vibrate) navigator.vibrate(15);\n");
        js.append("          var idx = parseInt(el.getAttribute('data-ridx'), 10);\n");
        js.append("          window.playRadioStationIndex(idx);\n");
        js.append("        });\n");
        js.append("      });\n");
        js.append("      return;\n");
        js.append("    }\n");

        js.append("    var selDate = document.getElementById('date-select').value;\n");
        js.append("    var q = (document.getElementById('search-box').value || '').trim().toLowerCase();\n");
        js.append("    var hasPrevVisitData = Object.keys(window.__seenOnPrevVisit).length > 0;\n");
        js.append("    if (!hasPrevVisitData && allRecs.length > 0) {\n");
        js.append("      for (var k = 10; k < allRecs.length; k++) {\n");
        js.append("        window.__seenOnPrevVisit[allRecs[k].fileId || allRecs[k].fileName] = true;\n");
        js.append("      }\n");
        js.append("    }\n");
        js.append("    filteredRecs = allRecs.filter(function(r) {\n");
        js.append("      var id = r.fileId || r.fileName;\n");
        js.append("      var isCurrentlyActive = (r.fileId && r.fileId === window.__activePlayingFileId && isPlayingNow);\n");
        js.append("      if (!isCurrentlyActive) {\n");
        js.append("        if (window.__filterMode === 1 && window.__seenOnPrevVisit[id]) return false;\n");
        js.append("        if (window.__filterMode === 2 && window.__playedDriveIds[id]) return false;\n");
        js.append("      }\n");
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
        js.append("        if (window.__playlistIsDragging) return;\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(15);\n");
        js.append("        window.selectIndexOnly(parseInt(el.getAttribute('data-idx'), 10));\n");
        js.append("      });\n");
        js.append("    });\n");
        js.append("  };\n");

        js.append("  var origPlayIndex = window.playIndex;\n");
        js.append("  window.playIndex = function(idx) {\n");
        js.append("    if (idx < 0 || idx >= filteredRecs.length) return;\n");
        js.append("    window.__stopAllRadioAndNativeAudio();\n");
        js.append("    var r = filteredRecs[idx];\n");
        js.append("    var id = r.fileId || r.fileName;\n");
        js.append("    window.__activePlayingRecordKey = id;\n");
        js.append("    var bCh = document.getElementById('badge-ch-name'), bFr = document.getElementById('badge-ch-freq');\n");
        js.append("    if (bCh) bCh.innerText = 'PLAYING: ' + r.name + ' (' + r.timeStr + ')';\n");
        js.append("    if (bFr) bFr.innerText = r.freq;\n");
        js.append("    origPlayIndex(idx);\n");
        js.append("  };\n");

        js.append("  window.onNativePlayEnded = function() {\n");
        js.append("    setPlayingUI(false);\n");
        js.append("    if (window.__isBroadcastRadioMode) { renderPlaylist(); return; }\n");
        js.append("    if (window.__activePlayingRecordKey) {\n");
        js.append("      window.__playedDriveIds[window.__activePlayingRecordKey] = true;\n");
        js.append("      window.__seenOnPrevVisit[window.__activePlayingRecordKey] = true;\n");
        js.append("      localStorage.setItem('proscan_played_recs_v1', JSON.stringify(window.__playedDriveIds));\n");
        js.append("      localStorage.setItem('proscan_seen_visit_ids_v1', JSON.stringify(window.__seenOnPrevVisit));\n");
        js.append("    }\n");
        js.append("    window.__activePlayingFileId = '';\n");
        js.append("    renderPlaylist();\n");
        js.append("    if (window.__isLoopMode && curIdx >= 0) playIndex(curIdx);\n");
        js.append("    else if (isCont && curIdx > 0) playIndex(curIdx - 1);\n");
        js.append("  };\n");

        js.append("  var oldPlayBtn = document.getElementById('btn-play');\n");
        js.append("  if (oldPlayBtn) {\n");
        js.append("    var newPlayBtn = oldPlayBtn.cloneNode(true);\n");
        js.append("    oldPlayBtn.parentNode.replaceChild(newPlayBtn, oldPlayBtn);\n");
        js.append("    newPlayBtn.addEventListener('click', function() {\n");
        js.append("      if (window.__isBroadcastRadioMode) {\n");
        js.append("        var stObj = window.__radioStations[window.__radioIdx];\n");
        js.append("        if (!stObj) return;\n");
        js.append("        if (window.__activeRadioUrl === stObj.url && isPlayingNow) {\n");
        js.append("          window.__stopAllRadioAndNativeAudio();\n");
        js.append("          setPlayingUI(false); renderPlaylist();\n");
        js.append("        } else {\n");
        js.append("          window.playRadioStationIndex(window.__radioIdx);\n");
        js.append("        }\n");
        js.append("        return;\n");
        js.append("      }\n");
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

        js.append("  var oldStopBtn = document.getElementById('btn-stop');\n");
        js.append("  if (oldStopBtn) {\n");
        js.append("    oldStopBtn.addEventListener('click', function() {\n");
        js.append("      window.__stopAllRadioAndNativeAudio();\n");
        js.append("    });\n");
        js.append("  }\n");

        js.append("  var oldNextBtn = document.getElementById('btn-next');\n");
        js.append("  if (oldNextBtn) {\n");
        js.append("    var newNextBtn = oldNextBtn.cloneNode(true);\n");
        js.append("    oldNextBtn.parentNode.replaceChild(newNextBtn, oldNextBtn);\n");
        js.append("    newNextBtn.addEventListener('click', function() {\n");
        js.append("      if (window.__isBroadcastRadioMode) {\n");
        js.append("        if (window.__radioStations.length > 0) {\n");
        js.append("          var nIdx = (window.__radioIdx + 1) % window.__radioStations.length;\n");
        js.append("          window.playRadioStationIndex(nIdx);\n");
        js.append("        }\n");
        js.append("      } else if (curIdx > 0) {\n");
        js.append("        playIndex(curIdx - 1);\n");
        js.append("      }\n");
        js.append("    });\n");
        js.append("  }\n");

        js.append("  var oldPrevBtn = document.getElementById('btn-prev');\n");
        js.append("  if (oldPrevBtn) {\n");
        js.append("    var newPrevBtn = oldPrevBtn.cloneNode(true);\n");
        js.append("    oldPrevBtn.parentNode.replaceChild(newPrevBtn, oldPrevBtn);\n");
        js.append("    newPrevBtn.addEventListener('click', function() {\n");
        js.append("      if (window.__isBroadcastRadioMode) {\n");
        js.append("        if (window.__radioStations.length > 0) {\n");
        js.append("          var pIdx = (window.__radioIdx - 1 + window.__radioStations.length) % window.__radioStations.length;\n");
        js.append("          window.playRadioStationIndex(pIdx);\n");
        js.append("        }\n");
        js.append("      } else if (curIdx + 1 < filteredRecs.length) {\n");
        js.append("        playIndex(curIdx + 1);\n");
        js.append("      }\n");
        js.append("    });\n");
        js.append("  }\n");

        if (openInRadio) {
            js.append("  window.setAppMode(true, true);\n");
        }
        js.append("})();");

        if (playerWeb != null) playerWeb.evaluateJavascript(js.toString(), null);
    }

    private void stopNativePlayer() {
        releaseEqualizer();
        releasePlaybackLocks();
        currentStreamReqId++;
        mainHandler.removeCallbacks(progressTicker);
        isLiveRadioStream = false;
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

    private String resolveRedirects(String urlStr) {
        String current = urlStr;
        for (int hop = 0; hop < 6; hop++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/124.0.0.0 Mobile");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null || loc.isEmpty()) break;
                    current = new URL(new URL(current), loc).toString();
                } else {
                    conn.disconnect();
                    break;
                }
            } catch (Exception e) {
                break;
            }
        }
        return current;
    }

    private void playLiveRadioUrlNatively(final String streamUrl) {
        stopNativePlayer();
        acquirePlaybackLocks();
        final int reqId = ++currentStreamReqId;
        audioFetchExecutor.execute(() -> {
            final String resolvedUrl = resolveRedirects(streamUrl);
            if (reqId != currentStreamReqId) return;
            mainHandler.post(() -> {
                if (reqId != currentStreamReqId) return;
                try {
                    if (nativePlayer != null) {
                        try { nativePlayer.reset(); nativePlayer.release(); } catch (Exception ignored) {}
                        nativePlayer = null;
                    }
                    isLiveRadioStream = true;
                    nativePlayer = new MediaPlayer();
                    if (Build.VERSION.SDK_INT >= 21) {
                        nativePlayer.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build());
                    }
                    Map<String, String> headers = new HashMap<>();
                    headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/124.0.0.0 Mobile");
                    nativePlayer.setDataSource(PlayerActivity.this, Uri.parse(resolvedUrl), headers);
                    nativePlayer.setOnPreparedListener(mp -> {
                        if (reqId != currentStreamReqId) {
                            try { mp.stop(); mp.release(); } catch (Exception ignored) {}
                            return;
                        }
                        setupEqualizer(mp.getAudioSessionId());
                        mp.start();
                        if (playerWeb != null) playerWeb.evaluateJavascript("if(window.onNativePlayStarted)window.onNativePlayStarted();", null);
                        mainHandler.post(progressTicker);
                    });
                    nativePlayer.setOnErrorListener((mp, what, extra) -> {
                        if (reqId == currentStreamReqId) {
                            stopNativePlayer();
                            if (playerWeb != null) playerWeb.evaluateJavascript("if(window.onNativePlayError)window.onNativePlayError('STREAM OFFLINE');", null);
                        }
                        return true;
                    });
                    nativePlayer.prepareAsync();
                } catch (Exception e) {
                    if (reqId == currentStreamReqId && playerWeb != null) {
                        playerWeb.evaluateJavascript("if(window.onNativePlayError)window.onNativePlayError('STREAM ERROR');", null);
                    }
                }
            });
        });
    }

    private void playDriveFileIdNatively(final String fileId) {
        stopNativePlayer();
        acquirePlaybackLocks();
        final int reqId = ++currentStreamReqId;
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
                    if (reqId != currentStreamReqId) {
                        fos.close(); is.close(); conn.disconnect();
                        return;
                    }
                    fos.write(buf, 0, n);
                }
                fos.close();
                is.close();
                conn.disconnect();
                if (reqId != currentStreamReqId) return;
                final float[] env = extractMp3AmplitudeEnvelope(tempMp3);
                mainHandler.post(() -> {
                    if (reqId != currentStreamReqId) return;
                    try {
                        if (nativePlayer != null) {
                            try { nativePlayer.reset(); nativePlayer.release(); } catch (Exception ignored) {}
                            nativePlayer = null;
                        }
                        isLiveRadioStream = false;
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
                            if (reqId != currentStreamReqId) {
                                try { mp.stop(); mp.release(); } catch (Exception ignored) {}
                                return;
                            }
                            setupEqualizer(mp.getAudioSessionId());
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
                    if (reqId == currentStreamReqId && playerWeb != null) {
                        playerWeb.evaluateJavascript("if(window.onNativePlayError)window.onNativePlayError('STREAM ERROR');", null);
                    }
                });
            }
        });
    }

    private void openAddOrEditStationDialog(final int editIdx, String curName, String curFreq, String curUrl) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        TextView l1 = new TextView(this);
        l1.setText("Station Name (e.g. BBC Radio 5 Live):");
        layout.addView(l1);
        final EditText nameInput = new EditText(this);
        nameInput.setText(curName != null ? curName : "");
        layout.addView(nameInput);

        TextView l2 = new TextView(this);
        l2.setText("\nFreq / Band Label (e.g. 909 AM or DAB+):");
        layout.addView(l2);
        final EditText freqInput = new EditText(this);
        freqInput.setText(curFreq != null && !curFreq.isEmpty() ? curFreq : "DAB+");
        layout.addView(freqInput);

        TextView l3 = new TextView(this);
        l3.setText("\nDirect Stream URL (.mp3 / .aac / .m3u8):");
        layout.addView(l3);
        final EditText urlInput = new EditText(this);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(curUrl != null ? curUrl : "");
        layout.addView(urlInput);

        new AlertDialog.Builder(this)
                .setTitle(editIdx >= 0 ? "Edit Radio Station" : "Add Radio Station")
                .setView(layout)
                .setPositiveButton("Save Station", (d, w) -> {
                    String n = nameInput.getText().toString().trim();
                    String f = freqInput.getText().toString().trim();
                    String u = urlInput.getText().toString().trim();
                    if (n.isEmpty() || u.isEmpty()) {
                        Toast.makeText(PlayerActivity.this, "Name and Stream URL required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String jsCall = "if(window.onNativeStationSaved)window.onNativeStationSaved("
                            + editIdx + "," + JSONObject.quote(n) + "," + JSONObject.quote(f) + "," + JSONObject.quote(u) + ");";
                    if (playerWeb != null) playerWeb.evaluateJavascript(jsCall, null);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public class PlayerBridge {
        @JavascriptInterface
        public String getServerUrl() { return serverBaseUrl; }

        @JavascriptInterface
        public String getBatteryStatus() { return getNativeBatteryStatus(); }

        @JavascriptInterface
        public boolean toggleClarityFilter() {
            clarityFilterEnabled = !clarityFilterEnabled;
            prefs.edit().putBoolean("clarity_filter_on", clarityFilterEnabled).apply();
            mainHandler.post(() -> {
                if (speechEqualizer != null) {
                    try {
                        speechEqualizer.setEnabled(clarityFilterEnabled);
                        if (clarityFilterEnabled) {
                            configureSpeechBands(speechEqualizer);
                        }
                    } catch (Exception ignored) {}
                }
                Toast.makeText(PlayerActivity.this, clarityFilterEnabled ? "Audio Clarity: ON (Speech Boost & De-Hiss)" : "Audio Clarity: OFF", Toast.LENGTH_SHORT).show();
            });
            return clarityFilterEnabled;
        }

        @JavascriptInterface
        public boolean getClarityFilterState() {
            return clarityFilterEnabled;
        }

        @JavascriptInterface
        public void setWakeLock(final boolean keepOn) {
            mainHandler.post(() -> {
                applyPlayerWakeLock(keepOn);
                Toast.makeText(PlayerActivity.this, keepOn ? "Screen Wake: ON (All Modes)" : "Screen Wake: OFF", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void setWebStreamActive(final boolean active) {
            mainHandler.post(() -> {
                isLiveRadioStream = active;
                mainHandler.removeCallbacks(progressTicker);
                if (active) mainHandler.post(progressTicker);
            });
        }

        @JavascriptInterface
        public void saveStationsJson(final String jsonStr, final boolean showToast) {
            mainHandler.post(() -> saveRadioStationsJsonToDownloads(jsonStr, showToast));
        }

        @JavascriptInterface
        public void openStationJsonMenu(final int curIdx, final String name, final String freq, final String url) {
            mainHandler.post(() -> showStationMenuDialog(curIdx, name, freq, url));
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
        public void playLiveStreamUrl(final String url) {
            mainHandler.post(() -> playLiveRadioUrlNatively(url));
        }

        @JavascriptInterface
        public void showStationDialog(final int editIdx, final String name, final String freq, final String url) {
            mainHandler.post(() -> openAddOrEditStationDialog(editIdx, name, freq, url));
        }

        @JavascriptInterface
        public void seekToSeconds(final int sec) {
            mainHandler.post(() -> {
                try { if (nativePlayer != null && !isLiveRadioStream) nativePlayer.seekTo(sec * 1000); } catch (Exception ignored) {}
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
        public void showSystemBars() {
            mainHandler.post(() -> {
                showBothSystemBarsNow();
                mainHandler.postDelayed(() -> hideSystemUI(), 4500);
            });
        }

        @JavascriptInterface
        public void minimizeApp() {
            mainHandler.post(() -> moveTaskToBack(true));
        }

        @JavascriptInterface
        public void exitEntireApp() {
            mainHandler.post(() -> {
                stopNativePlayer();
                try { stopService(new Intent(PlayerActivity.this, AudioKeepAliveService.class)); } catch (Exception ignored) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAffinity();
                    finishAndRemoveTask();
                } else {
                    finishAffinity();
                }
                System.exit(0);
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
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
            );
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController ic = getWindow().getInsetsController();
                if (ic != null) {
                    ic.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
        } catch (Exception ignored) {}
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
        try { unregisterReceiver(noisyAudioReceiver); } catch (Exception ignored) {}
        try {
            if (audioManager != null && Build.VERSION.SDK_INT >= 23) {
                audioManager.unregisterAudioDeviceCallback(btDeviceCallback);
            }
        } catch (Exception ignored) {}
        mainHandler.removeCallbacks(autoSyncRunnable);
        stopNativePlayer();
        try {
            if (tts != null) { tts.stop(); tts.shutdown(); }
            if (playerWeb != null) { playerWeb.loadUrl("about:blank"); playerWeb.destroy(); }
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}