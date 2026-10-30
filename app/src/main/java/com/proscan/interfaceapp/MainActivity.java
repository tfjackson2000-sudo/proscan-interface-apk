package com.proscan.interfaceapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
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
import android.view.WindowInsets;
import android.view.WindowInsetsController;
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

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_LOAD_JSON = 901;

    private WebView webView;
    private SharedPreferences prefs;
    private AudioManager audioManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean userWakeLockEnabled = false;
    private boolean bgAudioModeEnabled = false;
    private String activeServerUrl = "";
    private TextToSpeech tts = null;
    private volatile boolean ttsReady = false;

    private synchronized void initTTS() {
        try {
            if (tts != null) {
                try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
                tts = null;
            }
            ttsReady = false;
            tts = new TextToSpeech(getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(Locale.UK);
                    tts.setSpeechRate(1.05f);
                    if (Build.VERSION.SDK_INT >= 21) {
                        tts.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build());
                    }
                    ttsReady = true;
                }
            });
        } catch (Exception ignored) {}
    }

    private void forceStopScannerOnHeadphoneDisconnect() {
        setBgServiceRunning(false);
        Runnable stopJs = () -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "try{if(window.__stopScannerAudioFromBt)window.__stopScannerAudioFromBt();" +
                        "var p=document.getElementById('audio_player');if(p){p.pause();}" +
                        "isConnected=false;var b=document.getElementById('btn-audio-toggle');if(b)b.classList.remove('active');}catch(e){}",
                        null
                );
            }
        };
        mainHandler.post(stopJs);
        mainHandler.postDelayed(stopJs, 200);
        mainHandler.postDelayed(stopJs, 500);
        mainHandler.post(() -> Toast.makeText(MainActivity.this, "Headphones disconnected — Audio stopped", Toast.LENGTH_SHORT).show());
    }

    private final BroadcastReceiver noisyAudioReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (isInitialStickyBroadcast()) return; // Discard Android launch-time status dump
            String act = intent.getAction();
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(act)
                    || (Intent.ACTION_HEADSET_PLUG.equals(act) && intent.getIntExtra("state", -1) == 0)) {
                forceStopScannerOnHeadphoneDisconnect();
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
                        || t == 26 /* TYPE_BLE_HEADSET */
                        || t == 27 /* TYPE_BLE_SPEAKER */) {
                    forceStopScannerOnHeadphoneDisconnect();
                    break;
                }
            }
        }
    };

    private final Runnable periodicInjector = new Runnable() {
        @Override
        public void run() {
            if (webView != null) {
                injectEnhancements();
            }
            mainHandler.postDelayed(this, 1800);
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

        initTTS();

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

        userWakeLockEnabled = prefs.getBoolean("wake_lock_on", true);
        if (userWakeLockEnabled) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            webView.setKeepScreenOn(true);
        }

        String localUrl = prefs.getString("local_url", "");
        String tailscaleUrl = prefs.getString("tailscale_url", "");

        if (localUrl.isEmpty() && tailscaleUrl.isEmpty()) {
            showSetupDialog(true);
        } else {
            connectSmart(localUrl, tailscaleUrl);
        }
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

    private void saveBackupJsonToDownloads(String webStorageJson, boolean showToast) {
        try {
            JSONObject root = new JSONObject();
            root.put("local_url", prefs.getString("local_url", ""));
            root.put("tailscale_url", prefs.getString("tailscale_url", ""));
            JSONObject ws = webStorageJson != null ? new JSONObject(webStorageJson) : new JSONObject();
            String rad = prefs.getString("radio_stations_json", "");
            if (!rad.isEmpty() && !ws.has("proscan_radio_stations_v3")) {
                ws.put("proscan_radio_stations_v3", rad);
            }
            root.put("web_storage", ws);
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
                if (webStorage.has("proscan_radio_stations_v3")) {
                    prefs.edit().putString("radio_stations_json", webStorage.optString("proscan_radio_stations_v3", "")).apply();
                }
                prefs.edit().putString("shared_web_storage", webStorage.toString()).putString("last_backup_json", root.toString()).apply();
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

    private void openPlayerActivityMode(final String mode) {
        mainHandler.post(() -> {
            String base = activeServerUrl;
            if (base == null || base.isEmpty()) base = webView != null ? webView.getUrl() : "";
            Intent i = new Intent(MainActivity.this, PlayerActivity.class);
            i.putExtra("server_url", base != null ? base : "");
            i.putExtra("start_mode", mode);
            startActivity(i);
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        });
    }

    public class WebAppBridge {
        @JavascriptInterface
        public String getBatteryStatus() {
            return getNativeBatteryStatus();
        }

        @JavascriptInterface
        public void setKeepScreenOn(final boolean keepOn) {
            mainHandler.post(() -> {
                userWakeLockEnabled = keepOn;
                prefs.edit().putBoolean("wake_lock_on", keepOn).apply();
                if (keepOn) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    webView.setKeepScreenOn(true);
                    Toast.makeText(MainActivity.this, "Screen Wake: ON (All Modes)", Toast.LENGTH_SHORT).show();
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
                if (enabled) Toast.makeText(MainActivity.this, "VLC / Screen-Off Stream: ON", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void speakChannel(final String text) {
            mainHandler.post(() -> {
                if (text == null || text.trim().isEmpty()) return;
                final String cleanText = text.trim();
                if (tts == null || !ttsReady) {
                    initTTS();
                    mainHandler.postDelayed(() -> speakDirect(cleanText), 1000);
                    return;
                }
                speakDirect(cleanText);
            });
        }

        private void speakDirect(String cleanText) {
            int result = TextToSpeech.ERROR;
            try {
                if (Build.VERSION.SDK_INT >= 21) {
                    Bundle params = new Bundle();
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.40f);
                    result = tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "proscan_tts");
                } else {
                    result = tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null);
                }
            } catch (Exception e) {
                result = TextToSpeech.ERROR;
            }

            // If Android killed the remote TTS engine overnight, resurrect it immediately
            if (result != TextToSpeech.SUCCESS) {
                initTTS();
                mainHandler.postDelayed(() -> {
                    if (tts != null && ttsReady) {
                        if (Build.VERSION.SDK_INT >= 21) {
                            Bundle params = new Bundle();
                            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.40f);
                            tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "proscan_tts");
                        } else {
                            tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null);
                        }
                    }
                }, 1100);
            }
        }

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
        public void openArchivePlayer() { openPlayerActivityMode("archive"); }

        @JavascriptInterface
        public void openBroadcastRadio() { openPlayerActivityMode("radio"); }

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
                showBothSystemBarsNow();
                mainHandler.postDelayed(() -> hideSystemUI(), 4500);
            });
        }

        @JavascriptInterface
        public void minimizeApp() { mainHandler.post(() -> moveTaskToBack(true)); }

        @JavascriptInterface
        public void exitApp() { mainHandler.post(() -> closeAndExitApp()); }
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
                if (ic != null) ic.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } catch (Exception ignored) {}
    }

    private void closeAndExitApp() {
        setBgServiceRunning(false);
        try { unregisterReceiver(noisyAudioReceiver); } catch (Exception ignored) {}
        try {
            if (audioManager != null && Build.VERSION.SDK_INT >= 23) {
                audioManager.unregisterAudioDeviceCallback(btDeviceCallback);
            }
        } catch (Exception ignored) {}
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception ignored) {}
        try { if (webView != null) { webView.loadUrl("about:blank"); webView.stopLoading(); webView.destroy(); } } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) finishAndRemoveTask();
        else finish();
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
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return false; }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
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
        boolean wakePref = prefs.getBoolean("wake_lock_on", true);

        StringBuilder js = new StringBuilder();
        js.append("(function() {\n");
        js.append("  try {\n");
        js.append("    var existingSt = document.getElementById('proscan-v7-upgrade-css');\n");
        js.append("    if (document.head && !existingSt) {\n");
        js.append("      var st = document.createElement('style');\n");
        js.append("      st.id = 'proscan-v7-upgrade-css';\n");
        js.append("      st.textContent = '' +\n");
        js.append("        '#device-wrapper{transition:opacity 0.18s ease!important;}' +\n");
        js.append("        '#device-wrapper.flipping-out{opacity:0!important;}' +\n");
        js.append("        '.scanner-nav-5{display:grid!important;grid-template-columns:repeat(5,minmax(0,1fr))!important;gap:5px!important;}' +\n");
        js.append("        '.buttons-box{border:1px solid rgba(0,0,0,0.48)!important;border-radius:12px!important;padding:7px!important;background:rgba(0,0,0,0.22)!important;box-shadow:inset 0 2px 6px rgba(0,0,0,0.75),0 1px 0 rgba(255,255,255,0.09)!important;}' +\n");
        js.append("        '.freq-hit-badge, #mini-log-table .freq-hit-badge, #mini-log-table td:nth-child(5) .freq-hit-badge, #mini-log-table td:nth-child(5) span[class*=\"hit\"], #mini-log-table td:nth-child(5) span[class*=\"badge\"]{display:none!important;visibility:hidden!important;width:0!important;height:0!important;padding:0!important;margin:0!important;border:none!important;overflow:hidden!important;font-size:0!important;}' +\n");
        js.append("        '#mini-log-table th,#mini-log-table td{padding:3px 2px 3px 4px!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(1),#mini-log-table td:nth-child(1){width:19%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(2),#mini-log-table td:nth-child(2){width:20%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(3),#mini-log-table td:nth-child(3){width:13%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(4),#mini-log-table td:nth-child(4){width:14%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(5),#mini-log-table td:nth-child(5){width:34%!important;}' +\n");
        js.append("        '#mini-log-table tbody tr{cursor:pointer;}#mini-log-table tbody tr:active{background:rgba(255,255,255,0.15)!important;}' +\n");
        js.append("        '.top-leds-left{gap:10px!important;}' +\n");
        js.append("        '#voice-switch-track{width:26px;height:11px;border-radius:999px;background:rgba(0,0,0,0.55);border:1px solid rgba(255,255,255,0.16);box-shadow:inset 0 1px 3px rgba(0,0,0,0.85),0 1px 0 rgba(255,255,255,0.08);padding:1px;display:flex;align-items:center;position:relative;flex-shrink:0;}' +\n");
        js.append("        '#voice-switch-thumb{width:11px;height:7px;border-radius:999px;background:var(--btn-bg);border:var(--btn-border);box-shadow:0 1px 2px rgba(0,0,0,0.75),inset 0 1px 0 rgba(255,255,255,0.2);transform:translateX(0);transition:transform 0.22s cubic-bezier(0.4,0,0.2,1);display:flex;align-items:center;justify-content:center;}' +\n");
        js.append("        '#voice-switch-thumb::after{content:\"\";width:2px;height:3px;border-radius:1px;background:rgba(255,220,220,0.85);}' +\n");
        js.append("        '#btn-voice-toggle.active #voice-switch-thumb{transform:translateX(11px);}' +\n");
        js.append("        '#voice-led{flex-shrink:0;width:10px;height:10px;border-radius:50%;background-color:var(--led-off-bg,#1a1a1a);border:var(--led-off-border,2px solid #000);box-shadow:var(--led-off-shadow,inset 1px 1px 3px rgba(0,0,0,0.8),0 1px 1px rgba(255,255,255,0.1));transition:all 0.3s ease;}' +\n");
        js.append("        '@keyframes voice-led-pulse-green{0%,100%{background-color:#10b981;border-color:#a7f3d0;box-shadow:inset 1px 1px 2px rgba(255,255,255,0.9),0 0 0 1px rgba(0,0,0,0.65),0 0 8px #10b981,0 0 14px rgba(16,185,129,0.75);}50%{background-color:#065f46;border-color:#059669;box-shadow:inset 1px 1px 2px rgba(255,255,255,0.35),0 0 0 1px rgba(0,0,0,0.65),0 0 2px rgba(16,185,129,0.25);}}' +\n");
        js.append("        '#btn-voice-toggle.active #voice-led{border-style:solid!important;border-width:2px!important;animation:voice-led-pulse-green 3.8s ease-in-out infinite!important;}' +\n");
        js.append("        '#btn-hardware-exit{display:inline-flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;background:transparent;border:none;padding:0;margin:0 0 0 2px;cursor:pointer;touch-action:manipulation;}#btn-hardware-exit .exit-cap{width:24px;height:11px;border-radius:4px;background:linear-gradient(180deg,#b91c1c,#7f1d1d);border:1px solid #450a0a;box-shadow:0 1px 2px rgba(0,0,0,0.75),inset 0 1px 0 rgba(255,255,255,0.28);display:flex;align-items:center;justify-content:center;}#btn-hardware-exit .exit-cap::after{content:\"\";width:8px;height:2px;border-radius:1px;background:rgba(255,220,220,0.85);}#btn-hardware-exit:active .exit-cap{transform:translateY(1px);filter:brightness(1.2);}' +\n");

        /* Symmetrical Clock on Left, Battery on Right + Exact Match to custom_ui.html */
        js.append("        '#display-clock-hud{position:absolute!important;top:6px!important;left:10px!important;right:auto!important;z-index:3!important;}' +\n");
        js.append("        '#scanner-bat-hud{position:absolute!important;top:6px!important;right:9px!important;left:auto!important;z-index:3!important;font-family:\"JetBrains Mono\",monospace!important;font-size:10px!important;font-weight:700!important;letter-spacing:0.8px!important;color:var(--log-text)!important;opacity:0.88;pointer-events:none;display:inline-flex!important;align-items:center!important;line-height:1;transition:opacity 0.3s ease,color 0.3s ease;}' +\n");
        js.append("        '#scanner-bat-hud *{font-family:inherit!important;font-size:inherit!important;font-weight:inherit!important;letter-spacing:inherit!important;line-height:inherit!important;}' +\n");
        js.append("        '#scanner-bat-txt{color:inherit!important;}' +\n");
        js.append("        '#scanner-bat-hud.disconnected{opacity:0!important;}' +\n");
        js.append("        '.bat-icon-box{display:inline-block;width:13px;height:8px;border:1.4px solid currentColor;border-radius:2px;padding:1px;position:relative;vertical-align:middle;margin-right:4px;box-sizing:border-box;flex-shrink:0;}' +\n");
        js.append("        '.bat-icon-box::after{content:\"\";position:absolute;right:-3px;top:1px;width:1.5px;height:3px;background:currentColor;border-radius:0 1px 1px 0;}' +\n");
        js.append("        '.bat-icon-fill{display:block;height:100%;background:currentColor;width:80%;transition:width 0.3s ease;}' +\n");

        /* Sync Bad Contact Glitch Animations directly to #scanner-bat-hud */
        js.append("        'html:is([data-disp-tex=\"glitch\"],[data-disp-tex=\"cracked-glitch\"]):is(.bc-blur,.bc-blur-blink,.bc-wavy-blur) .scanner-display #scanner-bat-hud:not(.disconnected){filter:blur(2.2px)!important;}' +\n");
        js.append("        'html:is([data-disp-tex=\"glitch\"],[data-disp-tex=\"cracked-glitch\"]).bc-deep-blur .scanner-display #scanner-bat-hud:not(.disconnected){filter:blur(2.9px)!important;}' +\n");
        js.append("        'html:is([data-disp-tex=\"glitch\"],[data-disp-tex=\"cracked-glitch\"]):is(.bc-fuzzy,.bc-flicker,.bc-tv-snow) .scanner-display #scanner-bat-hud:not(.disconnected){filter:blur(1.2px)!important;}' +\n");
        js.append("        'html:is([data-disp-tex=\"glitch\"],[data-disp-tex=\"cracked-glitch\"]).bc-wobble .scanner-display #scanner-bat-hud:not(.disconnected){animation:bcWobbleSub 0.46s steps(2,end) forwards!important;}' +\n");
        js.append("        'html:is([data-disp-tex=\"glitch\"],[data-disp-tex=\"cracked-glitch\"]).bc-fuzzy .scanner-display #scanner-bat-hud:not(.disconnected){animation:bcFuzzySub 0.58s ease-in-out forwards!important;}' +\n");
        js.append("        'html:is([data-disp-tex=\"glitch\"],[data-disp-tex=\"cracked-glitch\"]).bc-flicker .scanner-display #scanner-bat-hud:not(.disconnected){animation:bcFlickerSub 0.62s steps(1,end) forwards!important;}' +\n");
        js.append("        'html:is([data-disp-tex=\"glitch\"],[data-disp-tex=\"cracked-glitch\"]).bc-tv-off .scanner-display #scanner-bat-hud:not(.disconnected){animation:bcTvOffSub 1.35s ease-in-out forwards!important;}' +\n");
        js.append("        'html:is([data-disp-tex=\"glitch\"],[data-disp-tex=\"cracked-glitch\"]):is(.bc-fast-blink,.bc-blur-blink) .scanner-display #scanner-bat-hud:not(.disconnected){animation:bcFastBlinkSub 0.64s steps(1,end) forwards!important;}' +\n");
        js.append("        'html:is([data-disp-tex=\"glitch\"],[data-disp-tex=\"cracked-glitch\"]):is(.bc-wavy,.bc-wavy-blur) .scanner-display #scanner-bat-hud:not(.disconnected){animation:bcWavySub 0.82s ease-in-out forwards!important;}' +\n");
        js.append("        'html:is([data-disp-tex=\"glitch\"],[data-disp-tex=\"cracked-glitch\"]).bc-wavy-blink .scanner-display #scanner-bat-hud:not(.disconnected){animation:bcWavySub 0.78s ease-in-out forwards,bcFastBlinkSub 0.78s steps(1,end) forwards!important;}' +\n");
        js.append("        'html:is([data-disp-tex=\"glitch\"],[data-disp-tex=\"cracked-glitch\"]).bc-tv-snow .scanner-display #scanner-bat-hud:not(.disconnected){animation:bcSnowDropSub 0.86s steps(2,end) forwards!important;}' +\n");
        js.append("        'html:is([data-disp-tex=\"glitch\"],[data-disp-tex=\"cracked-glitch\"]).bc-snow-wavy .scanner-display #scanner-bat-hud:not(.disconnected){animation:bcWavySub 0.86s ease-in-out forwards,bcSnowDropSub 0.86s steps(2,end) forwards!important;}' +\n");

        /* Screen Power Off / CRT Beam Collapse Animation */
        js.append("        'html.tv-off #scanner-bat-hud,.scanner-display.tv-shutting-down #scanner-bat-hud{filter:grayscale(1) brightness(2.5)!important;animation:crtPowerOffImgF 0.38s cubic-bezier(0.4,0,0.2,1) forwards!important;}' +\n");

        /* 6 Synchronized Boot Animations */
        js.append("        '.scanner-display.tv-boot-scart #scanner-bat-hud{animation:scartBootImgF 0.78s cubic-bezier(0.22,1,0.36,1) forwards!important;}' +\n");
        js.append("        '.scanner-display.tv-boot-rf #scanner-bat-hud{animation:rfBootImgF 0.88s steps(3,end) forwards!important;}' +\n");
        js.append("        '.scanner-display.tv-boot-lcdtest #scanner-bat-hud{animation:lcdTestImgF 0.82s steps(2,end) forwards!important;}' +\n");
        js.append("        '.scanner-display.tv-boot-phosphor #scanner-bat-hud{animation:phosphorSweepImgF 0.78s cubic-bezier(0.22,1,0.36,1) forwards!important;}' +\n");
        js.append("        '.scanner-display.tv-boot-surge #scanner-bat-hud{animation:surgeBootImgF 0.88s ease-in-out forwards!important;}' +\n");
        js.append("        '.scanner-display.tv-boot-vhold #scanner-bat-hud{animation:vholdBootImgF 0.84s cubic-bezier(0.22,1,0.36,1) forwards!important;};'\n");
        js.append("      document.head.appendChild(st);\n");
        js.append("    } else if (existingSt && document.head.lastElementChild !== existingSt) {\n");
        js.append("      document.head.appendChild(existingSt);\n");
        js.append("    }\n");

        /* Directly attach and synchronize #scanner-bat-hud beside #display-clock-hud inside .scanner-display */
        js.append("    var dispBox = document.querySelector('.scanner-display');\n");
        js.append("    if (dispBox) {\n");
        js.append("      var clkEl = document.getElementById('display-clock-hud');\n");
        js.append("      var batEl = document.getElementById('scanner-bat-hud');\n");
        js.append("      if (!batEl) {\n");
        js.append("        batEl = document.createElement('div');\n");
        js.append("        batEl.id = 'scanner-bat-hud';\n");
        js.append("        batEl.innerHTML = '<span class=\"bat-icon-box\"><span class=\"bat-icon-fill\" id=\"scanner-bat-fill\"></span></span><span id=\"scanner-bat-txt\">--%</span>';\n");
        js.append("        dispBox.appendChild(batEl);\n");
        js.append("      }\n");
        js.append("      if (clkEl && batEl) {\n");
        js.append("        if (clkEl.classList.contains('disconnected')) batEl.classList.add('disconnected');\n");
        js.append("        else batEl.classList.remove('disconnected');\n");
        js.append("      }\n");
        js.append("      if (window.AndroidApp && typeof window.AndroidApp.getBatteryStatus === 'function') {\n");
        js.append("        var bStr = window.AndroidApp.getBatteryStatus() || '';\n");
        js.append("        var bTxt = document.getElementById('scanner-bat-txt');\n");
        js.append("        var bFill = document.getElementById('scanner-bat-fill');\n");
        js.append("        if (bTxt && bStr) bTxt.innerText = bStr;\n");
        js.append("        if (bFill && bStr) {\n");
        js.append("          var num = parseInt(bStr.replace(/[^0-9]/g, ''), 10) || 0;\n");
        js.append("          bFill.style.width = Math.max(8, Math.min(100, num)) + '%';\n");
        js.append("        }\n");
        js.append("      }\n");
        js.append("    }\n");

        js.append("    window.__stripHitBadgesFromLog = function() {\n");
        js.append("      document.querySelectorAll('#mini-log-data tr').forEach(function(tr) {\n");
        js.append("        if (tr.children.length < 5) return;\n");
        js.append("        var nameTd = tr.children[4];\n");
        js.append("        nameTd.querySelectorAll('*').forEach(function(el) {\n");
        js.append("          var t = (el.innerText || el.textContent || '').trim();\n");
        js.append("          if (el.classList.contains('freq-hit-badge') || /^[×xX]\\s*\\d+$/.test(t)) {\n");
        js.append("            var num = t.replace(/[^0-9]/g, '');\n");
        js.append("            if (num) nameTd.setAttribute('data-hit-count', num);\n");
        js.append("            el.style.setProperty('display', 'none', 'important');\n");
        js.append("          }\n");
        js.append("        });\n");
        js.append("      });\n");
        js.append("    };\n");
        js.append("    window.__stripHitBadgesFromLog();\n");

        js.append("    if (!window.__crossIpSynced && typeof applySkin === 'function') {\n");
        js.append("      window.__crossIpSynced = true;\n");
        js.append("      var sharedRaw = ").append(safeShared).append(";\n");
        js.append("      if (sharedRaw && sharedRaw.length > 10) {\n");
        js.append("        var sharedObj = JSON.parse(sharedRaw);\n");
        js.append("        var localPresets = localStorage.getItem('proscan_theme_presets_v1');\n");
        js.append("        if ((!localPresets || localPresets === '{}') && sharedObj['proscan_theme_presets_v1']) {\n");
        js.append("          Object.keys(sharedObj).forEach(function(k){ localStorage.setItem(k, sharedObj[k]); });\n");
        js.append("          applySkin(parseInt(localStorage.getItem('proscan_skin_index')||'0',10)||0);\n");
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
        js.append("    var wBtn = document.getElementById('btn-wake-toggle');\n");
        js.append("    isWakeLockActive = ").append(wakePref).append(";\n");
        js.append("    if (wBtn) { wBtn.classList.toggle('active', isWakeLockActive); wBtn.setAttribute('aria-pressed', isWakeLockActive ? 'true' : 'false'); }\n");
        js.append("    window.toggleWakeLock = function() {\n");
        js.append("      var btn = document.getElementById('btn-wake-toggle');\n");
        js.append("      isWakeLockActive = !isWakeLockActive;\n");
        js.append("      if (btn) { btn.classList.toggle('active', isWakeLockActive); btn.setAttribute('aria-pressed', isWakeLockActive ? 'true' : 'false'); }\n");
        js.append("      if (window.AndroidApp) window.AndroidApp.setKeepScreenOn(isWakeLockActive);\n");
        js.append("    };\n");

        js.append("    window.__syncThemeInMem = function() {\n");
        js.append("      try {\n");
        js.append("        var dump = {};\n");
        js.append("        for (var i = 0; i < localStorage.length; i++) {\n");
        js.append("          var k = localStorage.key(i);\n");
        js.append("          if (k && k.indexOf('proscan_') === 0) dump[k] = localStorage.getItem(k);\n");
        js.append("        }\n");
        js.append("        if (window.AndroidApp && window.AndroidApp.syncActiveThemeInMemory) window.AndroidApp.syncActiveThemeInMemory(JSON.stringify(dump));\n");
        js.append("      } catch (e) {}\n");
        js.append("    };\n");
        js.append("    window.__syncThemeInMem();\n");

        js.append("    if (!window.__presetAutoBackupHooked && typeof window.savePresetToSlot === 'function') {\n");
        js.append("      window.__presetAutoBackupHooked = true;\n");
        js.append("      window.__exportAllProscanStorage = function(notify) {\n");
        js.append("        try {\n");
        js.append("          var dump = {};\n");
        js.append("          for (var i = 0; i < localStorage.length; i++) {\n");
        js.append("            var k = localStorage.key(i);\n");
        js.append("            if (k && k.indexOf('proscan_') === 0) dump[k] = localStorage.getItem(k);\n");
        js.append("          }\n");
        js.append("          if (window.AndroidApp && window.AndroidApp.autoSavePresetsJson) window.AndroidApp.autoSavePresetsJson(JSON.stringify(dump), Boolean(notify));\n");
        js.append("        } catch (e) {}\n");
        js.append("      };\n");
        js.append("      var origSave = window.savePresetToSlot;\n");
        js.append("      window.savePresetToSlot = function(slot, snap) { origSave(slot, snap); window.__exportAllProscanStorage(false); };\n");
        js.append("    }\n");

        js.append("    var loginBtn = document.getElementById('proscan-login-open');\n");
        js.append("    var logoutBtn = document.getElementById('proscan-logout-button');\n");
        js.append("    if (loginBtn && logoutBtn && !loginBtn.__mergedAuthHooked) {\n");
        js.append("      var rowParent = loginBtn.parentNode;\n");
        js.append("      if (rowParent) rowParent.classList.add('scanner-nav-5');\n");
        js.append("      var newAuthBtn = loginBtn.cloneNode(true);\n");
        js.append("      newAuthBtn.__mergedAuthHooked = true;\n");
        js.append("      rowParent.replaceChild(newAuthBtn, loginBtn);\n");
        js.append("      function isSuperUserLoggedIn() { return (document.body.innerText || '').indexOf('Logged In: Super User') !== -1; }\n");
        js.append("      function syncAuthBtnLabel() { newAuthBtn.textContent = isSuperUserLoggedIn() ? 'LOG OUT' : 'LOG IN'; }\n");
        js.append("      syncAuthBtnLabel(); setInterval(syncAuthBtnLabel, 1000);\n");
        js.append("      newAuthBtn.addEventListener('click', function() {\n");
        js.append("        if (isSuperUserLoggedIn()) {\n");
        js.append("          var origOut = document.getElementById('input3'); if (origOut) origOut.click();\n");
        js.append("          var p = document.getElementById('proscan-login-panel'); if (p) p.hidden = true;\n");
        js.append("        } else {\n");
        js.append("          var p = document.getElementById('proscan-login-panel'); var pw = document.getElementById('proscan-password');\n");
        js.append("          if (p) p.hidden = false; if (pw) pw.focus();\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("      var archiveBtn = logoutBtn.cloneNode(true);\n");
        js.append("      archiveBtn.id = 'btn-open-player-app'; archiveBtn.textContent = 'ARCHIVE';\n");
        js.append("      var radioBtn = logoutBtn.cloneNode(true);\n");
        js.append("      radioBtn.id = 'btn-open-radio-app'; radioBtn.textContent = 'RADIO';\n");
        js.append("      rowParent.replaceChild(archiveBtn, logoutBtn);\n");
        js.append("      rowParent.insertBefore(radioBtn, archiveBtn.nextSibling);\n");
        js.append("      archiveBtn.addEventListener('click', function() {\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(25);\n");
        js.append("        if (window.__syncThemeInMem) window.__syncThemeInMem();\n");
        js.append("        var wrap = document.getElementById('device-wrapper'); if (wrap) wrap.classList.add('flipping-out');\n");
        js.append("        setTimeout(function() { if (window.AndroidApp) window.AndroidApp.openArchivePlayer(); }, 160);\n");
        js.append("      });\n");
        js.append("      radioBtn.addEventListener('click', function() {\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(25);\n");
        js.append("        if (window.__syncThemeInMem) window.__syncThemeInMem();\n");
        js.append("        var wrap = document.getElementById('device-wrapper'); if (wrap) wrap.classList.add('flipping-out');\n");
        js.append("        setTimeout(function() { if (window.AndroidApp) window.AndroidApp.openBroadcastRadio(); }, 160);\n");
        js.append("      });\n");
        js.append("    }\n");

        /* Real-Time, Synchronized Voice Readout System (Zero Latency Drift) */
        js.append("    var ledsLeft = document.querySelector('.top-leds-left');\n");
        js.append("    if (ledsLeft && !document.getElementById('btn-voice-toggle')) {\n");
        js.append("      window.__voiceReadoutEnabled = false; window.__lastSpokenRowKey = '';\n");
        js.append("      function speakTextNow(txt) { if (window.AndroidApp) window.AndroidApp.speakChannel(txt); }\n");
        js.append("      var vBtn = document.createElement('button');\n");
        js.append("      vBtn.id = 'btn-voice-toggle'; vBtn.className = 'wake-btn-wrapper'; vBtn.type = 'button';\n");
        js.append("      vBtn.innerHTML = '<div class=\"wake-switch-col\"><div id=\"voice-switch-track\"><div id=\"voice-switch-thumb\"></div></div><span class=\"rx-label\">VOICE</span></div><div id=\"voice-led\"></div>';\n");
        js.append("      vBtn.addEventListener('click', function() {\n");
        js.append("        window.__voiceReadoutEnabled = !window.__voiceReadoutEnabled;\n");
        js.append("        vBtn.classList.toggle('active', window.__voiceReadoutEnabled);\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(25);\n");
        js.append("        if (window.__voiceReadoutEnabled) {\n");
        js.append("          speakTextNow('Voice readout on');\n");
        js.append("          if (typeof showDisplayHud === 'function') showDisplayHud('VOICE READOUT: ON', 1400);\n");
        js.append("        } else if (typeof showDisplayHud === 'function') {\n");
        js.append("          showDisplayHud('VOICE READOUT: OFF', 1400);\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("      ledsLeft.appendChild(vBtn);\n");

        js.append("      var logBody = document.getElementById('mini-log-data');\n");
        js.append("      if (logBody) {\n");
        js.append("        function checkTopLogRowForSpeech() {\n");
        js.append("          if (window.__stripHitBadgesFromLog) window.__stripHitBadgesFromLog();\n");
        js.append("          if (!window.__voiceReadoutEnabled) return;\n");
        js.append("          var topRow = logBody.querySelector('tr.new-log-row') || logBody.querySelector('tr');\n");
        js.append("          if (!topRow || topRow.children.length < 5) return;\n");
        js.append("          var timeStr = (topRow.children[0].textContent || '').trim();\n");
        js.append("          var freqStr = (topRow.children[1].textContent || '').trim();\n");
        js.append("          var nameTd = topRow.children[4];\n");
        js.append("          var rawTitle = nameTd.getAttribute('title') || '';\n");
        js.append("          if (!rawTitle) {\n");
        js.append("            var cloneTd = nameTd.cloneNode(true);\n");
        js.append("            cloneTd.querySelectorAll('.freq-hit-badge').forEach(function(b){ b.remove(); });\n");
        js.append("            rawTitle = cloneTd.textContent || '';\n");
        js.append("          }\n");
        js.append("          var closeParen = rawTitle.indexOf(')');\n");
        js.append("          var chName = (closeParen !== -1 ? rawTitle.slice(closeParen + 1) : rawTitle).replace(/^[×xX]\\s*\\d+\\s*/, '').trim();\n");
        js.append("          if (!chName || chName === '---') chName = freqStr;\n");
        js.append("          if (!chName) return;\n");
        js.append("          var rowKey = timeStr + '|' + freqStr + '|' + chName;\n");
        js.append("          if (rowKey !== window.__lastSpokenRowKey) {\n");
        js.append("            window.__lastSpokenRowKey = rowKey;\n");
        js.append("            speakTextNow(chName);\n");
        js.append("          }\n");
        js.append("        }\n");
        js.append("        new MutationObserver(checkTopLogRowForSpeech).observe(logBody, { childList: true, subtree: true });\n");
        js.append("      }\n");
        js.append("    }\n");

        js.append("    var logTb = document.getElementById('mini-log-data');\n");
        js.append("    if (logTb && !logTb.__hitHudHooked) {\n");
        js.append("      logTb.__hitHudHooked = true;\n");
        js.append("      logTb.addEventListener('click', function(e) {\n");
        js.append("        var tr = e.target.closest('tr'); if (!tr || tr.children.length < 5) return;\n");
        js.append("        var nameTd = tr.children[4]; var badge = nameTd.querySelector('.freq-hit-badge');\n");
        js.append("        var hitsText = nameTd.getAttribute('data-hit-count') || (badge ? (badge.textContent || '').replace(/[^0-9]/g, '') : '') || '1';\n");
        js.append("        var rawTitle = nameTd.getAttribute('title') || nameTd.innerText || '';\n");
        js.append("        var closeParen = rawTitle.indexOf(')');\n");
        js.append("        var cleanName = (closeParen !== -1 ? rawTitle.slice(closeParen + 1) : rawTitle).trim();\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(15);\n");
        js.append("        if (typeof showDisplayHud === 'function') showDisplayHud(cleanName + ' • HITS: ' + hitsText, 2200);\n");
        js.append("      });\n");
        js.append("    }\n");

        js.append("    var oldAudioBtn = document.getElementById('btn-audio-toggle');\n");
        js.append("    if (oldAudioBtn && !oldAudioBtn.__vlcUnifiedHooked) {\n");
        js.append("      var aBtn = oldAudioBtn.cloneNode(true); aBtn.__vlcUnifiedHooked = true;\n");
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
        js.append("      window.__stopScannerAudioFromBt = function() { setAudioPlaying(false, false); };\n");
        js.append("      var aTimer = null, aLong = false;\n");
        js.append("      aBtn.addEventListener('pointerdown', function() {\n");
        js.append("        clearTimeout(aTimer); aLong = false;\n");
        js.append("        aTimer = setTimeout(function() {\n");
        js.append("          aTimer = null; aLong = true; if (navigator.vibrate) navigator.vibrate(40);\n");
        js.append("          if (!isConnected) setAudioPlaying(true, true); else setAudioPlaying(true, !isVlcMode);\n");
        js.append("        }, 500);\n");
        js.append("      });\n");
        js.append("      aBtn.addEventListener('pointerup', function() {\n");
        js.append("        var wasActive = aTimer !== null; clearTimeout(aTimer); aTimer = null;\n");
        js.append("        if (wasActive && !aLong) { if (!isConnected) setAudioPlaying(true, false); else setAudioPlaying(false, false); }\n");
        js.append("      });\n");
        js.append("      aBtn.addEventListener('pointerleave', function() { clearTimeout(aTimer); aTimer = null; });\n");
        js.append("      aBtn.addEventListener('pointercancel', function() { clearTimeout(aTimer); aTimer = null; });\n");
        js.append("    }\n");

        js.append("    var ledsRight = document.querySelector('.top-leds-right');\n");
        js.append("    if (ledsRight && !document.getElementById('btn-hardware-exit')) {\n");
        js.append("      var exitBtn = document.createElement('button');\n");
        js.append("      exitBtn.id = 'btn-hardware-exit'; exitBtn.type = 'button';\n");
        js.append("      exitBtn.innerHTML = '<div class=\"exit-cap\"></div><span class=\"rx-label\">EXIT</span>';\n");
        js.append("      ledsRight.appendChild(exitBtn);\n");
        js.append("      var exTimer = null, exLong = false, lastTapTime = 0;\n");
        js.append("      exitBtn.addEventListener('pointerdown', function() {\n");
        js.append("        clearTimeout(exTimer); exLong = false;\n");
        js.append("        exTimer = setTimeout(function() {\n");
        js.append("          exTimer = null; exLong = true; if (navigator.vibrate) navigator.vibrate([45, 60, 55]);\n");
        js.append("          if (typeof isConnected !== 'undefined' && isConnected) {\n");
        js.append("            var p = document.getElementById('audio_player'); if (p && !p.paused) p.pause();\n");
        js.append("            isConnected = false; var ab = document.getElementById('btn-audio-toggle'); if (ab) ab.classList.remove('active');\n");
        js.append("          }\n");
        js.append("          if (typeof isDisplayOn !== 'undefined' && isDisplayOn && typeof toggleDisplay === 'function') toggleDisplay();\n");
        js.append("          setTimeout(function() { if (window.AndroidApp) window.AndroidApp.exitApp(); }, 550);\n");
        js.append("        }, 550);\n");
        js.append("      });\n");
        js.append("      exitBtn.addEventListener('pointerup', function() {\n");
        js.append("        var wasActive = exTimer !== null; clearTimeout(exTimer); exTimer = null;\n");
        js.append("        if (wasActive && !exLong) {\n");
        js.append("          var now = Date.now();\n");
        js.append("          if (now - lastTapTime < 420) { lastTapTime = 0; if (window.AndroidApp) window.AndroidApp.minimizeApp(); }\n");
        js.append("          else { lastTapTime = now; if (window.AndroidApp) window.AndroidApp.showSystemBars(); }\n");
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
                "Load Backup File from Downloads",
                "Open Broadcast Radio Tuner (Offline / Direct)",
                "Open Google Drive Archive Player (Offline / Direct)"
        };
        new AlertDialog.Builder(this)
                .setTitle("Presets, IPs & Direct Modes")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        if (webView != null) webView.evaluateJavascript("if(window.__exportAllProscanStorage)window.__exportAllProscanStorage(true);", null);
                    } else if (which == 1) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        startActivityForResult(intent, REQ_LOAD_JSON);
                    } else if (which == 2) {
                        openPlayerActivityMode("radio");
                    } else if (which == 3) {
                        openPlayerActivityMode("archive");
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
        builder.setNeutralButton("JSON / Radio", (dialog, which) -> showPresetsBackupMenu());
        if (!isFirstRun) builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void connectSmart(String localUrl, String tailscaleUrl) {
        if (!localUrl.isEmpty() && tailscaleUrl.isEmpty()) { activeServerUrl = localUrl; webView.loadUrl(localUrl); return; }
        if (localUrl.isEmpty() && !tailscaleUrl.isEmpty()) { activeServerUrl = tailscaleUrl; webView.loadUrl(tailscaleUrl); return; }
        Toast.makeText(this, "Checking Local Wi-Fi...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            boolean localReachable = isUrlReachable(localUrl, 1500);
            if (localReachable) {
                mainHandler.post(() -> {
                    activeServerUrl = localUrl;
                    Toast.makeText(this, "Connected via Local Wi-Fi", Toast.LENGTH_SHORT).show();
                    webView.loadUrl(localUrl);
                });
            } else {
                boolean tsReachable = isUrlReachable(tailscaleUrl, 2200);
                mainHandler.post(() -> {
                    activeServerUrl = tailscaleUrl;
                    if (tsReachable) {
                        Toast.makeText(this, "Connected via Tailscale", Toast.LENGTH_SHORT).show();
                        webView.loadUrl(tailscaleUrl);
                    } else {
                        Toast.makeText(this, "Scanner offline (Check Wi-Fi / Tailscale)", Toast.LENGTH_LONG).show();
                        webView.loadUrl(tailscaleUrl);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Scanner Server Unreachable")
                                .setMessage("Could not reach Local Wi-Fi or Tailscale. Would you like to open Broadcast Radio or Archive Player instead?")
                                .setPositiveButton("Open Radio", (d, w) -> openPlayerActivityMode("radio"))
                                .setNeutralButton("Open Archive", (d, w) -> openPlayerActivityMode("archive"))
                                .setNegativeButton("Retry / Setup", (d, w) -> showSetupDialog(false))
                                .show();
                    }
                });
            }
        });
    }

    private boolean isUrlReachable(String urlStr, int timeoutMs) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return (code >= 200 && code < 500);
        } catch (Exception e) { return false; }
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
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.evaluateJavascript("var dw=document.getElementById('device-wrapper');if(dw)dw.classList.remove('flipping-out');", null);
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
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    public void onBackPressed() { showSetupDialog(false); }
}