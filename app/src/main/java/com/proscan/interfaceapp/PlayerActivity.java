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
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Visualizer;
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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
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

    private static final int REQ_LOAD_RADIO_JSON = 902;
    private static final String GDRIVE_ROOT_FOLDER_ID = "1gToQyY6YPOk_3muiQSY5WLy7jTSvXbTv";
    private WebView playerWeb;
    private SharedPreferences prefs;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest = null;
    private PowerManager.WakeLock cpuWakeLock = null;
    private WifiManager.WifiLock wifiLock = null;
    private boolean wakeLockOn = true;
    private String serverBaseUrl = "";
    private String initialStartMode = "archive";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService audioFetchExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService liveStreamTapExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextToSpeech tts = null;
    private boolean ttsReady = false;
    private String lastSpokenCaption = "";
    private long lastSpokenCaptionMs = 0L;
    private MediaPlayer nativePlayer = null;
    private Equalizer speechEqualizer = null;
    private Visualizer audioVisualizer = null;
    private volatile float liveVisualizerLevel = -1f;
    private volatile long lastVisualizerUpdateMs = 0L;
    private volatile float visAdaptiveMax = 22f;
    private volatile float liveStreamTapLevel = -1f;
    private volatile long lastStreamTapUpdateMs = 0L;
    private boolean clarityFilterEnabled = false;
    private boolean isFetchingDrive = false;
    private boolean isLiveRadioStream = false;
    private String activeLiveRadioUrl = "";
    private volatile int currentStreamReqId = 0;
    private float[] currentEnvelope = new float[0];
    private boolean bedroomLampOn = false;

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = focusChange -> {
        // Keep playing uninterrupted when screen is off unless user explicitly stops or disconnects headphones
    };

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
            actionLabel = bedroomLampOn ? "Bedroom Lights: ON" : "Bedroom Lights: OFF";
        } else {
            bedroomLampOn = false;
            targetUrl = !offUrl.isEmpty() ? offUrl : onUrl;
            actionLabel = "Bedroom Lights: Triggered";
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
                    mainHandler.post(() -> Toast.makeText(PlayerActivity.this, "Routine HTTP " + code, Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(PlayerActivity.this, "Routine failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
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
                    Toast.makeText(PlayerActivity.this, "Bedroom Light Routine Saved! Tap LAMP to trigger.", Toast.LENGTH_SHORT).show();
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

    private void showNativeAudioMixerDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        final CheckBox autoDuckCheck = new CheckBox(this);
        autoDuckCheck.setText("Auto-Duck Radio on Scanner Hit / TTS");
        autoDuckCheck.setChecked(MainActivity.PriorityRadioEngine.isAutoDuckEnabled(prefs));
        layout.addView(autoDuckCheck);

        int curCeil = prefs.getInt("mixer_radio_ceiling_pct", 40);
        final TextView ceilLbl = new TextView(this);
        ceilLbl.setText("\nRadio Baseline Ceiling: " + curCeil + "% (Default 40%)");
        layout.addView(ceilLbl);
        final SeekBar ceilBar = new SeekBar(this);
        ceilBar.setMax(90);
        ceilBar.setProgress(Math.max(0, Math.min(90, curCeil - 10)));
        ceilBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { ceilLbl.setText("\nRadio Baseline Ceiling: " + (p + 10) + "% (Default 40%)"); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        layout.addView(ceilBar);

        int curDuck = prefs.getInt("mixer_radio_duck_pct", 5);
        final TextView duckLbl = new TextView(this);
        duckLbl.setText("\nRadio Ducked Level: " + curDuck + "% (Default 5%)");
        layout.addView(duckLbl);
        final SeekBar duckBar = new SeekBar(this);
        duckBar.setMax(25);
        duckBar.setProgress(Math.max(0, Math.min(25, curDuck)));
        duckBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { duckLbl.setText("\nRadio Ducked Level: " + p + "% (Default 5%)"); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        layout.addView(duckBar);

        int curAgc = prefs.getInt("mixer_scanner_agc_db", 8);
        final TextView agcLbl = new TextView(this);
        agcLbl.setText("\nScanner AGC Boost: +" + curAgc + " dB (Default +8 dB)");
        layout.addView(agcLbl);
        final SeekBar agcBar = new SeekBar(this);
        agcBar.setMax(15);
        agcBar.setProgress(Math.max(0, Math.min(15, curAgc)));
        agcBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { agcLbl.setText("\nScanner AGC Boost: +" + p + " dB (Default +8 dB)"); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        layout.addView(agcBar);

        int curHold = prefs.getInt("mixer_duck_hold_ms", 2500);
        final TextView holdLbl = new TextView(this);
        holdLbl.setText(String.format(Locale.US, "\nDuck Hold Time: %.1fs (Default 2.5s)", curHold / 1000f));
        layout.addView(holdLbl);
        final SeekBar holdBar = new SeekBar(this);
        holdBar.setMax(40);
        holdBar.setProgress(Math.max(0, Math.min(40, (curHold - 1000) / 100)));
        holdBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                holdLbl.setText(String.format(Locale.US, "\nDuck Hold Time: %.1fs (Default 2.5s)", (1000 + p * 100) / 1000f));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        layout.addView(holdBar);

        new AlertDialog.Builder(this)
                .setTitle("Priority Audio Mixer & Auto-Duck")
                .setView(layout)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.edit()
                            .putBoolean("mixer_auto_duck_on", autoDuckCheck.isChecked())
                            .putInt("mixer_radio_ceiling_pct", ceilBar.getProgress() + 10)
                            .putInt("mixer_radio_duck_pct", duckBar.getProgress())
                            .putInt("mixer_scanner_agc_db", agcBar.getProgress())
                            .putInt("mixer_duck_hold_ms", 1000 + holdBar.getProgress() * 100)
                            .apply();
                    MainActivity.PriorityRadioEngine.updateSettingsLive(prefs);
                    Toast.makeText(PlayerActivity.this, "Audio Mixer Settings Saved!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private synchronized void setupEqualizer(int audioSessionId) {
        releaseEqualizer();
        try {
            speechEqualizer = new Equalizer(0, audioSessionId);
            speechEqualizer.setEnabled(clarityFilterEnabled);
            if (clarityFilterEnabled) {
                configureSpeechBands(speechEqualizer);
            }
        } catch (Exception ignored) {}
        setupVisualizer(audioSessionId);
    }

    private synchronized void setupVisualizer(int audioSessionId) {
        releaseVisualizer();
        try {
            visAdaptiveMax = 22f;
            audioVisualizer = new Visualizer(audioSessionId);
            int[] range = Visualizer.getCaptureSizeRange();
            int capSize = range != null && range.length > 0 ? Math.min(256, Math.max(range[0], 128)) : 128;
            audioVisualizer.setCaptureSize(capSize);
            audioVisualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                    if (waveform == null || waveform.length == 0) return;
                    double sumSq = 0;
                    int peak = 0;
                    for (byte b : waveform) {
                        int centered = (b & 0xFF) - 128;
                        int abs = Math.abs(centered);
                        if (abs > peak) peak = abs;
                        sumSq += centered * centered;
                    }
                    double rms = Math.sqrt(sumSq / waveform.length);
                    float combined = (float) ((rms * 0.65) + (peak * 0.35));
                    if (peak <= 1 && rms < 0.8) {
                        liveVisualizerLevel = 0.02f;
                        lastVisualizerUpdateMs = System.currentTimeMillis();
                        return;
                    }
                    if (combined > visAdaptiveMax) {
                        visAdaptiveMax = combined;
                    } else {
                        visAdaptiveMax = Math.max(14f, visAdaptiveMax * 0.996f);
                    }
                    float noiseFloor = visAdaptiveMax * 0.08f;
                    float norm;
                    if (combined <= noiseFloor) {
                        norm = 0.02f;
                    } else {
                        norm = (combined - noiseFloor) / Math.max(4f, (visAdaptiveMax * 0.88f) - noiseFloor);
                        norm = (float) Math.pow(Math.min(1f, Math.max(0f, norm)), 0.78);
                        norm = Math.min(0.98f, Math.max(0.03f, norm));
                    }
                    liveVisualizerLevel = norm;
                    lastVisualizerUpdateMs = System.currentTimeMillis();
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {}
            }, Visualizer.getMaxCaptureRate() / 2, true, false);
            audioVisualizer.setEnabled(true);
        } catch (Exception ignored) {
            liveVisualizerLevel = -1f;
        }
    }

    private synchronized void releaseVisualizer() {
        liveVisualizerLevel = -1f;
        lastVisualizerUpdateMs = 0L;
        if (audioVisualizer != null) {
            try { audioVisualizer.setEnabled(false); } catch (Exception ignored) {}
            try { audioVisualizer.release(); } catch (Exception ignored) {}
            audioVisualizer = null;
        }
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
                    eq.setBandLevel(i, (short) Math.max(minEq, -700));
                } else if (hz >= 1000 && hz <= 3600) {
                    eq.setBandLevel(i, (short) Math.min(maxEq, 650));
                } else if (hz > 4500) {
                    eq.setBandLevel(i, (short) Math.max(minEq, -600));
                } else {
                    eq.setBandLevel(i, (short) 0);
                }
            }
        } catch (Exception ignored) {}
    }

    private synchronized void releaseEqualizer() {
        releaseVisualizer();
        if (speechEqualizer != null) {
            try { speechEqualizer.release(); } catch (Exception ignored) {}
            speechEqualizer = null;
        }
    }

    private synchronized void acquirePlaybackLocks() {
        try {
            if (audioManager != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    if (audioFocusRequest == null) {
                        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                                .setAudioAttributes(new AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .build())
                                .setAcceptsDelayedFocusGain(true)
                                .setWillPauseWhenDucked(false)
                                .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
                                .build();
                    }
                    audioManager.requestAudioFocus(audioFocusRequest);
                } else {
                    audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                }
            }
            if (cpuWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "proscan:player_cpu_lock");
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
                    wifiLock = wm.createWifiLock(mode, "proscan:player_wifi_lock");
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
        if (MainActivity.PriorityRadioEngine.isRadioPlaying()) return;
        try {
            if (audioManager != null) {
                if (Build.VERSION.SDK_INT >= 26 && audioFocusRequest != null) {
                    audioManager.abandonAudioFocusRequest(audioFocusRequest);
                } else {
                    audioManager.abandonAudioFocus(focusChangeListener);
                }
            }
            if (cpuWakeLock != null && cpuWakeLock.isHeld()) cpuWakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {}
    }

    private void forceStopAllPlayerAudioOnDisconnect() {
        boolean wasPlaying = isLiveRadioStream || MainActivity.PriorityRadioEngine.isRadioPlaying() || (nativePlayer != null && nativePlayer.isPlaying());
        MainActivity.PriorityRadioEngine.stopStation();
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
                boolean radioRunning = isLiveRadioStream && MainActivity.PriorityRadioEngine.isRadioPlaying();
                boolean archiveRunning = !isLiveRadioStream && nativePlayer != null && nativePlayer.isPlaying();
                if (playerWeb != null && (radioRunning || archiveRunning)) {
                    int sec = 0, tot = 0;
                    float prog = 0.5f, level = 0.04f;
                    long now = System.currentTimeMillis();
                    boolean hasLiveVis = (liveVisualizerLevel >= 0f && (now - lastVisualizerUpdateMs) < 450);
                    boolean hasStreamTap = (liveStreamTapLevel >= 0f && (now - lastStreamTapUpdateMs) < 550);

                    if (radioRunning) {
                        sec = (int) ((now / 1000) % 3600);
                        tot = 3600;
                        prog = (sec % 60) / 60f;
                        if (hasLiveVis && liveVisualizerLevel > 0.025f) {
                            level = liveVisualizerLevel;
                        } else if (hasStreamTap) {
                            level = liveStreamTapLevel;
                        } else if (hasLiveVis) {
                            level = liveVisualizerLevel;
                        }
                    } else {
                        int posMs = nativePlayer.getCurrentPosition();
                        int durMs = Math.max(1, nativePlayer.getDuration());
                        sec = posMs / 1000;
                        tot = Math.max(1, durMs / 1000);
                        prog = Math.min(1f, Math.max(0f, (float) posMs / (float) durMs));
                        if (currentEnvelope.length > 0) {
                            float exactIdx = prog * (currentEnvelope.length - 1);
                            int idx0 = Math.min(currentEnvelope.length - 1, Math.max(0, (int) exactIdx));
                            int idx1 = Math.min(currentEnvelope.length - 1, idx0 + 1);
                            float frac = exactIdx - idx0;
                            level = currentEnvelope[idx0] * (1f - frac) + currentEnvelope[idx1] * frac;
                        } else if (hasLiveVis) {
                            level = liveVisualizerLevel;
                        }
                    }
                    playerWeb.evaluateJavascript("if(window.onNativeTelemetry)window.onNativeTelemetry(" + sec + "," + tot + "," + prog + "," + level + ");", null);
                    mainHandler.postDelayed(this, 50);
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

        if (Build.VERSION.SDK_INT >= 23) {
            try {
                if (checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 102);
                }
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
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) { MainActivity.PriorityRadioEngine.notifyTtsSpeaking(prefs, true); }
                        @Override public void onDone(String utteranceId) { MainActivity.PriorityRadioEngine.notifyTtsSpeaking(prefs, false); }
                        @Override public void onError(String utteranceId) { MainActivity.PriorityRadioEngine.notifyTtsSpeaking(prefs, false); }
                    });
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
            if (!showToast) return;
            JSONArray arr = new JSONArray(stationsJson);
            byte[] bytes = arr.toString(2).getBytes(StandardCharsets.UTF_8);
            boolean written = false;

            if (Build.VERSION.SDK_INT >= 29) {
                Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

                try {
                    Cursor c = getContentResolver().query(
                            collection,
                            new String[]{MediaStore.MediaColumns._ID},
                            MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                            new String[]{"proscan_radio_stations%"},
                            null
                    );
                    if (c != null) {
                        while (c.moveToNext()) {
                            long id = c.getLong(0);
                            Uri delUri = ContentUris.withAppendedId(collection, id);
                            try { getContentResolver().delete(delUri, null, null); } catch (Exception ignored) {}
                        }
                        c.close();
                    }
                } catch (Exception ignored) {}

                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, "proscan_radio_stations.json");
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri targetUri = getContentResolver().insert(collection, values);
                if (targetUri != null) {
                    OutputStream os = getContentResolver().openOutputStream(targetUri, "w");
                    if (os != null) {
                        os.write(bytes);
                        os.close();
                        written = true;
                    }
                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(targetUri, values, null, null);
                }
            }

            if (!written) {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, "proscan_radio_stations.json");
                if (file.exists()) file.delete();
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(bytes);
                fos.close();
                written = true;
            }

            if (written) {
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
                        } else if (obj.has("stations")) {
                            stationsArrayStr = obj.optString("stations", "[]");
                        }
                    }
                    JSONArray rawArr = new JSONArray(stationsArrayStr);
                    JSONArray cleanArr = new JSONArray();
                    for (int i = 0; i < rawArr.length(); i++) {
                        JSONObject item = rawArr.optJSONObject(i);
                        if (item != null) {
                            String name = item.optString("name", "").trim();
                            String url = item.optString("url", "").trim();
                            if (!name.isEmpty() && !url.isEmpty()) {
                                JSONObject cleanItem = new JSONObject();
                                cleanItem.put("name", name);
                                cleanItem.put("url", url);
                                cleanArr.put(cleanItem);
                            }
                        }
                    }
                    if (cleanArr.length() > 0) {
                        prefs.edit().putString("radio_stations_json", cleanArr.toString()).apply();
                        final String safeArr = JSONObject.quote(cleanArr.toString());
                        if (playerWeb != null) {
                            playerWeb.evaluateJavascript("if(window.onNativeStationsJsonLoaded)window.onNativeStationsJsonLoaded(" + safeArr + ");", null);
                        }
                        Toast.makeText(this, "Loaded " + cleanArr.length() + " Radio Stations from JSON!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "No valid stations found in JSON", Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (Exception e) {
                Toast.makeText(this, "Invalid Radio Stations JSON file", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showStationMenuDialog(final int curIdx, final String curName, final String curUrl) {
        String[] items = new String[]{
                "+ Add New Station",
                "Edit Selected (" + (curName != null && !curName.isEmpty() ? curName : "None") + ")",
                "Priority Audio Mixer & Auto-Duck Settings",
                "Save Stations JSON to Downloads (proscan_radio_stations.json)",
                "Load Stations JSON from Downloads"
        };
        new AlertDialog.Builder(this)
                .setTitle("Radio Station & Mixer Manager")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        openAddOrEditStationDialog(-1, "", "");
                    } else if (which == 1) {
                        if (curIdx >= 0 && curName != null && !curName.isEmpty()) {
                            openAddOrEditStationDialog(curIdx, curName, curUrl);
                        } else {
                            openAddOrEditStationDialog(-1, "", "");
                        }
                    } else if (which == 2) {
                        showNativeAudioMixerDialog();
                    } else if (which == 3) {
                        if (playerWeb != null) {
                            playerWeb.evaluateJavascript("if(window.PlayerBridge)window.PlayerBridge.saveStationsJson(JSON.stringify(window.__radioStations), true);", null);
                        }
                    } else if (which == 4) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        startActivityForResult(intent, REQ_LOAD_RADIO_JSON);
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
        String activeBgRadioUrl = JSONObject.quote(MainActivity.PriorityRadioEngine.getActiveStationUrl());
        boolean isBgRadioPlaying = MainActivity.PriorityRadioEngine.isRadioPlaying();

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
        js.append("      }\n");
        js.append("    }\n");
        js.append("    var nativeSt = ").append(safeStations).append(";\n");
        js.append("    if (nativeSt && nativeSt.length > 2) localStorage.setItem('proscan_radio_stations_v3', nativeSt);\n");
        js.append("  } catch(e) {}\n");

        js.append("  var nameHdr = document.querySelector('.screen-log-header span:last-child');\n");
        js.append("  if (nameHdr) nameHdr.innerText = 'NAME';\n");

        js.append("  var st = document.createElement('style');\n");
        js.append("  st.textContent = '' +\n");
        js.append("    '@keyframes smoothFadeIn{0%{opacity:0;}100%{opacity:1;}}' +\n");
        js.append("    '@keyframes crtWhiteBeamCollapse{0%{opacity:0;transform:translate(-50%,-50%) scale(1,35);border-radius:0;}16%{opacity:1;transform:translate(-50%,-50%) scale(1,1.4);border-radius:2px;}46%{opacity:1;transform:translate(-50%,-50%) scale(0.92,0.65);border-radius:999px;}76%{opacity:1;transform:translate(-50%,-50%) scale(0.014,1.35);border-radius:50%;}92%{opacity:0.9;transform:translate(-50%,-50%) scale(0.006,0.6);border-radius:50%;}100%{opacity:0;transform:translate(-50%,-50%) scale(0,0);border-radius:50%;}}' +\n");
        js.append("    'html.tv-shutting-down .scanner-display{background:#000!important;box-shadow:inset 0 0 24px #000!important;}' +\n");
        js.append("    'html.tv-shutting-down .scanner-display > *{opacity:0!important;transition:opacity 0.15s ease!important;}' +\n");
        js.append("    'html.tv-shutting-down .scanner-display::after{content:\"\"!important;position:absolute!important;left:50%!important;top:50%!important;width:96%!important;height:3px!important;background:#fff!important;box-shadow:0 0 8px #fff,0 0 18px rgba(255,255,255,0.95),0 0 32px rgba(200,230,255,0.75)!important;z-index:99!important;pointer-events:none!important;animation:crtWhiteBeamCollapse 0.48s cubic-bezier(0.4,0,0.2,1) forwards!important;}' +\n");
        js.append("    '#device-wrapper{width:95%!important;max-height:94dvh!important;animation:smoothFadeIn 0.25s ease-out forwards;transition:opacity 0.2s ease!important;}' +\n");
        js.append("    '#device-wrapper.fading-out{opacity:0!important;}' +\n");
        js.append("    '#custom-scanner-ui{padding:16px 16px!important;gap:8px!important;}' +\n");
        js.append("    '.scanner-display{height:345px!important;transition:height 0.22s ease!important;}.top-controls-container{height:34px!important;}.buttons-box{gap:8px!important;padding:9px!important;}#mech-bay{height:216px!important;}' +\n");
        js.append("    'html[data-deck=\"lite\"] #mech-bay{display:none!important;}' +\n");
        js.append("    'html[data-deck=\"lite\"] .scanner-display{height:565px!important;}' +\n");

        /* MOBILE LANDSCAPE SPLIT-SCREEN COCKPIT LAYOUT */
        js.append("    '@media (orientation: landscape) and (max-height: 540px){' +\n");
        js.append("      '#device-wrapper{width:96vw!important;max-width:96vw!important;height:94dvh!important;max-height:94dvh!important;}' +\n");
        js.append("      '#custom-scanner-ui{display:grid!important;grid-template-columns:1.08fr 1fr!important;grid-template-rows:auto 1fr!important;gap:5px 12px!important;padding:10px 14px!important;height:100%!important;overflow:hidden!important;}' +\n");
        js.append("      '.scanner-display, html[data-deck=\"lite\"] .scanner-display{grid-column:1!important;grid-row:1 / span 2!important;height:100%!important;min-height:0!important;}' +\n");
        js.append("      '.top-controls-container{grid-column:2!important;grid-row:1!important;height:30px!important;}' +\n");
        js.append("      '.buttons-box{grid-column:2!important;grid-row:2!important;height:100%!important;min-height:0!important;padding:6px!important;gap:5px!important;justify-content:space-between!important;}' +\n");
        js.append("      '.player-nav-row{height:27px!important;gap:4px!important;}' +\n");
        js.append("      '.player-nav-row .btn{font-size:9.5px!important;}' +\n");
        js.append("      '#mech-bay{flex:1!important;height:auto!important;min-height:110px!important;}' +\n");
        js.append("      '.btn-row-4{height:32px!important;gap:6px!important;}' +\n");
        js.append("      '#btn-play, #btn-stop{font-size:12px!important;}' +\n");
        js.append("      '.filter-row-archive, .radio-crud-bar{height:27px!important;gap:5px!important;}' +\n");
        js.append("    '}' +\n");

        js.append("    '#playlist-box{overflow-y:scroll!important;-webkit-overflow-scrolling:touch!important;touch-action:pan-y!important;overscroll-behavior:contain!important;}' +\n");
        js.append("    '.rec-row{padding:4.5px 4px!important;font-size:10px!important;touch-action:pan-y!important;}' +\n");
        js.append("    '.rec-row.playing, .rec-row.playing.selected{background:var(--log-text)!important;color:var(--display-bg)!important;font-weight:800!important;border-radius:3px!important;outline:none!important;}' +\n");
        js.append("    '.rec-row.selected:not(.playing){outline:1px dashed var(--log-text,#38bdf8)!important;background:rgba(0,0,0,0.16)!important;color:var(--log-text)!important;font-weight:800!important;}' +\n");

        /* Large Station Tiles for Radio Mode */
        js.append("    '.radio-grid{display:grid!important;grid-template-columns:repeat(2,minmax(0,1fr))!important;gap:8px!important;padding:8px 6px!important;width:100%!important;}' +\n");
        js.append("    '.radio-tile{min-height:66px!important;padding:8px 10px!important;border:1.5px solid var(--log-cell-border)!important;border-radius:8px!important;background:rgba(0,0,0,0.14)!important;font-family:\"JetBrains Mono\",monospace!important;font-size:12px!important;font-weight:800!important;color:var(--log-text)!important;display:flex!important;align-items:center!important;justify-content:center!important;text-align:center!important;cursor:pointer!important;touch-action:pan-y!important;word-break:break-word!important;line-height:1.25!important;box-shadow:inset 0 1px 3px rgba(0,0,0,0.35)!important;transition:transform 0.1s ease,background 0.15s ease!important;}' +\n");
        js.append("    '.radio-tile:active{transform:scale(0.97)!important;}' +\n");
        js.append("    '.radio-tile.playing, .radio-tile.playing.selected{background:var(--log-text)!important;color:var(--display-bg)!important;font-weight:900!important;border-color:var(--log-text)!important;box-shadow:0 0 10px rgba(0,0,0,0.5)!important;outline:none!important;}' +\n");
        js.append("    '.radio-tile.selected:not(.playing){outline:2px dashed var(--log-text,#38bdf8)!important;outline-offset:-2px!important;background:rgba(0,0,0,0.28)!important;color:var(--log-text)!important;font-weight:900!important;}' +\n");

        /* Top Screen Header */
        js.append("    '.disp-top-row{display:grid!important;grid-template-columns:auto 1fr auto!important;align-items:center!important;gap:6px!important;width:100%!important;}' +\n");
        js.append("    '#top-header-center{display:inline-flex!important;align-items:center!important;justify-content:center!important;gap:6px!important;min-width:0!important;overflow:hidden!important;}' +\n");
        js.append("    '#deck-mode-hud{white-space:nowrap!important;overflow:hidden!important;text-overflow:ellipsis!important;font-size:9.5px!important;}' +\n");
        js.append("    '#rec-count-hud{font-family:\"JetBrains Mono\",monospace!important;font-size:9px!important;font-weight:900!important;color:var(--log-header-text)!important;padding:0 5px!important;border:1px solid var(--log-cell-border)!important;border-radius:4px!important;white-space:nowrap!important;flex-shrink:0!important;}' +\n");

        js.append("    '.player-nav-row{display:grid!important;grid-template-columns:repeat(5,minmax(0,1fr))!important;gap:6px!important;height:35px!important;}' +\n");
        js.append("    '.player-nav-row.radio-mode-nav{grid-template-columns:repeat(3,minmax(0,1fr))!important;gap:8px!important;}' +\n");
        js.append("    '.player-nav-row .btn{font-size:11px!important;font-weight:900!important;padding:2px 3px!important;letter-spacing:0.2px!important;}' +\n");

        js.append("    '.btn-row-4{display:grid!important;grid-template-columns:1fr 1fr!important;gap:8px!important;height:44px!important;}' +\n");
        js.append("    '#btn-play, #btn-stop{font-size:14px!important;font-weight:900!important;letter-spacing:0.9px!important;}' +\n");
        js.append("    '#btn-prev, #btn-next{display:none!important;}' +\n");

        js.append("    '.filter-row-archive{display:grid!important;grid-template-columns:1fr 84px!important;gap:6px!important;height:34px!important;}' +\n");
        js.append("    '#search-box{width:100%!important;height:100%!important;font-size:11px!important;}' +\n");
        js.append("    '#btn-filter-unread{font-size:10.5px!important;font-weight:900!important;padding:2px 4px!important;letter-spacing:0.3px!important;}' +\n");

        js.append("    '.radio-crud-bar{display:none;grid-template-columns:2fr 1fr 1fr 1fr!important;gap:6px!important;height:34px!important;}' +\n");
        js.append("    '.radio-crud-bar .btn{font-size:10.5px!important;font-weight:900!important;padding:2px!important;}' +\n");

        js.append("    '#date-select, #btn-refresh, #btn-open-gdrive, #btn-mode-tts, #cont-status-hud, #btn-cc-toggle{display:none!important;}' +\n");

        /* Signal Meter */
        js.append("    '#sig-meter-host{display:inline-flex;align-items:center;justify-content:center;width:124px;height:23px;background:rgba(6,8,12,0.82);border:1px solid rgba(255,255,255,0.16);border-radius:5px;padding:2px 5px;box-shadow:inset 0 2px 4px rgba(0,0,0,0.9),0 1px 0 rgba(255,255,255,0.1);position:relative;overflow:hidden;pointer-events:auto;cursor:pointer;}' +\n");
        js.append("    '.sm-mode{display:none;width:100%;height:100%;align-items:center;justify-content:space-between;}' +\n");
        js.append("    '.sm-mode.active{display:flex;}' +\n");
        js.append("    '.sm-lbl{font-family:\"JetBrains Mono\",monospace;font-size:7.5px;font-weight:900;color:#38bdf8;letter-spacing:0.3px;margin-right:3px;flex-shrink:0;}' +\n");
        js.append("    '.sm-bar-wrap{display:flex;align-items:center;gap:2px;flex:1;height:11px;justify-content:space-between;}' +\n");
        js.append("    '.sm-seg{flex:1;height:100%;border-radius:1px;background:#1e293b;transition:background 0.04s linear;}' +\n");
        js.append("    '.sm-vu-wrap{width:100%;height:100%;background:linear-gradient(90deg,#fef9c3 0%,#fde047 68%,#f59e0b 100%);border-radius:3px;position:relative;overflow:hidden;border:1px solid #000;box-shadow:inset 0 1px 3px rgba(0,0,0,0.45);}' +\n");
        js.append("    '.sm-vu-wrap::after{content:\"\";position:absolute;left:4px;right:4px;bottom:1px;height:5px;border-bottom:1.5px solid #0f172a;background:repeating-linear-gradient(90deg,#0f172a 0px,#0f172a 2px,transparent 2px,transparent 8px);opacity:0.9;z-index:1;pointer-events:none;}' +\n");
        js.append("    '.sm-vu-scale{position:absolute;top:1px;left:4px;right:4px;font-family:monospace;font-size:6.5px;font-weight:900;color:#0f172a;display:flex;justify-content:space-between;line-height:1;z-index:2;pointer-events:none;}' +\n");
        js.append("    '#sm-vu-needle{position:absolute;top:0;bottom:0;left:3px;width:2.5px;background:#dc2626;border-radius:1px;box-shadow:0 0 4px rgba(220,38,38,0.9),1px 0 2px rgba(0,0,0,0.6);transition:left 0.14s ease-out;z-index:3;}' +\n");
        js.append("    '.sm-spec-wrap{display:flex;align-items:flex-end;justify-content:space-between;gap:2px;width:100%;height:15px;padding-top:1px;}' +\n");
        js.append("    '.sm-spec-bar{flex:1;height:2px;background:linear-gradient(180deg,#ef4444 0%,#facc15 35%,#22c55e 100%);border-radius:1px 1px 0 0;transition:height 0.04s linear;}' +\n");
        js.append("    '#sm-scope-canvas{width:100%;height:16px;display:block;}' +\n");
        js.append("    '.sm-dig-wrap{display:flex;align-items:center;justify-content:space-between;width:100%;gap:4px;font-family:\"JetBrains Mono\",monospace;}' +\n");
        js.append("    '#sm-dig-txt{font-size:8.5px;font-weight:900;color:#39ff14;min-width:46px;text-align:left;letter-spacing:-0.3px;}' +\n");
        js.append("    '.sm-peak-track{flex:1;height:8px;background:#0f172a;border:1px solid #334155;border-radius:4px;overflow:hidden;position:relative;}' +\n");
        js.append("    '#sm-peak-fill{height:100%;width:0%;background:linear-gradient(90deg,#22c55e 0%,#facc15 65%,#ef4444 100%);transition:width 0.14s ease-out;}' +\n");

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
        js.append("    '.hw-vu-needle{position:absolute;bottom:-2px;left:25px;width:1.8px;height:25px;background:#dc2626;transform-origin:bottom center;transform:rotate(-35deg);transition:transform 0.05s ease-out;box-shadow:0 0 2px rgba(0,0,0,0.5);}' +\n");
        js.append("    '#cd-laser-sled{position:absolute;width:12px;height:12px;border-radius:50%;background:radial-gradient(circle,#ff2222 20%,rgba(255,0,0,0.7) 55%,transparent 100%);box-shadow:0 0 10px #ff0000;z-index:2;pointer-events:none;transition:transform 0.1s linear;}' +\n");
        js.append("    '#reactive-vu-strip{position:absolute;bottom:5px;left:50%;transform:translateX(-50%);display:flex;gap:3px;z-index:12;background:rgba(0,0,0,0.78);padding:3px 7px;border-radius:4px;border:1px solid rgba(255,255,255,0.14);}' +\n");
        js.append("    '.rvu-seg{width:6px;height:5px;border-radius:1px;background:#1e293b;}' +\n");
        js.append("    '#player-wake-pill{display:inline-flex;align-items:center;gap:3px;cursor:pointer;padding:1px 6px;border-radius:999px;background:rgba(0,0,0,0.45);border:1px solid rgba(255,255,255,0.14);margin-right:6px;touch-action:manipulation;}' +\n");
        js.append("    '#player-wake-dot{width:7px;height:7px;border-radius:50%;background:#334155;transition:all 0.25s ease;}' +\n");
        js.append("    '#player-wake-pill.active #player-wake-dot{background:#10b981;box-shadow:0 0 6px #10b981;}' +\n");

        /* LAMP & EXIT Cap Buttons */
        js.append("    '#btn-player-lamp{display:inline-flex;flex-direction:column;align-items:center;justify-content:center;gap:1px;background:transparent;border:none;padding:0;margin-right:8px;cursor:pointer;touch-action:manipulation;}' +\n");
        js.append("    '#btn-player-lamp .lamp-cap{width:22px;height:16px;border-radius:5px;background:linear-gradient(180deg,#eab308,#a16207);border:1px solid #422006;box-shadow:0 2px 3px rgba(0,0,0,0.8),inset 0 1px 0 rgba(255,255,255,0.4);display:flex;align-items:center;justify-content:center;}' +\n");
        js.append("    '#btn-player-lamp .lamp-cap::after{content:\"\";width:8px;height:3px;border-radius:1.5px;background:rgba(254,249,195,0.92);box-shadow:0 0 4px rgba(254,249,195,0.5);}' +\n");
        js.append("    '#btn-player-lamp:active .lamp-cap{transform:translateY(1px);filter:brightness(1.25);}' +\n");
        js.append("    '#btn-player-exit{display:inline-flex;flex-direction:column;align-items:center;justify-content:center;gap:1px;background:transparent;border:none;padding:0;margin-left:2px;cursor:pointer;touch-action:manipulation;}' +\n");
        js.append("    '#btn-player-exit .exit-cap{width:22px;height:16px;border-radius:5px;background:linear-gradient(180deg,#b91c1c,#7f1d1d);border:1px solid #450a0a;box-shadow:0 2px 3px rgba(0,0,0,0.8),inset 0 1px 0 rgba(255,255,255,0.32);display:flex;align-items:center;justify-content:center;}' +\n");
        js.append("    '#btn-player-exit .exit-cap::after{content:\"\";width:8px;height:3px;border-radius:1.5px;background:rgba(255,220,220,0.88);}' +\n");
        js.append("    '#btn-player-exit:active .exit-cap{transform:translateY(1px);filter:brightness(1.2);}' +\n");
        js.append("    '#player-bat-hud{display:inline-flex;align-items:center;gap:3px;font-family:\"JetBrains Mono\",monospace;font-size:10px;font-weight:800;color:var(--log-text);}' +\n");
        js.append("    '.bat-icon-box{display:inline-block;width:15px;height:8px;border:1.4px solid currentColor;border-radius:2px;padding:1px;position:relative;vertical-align:middle;margin-right:3px;}' +\n");
        js.append("    '.bat-icon-box::after{content:\"\";position:absolute;right:-3px;top:1.5px;width:1.5px;height:3px;background:currentColor;border-radius:0 1px 1px 0;}' +\n");
        js.append("    '.bat-icon-fill{display:block;height:100%;background:currentColor;width:80%;transition:width 0.3s ease;}';\n");
        js.append("  document.head.appendChild(st);\n");

        /* Add 6th Deck Option: DECK: LITE */
        js.append("  if (typeof decks !== 'undefined' && !decks.some(function(d){ return d.id === 'lite'; })) {\n");
        js.append("    decks.push({ id: 'lite', btn: 'DECK: LITE', hud: 'ARCHIVE // LITE LOG VIEW' });\n");
        js.append("  }\n");
        js.append("  var savedDeckIdx = parseInt(localStorage.getItem('proscan_deck_idx') || '0', 10) || 0;\n");
        js.append("  if (typeof applyDeck === 'function') applyDeck(savedDeckIdx);\n");

        /* Long-press on DECK button toggles straight to/from DECK: LITE */
        js.append("  var cycDeckBtn = document.getElementById('btn-cycle-deck');\n");
        js.append("  if (cycDeckBtn) {\n");
        js.append("    var newCycBtn = cycDeckBtn.cloneNode(true);\n");
        js.append("    cycDeckBtn.parentNode.replaceChild(newCycBtn, cycDeckBtn);\n");
        js.append("    var dHoldTimer = null, dLongFired = false;\n");
        js.append("    newCycBtn.addEventListener('pointerdown', function() {\n");
        js.append("      clearTimeout(dHoldTimer); dLongFired = false;\n");
        js.append("      dHoldTimer = setTimeout(function() {\n");
        js.append("        dHoldTimer = null; dLongFired = true;\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(35);\n");
        js.append("        var liteIdx = decks.findIndex(function(d){ return d.id === 'lite'; });\n");
        js.append("        if (liteIdx !== -1) {\n");
        js.append("          if (deckIdx === liteIdx) {\n");
        js.append("            applyDeck(window.__prevFullDeckIdx || 0);\n");
        js.append("          } else {\n");
        js.append("            window.__prevFullDeckIdx = deckIdx;\n");
        js.append("            applyDeck(liteIdx);\n");
        js.append("          }\n");
        js.append("        }\n");
        js.append("      }, 480);\n");
        js.append("    });\n");
        js.append("    newCycBtn.addEventListener('pointerup', function() {\n");
        js.append("      var wasTap = dHoldTimer !== null; clearTimeout(dHoldTimer); dHoldTimer = null;\n");
        js.append("      if (wasTap && !dLongFired) {\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(15);\n");
        js.append("        applyDeck(deckIdx + 1);\n");
        js.append("      }\n");
        js.append("    });\n");
        js.append("    newCycBtn.addEventListener('pointerleave', function() { clearTimeout(dHoldTimer); dHoldTimer = null; });\n");
        js.append("    newCycBtn.addEventListener('pointercancel', function() { clearTimeout(dHoldTimer); dHoldTimer = null; });\n");
        js.append("  }\n");

        /* Top Screen Header: Clock on Left, Mode Title + Counter in Center, Battery on Right */
        js.append("  var pClk = document.getElementById('player-clock-hud');\n");
        js.append("  var recHud = document.getElementById('rec-count-hud');\n");
        js.append("  var modeHudEl = document.getElementById('deck-mode-hud');\n");
        js.append("  if (pClk && !document.getElementById('player-bat-hud')) {\n");
        js.append("    var topBar = pClk.parentNode;\n");
        js.append("    var pBat = document.createElement('span');\n");
        js.append("    pBat.id = 'player-bat-hud';\n");
        js.append("    pBat.innerHTML = '<span class=\"bat-icon-box\"><span class=\"bat-icon-fill\" id=\"player-bat-fill\"></span></span><span id=\"player-bat-txt\">--%</span>';\n");
        js.append("    topBar.insertBefore(pClk, topBar.firstChild);\n");
        js.append("    var centerWrap = document.createElement('div');\n");
        js.append("    centerWrap.id = 'top-header-center';\n");
        js.append("    if (modeHudEl) centerWrap.appendChild(modeHudEl);\n");
        js.append("    topBar.appendChild(centerWrap);\n");
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

        /* Middle Status Bar Left: WAKE pill */
        js.append("  if (recHud && !document.getElementById('player-wake-pill')) {\n");
        js.append("    var wWrap = document.createElement('div');\n");
        js.append("    wWrap.style.cssText = 'display:flex;align-items:center;padding-left:2px;';\n");
        js.append("    var wPill = document.createElement('button');\n");
        js.append("    wPill.id = 'player-wake-pill'; wPill.type = 'button';\n");
        js.append("    wPill.className = ").append(wakePref ? "'active'" : "''").append(";\n");
        js.append("    wPill.innerHTML = '<span id=\"player-wake-dot\"></span><span style=\"font-size:7px;font-weight:900;color:var(--toggle-color);\">WAKE</span>';\n");

        js.append("    recHud.parentNode.insertBefore(wWrap, recHud);\n");
        js.append("    wWrap.appendChild(wPill);\n");
        js.append("    var hdrCenter = document.getElementById('top-header-center');\n");
        js.append("    if (hdrCenter) hdrCenter.appendChild(recHud);\n");
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

        /* Middle Status Bar Right: LAMP + EXIT Cap Buttons (EXIT: 1 Short Tap = System Bars, Long Press 550ms = Full CRT Switch-Off Animation + Exit App) */
        js.append("  var contHud = document.getElementById('cont-status-hud');\n");
        js.append("  if (contHud && !document.getElementById('btn-player-exit')) {\n");
        js.append("    var rWrap = document.createElement('div');\n");
        js.append("    rWrap.style.cssText = 'justify-self:end;display:flex;align-items:center;padding-right:2px;';\n");
        js.append("    contHud.parentNode.insertBefore(rWrap, contHud);\n");
        js.append("    rWrap.appendChild(contHud);\n");

        js.append("    var lampBtn = document.createElement('button');\n");
        js.append("    lampBtn.id = 'btn-player-lamp'; lampBtn.type = 'button';\n");
        js.append("    lampBtn.title = 'Tap: Bedroom Lights | Hold: Setup Alexa Routine URL';\n");
        js.append("    lampBtn.innerHTML = '<div class=\"lamp-cap\"></div><span style=\"font-size:6.5px;font-weight:900;color:var(--toggle-color);letter-spacing:0.5px;\">LAMP</span>';\n");
        js.append("    rWrap.appendChild(lampBtn);\n");
        js.append("    window.__playerLampState = false;\n");
        js.append("    var lpTimer = null, lpLong = false;\n");
        js.append("    lampBtn.addEventListener('pointerdown', function() {\n");
        js.append("      clearTimeout(lpTimer); lpLong = false;\n");
        js.append("      lpTimer = setTimeout(function() {\n");
        js.append("        lpTimer = null; lpLong = true;\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(40);\n");
        js.append("        if (window.PlayerBridge && window.PlayerBridge.openLampSetup) window.PlayerBridge.openLampSetup();\n");
        js.append("      }, 500);\n");
        js.append("    });\n");
        js.append("    lampBtn.addEventListener('pointerup', function() {\n");
        js.append("      var wasTap = lpTimer !== null; clearTimeout(lpTimer); lpTimer = null;\n");
        js.append("      if (wasTap && !lpLong) {\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(25);\n");
        js.append("        window.__playerLampState = !window.__playerLampState;\n");
        js.append("        if (window.PlayerBridge && window.PlayerBridge.triggerBedroomLamp) {\n");
        js.append("          window.PlayerBridge.triggerBedroomLamp(window.__playerLampState);\n");
        js.append("        }\n");
        js.append("      }\n");
        js.append("    });\n");
        js.append("    lampBtn.addEventListener('pointerleave', function() { clearTimeout(lpTimer); lpTimer = null; });\n");
        js.append("    lampBtn.addEventListener('pointercancel', function() { clearTimeout(lpTimer); lpTimer = null; });\n");

        js.append("    var exBtn = document.createElement('button');\n");
        js.append("    exBtn.id = 'btn-player-exit'; exBtn.type = 'button';\n");
        js.append("    exBtn.title = 'Tap: System Bars | Hold: Switch Off & Exit App';\n");
        js.append("    exBtn.innerHTML = '<div class=\"exit-cap\"></div><span style=\"font-size:6.5px;font-weight:900;color:var(--toggle-color);letter-spacing:0.5px;\">EXIT</span>';\n");
        js.append("    rWrap.appendChild(exBtn);\n");
        js.append("    var exHoldTimer = null, exLongFired = false;\n");
        js.append("    exBtn.addEventListener('pointerdown', function() {\n");
        js.append("      clearTimeout(exHoldTimer); exLongFired = false;\n");
        js.append("      exHoldTimer = setTimeout(function() {\n");
        js.append("        exHoldTimer = null; exLongFired = true;\n");
        js.append("        if (navigator.vibrate) navigator.vibrate([45, 60, 55]);\n");
        js.append("        if (window.__stopAllRadioAndNativeAudio) window.__stopAllRadioAndNativeAudio();\n");
        js.append("        document.documentElement.classList.add('tv-shutting-down');\n");
        js.append("        setTimeout(function() { if (window.PlayerBridge && window.PlayerBridge.exitEntireApp) window.PlayerBridge.exitEntireApp(); }, 720);\n");
        js.append("      }, 550);\n");
        js.append("    });\n");
        js.append("    exBtn.addEventListener('pointerup', function() {\n");
        js.append("      var wasTap = exHoldTimer !== null; clearTimeout(exHoldTimer); exHoldTimer = null;\n");
        js.append("      if (wasTap && !exLongFired) {\n");
        js.append("        if (window.PlayerBridge && window.PlayerBridge.showSystemBars) window.PlayerBridge.showSystemBars();\n");
        js.append("      }\n");
        js.append("    });\n");
        js.append("    exBtn.addEventListener('pointerleave', function() { clearTimeout(exHoldTimer); exHoldTimer = null; });\n");
        js.append("    exBtn.addEventListener('pointercancel', function() { clearTimeout(exHoldTimer); exHoldTimer = null; });\n");
        js.append("  }\n");
        /* Stop all native audio AND immediately stop deck animations & reset VU meters */
        js.append("  window.__stopAllRadioAndNativeAudio = function() {\n");
        js.append("    window.__streamLaunchToken = (window.__streamLaunchToken || 0) + 1;\n");
        js.append("    window.__audioLevel = 0;\n");
        js.append("    if (typeof setPlayingUI === 'function') setPlayingUI(false);\n");
        js.append("    var arm = document.querySelector('.tt-tonearm');\n");
        js.append("    if (arm) arm.style.transform = 'rotate(6deg)';\n");
        js.append("    ['cd-vu-l','r2r-vu-l','r2r-vu-r'].forEach(function(id){\n");
        js.append("      var ndl = document.getElementById(id);\n");
        js.append("      if (ndl) ndl.style.transform = 'rotate(-35deg)';\n");
        js.append("    });\n");
        js.append("    document.querySelectorAll('#reactive-vu-strip .rvu-seg').forEach(function(seg) {\n");
        js.append("      seg.style.background = '#1e293b'; seg.style.boxShadow = 'none';\n");
        js.append("    });\n");
        js.append("    if (window.PlayerBridge && window.PlayerBridge.stopAudio) {\n");
        js.append("      window.PlayerBridge.stopAudio();\n");
        js.append("    }\n");
        js.append("  };\n");

        /* Build Clean 5-Button Top Row: [ SCANNER ] [ RADIO ] [ DECK ] [ CONT ] [ DSP ] */
        js.append("  var origBackBtn = document.getElementById('btn-back-radio');\n");
        js.append("  var origTopRow = origBackBtn ? origBackBtn.parentNode : null;\n");
        js.append("  if (origTopRow && !document.getElementById('btn-switch-radio-archive')) {\n");
        js.append("    origTopRow.className = 'player-nav-row';\n");
        js.append("    var newScannerBtn = origBackBtn.cloneNode(true);\n");
        js.append("    newScannerBtn.innerText = 'SCANNER';\n");
        js.append("    origTopRow.replaceChild(newScannerBtn, origBackBtn);\n");
        js.append("    newScannerBtn.addEventListener('click', function() {\n");
        js.append("      if (navigator.vibrate) navigator.vibrate(20);\n");
        js.append("      // If in Archive mode, stop archive playback; if in Broadcast Radio mode, keep Radio playing for Priority Auto-Ducking!\n");
        js.append("      if (!window.__isBroadcastRadioMode) {\n");
        js.append("        window.__stopAllRadioAndNativeAudio();\n");
        js.append("      }\n");
        js.append("      if (window.markCurrentListAsVisited) window.markCurrentListAsVisited();\n");
        js.append("      var dw = document.getElementById('device-wrapper');\n");
        js.append("      if (dw) dw.classList.add('fading-out');\n");
        js.append("      setTimeout(function() { if (window.PlayerBridge) window.PlayerBridge.closePlayerKeepRadio(); }, 180);\n");
        js.append("    });\n");

        js.append("    var modeSwitchBtn = document.createElement('button');\n");
        js.append("    modeSwitchBtn.id = 'btn-switch-radio-archive';\n");
        js.append("    modeSwitchBtn.className = 'btn';\n");
        js.append("    modeSwitchBtn.type = 'button';\n");
        js.append("    modeSwitchBtn.innerText = 'RADIO';\n");
        js.append("    origTopRow.insertBefore(modeSwitchBtn, newScannerBtn.nextSibling);\n");
        js.append("    modeSwitchBtn.addEventListener('click', function() {\n");
        js.append("      if (navigator.vibrate) navigator.vibrate(20);\n");
        js.append("      window.setAppMode(!window.__isBroadcastRadioMode);\n");
        js.append("    });\n");

        /* Continuous / Loop Mode Button */
        js.append("    var oldContBtn = document.getElementById('btn-mode-cont');\n");
        js.append("    if (oldContBtn) {\n");
        js.append("      var cBtn = oldContBtn.cloneNode(true);\n");
        js.append("      cBtn.id = 'btn-mode-cont';\n");
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
        js.append("      });\n");
        js.append("    }\n");

        /* DSP Audio Clarity Button (Tap = Toggle DSP, Hold = Open Priority Audio Mixer Dialog) */
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
        js.append("    var pDspTimer = null, pDspLong = false;\n");
        js.append("    dspBtn.addEventListener('pointerdown', function() {\n");
        js.append("      clearTimeout(pDspTimer); pDspLong = false;\n");
        js.append("      pDspTimer = setTimeout(function() {\n");
        js.append("        pDspTimer = null; pDspLong = true;\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(35);\n");
        js.append("        if (window.PlayerBridge && window.PlayerBridge.openMixerDialog) window.PlayerBridge.openMixerDialog();\n");
        js.append("      }, 480);\n");
        js.append("    });\n");
        js.append("    dspBtn.addEventListener('pointerup', function() {\n");
        js.append("      var wasTap = pDspTimer !== null; clearTimeout(pDspTimer); pDspTimer = null;\n");
        js.append("      if (wasTap && !pDspLong) {\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(25);\n");
        js.append("        if (window.PlayerBridge && window.PlayerBridge.toggleClarityFilter) {\n");
        js.append("          var state = window.PlayerBridge.toggleClarityFilter();\n");
        js.append("          syncDspLabel(state);\n");
        js.append("        }\n");
        js.append("      }\n");
        js.append("    });\n");
        js.append("    origTopRow.appendChild(dspBtn);\n");

        js.append("    var oldTtsBtn = document.getElementById('btn-mode-tts');\n");
        js.append("    if (oldTtsBtn) oldTtsBtn.remove();\n");
        js.append("  }\n");

        /* Archive Filter Row & Radio Management Bar */
        js.append("  window.__archiveFilterMode = 0;\n");
        js.append("  var fRow = document.getElementById('date-select').parentNode;\n");
        js.append("  if (fRow && !document.getElementById('radio-crud-row')) {\n");
        js.append("    fRow.className = 'filter-row-archive';\n");
        js.append("    var sBoxEl = document.getElementById('search-box');\n");
        js.append("    var fltBtn = document.getElementById('btn-filter-unread');\n");
        js.append("    if (!fltBtn) {\n");
        js.append("      fltBtn = document.createElement('button');\n");
        js.append("      fltBtn.id = 'btn-filter-unread';\n");
        js.append("      fltBtn.className = 'btn';\n");
        js.append("      fltBtn.type = 'button';\n");
        js.append("      fltBtn.innerText = 'ALL';\n");
        js.append("      if (sBoxEl && sBoxEl.nextSibling) {\n");
        js.append("        fRow.insertBefore(fltBtn, sBoxEl.nextSibling);\n");
        js.append("      } else {\n");
        js.append("        fRow.appendChild(fltBtn);\n");
        js.append("      }\n");
        js.append("    }\n");
        js.append("    fltBtn.addEventListener('click', function() {\n");
        js.append("      if (navigator.vibrate) navigator.vibrate(20);\n");
        js.append("      window.__archiveFilterMode = (window.__archiveFilterMode + 1) % 3;\n");
        js.append("      var labels = ['ALL', 'NEW', 'UNPLAY'];\n");
        js.append("      fltBtn.innerText = labels[window.__archiveFilterMode];\n");
        js.append("      fltBtn.classList.toggle('active', window.__archiveFilterMode !== 0);\n");
        js.append("      window.renderPlaylist();\n");
        js.append("    });\n");

        js.append("    var rBar = document.createElement('div');\n");
        js.append("    rBar.id = 'radio-crud-row'; rBar.className = 'radio-crud-bar';\n");
        js.append("    rBar.innerHTML = '<button class=\"btn\" type=\"button\" id=\"btn-rad-edit\">MANAGE</button><button class=\"btn\" type=\"button\" id=\"btn-rad-up\">&#9650; UP</button><button class=\"btn\" type=\"button\" id=\"btn-rad-dn\">&#9660; DN</button><button class=\"btn\" type=\"button\" id=\"btn-rad-del\">DEL</button>';\n");
        js.append("    fRow.parentNode.appendChild(rBar);\n");

        js.append("    document.getElementById('btn-rad-edit').addEventListener('click', function() {\n");
        js.append("      var stObj = (window.__radioIdx >= 0 && window.__radioIdx < window.__radioStations.length) ? window.__radioStations[window.__radioIdx] : {};\n");
        js.append("      if (window.PlayerBridge && window.PlayerBridge.openStationJsonMenu) {\n");
        js.append("        window.PlayerBridge.openStationJsonMenu(window.__radioIdx, stObj.name || '', stObj.url || '');\n");
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
        js.append("      if (window.__radioStations.length === 0 || window.__radioIdx < 0 || window.__radioIdx >= window.__radioStations.length) return;\n");
        js.append("      if (navigator.vibrate) navigator.vibrate(25);\n");
        js.append("      window.__radioStations.splice(window.__radioIdx, 1);\n");
        js.append("      if (window.__radioIdx >= window.__radioStations.length) window.__radioIdx = window.__radioStations.length - 1;\n");
        js.append("      window.__saveRadioStations(false);\n");
        js.append("      window.renderPlaylist();\n");
        js.append("      if (window.__radioStations.length > 0 && window.__radioIdx >= 0) {\n");
        js.append("        window.selectRadioStationOnly(window.__radioIdx);\n");
        js.append("      } else {\n");
        js.append("        document.getElementById('track-main-readout').innerText = 'NO STATIONS // TAP MANAGE';\n");
        js.append("      }\n");
        js.append("    });\n");
        js.append("  }\n");

        js.append("  window.__normalizeStationList = function(arr) {\n");
        js.append("    if (!Array.isArray(arr)) return [];\n");
        js.append("    var out = [];\n");
        js.append("    arr.forEach(function(item) {\n");
        js.append("      if (item && item.name && item.url) {\n");
        js.append("        out.push({ name: String(item.name).trim(), url: String(item.url).trim() });\n");
        js.append("      }\n");
        js.append("    });\n");
        js.append("    return out;\n");
        js.append("  };\n");
        js.append("  try {\n");
        js.append("    var savedSt = JSON.parse(localStorage.getItem('proscan_radio_stations_v3') || '[]');\n");
        js.append("    window.__radioStations = window.__normalizeStationList(savedSt);\n");
        js.append("  } catch(e) { window.__radioStations = []; }\n");

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
        js.append("      var arr = window.__normalizeStationList(JSON.parse(jsonStr));\n");
        js.append("      window.__radioStations = arr;\n");
        js.append("      window.__radioIdx = arr.length > 0 ? 0 : -1;\n");
        js.append("      localStorage.setItem('proscan_radio_stations_v3', JSON.stringify(arr));\n");
        js.append("      window.renderPlaylist();\n");
        js.append("      if (arr.length > 0) window.selectRadioStationOnly(0);\n");
        js.append("    } catch(e) {}\n");
        js.append("  };\n");

        js.append("  window.onNativeStationSaved = function(editIdx, name, url) {\n");
        js.append("    try {\n");
        js.append("      var stItem = { name: String(name || '').trim(), url: String(url || '').trim() };\n");
        js.append("      if (!stItem.name || !stItem.url) return;\n");
        js.append("      if (typeof editIdx === 'number' && editIdx >= 0 && editIdx < window.__radioStations.length) {\n");
        js.append("        window.__radioStations[editIdx] = stItem;\n");
        js.append("        window.__radioIdx = editIdx;\n");
        js.append("      } else {\n");
        js.append("        window.__radioStations.push(stItem);\n");
        js.append("        window.__radioIdx = window.__radioStations.length - 1;\n");
        js.append("      }\n");
        js.append("      window.__saveRadioStations(false);\n");
        js.append("      window.renderPlaylist();\n");
        js.append("      window.selectRadioStationOnly(window.__radioIdx);\n");
        js.append("    } catch(e) {}\n");
        js.append("  };\n");

        js.append("  window.__isBroadcastRadioMode = false;\n");
        js.append("  window.__radioIdx = window.__radioStations.length > 0 ? 0 : -1;\n");
        js.append("  window.__activeRadioUrl = ").append(activeBgRadioUrl).append(";\n");

        js.append("  window.__startHybridLiveStream = function(url, name) {\n");
        js.append("    window.__stopAllRadioAndNativeAudio();\n");
        js.append("    if (window.PlayerBridge && window.PlayerBridge.playLiveStreamUrlWithName) {\n");
        js.append("      window.PlayerBridge.playLiveStreamUrlWithName(url, name || 'RADIO');\n");
        js.append("    } else if (window.PlayerBridge && window.PlayerBridge.playLiveStreamUrl) {\n");
        js.append("      window.PlayerBridge.playLiveStreamUrl(url);\n");
        js.append("    }\n");
        js.append("  };\n");

        js.append("  window.__seenOnPrevVisit = JSON.parse(localStorage.getItem('proscan_seen_visit_ids_v1') || '{}');\n");
        js.append("  window.__playedDriveIds = JSON.parse(localStorage.getItem('proscan_played_recs_v1') || '{}');\n");
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

        /* 3D Decks Modulate with Voice */
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
        js.append("    var deg = -35 + (window.__audioLevel * 72);\n");
        js.append("    ['cd-vu-l','r2r-vu-l','r2r-vu-r'].forEach(function(id, i){\n");
        js.append("      var ndl = document.getElementById(id);\n");
        js.append("      if (ndl) {\n");
        js.append("        var chDiff = (window.__audioLevel > 0.05) ? (i % 2 === 0 ? 2.5 : -2.5) : 0;\n");
        js.append("        ndl.style.transform = 'rotate(' + Math.min(38, Math.max(-35, deg + chDiff)).toFixed(1) + 'deg)';\n");
        js.append("      }\n");
        js.append("    });\n");
        js.append("    var sled = document.getElementById('cd-laser-sled');\n");
        js.append("    if (sled) {\n");
        js.append("      var dist = -22 - (window.__audioProgress * 36);\n");
        js.append("      sled.style.transform = 'translateX(' + dist.toFixed(1) + 'px) scale(' + (0.85 + window.__audioLevel*0.65).toFixed(2) + ')';\n");
        js.append("    }\n");
        js.append("    var activeSegs = window.__audioLevel < 0.04 ? 0 : Math.round(window.__audioLevel * 14);\n");
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
        js.append("    var hCtx = hCanvas.getContext('2d'), hPhase = 0, smoothLvl = 0.01;\n");
        js.append("    var barHeights = [], peaks = [];\n");
        js.append("    for (var p=0; p<28; p++) { barHeights.push(2.5); peaks.push(2.5); }\n");
        js.append("    function drawProSigintScope() {\n");
        js.append("      requestAnimationFrame(drawProSigintScope);\n");
        js.append("      if (document.documentElement.getAttribute('data-deck') !== 'hack') return;\n");
        js.append("      var W = hCanvas.width, H = hCanvas.height;\n");
        js.append("      hCtx.clearRect(0, 0, W, H);\n");
        js.append("      var rawTarget = isPlayingNow ? Math.max(0.015, window.__audioLevel * 0.95) : 0.01;\n");
        js.append("      smoothLvl += (rawTarget - smoothLvl) * 0.25;\n");
        js.append("      hCtx.strokeStyle = 'rgba(57,255,20,0.11)'; hCtx.lineWidth = 1;\n");
        js.append("      hCtx.beginPath();\n");
        js.append("      for (var gx = 0; gx < W; gx += 38) { hCtx.moveTo(gx, 0); hCtx.lineTo(gx, H); }\n");
        js.append("      for (var gy = 0; gy < H; gy += 28) { hCtx.moveTo(0, gy); hCtx.lineTo(W, gy); }\n");
        js.append("      hCtx.stroke();\n");
        js.append("      hCtx.fillStyle = '#38bdf8'; hCtx.font = 'bold 8.5px monospace';\n");
        js.append("      var dbm = (isPlayingNow && smoothLvl > 0.04) ? (-88 + Math.round(smoothLvl * 48)) : -98;\n");
        js.append("      hCtx.fillText('RF: ' + dbm + ' dBm  //  BW: 12.5kHz  //  DEMOD: NFM', 8, 12);\n");
        js.append("      hCtx.fillStyle = (isPlayingNow && smoothLvl > 0.04) ? '#39ff14' : '#64748b';\n");
        js.append("      hCtx.fillText((isPlayingNow && smoothLvl > 0.04) ? 'CARRIER: LOCKED' : 'CARRIER: SQUELCH', W - 96, 12);\n");
        js.append("      hCtx.strokeStyle = 'rgba(56,189,248,0.38)'; hCtx.lineWidth = 1.4;\n");
        js.append("      hCtx.beginPath();\n");
        js.append("      for (var x2 = 0; x2 < W; x2 += 3) {\n");
        js.append("        var env2 = Math.sin((x2 / W) * Math.PI);\n");
        js.append("        var y2 = 54 + Math.sin(x2 * 0.045 - hPhase * 0.65) * env2 * (smoothLvl * 28);\n");
        js.append("        if (x2 === 0) hCtx.moveTo(x2, y2); else hCtx.lineTo(x2, y2);\n");
        js.append("      }\n");
        js.append("      hCtx.stroke();\n");
        js.append("      hCtx.strokeStyle = '#39ff14'; hCtx.lineWidth = 2.2;\n");
        js.append("      hCtx.beginPath();\n");
        js.append("      for (var x = 0; x < W; x += 2) {\n");
        js.append("        var env = Math.sin((x / W) * Math.PI);\n");
        js.append("        var w1 = Math.sin(x * 0.04 + hPhase) * Math.cos(x * 0.018 - hPhase * 0.45);\n");
        js.append("        var w2 = Math.sin(x * 0.09 - hPhase * 0.8) * 0.25;\n");
        js.append("        var y = 54 + ((w1 + w2) * env * (smoothLvl * 38));\n");
        js.append("        if (x === 0) hCtx.moveTo(x, y); else hCtx.lineTo(x, y);\n");
        js.append("      }\n");
        js.append("      hCtx.stroke();\n");
        js.append("      var barW = 10, gap = 3, startX = 10, baseY = H - 6;\n");
        js.append("      for (var b = 0; b < 28; b++) {\n");
        js.append("        var bell = Math.sin(((b + 1) / 29) * Math.PI);\n");
        js.append("        var wave = 0.55 + 0.45 * Math.sin(b * 0.42 + hPhase * 0.85) * Math.cos(b * 0.21 - hPhase * 0.5);\n");
        js.append("        var goalH = (isPlayingNow && smoothLvl > 0.04) ? Math.max(3, Math.min(42, bell * wave * smoothLvl * 54)) : 2.5;\n");
        js.append("        barHeights[b] += (goalH - barHeights[b]) * 0.24;\n");
        js.append("        var curH = barHeights[b];\n");
        js.append("        if (curH >= peaks[b]) peaks[b] = curH;\n");
        js.append("        else peaks[b] = Math.max(2.5, peaks[b] - 0.22);\n");
        js.append("        var bx = startX + b * (barW + gap);\n");
        js.append("        var grad = hCtx.createLinearGradient(0, baseY - 42, 0, baseY);\n");
        js.append("        grad.addColorStop(0, '#facc15'); grad.addColorStop(0.4, '#22c55e'); grad.addColorStop(1, '#15803d');\n");
        js.append("        hCtx.fillStyle = grad;\n");
        js.append("        hCtx.fillRect(bx, baseY - curH, barW, curH);\n");
        js.append("        hCtx.fillStyle = 'rgba(56,189,248,0.85)';\n");
        js.append("        hCtx.fillRect(bx, baseY - peaks[b] - 2, barW, 1.5);\n");
        js.append("      }\n");
        js.append("      hPhase += isPlayingNow ? (0.05 + smoothLvl * 0.12) : 0.015;\n");
        js.append("    }\n");
        js.append("    drawProSigintScope();\n");
        js.append("  }\n");

        /* Select / Play Radio Station */
        js.append("  window.selectRadioStationOnly = function(idx) {\n");
        js.append("    if (idx < 0 || idx >= window.__radioStations.length) return;\n");
        js.append("    window.__radioIdx = idx;\n");
        js.append("    var stObj = window.__radioStations[idx];\n");
        js.append("    document.getElementById('track-main-readout').innerText = stObj.name;\n");
        js.append("    document.getElementById('cassette-lbl-left').innerText = stObj.name;\n");
        js.append("    document.getElementById('cassette-lbl-right').innerText = 'RADIO';\n");
        js.append("    var bCh = document.getElementById('badge-ch-name'), bFr = document.getElementById('badge-ch-freq');\n");
        js.append("    if (bCh) bCh.innerText = stObj.name;\n");
        js.append("    if (bFr) bFr.innerText = '';\n");
        js.append("    document.querySelectorAll('.radio-tile').forEach(function(el, i){ el.classList.toggle('selected', i === idx); });\n");
        js.append("  };\n");

        js.append("  window.playRadioStationIndex = function(idx) {\n");
        js.append("    if (idx < 0 || idx >= window.__radioStations.length) return;\n");
        js.append("    window.__stopAllRadioAndNativeAudio();\n");
        js.append("    window.__radioIdx = idx;\n");
        js.append("    var stObj = window.__radioStations[idx];\n");
        js.append("    window.__activeRadioUrl = stObj.url;\n");
        js.append("    document.getElementById('track-main-readout').innerText = stObj.name;\n");
        js.append("    document.getElementById('cassette-lbl-left').innerText = stObj.name;\n");
        js.append("    document.getElementById('cassette-lbl-right').innerText = 'ON AIR';\n");
        js.append("    var bCh = document.getElementById('badge-ch-name'), bFr = document.getElementById('badge-ch-freq');\n");
        js.append("    if (bCh) bCh.innerText = stObj.name;\n");
        js.append("    if (bFr) bFr.innerText = 'ON AIR';\n");
        js.append("    window.renderPlaylist();\n");
        js.append("    window.__startHybridLiveStream(stObj.url, stObj.name);\n");
        js.append("  };\n");

        js.append("  window.setAppMode = function(radioMode, skipAnim) {\n");
        js.append("    var dw = document.getElementById('device-wrapper');\n");
        js.append("    if (dw && !skipAnim) dw.classList.add('fading-out');\n");
        js.append("    setTimeout(function() {\n");
        js.append("      var wasRadio = window.__isBroadcastRadioMode;\n");
        js.append("      window.__isBroadcastRadioMode = Boolean(radioMode);\n");
        js.append("      if (wasRadio !== window.__isBroadcastRadioMode && !skipAnim) {\n");
        js.append("        window.__stopAllRadioAndNativeAudio();\n");
        js.append("        setPlayingUI(false);\n");
        js.append("      }\n");
        js.append("      var swBtn = document.getElementById('btn-switch-radio-archive');\n");
        js.append("      var navRowEl = swBtn ? swBtn.parentNode : document.querySelector('.player-nav-row');\n");
        js.append("      var hdr = document.querySelector('.screen-log-header');\n");
        js.append("      var fRowEl = document.getElementById('date-select').parentNode;\n");
        js.append("      var cRowEl = document.getElementById('radio-crud-row');\n");
        js.append("      var cModeBtn = document.getElementById('btn-mode-cont');\n");
        js.append("      var dspBtnEl = document.getElementById('btn-dsp-filter');\n");
        js.append("      if (window.__isBroadcastRadioMode) {\n");
        js.append("        if (navRowEl) navRowEl.classList.add('radio-mode-nav');\n");
        js.append("        if (swBtn) swBtn.innerText = 'ARCHIVE';\n");
        js.append("        document.getElementById('deck-mode-hud').innerText = 'BROADCAST // TUNER';\n");
        js.append("        if (hdr) hdr.style.setProperty('display', 'none', 'important');\n");
        js.append("        if (fRowEl) fRowEl.style.setProperty('display', 'none', 'important');\n");
        js.append("        if (cRowEl) cRowEl.style.setProperty('display', 'grid', 'important');\n");
        js.append("        if (cModeBtn) cModeBtn.style.display = 'none';\n");
        js.append("        if (dspBtnEl) dspBtnEl.style.display = 'none';\n");
        js.append("        if (").append(isBgRadioPlaying).append(" && window.__activeRadioUrl) {\n");
        js.append("          var activeIdx = window.__radioStations.findIndex(function(s){ return s.url === window.__activeRadioUrl; });\n");
        js.append("          if (activeIdx !== -1) window.__radioIdx = activeIdx;\n");
        js.append("          setPlayingUI(true);\n");
        js.append("        }\n");
        js.append("        window.renderPlaylist();\n");
        js.append("        if (window.__radioStations.length > 0) {\n");
        js.append("          if (window.__radioIdx < 0 || window.__radioIdx >= window.__radioStations.length) window.__radioIdx = 0;\n");
        js.append("          window.selectRadioStationOnly(window.__radioIdx);\n");
        js.append("        } else {\n");
        js.append("          document.getElementById('track-main-readout').innerText = 'NO STATIONS // TAP MANAGE';\n");
        js.append("          var bCh = document.getElementById('badge-ch-name'), bFr = document.getElementById('badge-ch-freq');\n");
        js.append("          if (bCh) bCh.innerText = 'NO STATIONS LOADED';\n");
        js.append("          if (bFr) bFr.innerText = '';\n");
        js.append("        }\n");
        js.append("      } else {\n");
        js.append("        if (navRowEl) navRowEl.classList.remove('radio-mode-nav');\n");
        js.append("        if (swBtn) swBtn.innerText = 'RADIO';\n");
        js.append("        if (typeof applyDeck === 'function') applyDeck(deckIdx);\n");
        js.append("        if (hdr) {\n");
        js.append("          hdr.style.setProperty('display', 'grid', 'important');\n");
        js.append("          hdr.style.gridTemplateColumns = '62px 52px 58px 34px 1fr';\n");
        js.append("          hdr.innerHTML = '<span>DATE</span><span>TIME</span><span>FREQ</span><span>DUR</span><span>NAME</span>';\n");
        js.append("        }\n");
        js.append("        if (fRowEl) fRowEl.style.setProperty('display', 'grid', 'important');\n");
        js.append("        if (cRowEl) cRowEl.style.setProperty('display', 'none', 'important');\n");
        js.append("        if (cModeBtn) cModeBtn.style.display = 'flex';\n");
        js.append("        if (dspBtnEl) dspBtnEl.style.display = 'flex';\n");
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
        js.append("      document.getElementById('rec-count-hud').innerText = window.__radioStations.length + ' ST';\n");
        js.append("      if (window.__radioStations.length === 0) {\n");
        js.append("        box.innerHTML = '<div style=\"padding:24px 14px;text-align:center;opacity:0.65;font-size:11px;font-family:monospace;\">No stations loaded.<br><br>Tap <b>MANAGE</b> below to Add a Station or Load Stations JSON.</div>';\n");
        js.append("        return;\n");
        js.append("      }\n");
        js.append("      var rHtml = '<div class=\"radio-grid\">';\n");
        js.append("      for (var j = 0; j < window.__radioStations.length; j++) {\n");
        js.append("        var st = window.__radioStations[j];\n");
        js.append("        var isPl = (st.url === window.__activeRadioUrl && isPlayingNow);\n");
        js.append("        var rCls = (isPl ? 'playing ' : '') + (j === window.__radioIdx ? 'selected' : '');\n");
        js.append("        rHtml += '<div class=\"radio-tile ' + rCls + '\" data-ridx=\"' + j + '\" title=\"' + st.name + '\">' + st.name + '</div>';\n");
        js.append("      }\n");
        js.append("      rHtml += '</div>';\n");
        js.append("      box.innerHTML = rHtml; box.scrollTop = prevScroll;\n");
        js.append("      box.querySelectorAll('.radio-tile').forEach(function(el) {\n");
        js.append("        el.addEventListener('click', function() {\n");
        js.append("          if (navigator.vibrate) navigator.vibrate(15);\n");
        js.append("          var idx = parseInt(el.getAttribute('data-ridx'), 10);\n");
        js.append("          window.playRadioStationIndex(idx);\n");
        js.append("        });\n");
        js.append("      });\n");
        js.append("      return;\n");
        js.append("    }\n");

        js.append("    var q = (document.getElementById('search-box').value || '').trim().toLowerCase();\n");
        js.append("    var fMode = window.__archiveFilterMode || 0;\n");
        js.append("    filteredRecs = allRecs.filter(function(r) {\n");
        js.append("      var key = r.fileId || r.fileName;\n");
        js.append("      if (window.__deletedDriveIds && window.__deletedDriveIds[key]) return false;\n");
        js.append("      var isCurrentlyPlayingTrack = (key && key === window.__activePlayingRecordKey && isPlayingNow);\n");
        js.append("      if (!isCurrentlyPlayingTrack) {\n");
        js.append("        if (fMode === 1 && window.__seenOnPrevVisit && window.__seenOnPrevVisit[key]) return false;\n");
        js.append("        if (fMode === 2 && window.__playedDriveIds && window.__playedDriveIds[key]) return false;\n");
        js.append("      }\n");
        js.append("      if (q && !r.name.toLowerCase().includes(q) && !r.freq.toLowerCase().includes(q) && !r.timeStr.toLowerCase().includes(q)) return false;\n");
        js.append("      return true;\n");
        js.append("    });\n");
        js.append("    document.getElementById('rec-count-hud').innerText = filteredRecs.length + ' RECS';\n");
        js.append("    if (!filteredRecs.length) {\n");
        js.append("      box.innerHTML = '<div style=\"padding:14px;text-align:center;opacity:0.6;font-size:10px;\">No recordings found.</div>';\n");
        js.append("      return;\n");
        js.append("    }\n");
        js.append("    var html = '';\n");
        js.append("    for (var i = 0; i < filteredRecs.length; i++) {\n");
        js.append("      var r = filteredRecs[i];\n");
        js.append("      var cls = ((r.fileId && r.fileId === window.__activePlayingFileId && isPlayingNow) ? 'playing ' : '') + (i === curIdx ? 'selected' : '');\n");
        js.append("      html += '<div class=\"rec-row ' + cls + '\" data-idx=\"' + i + '\">' +\n");
        js.append("        '<span>' + r.ukDate + '</span><span>' + r.timeStr + '</span><span>' + r.freq + '</span><span>' + r.dur + '</span>' +\n");
        js.append("        '<span title=\"' + r.name + '\">' + r.name + '</span></div>';\n");
        js.append("    }\n");
        js.append("    box.innerHTML = html; box.scrollTop = prevScroll;\n");
        js.append("    box.querySelectorAll('.rec-row').forEach(function(el) {\n");
        js.append("      el.addEventListener('click', function() {\n");
        js.append("        if (navigator.vibrate) navigator.vibrate(15);\n");
        js.append("        window.selectIndexOnly(parseInt(el.getAttribute('data-idx'), 10));\n");
        js.append("      });\n");
        js.append("    });\n");
        js.append("  };\n");

        js.append("  window.__commitPlayedRecordKey = function(key) {\n");
        js.append("    if (!key) return;\n");
        js.append("    window.__playedDriveIds[key] = true;\n");
        js.append("    try { localStorage.setItem('proscan_played_recs_v1', JSON.stringify(window.__playedDriveIds)); } catch(e) {}\n");
        js.append("  };\n");

        js.append("  var origPlayIndex = window.playIndex;\n");
        js.append("  window.playIndex = function(idx) {\n");
        js.append("    if (idx < 0 || idx >= filteredRecs.length) return;\n");
        js.append("    if (window.__activePlayingRecordKey) {\n");
        js.append("      window.__commitPlayedRecordKey(window.__activePlayingRecordKey);\n");
        js.append("    }\n");
        js.append("    window.__stopAllRadioAndNativeAudio();\n");
        js.append("    var r = filteredRecs[idx];\n");
        js.append("    var id = r.fileId || r.fileName;\n");
        js.append("    window.__activePlayingRecordKey = id;\n");
        js.append("    var ro = document.getElementById('track-main-readout');\n");
        js.append("    if (ro) ro.innerText = r.name + ' • ' + r.freq;\n");
        js.append("    var bCh = document.getElementById('badge-ch-name'), bFr = document.getElementById('badge-ch-freq');\n");
        js.append("    if (bCh) bCh.innerText = 'PLAYING: ' + r.name + ' (' + r.timeStr + ')';\n");
        js.append("    if (bFr) bFr.innerText = r.freq;\n");
        js.append("    origPlayIndex(idx);\n");
        js.append("  };\n");

        js.append("  window.onNativePlayEnded = function() {\n");
        js.append("    window.__stopAllRadioAndNativeAudio();\n");
        js.append("    if (window.__isBroadcastRadioMode) { renderPlaylist(); return; }\n");
        js.append("    if (window.__isLoopMode && curIdx >= 0) {\n");
        js.append("      playIndex(curIdx);\n");
        js.append("      return;\n");
        js.append("    }\n");
        js.append("    if (window.__activePlayingRecordKey) {\n");
        js.append("      window.__commitPlayedRecordKey(window.__activePlayingRecordKey);\n");
        js.append("      window.__activePlayingRecordKey = '';\n");
        js.append("    }\n");
        js.append("    window.__activePlayingFileId = '';\n");
        js.append("    renderPlaylist();\n");
        js.append("    if (isCont && curIdx > 0) {\n");
        js.append("      var nextIdx = Math.min(curIdx - 1, filteredRecs.length - 1);\n");
        js.append("      if (nextIdx >= 0) playIndex(nextIdx);\n");
        js.append("    } else if (curIdx >= 0 && curIdx < filteredRecs.length) {\n");
        js.append("      window.selectIndexOnly(curIdx);\n");
        js.append("    } else if (filteredRecs.length > 0) {\n");
        js.append("      window.selectIndexOnly(Math.min(curIdx, filteredRecs.length - 1));\n");
        js.append("    }\n");
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
        js.append("    var newStopBtn = oldStopBtn.cloneNode(true);\n");
        js.append("    oldStopBtn.parentNode.replaceChild(newStopBtn, oldStopBtn);\n");
        js.append("    newStopBtn.addEventListener('click', function() {\n");
        js.append("      window.__stopAllRadioAndNativeAudio();\n");
        js.append("      if (window.__isBroadcastRadioMode) {\n");
        js.append("        if (window.__radioStations.length > 0 && window.__radioIdx >= 0) {\n");
        js.append("          window.selectRadioStationOnly(window.__radioIdx);\n");
        js.append("        }\n");
        js.append("      } else {\n");
        js.append("        if (window.__activePlayingRecordKey) {\n");
        js.append("          window.__commitPlayedRecordKey(window.__activePlayingRecordKey);\n");
        js.append("          window.__activePlayingRecordKey = '';\n");
        js.append("        }\n");
        js.append("        window.__activePlayingFileId = '';\n");
        js.append("        window.renderPlaylist();\n");
        js.append("        if (curIdx >= 0 && curIdx < filteredRecs.length) window.selectIndexOnly(curIdx);\n");
        js.append("        else if (filteredRecs.length > 0) window.selectIndexOnly(Math.min(curIdx, filteredRecs.length - 1));\n");
        js.append("        return;\n");
        js.append("      }\n");
        js.append("      window.renderPlaylist();\n");
        js.append("    });\n");
        js.append("  }\n");

        js.append("  var sBox = document.getElementById('search-box');\n");
        js.append("  if (sBox) sBox.addEventListener('input', function() { window.renderPlaylist(); });\n");

        if (openInRadio) {
            js.append("  window.setAppMode(true, true);\n");
            if (isBgRadioPlaying) {
                js.append("  if (window.PlayerBridge && window.PlayerBridge.attachActiveRadioSession) window.PlayerBridge.attachActiveRadioSession();\n");
            }
        }
        js.append("})();");

        if (playerWeb != null) playerWeb.evaluateJavascript(js.toString(), null);
    }

    private void stopArchiveOnlyPlayer() {
        releaseEqualizer();
        currentStreamReqId++;
        liveStreamTapLevel = -1f;
        lastStreamTapUpdateMs = 0L;
        mainHandler.removeCallbacks(progressTicker);
        isLiveRadioStream = false;
        activeLiveRadioUrl = "";
        if (nativePlayer != null) {
            try { if (nativePlayer.isPlaying()) nativePlayer.stop(); } catch (Exception ignored) {}
            try { nativePlayer.reset(); nativePlayer.release(); } catch (Exception ignored) {}
            nativePlayer = null;
        }
    }

    private void stopNativePlayer() {
        stopArchiveOnlyPlayer();
        MainActivity.PriorityRadioEngine.stopStation();
        releasePlaybackLocks();
    }
    var FOLDER_ID = '1gToQyY6YPOk_3muiQSY5WLy7jTSvXbTv';

    function driveListChildren(parentIds) {
      var clauses = parentIds.map(function(id) { return "'" + id + "' in parents"; }).join(' or ');
      var q = encodeURIComponent("(" + clauses + ") and trashed = false");
      var url = 'https://www.googleapis.com/drive/v3/files?q=' + q +
                '&fields=files(id,name,createdTime,mimeType,trashed,size)&orderBy=name+desc&pageSize=250' +
                '&key=' + encodeURIComponent(apiKey) + '&_t=' + Date.now();
      return fetch(url, { cache: 'no-store' }).then(function(r) { return r.json(); });
    }

    function fetchRecordings() {
      if (window.AndroidApp && typeof window.AndroidApp.fetchDriveRecordingsForWeb === 'function') {
        listEl.textContent = 'Syncing active recordings from Google Drive...';
        window.__renderPcTapeDeckFromNative = function(items) {
          tracks = (items || []).filter(function(f) {
            return f && !window.__deletedDriveIds[f.id] && !window.__deletedDriveIds[f.name];
          });
          renderTracks();
        };
        window.AndroidApp.fetchDriveRecordingsForWeb();
        return;
      }
      if (!apiKey) {
        listEl.textContent = 'Missing Google Drive API Key. Tap [API KEY] above.';
        return;
      }
      window.__deletedDriveIds = JSON.parse(localStorage.getItem('proscan_deleted_recs_v1') || '{}');
      listEl.textContent = 'Syncing date folders from Google Drive...';

      driveListChildren([FOLDER_ID]).then(function(rootData) {
        if (rootData.error) {
          listEl.textContent = 'Drive Error: ' + (rootData.error.message || 'Check API Key');
          return;
        }
        var rootFiles = rootData.files || [];
        var dateFolders = rootFiles.filter(function(f) {
          return f.mimeType === 'application/vnd.google-apps.folder' && /^\d{2}-\d{2}-\d{2}$/.test(f.name);
        }).slice(0, 3);

        var directMp3s = rootFiles.filter(function(f) { return /\.(mp3|wav|m4a)$/i.test(f.name); });
        if (!dateFolders.length) {
          tracks = directMp3s;
          renderTracks();
          return;
        }
        return driveListChildren(dateFolders.map(function(d) { return d.id; })).then(function(level1) {
          var l1Files = (level1 && level1.files) || [];
          var subFolders = l1Files.filter(function(f) { return f.mimeType === 'application/vnd.google-apps.folder'; });
          var l1Mp3s = l1Files.filter(function(f) { return /\.(mp3|wav|m4a)$/i.test(f.name); });
          if (!subFolders.length) {
            tracks = directMp3s.concat(l1Mp3s);
            renderTracks();
            return;
          }
          return driveListChildren(subFolders.map(function(s) { return s.id; })).then(function(level2) {
            var l2Mp3s = ((level2 && level2.files) || []).filter(function(f) { return /\.(mp3|wav|m4a)$/i.test(f.name); });
            tracks = directMp3s.concat(l1Mp3s).concat(l2Mp3s).filter(function(f) {
              if (!f || f.trashed === true) return false;
              if (f.size !== undefined && parseInt(f.size, 10) <= 0) return false;
              if (window.__deletedDriveIds[f.id] || window.__deletedDriveIds[f.name]) return false;
              return true;
            });
            renderTracks();
          });
        });
      }).catch(function() {
        listEl.textContent = 'Network error connecting to Google Drive.';
      });
    }
