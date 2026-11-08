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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements SensorEventListener {

    private static final int REQ_LOAD_JSON = 901;

    /**
     * Ultra-Simple, Rock-Solid Commercial Radio & Auto-Duck Engine:
     * - Independent Radio Volume (0% to 100%, default 50%) controlled directly from the Radio Player slider.
     * - Simple Auto-Duck ON/OFF (toggled by Long-Pressing AUDIO on the Scanner).
     * - When Auto-Duck is ON:
     *   1) Radio volume immediately drops to 0.0f (mute) the instant a scanner transmission starts or TTS speaks.
     *   2) Radio volume only cuts back in 2.5 seconds (2500ms) AFTER both the scanner transmission AND any TTS readout have finished.
     */
    public static final class PriorityRadioEngine {
        private static MediaPlayer radioPlayer = null;
        private static String activeStationUrl = "";
        private static String activeStationName = "";
        private static int activeSessionId = 0;
        private static boolean isPlaying = false;
        private static boolean isScannerRxActive = false;
        private static boolean isTtsSpeaking = false;
        private static boolean isWaitingForPendingTts = false;
        private static final Handler handler = new Handler(Looper.getMainLooper());
        private static final ExecutorService radioExecutor = Executors.newSingleThreadExecutor();

        private static final Runnable restoreVolumeRunnable = new Runnable() {
            @Override
            public void run() {
                synchronized (PriorityRadioEngine.class) {
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

        private static SharedPreferences cachedPrefs = null;

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
            handler.post(() -> {
                synchronized (PriorityRadioEngine.class) {
                    handler.removeCallbacks(restoreVolumeRunnable);
                    applyCurrentVolumeLocked(cachedPrefs);
                }
            });
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
            handler.post(() -> {
                synchronized (PriorityRadioEngine.class) {
                    applyCurrentVolumeLocked(cachedPrefs);
                }
            });
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
            if (!isScannerRxActive && !isTtsSpeaking && !isWaitingForPendingTts) {
                handler.postDelayed(restoreVolumeRunnable, 2500);
            }
        }

        public static void onScannerRxStateChanged(final SharedPreferences prefs, final boolean rxActive, final boolean voiceReadoutEnabled) {
            if (prefs != null) cachedPrefs = prefs;
            handler.post(() -> {
                synchronized (PriorityRadioEngine.class) {
                    if (rxActive) {
                        isScannerRxActive = true;
                        handler.removeCallbacks(restoreVolumeRunnable);
                        handler.removeCallbacks(clearPendingTtsWaitRunnable);
                        applyCurrentVolumeLocked(cachedPrefs);
                    } else if (isScannerRxActive) {
                        isScannerRxActive = false;
                        handler.removeCallbacks(restoreVolumeRunnable);
                        if (voiceReadoutEnabled) {
                            isWaitingForPendingTts = true;
                            handler.removeCallbacks(clearPendingTtsWaitRunnable);
                            handler.postDelayed(clearPendingTtsWaitRunnable, 1400);
                        } else {
                            isWaitingForPendingTts = false;
                            scheduleRestoreAfter2500msLocked();
                        }
                    }
                }
            });
        }

        public static void notifyScannerRx(final SharedPreferences prefs, final boolean rxActive, final boolean voiceReadoutEnabled) {
            onScannerRxStateChanged(prefs, rxActive, voiceReadoutEnabled);
        }

        public static void notifyScannerRx(final SharedPreferences prefs, final boolean rxActive) {
            onScannerRxStateChanged(prefs, rxActive, false);
        }

        public static void onTtsSpeechStateChanged(final SharedPreferences prefs, final boolean speaking) {
            if (prefs != null) cachedPrefs = prefs;
            handler.post(() -> {
                synchronized (PriorityRadioEngine.class) {
                    handler.removeCallbacks(clearPendingTtsWaitRunnable);
                    isWaitingForPendingTts = false;
                    if (speaking) {
                        isTtsSpeaking = true;
                        handler.removeCallbacks(restoreVolumeRunnable);
                        applyCurrentVolumeLocked(cachedPrefs);
                    } else if (isTtsSpeaking) {
                        isTtsSpeaking = false;
                        scheduleRestoreAfter2500msLocked();
                    }
                }
            });
        }

        // Backwards-compatible alias so PlayerActivity.java compiles whether it calls notifyTtsSpeaking or onTtsSpeechStateChanged
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
                }
            } catch (Exception ignored) {}
        }

        private static synchronized void stopStationInternalOnly() {
            handler.removeCallbacks(restoreVolumeRunnable);
            handler.removeCallbacks(clearPendingTtsWaitRunnable);
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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean userWakeLockEnabled = false;
    private boolean scannerAudioConnected = false;
    private boolean bgAudioModeEnabled = false;
    private boolean bedroomLampOn = false;
    private String activeServerUrl = "";
    private TextToSpeech tts = null;
    private volatile boolean ttsReady = false;

    private String lastSpokenText = "";
    private long lastSpokenTimestamp = 0;

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null || event.values.length < 2 || webView == null) return;
        long now = System.currentTimeMillis();
        if (now - lastTiltPushMs < 45) return;
        lastTiltPushMs = now;
        float rawX = Math.max(-7f, Math.min(7f, event.values[0])) / 7f;
        float rawY = Math.max(-7f, Math.min(7f, event.values[1] - 4.5f)) / 7f;
        smoothTiltX += (rawX - smoothTiltX) * 0.22f;
        smoothTiltY += (rawY - smoothTiltY) * 0.22f;
        int deg = Math.round(125f - smoothTiltX * 28f + smoothTiltY * 14f);
        int posPct = Math.round(34f + smoothTiltX * 18f - smoothTiltY * 12f);
        int bx = Math.round(-smoothTiltX * 8f);
        int by = Math.round(smoothTiltY * 8f);
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
            bedroomLampOn = false;
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
                            PriorityRadioEngine.onTtsSpeechStateChanged(prefs, false);
                        }

                        @Override
                        public void onError(String utteranceId) {
                            PriorityRadioEngine.onTtsSpeechStateChanged(prefs, false);
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
                        "window.__voiceReadoutEnabled=false;var vb=document.getElementById('btn-voice-toggle');if(vb)vb.classList.remove('active');}catch(e){}",
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

    private final Runnable periodicInjector = new Runnable() {
        @Override
        public void run() {
            if (webView != null) {
                injectEnhancements();
                if (scannerAudioConnected || PriorityRadioEngine.isRadioPlaying()) {
                    webView.evaluateJavascript(
                            "try{if(window.__bgTickSquelchAndLog)window.__bgTickSquelchAndLog();}catch(e){}",
                            null
                    );
                }
            }
            mainHandler.postDelayed(this, 600);
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
            mainHandler.post(() -> {
                scannerAudioConnected = connected;
                if (!connected) {
                    PriorityRadioEngine.onScannerRxStateChanged(prefs, false, false);
                }
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
        }

        @JavascriptInterface
        public void speakChannel(final String text) {
            mainHandler.post(() -> {
                if (text == null || text.trim().isEmpty()) return;
                final String cleanText = text.trim();

                long now = System.currentTimeMillis();
                if (!cleanText.equalsIgnoreCase("Voice readout on")
                        && cleanText.equalsIgnoreCase(lastSpokenText)
                        && (now - lastSpokenTimestamp < 30000)) {
                    PriorityRadioEngine.onTtsSpeechStateChanged(prefs, false);
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
            try {
                if (Build.VERSION.SDK_INT >= 21) {
                    Bundle params = new Bundle();
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.45f);
                    result = tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "proscan_tts_" + System.currentTimeMillis());
                } else {
                    result = tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null);
                }
            } catch (Exception e) {
                result = TextToSpeech.ERROR;
            }

            if (result != TextToSpeech.SUCCESS) {
                initTTS();
                mainHandler.postDelayed(() -> {
                    if (tts != null && ttsReady) {
                        if (Build.VERSION.SDK_INT >= 21) {
                            Bundle params = new Bundle();
                            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.45f);
                            tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "proscan_tts_retry");
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
        mainHandler.postDelayed(periodicInjector, 600);
    }

    private void injectEnhancements() {
        if (webView == null) return;
        String sharedRawPref = prefs != null ? prefs.getString("shared_web_storage", "") : "";
        String safeShared = JSONObject.quote(sharedRawPref != null ? sharedRawPref : "");
        String wakePref = userWakeLockEnabled ? "true" : "false";

        StringBuilder js = new StringBuilder();
        js.append("(function() {\n");
        js.append("  try {\n");
        js.append("    if (!document.getElementById('proscan-native-enhancements-css')) {\n");
        js.append("      var st = document.createElement('style');\n");
        js.append("      st.id = 'proscan-native-enhancements-css';\n");
        js.append("      st.textContent = `\n");
        js.append("        .scanner-nav-6 { display: grid !important; grid-template-columns: repeat(6, 1fr) !important; gap: 5px !important; width: 100% !important; }\n");
        js.append("        .scanner-nav-6 .btn { min-width: 0 !important; padding: 6px 2px !important; font-size: 10px !important; white-space: nowrap !important; }\n");
        js.append("        #scanner-bat-hud { position: absolute; top: 6px; left: 8px; z-index: 30; display: inline-flex; align-items: center; gap: 4px; font-family: monospace; font-size: 10px; font-weight: 700; color: #38bdf8; pointer-events: none; text-shadow: 0 0 4px rgba(56,189,248,0.5); }\n");
        js.append("        #scanner-bat-hud.disconnected { opacity: 0 !important; }\n");
        js.append("        .bat-icon-box { width: 16px; height: 8px; border: 1px solid currentColor; border-radius: 2px; padding: 1px; display: inline-flex; align-items: center; position: relative; }\n");
        js.append("        .bat-icon-box::after { content: ''; position: absolute; right: -3px; top: 2px; width: 2px; height: 4px; background: currentColor; border-radius: 0 1px 1px 0; }\n");
        js.append("        .bat-icon-fill { height: 100%; background: currentColor; width: 80%; border-radius: 1px; }\n");
        js.append("        #proscan-sig-meter-box { position: absolute; bottom: 5px; right: 8px; z-index: 28; display: flex; align-items: center; gap: 4px; background: rgba(2,6,23,0.78); border: 1px solid rgba(100,116,139,0.45); border-radius: 4px; padding: 2px 5px; cursor: pointer; user-select: none; }\n");
        js.append("        #proscan-sig-meter-box.disconnected { opacity: 0 !important; pointer-events: none !important; }\n");
        js.append("        #sm-led-bars { display: flex; align-items: flex-end; gap: 2px; height: 14px; }\n");
        js.append("        #sm-led-bars .sm-seg { width: 4px; border-radius: 1px; background: #1e293b; }\n");
        js.append("        #sm-vu-wrap { display: none; position: relative; width: 64px; height: 14px; background: linear-gradient(90deg, #0f172a 0%, #1e293b 75%, #450a0a 100%); border: 1px solid #334155; border-radius: 3px; overflow: hidden; }\n");
        js.append("        #sm-vu-needle { position: absolute; top: 0; bottom: 0; left: 3px; width: 2px; background: #facc15; box-shadow: 0 0 4px #facc15; transition: left 60ms linear; }\n");
        js.append("        #sm-spec-bars { display: none; align-items: flex-end; gap: 2px; height: 15px; width: 62px; }\n");
        js.append("        #sm-spec-bars .sm-spec-bar { flex: 1; height: 2px; background: linear-gradient(180deg, #facc15 0%, #22c55e 45%, #06b6d4 100%); border-radius: 1px; }\n");
        js.append("        #sm-scope-canvas { display: none; width: 64px; height: 15px; background: #020617; border-radius: 2px; }\n");
        js.append("        #sm-dig-wrap { display: none; flex-direction: column; width: 66px; gap: 1px; }\n");
        js.append("        #sm-dig-txt { font-family: monospace; font-size: 9px; font-weight: 700; color: #64748b; line-height: 1; text-align: right; }\n");
        js.append("        #sm-peak-track { width: 100%; height: 3px; background: #1e293b; border-radius: 1px; overflow: hidden; }\n");
        js.append("        #sm-peak-fill { width: 0%; height: 100%; background: linear-gradient(90deg, #22c55e, #facc15, #ef4444); }\n");
        js.append("        #btn-voice-toggle, #btn-lamp-toggle, #btn-hardware-exit { background: transparent; border: none; color: inherit; display: inline-flex; align-items: center; gap: 4px; cursor: pointer; padding: 2px 4px; }\n");
        js.append("        #voice-switch-track { width: 14px; height: 24px; border-radius: 7px; background: #1e293b; border: 1px solid #475569; position: relative; transition: background 0.2s; }\n");
        js.append("        #voice-switch-thumb { width: 10px; height: 10px; border-radius: 50%; background: #94a3b8; position: absolute; left: 1px; bottom: 2px; transition: transform 0.2s, background 0.2s; }\n");
        js.append("        #btn-voice-toggle.active #voice-switch-track { background: rgba(14,165,233,0.3); border-color: #38bdf8; }\n");
        js.append("        #btn-voice-toggle.active #voice-switch-thumb { transform: translateY(-9px); background: #38bdf8; box-shadow: 0 0 6px #38bdf8; }\n");
        js.append("        #voice-led { width: 8px; height: 8px; border-radius: 50%; background: #1e293b; border: 1px solid #334155; }\n");
        js.append("        #btn-voice-toggle.active #voice-led { background: #38bdf8; box-shadow: 0 0 8px #38bdf8; }\n");
        js.append("        .lamp-cap { width: 12px; height: 12px; border-radius: 50%; background: radial-gradient(circle, #fde047 0%, #ca8a04 100%); box-shadow: 0 0 6px rgba(250,204,21,0.5); border: 1px solid #fef08a; }\n");
        js.append("        .exit-cap { width: 12px; height: 12px; border-radius: 50%; background: radial-gradient(circle, #f87171 0%, #991b1b 100%); box-shadow: 0 0 6px rgba(239,68,68,0.55); border: 1px solid #fecaca; }\n");
        js.append("        html[data-scanner-lite='true'] .keypad-section, html[data-scanner-lite='true'] .knobs-row, html[data-scanner-lite='true'] . rotary-row { display: none !important; }\n");
        js.append("        html.tv-off, html.tv-off body { background: #000 !important; }\n");
        js.append("        .tv-shutting-down { transition: transform 0.55s cubic-bezier(0.22, 1, 0.36, 1), opacity 0.55s ease !important; transform: scaleY(0.008) scaleX(0.02) !important; opacity: 0 !important; filter: brightness(8) !important; }\n");
        js.append("      `;\n");
        js.append("      document.head.appendChild(st);\n");
        js.append("    }\n");

        /* Hook RX state notifications to native PriorityRadioEngine Auto-Duck */
        js.append("    window.__bgTickSquelchAndLog = function() {\n");
        js.append("      var rxEl = document.getElementById('rx-led');\n");
        js.append("      var isRxNow = Boolean(rxEl && rxEl.classList.contains('active'));\n");
        js.append("      if (window.__lastNotifiedRxState !== isRxNow) {\n");
        js.append("        window.__lastNotifiedRxState = isRxNow;\n");
        js.append("        if (window.AndroidApp && typeof window.AndroidApp.notifyScannerRx === 'function') {\n");
        js.append("          window.AndroidApp.notifyScannerRx(isRxNow, Boolean(window.__voiceReadoutEnabled));\n");
        js.append("        }\n");
        js.append("      }\n");
        js.append("    };\n");
        js.append("    window.__bgTickSquelchAndLog();\n");

        /* Multi-Mode Live Scanner Signal Meter (LED / VU / SPEC / SCOPE / dB) */
        js.append("    var dispForMeter = document.querySelector('.scanner-display');\n");
        js.append("    if (dispForMeter && !document.getElementById('proscan-sig-meter-box')) {\n");
        js.append("      window.__sigMeterStyle = parseInt(localStorage.getItem('proscan_sig_meter_style_v1') || '0', 10) || 0;\n");
        js.append("      var mBox = document.createElement('div');\n");
        js.append("      mBox.id = 'proscan-sig-meter-box';\n");
        js.append("      mBox.title = 'Tap to cycle Signal Meter style';\n");
        js.append("      var ledHtml = '<div id=\"sm-led-bars\">';\n");
        js.append("      for (var s = 0; s < 12; s++) {\n");
        js.append("        var sh = 4 + Math.round((s / 11) * 10);\n");
        js.append("        ledHtml += '<span class=\"sm-seg\" style=\"height:' + sh + 'px\"></span>';\n");
        js.append("      }\n");
        js.append("      ledHtml += '</div>';\n");
        js.append("      var vuHtml = '<div id=\"sm-vu-wrap\"><div id=\"sm-vu-needle\"></div></div>';\n");
        js.append("      var specHtml = '<div id=\"sm-spec-bars\">';\n");
        js.append("      for (var sp = 0; sp < 10; sp++) specHtml += '<span class=\"sm-spec-bar\"></span>';\n");
        js.append("      specHtml += '</div>';\n");
        js.append("      var scopeHtml = '<canvas id=\"sm-scope-canvas\" width=\"64\" height=\"15\"></canvas>';\n");
        js.append("      var digHtml = '<div id=\"sm-dig-wrap\"><div id=\"sm-dig-txt\">S0 -98dB</div><div id=\"sm-peak-track\"><div id=\"sm-peak-fill\"></div></div></div>';\n");
        js.append("      mBox.innerHTML = ledHtml + vuHtml + specHtml + scopeHtml + digHtml;\n");
        js.append("      dispForMeter.appendChild(mBox);\n");
        js.append("      window.__applySigMeterStyle = function(idx, showHud) {\n");
        js.append("        window.__sigMeterStyle = ((idx % 5) + 5) % 5;\n");
        js.append("        localStorage.setItem('proscan_sig_meter_style_v1', String(window.__sigMeterStyle));\n");
        js.append("        var elLed = document.getElementById('sm-led-bars');\n");
        js.append("        var elVu = document.getElementById('sm-vu-wrap');\n");
        js.append("        var elSpec = document.getElementById('sm-spec-bars');\n");
        js.append("        var elScope = document.getElementById('sm-scope-canvas');\n");
        js.append("        var elDig = document.getElementById('sm-dig-wrap');\n");
        js.append("        if (elLed) elLed.style.display = window.__sigMeterStyle === 0 ? 'flex' : 'none';\n");
        js.append("        if (elVu) elVu.style.display = window.__sigMeterStyle === 1 ? 'block' : 'none';\n");
        js.append("        if (elSpec) elSpec.style.display = window.__sigMeterStyle === 2 ? 'flex' : 'none';\n");
        js.append("        if (elScope) elScope.style.display = window.__sigMeterStyle === 3 ? 'block' : 'none';\n");
        js.append("        if (elDig) elDig.style.display = window.__sigMeterStyle === 4 ? 'flex' : 'none';\n");
        js.append("        var names = ['LED BARS', 'VU NEEDLE', 'SPECTRUM', 'WAVE SCOPE', 'DIGITAL dB'];\n");
        js.append("        if (showHud && typeof showDisplayHud === 'function') {\n");
        js.append("          showDisplayHud('METER: ' + names[window.__sigMeterStyle], 1200);\n");
        js.append("        }\n");
        js.append("      };\n");
        js.append("      window.__applySigMeterStyle(window.__sigMeterStyle, false);\n");
        js.append("      mBox.addEventListener('click', function(ev) {\n");
        js.append("        ev.stopPropagation();\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(15);\n");
        js.append("        window.__applySigMeterStyle((window.__sigMeterStyle || 0) + 1, true);\n");
        js.append("      });\n");
        js.append("      var smCarrierLvl = 0, smAudioLvl = 0, smAdaptiveMax = 14.0, smPhase = 0;\n");
        js.append("      function renderLiveScannerSignalMeter() {\n");
        js.append("        requestAnimationFrame(renderLiveScannerSignalMeter);\n");
        js.append("        var rawAud = 0;\n");
        js.append("        var rxLedEl = document.getElementById('rx-led');\n");
        js.append("        var isRxVoiceActive = Boolean(rxLedEl && rxLedEl.classList.contains('active'));\n");
        js.append("        if (window.__bgTickSquelchAndLog) window.__bgTickSquelchAndLog();\n");
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

        /* Isolated LITE button replacement */
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
        js.append("      var lHoldTimer = null, lLongFired = false;\n");
        js.append("      freshLiteBtn.addEventListener('pointerdown', function(ev) {\n");
        js.append("        ev.stopPropagation(); ev.stopImmediatePropagation();\n");
        js.append("        clearTimeout(lHoldTimer); lLongFired = false;\n");
        js.append("        lHoldTimer = setTimeout(function() {\n");
        js.append("          lHoldTimer = null; lLongFired = true;\n");
        js.append("          if (navigator.vibrate) navigator.vibrate(35);\n");
        js.append("          if (typeof toggleAppMenuPanel === 'function') toggleAppMenuPanel();\n");
        js.append("        }, 500);\n");
        js.append("      }, true);\n");
        js.append("      freshLiteBtn.addEventListener('pointerup', function(ev) {\n");
        js.append("        ev.preventDefault(); ev.stopPropagation(); ev.stopImmediatePropagation();\n");
        js.append("        var wasTap = lHoldTimer !== null; clearTimeout(lHoldTimer); lHoldTimer = null;\n");
        js.append("        if (wasTap && !lLongFired) {\n");
        js.append("          if (navigator.vibrate) navigator.vibrate(20);\n");
        js.append("          window.__applyScannerLiteMode(!window.__scannerLiteMode, true);\n");
        js.append("        }\n");
        js.append("      }, true);\n");
        js.append("      freshLiteBtn.addEventListener('click', function(ev) {\n");
        js.append("        ev.preventDefault(); ev.stopPropagation(); ev.stopImmediatePropagation();\n");
        js.append("      }, true);\n");
        js.append("      freshLiteBtn.addEventListener('pointerleave', function() { clearTimeout(lHoldTimer); lHoldTimer = null; });\n");
        js.append("      freshLiteBtn.addEventListener('pointercancel', function() { clearTimeout(lHoldTimer); lHoldTimer = null; });\n");
        js.append("    }\n");

        js.append("    var dispBox = document.querySelector('.scanner-display');\n");
        js.append("    if (dispBox) {\n");
        js.append("      var clkEl = document.getElementById('display-clock-hud');\n");
        js.append("      var batEl = document.getElementById('scanner-bat-hud');\n");
        js.append("      var mtrBoxEl = document.getElementById('proscan-sig-meter-box');\n");
        js.append("      if (!batEl) {\n");
        js.append("        batEl = document.createElement('div');\n");
        js.append("        batEl.id = 'scanner-bat-hud';\n");
        js.append("        batEl.innerHTML = '<span class=\"bat-icon-box\"><span class=\"bat-icon-fill\" id=\"scanner-bat-fill\"></span></span><span id=\"scanner-bat-txt\">--%</span>';\n");
        js.append("        dispBox.appendChild(batEl);\n");
        js.append("      }\n");
        js.append("      if (clkEl && batEl) {\n");
        js.append("        var isDisc = clkEl.classList.contains('disconnected');\n");
        js.append("        batEl.classList.toggle('disconnected', isDisc);\n");
        js.append("        if (mtrBoxEl) mtrBoxEl.classList.toggle('disconnected', isDisc);\n");
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

        /* Second Row: Clean 6-Button Grid [ LOG IN ] [ ARCHIVE ] [ RADIO ] [ MTR ] [ LITE ] [ DSP ] */
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

        /* Clean Scanner Web Audio DSP (Zero Graph Disconnects) */
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

        /* Top LEDs Left: WAKE and VOICE */
        js.append("    var ledsLeft = document.querySelector('.top-leds-left');\n");
        js.append("    if (ledsLeft && !document.getElementById('btn-voice-toggle')) {\n");
        js.append("      window.__voiceReadoutEnabled = false; window.__lastSpokenRowKey = '';\n");
        js.append("      var rxWaitTimer = null;\n");
        js.append("      function speakTextNow(txt, immediate) {\n");
        js.append("        if (!window.AndroidApp) return;\n");
        js.append("        if (immediate) { window.AndroidApp.speakChannel(txt); return; }\n");
        js.append("        clearTimeout(rxWaitTimer);\n");
        js.append("        var tries = 0;\n");
        js.append("        (function waitRxOff() {\n");
        js.append("          var rx = document.getElementById('rx-led');\n");
        js.append("          if (rx && rx.classList.contains('active') && tries++ < 25) {\n");
        js.append("            rxWaitTimer = setTimeout(waitRxOff, 220);\n");
        js.append("          } else {\n");
        js.append("            rxWaitTimer = setTimeout(function() { window.AndroidApp.speakChannel(txt); }, 150);\n");
        js.append("          }\n");
        js.append("        })();\n");
        js.append("      }\n");
        js.append("      var vBtn = document.createElement('button');\n");
        js.append("      vBtn.id = 'btn-voice-toggle'; vBtn.className = 'wake-btn-wrapper'; vBtn.type = 'button';\n");
        js.append("      vBtn.innerHTML = '<div class=\"wake-switch-col\"><div id=\"voice-switch-track\"><div id=\"voice-switch-thumb\"></div></div><span class=\"rx-label\">VOICE</span></div><div id=\"voice-led\"></div>';\n");
        js.append("      window.__setVoiceReadoutState = function(enable, showHud) {\n");
        js.append("        window.__voiceReadoutEnabled = Boolean(enable);\n");
        js.append("        var vb = document.getElementById('btn-voice-toggle');\n");
        js.append("        if (vb) vb.classList.toggle('active', window.__voiceReadoutEnabled);\n");
        js.append("        if (!window.__voiceReadoutEnabled) {\n");
        js.append("          clearTimeout(rxWaitTimer);\n");
        js.append("        }\n");
        js.append("        if (showHud && typeof showDisplayHud === 'function') {\n");
        js.append("          showDisplayHud('VOICE READOUT: ' + (window.__voiceReadoutEnabled ? 'ON' : 'OFF'), 1400);\n");
        js.append("        }\n");
        js.append("      };\n");
        js.append("      vBtn.addEventListener('click', function() {\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(25);\n");
        js.append("        window.__setVoiceReadoutState(!window.__voiceReadoutEnabled, true);\n");
        js.append("        if (window.__voiceReadoutEnabled) {\n");
        js.append("          speakTextNow('Voice readout on', true);\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("      ledsLeft.appendChild(vBtn);\n");

        js.append("      var logBody = document.getElementById('mini-log-data');\n");
        js.append("      if (logBody) {\n");
        js.append("        var speechThrottleTimer = null;\n");
        js.append("        function checkTopLogRowForSpeech() {\n");
        js.append("          if (!window.__voiceReadoutEnabled) return;\n");
        js.append("          var topRow = logBody.querySelector('tr.new-log-row') || logBody.querySelector('tr');\n");
        js.append("          if (!topRow || topRow.children.length < 5) return;\n");
        js.append("          var timeStr = (topRow.children[0].textContent || '').trim();\n");
        js.append("          var freqStr = (topRow.children[1].textContent || '').trim();\n");
        js.append("          var nameTd = topRow.children[4];\n");
        js.append("          var rawTitle = nameTd.getAttribute('title') || nameTd.textContent || '';\n");
        js.append("          var closeParen = rawTitle.indexOf(')');\n");
        js.append("          var chName = (closeParen !== -1 ? rawTitle.slice(closeParen + 1) : rawTitle).replace(/^[×xX]\\s*\\d+\\s*/, '').trim();\n");
        js.append("          if (!chName || chName === '---') chName = freqStr;\n");
        js.append("          if (!chName) return;\n");
        js.append("          if (speechThrottleTimer) return;\n");
        js.append("          speechThrottleTimer = setTimeout(function() { speechThrottleTimer = null; }, 400);\n");
        js.append("          var rowKey = timeStr + '|' + freqStr + '|' + chName;\n");
        js.append("          if (rowKey !== window.__lastSpokenRowKey) {\n");
        js.append("            window.__lastSpokenRowKey = rowKey;\n");
        js.append("            speakTextNow(chName, false);\n");
        js.append("          }\n");
        js.append("        }\n");
        js.append("        new MutationObserver(checkTopLogRowForSpeech).observe(logBody, { childList: true });\n");
        js.append("      }\n");
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

        /* AUDIO Toggle: Short Tap = Toggle Scanner Audio ON/OFF; Long Press = Toggle Simple Auto-Duck ON/OFF */
        js.append("    var oldAudioBtn = document.getElementById('btn-audio-toggle');\n");
        js.append("    if (oldAudioBtn && !oldAudioBtn.__vlcUnifiedHooked) {\n");
        js.append("      var aBtn = oldAudioBtn.cloneNode(true); aBtn.__vlcUnifiedHooked = true;\n");
        js.append("      oldAudioBtn.parentNode.replaceChild(aBtn, oldAudioBtn);\n");
        js.append("      window.__simpleAutoDuckOn = (window.AndroidApp && window.AndroidApp.isAutoDuckOn) ? window.AndroidApp.isAutoDuckOn() : true;\n");
        js.append("      function renderAudioLabel() {\n");
        js.append("        var duckTag = window.__simpleAutoDuckOn ? ' <span style=\"color:#38bdf8;font-size:8.5px;margin-left:3px;\">[DUCK]</span>' : '';\n");
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

        /* Top LEDs Right: RX, LOG, LAMP, and PWR (ON/OFF) Button:
           - Double-Tap (whether ON or OFF) = Show Android Top/Bottom System Bars
           - Long-Press (550ms) when OFF = Power ON both DISPLAY and AUDIO
           - Long-Press (550ms) when ON  = Turn OFF AUDIO & DISPLAY, blank screen to pure black with white CRT animation, and Exit App */
        js.append("    var ledsRight = document.querySelector('.top-leds-right');\n");
        js.append("    if (ledsRight && !document.getElementById('btn-hardware-exit')) {\n");
        js.append("      var lampBtn = document.createElement('button');\n");
        js.append("      lampBtn.id = 'btn-lamp-toggle'; lampBtn.type = 'button';\n");
        js.append("      lampBtn.title = 'Tap: Bedroom Lights | Hold: Setup Alexa Routine URL';\n");
        js.append("      lampBtn.innerHTML = '<div class=\"lamp-cap\"></div><span class=\"rx-label\">LAMP</span>';\n");
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
        js.append("          if (window.AndroidApp && window.AndroidApp.triggerBedroomLamp) {\n");
        js.append("            window.AndroidApp.triggerBedroomLamp(window.__scannerLampState);\n");
        js.append("          }\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("      lampBtn.addEventListener('pointerleave', function() { clearTimeout(lpTimer); lpTimer = null; });\n");
        js.append("      lampBtn.addEventListener('pointercancel', function() { clearTimeout(lpTimer); lpTimer = null; });\n");

        js.append("      var exitBtn = document.createElement('button');\n");
        js.append("      exitBtn.id = 'btn-hardware-exit'; exitBtn.type = 'button';\n");
        js.append("      exitBtn.title = 'Hold: Power ON / OFF | Double-Tap: System Bars';\n");
        js.append("      exitBtn.innerHTML = '<div class=\"exit-cap\"></div><span class=\"rx-label\">PWR</span>';\n");
        js.append("      ledsRight.appendChild(exitBtn);\n");
        js.append("      var exHoldTimer = null, exLongFired = false, lastPwrTapMs = 0;\n");
        js.append("      exitBtn.addEventListener('pointerdown', function() {\n");
        js.append("        clearTimeout(exHoldTimer); exLongFired = false;\n");
        js.append("        exHoldTimer = setTimeout(function() {\n");
        js.append("          exHoldTimer = null; exLongFired = true;\n");
        js.append("          var anyOn = (typeof isDisplayOn !== 'undefined' && isDisplayOn) || (typeof isConnected !== 'undefined' && isConnected);\n");
        js.append("          if (!anyOn) {\n");
        js.append("            if (navigator.vibrate) navigator.vibrate(40);\n");
        js.append("            if (typeof isDisplayOn !== 'undefined' && !isDisplayOn && typeof toggleDisplay === 'function') {\n");
        js.append("              toggleDisplay();\n");
        js.append("            }\n");
        js.append("            if (window.__setScannerAudioState) {\n");
        js.append("              window.__setScannerAudioState(true);\n");
        js.append("            }\n");
        js.append("          } else {\n");
        js.append("            if (navigator.vibrate) navigator.vibrate([45, 60, 55]);\n");
        js.append("            if (window.__setScannerAudioState) window.__setScannerAudioState(false);\n");
        js.append("            isDisplayOn = false;\n");
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
        js.append("          if (now - lastPwrTapMs < 380) {\n");
        js.append("            lastPwrTapMs = 0;\n");
        js.append("            if (navigator.vibrate) navigator.vibrate(20);\n");
        js.append("            if (window.AndroidApp) window.AndroidApp.showSystemBars();\n");
        js.append("          } else {\n");
        js.append("            lastPwrTapMs = now;\n");
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
