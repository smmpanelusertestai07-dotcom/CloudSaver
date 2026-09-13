package com.pocketide;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * The editor, full screen, with the few controls a phone needs around it.
 *
 * What fills this screen is Visual Studio Code, drawn by the phone's own browser engine from a
 * server running in the workspace on this same phone. There is no video stream and no remote
 * desktop: the text is real text, the scrolling is the platform's scrolling, and the keyboard
 * is the phone's keyboard. That is the entire reason this app is built the way it is.
 *
 * The bar at the bottom belongs to this app rather than to the editor, and everything on it is
 * hidden until it is wanted:
 *
 *   ⌘  opens the Command Palette, so the 29 commands that live only there are one tap away
 *      rather than behind a keyboard shortcut a phone cannot press
 *   ⌨  shows the key row
 *   ⊹  shows the cursor trackpad
 *   ⌂  goes back
 *
 * The agent panels themselves need none of this. All three are webviews -- the publishers draw
 * their own buttons -- so chatting, reading a plan, accepting a diff and changing a setting are
 * all ordinary taps.
 */
public final class WorkspaceActivity extends Activity implements KeyBar.Target {

    private WebView web;
    private KeyBar keys;
    private ProgressBar loading;
    private LinearLayout waiting;
    private TextView waitingLine;
    private BroadcastReceiver events;
    private android.widget.FrameLayout lockRoot;
    private boolean shown;
    /** One typed sign-in, at most, if the cookie is ever refused. See signInWithForm(). */
    private boolean formSignInTried;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Theme.apply(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // The lock matters most here. This is the screen with the editor on it, the terminal,
        // the agents' own panels and whatever source is open -- everything an app lock exists
        // to keep behind a fingerprint.
        AppLock.applyWindowSecurity(this);
        lockRoot = new android.widget.FrameLayout(this);
        lockRoot.addView(build());
        setContentView(lockRoot);
        listen();
        if (WorkspaceService.editorRunning()) open(WorkspaceService.editorUrl());
        else WorkspaceService.startEditor(this);
    }

    @Override protected void onStart() {
        super.onStart();
        if (AppLock.isLocked(this)) AppLock.show(this, lockRoot, null);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        AppLock.handleResult(this, lockRoot, request, result, null);
    }

    @Override protected void onDestroy() {
        if (events != null) {
            unregisterReceiver(events);
            events = null;
        }
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (keys != null && keys.anythingShowing()) {
            keys.hideAll();
            return;
        }
        // The editor's own history is where a person expects Back to go first: out of a file,
        // out of a panel. Only when it has nowhere left does Back leave the screen.
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ the screen

    private View build() {
        boolean dark = Ui.dark(this);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.bg(dark));

        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setMax(100);
        loading.setProgressTintList(ColorStateList.valueOf(Ui.accent(dark)));
        loading.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.bg(dark)));
        LinearLayout.LayoutParams loadingParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3));
        root.addView(loading, loadingParams);

        waiting = waitingPanel(dark);
        root.addView(waiting, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        web = new WebView(this);
        configure(web);
        web.setVisibility(View.GONE);
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        keys = new KeyBar(this, this);
        root.addView(keys, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(Ui.divider(this, dark, false));
        root.addView(bottomBar(dark), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    private LinearLayout waitingPanel(boolean dark) {
        LinearLayout column = Ui.column(this);
        column.setGravity(Gravity.CENTER);
        int pad = Ui.dp(this, 28);
        column.setPadding(pad, pad, pad, pad);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.mipmap.ic_launcher_foreground);
        int size = Ui.dp(this, 84);
        column.addView(mark, new LinearLayout.LayoutParams(size, size));
        column.addView(Ui.bold(this, "Starting the editor", 18, Ui.text(dark)),
                Ui.wide(this, 8));
        waitingLine = Ui.text(this,
                "The first start takes longest. Everything after this one is quick.",
                13.5f, Ui.muted(dark));
        waitingLine.setGravity(Gravity.CENTER);
        column.addView(waitingLine, Ui.wide(this, 8));
        return column;
    }

    /**
     * The editor's own toolbar.
     *
     * Material's rule is that a full-screen page reached from a primary one carries a toolbar
     * rather than a navigation bar, and this is that toolbar -- so it is built to the same
     * measurements as the navigation bar it replaces, and the two are never on screen together.
     *
     * Every button was previously added with no layout parameters at all, which meant each one
     * wrapped its own content and they all bunched against the left edge with no space between
     * them. On a phone that rendered as a single run of letters: CommandsKeysTrackpadHome. The
     * weight below is the fix, and it is the whole fix.
     */
    private View bottomBar(boolean dark) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Ui.card(dark));
        bar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout wrapper = Ui.column(this);
        View edge = new View(this);
        edge.setBackgroundColor(Ui.line(dark));
        wrapper.addView(edge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 0.5f))));
        wrapper.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, Shell.NAV_BAR_DP)));

        addBarButton(bar, dark, R.drawable.ic_terminal, "Commands",
                "Open the command palette", v -> commandPalette());
        addBarButton(bar, dark, R.drawable.ic_keyboard, "Keys",
                "Show or hide the key row", v -> keys.toggleKeys());
        addBarButton(bar, dark, R.drawable.ic_touch, "Cursor",
                "Show or hide the cursor pad", v -> keys.toggleTrackpad());
        addBarButton(bar, dark, R.drawable.ic_home, "Back",
                "Leave the editor", v -> finish());
        return wrapper;
    }

    /**
     * One button, given an equal quarter of the bar.
     *
     * The numbers match the navigation bar on the other screens: a 24 dp icon and a 12 sp label,
     * because a label small enough to need a squint is a label nobody reads, and because a
     * toolbar that looks like a different app from the one behind it is worse than no toolbar.
     */
    private void addBarButton(LinearLayout bar, boolean dark, int iconRes, String label,
                              String description, View.OnClickListener click) {
        LinearLayout button = Ui.column(this);
        button.setGravity(Gravity.CENTER);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(Ui.text(dark)));
        int size = Ui.dp(this, 24);
        button.addView(icon, new LinearLayout.LayoutParams(size, size));

        TextView words = Ui.text(this, label, 12f, Ui.muted(dark));
        words.setGravity(Gravity.CENTER);
        words.setSingleLine(true);
        LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wordParams.topMargin = Ui.dp(this, 4);
        button.addView(words, wordParams);

        button.setBackground(Ui.tappable(this,
                Ui.fill(this, android.graphics.Color.TRANSPARENT, 0), dark));
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(description);
        button.setOnClickListener(click);

        // A quarter each. Without this every button wrapped its own width and the four of them
        // ran together against the left edge.
        bar.addView(button, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    // ------------------------------------------------------------------ the editor

    private void configure(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // The editor is a local server on this phone. It is not a website, and treating it as
        // one -- pinch zoom, overview mode, a viewport it never asked for -- is what makes a
        // WebView feel like a bad browser instead of an application.
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // The editor keeps its session in a cookie. Without this the sign-in is forgotten on
        // every start and the owner sees a password box they never set.
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        view.setBackgroundColor(Ui.bg(Ui.dark(this)));

        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView web, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url == null) return false;
                String host = url.getHost();
                // Anything that is not the local editor is somebody else's website -- an agent's
                // sign-in page, a documentation link, a GitHub repository. Those belong in the
                // phone's own browser, where the owner can see the address bar and where a
                // password manager can reach them. Nothing signs in inside this window.
                if ("127.0.0.1".equals(host) || "localhost".equals(host)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Throwable noBrowser) {
                    Dialogs.message(WorkspaceActivity.this, "No browser",
                            "This phone has no browser to open " + url + ".");
                }
                return true;
            }

            @Override public void onPageFinished(WebView web, String url) {
                loading.setVisibility(View.GONE);
                // If the cookie was refused -- a wiped WebView data directory, a password
                // changed under us -- code-server answers with its own sign-in page. The owner
                // does not know this password and was never meant to, so the app types it for
                // them, once, using the form code-server itself serves.
                if (isLoginPage(url) && !formSignInTried) {
                    formSignInTried = true;
                    signInWithForm();
                    return;
                }
                showEditor();
            }
        });

        view.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public void onProgressChanged(WebView web, int progress) {
                loading.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
                loading.setProgress(progress);
            }
        });
    }

    /**
     * Opens the editor, already signed in.
     *
     * The owner never sees a password box, because the password is not theirs: the app generated
     * it so that no other app on this phone can reach the editor over loopback, and the app is
     * the one that has to present it.
     *
     * It does that by writing code-server's own session cookie before the first request, rather
     * than by loading a sign-in page and filling it in. That is possible because of what the
     * editor's source actually does -- see Workspace.editorSessionToken() for the three
     * functions this was read out of. Under the SHA256 password method, which is the method a
     * plain hex hashed-password selects, the cookie code-server accepts is that same digest.
     * The app writes the digest into the config and the digest into the cookie, and the first
     * request is authenticated.
     *
     * There is no query parameter here on purpose. An earlier draft passed ?password=, which
     * code-server has never supported and never has: every owner would have been met by a
     * sign-in box for a password they could not know.
     */
    private void open(String url) {
        if (web == null) return;
        String target = (url == null || url.isEmpty() ? Workspace.editorUrl() : url);
        if (!target.endsWith("/")) target = target + "/";
        final String destination = target + "?folder=/root/projects";

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        // Path=/ because code-server sets it at the root and a cookie only matches its own path;
        // Lax because the editor's own navigations are same-site and Lax is what code-server
        // itself uses; no Secure, because this is plain http on loopback and a Secure cookie
        // would simply be dropped.
        String cookie = Workspace.EDITOR_COOKIE_NAME + "=" + Workspace.editorSessionToken(this)
                + "; Path=/; SameSite=Lax";
        // The callback form, so the page is not requested before the cookie is committed. The
        // plain setCookie() returns before the store is written, and the race it loses shows up
        // as an unexplained sign-in page on slower phones.
        cookies.setCookie(target, cookie, new ValueCallback<Boolean>() {
            @Override public void onReceiveValue(Boolean written) {
                CookieManager.getInstance().flush();
                if (web != null) web.loadUrl(destination);
            }
        });
    }

    private boolean isLoginPage(String url) {
        if (url == null) return false;
        Uri parsed = Uri.parse(url);
        String path = parsed.getPath();
        return path != null && (path.equals("/login") || path.endsWith("/login"));
    }

    /**
     * The fallback: sign in the way a person would, by posting the form.
     *
     * code-server checks the typed password as sha256(typed) against the hashed-password in its
     * config, which is exactly what the app wrote there, so the generated password still works
     * as a typed password. It answers by setting its own cookie and redirecting, and the editor
     * loads. Tried once per screen, so a genuinely wrong password shows the sign-in page rather
     * than posting to it forever.
     */
    private void signInWithForm() {
        if (web == null) return;
        try {
            String body = "password=" + URLEncoder.encode(Workspace.editorPassword(this), "UTF-8")
                    + "&base=" + URLEncoder.encode("/", "UTF-8");
            web.postUrl(Workspace.editorUrl() + "login?to=%2F&folder=%2Froot%2Fprojects",
                    body.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException never) {
            // UTF-8 is required of every Java platform.
            showEditor();
        }
    }

    private void showEditor() {
        if (shown) return;
        shown = true;
        waiting.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
    }

    /**
     * Opens the Command Palette.
     *
     * Of the commands the three agent extensions contribute, 29 are reachable only from the
     * palette, and the palette's own shortcut is Ctrl+Shift+P -- three keys a phone keyboard
     * does not offer together. One tap here presses it for them.
     */
    private void commandPalette() {
        key(KeyEvent.KEYCODE_P, KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON);
    }

    // ------------------------------------------------------------------ keys

    @Override public void key(int keyCode, int metaState) {
        if (web == null) return;
        web.requestFocus();
        long now = android.os.SystemClock.uptimeMillis();
        // Both halves of the press, with the modifier state on each. A KeyEvent without its
        // ACTION_UP never releases, and the editor then behaves as if the key were stuck.
        web.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState));
        web.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState));
    }

    // ------------------------------------------------------------------ service

    private void listen() {
        events = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String state = intent.getStringExtra(WorkspaceService.EXTRA_STATE);
                String line = intent.getStringExtra(WorkspaceService.EXTRA_LINE);
                String url = intent.getStringExtra(WorkspaceService.EXTRA_URL);
                if ("ready".equals(state)) {
                    open(url);
                    return;
                }
                if ("failed".equals(state)) {
                    String advice = Trouble.advice(line);
                    Dialogs.details(WorkspaceActivity.this, "The editor did not start",
                            advice != null ? advice
                                    : "Nothing in Linux was lost. Go back and open it "
                                            + "again.",
                            line, "Copy details");
                    return;
                }
                if ("stopped".equals(state)) {
                    finish();
                    return;
                }
                if (line != null && !shown && waitingLine != null) waitingLine.setText(line);
            }
        };
        registerReceiver(events, new IntentFilter(WorkspaceService.EVENT),
                Context.RECEIVER_NOT_EXPORTED);
    }
}
