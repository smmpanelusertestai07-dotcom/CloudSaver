package com.pocketagent.doors;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The publisher's own interface, full screen, with the phone's keys underneath.
 *
 * This screen draws almost nothing. The page inside it belongs to Google, OpenAI or Anthropic;
 * it updates when they update it, and it has every option they ship. What is added around it is
 * the part a web page cannot do for itself: a keyboard row for the keys a touch screen lacks, a
 * way back, and a line that says what the daemon is doing while the page is still loading.
 *
 * Sign-in deliberately leaves this window. Google refuses OAuth inside an embedded view, and
 * they are right to -- a page cannot tell whether the app around it is reading what is typed.
 * So a login link opens in the phone's real browser, and the session comes back by cookie.
 */
public final class DoorActivity extends android.app.Activity implements KeyBar.Sender {
    static final String EXTRA_AGENT = "agent";

    private WebView web;
    private TextView status;
    private Doors.Agent agent;
    private String pendingUrl;
    private boolean loaded;
    private BroadcastReceiver events;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        agent = Doors.byId(getIntent().getStringExtra(EXTRA_AGENT));
        if (agent == null) { finish(); return; }
        setTitle(agent.name);

        boolean dark = Ui.dark(this);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.bg(dark));
        setContentView(root);

        status = Ui.text(this, "Opening " + agent.name + "…", 13.5f, Ui.muted(dark));
        int pad = Ui.dp(this, 14);
        status.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 10));
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        web = new WebView(this);
        web.setBackgroundColor(Ui.bg(dark));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // The editor and the dashboard are both desktop layouts. Letting the page believe it
        // has a wide viewport and then zooming is what makes them usable at all on 720 pixels.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        stage.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(Ui.divider(this, dark), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1))));
        root.addView(new KeyBar(this, this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The keyboard should push the page up, not cover it.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        listen();
        if (agent.door == Doors.Door.REMOTE_CONTROL && !agent.surface.isEmpty()) {
            // Google's dashboard is a public page; it can load while the daemon is still
            // starting, and it will find the machine once it is there.
            load(agent.surface);
        }
        DoorService.start(this, agent.id);
    }

    private void listen() {
        events = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String line = intent.getStringExtra(DoorService.EXTRA_LINE);
                String state = intent.getStringExtra(DoorService.EXTRA_STATE);
                String url = intent.getStringExtra(DoorService.EXTRA_URL);
                if (line != null) status.setText(line);
                if ("ready".equals(state) && url != null && !url.isEmpty() && !loaded) load(url);
                if ("failed".equals(state)) showFailure(line);
            }
        };
        registerReceiver(events, new IntentFilter(DoorService.EVENT), Context.RECEIVER_NOT_EXPORTED);
    }

    private void load(String url) {
        loaded = true;
        pendingUrl = url;
        web.loadUrl(url);
    }

    private void showFailure(String line) {
        loaded = false;
        status.setTextColor(Ui.FAILED);
        status.setText(line == null ? "This door did not open." : line);
        // The whole point of this build is to find out; the reason belongs on screen, not in
        // a log the owner has no way to read.
        String detail = Probe.detail(this, agent.id);
        if (!detail.isEmpty()) {
            web.loadDataWithBaseURL(null,
                    "<meta name=viewport content='width=device-width,initial-scale=1'>"
                            + "<body style=\"margin:16px;font:14px/1.5 sans-serif;"
                            + "background:" + hex(Ui.bg(Ui.dark(this))) + ";"
                            + "color:" + hex(Ui.text(Ui.dark(this))) + "\">"
                            + "<p><b>" + escape(agent.name) + " could not start here.</b></p>"
                            + "<p>Its own last words:</p><pre style=\"white-space:pre-wrap;"
                            + "font:12px/1.45 monospace;opacity:.85\">" + escape(detail) + "</pre>"
                            + "</body>", "text/html", "utf-8", null);
        }
    }

    private static String hex(int colour) {
        return String.format(Locale.US, "#%06X", colour & 0xFFFFFF);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------ keys

    @Override public void key(int keyCode, int metaState) {
        long now = SystemClock.uptimeMillis();
        web.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState));
        web.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState));
    }

    @Override public void type(String text) {
        long now = SystemClock.uptimeMillis();
        web.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_MULTIPLE, KeyEvent.KEYCODE_UNKNOWN,
                0, 0, 0, 0, 0, text.charAt(0)));
        // ACTION_MULTIPLE with a character is ignored by some engines; a real text commit is
        // the reliable path, so send both and let the page take whichever it understands.
        web.evaluateJavascript(
                "(function(t){var e=document.activeElement;if(!e)return;"
                        + "if(e.isContentEditable||'value' in e){"
                        + "document.execCommand('insertText',false,t);}})("
                        + org.json.JSONObject.quote(text) + ")", null);
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ web

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String host = uri.getHost() == null ? "" : uri.getHost();
            // Sign-in belongs in the real browser: Google refuses OAuth in an embedded view,
            // and an app that asked for a password in its own window would deserve the refusal.
            boolean signIn = host.contains("accounts.google.com")
                    || host.contains("auth.openai.com")
                    || host.contains("login.microsoftonline.com")
                    || host.contains("github.com") && uri.getPath() != null
                        && uri.getPath().startsWith("/login");
            if (signIn) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    status.setText("Sign in opened in your browser. Come back when it is done.");
                    return true;
                } catch (Exception noBrowser) {
                    return false;
                }
            }
            return false;
        }

        @Override public void onPageFinished(WebView view, String url) {
            status.setText(agent.name + " · " + shortHost(url));
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    android.webkit.WebResourceError error) {
            if (request.isForMainFrame()) {
                status.setTextColor(Ui.NEEDS_YOU);
                status.setText("Could not reach " + shortHost(request.getUrl().toString())
                        + ". It may still be starting.");
            }
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override public void onPermissionRequest(PermissionRequest request) {
            // Nothing in these interfaces needs the camera or the microphone yet. Refusing by
            // default is the honest position for a window showing somebody else's page.
            request.deny();
        }
    }

    private static String shortHost(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? url : host;
        } catch (Exception unparsed) {
            return url;
        }
    }

    @Override protected void onDestroy() {
        if (events != null) unregisterReceiver(events);
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}
