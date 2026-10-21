package com.proscan.interfaceapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private WebView webView;
    private SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String embeddedUiBase64 = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        /* Screen is NOT forced awake on launch anymore — controlled by your WAKE switch! */
        hideSystemUI();

        prefs = getSharedPreferences("proscan_prefs", MODE_PRIVATE);
        loadEmbeddedUiAsset();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        setupWebView();
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        /* Subtle tiny gear button in the very bottom-right corner of the screen to edit URLs anytime */
        Button settingsBtn = new Button(this);
        settingsBtn.setText("⚙");
        settingsBtn.setTextColor(Color.argb(90, 255, 255, 255));
        settingsBtn.setBackgroundColor(Color.TRANSPARENT);
        settingsBtn.setTextSize(14f);
        settingsBtn.setPadding(8, 8, 8, 8);
        settingsBtn.setOnClickListener(v -> showSetupDialog(false));

        FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END
        );
        root.addView(settingsBtn, btnParams);

        setContentView(root);

        String localUrl = prefs.getString("local_url", "");
        String tailscaleUrl = prefs.getString("tailscale_url", "");

        if (localUrl.isEmpty() && tailscaleUrl.isEmpty()) {
            showSetupDialog(true);
        } else {
            connectSmart(localUrl, tailscaleUrl);
        }
    }

    private void loadEmbeddedUiAsset() {
        try {
            InputStream is = getAssets().open("custom_ui.html");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[4096];
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            is.close();
            byte[] bytes = buffer.toByteArray();
            embeddedUiBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            embeddedUiBase64 = null;
        }
    }

    public class WebAppBridge {
        @JavascriptInterface
        public void setKeepScreenOn(final boolean keepOn) {
            mainHandler.post(() -> {
                if (keepOn) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }
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
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectCustomUiAndWakeBridge();
            }
        });
    }

    private void injectCustomUiAndWakeBridge() {
        StringBuilder js = new StringBuilder();
        js.append("(function() {\n");

        /* 1. If the server is a stock ProScan server without the custom UI, inject the embedded custom_ui.html! */
        if (embeddedUiBase64 != null && !embeddedUiBase64.isEmpty()) {
            js.append("  if (!document.getElementById('custom-scanner-ui-wrapper')) {\n");
            js.append("    try {\n");
            js.append("      var raw = atob('").append(embeddedUiBase64).append("');\n");
            js.append("      var bytes = new Uint8Array(raw.length);\n");
            js.append("      for (var i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);\n");
            js.append("      var html = new TextDecoder('utf-8').decode(bytes);\n");
            js.append("      var parser = new DOMParser();\n");
            js.append("      var doc = parser.parseFromString(html, 'text/html');\n");
            js.append("      var htmlAttrs = doc.documentElement.attributes;\n");
            js.append("      for (var a = 0; a < htmlAttrs.length; a++) {\n");
            js.append("        document.documentElement.setAttribute(htmlAttrs[a].name, htmlAttrs[a].value);\n");
            js.append("      }\n");
            js.append("      doc.head.querySelectorAll('link, style, meta[name=\"viewport\"]').forEach(function(el) {\n");
            js.append("        document.head.appendChild(el.cloneNode(true));\n");
            js.append("      });\n");
            js.append("      Array.from(doc.body.children).forEach(function(el) {\n");
            js.append("        if (el.tagName.toLowerCase() !== 'script') {\n");
            js.append("          document.body.appendChild(el.cloneNode(true));\n");
            js.append("        }\n");
            js.append("      });\n");
            js.append("      doc.querySelectorAll('script').forEach(function(oldScript) {\n");
            js.append("        var newScript = document.createElement('script');\n");
            js.append("        newScript.textContent = oldScript.textContent;\n");
            js.append("        document.body.appendChild(newScript);\n");
            js.append("      });\n");
            js.append("      window.dispatchEvent(new Event('load'));\n");
            js.append("    } catch (e) { console.error('UI Inject error:', e); }\n");
            js.append("  }\n");
        }

        /* 2. Hook the WAKE switch directly into Android's hardware FLAG_KEEP_SCREEN_ON */
        js.append("  if (!window.__androidWakeHooked) {\n");
        js.append("    window.__androidWakeHooked = true;\n");
        js.append("    if (window.AndroidApp && typeof window.AndroidApp.setKeepScreenOn === 'function') {\n");
        js.append("      window.AndroidApp.setKeepScreenOn(false);\n");
        js.append("    }\n");
        js.append("    var origToggleWake = window.toggleWakeLock;\n");
        js.append("    if (typeof origToggleWake === 'function') {\n");
        js.append("      window.toggleWakeLock = function() {\n");
        js.append("        origToggleWake();\n");
        js.append("        var btn = document.getElementById('btn-wake-toggle');\n");
        js.append("        var active = btn && btn.classList.contains('active');\n");
        js.append("        if (!active && typeof releaseScreenWake === 'function') releaseScreenWake();\n");
        js.append("        if (window.AndroidApp && typeof window.AndroidApp.setKeepScreenOn === 'function') {\n");
        js.append("          window.AndroidApp.setKeepScreenOn(Boolean(active));\n");
        js.append("        }\n");
        js.append("      };\n");
        js.append("    }\n");
        js.append("  }\n");
        js.append("})();");

        webView.evaluateJavascript(js.toString(), null);
    }

    private String normalizeUrl(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://" + u;
        }
        return u;
    }

    private void showSetupDialog(boolean isFirstRun) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        TextView label1 = new TextView(this);
        label1.setText("Local Wi-Fi URL (e.g. 192.168.1.50:5000):");
        layout.addView(label1);

        EditText localInput = new EditText(this);
        localInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        localInput.setHint("http://192.168.1.50:5000");
        localInput.setText(prefs.getString("local_url", ""));
        layout.addView(localInput);

        TextView label2 = new TextView(this);
        label2.setText("\nTailscale URL (e.g. 100.x.y.z:5000):");
        layout.addView(label2);

        EditText tsInput = new EditText(this);
        tsInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        tsInput.setHint("http://100.x.y.z:5000");
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

            prefs.edit()
                    .putString("local_url", loc)
                    .putString("tailscale_url", ts)
                    .apply();

            connectSmart(loc, ts);
        });

        if (!isFirstRun) {
            builder.setNegativeButton("Cancel", null);
        }

        builder.show();
    }

    private void connectSmart(String localUrl, String tailscaleUrl) {
        if (!localUrl.isEmpty() && tailscaleUrl.isEmpty()) {
            webView.loadUrl(localUrl);
            return;
        }
        if (localUrl.isEmpty() && !tailscaleUrl.isEmpty()) {
            webView.loadUrl(tailscaleUrl);
            return;
        }

        Toast.makeText(this, "Checking Local Wi-Fi...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            boolean localReachable = isUrlReachable(localUrl, 1500);
            mainHandler.post(() -> {
                if (localReachable) {
                    Toast.makeText(this, "Connected via Local Wi-Fi", Toast.LENGTH_SHORT).show();
                    webView.loadUrl(localUrl);
                } else {
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
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    @Override
    public void onBackPressed() {
        showSetupDialog(false);
    }
}
