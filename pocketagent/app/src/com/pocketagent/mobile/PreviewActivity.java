package com.pocketagent.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Local project preview. No JavaScript bridge, filesystem access or automatic external navigation. */
public final class PreviewActivity extends Activity {
    static final String EXTRA_PORT = "preview_port";
    static final String EXTRA_TOKEN = "preview_token";
    private WebView web;
    private TextView status;
    private FrameLayout lockRoot;
    private int port;
    private String token = "";
    private boolean initialLoaded;
    private AlertDialog externalDialog;

    static void open(Context context, int port) {
        open(context, port, WorkspaceTools.previewPort() == port ? WorkspaceTools.previewToken() : "");
    }

    static void open(Context context, int port, String token) {
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid preview port");
        if (token != null && !token.isEmpty() && !token.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid preview token");
        }
        Intent intent = new Intent(context, PreviewActivity.class).putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_TOKEN, token == null ? "" : token);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        port = getIntent().getIntExtra(EXTRA_PORT, 0);
        if (port < 1024 || port > 65535) { finish(); return; }
        token = getIntent().getStringExtra(EXTRA_TOKEN);
        if (token == null) token = WorkspaceTools.previewPort() == port ? WorkspaceTools.previewToken() : "";
        if (!token.isEmpty() && !token.matches("[a-f0-9]{64}")) { finish(); return; }
        AppLock.applyWindowSecurity(this);
        getWindow().setStatusBarColor(DeskStyle.BG);
        getWindow().setNavigationBarColor(DeskStyle.BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(DeskStyle.background(this));
        root.setFitsSystemWindows(true);
        lockRoot = new FrameLayout(this);
        lockRoot.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(lockRoot);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(4), dp(8), dp(4));
        toolbar.setBackground(DeskStyle.card(this));
        Button back = button("‹ Back");
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(80), dp(48)));
        status = Ui.text(this, "Local preview · " + port, 13, DeskStyle.MUTED);
        status.setPadding(dp(8), 0, dp(4), 0);
        toolbar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        Button reload = button("↻");
        reload.setContentDescription("Reload preview");
        reload.setOnClickListener(v -> { if (web != null) web.reload(); });
        toolbar.addView(reload, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(toolbar);
        try {
            web = new WebView(this);
            configure(web);
            root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
            web.setVisibility(View.INVISIBLE);
        } catch (RuntimeException unavailable) {
            TextView message = Ui.text(this, "Android WebView is unavailable. Update Android System WebView, then try preview again.",
                    16, Color.WHITE);
            message.setPadding(dp(24), dp(24), dp(24), dp(24));
            root.addView(message);
        }
    }

    private void configure(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        settings.setSafeBrowsingEnabled(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        view.setBackgroundColor(Color.WHITE);
        view.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public void onProgressChanged(WebView view, int progress) {
                status.setText(progress < 100 ? "Loading preview · " + progress + "%" : "Local preview · " + port);
            }
        });
        view.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (token.isEmpty() || !isLocalUrl(request.getUrl(), port)) return null;
                return protectedAsset(request);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isLocalUrl(uri, port)) return false;
                if (request.isForMainFrame() && request.hasGesture()) askOpenInBrowser(uri);
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (isLocalUrl(uri, port)) return false;
                return true;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    status.setText("Preview disconnected · tap ↻ to retry");
                }
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                status.setText("Preview closed · reopen it from your project");
                if (view.getParent() instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) view.getParent()).removeView(view);
                }
                view.destroy();
                web = null;
                return true;
            }
        });
        view.setDownloadListener((url, userAgent, disposition, mimeType, length) -> {
            status.setText("Use project files to save generated files.");
        });
    }

    static boolean isLocalUrl(Uri uri, int port) {
        if (uri == null || !"http".equalsIgnoreCase(uri.getScheme()) || uri.getPort() != port) return false;
        String authority = uri.getEncodedAuthority();
        if (authority == null || authority.indexOf('@') >= 0) return false;
        return "127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost());
    }

    /** Authenticate every static asset, including root-relative URLs, without exposing a JS bridge. */
    private WebResourceResponse protectedAsset(WebResourceRequest request) {
        HttpURLConnection connection = null;
        try {
            String method = request.getMethod();
            if (!("GET".equals(method) || "HEAD".equals(method))) {
                return new WebResourceResponse("text/plain", "UTF-8", 405, "Method Not Allowed",
                        new HashMap<>(), new ByteArrayInputStream(new byte[0]));
            }
            connection = (HttpURLConnection) new URL(request.getUrl().toString()).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("X-PocketAgent-Preview", token);
            for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                String name = header.getKey();
                if (!"Host".equalsIgnoreCase(name) && !"Connection".equalsIgnoreCase(name)
                        && !"X-PocketAgent-Preview".equalsIgnoreCase(name)) {
                    connection.setRequestProperty(name, header.getValue());
                }
            }
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) throw new IOException("Unexpected static preview redirect");
            String type = connection.getContentType();
            String mime = type == null ? "application/octet-stream" : type.split(";", 2)[0].trim();
            Map<String, String> headers = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    headers.put(entry.getKey(), String.join(", ", entry.getValue()));
                }
            }
            InputStream raw = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (raw == null) raw = new ByteArrayInputStream(new byte[0]);
            final HttpURLConnection active = connection;
            InputStream body = new FilterInputStream(raw) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { active.disconnect(); }
                }
            };
            String reason = connection.getResponseMessage();
            return new WebResourceResponse(mime, mime.startsWith("text/") ? "UTF-8" : null,
                    code, reason == null || reason.isEmpty() ? "Preview response" : reason, headers, body);
        } catch (IOException | RuntimeException error) {
            if (connection != null) connection.disconnect();
            byte[] message = "Preview disconnected. Return to your project and start preview again."
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return new WebResourceResponse("text/plain", "UTF-8", 503, "Preview unavailable",
                    new HashMap<>(), new ByteArrayInputStream(message));
        }
    }

    private void askOpenInBrowser(Uri uri) {
        if (AppLock.isLocked(this)) return;
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) || uri.getHost() == null) return;
        if (externalDialog != null) externalDialog.dismiss();
        externalDialog = new AlertDialog.Builder(this).setTitle("Open external link?")
                .setMessage(uri.getHost() + " will open in your browser.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open browser", (dialog, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
                    catch (RuntimeException unavailable) { status.setText("No browser is available for this link."); }
                }).show();
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setTextColor(DeskStyle.ACCENT);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(Ui.tappable(this, DeskStyle.field(this), true));
        return button;
    }

    private int dp(float value) { return Ui.dp(this, value); }

    @Override public void onBackPressed() {
        if (AppLock.isLocked(this)) { finish(); return; }
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onPause() {
        if (externalDialog != null) { externalDialog.dismiss(); externalDialog = null; }
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        AppLock.applyWindowSecurity(this);
        if (lockRoot == null) return;
        if (AppLock.isLocked(this)) {
            if (web != null) { web.onPause(); web.setVisibility(View.INVISIBLE); }
            AppLock.show(this, lockRoot, this::onUnlocked);
        } else onUnlocked();
    }

    private void onUnlocked() {
        if (web == null || AppLock.isLocked(this)) return;
        web.setVisibility(View.VISIBLE);
        web.onResume();
        if (!initialLoaded) {
            initialLoaded = true;
            web.loadUrl("http://127.0.0.1:" + port + "/");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        if (!AppLock.handleResult(this, lockRoot, request, result, this::onUnlocked)) {
            super.onActivityResult(request, result, data);
        }
    }

    @Override protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.setWebChromeClient(null);
            web.setWebViewClient(new WebViewClient());
            if (web.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) web.getParent()).removeView(web);
            }
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
