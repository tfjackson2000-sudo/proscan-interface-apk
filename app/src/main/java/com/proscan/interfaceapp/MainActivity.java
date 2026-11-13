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
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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
import android.speech.tts.UtteranceProgressListener;
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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements SensorEventListener {

    private static final int REQ_LOAD_JSON = 901;

    public static final class PriorityRadioEngine {
        private static MediaPlayer radioPlayer = null;
        private static String activeStationUrl = "";
        private static String activeStationName = "";
        private static int activeSessionId = 0;
        private static volatile boolean isPlaying = false;
        private static volatile boolean isScannerRxActive = false;
        private static volatile boolean isTtsSpeaking = false;
        private static volatile boolean isWaitingForPendingTts = false;
        private static volatile long lastRxHeartbeatMs = 0L;
        private static volatile long lastTtsStartMs = 0L;
        private static volatile boolean restoreScheduled = false;
        private static final Handler handler = new Handler(Looper.getMainLooper());
        private static final ExecutorService radioExecutor = Executors.newSingleThreadExecutor();

        private static final Runnable restoreVolumeRunnable = new Runnable() {
            @Override
            public void run() {
                synchronized (PriorityRadioEngine.class) {
                    restoreScheduled = false;
                    if (radioPlayer == null || !isPlaying) return;
                    if (isScannerRxActive || isTtsSpeaking || isWaitingForPendingTts) return;
                    applyCurrentVolumeLocked(cachedPrefs);
                }
            }
        };

        private static final Runnable clearPendingTtsWaitRunnable = new Runnable() {
            @Override
            public void run() {
                synchronized (PriorityRadioEngine.class) {
                    isWaitingForPendingTts = false;
                    if (!isScannerRxActive && !isTtsSpeaking) {
                        scheduleRestoreAfter2500msLocked();
                    }
                }
            }
        };

        private static final Runnable duckWatchdogRunnable = new Runnable() {
            @Override
            public void run() {
                synchronized (PriorityRadioEngine.class) {
                    if (radioPlayer != null && isPlaying) {
                        long now = System.currentTimeMillis();
                        if (isScannerRxActive && (now - lastRxHeartbeatMs > 1500L)) {
                            isScannerRxActive = false;
                        }
                        if (isTtsSpeaking && (now - lastTtsStartMs > 7000L)) {
                            isTtsSpeaking = false;
                        }
                        if (!isScannerRxActive && !isTtsSpeaking && !isWaitingForPendingTts && !restoreScheduled) {
                            applyCurrentVolumeLocked(cachedPrefs);
                        }
                        handler.postDelayed(this, 500);
                    }
                }
            }
        };

        private static SharedPreferences cachedPrefs = null;

        public static boolean isScannerCurrentlyReceiving() {
            if (isScannerRxActive && (System.currentTimeMillis() - lastRxHeartbeatMs > 1500L)) {
                isScannerRxActive = false;
            }
            return isScannerRxActive;
        }

        public static boolean isAutoDuckEnabled(SharedPreferences prefs) {
            if (prefs != null) cachedPrefs = prefs;
            return cachedPrefs == null || cachedPrefs.getBoolean("simple_auto_duck_on", true);
        }

        public static boolean toggleAutoDuck(SharedPreferences prefs) {
            if (prefs != null) cachedPrefs = prefs;
            boolean next = !isAutoDuckEnabled(cachedPrefs);
            if (cachedPrefs != null) {
                cachedPrefs.edit().putBoolean("simple_auto_duck_on", next).apply();
            }
            synchronized (PriorityRadioEngine.class) {
                handler.removeCallbacks(restoreVolumeRunnable);
                restoreScheduled = false;
                applyCurrentVolumeLocked(cachedPrefs);
            }
            return next;
        }

        public static int getRadioVolumePct(SharedPreferences prefs) {
            if (prefs != null) cachedPrefs = prefs;
            return cachedPrefs != null ? cachedPrefs.getInt("radio_independent_vol_pct", 50) : 50;
        }

        public static void setRadioVolumePct(SharedPreferences prefs, int pct) {
            if (prefs != null) cachedPrefs = prefs;
            int clamped = Math.max(0, Math.min(100, pct));
            if (cachedPrefs != null) {
                cachedPrefs.edit().putInt("radio_independent_vol_pct", clamped).apply();
            }
            synchronized (PriorityRadioEngine.class) {
                applyCurrentVolumeLocked(cachedPrefs);
            }
        }

        public static float getUserRadioVolumeScalar(SharedPreferences prefs) {
            int pct = getRadioVolumePct(prefs);
            return Math.max(0f, Math.min(1f, pct / 100f));
        }

        private static synchronized void applyCurrentVolumeLocked(SharedPreferences prefs) {
            if (radioPlayer == null || !isPlaying) return;
            boolean ducked = isAutoDuckEnabled(prefs) && (isScannerRxActive || isTtsSpeaking || isWaitingForPendingTts);
            float target = ducked ? 0.0f : getUserRadioVolumeScalar(prefs);
            try {
                radioPlayer.setVolume(target, target);
            } catch (Exception ignored) {}
        }

        private static synchronized void scheduleRestoreAfter2500msLocked() {
            handler.removeCallbacks(restoreVolumeRunnable);
            restoreScheduled = false;
            if (!isScannerRxActive && !isTtsSpeaking && !isWaitingForPendingTts) {
                restoreScheduled = true;
                handler.postDelayed(restoreVolumeRunnable, 2500);
            }
        }

        public static void onScannerRxStateChanged(final SharedPreferences prefs, final boolean rxActive, final boolean voiceReadoutEnabled) {
            if (prefs != null) cachedPrefs = prefs;
            synchronized (PriorityRadioEngine.class) {
                if (rxActive) {
                    lastRxHeartbeatMs = System.currentTimeMillis();
                    isScannerRxActive = true;
                    isWaitingForPendingTts = false;
                    restoreScheduled = false;
                    handler.removeCallbacks(restoreVolumeRunnable);
                    handler.removeCallbacks(clearPendingTtsWaitRunnable);
                    applyCurrentVolumeLocked(cachedPrefs);
                } else {
                    if (isScannerRxActive) {
                        isScannerRxActive = false;
                        handler.removeCallbacks(restoreVolumeRunnable);
                        restoreScheduled = false;
                        if (voiceReadoutEnabled) {
                            isWaitingForPendingTts = true;
                            handler.removeCallbacks(clearPendingTtsWaitRunnable);
                            handler.postDelayed(clearPendingTtsWaitRunnable, 1300);
                        } else {
                            isWaitingForPendingTts = false;
                            scheduleRestoreAfter2500msLocked();
                        }
                    } else if (!isTtsSpeaking && !isWaitingForPendingTts && !restoreScheduled) {
                        scheduleRestoreAfter2500msLocked();
                    }
                }
            }
        }

        public static void notifyScannerRx(final SharedPreferences prefs, final boolean rxActive, final boolean voiceReadoutEnabled) {
            onScannerRxStateChanged(prefs, rxActive, voiceReadoutEnabled);
        }

        public static void notifyScannerRx(final SharedPreferences prefs, final boolean rxActive) {
            onScannerRxStateChanged(prefs, rxActive, false);
        }

        public static void onTtsSpeechStateChanged(final SharedPreferences prefs, final boolean speaking) {
            if (prefs != null) cachedPrefs = prefs;
            synchronized (PriorityRadioEngine.class) {
                handler.removeCallbacks(clearPendingTtsWaitRunnable);
                isWaitingForPendingTts = false;
                if (speaking) {
                    lastTtsStartMs = System.currentTimeMillis();
                    isTtsSpeaking = true;
                    restoreScheduled = false;
                    handler.removeCallbacks(restoreVolumeRunnable);
                    applyCurrentVolumeLocked(cachedPrefs);
                } else {
                    isTtsSpeaking = false;
                    if (!isScannerRxActive) {
                        scheduleRestoreAfter2500msLocked();
                    }
                }
            }
        }

        public static void notifyTtsSpeaking(final SharedPreferences prefs, final boolean speaking) {
            onTtsSpeechStateChanged(prefs, speaking);
        }

        public static synchronized boolean isRadioPlaying() {
            try {
                return isPlaying && radioPlayer != null && radioPlayer.isPlaying();
            } catch (Exception e) {
                return false;
            }
        }

        public static synchronized String getActiveStationUrl() {
            return activeStationUrl != null ? activeStationUrl : "";
        }

        public static synchronized String getActiveStationName() {
            return activeStationName != null ? activeStationName : "";
        }

        public static synchronized int getAudioSessionId() {
            return activeSessionId;
        }

        public static void startStation(final Context context, final SharedPreferences prefs, final String streamUrl, final String stationName, final Runnable onStarted, final Runnable onError) {
            if (prefs != null) cachedPrefs = prefs;
            stopStation();
            activeStationUrl = streamUrl != null ? streamUrl : "";
            activeStationName = stationName != null ? stationName : "RADIO";
            radioExecutor.execute(() -> {
                final String resolved = resolveRedirectsStatic(activeStationUrl);
                handler.post(() -> {
                    try {
                        stopStationInternalOnly();
                        radioPlayer = new MediaPlayer();
                        radioPlayer.setWakeMode(context.getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
                        if (Build.VERSION.SDK_INT >= 21) {
                            radioPlayer.setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build());
                        }
                        Map<String, String> headers = new HashMap<>();
                        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 14) Chrome/124.0.0.0 Mobile");
                        radioPlayer.setDataSource(context.getApplicationContext(), Uri.parse(resolved), headers);
                        radioPlayer.setOnPreparedListener(mp -> {
                            synchronized (PriorityRadioEngine.class) {
                                isPlaying = true;
                                activeSessionId = mp.getAudioSessionId();
                                applyCurrentVolumeLocked(cachedPrefs);
                                mp.start();
                                handler.removeCallbacks(duckWatchdogRunnable);
                                handler.postDelayed(duckWatchdogRunnable, 500);
                            }
                            if (onStarted != null) onStarted.run();
                        });
                        radioPlayer.setOnErrorListener((mp, what, extra) -> {
                            if (onError != null) onError.run();
                            return true;
                        });
                        radioPlayer.prepareAsync();
                    } catch (Exception e) {
                        if (onError != null) onError.run();
                    }
                });
            });
        }

        public static synchronized void togglePause(SharedPreferences prefs) {
            if (prefs != null) cachedPrefs = prefs;
            if (radioPlayer == null) return;
            try {
                if (radioPlayer.isPlaying()) {
                    radioPlayer.pause();
                    isPlaying = false;
                } else {
                    isPlaying = true;
                    applyCurrentVolumeLocked(cachedPrefs);
                    radioPlayer.start();
                    handler.removeCallbacks(duckWatchdogRunnable);
                    handler.postDelayed(duckWatchdogRunnable, 500);
                }
            } catch (Exception ignored) {}
        }

        private static synchronized void stopStationInternalOnly() {
            handler.removeCallbacks(restoreVolumeRunnable);
            handler.removeCallbacks(clearPendingTtsWaitRunnable);
            handler.removeCallbacks(duckWatchdogRunnable);
            restoreScheduled = false;
            isPlaying = false;
            activeSessionId = 0;
            if (radioPlayer != null) {
                try { if (radioPlayer.isPlaying()) radioPlayer.stop(); } catch (Exception ignored) {}
                try { radioPlayer.reset(); radioPlayer.release(); } catch (Exception ignored) {}
                radioPlayer = null;
            }
        }

        public static synchronized void stopStation() {
            activeStationUrl = "";
            activeStationName = "";
            stopStationInternalOnly();
        }

        private static String resolveRedirectsStatic(String urlStr) {
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
    }
    private WebView webView;
    private SharedPreferences prefs;
    private AudioManager audioManager;
    private SensorManager sensorManager;
    private Sensor tiltSensor;
    private float smoothTiltX = 0f;
    private float smoothTiltY = 0f;
    private long lastTiltPushMs = 0L;

    private AudioFocusRequest scannerAudioFocusReq = null;
    private PowerManager.WakeLock scannerCpuLock = null;
    private WifiManager.WifiLock scannerWifiLock = null;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService logPollExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean nativeLogPollInFlight = false;
    private volatile long lastNativeLogPollMs = 0L;
    private volatile long lastRxEndedMs = 0L;

    private boolean userWakeLockEnabled = false;
    private boolean scannerAudioConnected = false;
    private boolean bgAudioModeEnabled = false;
    private boolean bedroomLampOn = false;
    private String activeServerUrl = "";
    private TextToSpeech tts = null;
    private volatile boolean ttsReady = false;
    private volatile String activeTtsUtteranceId = "";

    private String lastSpokenText = "";
    private long lastSpokenTimestamp = 0;

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null || event.values.length < 2 || webView == null) return;
        long now = System.currentTimeMillis();
        if (now - lastTiltPushMs < 35) return;
        lastTiltPushMs = now;
        float rawX = Math.max(-7f, Math.min(7f, event.values[0])) / 7f;
        float rawY = Math.max(-7f, Math.min(7f, event.values[1] - 4.5f)) / 7f;
        smoothTiltX += (rawX - smoothTiltX) * 0.28f;
        smoothTiltY += (rawY - smoothTiltY) * 0.28f;
        int deg = Math.round(125f - smoothTiltX * 38f + smoothTiltY * 22f);
        int posPct = Math.round(38f + smoothTiltX * 28f - smoothTiltY * 20f);
        int bx = Math.round(-smoothTiltX * 14f);
        int by = Math.round(smoothTiltY * 14f);
        webView.evaluateJavascript(
                "try{var r=document.documentElement.style;" +
                "r.setProperty('--gloss-deg','" + deg + "deg');" +
                "r.setProperty('--gloss-pos','" + posPct + "%');" +
                "r.setProperty('--tilt-x','" + bx + "px');" +
                "r.setProperty('--tilt-y','" + by + "px');}catch(e){}",
                null
        );
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /**
     * Native Uncached Java Log Poller:
     * Never polls while scanner RX is active or during the 550ms post-squelch write window,
     * and strips out 00:00:00 silent kerchunks right at the source!
     */
    private void pollHistoryLogNatively() {
        long now = System.currentTimeMillis();
        if (nativeLogPollInFlight) return;
        if (PriorityRadioEngine.isScannerCurrentlyReceiving()) return;
        if (now - lastRxEndedMs < 550L) return;
        if (now - lastNativeLogPollMs < 450L) return;

        String base = activeServerUrl;
        if ((base == null || base.isEmpty()) && webView != null) {
            base = webView.getUrl();
        }
        if (base == null || base.isEmpty()) return;
        final String cleanBase = base.replaceAll("/+$", "");
        nativeLogPollInFlight = true;
        lastNativeLogPollMs = now;
        logPollExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(cleanBase + "/History%20Log.txt");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setUseCaches(false);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate");
                conn.setRequestProperty("Pragma", "no-cache");
                conn.setRequestProperty("Connection", "close");
                String cookie = CookieManager.getInstance().getCookie(cleanBase);
                if (cookie != null && !cookie.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookie);
                }
                int code = conn.getResponseCode();
                if (code == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    List<String> tailLines = new LinkedList<>();
                    int totalValidLines = 0;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if ((trimmed.contains(",") || trimmed.contains("\t"))
                                && !trimmed.toLowerCase(Locale.ROOT).startsWith("frequency")) {
                            totalValidLines++;
                            if (trimmed.contains("00:00:00")) {
                                continue;
                            }
                            tailLines.add(totalValidLines + "\t" + trimmed);
                            if (tailLines.size() > 160) {
                                tailLines.remove(0);
                            }
                        }
                    }
                    reader.close();
                    if (!tailLines.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (String l : tailLines) {
                            sb.append(l).append("\n");
                        }
                        final String quotedLog = JSONObject.quote(sb.toString());
                        mainHandler.post(() -> {
                            if (webView != null) {
                                webView.evaluateJavascript("if(window.__onNativeHistoryLogText)window.__onNativeHistoryLogText(" + quotedLog + ");", null);
                            }
                        });
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) {}
                }
                nativeLogPollInFlight = false;
            }
        });
    }

    private synchronized void acquireScannerWakeAndWifiLocks() {
        try {
            if (audioManager != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    if (scannerAudioFocusReq == null) {
                        scannerAudioFocusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                                .setAudioAttributes(new AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build())
                                .setAcceptsDelayedFocusGain(true)
                                .setWillPauseWhenDucked(false)
                                .setOnAudioFocusChangeListener(fc -> {}, mainHandler)
                                .build();
                    }
                    audioManager.requestAudioFocus(scannerAudioFocusReq);
                } else {
                    audioManager.requestAudioFocus(fc -> {}, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                }
            }
            if (scannerCpuLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    scannerCpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "proscan:main_scanner_cpu");
                    scannerCpuLock.setReferenceCounted(false);
                }
            }
            if (scannerCpuLock != null && !scannerCpuLock.isHeld()) {
                scannerCpuLock.acquire();
            }
            if (scannerWifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                    scannerWifiLock = wm.createWifiLock(mode, "proscan:main_scanner_wifi");
                    scannerWifiLock.setReferenceCounted(false);
                }
            }
            if (scannerWifiLock != null && !scannerWifiLock.isHeld()) {
                scannerWifiLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private synchronized void releaseScannerWakeAndWifiLocks() {
        if (PriorityRadioEngine.isRadioPlaying()) return;
        try {
            if (scannerCpuLock != null && scannerCpuLock.isHeld()) scannerCpuLock.release();
            if (scannerWifiLock != null && scannerWifiLock.isHeld()) scannerWifiLock.release();
        } catch (Exception ignored) {}
    }

    private void triggerAlexaWebhook(final boolean turnOn) {
        final String offUrl = prefs.getString("alexa_lamp_off_url", "").trim();
        final String onUrl = prefs.getString("alexa_lamp_on_url", "").trim();
        if (offUrl.isEmpty() && onUrl.isEmpty()) {
            showAlexaLampSetupDialog();
            return;
        }
        final String targetUrl;
        final String actionLabel;
        if (!offUrl.isEmpty() && !onUrl.isEmpty()) {
            bedroomLampOn = turnOn;
            targetUrl = bedroomLampOn ? onUrl : offUrl;
            actionLabel = bedroomLampOn ? "BEDROOM LIGHTS: ON" : "BEDROOM LIGHTS: OFF";
        } else {
            bedroomLampOn = !bedroomLampOn;
            targetUrl = !offUrl.isEmpty() ? offUrl : onUrl;
            actionLabel = "BEDROOM LIGHTS: TRIGGERED";
        }
        if (webView != null) {
            webView.evaluateJavascript(
                    "if(typeof showDisplayHud==='function')showDisplayHud('" + actionLabel + "',1500);",
                    null
            );
        }
        Toast.makeText(this, actionLabel, Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(targetUrl).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Cache-Control", "no-cache");
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code < 200 || code >= 400) {
                    mainHandler.post(() -> Toast.makeText(MainActivity.this, "Routine HTTP " + code, Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "Routine failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showAlexaLampSetupDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        TextView l1 = new TextView(this);
        l1.setText("Lights Toggle / OFF Alexa Routine URL (Virtual Buttons / Voice Monkey):");
        layout.addView(l1);
        final EditText offInput = new EditText(this);
        offInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        offInput.setHint("https://api.virtualbuttons.com/v1?...");
        offInput.setText(prefs.getString("alexa_lamp_off_url", ""));
        layout.addView(offInput);

        TextView l2 = new TextView(this);
        l2.setText("\nSecond Routine URL (Optional - leave blank for single toggle URL):");
        layout.addView(l2);
        final EditText onInput = new EditText(this);
        onInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        onInput.setHint("Optional second routine URL");
        onInput.setText(prefs.getString("alexa_lamp_on_url", ""));
        layout.addView(onInput);

        new AlertDialog.Builder(this)
                .setTitle("Bedroom Lights (Alexa Routine)")
                .setView(layout)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.edit()
                            .putString("alexa_lamp_off_url", offInput.getText().toString().trim())
                            .putString("alexa_lamp_on_url", onInput.getText().toString().trim())
                            .apply();
                    Toast.makeText(MainActivity.this, "Bedroom Light Routine Saved! Tap LAMP to trigger.", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Test Now", (d, w) -> {
                    prefs.edit()
                            .putString("alexa_lamp_off_url", offInput.getText().toString().trim())
                            .putString("alexa_lamp_on_url", onInput.getText().toString().trim())
                            .apply();
                    triggerAlexaWebhook(false);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

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
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String utteranceId) {
                            PriorityRadioEngine.onTtsSpeechStateChanged(prefs, true);
                        }

                        @Override
                        public void onDone(String utteranceId) {
                            if (utteranceId == null || utteranceId.equals(activeTtsUtteranceId)) {
                                PriorityRadioEngine.onTtsSpeechStateChanged(prefs, false);
                            }
                        }

                        @Override
                        public void onError(String utteranceId) {
                            if (utteranceId == null || utteranceId.equals(activeTtsUtteranceId)) {
                                PriorityRadioEngine.onTtsSpeechStateChanged(prefs, false);
                            }
                        }

                        @Override
                        public void onStop(String utteranceId, boolean interrupted) {
                            if (utteranceId == null || utteranceId.equals(activeTtsUtteranceId)) {
                                PriorityRadioEngine.onTtsSpeechStateChanged(prefs, false);
                            }
                        }
                    });
                    ttsReady = true;
                }
            });
        } catch (Exception ignored) {}
    }

    private void forceStopScannerOnHeadphoneDisconnect() {
        scannerAudioConnected = false;
        PriorityRadioEngine.stopStation();
        setBgServiceRunning(false);
        releaseScannerWakeAndWifiLocks();
        Runnable stopJs = () -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "try{if(window.__stopScannerAudioFromBt)window.__stopScannerAudioFromBt();" +
                        "var p=document.getElementById('audio_player');if(p){p.pause();}" +
                        "isConnected=false;var b=document.getElementById('btn-audio-toggle');if(b)b.classList.remove('active');" +
                        "window.__voiceReadoutEnabled=false;window.__vOn=false;var vb=document.getElementById('btn-voice-toggle');if(vb)vb.classList.remove('active');}catch(e){}",
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
            if (isInitialStickyBroadcast()) return;
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
                        || t == 26
                        || t == 27) {
                    forceStopScannerOnHeadphoneDisconnect();
                    break;
                }
            }
        }
    };

    private int injectorTickCount = 0;

    private final Runnable periodicInjector = new Runnable() {
        @Override
        public void run() {
            if (webView != null) {
                if ((injectorTickCount++ % 5) == 0) {
                    injectEnhancements();
                }
                if (scannerAudioConnected || PriorityRadioEngine.isRadioPlaying()) {
                    webView.evaluateJavascript(
                            "try{if(window.__bgTickSquelchAndLog)window.__bgTickSquelchAndLog();}catch(e){}",
                            null
                    );
                }
            }
            mainHandler.postDelayed(this, 120);
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
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS", "android.permission.RECORD_AUDIO"}, 101);
            } catch (Exception ignored) {}
        } else if (Build.VERSION.SDK_INT >= 23) {
            try {
                requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 101);
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

        try {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                tiltSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
                if (tiltSensor == null) {
                    tiltSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                }
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
                super.onWindowVisibilityChanged(View.VISIBLE);
            }

            @Override
            public void onWindowFocusChanged(boolean hasWindowFocus) {
                super.onWindowFocusChanged(true);
            }

            @Override
            public void onPause() {
            }

            @Override
            public void pauseTimers() {
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
            if (enable || scannerAudioConnected || PriorityRadioEngine.isRadioPlaying()) {
                acquireScannerWakeAndWifiLocks();
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
                else startService(svc);
            } else {
                releaseScannerWakeAndWifiLocks();
                stopService(svc);
            }
        } catch (Exception ignored) {}
    }

    private void saveBackupJsonToDownloads(String webStorageJson, boolean showToast) {
        try {
            JSONObject root = new JSONObject();
            root.put("local_url", prefs.getString("local_url", ""));
            root.put("tailscale_url", prefs.getString("tailscale_url", ""));
            root.put("alexa_lamp_off_url", prefs.getString("alexa_lamp_off_url", ""));
            root.put("alexa_lamp_on_url", prefs.getString("alexa_lamp_on_url", ""));
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
            String lampOff = root.optString("alexa_lamp_off_url", "");
            String lampOn = root.optString("alexa_lamp_on_url", "");
            JSONObject webStorage = root.optJSONObject("web_storage");

            if (!loc.isEmpty() || !ts.isEmpty()) {
                prefs.edit().putString("local_url", loc).putString("tailscale_url", ts).apply();
            }
            if (!lampOff.isEmpty() || !lampOn.isEmpty()) {
                prefs.edit().putString("alexa_lamp_off_url", lampOff).putString("alexa_lamp_on_url", lampOn).apply();
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
        public void requestNativeLogPoll() {
            pollHistoryLogNatively();
        }

        @JavascriptInterface
        public void triggerBedroomLamp(final boolean turnOn) {
            mainHandler.post(() -> triggerAlexaWebhook(turnOn));
        }

        @JavascriptInterface
        public void openLampSetup() {
            mainHandler.post(() -> showAlexaLampSetupDialog());
        }

        @JavascriptInterface
        public void openAppSettings() {
            mainHandler.post(() -> showSetupDialog(false));
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
        public void setScannerAudioConnected(final boolean connected) {
            if (!connected) {
                PriorityRadioEngine.onScannerRxStateChanged(prefs, false, false);
            }
            mainHandler.post(() -> {
                scannerAudioConnected = connected;
                setBgServiceRunning(connected || bgAudioModeEnabled);
            });
        }

        @JavascriptInterface
        public void setBackgroundAudioMode(final boolean enabled) {
            mainHandler.post(() -> {
                setBgServiceRunning(enabled);
            });
        }

        @JavascriptInterface
        public boolean isAutoDuckOn() {
            return PriorityRadioEngine.isAutoDuckEnabled(prefs);
        }

        @JavascriptInterface
        public boolean toggleSimpleAutoDuck() {
            final boolean nextState = PriorityRadioEngine.toggleAutoDuck(prefs);
            mainHandler.post(() -> Toast.makeText(MainActivity.this,
                    nextState ? "Radio Auto-Duck: ON (Mutes on Scanner Hit)" : "Radio Auto-Duck: OFF",
                    Toast.LENGTH_SHORT).show());
            return nextState;
        }

        @JavascriptInterface
        public void notifyScannerRx(final boolean rxActive, final boolean voiceReadoutEnabled) {
            PriorityRadioEngine.onScannerRxStateChanged(prefs, rxActive, voiceReadoutEnabled);
            if (!rxActive) {
                lastRxEndedMs = System.currentTimeMillis();
                mainHandler.postDelayed(() -> pollHistoryLogNatively(), 650);
                mainHandler.postDelayed(() -> pollHistoryLogNatively(), 1850);
            }
        }

        @JavascriptInterface
        public void speakChannel(final String text) {
            if (text == null || text.trim().isEmpty()) return;
            mainHandler.post(() -> {
                final String cleanText = text.trim();

                long now = System.currentTimeMillis();
                if (!cleanText.equalsIgnoreCase("Voice readout on")
                        && cleanText.equalsIgnoreCase(lastSpokenText)
                        && (now - lastSpokenTimestamp < 15000)) {
                    if (tts == null || !tts.isSpeaking()) {
                        PriorityRadioEngine.onTtsSpeechStateChanged(prefs, false);
                    }
                    return;
                }
                lastSpokenText = cleanText;
                lastSpokenTimestamp = now;

                PriorityRadioEngine.onTtsSpeechStateChanged(prefs, true);

                if (tts == null || !ttsReady) {
                    initTTS();
                    mainHandler.postDelayed(() -> speakDirect(cleanText), 900);
                    return;
                }
                speakDirect(cleanText);
            });
        }

        private void speakDirect(String cleanText) {
            int result = TextToSpeech.ERROR;
            final String uttId = "proscan_tts_" + System.currentTimeMillis();
            activeTtsUtteranceId = uttId;
            try {
                if (Build.VERSION.SDK_INT >= 21) {
                    Bundle params = new Bundle();
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.45f);
                    result = tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, uttId);
                } else {
                    result = tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null);
                }
            } catch (Exception e) {
                result = TextToSpeech.ERROR;
            }

            mainHandler.postDelayed(() -> {
                if (uttId.equals(activeTtsUtteranceId) && (tts == null || !tts.isSpeaking())) {
                    PriorityRadioEngine.onTtsSpeechStateChanged(prefs, false);
                }
            }, 4200);

            if (result != TextToSpeech.SUCCESS) {
                initTTS();
                mainHandler.postDelayed(() -> {
                    if (tts != null && ttsReady) {
                        final String retryId = "proscan_tts_retry_" + System.currentTimeMillis();
                        activeTtsUtteranceId = retryId;
                        if (Build.VERSION.SDK_INT >= 21) {
                            Bundle params = new Bundle();
                            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.45f);
                            tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, retryId);
                        } else {
                            tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null);
                        }
                    } else {
                        PriorityRadioEngine.onTtsSpeechStateChanged(prefs, false);
                    }
                }, 1000);
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
                root.put("alexa_lamp_off_url", prefs.getString("alexa_lamp_off_url", ""));
                root.put("alexa_lamp_on_url", prefs.getString("alexa_lamp_on_url", ""));
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
        scannerAudioConnected = false;
        PriorityRadioEngine.stopStation();
        setBgServiceRunning(false);
        releaseScannerWakeAndWifiLocks();
        try { if (sensorManager != null) sensorManager.unregisterListener(this); } catch (Exception ignored) {}
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
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
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
                pollHistoryLogNatively();
            }
        });

        mainHandler.removeCallbacks(periodicInjector);
        mainHandler.postDelayed(periodicInjector, 200);
    }
    private void injectEnhancements() {
        String sharedStorage = prefs.getString("shared_web_storage", "");
        String safeShared = JSONObject.quote(sharedStorage);
        boolean wakePref = prefs.getBoolean("wake_lock_on", true);

        StringBuilder js = new StringBuilder();
        js.append("(function() {\n");
        js.append("  try {\n");
        js.append("    try {\n");
        js.append("      Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });\n");
        js.append("      Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });\n");
        js.append("    } catch(e) {}\n");

        js.append("    var existingSt = document.getElementById('proscan-v10-upgrade-css');\n");
        js.append("    if (document.head && !existingSt) {\n");
        js.append("      var st = document.createElement('style');\n");
        js.append("      st.id = 'proscan-v10-upgrade-css';\n");
        js.append("      st.textContent = '' +\n");
        js.append("        'html{--gloss-deg:125deg;--gloss-pos:38%;--tilt-x:0px;--tilt-y:0px;}' +\n");
        js.append("        '#device-wrapper{width:95%!important;max-height:94dvh!important;transition:opacity 0.18s ease!important;}' +\n");
        js.append("        '#device-wrapper.flipping-out{opacity:0!important;}' +\n");
        js.append("        '#custom-scanner-ui{padding:16px 16px!important;gap:8px!important;position:relative!important;}' +\n");
        js.append("        '#custom-scanner-ui::after{content:\"\"!important;position:absolute!important;inset:0!important;pointer-events:none!important;z-index:25!important;border-radius:inherit!important;background:radial-gradient(circle at calc(50% + var(--tilt-x,0px) * 4.5) calc(24% + var(--tilt-y,0px) * 4.5),rgba(255,255,255,0.13) 0%,rgba(255,255,255,0.04) 34%,transparent 65%)!important;}' +\n");
        js.append("        '.scanner-display{height:325px!important;position:relative!important;overflow:hidden!important;transition:height 0.22s ease!important;}' +\n");
        js.append("        'html[data-disp-tex=\"glass\"] .scanner-display::after{content:\"\"!important;position:absolute!important;inset:0!important;pointer-events:none!important;z-index:18!important;opacity:1!important;background:linear-gradient(var(--gloss-deg,125deg),rgba(255,255,255,0.22) 0%,rgba(255,255,255,0.08) calc(var(--gloss-pos,38%) - 4%),rgba(255,255,255,0.26) var(--gloss-pos,38%),rgba(255,255,255,0.03) calc(var(--gloss-pos,38%) + 2%),transparent calc(var(--gloss-pos,38%) + 12%),transparent 100%),radial-gradient(circle at center,transparent 60%,rgba(0,0,0,0.32) 100%)!important;}' +\n");

        /* Battery HUD Follows All Bad Contact / Glitch Animations Alongside Clock & Log! */
        js.append("        'html:is([data-disp-tex=\"glitch\"],[data-disp-tex=\"cracked-glitch\"]):is(.bc-blur,.bc-blur-blink,.bc-deep-blur) #scanner-bat-hud:not(.disconnected){filter:blur(2.4px)!important;}' +\n");
        js.append("        'html:is([data-disp-tex=\"glitch\"],[data-disp-tex=\"cracked-glitch\"]):is(.bc-fuzzy,.bc-flicker) #scanner-bat-hud:not(.disconnected){filter:blur(1.2px)!important;}' +\n");
        js.append("        'html:is([data-disp-tex=\"glitch\"],[data-disp-tex=\"cracked-glitch\"]).bc-wobble #scanner-bat-hud:not(.disconnected){animation:bcWobS 0.46s steps(2,end) forwards!important;}' +\n");
        js.append("        'html:is([data-disp-tex=\"glitch\"],[data-disp-tex=\"cracked-glitch\"]):is(.bc-flicker,.bc-fast-blink,.bc-blur-blink) #scanner-bat-hud:not(.disconnected){animation:bcFlkM 0.62s steps(1,end) forwards!important;}' +\n");
        js.append("        'html:is([data-disp-tex=\"glitch\"],[data-disp-tex=\"cracked-glitch\"]):is(.bc-wavy,.bc-wavy-blink) #scanner-bat-hud:not(.disconnected){animation:bcWavS 0.82s ease-in-out forwards!important;}' +\n");

        /* Log FX Keyframes Built Directly Into Mobile CSS */
        js.append("        '@keyframes logFxBlock{0%{background:var(--log-text);color:var(--display-bg);font-weight:900}55%{background:var(--log-text);color:var(--display-bg);font-weight:900}100%{background:transparent;color:var(--log-text)}}' +\n");
        js.append("        '@keyframes logFxScroll{0%{opacity:0;transform:translateY(-100%)}100%{opacity:1;transform:translateY(0)}}' +\n");
        js.append("        '@keyframes logFxFlap{0%{transform:perspective(300px) rotateX(-88deg);opacity:0}60%{transform:perspective(300px) rotateX(14deg);opacity:1}100%{transform:perspective(300px) rotateX(0deg);opacity:1}}' +\n");
        js.append("        '@keyframes logFxType{0%{clip-path:inset(0 100% 0 0)}100%{clip-path:inset(0 0 0 0)}}' +\n");
        js.append("        '@keyframes logFxGlow{0%{text-shadow:0 0 12px var(--log-text),0 0 22px var(--log-text);background:rgba(255,255,255,.18)}100%{text-shadow:none;background:transparent}}' +\n");
        js.append("        'html[data-log-fx=\"fx-block\"] #mini-log-table tbody tr.new-log-row td{animation:logFxBlock 1.6s ease-out forwards!important;}' +\n");
        js.append("        'html[data-log-fx=\"fx-scroll\"] #mini-log-table tbody tr.new-log-row{animation:logFxScroll 0.42s cubic-bezier(0.22,1,0.36,1) forwards!important;}' +\n");
        js.append("        'html[data-log-fx=\"fx-flap\"] #mini-log-table tbody tr.new-log-row{transform-origin:top center;animation:logFxFlap 0.55s ease-out forwards!important;}' +\n");
        js.append("        'html[data-log-fx=\"fx-type\"] #mini-log-table tbody tr.new-log-row{animation:logFxType 0.65s steps(24,end) forwards!important;}' +\n");
        js.append("        'html[data-log-fx=\"fx-glow\"] #mini-log-table tbody tr.new-log-row td{animation:logFxGlow 1.9s ease-out forwards!important;}' +\n");

        js.append("        '.top-controls-container{height:40px!important;display:grid!important;grid-template-columns:1fr auto 1fr!important;align-items:center!important;}' +\n");
        js.append("        '.top-leds-left{justify-self:start!important;gap:8px!important;align-items:center!important;}' +\n");
        js.append("        '#brand-logo-slot{justify-self:center!important;display:inline-flex!important;align-items:center!important;justify-content:center!important;gap:8px!important;}' +\n");
        js.append("        '.top-leds-right{justify-self:end!important;gap:8px!important;align-items:center!important;}' +\n");
        js.append("        '.top-right-group{height:34px!important;}' +\n");
        js.append("        '.top-right-group .btn{font-size:11px!important;}' +\n");
        js.append("        '.scanner-nav-6{display:grid!important;grid-template-columns:repeat(6,minmax(0,1fr))!important;gap:4px!important;height:32px!important;}' +\n");
        js.append("        '.scanner-nav-6 .btn{font-size:8.5px!important;letter-spacing:-0.3px!important;padding:2px 1px!important;white-space:nowrap!important;}' +\n");
        js.append("        '.essential-controls{grid-auto-rows:33px!important;gap:6px!important;}' +\n");
        js.append("        '.essential-controls > .btn{font-size:11.5px!important;}' +\n");
        js.append("        '.essential-controls .scroll-btn{font-size:9px!important;}' +\n");
        js.append("        '.numpad{grid-auto-rows:34px!important;gap:6px!important;}' +\n");
        js.append("        '.numpad .btn{font-size:15.5px!important;font-weight:900!important;}' +\n");
        js.append("        'html.tv-off .scanner-display, .scanner-display.tv-shutting-down{background:#000000!important;border-color:#0a0a0a!important;box-shadow:inset 0 0 24px #000000!important;filter:brightness(1)!important;transition:none!important;}' +\n");
        js.append("        'html.tv-off .scanner-display #custom-imgd, html.tv-off .scanner-display #custom-imgf, html.tv-off .scanner-display .log-wrapper, html.tv-off .scanner-display #display-clock-hud, html.tv-off .scanner-display #scanner-bat-hud, html.tv-off .scanner-display #display-font-hud, .scanner-display.tv-shutting-down #custom-imgd, .scanner-display.tv-shutting-down #custom-imgf, .scanner-display.tv-shutting-down .log-wrapper, .scanner-display.tv-shutting-down #display-clock-hud, .scanner-display.tv-shutting-down #scanner-bat-hud, .scanner-display.tv-shutting-down #display-font-hud{opacity:0!important;visibility:hidden!important;animation:none!important;transition:none!important;}' +\n");
        js.append("        'html[data-skin=\"whiteline\"]{--ui-bg:radial-gradient(circle at 50% 12%,#26292e 0%,#121417 65%,#070809 100%)!important;--btn-bg:linear-gradient(180deg,#2e3238 0%,#181a1e 55%,#0d0e11 100%)!important;--btn-color:#ffffff!important;--btn-active-color:#38bdf8!important;--btn-border:1px solid rgba(255,255,255,0.75)!important;--btn-radius:7px!important;--btn-shadow:0 3px 0 #94a3b8,0 4px 6px rgba(0,0,0,0.85),inset 0 1px 1px rgba(255,255,255,0.45)!important;--btn-shadow-active:0 0 0 #94a3b8,inset 0 2px 5px rgba(0,0,0,0.9)!important;--btn-active-transform:translateY(2px)!important;}' +\n");
        js.append("        'html[data-skin=\"whiteline\"] .btn:active{background:linear-gradient(180deg,#181a1e 0%,#0d0e11 100%)!important;color:#38bdf8!important;}' +\n");
        js.append("        'html[data-skin=\"tron\"]{--ui-bg:radial-gradient(circle at 50% 12%,#0c2e36 0%,#06161a 65%,#02080a 100%)!important;--btn-bg:linear-gradient(180deg,#103c47 0%,#071f26 55%,#031014 100%)!important;--btn-color:#00ffff!important;--btn-active-color:#ffffff!important;--btn-border:1px solid #00ffff!important;--btn-radius:7px!important;--btn-shadow:0 3px 0 #008b8b,0 4px 7px rgba(0,0,0,0.85),inset 0 1px 1px rgba(180,255,255,0.45)!important;--btn-shadow-active:0 0 0 #008b8b,inset 0 2px 5px rgba(0,0,0,0.9)!important;--btn-active-transform:translateY(2px)!important;}' +\n");
        js.append("        'html[data-skin=\"tron\"] .btn:active{background:linear-gradient(180deg,#071f26 0%,#031014 100%)!important;color:#ffffff!important;box-shadow:0 0 12px rgba(0,255,255,0.5),inset 0 2px 5px rgba(0,0,0,0.9)!important;}' +\n");
        js.append("        'html[data-skin=\"neongreen\"]{--ui-bg:radial-gradient(circle at 50% 12%,#11330d 0%,#081906 65%,#030a02 100%)!important;--btn-bg:linear-gradient(180deg,#184212 0%,#0b2108 55%,#051104 100%)!important;--btn-color:#39ff14!important;--btn-active-color:#ffffff!important;--btn-border:1px solid #39ff14!important;--btn-radius:7px!important;--btn-shadow:0 3px 0 #1b8a08,0 4px 7px rgba(0,0,0,0.85),inset 0 1px 1px rgba(190,255,180,0.45)!important;--btn-shadow-active:0 0 0 #1b8a08,inset 0 2px 5px rgba(0,0,0,0.9)!important;--btn-active-transform:translateY(2px)!important;}' +\n");
        js.append("        'html[data-skin=\"neongreen\"] .btn:active{background:linear-gradient(180deg,#0b2108 0%,#051104 100%)!important;color:#ffffff!important;box-shadow:0 0 12px rgba(57,255,20,0.5),inset 0 2px 5px rgba(0,0,0,0.9)!important;}' +\n");
        js.append("        'html[data-skin=\"amber\"]{--ui-bg:radial-gradient(circle at 50% 12%,#332406 0%,#1a1203 65%,#0a0701 100%)!important;--btn-bg:linear-gradient(180deg,#422f08 0%,#211704 55%,#120c02 100%)!important;--btn-color:#ffb000!important;--btn-active-color:#ffffff!important;--btn-border:1px solid #ffb000!important;--btn-radius:7px!important;--btn-shadow:0 3px 0 #996900,0 4px 7px rgba(0,0,0,0.85),inset 0 1px 1px rgba(255,230,170,0.45)!important;--btn-shadow-active:0 0 0 #996900,inset 0 2px 5px rgba(0,0,0,0.9)!important;--btn-active-transform:translateY(2px)!important;}' +\n");
        js.append("        'html[data-skin=\"amber\"] .btn:active{background:linear-gradient(180deg,#211704 0%,#120c02 100%)!important;color:#ffffff!important;box-shadow:0 0 12px rgba(255,176,0,0.5),inset 0 2px 5px rgba(0,0,0,0.9)!important;}' +\n");
        js.append("        'html[data-scanner-lite=\"true\"] .scanner-display{height:420px!important;}' +\n");
        js.append("        'html[data-scanner-lite=\"true\"] .essential-controls > *:nth-child(4),' +\n");
        js.append("        'html[data-scanner-lite=\"true\"] .essential-controls > *:nth-child(5),' +\n");
        js.append("        'html[data-scanner-lite=\"true\"] .essential-controls > *:nth-child(6),' +\n");
        js.append("        'html[data-scanner-lite=\"true\"] .essential-controls > *:nth-child(8),' +\n");
        js.append("        'html[data-scanner-lite=\"true\"] .essential-controls > *:nth-child(11),' +\n");
        js.append("        'html[data-scanner-lite=\"true\"] .essential-controls > *:nth-child(12),' +\n");
        js.append("        'html[data-scanner-lite=\"true\"] #btn-brt-toggle{display:none!important;}' +\n");
        js.append("        'html[data-scanner-lite=\"true\"] .essential-controls{grid-auto-rows:44px!important;gap:8px!important;}' +\n");
        js.append("        'html[data-scanner-lite=\"true\"] .essential-controls > .btn{font-size:13.5px!important;font-weight:900!important;letter-spacing:0.6px!important;}' +\n");
        js.append("        'html[data-scanner-lite=\"true\"] .essential-controls .scroll-btn{font-size:10.5px!important;font-weight:900!important;}' +\n");
        js.append("        'html[data-scanner-lite=\"true\"] .numpad{grid-auto-rows:40px!important;gap:7px!important;}' +\n");
        js.append("        'html[data-scanner-lite=\"true\"] .numpad .btn{font-size:16.5px!important;font-weight:900!important;}' +\n");
        js.append("        'html[data-scanner-lite=\"true\"] .top-right-group{height:38px!important;}' +\n");
        js.append("        'html[data-scanner-lite=\"true\"] .top-right-group .btn{font-size:12px!important;}' +\n");
        js.append("        '@media (orientation: landscape) and (max-height: 540px){' +\n");
        js.append("          '#device-wrapper{width:96vw!important;max-width:96vw!important;height:94dvh!important;max-height:94dvh!important;}' +\n");
        js.append("          '#custom-scanner-ui{display:grid!important;grid-template-columns:1.08fr 1fr!important;grid-template-rows:auto 1fr!important;gap:6px 12px!important;padding:10px 14px!important;height:100%!important;overflow:hidden!important;}' +\n");
        js.append("          '.scanner-display, html[data-scanner-lite=\"true\"] .scanner-display{grid-column:1!important;grid-row:1 / span 2!important;height:100%!important;min-height:0!important;}' +\n");
        js.append("          '#custom-imgd{max-height:92px!important;}' +\n");
        js.append("          '.top-controls-container{grid-column:2!important;grid-row:1!important;height:34px!important;}' +\n");
        js.append("          '.buttons-box{grid-column:2!important;grid-row:2!important;height:100%!important;min-height:0!important;padding:6px!important;gap:5px!important;justify-content:space-between!important;}' +\n");
        js.append("          '.top-btn-wrapper{gap:4px!important;}' +\n");
        js.append("          '.top-right-group, html[data-scanner-lite=\"true\"] .top-right-group{height:26px!important;}' +\n");
        js.append("          '.scanner-nav-6{height:25px!important;gap:3px!important;}' +\n");
        js.append("          '.keypads-wrapper{flex-direction:row!important;gap:6px!important;flex:1!important;min-height:0!important;}' +\n");
        js.append("          '.keypad-half{flex:1!important;height:100%!important;}' +\n");
        js.append("          '.essential-controls, .numpad, html[data-scanner-lite=\"true\"] .essential-controls, html[data-scanner-lite=\"true\"] .numpad{height:100%!important;grid-auto-rows:1fr!important;gap:4px!important;}' +\n");
        js.append("          '.essential-controls > .btn, html[data-scanner-lite=\"true\"] .essential-controls > .btn{font-size:9px!important;letter-spacing:0!important;}' +\n");
        js.append("          '.essential-controls .scroll-btn, .essential-controls .split-btn-group .btn, html[data-scanner-lite=\"true\"] .essential-controls .scroll-btn{font-size:7.5px!important;}' +\n");
        js.append("          '.numpad .btn, html[data-scanner-lite=\"true\"] .numpad .btn{font-size:13px!important;}' +\n");
        js.append("        '}' +\n");
        js.append("        '.buttons-box{border:1px solid rgba(0,0,0,0.48)!important;border-radius:12px!important;padding:8px!important;gap:8px!important;background:rgba(0,0,0,0.22)!important;box-shadow:inset 0 2px 6px rgba(0,0,0,0.75),0 1px 0 rgba(255,255,255,0.09)!important;}' +\n");
        js.append("        '.scanner-display .log-container{display:flex!important;flex-direction:column!important;overflow:hidden!important;}' +\n");
        js.append("        '#mini-log-table{display:flex!important;flex-direction:column!important;height:100%!important;width:100%!important;}' +\n");
        js.append("        '#mini-log-table thead{flex:0 0 auto!important;display:table!important;width:100%!important;table-layout:fixed!important;background:transparent!important;}' +\n");
        js.append("        '#mini-log-table thead th{background:transparent!important;position:static!important;}' +\n");
        js.append("        '#mini-log-table tbody{flex:1 1 auto!important;display:block!important;overflow-y:auto!important;overflow-x:hidden!important;width:100%!important;scrollbar-width:thin!important;scrollbar-color:var(--log-header-text) transparent!important;}' +\n");
        js.append("        '#mini-log-table tbody::-webkit-scrollbar{width:4px!important;}' +\n");
        js.append("        '#mini-log-table tbody::-webkit-scrollbar-thumb{background:var(--log-header-text)!important;border-radius:2px!important;}' +\n");
        js.append("        '#mini-log-table tbody tr{display:table!important;width:100%!important;table-layout:fixed!important;cursor:pointer;}' +\n");
        js.append("        '#mini-log-table tbody tr:active{background:rgba(255,255,255,0.15)!important;}' +\n");
        js.append("        '.freq-hit-badge, #mini-log-table .freq-hit-badge, #mini-log-table td:nth-child(5) .freq-hit-badge, #mini-log-table td:nth-child(5) span[class*=\"hit\"], #mini-log-table td:nth-child(5) span[class*=\"badge\"]{display:none!important;visibility:hidden!important;width:0!important;height:0!important;padding:0!important;margin:0!important;border:none!important;overflow:hidden!important;font-size:0!important;}' +\n");
        js.append("        '#mini-log-table th,#mini-log-table td{padding:3.5px 2px 3.5px 4px!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(1),#mini-log-table td:nth-child(1){width:19%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(2),#mini-log-table td:nth-child(2){width:20%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(3),#mini-log-table td:nth-child(3){width:13%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(4),#mini-log-table td:nth-child(4){width:14%!important;}' +\n");
        js.append("        '#mini-log-table th:nth-child(5),#mini-log-table td:nth-child(5){width:34%!important;}' +\n");
        js.append("        '#btn-wake-toggle, #btn-voice-toggle, #btn-lamp-toggle, #btn-hardware-exit{display:inline-flex!important;flex-direction:column!important;align-items:center!important;justify-content:center!important;gap:2px!important;background:transparent!important;border:none!important;padding:0!important;margin:0 1px!important;cursor:pointer!important;touch-action:manipulation!important;box-shadow:none!important;}' +\n");
        js.append("        '#btn-wake-toggle > *:not(.sq-hw-cap):not(.sq-hw-lbl), #btn-voice-toggle > *:not(.sq-hw-cap):not(.sq-hw-lbl){display:none!important;}' +\n");
        js.append("        '.sq-hw-cap{width:28px!important;height:22px!important;border-radius:5px!important;background:linear-gradient(180deg,#262a30 0%,#14171a 100%)!important;border:1.5px solid #090a0c!important;box-shadow:0 2px 4px rgba(0,0,0,0.85),inset 0 1px 1px rgba(255,255,255,0.12)!important;display:flex!important;align-items:center!important;justify-content:center!important;transition:all 0.2s ease!important;position:relative!important;}' +\n");
        js.append("        '.sq-hw-cap::after{content:\"\"!important;width:12px!important;height:4px!important;border-radius:2px!important;background:#334155!important;box-shadow:inset 0 1px 2px rgba(0,0,0,0.8)!important;transition:all 0.2s ease!important;}' +\n");
        js.append("        '.sq-hw-lbl{font-family:\"JetBrains Mono\",monospace!important;font-size:7px!important;font-weight:900!important;letter-spacing:0.4px!important;color:var(--toggle-color,#94a3b8)!important;line-height:1!important;}' +\n");
        js.append("        '#btn-wake-toggle:active .sq-hw-cap, #btn-voice-toggle:active .sq-hw-cap, #btn-lamp-toggle:active .sq-hw-cap, #btn-hardware-exit:active .sq-hw-cap{transform:translateY(1.5px)!important;}' +\n");
        js.append("        '#btn-wake-toggle.active .sq-hw-cap{background:linear-gradient(180deg,#10b981 0%,#047857 100%)!important;border-color:#6ee7b7!important;box-shadow:0 0 10px rgba(16,185,129,0.85),0 0 18px rgba(16,185,129,0.45),inset 0 1px 2px rgba(255,255,255,0.65)!important;}' +\n");
        js.append("        '#btn-wake-toggle.active .sq-hw-cap::after{background:#ecfdf5!important;box-shadow:0 0 6px #ffffff!important;}' +\n");
        js.append("        '#btn-voice-toggle.active .sq-hw-cap{background:linear-gradient(180deg,#0ea5e9 0%,#0369a1 100%)!important;border-color:#7dd3fc!important;box-shadow:0 0 10px rgba(14,165,233,0.85),0 0 18px rgba(14,165,233,0.45),inset 0 1px 2px rgba(255,255,255,0.65)!important;}' +\n");
        js.append("        '#btn-voice-toggle.active .sq-hw-cap::after{background:#f0f9ff!important;box-shadow:0 0 6px #ffffff!important;}' +\n");
        js.append("        '#btn-lamp-toggle.active .sq-hw-cap{background:linear-gradient(180deg,#facc15 0%,#ca8a04 100%)!important;border-color:#fef08a!important;box-shadow:0 0 11px rgba(250,204,21,0.9),0 0 20px rgba(250,204,21,0.5),inset 0 1px 2px rgba(255,255,255,0.75)!important;}' +\n");
        js.append("        '#btn-lamp-toggle.active .sq-hw-cap::after{background:#fefce8!important;box-shadow:0 0 7px #ffffff!important;}' +\n");
        js.append("        '#btn-hardware-exit.active .sq-hw-cap{background:linear-gradient(180deg,#ef4444 0%,#b91c1c 100%)!important;border-color:#fca5a5!important;box-shadow:0 0 10px rgba(239,68,68,0.85),0 0 18px rgba(239,68,68,0.45),inset 0 1px 2px rgba(255,255,255,0.6)!important;}' +\n");
        js.append("        '#btn-hardware-exit.active .sq-hw-cap::after{background:#fef2f2!important;box-shadow:0 0 6px #ffffff!important;}' +\n");
        js.append("        '#sig-meter-host{display:inline-flex;align-items:center;justify-content:center;width:118px;height:23px;background:rgba(6,8,12,0.82);border:1px solid rgba(255,255,255,0.16);border-radius:5px;padding:2px 5px;box-shadow:inset 0 2px 4px rgba(0,0,0,0.9),0 1px 0 rgba(255,255,255,0.1);position:relative;overflow:hidden;pointer-events:auto;cursor:pointer;}' +\n");
        js.append("        '.sm-mode{display:none;width:100%;height:100%;align-items:center;justify-content:space-between;}' +\n");
        js.append("        '.sm-mode.active{display:flex;}' +\n");
        js.append("        '.sm-lbl{font-family:\"JetBrains Mono\",monospace;font-size:7.5px;font-weight:900;color:#38bdf8;letter-spacing:0.3px;margin-right:3px;flex-shrink:0;}' +\n");
        js.append("        '.sm-bar-wrap{display:flex;align-items:center;gap:2px;flex:1;height:11px;justify-content:space-between;}' +\n");
        js.append("        '.sm-seg{flex:1;height:100%;border-radius:1px;background:#1e293b;transition:background 0.04s linear;}' +\n");
        js.append("        '.sm-vu-wrap{width:100%;height:100%;background:linear-gradient(90deg,#fef9c3 0%,#fde047 68%,#f59e0b 100%);border-radius:3px;position:relative;overflow:hidden;border:1px solid #000;box-shadow:inset 0 1px 3px rgba(0,0,0,0.45);}' +\n");
        js.append("        '.sm-vu-wrap::after{content:\"\";position:absolute;left:4px;right:4px;bottom:1px;height:5px;border-bottom:1.5px solid #0f172a;background:repeating-linear-gradient(90deg,#0f172a 0px,#0f172a 2px,transparent 2px,transparent 8px);opacity:0.9;z-index:1;pointer-events:none;}' +\n");
        js.append("        '.sm-vu-scale{position:absolute;top:1px;left:4px;right:4px;font-family:monospace;font-size:6.5px;font-weight:900;color:#0f172a;display:flex;justify-content:space-between;line-height:1;z-index:2;pointer-events:none;}' +\n");
        js.append("        '#sm-vu-needle{position:absolute;top:0;bottom:0;left:3px;width:2.5px;background:#dc2626;border-radius:1px;box-shadow:0 0 4px rgba(220,38,38,0.9),1px 0 2px rgba(0,0,0,0.6);transition:left 0.14s ease-out;z-index:3;}' +\n");
        js.append("        '.sm-spec-wrap{display:flex;align-items:flex-end;justify-content:space-between;gap:2px;width:100%;height:15px;padding-top:1px;}' +\n");
        js.append("        '.sm-spec-bar{flex:1;height:2px;background:linear-gradient(180deg,#ef4444 0%,#facc15 35%,#22c55e 100%);border-radius:1px 1px 0 0;transition:height 0.04s linear;}' +\n");
        js.append("        '#sm-scope-canvas{width:100%;height:16px;display:block;}' +\n");
        js.append("        '.sm-dig-wrap{display:flex;align-items:center;justify-content:space-between;width:100%;gap:4px;font-family:\"JetBrains Mono\",monospace;}' +\n");
        js.append("        '#sm-dig-txt{font-size:8.5px;font-weight:900;color:#39ff14;min-width:46px;text-align:left;letter-spacing:-0.3px;}' +\n");
        js.append("        '.sm-peak-track{flex:1;height:8px;background:#0f172a;border:1px solid #334155;border-radius:4px;overflow:hidden;position:relative;}' +\n");
        js.append("        '#sm-peak-fill{height:100%;width:0%;background:linear-gradient(90deg,#22c55e 0%,#facc15 65%,#ef4444 100%);transition:width 0.14s ease-out;}' +\n");
        js.append("        '#display-clock-hud{position:absolute!important;top:6px!important;left:10px!important;right:auto!important;z-index:3!important;}' +\n");
        js.append("        '#scanner-bat-hud{position:absolute!important;top:6px!important;right:9px!important;left:auto!important;z-index:3!important;font-family:\"JetBrains Mono\",monospace!important;font-size:10px!important;font-weight:700!important;letter-spacing:0.8px!important;color:var(--log-text)!important;opacity:0.88;pointer-events:none;display:inline-flex!important;align-items:center!important;line-height:1;transition:opacity 0.3s ease,color 0.3s ease;}' +\n");
        js.append("        '#scanner-bat-hud *{font-family:inherit!important;font-size:inherit!important;font-weight:inherit!important;letter-spacing:inherit!important;line-height:inherit!important;}' +\n");
        js.append("        '#scanner-bat-txt{color:inherit!important;}' +\n");
        js.append("        '#scanner-bat-hud.disconnected{opacity:0!important;}' +\n");
        js.append("        '.bat-icon-box{display:inline-block;width:13px;height:8px;border:1.4px solid currentColor;border-radius:2px;padding:1px;position:relative;vertical-align:middle;margin-right:4px;box-sizing:border-box;flex-shrink:0;}' +\n");
        js.append("        '.bat-icon-box::after{content:\"\";position:absolute;right:-3px;top:1px;width:1.5px;height:3px;background:currentColor;border-radius:0 1px 1px 0;}' +\n");
        js.append("        '.bat-icon-fill{display:block;height:100%;background:currentColor;width:80%;transition:width 0.3s ease;}';\n");
        js.append("      document.head.appendChild(st);\n");
        js.append("    } else if (existingSt && document.head.lastElementChild !== existingSt) {\n");
        js.append("      document.head.appendChild(existingSt);\n");
        js.append("    }\n");

        /* Always Clear Web_Server_User_HTML.htm's Duplicate logInterval Timer! */
        js.append("    try { if (typeof logInterval !== 'undefined') clearInterval(logInterval); } catch(e) {}\n");
        js.append("    try { if (typeof rxHoldTimeout !== 'undefined') clearTimeout(rxHoldTimeout); } catch(e) {}\n");
        js.append("    try { if (typeof rxPostLogTimeout !== 'undefined') clearTimeout(rxPostLogTimeout); } catch(e) {}\n");

        /* Global Native TTS Helper + Zero-Duration Filter + Top-Row-Only Animation */
        js.append("    if (!window.__reliableLogAndSquelchHooked) {\n");
        js.append("      window.__reliableLogAndSquelchHooked = true;\n");
        js.append("      window.__lastRenderedIdsSig = '';\n");
        js.append("      window.__lastTopRowId = '';\n");
        js.append("      window.__lastSpokenRowKey = '';\n");
        js.append("      window.__rxTtsWaitTimer = null;\n");
        js.append("      window.__lastRealAudioMs = 0;\n");
        js.append("      window.__voiceEnergyFrames = 0;\n");

        /* Intercept Web_Server_User_HTML.htm's fetch('History Log.txt') so it can NEVER overwrite the log with 00:00 rows! */
        js.append("      if (window.fetch && !window.__fetchInterceptedForLog) {\n");
        js.append("        window.__fetchInterceptedForLog = true;\n");
        js.append("        var origFetch = window.fetch.bind(window);\n");
        js.append("        window.fetch = function(resource, init) {\n");
        js.append("          var urlStr = (typeof resource === 'string') ? resource : (resource && resource.url ? resource.url : '');\n");
        js.append("          if (urlStr.indexOf('History Log.txt') !== -1 || urlStr.indexOf('History%20Log.txt') !== -1) {\n");
        js.append("            if (window.AndroidApp && window.AndroidApp.requestNativeLogPoll) {\n");
        js.append("              window.AndroidApp.requestNativeLogPoll();\n");
        js.append("            }\n");
        js.append("            return new Promise(function() {});\n");
        js.append("          }\n");
        js.append("          return origFetch(resource, init);\n");
        js.append("        };\n");
        js.append("      }\n");

        js.append("      window.__speakTextAfterRx = function(txt, immediate) {\n");
        js.append("        if (!window.AndroidApp || !txt) return;\n");
        js.append("        var clean = String(txt).trim();\n");
        js.append("        if (!clean) return;\n");
        js.append("        if (immediate) {\n");
        js.append("          window.AndroidApp.speakChannel(clean);\n");
        js.append("          return;\n");
        js.append("        }\n");
        js.append("        clearTimeout(window.__rxTtsWaitTimer);\n");
        js.append("        var tries = 0;\n");
        js.append("        (function waitRxOff() {\n");
        js.append("          var rx = document.getElementById('rx-led');\n");
        js.append("          if (rx && rx.classList.contains('active') && tries++ < 35) {\n");
        js.append("            window.__rxTtsWaitTimer = setTimeout(waitRxOff, 200);\n");
        js.append("          } else {\n");
        js.append("            window.__rxTtsWaitTimer = setTimeout(function() {\n");
        js.append("              if (window.__voiceReadoutEnabled || window.__vOn) {\n");
        js.append("                window.AndroidApp.speakChannel(clean);\n");
        js.append("              }\n");
        js.append("            }, 150);\n");
        js.append("          }\n");
        js.append("        })();\n");
        js.append("      };\n");

        js.append("      window.__onNativeHistoryLogText = function(text) {\n");
        js.append("        isLogFetchInFlight = false;\n");
        js.append("        if (!text || text.toLowerCase().indexOf('<html') !== -1) return;\n");
        js.append("        var rawLines = text.trim().split('\\n').filter(function(l) { return (l.indexOf(',') !== -1 || l.indexOf('\\t') !== -1) && !l.trim().toLowerCase().startsWith('frequency'); });\n");
        js.append("        if (!rawLines.length) return;\n");
        js.append("        var counts = {}, validRows = [];\n");
        js.append("        for (var k = 0; k < rawLines.length; k++) {\n");
        js.append("          var rawL = rawLines[k], tabIdx = rawL.indexOf('\\t');\n");
        js.append("          var lNum = String(k + 1), dLine = rawL;\n");
        js.append("          if (tabIdx > 0 && /^\\d+$/.test(rawL.slice(0, tabIdx))) {\n");
        js.append("            lNum = rawL.slice(0, tabIdx);\n");
        js.append("            dLine = rawL.slice(tabIdx + 1);\n");
        js.append("          }\n");
        js.append("          var p = dLine.split(',');\n");
        js.append("          if (p.length < 5) continue;\n");
        js.append("          var fq = (p[0] || '---').trim(), tn = (p[1] || '').trim(), nm = (p[p.length - 1] || '---').trim(), du = '---', tr = '---';\n");
        js.append("          if (!fq || fq === '---') continue;\n");
        js.append("          for (var j = 0; j < p.length; j++) {\n");
        js.append("            var v = p[j].trim();\n");
        js.append("            if (/^\\d{2}:\\d{2}:\\d{2}$/.test(v)) du = v;\n");
        js.append("            else if (/^\\d{1,4}[\\/\\-]\\d{1,2}[\\/\\-]\\d{1,4}\\s+\\d{1,2}:\\d{2}(:\\d{2})?$/.test(v)) tr = v;\n");
        js.append("          }\n");
        js.append("          if (du.indexOf('00:') === 0) du = du.slice(3);\n");
        js.append("          var durSec = 0, dp = du.split(':');\n");
        js.append("          if (dp.length === 2) durSec = (parseInt(dp[0], 10) || 0) * 60 + (parseInt(dp[1], 10) || 0);\n");
        js.append("          else if (dp.length === 3) durSec = (parseInt(dp[0], 10) || 0) * 3600 + (parseInt(dp[1], 10) || 0) * 60 + (parseInt(dp[2], 10) || 0);\n");
        js.append("          if (durSec <= 0) continue;\n");
        js.append("          counts[fq] = (counts[fq] || 0) + 1;\n");
        js.append("          var tp = tr.split(' '), tm = tp.length > 1 ? tp[tp.length - 1] : tr;\n");
        js.append("          validRows.push({\n");
        js.append("            lineKey: lNum + '|' + tm + '|' + fq + '|' + nm,\n");
        js.append("            tm: tm, fq: fq, tn: tn, du: du, nm: nm, hit: counts[fq]\n");
        js.append("          });\n");
        js.append("        }\n");
        js.append("        if (!validRows.length) return;\n");
        js.append("        var topRowObj = validRows[validRows.length - 1];\n");
        js.append("        var isNewTopHit = (!isFirstLoad && window.__lastTopRowId !== '' && topRowObj.lineKey !== window.__lastTopRowId);\n");
        js.append("        window.__lastTopRowId = topRowObj.lineKey;\n");
        js.append("        var html = '', idsSig = '', added = 0;\n");
        js.append("        for (var i = validRows.length - 1; i >= 0 && added < 25; i--) {\n");
        js.append("          var r = validRows[i];\n");
        js.append("          var showFxClass = (added === 0 && isNewTopHit);\n");
        js.append("          idsSig += r.lineKey + '|' + r.du + ';';\n");
        js.append("          html += '<tr class=\"' + (showFxClass ? 'new-log-row' : '') + '\" data-line-key=\"' + r.lineKey + '\"><td title=\"' + r.tm + '\">' + r.tm + '</td><td title=\"' + r.fq + '\">' + r.fq + '</td><td title=\"' + r.tn + '\">' + r.tn + '</td><td title=\"' + r.du + '\">' + r.du + '</td><td title=\"(Hit #' + r.hit + ') ' + r.nm + '\" data-hit-count=\"' + r.hit + '\"><span class=\"freq-hit-badge\" style=\"display:none\">×' + r.hit + '</span>' + r.nm + '</td></tr>';\n");
        js.append("          added++;\n");
        js.append("        }\n");
        js.append("        if (html && isDisplayOn) {\n");
        js.append("          var tb = document.getElementById('mini-log-data');\n");
        js.append("          if (tb && (isNewTopHit || window.__lastRenderedIdsSig !== idsSig || !tb.children.length)) {\n");
        js.append("            window.__lastRenderedIdsSig = idsSig;\n");
        js.append("            tb.innerHTML = html;\n");
        js.append("          }\n");
        js.append("        }\n");
        js.append("        if (isNewTopHit) {\n");
        js.append("          if (typeof triggerLogLight === 'function') triggerLogLight();\n");
        js.append("          var spkName = (topRowObj.nm && topRowObj.nm !== '---') ? topRowObj.nm : topRowObj.fq;\n");
        js.append("          if ((window.__voiceReadoutEnabled || window.__vOn) && spkName && topRowObj.lineKey !== window.__lastSpokenRowKey) {\n");
        js.append("            window.__lastSpokenRowKey = topRowObj.lineKey;\n");
        js.append("            window.__speakTextAfterRx(spkName, false);\n");
        js.append("          }\n");
        js.append("        }\n");
        js.append("        isFirstLoad = false;\n");
        js.append("      };\n");

        js.append("      window.updateMiniLog = function() {\n");
        js.append("        try { if (typeof logInterval !== 'undefined') clearInterval(logInterval); } catch(e) {}\n");
        js.append("        if (typeof isDisplayOn !== 'undefined' && !isDisplayOn && (typeof isConnected === 'undefined' || !isConnected)) return;\n");
        js.append("        if (window.AndroidApp && window.AndroidApp.requestNativeLogPoll) {\n");
        js.append("          window.AndroidApp.requestNativeLogPoll();\n");
        js.append("        }\n");
        js.append("      };\n");

        js.append("      window.detectAudio = function() {};\n");
        js.append("      window.__lastLogPollMs = 0;\n");
        js.append("      window.__lastActiveRxPushMs = 0;\n");
        js.append("      window.__bgTickSquelchAndLog = function() {\n");
        js.append("        var nowWall = Date.now();\n");
        js.append("        var led = document.getElementById('rx-led');\n");
        js.append("        var isRxCurrentlyLit = Boolean(led && led.classList.contains('active'));\n");
        js.append("        if ((isDisplayOn || isConnected) && !isRxCurrentlyLit && (nowWall - window.__lastLogPollMs >= 3000)) {\n");
        js.append("          window.__lastLogPollMs = nowWall;\n");
        js.append("          window.updateMiniLog();\n");
        js.append("        }\n");
        js.append("        var activeAn = window.analyser || (typeof analyser !== 'undefined' ? analyser : null);\n");
        js.append("        if (typeof isConnected === 'undefined' || !isConnected || !activeAn || typeof dataArray === 'undefined' || !dataArray) {\n");
        js.append("          if (led && led.classList.contains('active')) led.classList.remove('active');\n");
        js.append("          window.__voiceEnergyFrames = 0;\n");
        js.append("          if (window.__lastNotifiedRxState) {\n");
        js.append("            window.__lastNotifiedRxState = false;\n");
        js.append("            if (window.AndroidApp && window.AndroidApp.notifyScannerRx) {\n");
        js.append("              window.AndroidApp.notifyScannerRx(false, Boolean(window.__voiceReadoutEnabled || window.__vOn));\n");
        js.append("            }\n");
        js.append("          }\n");
        js.append("          return;\n");
        js.append("        }\n");
        js.append("        if (window.audioContext && window.audioContext.state === 'suspended') {\n");
        js.append("          try { window.audioContext.resume(); } catch(e) {}\n");
        js.append("        }\n");
        js.append("        activeAn.getByteTimeDomainData(dataArray);\n");
        js.append("        var maxDev = 0, sumSq = 0;\n");
        js.append("        for (var i = 0; i < dataArray.length; i++) {\n");
        js.append("          var d = Math.abs(dataArray[i] - 128);\n");
        js.append("          if (d > maxDev) maxDev = d;\n");
        js.append("          sumSq += d * d;\n");
        js.append("        }\n");
        js.append("        var rms = Math.sqrt(sumSq / dataArray.length);\n");
        js.append("        var now = performance.now();\n");
        js.append("        if (maxDev >= 4 && rms >= 1.2) {\n");
        js.append("          window.__voiceEnergyFrames = (window.__voiceEnergyFrames || 0) + 1;\n");
        js.append("          var isKey = (now - lastScannerKeyTime) < 2200;\n");
        js.append("          if (rxSignalStartTime === 0) rxSignalStartTime = now;\n");
        js.append("          rxLastActiveTime = now;\n");
        js.append("          var reqMs = isKey ? 320 : 120;\n");
        js.append("          if ((now - rxSignalStartTime) >= reqMs && window.__voiceEnergyFrames >= 3) {\n");
        js.append("            window.__lastRealAudioMs = nowWall;\n");
        js.append("            if (led && !led.classList.contains('active')) led.classList.add('active');\n");
        js.append("            if (!window.__lastNotifiedRxState || (nowWall - window.__lastActiveRxPushMs > 350)) {\n");
        js.append("              window.__lastNotifiedRxState = true;\n");
        js.append("              window.__lastActiveRxPushMs = nowWall;\n");
        js.append("              if (window.AndroidApp && window.AndroidApp.notifyScannerRx) {\n");
        js.append("                window.AndroidApp.notifyScannerRx(true, Boolean(window.__voiceReadoutEnabled || window.__vOn));\n");
        js.append("              }\n");
        js.append("            }\n");
        js.append("          }\n");
        js.append("        } else {\n");
        js.append("          var silentMs = rxLastActiveTime > 0 ? (now - rxLastActiveTime) : 99999;\n");
        js.append("          if (silentMs > 160) { rxSignalStartTime = 0; window.__voiceEnergyFrames = 0; }\n");
        js.append("          if (silentMs >= 360) {\n");
        js.append("            if (led && led.classList.contains('active')) led.classList.remove('active');\n");
        js.append("            if (window.__lastNotifiedRxState) {\n");
        js.append("              window.__lastNotifiedRxState = false;\n");
        js.append("              window.__lastRealAudioMs = nowWall;\n");
        js.append("              window.__lastLogPollMs = nowWall;\n");
        js.append("              if (window.AndroidApp && window.AndroidApp.notifyScannerRx) {\n");
        js.append("                window.AndroidApp.notifyScannerRx(false, Boolean(window.__voiceReadoutEnabled || window.__vOn));\n");
        js.append("              }\n");
        js.append("            }\n");
        js.append("          }\n");
        js.append("        }\n");
        js.append("      };\n");
        js.append("      setInterval(window.__bgTickSquelchAndLog, 45);\n");
        js.append("    }\n");
        /* Symmetrical Center Slot: [ RX ] — [ S-METER ] — [ LOG ] */
        js.append("    var bSlot = document.getElementById('brand-logo-slot');\n");
        js.append("    if (bSlot && !document.getElementById('sig-meter-host')) {\n");
        js.append("      window.renderCustomBrand = function() {};\n");
        js.append("      var mShortNames = ['BAR', 'VU', 'EQ', 'SCP', 'dB'];\n");
        js.append("      var mHudNames   = ['S-BAR', 'VU DIAL', 'SPECTRUM', 'SCOPE', 'PEAK dB'];\n");
        js.append("      window.__sigMeterStyle = parseInt(localStorage.getItem('proscan_sig_meter_style_v1') || '0', 10) || 0;\n");
        js.append("      var segHtml = ''; for (var s=0; s<12; s++) segHtml += '<div class=\"sm-seg\"></div>';\n");
        js.append("      var specHtml = ''; for (var sp=0; sp<10; sp++) specHtml += '<div class=\"sm-spec-bar\"></div>';\n");
        js.append("      bSlot.innerHTML = '<div id=\"sig-meter-host\" title=\"Tap to cycle Signal Meter style\">' +\n");
        js.append("        '<div class=\"sm-mode\" id=\"sm-mode-0\"><span class=\"sm-lbl\">S</span><div class=\"sm-bar-wrap\" id=\"sm-led-bars\">' + segHtml + '</div></div>' +\n");
        js.append("        '<div class=\"sm-mode\" id=\"sm-mode-1\"><div class=\"sm-vu-wrap\"><div class=\"sm-vu-scale\"><span>S1</span><span>3</span><span>5</span><span>7</span><span>9</span><span style=\"color:#dc2626;\">+30</span></div><div id=\"sm-vu-needle\"></div></div></div>' +\n");
        js.append("        '<div class=\"sm-mode\" id=\"sm-mode-2\"><span class=\"sm-lbl\">EQ</span><div class=\"sm-spec-wrap\" id=\"sm-spec-bars\">' + specHtml + '</div></div>' +\n");
        js.append("        '<div class=\"sm-mode\" id=\"sm-mode-3\"><canvas id=\"sm-scope-canvas\" width=\"114\" height=\"16\"></canvas></div>' +\n");
        js.append("        '<div class=\"sm-mode\" id=\"sm-mode-4\"><div class=\"sm-dig-wrap\"><span id=\"sm-dig-txt\">S0 -98dB</span><div class=\"sm-peak-track\"><div id=\"sm-peak-fill\"></div></div></div></div>' +\n");
        js.append("      '</div>';\n");

        js.append("      var rxEl = document.getElementById('rx-led');\n");
        js.append("      var logEl = document.getElementById('log-led');\n");
        js.append("      var mHost = document.getElementById('sig-meter-host');\n");
        js.append("      if (rxEl && rxEl.parentNode && mHost) bSlot.insertBefore(rxEl.parentNode, mHost);\n");
        js.append("      if (logEl && logEl.parentNode && mHost) bSlot.appendChild(logEl.parentNode);\n");

        js.append("      window.__applySigMeterStyle = function(idx, showHudMsg) {\n");
        js.append("        window.__sigMeterStyle = ((idx % 5) + 5) % 5;\n");
        js.append("        localStorage.setItem('proscan_sig_meter_style_v1', String(window.__sigMeterStyle));\n");
        js.append("        for (var m = 0; m < 5; m++) {\n");
        js.append("          var el = document.getElementById('sm-mode-' + m);\n");
        js.append("          if (el) el.classList.toggle('active', m === window.__sigMeterStyle);\n");
        js.append("        }\n");
        js.append("        var mBtn = document.getElementById('btn-brand-open');\n");
        js.append("        if (mBtn) mBtn.innerText = 'MTR: ' + mShortNames[window.__sigMeterStyle];\n");
        js.append("        if (showHudMsg && typeof showDisplayHud === 'function') {\n");
        js.append("          showDisplayHud('SIGNAL METER: ' + mHudNames[window.__sigMeterStyle], 1300);\n");
        js.append("        }\n");
        js.append("      };\n");

        js.append("      window.handleBrandCornerTap = function() {\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(20);\n");
        js.append("        var bp = document.getElementById('proscan-brand-panel');\n");
        js.append("        if (bp) bp.hidden = true;\n");
        js.append("        window.__applySigMeterStyle((window.__sigMeterStyle || 0) + 1, true);\n");
        js.append("      };\n");

        js.append("      if (mHost) mHost.addEventListener('click', window.handleBrandCornerTap);\n");
        js.append("      window.__applySigMeterStyle(window.__sigMeterStyle, false);\n");

        js.append("      var smCarrierLvl = 0, smAudioLvl = 0, smAdaptiveMax = 14.0, smPhase = 0;\n");
        js.append("      function renderLiveScannerSignalMeter() {\n");
        js.append("        requestAnimationFrame(renderLiveScannerSignalMeter);\n");
        js.append("        var rawAud = 0;\n");
        js.append("        var rxLedEl = document.getElementById('rx-led');\n");
        js.append("        var isRxVoiceActive = Boolean(rxLedEl && rxLedEl.classList.contains('active'));\n");
        js.append("        var activeAn = window.analyser || (typeof analyser !== 'undefined' ? analyser : null);\n");
        js.append("        if (isRxVoiceActive && typeof isConnected !== 'undefined' && isConnected && activeAn && typeof dataArray !== 'undefined' && dataArray) {\n");
        js.append("          try {\n");
        js.append("            activeAn.getByteTimeDomainData(dataArray);\n");
        js.append("            var sumSq = 0, peak = 0;\n");
        js.append("            for (var i = 0; i < dataArray.length; i++) {\n");
        js.append("              var d = Math.abs(dataArray[i] - 128);\n");
        js.append("              if (d > peak) peak = d;\n");
        js.append("              sumSq += d * d;\n");
        js.append("            }\n");
        js.append("            var rms = Math.sqrt(sumSq / dataArray.length);\n");
        js.append("            var combined = (rms * 0.65) + (peak * 0.35);\n");
        js.append("            if (peak > 2 && combined > 1.5) {\n");
        js.append("              if (combined > smAdaptiveMax) smAdaptiveMax = combined;\n");
        js.append("              else smAdaptiveMax = Math.max(12.0, smAdaptiveMax * 0.9992);\n");
        js.append("              var noiseFloor = 1.4;\n");
        js.append("              var norm = (combined - noiseFloor) / Math.max(4.0, (smAdaptiveMax * 0.82) - noiseFloor);\n");
        js.append("              norm = Math.max(0, Math.min(1.0, norm));\n");
        js.append("              rawAud = Math.min(0.98, Math.pow(norm, 0.72));\n");
        js.append("            }\n");
        js.append("          } catch(e) {}\n");
        js.append("        }\n");
        js.append("        var targetCarrier = isRxVoiceActive ? Math.min(0.94, 0.82 + rawAud * 0.12 + Math.sin(smPhase * 0.4) * 0.015) : 0;\n");
        js.append("        smCarrierLvl += (targetCarrier - smCarrierLvl) * (targetCarrier > smCarrierLvl ? 0.16 : 0.10);\n");
        js.append("        if (smCarrierLvl < 0.01) smCarrierLvl = 0;\n");
        js.append("        smAudioLvl += (rawAud - smAudioLvl) * (rawAud > smAudioLvl ? 0.42 : 0.22);\n");
        js.append("        if (smAudioLvl < 0.015) smAudioLvl = 0;\n");

        js.append("        var stIdx = window.__sigMeterStyle || 0;\n");
        js.append("        if (stIdx === 0) {\n");
        js.append("          var activeSegs = smCarrierLvl < 0.03 ? 0 : Math.min(12, Math.ceil(smCarrierLvl * 12));\n");
        js.append("          var segs = document.querySelectorAll('#sm-led-bars .sm-seg');\n");
        js.append("          for (var k = 0; k < segs.length; k++) {\n");
        js.append("            if (k < activeSegs) {\n");
        js.append("              var col = k >= 9 ? '#ef4444' : (k >= 6 ? '#facc15' : '#22c55e');\n");
        js.append("              segs[k].style.background = col;\n");
        js.append("              segs[k].style.boxShadow = '0 0 4px ' + col;\n");
        js.append("            } else {\n");
        js.append("              segs[k].style.background = '#1e293b';\n");
        js.append("              segs[k].style.boxShadow = 'none';\n");
        js.append("            }\n");
        js.append("          }\n");
        js.append("        } else if (stIdx === 1) {\n");
        js.append("          var ndl = document.getElementById('sm-vu-needle');\n");
        js.append("          if (ndl) {\n");
        js.append("            var pct = Math.min(100, Math.max(0, smCarrierLvl * 100));\n");
        js.append("            ndl.style.left = 'calc(3px + ' + (pct * 0.92).toFixed(1) + '%)';\n");
        js.append("          }\n");
        js.append("        } else if (stIdx === 2) {\n");
        js.append("          var bars = document.querySelectorAll('#sm-spec-bars .sm-spec-bar');\n");
        js.append("          var hasFreq = (typeof isConnected !== 'undefined' && isConnected && activeAn && typeof freqArray !== 'undefined' && freqArray);\n");
        js.append("          if (hasFreq) { try { activeAn.getByteFrequencyData(freqArray); } catch(e) {} }\n");
        js.append("          for (var b = 0; b < bars.length; b++) {\n");
        js.append("            var h = 2;\n");
        js.append("            if (smAudioLvl >= 0.03) {\n");
        js.append("              var bell = Math.sin(((b + 1) / 11) * Math.PI);\n");
        js.append("              var wv = 0.55 + 0.45 * Math.sin(b * 0.8 + smPhase) * Math.cos(b * 0.4 - smPhase * 0.7);\n");
        js.append("              var binBoost = 0;\n");
        js.append("              if (hasFreq) {\n");
        js.append("                var bin = Math.min(freqArray.length - 1, 2 + b * 5);\n");
        js.append("                binBoost = (freqArray[bin] / 255.0) * 6.0;\n");
        js.append("              }\n");
        js.append("              h = Math.max(2, Math.min(15, Math.round(bell * wv * smAudioLvl * 16 + binBoost)));\n");
        js.append("            }\n");
        js.append("            bars[b].style.height = h + 'px';\n");
        js.append("          }\n");
        js.append("        } else if (stIdx === 3) {\n");
        js.append("          var cv = document.getElementById('sm-scope-canvas');\n");
        js.append("          if (cv) {\n");
        js.append("            var c = cv.getContext('2d');\n");
        js.append("            var W = cv.width, H = cv.height;\n");
        js.append("            c.clearRect(0, 0, W, H);\n");
        js.append("            c.strokeStyle = smAudioLvl >= 0.03 ? '#39ff14' : '#334155';\n");
        js.append("            c.lineWidth = 1.4;\n");
        js.append("            c.beginPath();\n");
        js.append("            for (var x = 0; x < W; x += 2) {\n");
        js.append("              var env = Math.sin((x / W) * Math.PI);\n");
        js.append("              var y = (H / 2) + Math.sin(x * 0.14 + smPhase) * Math.cos(x * 0.06 - smPhase * 0.5) * env * (smAudioLvl * (H * 0.48));\n");
        js.append("              if (x === 0) c.moveTo(x, y); else c.lineTo(x, y);\n");
        js.append("            }\n");
        js.append("            c.stroke();\n");
        js.append("          }\n");
        js.append("        } else if (stIdx === 4) {\n");
        js.append("          var dTxt = document.getElementById('sm-dig-txt');\n");
        js.append("          var pFill = document.getElementById('sm-peak-fill');\n");
        js.append("          if (dTxt) {\n");
        js.append("            if (smCarrierLvl < 0.03) {\n");
        js.append("              dTxt.innerText = 'S0 -98dB';\n");
        js.append("              dTxt.style.color = '#64748b';\n");
        js.append("            } else {\n");
        js.append("              var sUnit = Math.min(9, Math.max(1, Math.round(smCarrierLvl * 10)));\n");
        js.append("              var dbVal = -92 + Math.round(smCarrierLvl * 58);\n");
        js.append("              var plusStr = smCarrierLvl > 0.80 ? ('S9+' + Math.round((smCarrierLvl - 0.78) * 140)) : ('S' + sUnit + ' ' + dbVal + 'dB');\n");
        js.append("              dTxt.innerText = plusStr;\n");
        js.append("              dTxt.style.color = smCarrierLvl > 0.76 ? '#facc15' : '#39ff14';\n");
        js.append("            }\n");
        js.append("          }\n");
        js.append("          if (pFill) pFill.style.width = Math.min(100, Math.round(smCarrierLvl * 100)) + '%';\n");
        js.append("        }\n");
        js.append("        smPhase += isRxVoiceActive ? (0.14 + smAudioLvl * 0.28) : 0.02;\n");
        js.append("      }\n");
        js.append("      renderLiveScannerSignalMeter();\n");
        js.append("    }\n");

        js.append("    var oldGuideBtn = document.getElementById('btn-manual-open');\n");
        js.append("    if (oldGuideBtn) {\n");
        js.append("      var freshLiteBtn = document.createElement('button');\n");
        js.append("      freshLiteBtn.id = 'btn-lite-mode-custom';\n");
        js.append("      freshLiteBtn.className = 'btn';\n");
        js.append("      freshLiteBtn.type = 'button';\n");
        js.append("      oldGuideBtn.parentNode.replaceChild(freshLiteBtn, oldGuideBtn);\n");
        js.append("      window.__scannerLiteMode = localStorage.getItem('proscan_scanner_lite_v1') === 'true';\n");
        js.append("      window.__applyScannerLiteMode = function(active, showHudMsg) {\n");
        js.append("        window.__scannerLiteMode = Boolean(active);\n");
        js.append("        localStorage.setItem('proscan_scanner_lite_v1', String(window.__scannerLiteMode));\n");
        js.append("        document.documentElement.setAttribute('data-scanner-lite', window.__scannerLiteMode ? 'true' : 'false');\n");
        js.append("        var lb = document.getElementById('btn-lite-mode-custom');\n");
        js.append("        if (lb) {\n");
        js.append("          lb.classList.toggle('active', window.__scannerLiteMode);\n");
        js.append("          lb.innerText = window.__scannerLiteMode ? 'LITE: ON' : 'LITE: OFF';\n");
        js.append("        }\n");
        js.append("        if (showHudMsg && typeof showDisplayHud === 'function') {\n");
        js.append("          showDisplayHud('SCANNER LITE MODE: ' + (window.__scannerLiteMode ? 'ON' : 'OFF'), 1300);\n");
        js.append("        }\n");
        js.append("      };\n");
        js.append("      window.__applyScannerLiteMode(window.__scannerLiteMode, false);\n");
        js.append("      var liteLastClickMs = 0;\n");
        js.append("      freshLiteBtn.addEventListener('click', function(ev) {\n");
        js.append("        ev.preventDefault(); ev.stopPropagation(); ev.stopImmediatePropagation();\n");
        js.append("        var now = Date.now();\n");
        js.append("        if (now - liteLastClickMs < 280) return;\n");
        js.append("        liteLastClickMs = now;\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(20);\n");
        js.append("        window.__applyScannerLiteMode(!window.__scannerLiteMode, true);\n");
        js.append("      }, true);\n");
        js.append("      freshLiteBtn.addEventListener('contextmenu', function(ev) {\n");
        js.append("        ev.preventDefault(); ev.stopPropagation(); ev.stopImmediatePropagation();\n");
        js.append("        liteLastClickMs = Date.now();\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(35);\n");
        js.append("        if (typeof toggleAppMenuPanel === 'function') toggleAppMenuPanel();\n");
        js.append("        return false;\n");
        js.append("      }, true);\n");
        js.append("    }\n");

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
        js.append("    if (wBtn) {\n");
        js.append("      if (!wBtn.querySelector('.sq-hw-cap')) {\n");
        js.append("        wBtn.innerHTML = '<div class=\"sq-hw-cap\"></div><span class=\"sq-hw-lbl\">WAKE</span>';\n");
        js.append("      }\n");
        js.append("      if (!wBtn.__wakeSyncedOnce) {\n");
        js.append("        wBtn.__wakeSyncedOnce = true;\n");
        js.append("        isWakeLockActive = ").append(wakePref).append(";\n");
        js.append("      }\n");
        js.append("      wBtn.classList.toggle('active', isWakeLockActive);\n");
        js.append("      wBtn.setAttribute('aria-pressed', isWakeLockActive ? 'true' : 'false');\n");
        js.append("    }\n");
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
        js.append("      if (rowParent) rowParent.className = 'scanner-nav-6';\n");
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

        js.append("      window.__scannerDspOn = localStorage.getItem('proscan_scanner_dsp_v1') !== 'false';\n");
        js.append("      window.__applyScannerDspRouting = function() {\n");
        js.append("        if (!window.audioContext || !window.source || !window.analyser) return;\n");
        js.append("        if (!window.__dspNodes) {\n");
        js.append("          try {\n");
        js.append("            var ctx = window.audioContext;\n");
        js.append("            var hp = ctx.createBiquadFilter(); hp.type = 'highpass';\n");
        js.append("            var peak = ctx.createBiquadFilter(); peak.type = 'peaking';\n");
        js.append("            var lp = ctx.createBiquadFilter(); lp.type = 'lowpass';\n");
        js.append("            var comp = ctx.createDynamicsCompressor();\n");
        js.append("            comp.threshold.value = -24;\n");
        js.append("            comp.knee.value = 8;\n");
        js.append("            comp.ratio.value = 6;\n");
        js.append("            comp.attack.value = 0.002;\n");
        js.append("            comp.release.value = 0.20;\n");
        js.append("            hp.connect(peak); peak.connect(lp); lp.connect(comp);\n");
        js.append("            window.source.disconnect();\n");
        js.append("            window.source.connect(hp);\n");
        js.append("            comp.connect(window.analyser);\n");
        js.append("            window.__dspNodes = { hp: hp, peak: peak, lp: lp, comp: comp };\n");
        js.append("          } catch(e) {}\n");
        js.append("        }\n");
        js.append("        if (window.__dspNodes) {\n");
        js.append("          try {\n");
        js.append("            window.__dspNodes.hp.frequency.value = window.__scannerDspOn ? 340 : 40;\n");
        js.append("            window.__dspNodes.peak.frequency.value = 2150;\n");
        js.append("            window.__dspNodes.peak.Q.value = 1.1;\n");
        js.append("            window.__dspNodes.peak.gain.value = window.__scannerDspOn ? 8.5 : 0;\n");
        js.append("            window.__dspNodes.lp.frequency.value = window.__scannerDspOn ? 3900 : 16000;\n");
        js.append("          } catch(e) {}\n");
        js.append("        }\n");
        js.append("      };\n");

        js.append("      var dspBtn = document.createElement('button');\n");
        js.append("      dspBtn.id = 'btn-scanner-dsp'; dspBtn.className = 'btn'; dspBtn.type = 'button';\n");
        js.append("      function syncDspBtnUi(active) {\n");
        js.append("        dspBtn.classList.toggle('active', active);\n");
        js.append("        dspBtn.innerText = active ? 'DSP: ON' : 'DSP: OFF';\n");
        js.append("      }\n");
        js.append("      syncDspBtnUi(window.__scannerDspOn);\n");
        js.append("      dspBtn.addEventListener('click', function() {\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(20);\n");
        js.append("        window.__scannerDspOn = !window.__scannerDspOn;\n");
        js.append("        localStorage.setItem('proscan_scanner_dsp_v1', String(window.__scannerDspOn));\n");
        js.append("        syncDspBtnUi(window.__scannerDspOn);\n");
        js.append("        window.__applyScannerDspRouting();\n");
        js.append("        if (typeof showDisplayHud === 'function') {\n");
        js.append("          showDisplayHud('DSP CLARITY: ' + (window.__scannerDspOn ? 'ON' : 'OFF'), 1300);\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("      rowParent.appendChild(dspBtn);\n");
        js.append("    }\n");

        /* Always Hook #btn-voice-toggle (Even if Web_Server_User_HTML.htm Created It First!) */
        js.append("    var ledsLeft = document.querySelector('.top-leds-left');\n");
        js.append("    if (ledsLeft) {\n");
        js.append("      var existingVBtn = document.getElementById('btn-voice-toggle');\n");
        js.append("      if (!existingVBtn) {\n");
        js.append("        existingVBtn = document.createElement('button');\n");
        js.append("        existingVBtn.id = 'btn-voice-toggle'; existingVBtn.type = 'button';\n");
        js.append("        ledsLeft.appendChild(existingVBtn);\n");
        js.append("      }\n");
        js.append("      if (!existingVBtn.__nativeVoiceHooked) {\n");
        js.append("        var freshVBtn = existingVBtn.cloneNode(false);\n");
        js.append("        freshVBtn.id = 'btn-voice-toggle'; freshVBtn.type = 'button';\n");
        js.append("        freshVBtn.innerHTML = '<div class=\"sq-hw-cap\"></div><span class=\"sq-hw-lbl\">VOICE</span>';\n");
        js.append("        freshVBtn.__nativeVoiceHooked = true;\n");
        js.append("        existingVBtn.parentNode.replaceChild(freshVBtn, existingVBtn);\n");
        js.append("        window.__voiceReadoutEnabled = Boolean(window.__voiceReadoutEnabled || window.__vOn);\n");
        js.append("        freshVBtn.classList.toggle('active', window.__voiceReadoutEnabled);\n");

        js.append("        window.__setVoiceReadoutState = function(enable, showHud) {\n");
        js.append("          window.__voiceReadoutEnabled = Boolean(enable);\n");
        js.append("          window.__vOn = window.__voiceReadoutEnabled;\n");
        js.append("          var vb = document.getElementById('btn-voice-toggle');\n");
        js.append("          if (vb) vb.classList.toggle('active', window.__voiceReadoutEnabled);\n");
        js.append("          if (!window.__voiceReadoutEnabled) {\n");
        js.append("            clearTimeout(window.__rxTtsWaitTimer);\n");
        js.append("          }\n");
        js.append("          if (showHud && typeof showDisplayHud === 'function') {\n");
        js.append("            showDisplayHud('VOICE READOUT: ' + (window.__voiceReadoutEnabled ? 'ON' : 'OFF'), 1400);\n");
        js.append("          }\n");
        js.append("        };\n");

        js.append("        freshVBtn.addEventListener('click', function() {\n");
        js.append("          if (navigator.vibrate) navigator.vibrate(25);\n");
        js.append("          window.__setVoiceReadoutState(!window.__voiceReadoutEnabled, true);\n");
        js.append("          if (window.__voiceReadoutEnabled) {\n");
        js.append("            window.__speakTextAfterRx('Voice readout on', true);\n");
        js.append("          }\n");
        js.append("        });\n");
        js.append("      }\n");
        js.append("    }\n");

        js.append("    if (!window.__audioBufferCatchupHooked) {\n");
        js.append("      window.__audioBufferCatchupHooked = true;\n");
        js.append("      setInterval(function() {\n");
        js.append("        try {\n");
        js.append("          var p = document.getElementById('audio_player'), rx = document.getElementById('rx-led');\n");
        js.append("          if (p && !p.paused && (!rx || !rx.classList.contains('active')) && p.buffered && p.buffered.length > 0) {\n");
        js.append("            var end = p.buffered.end(p.buffered.length - 1);\n");
        js.append("            if (end - p.currentTime > 1.8) p.currentTime = end - 0.25;\n");
        js.append("          }\n");
        js.append("        } catch(e) {}\n");
        js.append("      }, 8000);\n");
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
        js.append("      window.__simpleAutoDuckOn = (window.AndroidApp && window.AndroidApp.isAutoDuckOn) ? window.AndroidApp.isAutoDuckOn() : true;\n");
        js.append("      function renderAudioLabel() {\n");
        js.append("        var duckTag = window.__simpleAutoDuckOn ? ' • DUCK' : '';\n");
        js.append("        aBtn.innerHTML = '<span class=\"inline-led\" id=\"audio-inline-led\"></span> AUDIO' + duckTag;\n");
        js.append("      }\n");
        js.append("      window.__setScannerAudioState = function(play) {\n");
        js.append("        var player = document.getElementById('audio_player');\n");
        js.append("        if (play) {\n");
        js.append("          isConnected = true;\n");
        js.append("          if (player) {\n");
        js.append("            player.setAttribute('playsinline', '');\n");
        js.append("            if (player.paused) player.play().catch(function(){});\n");
        js.append("            if (typeof setupAudioDetection === 'function') setupAudioDetection();\n");
        js.append("            if (window.__applyScannerDspRouting) setTimeout(window.__applyScannerDspRouting, 300);\n");
        js.append("          }\n");
        js.append("          aBtn.classList.add('active');\n");
        js.append("          if (window.AndroidApp && window.AndroidApp.setScannerAudioConnected) window.AndroidApp.setScannerAudioConnected(true);\n");
        js.append("          if (window.AndroidApp) window.AndroidApp.setBackgroundAudioMode(true);\n");
        js.append("        } else {\n");
        js.append("          isConnected = false;\n");
        js.append("          if (player && !player.paused) player.pause();\n");
        js.append("          aBtn.classList.remove('active');\n");
        js.append("          if (window.__setVoiceReadoutState) window.__setVoiceReadoutState(false, false);\n");
        js.append("          if (window.AndroidApp && window.AndroidApp.setScannerAudioConnected) window.AndroidApp.setScannerAudioConnected(false);\n");
        js.append("          if (window.AndroidApp) window.AndroidApp.setBackgroundAudioMode(false);\n");
        js.append("        }\n");
        js.append("        renderAudioLabel();\n");
        js.append("        if (window.__syncPwrGlowState) window.__syncPwrGlowState();\n");
        js.append("      };\n");
        js.append("      renderAudioLabel();\n");
        js.append("      setInterval(function() {\n");
        js.append("        if (typeof isConnected !== 'undefined' && isConnected) {\n");
        js.append("          var p = document.getElementById('audio_player');\n");
        js.append("          if (p && p.paused) { try { p.play().catch(function(){}); } catch(e){} }\n");
        js.append("          if (window.audioContext && window.audioContext.state === 'suspended') { try { window.audioContext.resume(); } catch(e){} }\n");
        js.append("        }\n");
        js.append("      }, 2500);\n");
        js.append("      window.__stopScannerAudioFromBt = function() { window.__setScannerAudioState(false); };\n");
        js.append("      var aTimer = null, aLong = false;\n");
        js.append("      aBtn.addEventListener('pointerdown', function() {\n");
        js.append("        clearTimeout(aTimer); aLong = false;\n");
        js.append("        aTimer = setTimeout(function() {\n");
        js.append("          aTimer = null; aLong = true; if (navigator.vibrate) navigator.vibrate(40);\n");
        js.append("          if (window.AndroidApp && window.AndroidApp.toggleSimpleAutoDuck) {\n");
        js.append("            window.__simpleAutoDuckOn = window.AndroidApp.toggleSimpleAutoDuck();\n");
        js.append("          } else {\n");
        js.append("            window.__simpleAutoDuckOn = !window.__simpleAutoDuckOn;\n");
        js.append("          }\n");
        js.append("          renderAudioLabel();\n");
        js.append("          if (typeof showDisplayHud === 'function') {\n");
        js.append("            showDisplayHud('RADIO AUTO-DUCK: ' + (window.__simpleAutoDuckOn ? 'ON' : 'OFF'), 1600);\n");
        js.append("          }\n");
        js.append("        }, 500);\n");
        js.append("      });\n");
        js.append("      aBtn.addEventListener('pointerup', function() {\n");
        js.append("        var wasActive = aTimer !== null; clearTimeout(aTimer); aTimer = null;\n");
        js.append("        if (wasActive && !aLong) { window.__setScannerAudioState(!isConnected); }\n");
        js.append("      });\n");
        js.append("      aBtn.addEventListener('pointerleave', function() { clearTimeout(aTimer); aTimer = null; });\n");
        js.append("      aBtn.addEventListener('pointercancel', function() { clearTimeout(aTimer); aTimer = null; });\n");
        js.append("    }\n");

        /* Top LEDs Right: LAMP and PWR (1 Tap = System Bars, 2 Taps = Minimize App, Long Press = Power ON / OFF & Exit) */
        js.append("    var ledsRight = document.querySelector('.top-leds-right');\n");
        js.append("    if (ledsRight && !document.getElementById('btn-hardware-exit')) {\n");
        js.append("      var lampBtn = document.createElement('button');\n");
        js.append("      lampBtn.id = 'btn-lamp-toggle'; lampBtn.type = 'button';\n");
        js.append("      lampBtn.title = 'Tap: Bedroom Lights | Hold: Setup Alexa Routine URL';\n");
        js.append("      lampBtn.innerHTML = '<div class=\"sq-hw-cap\"></div><span class=\"sq-hw-lbl\">LAMP</span>';\n");
        js.append("      ledsRight.appendChild(lampBtn);\n");
        js.append("      window.__scannerLampState = false;\n");
        js.append("      var lpTimer = null, lpLong = false;\n");
        js.append("      lampBtn.addEventListener('pointerdown', function() {\n");
        js.append("        clearTimeout(lpTimer); lpLong = false;\n");
        js.append("        lpTimer = setTimeout(function() {\n");
        js.append("          lpTimer = null; lpLong = true;\n");
        js.append("          if (navigator.vibrate) navigator.vibrate(40);\n");
        js.append("          if (window.AndroidApp && window.AndroidApp.openLampSetup) window.AndroidApp.openLampSetup();\n");
        js.append("        }, 500);\n");
        js.append("      });\n");
        js.append("      lampBtn.addEventListener('pointerup', function() {\n");
        js.append("        var wasTap = lpTimer !== null; clearTimeout(lpTimer); lpTimer = null;\n");
        js.append("        if (wasTap && !lpLong) {\n");
        js.append("          if (navigator.vibrate) navigator.vibrate(25);\n");
        js.append("          window.__scannerLampState = !window.__scannerLampState;\n");
        js.append("          lampBtn.classList.toggle('active', window.__scannerLampState);\n");
        js.append("          if (window.AndroidApp && window.AndroidApp.triggerBedroomLamp) {\n");
        js.append("            window.AndroidApp.triggerBedroomLamp(window.__scannerLampState);\n");
        js.append("          }\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("      lampBtn.addEventListener('pointerleave', function() { clearTimeout(lpTimer); lpTimer = null; });\n");
        js.append("      lampBtn.addEventListener('pointercancel', function() { clearTimeout(lpTimer); lpTimer = null; });\n");

        js.append("      var exitBtn = document.createElement('button');\n");
        js.append("      exitBtn.id = 'btn-hardware-exit'; exitBtn.type = 'button';\n");
        js.append("      exitBtn.title = '1 Tap: System Bars | 2 Taps: Minimize | Hold: Power ON/OFF';\n");
        js.append("      exitBtn.innerHTML = '<div class=\"sq-hw-cap\"></div><span class=\"sq-hw-lbl\">PWR</span>';\n");
        js.append("      ledsRight.appendChild(exitBtn);\n");
        js.append("      window.__syncPwrGlowState = function() {\n");
        js.append("        var anyOn = (typeof isDisplayOn !== 'undefined' && isDisplayOn) || (typeof isConnected !== 'undefined' && isConnected);\n");
        js.append("        exitBtn.classList.toggle('active', Boolean(anyOn));\n");
        js.append("      };\n");
        js.append("      window.__syncPwrGlowState();\n");
        js.append("      setInterval(window.__syncPwrGlowState, 600);\n");

        js.append("      var exHoldTimer = null, exSingleTapTimer = null, exLongFired = false, lastPwrTapMs = 0;\n");
        js.append("      exitBtn.addEventListener('pointerdown', function() {\n");
        js.append("        clearTimeout(exHoldTimer); exLongFired = false;\n");
        js.append("        exHoldTimer = setTimeout(function() {\n");
        js.append("          exHoldTimer = null; exLongFired = true;\n");
        js.append("          clearTimeout(exSingleTapTimer); exSingleTapTimer = null;\n");
        js.append("          var anyOn = (typeof isDisplayOn !== 'undefined' && isDisplayOn) || (typeof isConnected !== 'undefined' && isConnected);\n");
        js.append("          if (!anyOn) {\n");
        js.append("            if (navigator.vibrate) navigator.vibrate(40);\n");
        js.append("            if (typeof isDisplayOn !== 'undefined' && !isDisplayOn && typeof toggleDisplay === 'function') {\n");
        js.append("              toggleDisplay();\n");
        js.append("            }\n");
        js.append("            if (window.__setScannerAudioState) {\n");
        js.append("              window.__setScannerAudioState(true);\n");
        js.append("            }\n");
        js.append("            window.__syncPwrGlowState();\n");
        js.append("          } else {\n");
        js.append("            if (navigator.vibrate) navigator.vibrate([45, 60, 55]);\n");
        js.append("            if (window.__setScannerAudioState) window.__setScannerAudioState(false);\n");
        js.append("            isDisplayOn = false;\n");
        js.append("            window.__syncPwrGlowState();\n");
        js.append("            var dBtn = document.getElementById('btn-disp-toggle'); if (dBtn) dBtn.classList.remove('active');\n");
        js.append("            var dispEl = document.querySelector('.scanner-display');\n");
        js.append("            var logWrap = document.querySelector('.log-wrapper');\n");
        js.append("            var imgD = document.getElementById('custom-imgd'); if (imgD) imgD.classList.add('disconnected');\n");
        js.append("            var imgF = document.getElementById('custom-imgf'); if (imgF) imgF.classList.add('disconnected');\n");
        js.append("            var clk = document.getElementById('display-clock-hud'); if (clk) clk.classList.add('disconnected');\n");
        js.append("            var bat = document.getElementById('scanner-bat-hud'); if (bat) bat.classList.add('disconnected');\n");
        js.append("            document.documentElement.classList.add('tv-off', 'tv-shutting-down');\n");
        js.append("            if (dispEl) dispEl.classList.add('tv-shutting-down');\n");
        js.append("            if (logWrap) logWrap.classList.add('tv-shutting-down');\n");
        js.append("            setTimeout(function() { if (window.AndroidApp) window.AndroidApp.exitApp(); }, 720);\n");
        js.append("          }\n");
        js.append("        }, 550);\n");
        js.append("      });\n");
        js.append("      exitBtn.addEventListener('pointerup', function() {\n");
        js.append("        var wasShort = exHoldTimer !== null;\n");
        js.append("        clearTimeout(exHoldTimer); exHoldTimer = null;\n");
        js.append("        if (wasShort && !exLongFired) {\n");
        js.append("          var now = Date.now();\n");
        js.append("          if (now - lastPwrTapMs < 340) {\n");
        js.append("            lastPwrTapMs = 0;\n");
        js.append("            clearTimeout(exSingleTapTimer); exSingleTapTimer = null;\n");
        js.append("            if (navigator.vibrate) navigator.vibrate(25);\n");
        js.append("            if (window.AndroidApp && window.AndroidApp.minimizeApp) window.AndroidApp.minimizeApp();\n");
        js.append("          } else {\n");
        js.append("            lastPwrTapMs = now;\n");
        js.append("            clearTimeout(exSingleTapTimer);\n");
        js.append("            exSingleTapTimer = setTimeout(function() {\n");
        js.append("              exSingleTapTimer = null;\n");
        js.append("              if (navigator.vibrate) navigator.vibrate(15);\n");
        js.append("              if (window.AndroidApp && window.AndroidApp.showSystemBars) window.AndroidApp.showSystemBars();\n");
        js.append("            }, 340);\n");
        js.append("          }\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("      exitBtn.addEventListener('pointerleave', function() { clearTimeout(exHoldTimer); exHoldTimer = null; });\n");
        js.append("      exitBtn.addEventListener('pointercancel', function() { clearTimeout(exHoldTimer); exHoldTimer = null; });\n");
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
                "Bedroom Lights Alexa Routine Setup",
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
                        showAlexaLampSetupDialog();
                    } else if (which == 3) {
                        openPlayerActivityMode("radio");
                    } else if (which == 4) {
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

    private Network findActiveWifiNetwork() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    for (Network net : cm.getAllNetworks()) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            return net;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void connectSmart(String localUrl, String tailscaleUrl) {
        if (!localUrl.isEmpty() && tailscaleUrl.isEmpty()) { activeServerUrl = localUrl; webView.loadUrl(localUrl); return; }
        if (localUrl.isEmpty() && !tailscaleUrl.isEmpty()) { activeServerUrl = tailscaleUrl; webView.loadUrl(tailscaleUrl); return; }
        Toast.makeText(this, "Checking Local Wi-Fi...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            Network wifiNet = findActiveWifiNetwork();
            boolean localReachable = isUrlReachable(localUrl, 2400, wifiNet);
            if (!localReachable && wifiNet != null) {
                localReachable = isUrlReachable(localUrl, 2400, wifiNet);
            }
            if (localReachable) {
                mainHandler.post(() -> {
                    activeServerUrl = localUrl;
                    Toast.makeText(this, "Connected via Local Wi-Fi", Toast.LENGTH_SHORT).show();
                    webView.loadUrl(localUrl);
                });
            } else {
                boolean tsReachable = isUrlReachable(tailscaleUrl, 2800, null);
                mainHandler.post(() -> {
                    if (tsReachable) {
                        activeServerUrl = tailscaleUrl;
                        Toast.makeText(this, "Connected via Tailscale", Toast.LENGTH_SHORT).show();
                        webView.loadUrl(tailscaleUrl);
                    } else if (wifiNet != null && !localUrl.isEmpty()) {
                        activeServerUrl = localUrl;
                        Toast.makeText(this, "Loading Local Wi-Fi...", Toast.LENGTH_SHORT).show();
                        webView.loadUrl(localUrl);
                    } else {
                        activeServerUrl = tailscaleUrl;
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

    private boolean isUrlReachable(String urlStr, int timeoutMs, Network boundNetwork) {
        try {
            URL u = new URL(urlStr);
            HttpURLConnection conn = (boundNetwork != null && Build.VERSION.SDK_INT >= 21)
                    ? (HttpURLConnection) boundNetwork.openConnection(u)
                    : (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setUseCaches(false);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code >= 200 && code < 500) return true;
        } catch (Exception ignored) {}

        Socket sock = null;
        try {
            URL u = new URL(urlStr);
            String host = u.getHost();
            int port = u.getPort() != -1 ? u.getPort() : (u.getProtocol().equalsIgnoreCase("https") ? 443 : 80);
            sock = (boundNetwork != null && Build.VERSION.SDK_INT >= 21)
                    ? boundNetwork.getSocketFactory().createSocket()
                    : new Socket();
            sock.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (sock != null) {
                try { sock.close(); } catch (Exception ignored) {}
            }
        }
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
        try {
            if (sensorManager != null && tiltSensor != null) {
                sensorManager.registerListener(this, tiltSensor, SensorManager.SENSOR_DELAY_GAME);
            }
        } catch (Exception ignored) {}
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
            webView.evaluateJavascript("var dw=document.getElementById('device-wrapper');if(dw)dw.classList.remove('flipping-out');", null);
        }
    }

    @Override
    protected void onPause() {
        try {
            if (sensorManager != null) sensorManager.unregisterListener(this);
        } catch (Exception ignored) {}
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try { if (sensorManager != null) sensorManager.unregisterListener(this); } catch (Exception ignored) {}
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
