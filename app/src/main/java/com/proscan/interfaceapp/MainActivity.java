package com.proscan.interfaceapp;

import android.animation.ValueAnimator;
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
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.LoudnessEnhancer;
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
import android.view.animation.DecelerateInterpolator;
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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
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

public class MainActivity extends Activity {

    private static final int REQ_LOAD_JSON = 901;
    private static final String GDRIVE_ROOT_FOLDER_ID = "1gToQyY6YPOk_3muiQSY5WLy7jTSvXbTv";

    /**
     * Shared Priority Auto-Ducking Commercial Radio Engine
     * Allows commercial radio and scanner streams to run simultaneously
     * with instant ducking (default 5% whisper), hold timer (default 2500ms),
     * and smooth 600ms ValueAnimator ramp-back to baseline ceiling (default 40%).
     */
    public static final class PriorityRadioEngine {
        private static MediaPlayer radioPlayer = null;
        private static String activeStationUrl = "";
        private static String activeStationName = "";
        private static int activeSessionId = 0;
        private static boolean isPlaying = false;
        private static boolean isScannerSquelchOpen = false;
        private static boolean isTtsSpeaking = false;
        private static float currentVolume = 0.40f;
        private static ValueAnimator fadeAnimator = null;
        private static final Handler handler = new Handler(Looper.getMainLooper());
        private static Runnable holdRestoreRunnable = null;
        private static final ExecutorService radioExecutor = Executors.newSingleThreadExecutor();

        public static boolean isAutoDuckEnabled(SharedPreferences prefs) {
            return prefs == null || prefs.getBoolean("mixer_auto_duck_on", true);
        }

        public static float getBaselineVolume(SharedPreferences prefs) {
            int pct = prefs != null ? prefs.getInt("mixer_radio_ceiling_pct", 40) : 40;
            return Math.max(0.10f, Math.min(1.0f, pct / 100f));
        }

        public static float getDuckedVolume(SharedPreferences prefs) {
            int pct = prefs != null ? prefs.getInt("mixer_radio_duck_pct", 5) : 5;
            return Math.max(0.0f, Math.min(0.25f, pct / 100f));
        }

        public static int getHoldTimeMs(SharedPreferences prefs) {
            return prefs != null ? prefs.getInt("mixer_duck_hold_ms", 2500) : 2500;
        }

        public static int getScannerAgcDb(SharedPreferences prefs) {
            return prefs != null ? prefs.getInt("mixer_scanner_agc_db", 8) : 8;
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

        public static synchronized void updateSettingsLive(SharedPreferences prefs) {
            if (radioPlayer == null || !isPlaying) return;
            boolean shouldBeDucked = isAutoDuckEnabled(prefs) && (isScannerSquelchOpen || isTtsSpeaking);
            float target = shouldBeDucked ? getDuckedVolume(prefs) : getBaselineVolume(prefs);
            cancelFade();
            setPlayerVolumeInternal(target);
        }

        public static synchronized void notifyScannerHit(SharedPreferences prefs, boolean squelchOpen) {
            isScannerSquelchOpen = squelchOpen;
            evaluateDuckState(prefs);
        }

        public static synchronized void notifyTtsSpeaking(SharedPreferences prefs, boolean speaking) {
            isTtsSpeaking = speaking;
            evaluateDuckState(prefs);
        }

        private static synchronized void evaluateDuckState(final SharedPreferences prefs) {
            if (radioPlayer == null || !isPlaying) return;
            if (!isAutoDuckEnabled(prefs)) {
                cancelHoldAndFade();
                setPlayerVolumeInternal(getBaselineVolume(prefs));
                return;
            }
            if (isScannerSquelchOpen || isTtsSpeaking) {
                cancelHoldAndFade();
                setPlayerVolumeInternal(getDuckedVolume(prefs));
            } else {
                if (holdRestoreRunnable != null) {
                    handler.removeCallbacks(holdRestoreRunnable);
                }
                final int holdMs = getHoldTimeMs(prefs);
                holdRestoreRunnable = () -> {
                    synchronized (PriorityRadioEngine.class) {
                        if (isScannerSquelchOpen || isTtsSpeaking || radioPlayer == null || !isPlaying) return;
                        float startVol = currentVolume;
                        float endVol = getBaselineVolume(prefs);
                        cancelFade();
                        fadeAnimator = ValueAnimator.ofFloat(startVol, endVol);
                        fadeAnimator.setDuration(600);
                        fadeAnimator.setInterpolator(new DecelerateInterpolator());
                        fadeAnimator.addUpdateListener(anim -> {
                            float v = (Float) anim.getAnimatedValue();
                            setPlayerVolumeInternal(v);
                        });
                        fadeAnimator.start();
                    }
                };
                handler.postDelayed(holdRestoreRunnable, holdMs);
            }
        }

        private static synchronized void setPlayerVolumeInternal(float vol) {
            currentVolume = Math.max(0f, Math.min(1f, vol));
            if (radioPlayer != null) {
                try {
                    radioPlayer.setVolume(currentVolume, currentVolume);
                } catch (Exception ignored) {}
            }
        }

        private static synchronized void cancelFade() {
            if (fadeAnimator != null) {
                try { fadeAnimator.cancel(); } catch (Exception ignored) {}
                fadeAnimator = null;
            }
        }

        private static synchronized void cancelHoldAndFade() {
            if (holdRestoreRunnable != null) {
                handler.removeCallbacks(holdRestoreRunnable);
                holdRestoreRunnable = null;
            }
            cancelFade();
        }

        public static void startStation(final Context context, final SharedPreferences prefs, final String streamUrl, final String stationName, final Runnable onStarted, final Runnable onError) {
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
                                boolean duckNow = isAutoDuckEnabled(prefs) && (isScannerSquelchOpen || isTtsSpeaking);
                                float initVol = duckNow ? getDuckedVolume(prefs) : getBaselineVolume(prefs);
                                setPlayerVolumeInternal(initVol);
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
            if (radioPlayer == null) return;
            try {
                if (radioPlayer.isPlaying()) {
                    radioPlayer.pause();
                    isPlaying = false;
                } else {
                    boolean duckNow = isAutoDuckEnabled(prefs) && (isScannerSquelchOpen || isTtsSpeaking);
                    setPlayerVolumeInternal(duckNow ? getDuckedVolume(prefs) : getBaselineVolume(prefs));
                    radioPlayer.start();
                    isPlaying = true;
                }
            } catch (Exception ignored) {}
        }

        private static synchronized void stopStationInternalOnly() {
            cancelHoldAndFade();
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

    // Native Scanner AGC / LoudnessEnhancer & Limiter
    private LoudnessEnhancer scannerLoudnessEnhancer = null;
    private DynamicsProcessing scannerDynamicsLimiter = null;
    private volatile boolean currentModulationIsAm = false;
    private volatile float currentChannelGainOffsetDb = 0f;

    private void attachOrUpdateNativeScannerAgc() {
        try {
            int baseAgcDb = PriorityRadioEngine.getScannerAgcDb(prefs);
            int amBonusMb = currentModulationIsAm ? 300 : 0; // +300 mB (+3 dB) automatic AM airband offset
            int chOffsetMb = Math.round(currentChannelGainOffsetDb * 100f);
            int totalGainMb = Math.max(0, Math.min(2400, (baseAgcDb * 100) + amBonusMb + chOffsetMb));

            if (scannerLoudnessEnhancer == null) {
                try {
                    scannerLoudnessEnhancer = new LoudnessEnhancer(0);
                } catch (Exception ignored) {}
            }
            if (scannerLoudnessEnhancer != null) {
                scannerLoudnessEnhancer.setTargetGain(totalGainMb);
                scannerLoudnessEnhancer.setEnabled(scannerAudioConnected && totalGainMb > 0);
            }

            if (Build.VERSION.SDK_INT >= 28 && scannerDynamicsLimiter == null) {
                try {
                    DynamicsProcessing.Config.Builder b = new DynamicsProcessing.Config.Builder(
                            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                            1, true, 1, false, 0, false, 0, true
                    );
                    DynamicsProcessing.Config cfg = b.build();
                    DynamicsProcessing.Limiter lim = cfg.getLimiterByChannelIndex(0);
                    lim.setEnabled(true);
                    lim.setAttackTime(1.5f);
                    lim.setReleaseTime(60.0f);
                    lim.setRatio(10.0f);
                    lim.setThreshold(-2.5f);
                    lim.setPostGain(0.0f);
                    scannerDynamicsLimiter = new DynamicsProcessing(0, 0, cfg);
                    scannerDynamicsLimiter.setEnabled(true);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void releaseNativeScannerAgc() {
        try {
            if (scannerLoudnessEnhancer != null) {
                scannerLoudnessEnhancer.setEnabled(false);
                scannerLoudnessEnhancer.release();
                scannerLoudnessEnhancer = null;
            }
        } catch (Exception ignored) {}
        try {
            if (Build.VERSION.SDK_INT >= 28 && scannerDynamicsLimiter != null) {
                scannerDynamicsLimiter.setEnabled(false);
                scannerDynamicsLimiter.release();
                scannerDynamicsLimiter = null;
            }
        } catch (Exception ignored) {}
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
                            PriorityRadioEngine.notifyTtsSpeaking(prefs, true);
                        }

                        @Override
                        public void onDone(String utteranceId) {
                            PriorityRadioEngine.notifyTtsSpeaking(prefs, false);
                        }

                        @Override
                        public void onError(String utteranceId) {
                            PriorityRadioEngine.notifyTtsSpeaking(prefs, false);
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

        prefs = getSharedPreferences("proscan_prefs", MODE_PRIVATE);

        initTTS();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Custom WebView that keeps HTML5 Scanner Audio alive when the screen turns off
        webView = new WebView(this) {
            @Override
            public void setKeepScreenOn(boolean keepScreenOn) {
                super.setKeepScreenOn(userWakeLockEnabled);
            }

            @Override
            protected void onWindowVisibilityChanged(int visibility) {
                if (scannerAudioConnected || bgAudioModeEnabled || PriorityRadioEngine.isRadioPlaying()) {
                    super.onWindowVisibilityChanged(View.VISIBLE);
                } else {
                    super.onWindowVisibilityChanged(visibility);
                }
            }

            @Override
            public void onPause() {
                if (scannerAudioConnected || bgAudioModeEnabled || PriorityRadioEngine.isRadioPlaying()) {
                    return;
                }
                super.onPause();
            }

            @Override
            public void pauseTimers() {
                if (scannerAudioConnected || bgAudioModeEnabled || PriorityRadioEngine.isRadioPlaying()) {
                    return;
                }
                super.pauseTimers();
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
                setBgServiceRunning(connected || bgAudioModeEnabled);
                attachOrUpdateNativeScannerAgc();
            });
        }

        @JavascriptInterface
        public void setBackgroundAudioMode(final boolean enabled) {
            mainHandler.post(() -> {
                setBgServiceRunning(enabled);
                if (enabled) Toast.makeText(MainActivity.this, "Screen-Off Audio Keep-Alive: ON", Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface
        public void notifyScannerSquelchState(final boolean squelchOpen, final String modulation, final float channelGainOffsetDb) {
            mainHandler.post(() -> {
                boolean isAm = modulation != null && modulation.toUpperCase(Locale.ROOT).contains("AM");
                if (isAm != currentModulationIsAm || Math.abs(channelGainOffsetDb - currentChannelGainOffsetDb) > 0.1f) {
                    currentModulationIsAm = isAm;
                    currentChannelGainOffsetDb = channelGainOffsetDb;
                    attachOrUpdateNativeScannerAgc();
                }
                PriorityRadioEngine.notifyScannerHit(prefs, squelchOpen);
            });
        }

        @JavascriptInterface
        public String getMixerSettingsJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("autoDuck", PriorityRadioEngine.isAutoDuckEnabled(prefs));
                o.put("ceilingPct", prefs.getInt("mixer_radio_ceiling_pct", 40));
                o.put("duckPct", prefs.getInt("mixer_radio_duck_pct", 5));
                o.put("agcDb", PriorityRadioEngine.getScannerAgcDb(prefs));
                o.put("holdMs", PriorityRadioEngine.getHoldTimeMs(prefs));
                o.put("radioPlaying", PriorityRadioEngine.isRadioPlaying());
                o.put("radioName", PriorityRadioEngine.getActiveStationName());
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void saveMixerSettings(final boolean autoDuck, final int ceilingPct, final int duckPct, final int agcDb, final int holdMs) {
            mainHandler.post(() -> {
                prefs.edit()
                        .putBoolean("mixer_auto_duck_on", autoDuck)
                        .putInt("mixer_radio_ceiling_pct", Math.max(10, Math.min(100, ceilingPct)))
                        .putInt("mixer_radio_duck_pct", Math.max(0, Math.min(25, duckPct)))
                        .putInt("mixer_scanner_agc_db", Math.max(0, Math.min(15, agcDb)))
                        .putInt("mixer_duck_hold_ms", Math.max(1000, Math.min(5000, holdMs)))
                        .apply();
                attachOrUpdateNativeScannerAgc();
                PriorityRadioEngine.updateSettingsLive(prefs);
            });
        }

        @JavascriptInterface
        public void fetchDriveRecordingsForWeb() {
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
                    JSONArray filesArr = new JSONArray();
                    int maxDates = Math.min(dateFolders.size(), 3);
                    for (int i = 0; i < maxDates; i++) {
                        String dFolder = dateFolders.get(i);
                        String dId = rootItems.get(dFolder);
                        if (dId == null) continue;
                        Map<String, String> dateChildren = parseDriveFolderAllItems(dId);
                        for (Map.Entry<String, String> e : dateChildren.entrySet()) {
                            if (e.getKey().toLowerCase().endsWith(".mp3")) {
                                JSONObject f = new JSONObject();
                                f.put("id", e.getValue());
                                f.put("name", e.getKey());
                                f.put("dateStr", dFolder);
                                filesArr.put(f);
                            } else {
                                Map<String, String> subChildren = parseDriveFolderAllItems(e.getValue());
                                for (Map.Entry<String, String> se : subChildren.entrySet()) {
                                    if (se.getKey().toLowerCase().endsWith(".mp3")) {
                                        JSONObject f = new JSONObject();
                                        f.put("id", se.getValue());
                                        f.put("name", se.getKey());
                                        f.put("dateStr", dFolder);
                                        filesArr.put(f);
                                    }
                                }
                            }
                        }
                    }
                    final String safeJson = JSONObject.quote(filesArr.toString());
                    mainHandler.post(() -> {
                        if (webView != null) {
                            webView.evaluateJavascript("if(window.__onNativeDriveFilesForTapeDeck)window.__onNativeDriveFilesForTapeDeck(" + safeJson + ");", null);
                        }
                    });
                } catch (Exception ignored) {}
            });
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
                    return;
                }
                lastSpokenText = cleanText;
                lastSpokenTimestamp = now;

                // Duck Commercial Radio IMMEDIATELY BEFORE TTS begins speaking
                PriorityRadioEngine.notifyTtsSpeaking(prefs, true);

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
                        PriorityRadioEngine.notifyTtsSpeaking(prefs, false);
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
        releaseNativeScannerAgc();
        setBgServiceRunning(false);
        releaseScannerWakeAndWifiLocks();
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
        js.append("        '#device-wrapper{width:95%!important;max-height:94dvh!important;transition:opacity 0.18s ease!important;}' +\n");
        js.append("        '#device-wrapper.flipping-out{opacity:0!important;}' +\n");
        js.append("        '#custom-scanner-ui{padding:16px 16px!important;gap:8px!important;}' +\n");
        js.append("        '.scanner-display{height:325px!important;transition:height 0.22s ease!important;}' +\n");
        js.append("        '.top-controls-container{height:34px!important;}' +\n");
        js.append("        '.top-right-group{height:34px!important;}' +\n");
        js.append("        '.top-right-group .btn{font-size:11px!important;}' +\n");
        js.append("        '.scanner-nav-6{display:grid!important;grid-template-columns:repeat(6,minmax(0,1fr))!important;gap:4px!important;height:32px!important;}' +\n");
        js.append("        '.scanner-nav-6 .btn{font-size:8.5px!important;letter-spacing:-0.3px!important;padding:2px 1px!important;white-space:nowrap!important;}' +\n");
        js.append("        '.essential-controls{grid-auto-rows:33px!important;gap:6px!important;}' +\n");
        js.append("        '.essential-controls > .btn{font-size:11.5px!important;}' +\n");
        js.append("        '.essential-controls .scroll-btn{font-size:9px!important;}' +\n");
        js.append("        '.numpad{grid-auto-rows:34px!important;gap:6px!important;}' +\n");
        js.append("        '.numpad .btn{font-size:15.5px!important;font-weight:900!important;}' +\n");

        /* Realistic Gloss Glass Display Surface Texture (Replaces smudged corner look) */
        js.append("        'html[data-disp-tex=\"glass\"] .scanner-display::after{opacity:1!important;background:linear-gradient(180deg,rgba(255,255,255,0.24) 0%,rgba(255,255,255,0.07) 4.5%,transparent 13%,transparent 89%,rgba(255,255,255,0.06) 100%),linear-gradient(115deg,transparent 0%,transparent 35%,rgba(255,255,255,0.10) 35.5%,rgba(255,255,255,0.025) 43%,transparent 43.5%,transparent 47.5%,rgba(255,255,255,0.055) 48%,transparent 51.5%,transparent 100%),radial-gradient(ellipse at 50% 50%,transparent 68%,rgba(0,0,0,0.42) 100%)!important;box-shadow:inset 0 1px 1px rgba(255,255,255,0.38),inset 0 -1px 2px rgba(0,0,0,0.65)!important;}' +\n");

        /* 3D Bevelled Buttons on WIRE, TRON, NEON & TERM Skins */
        js.append("        'html[data-skin=\"whiteline\"]{--ui-bg:radial-gradient(circle at 50% 12%,#26292e 0%,#121417 65%,#070809 100%)!important;--btn-bg:linear-gradient(180deg,#2e3238 0%,#181a1e 55%,#0d0e11 100%)!important;--btn-color:#ffffff!important;--btn-active-color:#38bdf8!important;--btn-border:1px solid rgba(255,255,255,0.75)!important;--btn-radius:7px!important;--btn-shadow:0 3px 0 #94a3b8,0 4px 6px rgba(0,0,0,0.85),inset 0 1px 1px rgba(255,255,255,0.45)!important;--btn-shadow-active:0 0 0 #94a3b8,inset 0 2px 5px rgba(0,0,0,0.9)!important;--btn-active-transform:translateY(2px)!important;}' +\n");
        js.append("        'html[data-skin=\"whiteline\"] .btn:active{background:linear-gradient(180deg,#181a1e 0%,#0d0e11 100%)!important;color:#38bdf8!important;}' +\n");
        js.append("        'html[data-skin=\"tron\"]{--ui-bg:radial-gradient(circle at 50% 12%,#0c2e36 0%,#06161a 65%,#02080a 100%)!important;--btn-bg:linear-gradient(180deg,#103c47 0%,#071f26 55%,#031014 100%)!important;--btn-color:#00ffff!important;--btn-active-color:#ffffff!important;--btn-border:1px solid #00ffff!important;--btn-radius:7px!important;--btn-shadow:0 3px 0 #008b8b,0 4px 7px rgba(0,0,0,0.85),inset 0 1px 1px rgba(180,255,255,0.45)!important;--btn-shadow-active:0 0 0 #008b8b,inset 0 2px 5px rgba(0,0,0,0.9)!important;--btn-active-transform:translateY(2px)!important;}' +\n");
        js.append("        'html[data-skin=\"tron\"] .btn:active{background:linear-gradient(180deg,#071f26 0%,#031014 100%)!important;color:#ffffff!important;box-shadow:0 0 12px rgba(0,255,255,0.5),inset 0 2px 5px rgba(0,0,0,0.9)!important;}' +\n");
        js.append("        'html[data-skin=\"neongreen\"]{--ui-bg:radial-gradient(circle at 50% 12%,#11330d 0%,#081906 65%,#030a02 100%)!important;--btn-bg:linear-gradient(180deg,#184212 0%,#0b2108 55%,#051104 100%)!important;--btn-color:#39ff14!important;--btn-active-color:#ffffff!important;--btn-border:1px solid #39ff14!important;--btn-radius:7px!important;--btn-shadow:0 3px 0 #1b8a08,0 4px 7px rgba(0,0,0,0.85),inset 0 1px 1px rgba(190,255,180,0.45)!important;--btn-shadow-active:0 0 0 #1b8a08,inset 0 2px 5px rgba(0,0,0,0.9)!important;--btn-active-transform:translateY(2px)!important;}' +\n");
        js.append("        'html[data-skin=\"neongreen\"] .btn:active{background:linear-gradient(180deg,#0b2108 0%,#051104 100%)!important;color:#ffffff!important;box-shadow:0 0 12px rgba(57,255,20,0.5),inset 0 2px 5px rgba(0,0,0,0.9)!important;}' +\n");
        js.append("        'html[data-skin=\"amber\"]{--ui-bg:radial-gradient(circle at 50% 12%,#332406 0%,#1a1203 65%,#0a0701 100%)!important;--btn-bg:linear-gradient(180deg,#422f08 0%,#211704 55%,#120c02 100%)!important;--btn-color:#ffb000!important;--btn-active-color:#ffffff!important;--btn-border:1px solid #ffb000!important;--btn-radius:7px!important;--btn-shadow:0 3px 0 #996900,0 4px 7px rgba(0,0,0,0.85),inset 0 1px 1px rgba(255,230,170,0.45)!important;--btn-shadow-active:0 0 0 #996900,inset 0 2px 5px rgba(0,0,0,0.9)!important;--btn-active-transform:translateY(2px)!important;}' +\n");
        js.append("        'html[data-skin=\"amber\"] .btn:active{background:linear-gradient(180deg,#211704 0%,#120c02 100%)!important;color:#ffffff!important;box-shadow:0 0 12px rgba(255,176,0,0.5),inset 0 2px 5px rgba(0,0,0,0.9)!important;}' +\n");

        /* SCANNER LITE MODE */
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

        /* MOBILE LANDSCAPE SPLIT-SCREEN COCKPIT LAYOUT */
        js.append("        '@media (orientation: landscape) and (max-height: 540px){' +\n");
        js.append("          '#device-wrapper{width:96vw!important;max-width:96vw!important;height:94dvh!important;max-height:94dvh!important;}' +\n");
        js.append("          '#custom-scanner-ui{display:grid!important;grid-template-columns:1.08fr 1fr!important;grid-template-rows:auto 1fr!important;gap:6px 12px!important;padding:10px 14px!important;height:100%!important;overflow:hidden!important;}' +\n");
        js.append("          '.scanner-display, html[data-scanner-lite=\"true\"] .scanner-display{grid-column:1!important;grid-row:1 / span 2!important;height:100%!important;min-height:0!important;}' +\n");
        js.append("          '#custom-imgd{max-height:92px!important;}' +\n");
        js.append("          '.top-controls-container{grid-column:2!important;grid-row:1!important;height:30px!important;}' +\n");
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
        js.append("        '.top-leds-left{gap:8px!important;}' +\n");
        js.append("        '.top-leds-right{gap:8px!important;}' +\n");
        js.append("        '#voice-switch-track{width:24px;height:11px;border-radius:999px;background:rgba(0,0,0,0.55);border:1px solid rgba(255,255,255,0.16);box-shadow:inset 0 1px 3px rgba(0,0,0,0.85),0 1px 0 rgba(255,255,255,0.08);padding:1px;display:flex;align-items:center;position:relative;flex-shrink:0;}' +\n");
        js.append("        '#voice-switch-thumb{width:10px;height:7px;border-radius:999px;background:var(--btn-bg);border:var(--btn-border);box-shadow:0 1px 2px rgba(0,0,0,0.75),inset 0 1px 0 rgba(255,255,255,0.2);transform:translateX(0);transition:transform 0.22s cubic-bezier(0.4,0,0.2,1);display:flex;align-items:center;justify-content:center;}' +\n");
        js.append("        '#voice-switch-thumb::after{content:\"\";width:2px;height:3px;border-radius:1px;background:rgba(255,220,220,0.85);}' +\n");
        js.append("        '#btn-voice-toggle.active #voice-switch-thumb{transform:translateX(10px);}' +\n");
        js.append("        '#voice-led{flex-shrink:0;width:10px;height:10px;border-radius:50%;background-color:var(--led-off-bg,#1a1a1a);border:var(--led-off-border,2px solid #000);box-shadow:var(--led-off-shadow,inset 1px 1px 3px rgba(0,0,0,0.8),0 1px 1px rgba(255,255,255,0.1));transition:all 0.3s ease;}' +\n");
        js.append("        '@keyframes voice-led-pulse-green{0%,100%{background-color:#10b981;border-color:#a7f3d0;box-shadow:inset 1px 1px 2px rgba(255,255,255,0.9),0 0 0 1px rgba(0,0,0,0.65),0 0 8px #10b981,0 0 14px rgba(16,185,129,0.75);}50%{background-color:#065f46;border-color:#059669;box-shadow:inset 1px 1px 2px rgba(255,255,255,0.35),0 0 0 1px rgba(0,0,0,0.65),0 0 2px rgba(16,185,129,0.25);}}' +\n");
        js.append("        '#btn-voice-toggle.active #voice-led{border-style:solid!important;border-width:2px!important;animation:voice-led-pulse-green 3.8s ease-in-out infinite!important;}' +\n");

        /* LAMP & EXIT Button Caps */
        js.append("        '#btn-lamp-toggle{display:inline-flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;background:transparent;border:none;padding:0;margin:0 3px 0 1px;cursor:pointer;touch-action:manipulation;}' +\n");
        js.append("        '#btn-lamp-toggle .lamp-cap{width:22px;height:16px;border-radius:5px;background:linear-gradient(180deg,#eab308,#a16207);border:1px solid #422006;box-shadow:0 2px 3px rgba(0,0,0,0.8),inset 0 1px 0 rgba(255,255,255,0.4);display:flex;align-items:center;justify-content:center;}' +\n");
        js.append("        '#btn-lamp-toggle .lamp-cap::after{content:\"\";width:8px;height:3px;border-radius:1.5px;background:rgba(254,249,195,0.92);box-shadow:0 0 4px rgba(254,249,195,0.5);}' +\n");
        js.append("        '#btn-lamp-toggle:active .lamp-cap{transform:translateY(1px);filter:brightness(1.25);}' +\n");
        js.append("        '#btn-hardware-exit{display:inline-flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;background:transparent;border:none;padding:0;margin:0 0 0 2px;cursor:pointer;touch-action:manipulation;}' +\n");
        js.append("        '#btn-hardware-exit .exit-cap{width:22px;height:16px;border-radius:5px;background:linear-gradient(180deg,#b91c1c,#7f1d1d);border:1px solid #450a0a;box-shadow:0 2px 3px rgba(0,0,0,0.8),inset 0 1px 0 rgba(255,255,255,0.32);display:flex;align-items:center;justify-content:center;}' +\n");
        js.append("        '#btn-hardware-exit .exit-cap::after{content:\"\";width:8px;height:3px;border-radius:1.5px;background:rgba(255,220,220,0.88);}' +\n");
        js.append("        '#btn-hardware-exit:active .exit-cap{transform:translateY(1px);filter:brightness(1.2);}' +\n");

        /* Signal Meter */
        js.append("        '#sig-meter-host{display:inline-flex;align-items:center;justify-content:center;width:124px;height:23px;background:rgba(6,8,12,0.82);border:1px solid rgba(255,255,255,0.16);border-radius:5px;padding:2px 5px;box-shadow:inset 0 2px 4px rgba(0,0,0,0.9),0 1px 0 rgba(255,255,255,0.1);position:relative;overflow:hidden;pointer-events:auto;cursor:pointer;}' +\n");
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

        /* Per-Channel DSP & Priority Audio Mixer Modal Styles */
        js.append("        '#proscan-dsp-mixer-modal{position:absolute;z-index:130;inset:10px;border:1px solid #6b777d;border-radius:16px;background:linear-gradient(180deg,#161b20 0%,#0d1014 100%);box-shadow:0 16px 40px rgba(0,0,0,0.92),inset 0 1px 0 rgba(255,255,255,0.12);color:#e2e8f0;display:flex;flex-direction:column;overflow:hidden;font-family:\"JetBrains Mono\",monospace;}' +\n");
        js.append("        '#proscan-dsp-mixer-modal[hidden]{display:none!important;}' +\n");
        js.append("        '.dm-hdr{display:flex;align-items:center;justify-content:space-between;padding:9px 12px;background:rgba(0,0,0,0.55);border-bottom:1px solid rgba(255,255,255,0.12);}' +\n");
        js.append("        '.dm-title{font-family:\"Orbitron\",sans-serif;font-size:11px;font-weight:800;color:#38bdf8;letter-spacing:1px;}' +\n");
        js.append("        '.dm-body{flex:1;overflow-y:auto;padding:10px 12px;display:flex;flex-direction:column;gap:9px;font-size:10px;}' +\n");
        js.append("        '.dm-card{background:rgba(255,255,255,0.04);border:1px solid rgba(255,255,255,0.1);border-radius:10px;padding:8px 10px;display:flex;flex-direction:column;gap:6px;}' +\n");
        js.append("        '.dm-card-title{font-size:9.5px;font-weight:900;color:#facc15;letter-spacing:0.6px;text-transform:uppercase;display:flex;justify-content:space-between;align-items:center;}' +\n");
        js.append("        '.dm-row{display:flex;align-items:center;justify-content:space-between;gap:8px;}' +\n");
        js.append("        '.dm-row label{font-size:9.5px;color:#cbd5e1;font-weight:700;}' +\n");
        js.append("        '.dm-slider-wrap{display:flex;align-items:center;gap:6px;flex:1;max-width:58%;}' +\n");
        js.append("        '.dm-slider-wrap input[type=range]{flex:1;accent-color:#38bdf8;height:5px;}' +\n");
        js.append("        '.dm-val{min-width:42px;text-align:right;font-weight:800;color:#39ff14;font-size:9.5px;}' +\n");
        js.append("        '.dm-preset-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:4px;}' +\n");
        js.append("        '.dm-pbtn{padding:5px 2px;font-family:\"JetBrains Mono\",monospace;font-size:8.5px;font-weight:800;border-radius:5px;border:1px solid #334155;background:#1e293b;color:#cbd5e1;cursor:pointer;text-align:center;}' +\n");
        js.append("        '.dm-pbtn.active{background:#38bdf8;color:#090d14;border-color:#bae6fd;font-weight:900;}' +\n");

        js.append("        '#display-clock-hud{position:absolute!important;top:6px!important;left:10px!important;right:auto!important;z-index:3!important;}' +\n");
        js.append("        '#scanner-bat-hud{position:absolute!important;top:6px!important;right:9px!important;left:auto!important;z-index:3!important;font-family:\"JetBrains Mono\",monospace!important;font-size:10px!important;font-weight:700!important;letter-spacing:0.8px!important;color:var(--log-text)!important;opacity:0.88;pointer-events:none;display:inline-flex!important;align-items:center!important;line-height:1;transition:opacity 0.3s ease,color 0.3s ease;}' +\n");
        js.append("        '#scanner-bat-hud *{font-family:inherit!important;font-size:inherit!important;font-weight:inherit!important;letter-spacing:inherit!important;line-height:inherit!important;}' +\n");
        js.append("        '#scanner-bat-txt{color:inherit!important;}' +\n");
        js.append("        '#scanner-bat-hud.disconnected{opacity:0!important;}' +\n");
        js.append("        '.bat-icon-box{display:inline-block;width:13px;height:8px;border:1.4px solid currentColor;border-radius:2px;padding:1px;position:relative;vertical-align:middle;margin-right:4px;box-sizing:border-box;flex-shrink:0;}' +\n");
        js.append("        '.bat-icon-box::after{content:\"\";position:absolute;right:-3px;top:1px;width:1.5px;height:3px;background:currentColor;border-radius:0 1px 1px 0;}' +\n");
        js.append("        '.bat-icon-fill{display:block;height:100%;background:currentColor;width:80%;transition:width 0.3s ease;}' +\n");

        js.append("        'html.tv-off #scanner-bat-hud,.scanner-display.tv-shutting-down #scanner-bat-hud{filter:grayscale(1) brightness(2.5)!important;animation:crtPowerOffImgF 0.38s cubic-bezier(0.4,0,0.2,1) forwards!important;};'\n");
        js.append("      document.head.appendChild(st);\n");
        js.append("    } else if (existingSt && document.head.lastElementChild !== existingSt) {\n");
        js.append("      document.head.appendChild(existingSt);\n");
        js.append("    }\n");

        /* Signal Meter in #brand-logo-slot */
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

        js.append("      var mHost = document.getElementById('sig-meter-host');\n");
        js.append("      if (mHost) mHost.addEventListener('click', window.handleBrandCornerTap);\n");
        js.append("      window.__applySigMeterStyle(window.__sigMeterStyle, false);\n");

        /* Real-Time Signal Meter + Priority Auto-Ducking Squelch Bridge */
        js.append("      var smCarrierLvl = 0, smAudioLvl = 0, smAdaptiveMax = 14.0, smPhase = 0;\n");
        js.append("      var lastReportedRxState = false;\n");
        js.append("      function renderLiveScannerSignalMeter() {\n");
        js.append("        requestAnimationFrame(renderLiveScannerSignalMeter);\n");
        js.append("        var rawAud = 0;\n");
        js.append("        var rxLedEl = document.getElementById('rx-led');\n");
        js.append("        var isRxVoiceActive = Boolean(rxLedEl && rxLedEl.classList.contains('active'));\n");
        js.append("        if (isRxVoiceActive !== lastReportedRxState) {\n");
        js.append("          lastReportedRxState = isRxVoiceActive;\n");
        js.append("          if (window.AndroidApp && window.AndroidApp.notifyScannerSquelchState) {\n");
        js.append("            window.AndroidApp.notifyScannerSquelchState(isRxVoiceActive, window.__currentChannelMod || 'NFM', window.__currentChannelGainDb || 0);\n");
        js.append("          }\n");
        js.append("        }\n");
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

        /* Isolated LITE button replacement: completely detached from #btn-manual-open so ProScan never hears a scanner button press! */
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

        /* Second Row: Clean 6-Button Grid + Per-Channel DSP Profiles + Priority Mixer Modal */
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

        /* Per-Channel DSP Profiles, AGC Makeup Boost & Priority Mixer Engine */
        js.append("      window.__channelDspProfiles = JSON.parse(localStorage.getItem('proscan_ch_dsp_profiles_v1') || '{}');\n");
        js.append("      window.__currentActiveChannelKey = 'GLOBAL';\n");
        js.append("      window.__currentActiveChannelName = 'GLOBAL DEFAULT';\n");
        js.append("      window.__currentChannelMod = 'NFM';\n");
        js.append("      window.__currentChannelGainDb = 0;\n");

        js.append("      window.__dspPresetCurves = {\n");
        js.append("        'AUTO':     { hp: 320, peakFreq: 2150, peakQ: 1.1, peakGain: 7.5, lp: 3900 },\n");
        js.append("        'AIRBAND':  { hp: 420, peakFreq: 2250, peakQ: 1.3, peakGain: 10.5, lp: 3200 },\n");
        js.append("        'VHF_WEAK': { hp: 460, peakFreq: 2050, peakQ: 1.4, peakGain: 9.5, lp: 3000 },\n");
        js.append("        'REPEATER': { hp: 220, peakFreq: 2100, peakQ: 0.9, peakGain: 4.5, lp: 4500 },\n");
        js.append("        'FLAT':     { hp: 60,  peakFreq: 2000, peakQ: 0.7, peakGain: 0.0, lp: 12000 }\n");
        js.append("      };\n");

        js.append("      window.setupAudioDetection = function() {\n");
        js.append("        var player = document.getElementById('audio_player');\n");
        js.append("        if (typeof isAudioSetup !== 'undefined' && isAudioSetup) {\n");
        js.append("          if (typeof detectAudio === 'function') detectAudio();\n");
        js.append("          return;\n");
        js.append("        }\n");
        js.append("        if (!player) return;\n");
        js.append("        try {\n");
        js.append("          var AudioCtx = window.AudioContext || window.webkitAudioContext;\n");
        js.append("          audioContext = window.audioContext = new AudioCtx();\n");
        js.append("          analyser = window.analyser = audioContext.createAnalyser();\n");
        js.append("          analyser.smoothingTimeConstant = 0.5;\n");
        js.append("          window.source = audioContext.createMediaElementSource(player);\n");
        js.append("          window.source.connect(analyser);\n");
        js.append("          analyser.connect(audioContext.destination);\n");
        js.append("          analyser.fftSize = 512;\n");
        js.append("          dataArray = new Uint8Array(analyser.frequencyBinCount);\n");
        js.append("          freqArray = new Uint8Array(analyser.frequencyBinCount);\n");
        js.append("          isAudioSetup = true;\n");
        js.append("          if (window.__applyScannerDspRouting) window.__applyScannerDspRouting();\n");
        js.append("          if (typeof detectAudio === 'function') detectAudio();\n");
        js.append("        } catch(e) {}\n");
        js.append("      };\n");

        js.append("      window.__scannerDspOn = localStorage.getItem('proscan_scanner_dsp_v1') !== 'false';\n");
        js.append("      window.__applyScannerDspRouting = function() {\n");
        js.append("        var prof = window.__channelDspProfiles[window.__currentActiveChannelKey] || { preset: 'AUTO', gainDb: 0 };\n");
        js.append("        var presetKey = prof.preset || 'AUTO';\n");
        js.append("        if (presetKey === 'AUTO' && (window.__currentChannelMod === 'AM' || /^1[1-3]\\d\\./.test(window.__currentActiveChannelKey))) {\n");
        js.append("          presetKey = 'AIRBAND';\n");
        js.append("        }\n");
        js.append("        var curve = window.__dspPresetCurves[presetKey] || window.__dspPresetCurves['AUTO'];\n");
        js.append("        var chGainDb = parseFloat(prof.gainDb || 0);\n");
        js.append("        window.__currentChannelGainDb = chGainDb;\n");
        js.append("        var mixerCfg = {};\n");
        js.append("        try { if (window.AndroidApp && window.AndroidApp.getMixerSettingsJson) mixerCfg = JSON.parse(window.AndroidApp.getMixerSettingsJson()); } catch(e) {}\n");
        js.append("        var baseAgcDb = typeof mixerCfg.agcDb === 'number' ? mixerCfg.agcDb : 8;\n");
        js.append("        var amBonusDb = (window.__currentChannelMod === 'AM' || presetKey === 'AIRBAND') ? 3.0 : 0.0;\n");
        js.append("        var totalBoostDb = Math.max(-6, Math.min(24, baseAgcDb + amBonusDb + chGainDb));\n");

        js.append("        if (!window.audioContext || !window.source || !window.analyser) return;\n");
        js.append("        if (!window.__dspNodes) {\n");
        js.append("          try {\n");
        js.append("            var ctx = window.audioContext;\n");
        js.append("            var hp = ctx.createBiquadFilter(); hp.type = 'highpass';\n");
        js.append("            var peak = ctx.createBiquadFilter(); peak.type = 'peaking';\n");
        js.append("            var lp = ctx.createBiquadFilter(); lp.type = 'lowpass';\n");
        js.append("            var preGain = ctx.createGain();\n");
        js.append("            var comp = ctx.createDynamicsCompressor();\n");
        js.append("            comp.threshold.value = -22;\n");
        js.append("            comp.knee.value = 6;\n");
        js.append("            comp.ratio.value = 12;\n");
        js.append("            comp.attack.value = 0.0015;\n");
        js.append("            comp.release.value = 0.18;\n");
        js.append("            hp.connect(peak); peak.connect(lp); lp.connect(preGain); preGain.connect(comp);\n");
        js.append("            window.__dspNodes = { hp: hp, peak: peak, lp: lp, preGain: preGain, comp: comp };\n");
        js.append("          } catch(e) {}\n");
        js.append("        }\n");
        js.append("        if (window.__dspNodes) {\n");
        js.append("          try {\n");
        js.append("            window.__dspNodes.hp.frequency.value = curve.hp;\n");
        js.append("            window.__dspNodes.peak.frequency.value = curve.peakFreq;\n");
        js.append("            window.__dspNodes.peak.Q.value = curve.peakQ;\n");
        js.append("            window.__dspNodes.peak.gain.value = window.__scannerDspOn ? curve.peakGain : 0;\n");
        js.append("            window.__dspNodes.lp.frequency.value = window.__scannerDspOn ? curve.lp : 16000;\n");
        js.append("            window.__dspNodes.preGain.gain.value = Math.pow(10, totalBoostDb / 20);\n");
        js.append("            window.source.disconnect();\n");
        js.append("            window.__dspNodes.comp.disconnect();\n");
        js.append("            window.source.connect(window.__dspNodes.hp);\n");
        js.append("            window.__dspNodes.comp.connect(window.analyser);\n");
        js.append("          } catch(e) {}\n");
        js.append("        }\n");
        js.append("      };\n");

        /* Build Per-Channel DSP & Priority Mixer Modal */
        js.append("      var scannerUiBox = document.getElementById('custom-scanner-ui');\n");
        js.append("      if (scannerUiBox && !document.getElementById('proscan-dsp-mixer-modal')) {\n");
        js.append("        var dmModal = document.createElement('div');\n");
        js.append("        dmModal.id = 'proscan-dsp-mixer-modal';\n");
        js.append("        dmModal.hidden = true;\n");
        js.append("        dmModal.innerHTML = '' +\n");
        js.append("          '<div class=\"dm-hdr\">' +\n");
        js.append("            '<span class=\"dm-title\">DSP PROFILES &amp; PRIORITY MIXER</span>' +\n");
        js.append("            '<button class=\"manual-close-btn\" type=\"button\" id=\"dm-close-btn\">CLOSE ✕</button>' +\n");
        js.append("          '</div>' +\n");
        js.append("          '<div class=\"dm-body\">' +\n");
        js.append("            '<div class=\"dm-card\">' +\n");
        js.append("              '<div class=\"dm-card-title\"><span>PER-CHANNEL DSP PROFILE</span><span id=\"dm-active-ch-lbl\" style=\"color:#38bdf8;\">GLOBAL</span></div>' +\n");
        js.append("              '<div class=\"dm-preset-grid\" id=\"dm-preset-btns\">' +\n");
        js.append("                '<button type=\"button\" class=\"dm-pbtn\" data-p=\"AUTO\">AUTO</button>' +\n");
        js.append("                '<button type=\"button\" class=\"dm-pbtn\" data-p=\"AIRBAND\">AIR AM</button>' +\n");
        js.append("                '<button type=\"button\" class=\"dm-pbtn\" data-p=\"VHF_WEAK\">VHF DX</button>' +\n");
        js.append("                '<button type=\"button\" class=\"dm-pbtn\" data-p=\"REPEATER\">RPT FM</button>' +\n");
        js.append("                '<button type=\"button\" class=\"dm-pbtn\" data-p=\"FLAT\">FLAT</button>' +\n");
        js.append("              '</div>' +\n");
        js.append("              '<div class=\"dm-row\" style=\"margin-top:4px;\">' +\n");
        js.append("                '<label>Channel Gain Offset</label>' +\n");
        js.append("                '<div class=\"dm-slider-wrap\">' +\n");
        js.append("                  '<input type=\"range\" id=\"dm-ch-gain\" min=\"-6\" max=\"12\" step=\"1\" value=\"0\">' +\n");
        js.append("                  '<span class=\"dm-val\" id=\"dm-ch-gain-val\">0 dB</span>' +\n");
        js.append("                '</div>' +\n");
        js.append("              '</div>' +\n");
        js.append("              '<div style=\"font-size:8.5px;color:#94a3b8;\">Auto-switches filter &amp; gain whenever the scanner lands on this frequency (+3 dB auto boost on AM Airband).</div>' +\n");
        js.append("            '</div>' +\n");
        js.append("            '<div class=\"dm-card\">' +\n");
        js.append("              '<div class=\"dm-card-title\"><span>PRIORITY AUTO-DUCK MIXER</span><button type=\"button\" class=\"dm-pbtn\" id=\"dm-autoduck-toggle\" style=\"padding:3px 8px;\">ON</button></div>' +\n");
        js.append("              '<div class=\"dm-row\">' +\n");
        js.append("                '<label>Scanner AGC Boost</label>' +\n");
        js.append("                '<div class=\"dm-slider-wrap\">' +\n");
        js.append("                  '<input type=\"range\" id=\"dm-agc-db\" min=\"0\" max=\"15\" step=\"1\" value=\"8\">' +\n");
        js.append("                  '<span class=\"dm-val\" id=\"dm-agc-db-val\">+8 dB</span>' +\n");
        js.append("                '</div>' +\n");
        js.append("              '</div>' +\n");
        js.append("              '<div class=\"dm-row\">' +\n");
        js.append("                '<label>Radio Baseline Ceiling</label>' +\n");
        js.append("                '<div class=\"dm-slider-wrap\">' +\n");
        js.append("                  '<input type=\"range\" id=\"dm-rad-ceil\" min=\"10\" max=\"100\" step=\"5\" value=\"40\">' +\n");
        js.append("                  '<span class=\"dm-val\" id=\"dm-rad-ceil-val\">40%</span>' +\n");
        js.append("                '</div>' +\n");
        js.append("              '</div>' +\n");
        js.append("              '<div class=\"dm-row\">' +\n");
        js.append("                '<label>Radio Ducked Level</label>' +\n");
        js.append("                '<div class=\"dm-slider-wrap\">' +\n");
        js.append("                  '<input type=\"range\" id=\"dm-rad-duck\" min=\"0\" max=\"25\" step=\"1\" value=\"5\">' +\n");
        js.append("                  '<span class=\"dm-val\" id=\"dm-rad-duck-val\">5%</span>' +\n");
        js.append("                '</div>' +\n");
        js.append("              '</div>' +\n");
        js.append("              '<div class=\"dm-row\">' +\n");
        js.append("                '<label>Duck Hold Time</label>' +\n");
        js.append("                '<div class=\"dm-slider-wrap\">' +\n");
        js.append("                  '<input type=\"range\" id=\"dm-duck-hold\" min=\"1000\" max=\"5000\" step=\"250\" value=\"2500\">' +\n");
        js.append("                  '<span class=\"dm-val\" id=\"dm-duck-hold-val\">2.5s</span>' +\n");
        js.append("                '</div>' +\n");
        js.append("              '</div>' +\n");
        js.append("            '</div>' +\n");
        js.append("          '</div>';\n");
        js.append("        ['pointerdown','pointerup','click'].forEach(function(ev){ dmModal.addEventListener(ev, function(e){ e.stopPropagation(); }); });\n");
        js.append("        scannerUiBox.appendChild(dmModal);\n");

        js.append("        window.__openDspMixerModal = function() {\n");
        js.append("          var chKey = window.__currentActiveChannelKey || 'GLOBAL';\n");
        js.append("          document.getElementById('dm-active-ch-lbl').innerText = chKey + ' (' + (window.__currentChannelMod || 'NFM') + ')';\n");
        js.append("          var prof = window.__channelDspProfiles[chKey] || { preset: 'AUTO', gainDb: 0 };\n");
        js.append("          document.querySelectorAll('#dm-preset-btns .dm-pbtn').forEach(function(b){\n");
        js.append("            b.classList.toggle('active', b.getAttribute('data-p') === (prof.preset || 'AUTO'));\n");
        js.append("          });\n");
        js.append("          var gSl = document.getElementById('dm-ch-gain');\n");
        js.append("          gSl.value = prof.gainDb || 0;\n");
        js.append("          document.getElementById('dm-ch-gain-val').innerText = (gSl.value > 0 ? '+' : '') + gSl.value + ' dB';\n");
        js.append("          try {\n");
        js.append("            if (window.AndroidApp && window.AndroidApp.getMixerSettingsJson) {\n");
        js.append("              var m = JSON.parse(window.AndroidApp.getMixerSettingsJson());\n");
        js.append("              var adBtn = document.getElementById('dm-autoduck-toggle');\n");
        js.append("              var isAd = m.autoDuck !== false;\n");
        js.append("              adBtn.classList.toggle('active', isAd);\n");
        js.append("              adBtn.innerText = isAd ? 'AUTO-DUCK: ON' : 'AUTO-DUCK: OFF';\n");
        js.append("              document.getElementById('dm-agc-db').value = m.agcDb !== undefined ? m.agcDb : 8;\n");
        js.append("              document.getElementById('dm-agc-db-val').innerText = '+' + document.getElementById('dm-agc-db').value + ' dB';\n");
        js.append("              document.getElementById('dm-rad-ceil').value = m.ceilingPct !== undefined ? m.ceilingPct : 40;\n");
        js.append("              document.getElementById('dm-rad-ceil-val').innerText = document.getElementById('dm-rad-ceil').value + '%';\n");
        js.append("              document.getElementById('dm-rad-duck').value = m.duckPct !== undefined ? m.duckPct : 5;\n");
        js.append("              document.getElementById('dm-rad-duck-val').innerText = document.getElementById('dm-rad-duck').value + '%';\n");
        js.append("              document.getElementById('dm-duck-hold').value = m.holdMs !== undefined ? m.holdMs : 2500;\n");
        js.append("              document.getElementById('dm-duck-hold-val').innerText = (document.getElementById('dm-duck-hold').value / 1000).toFixed(1) + 's';\n");
        js.append("            }\n");
        js.append("          } catch(e) {}\n");
        js.append("          dmModal.hidden = false;\n");
        js.append("        };\n");

        js.append("        function saveCurrentChannelProfile(preset, gainDb) {\n");
        js.append("          var chKey = window.__currentActiveChannelKey || 'GLOBAL';\n");
        js.append("          var cur = window.__channelDspProfiles[chKey] || { preset: 'AUTO', gainDb: 0 };\n");
        js.append("          if (preset !== null) cur.preset = preset;\n");
        js.append("          if (gainDb !== null) cur.gainDb = parseInt(gainDb, 10) || 0;\n");
        js.append("          window.__channelDspProfiles[chKey] = cur;\n");
        js.append("          localStorage.setItem('proscan_ch_dsp_profiles_v1', JSON.stringify(window.__channelDspProfiles));\n");
        js.append("          window.__applyScannerDspRouting();\n");
        js.append("        }\n");

        js.append("        document.querySelectorAll('#dm-preset-btns .dm-pbtn').forEach(function(btn) {\n");
        js.append("          btn.addEventListener('click', function() {\n");
        js.append("            var p = btn.getAttribute('data-p');\n");
        js.append("            document.querySelectorAll('#dm-preset-btns .dm-pbtn').forEach(function(b){ b.classList.toggle('active', b === btn); });\n");
        js.append("            saveCurrentChannelProfile(p, null);\n");
        js.append("          });\n");
        js.append("        });\n");

        js.append("        document.getElementById('dm-ch-gain').addEventListener('input', function() {\n");
        js.append("          document.getElementById('dm-ch-gain-val').innerText = (this.value > 0 ? '+' : '') + this.value + ' dB';\n");
        js.append("          saveCurrentChannelProfile(null, this.value);\n");
        js.append("        });\n");

        js.append("        function pushMixerToNative() {\n");
        js.append("          var ad = document.getElementById('dm-autoduck-toggle').classList.contains('active');\n");
        js.append("          var agc = parseInt(document.getElementById('dm-agc-db').value, 10) || 8;\n");
        js.append("          var ceil = parseInt(document.getElementById('dm-rad-ceil').value, 10) || 40;\n");
        js.append("          var duck = parseInt(document.getElementById('dm-rad-duck').value, 10) || 5;\n");
        js.append("          var hold = parseInt(document.getElementById('dm-duck-hold').value, 10) || 2500;\n");
        js.append("          if (window.AndroidApp && window.AndroidApp.saveMixerSettings) {\n");
        js.append("            window.AndroidApp.saveMixerSettings(ad, ceil, duck, agc, hold);\n");
        js.append("          }\n");
        js.append("          window.__applyScannerDspRouting();\n");
        js.append("        }\n");

        js.append("        document.getElementById('dm-autoduck-toggle').addEventListener('click', function() {\n");
        js.append("          var nxt = !this.classList.contains('active');\n");
        js.append("          this.classList.toggle('active', nxt);\n");
        js.append("          this.innerText = nxt ? 'AUTO-DUCK: ON' : 'AUTO-DUCK: OFF';\n");
        js.append("          pushMixerToNative();\n");
        js.append("        });\n");
        js.append("        document.getElementById('dm-agc-db').addEventListener('input', function() {\n");
        js.append("          document.getElementById('dm-agc-db-val').innerText = '+' + this.value + ' dB';\n");
        js.append("          pushMixerToNative();\n");
        js.append("        });\n");
        js.append("        document.getElementById('dm-rad-ceil').addEventListener('input', function() {\n");
        js.append("          document.getElementById('dm-rad-ceil-val').innerText = this.value + '%';\n");
        js.append("          pushMixerToNative();\n");
        js.append("        });\n");
        js.append("        document.getElementById('dm-rad-duck').addEventListener('input', function() {\n");
        js.append("          document.getElementById('dm-rad-duck-val').innerText = this.value + '%';\n");
        js.append("          pushMixerToNative();\n");
        js.append("        });\n");
        js.append("        document.getElementById('dm-duck-hold').addEventListener('input', function() {\n");
        js.append("          document.getElementById('dm-duck-hold-val').innerText = (this.value / 1000).toFixed(1) + 's';\n");
        js.append("          pushMixerToNative();\n");
        js.append("        });\n");
        js.append("        document.getElementById('dm-close-btn').addEventListener('click', function() { dmModal.hidden = true; });\n");
        js.append("      }\n");

        /* Add DSP as the 6th button in the second row (Tap = Toggle DSP, Hold = Open Per-Channel DSP & Mixer Modal) */
        js.append("      var dspBtn = document.createElement('button');\n");
        js.append("      dspBtn.id = 'btn-scanner-dsp'; dspBtn.className = 'btn'; dspBtn.type = 'button';\n");
        js.append("      function syncDspBtnUi(active) {\n");
        js.append("        dspBtn.classList.toggle('active', active);\n");
        js.append("        dspBtn.innerText = active ? 'DSP: ON' : 'DSP: OFF';\n");
        js.append("      }\n");
        js.append("      syncDspBtnUi(window.__scannerDspOn);\n");
        js.append("      var dspHoldTimer = null, dspLongFired = false;\n");
        js.append("      dspBtn.addEventListener('pointerdown', function() {\n");
        js.append("        clearTimeout(dspHoldTimer); dspLongFired = false;\n");
        js.append("        dspHoldTimer = setTimeout(function() {\n");
        js.append("          dspHoldTimer = null; dspLongFired = true;\n");
        js.append("          if (navigator.vibrate) navigator.vibrate(35);\n");
        js.append("          if (window.__openDspMixerModal) window.__openDspMixerModal();\n");
        js.append("        }, 480);\n");
        js.append("      });\n");
        js.append("      dspBtn.addEventListener('pointerup', function() {\n");
        js.append("        var wasTap = dspHoldTimer !== null; clearTimeout(dspHoldTimer); dspHoldTimer = null;\n");
        js.append("        if (wasTap && !dspLongFired) {\n");
        js.append("          if (navigator.vibrate) navigator.vibrate(20);\n");
        js.append("          window.__scannerDspOn = !window.__scannerDspOn;\n");
        js.append("          localStorage.setItem('proscan_scanner_dsp_v1', String(window.__scannerDspOn));\n");
        js.append("          syncDspBtnUi(window.__scannerDspOn);\n");
        js.append("          window.__applyScannerDspRouting();\n");
        js.append("          if (typeof showDisplayHud === 'function') {\n");
        js.append("            showDisplayHud('DSP + AGC: ' + (window.__scannerDspOn ? 'ON (HOLD=MIXER)' : 'OFF (HOLD=MIXER)'), 1400);\n");
        js.append("          }\n");
        js.append("        }\n");
        js.append("      });\n");
        js.append("      dspBtn.addEventListener('pointerleave', function() { clearTimeout(dspHoldTimer); dspHoldTimer = null; });\n");
        js.append("      dspBtn.addEventListener('pointercancel', function() { clearTimeout(dspHoldTimer); dspHoldTimer = null; });\n");
        js.append("      rowParent.appendChild(dspBtn);\n");
        js.append("    }\n");

        /* Top LEDs Left: WAKE and VOICE + Automatic Per-Channel Profile Detection on Log Hit */
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
        js.append("            rxWaitTimer = setTimeout(function() { window.AndroidApp.speakChannel(txt); }, 120);\n");
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
        js.append("        function checkTopLogRowForSpeechAndDsp() {\n");
        js.append("          var topRow = logBody.querySelector('tr.new-log-row') || logBody.querySelector('tr');\n");
        js.append("          if (!topRow || topRow.children.length < 5) return;\n");
        js.append("          var timeStr = (topRow.children[0].textContent || '').trim();\n");
        js.append("          var freqStr = (topRow.children[1].textContent || '').trim();\n");
        js.append("          var nameTd = topRow.children[4];\n");
        js.append("          var rawTitle = nameTd.getAttribute('title') || nameTd.textContent || '';\n");
        js.append("          var closeParen = rawTitle.indexOf(')');\n");
        js.append("          var chName = (closeParen !== -1 ? rawTitle.slice(closeParen + 1) : rawTitle).replace(/^[×xX]\\s*\\d+\\s*/, '').trim();\n");
        js.append("          if (!chName || chName === '---') chName = freqStr;\n");
        js.append("          if (freqStr && freqStr !== '---') {\n");
        js.append("            window.__currentActiveChannelKey = freqStr;\n");
        js.append("            window.__currentActiveChannelName = chName;\n");
        js.append("            var fVal = parseFloat(freqStr) || 0;\n");
        js.append("            window.__currentChannelMod = ((fVal >= 108.0 && fVal <= 137.0) || (fVal >= 225.0 && fVal <= 400.0) || /airport|tower|approach|radar|ctrl|guard|atis/i.test(chName)) ? 'AM' : 'NFM';\n");
        js.append("            if (window.__applyScannerDspRouting) window.__applyScannerDspRouting();\n");
        js.append("          }\n");
        js.append("          if (!window.__voiceReadoutEnabled || !chName) return;\n");
        js.append("          if (speechThrottleTimer) return;\n");
        js.append("          speechThrottleTimer = setTimeout(function() { speechThrottleTimer = null; }, 400);\n");
        js.append("          var rowKey = timeStr + '|' + freqStr + '|' + chName;\n");
        js.append("          if (rowKey !== window.__lastSpokenRowKey) {\n");
        js.append("            window.__lastSpokenRowKey = rowKey;\n");
        js.append("            speakTextNow(chName, false);\n");
        js.append("          }\n");
        js.append("        }\n");
        js.append("        new MutationObserver(checkTopLogRowForSpeechAndDsp).observe(logBody, { childList: true });\n");
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
        js.append("        var freqStr = (tr.children[1].textContent || '').trim();\n");
        js.append("        var nameTd = tr.children[4]; var badge = nameTd.querySelector('.freq-hit-badge');\n");
        js.append("        var hitsText = nameTd.getAttribute('data-hit-count') || (badge ? (badge.textContent || '').replace(/[^0-9]/g, '') : '') || '1';\n");
        js.append("        var rawTitle = nameTd.getAttribute('title') || nameTd.innerText || '';\n");
        js.append("        var closeParen = rawTitle.indexOf(')');\n");
        js.append("        var cleanName = (closeParen !== -1 ? rawTitle.slice(closeParen + 1) : rawTitle).trim();\n");
        js.append("        if (freqStr && freqStr !== '---') {\n");
        js.append("          window.__currentActiveChannelKey = freqStr;\n");
        js.append("          window.__currentActiveChannelName = cleanName;\n");
        js.append("        }\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(15);\n");
        js.append("        if (typeof showDisplayHud === 'function') showDisplayHud(cleanName + ' • HITS: ' + hitsText, 2200);\n");
        js.append("      });\n");
        js.append("    }\n");

        /* AUDIO Toggle: Always enables Native Screen-Off Keep-Alive Service & AGC whenever AUDIO is ON */
        js.append("    var oldAudioBtn = document.getElementById('btn-audio-toggle');\n");
        js.append("    if (oldAudioBtn && !oldAudioBtn.__vlcUnifiedHooked) {\n");
        js.append("      var aBtn = oldAudioBtn.cloneNode(true); aBtn.__vlcUnifiedHooked = true;\n");
        js.append("      oldAudioBtn.parentNode.replaceChild(aBtn, oldAudioBtn);\n");
        js.append("      var isVlcMode = true;\n");
        js.append("      function renderAudioLabel() {\n");
        js.append("        var cone = (isConnected && isVlcMode) ? ' <span style=\"color:#ff8c00;font-size:10px;margin-left:2px;\">&#9650;</span>' : '';\n");
        js.append("        aBtn.innerHTML = '<span class=\"inline-led\" id=\"audio-inline-led\"></span> AUDIO' + cone;\n");
        js.append("      }\n");
        js.append("      function setAudioPlaying(play, vlcBg) {\n");
        js.append("        var player = document.getElementById('audio_player');\n");
        js.append("        isVlcMode = Boolean(play && vlcBg);\n");
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
        js.append("          isConnected = false; isVlcMode = false;\n");
        js.append("          if (player && !player.paused) player.pause();\n");
        js.append("          aBtn.classList.remove('active');\n");
        js.append("          if (window.__setVoiceReadoutState) window.__setVoiceReadoutState(false, false);\n");
        js.append("          if (window.AndroidApp && window.AndroidApp.setScannerAudioConnected) window.AndroidApp.setScannerAudioConnected(false);\n");
        js.append("          if (window.AndroidApp) window.AndroidApp.setBackgroundAudioMode(false);\n");
        js.append("        }\n");
        js.append("        renderAudioLabel();\n");
        js.append("      }\n");
        js.append("      setInterval(function() {\n");
        js.append("        if (typeof isConnected !== 'undefined' && isConnected) {\n");
        js.append("          var p = document.getElementById('audio_player');\n");
        js.append("          if (p && p.paused) { try { p.play().catch(function(){}); } catch(e){} }\n");
        js.append("          if (window.audioContext && window.audioContext.state === 'suspended') { try { window.audioContext.resume(); } catch(e){} }\n");
        js.append("        }\n");
        js.append("      }, 2500);\n");
        js.append("      window.__stopScannerAudioFromBt = function() { setAudioPlaying(false, false); };\n");
        js.append("      var aTimer = null, aLong = false;\n");
        js.append("      aBtn.addEventListener('pointerdown', function() {\n");
        js.append("        clearTimeout(aTimer); aLong = false;\n");
        js.append("        aTimer = setTimeout(function() {\n");
        js.append("          aTimer = null; aLong = true; if (navigator.vibrate) navigator.vibrate(40);\n");
        js.append("          if (window.__openDspMixerModal) window.__openDspMixerModal();\n");
        js.append("        }, 500);\n");
        js.append("      });\n");
        js.append("      aBtn.addEventListener('pointerup', function() {\n");
        js.append("        var wasActive = aTimer !== null; clearTimeout(aTimer); aTimer = null;\n");
        js.append("        if (wasActive && !aLong) { if (!isConnected) setAudioPlaying(true, true); else setAudioPlaying(false, false); }\n");
        js.append("      });\n");
        js.append("      aBtn.addEventListener('pointerleave', function() { clearTimeout(aTimer); aTimer = null; });\n");
        js.append("      aBtn.addEventListener('pointercancel', function() { clearTimeout(aTimer); aTimer = null; });\n");
        js.append("    }\n");

        /* Top LEDs Right: RX, LOG, LAMP, and EXIT (1 Short Tap = System Bars, Long Press 550ms = Full CRT Switch-Off Animation + Exit App) */
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
        js.append("      exitBtn.title = 'Tap: System Bars | Hold: Switch Off & Exit App';\n");
        js.append("      exitBtn.innerHTML = '<div class=\"exit-cap\"></div><span class=\"rx-label\">EXIT</span>';\n");
        js.append("      ledsRight.appendChild(exitBtn);\n");
        js.append("      var exHoldTimer = null, exLongFired = false;\n");
        js.append("      exitBtn.addEventListener('pointerdown', function() {\n");
        js.append("        clearTimeout(exHoldTimer); exLongFired = false;\n");
        js.append("        exHoldTimer = setTimeout(function() {\n");
        js.append("          exHoldTimer = null; exLongFired = true;\n");
        js.append("          if (navigator.vibrate) navigator.vibrate([45, 60, 55]);\n");
        js.append("          if (typeof isConnected !== 'undefined' && isConnected) {\n");
        js.append("            var p = document.getElementById('audio_player'); if (p && !p.paused) p.pause();\n");
        js.append("            isConnected = false; var ab = document.getElementById('btn-audio-toggle'); if (ab) ab.classList.remove('active');\n");
        js.append("            if (window.__setVoiceReadoutState) window.__setVoiceReadoutState(false, false);\n");
        js.append("          }\n");
        js.append("          var dispEl = document.querySelector('.scanner-display');\n");
        js.append("          var logWrap = document.querySelector('.log-wrapper');\n");
        js.append("          document.documentElement.classList.add('tv-off', 'tv-shutting-down');\n");
        js.append("          if (dispEl) dispEl.classList.add('tv-shutting-down');\n");
        js.append("          if (logWrap) logWrap.classList.add('tv-shutting-down');\n");
        js.append("          setTimeout(function() { if (window.AndroidApp) window.AndroidApp.exitApp(); }, 720);\n");
        js.append("        }, 550);\n");
        js.append("      });\n");
        js.append("      exitBtn.addEventListener('pointerup', function() {\n");
        js.append("        var wasTap = exHoldTimer !== null; clearTimeout(exHoldTimer); exHoldTimer = null;\n");
        js.append("        if (wasTap && !exLongFired) {\n");
        js.append("          if (window.AndroidApp) window.AndroidApp.showSystemBars();\n");
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
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
            webView.evaluateJavascript("var dw=document.getElementById('device-wrapper');if(dw)dw.classList.remove('flipping-out');", null);
        }
    }

    @Override
    protected void onDestroy() {
        releaseNativeScannerAgc();
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
