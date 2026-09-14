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
        // Android 13 and later never call onBackPressed() on this app; see Back.
        Back.register(this, () -> {
            if (!back()) finish();
        });
        listen();
        if (WorkspaceService.editorRunning()) open(WorkspaceService.editorUrl());
        else WorkspaceService.startEditor(this);
    }

    /**
     * The phone's Dark theme flipped while the editor was open.
     *
     * The chrome is rebuilt and the WebView is carried across rather than recreated -- see
     * build(). Losing an editor session, its open files and whatever a terminal was running,
     * because someone tapped a quick-settings tile, would be a far worse bug than the stale
     * colours this fixes.
     */
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Theme.apply(this);
        if (lockRoot != null) {
            lockRoot.removeAllViews();
            lockRoot.addView(build());
            if (AppLock.isLocked(this)) AppLock.show(this, lockRoot, null);
        }
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
        if (back()) return;
        super.onBackPressed();
    }

    /** True when Back was used up inside the editor; false when it should leave the screen. */
    private boolean back() {
        if (keys != null && keys.anythingShowing()) {
            keys.hideAll();
            return true;
        }
        // The editor's own history is where a person expects Back to go first: out of a file,
        // out of a panel. Only when it has nowhere left does Back leave the screen -- and the
        // sign-in page does not count as somewhere to go: with the cookie already set it only
        // bounces straight back to the editor, so Back never got out at all.
        if (web != null && web.canGoBack() && !previousIsSignIn()) {
            web.goBack();
            return true;
        }
        return false;
    }

    private boolean previousIsSignIn() {
        android.webkit.WebBackForwardList list = web.copyBackForwardList();
        int index = list.getCurrentIndex() - 1;
        if (index < 0) return true;
        android.webkit.WebHistoryItem item = list.getItemAtIndex(index);
        return item == null || item.getUrl() == null || item.getUrl().contains("/login");
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

        // Built once and re-parented afterwards. build() runs again when the phone's Dark
        // theme is flipped, and a new WebView there would drop the editor session, the open
        // files and whatever the terminal was in the middle of -- for a change of colour.
        if (web == null) {
            web = new WebView(this);
            configure(web);
            web.setVisibility(View.GONE);
        } else {
            ViewGroup previous = (ViewGroup) web.getParent();
            if (previous != null) previous.removeView(web);
        }
        if (web.getVisibility() == View.VISIBLE) waiting.setVisibility(View.GONE);
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        keys = new KeyBar(this, this);
        root.addView(keys, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(Ui.divider(this, dark, false));
        View bottom = bottomBar(dark);
        root.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Without this the toolbar's bottom 24 dp sat behind the gesture handle -- which is
        // exactly where Commands, Keys, Trackpad and Home have their labels -- and the WebView
        // started under the clock. targetSdk 35 draws every window edge to edge whether it asks
        // to or not, so a screen that does not handle insets does not get a choice.
        Theme.fitScreen(root, bottom);
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
        // A minimum, never a fixed height: fixed at 64 dp, a phone set to large text clipped
        // the bottom off every label, exactly as the navigation bar once did.
        bar.setMinimumHeight(Ui.dp(this, Shell.NAV_BAR_DP));
        wrapper.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addBarButton(bar, dark, R.drawable.ic_terminal, "Commands",
                "Open the command palette", v -> commandPalette());
        addBarButton(bar, dark, R.drawable.ic_keyboard, "Keys",
                "Show or hide the key row", v -> keys.toggleKeys());
        addBarButton(bar, dark, R.drawable.ic_cursor, "Cursor",
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
        button.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(Ui.text(dark)));
        int size = Ui.dp(this, 24);
        button.addView(icon, new LinearLayout.LayoutParams(size, size));

        TextView words = Ui.text(this, label, 12f, Ui.muted(dark));
        words.setGravity(Gravity.CENTER);
        words.setSingleLine(true);
        words.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    // ------------------------------------------------------------------ the editor

    private void configure(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // The editor is a local server on this phone, laid out at the phone's own width: no
        // wide viewport and no overview mode, which are for websites built for a desktop.
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        // Pinch to zoom, as a browser does. It was refused before, and an owner asked for it
        // by name: a diff, a diagram in a panel or a small line of terminal output is
        // something a finger should be able to enlarge for a moment and let go of. The
        // on-screen +/- controls stay off; the gesture is the control.
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        // 100, not the phone's font-scale percentage that WebView defaults to. Screen.java
        // already folds the owner's text size into the editor's own zoom level; left at the
        // default, the WebView applied it a second time on top and the editor's text was
        // scaled twice.
        settings.setTextZoom(100);
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
                allowPinchZoom(web);
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
     * palette, and one tap here presses its shortcut for them.
     *
     * F1, not Ctrl+Shift+P. Both open the palette in Visual Studio Code, and F1 is the one
     * that survives the trip through Android: a synthetic Ctrl+Shift+P has to carry two
     * modifier bits and a shifted letter through the WebView's key translation, and on the
     * owner's phone it arrived as nothing -- "Commands does not work" was the report. F1 is a
     * single unmodified key with its own key code on every layout, and the editor binds it to
     * the same command.
     */
    private void commandPalette() {
        key(KeyEvent.KEYCODE_F1, 0);
    }

    /**
     * Lets the owner pinch to zoom the editor's page.
     *
     * The workbench declares a viewport that forbids scaling, which is right for a desktop and
     * wrong for a thumb. The WebView's own zoom setting is not enough on its own -- the page's
     * viewport rule wins -- so the rule is loosened after the page has loaded. The layout width
     * is untouched: it stays the phone's own width, and only the scale is freed.
     */
    private void allowPinchZoom(WebView view) {
        if (view == null) return;
        view.evaluateJavascript(
                "(function(){var m=document.querySelector('meta[name=viewport]');"
                        + "if(!m){m=document.createElement('meta');m.name='viewport';"
                        + "document.head.appendChild(m);}"
                        + "m.setAttribute('content','width=device-width, initial-scale=1, "
                        + "minimum-scale=1, maximum-scale=4, user-scalable=yes');})();",
                null);
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
