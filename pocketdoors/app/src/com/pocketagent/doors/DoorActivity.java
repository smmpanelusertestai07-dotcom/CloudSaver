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
import android.widget.EditText;
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
    private LinearLayout signIn;
    private TextView openLink;
    private TextView openSurface;
    private LinearLayout openSurfaceHolder;
    private View keys;
    private EditText answer;
    private Doors.Agent agent;
    private String link;
    private String surface;
    private boolean loaded;
    private boolean signingIn;
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

        root.addView(buildSignIn(dark), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(buildOpenSurface(dark), new LinearLayout.LayoutParams(
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
        keys = new KeyBar(this, this);
        root.addView(keys, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (agent.opensInBrowser()) keys.setVisibility(View.GONE);

        // The keyboard should push the page up, not cover it.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        listen();
        // Nothing is loaded here on the way in. An earlier build opened the publisher's
        // dashboard immediately, which meant the first thing the owner saw was whatever that
        // address returned before any of the work had happened -- and the address it used was
        // wrong, so what they saw was a 404 that said nothing about the daemon at all.
        DoorService.start(this, agent.id);
    }

    /**
     * The sign-in strip. Hidden until a door asks for something.
     *
     * Google's CLI on a machine with no desktop prints a link and waits for the code the
     * browser gives back. So this is exactly two controls: one that opens that link in the
     * phone's real browser, and one box to paste the code into. The link never opens in this
     * window -- Google refuse an embedded view for sign-in, and a window that asked for a
     * password would deserve the refusal.
     */
    private LinearLayout buildSignIn(boolean dark) {
        signIn = Ui.column(this);
        int pad = Ui.dp(this, 14);
        signIn.setPadding(pad, Ui.dp(this, 4), pad, pad);
        signIn.setBackgroundColor(Ui.card(dark));
        signIn.setVisibility(View.GONE);

        openLink = Ui.button(this, "Open the sign-in link", true, dark);
        openLink.setId(1);
        openLink.setVisibility(View.GONE);
        openLink.setOnClickListener(v -> {
            if (link == null) return;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                status.setText("Sign in, then come back and paste the code below.");
            } catch (Exception noBrowser) {
                status.setText("No browser could open that link.");
            }
        });
        signIn.addView(openLink, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        answer = new EditText(this);
        answer.setId(2);
        answer.setHint("Paste the code here");
        answer.setSingleLine(true);
        answer.setTextSize(15);
        answer.setTextColor(Ui.text(dark));
        answer.setHintTextColor(Ui.muted(dark));
        answer.setBackground(Ui.outlined(this, Ui.bg(dark), Ui.line(dark), 10));
        int inner = Ui.dp(this, 12);
        answer.setPadding(inner, inner, inner, inner);
        row.addView(answer, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView send = Ui.button(this, "Send", false, dark);
        send.setId(3);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sendLp.setMarginStart(Ui.dp(this, 8));
        send.setOnClickListener(v -> {
            String typed = answer.getText().toString().trim();
            if (typed.isEmpty()) return;
            DoorService.send(this, typed);
            answer.setText("");
            status.setText("Sent. Waiting for the door…");
        });
        row.addView(send, sendLp);
        signIn.addView(row, Ui.wide(this, 10));
        return signIn;
    }

    private void askToSignIn() {
        signingIn = true;
        signIn.setVisibility(View.VISIBLE);
        openLink.setVisibility(View.GONE);
        status.setTextColor(Ui.NEEDS_YOU);
        status.setText("Sign in to Google first. Starting the sign-in…");
        DoorService.start(this, agent.id, DoorService.MODE_LOGIN);
    }

    private void listen() {
        events = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String line = intent.getStringExtra(DoorService.EXTRA_LINE);
                String state = intent.getStringExtra(DoorService.EXTRA_STATE);
                String url = intent.getStringExtra(DoorService.EXTRA_URL);
                String found = intent.getStringExtra(DoorService.EXTRA_LINK);
                if (line != null) status.setText(line);
                if ("needlogin".equals(state)) { askToSignIn(); return; }
                if ("asking".equals(state)) {
                    signIn.setVisibility(View.VISIBLE);
                    if (found != null) {
                        link = found;
                        openLink.setVisibility(View.VISIBLE);
                    }
                    return;
                }
                if ("signedin".equals(state)) {
                    signIn.setVisibility(View.GONE);
                    status.setTextColor(Ui.muted(Ui.dark(DoorActivity.this)));
                    if (signingIn) {
                        // Sign-in finished; the daemon can be started now, which is the step
                        // the owner actually asked for.
                        signingIn = false;
                        status.setText("Signed in. Starting " + agent.name + "…");
                        DoorService.start(DoorActivity.this, agent.id);
                    }
                    return;
                }
                if ("ready".equals(state) && url != null && !url.isEmpty() && !loaded) show(url);
                if ("failed".equals(state)) showFailure(line);
            }
        };
        registerReceiver(events, new IntentFilter(DoorService.EVENT), Context.RECEIVER_NOT_EXPORTED);
    }

    /** Puts the running interface where it belongs: this window, or the phone's browser. */
    private void show(String url) {
        loaded = true;
        if (!agent.opensInBrowser()) {
            web.loadUrl(url);
            return;
        }
        surface = url;
        status.setTextColor(Ui.text(Ui.dark(this)));
        status.setText(agent.name + " is running on this phone. Its dashboard opens in your "
                + "browser, where you are already signed in to Google.");
        openSurface.setText("Open the " + agent.name + " dashboard");
        openSurface.setVisibility(View.VISIBLE);
        openSurfaceHolder.setVisibility(View.VISIBLE);
    }

    private void load(String url) {
        loaded = true;
        web.loadUrl(url);
    }

    /**
     * The way out to a publisher's own dashboard.
     *
     * It is a button rather than an automatic jump because leaving the app the instant a daemon
     * starts would hide the one line that says the daemon started. Google's page can also be
     * added to the home screen from the browser, which is how their notifications arrive, and
     * that only works in a real browser.
     */
    private LinearLayout buildOpenSurface(boolean dark) {
        LinearLayout holder = Ui.column(this);
        int pad = Ui.dp(this, 14);
        holder.setPadding(pad, 0, pad, Ui.dp(this, 10));
        holder.setVisibility(View.GONE);
        openSurface = Ui.button(this, "Open the dashboard", true, dark);
        openSurface.setId(4);
        openSurface.setVisibility(View.GONE);
        openSurface.setOnClickListener(v -> {
            if (surface == null || surface.isEmpty()) return;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(surface))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception noBrowser) {
                status.setText("No browser on this phone could open " + surface + ".");
            }
        });
        holder.addView(openSurface, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        openSurfaceHolder = holder;
        return holder;
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
