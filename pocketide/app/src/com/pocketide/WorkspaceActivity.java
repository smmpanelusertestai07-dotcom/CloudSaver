package com.pocketide;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
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
    private TextView retry;
    private BroadcastReceiver events;
    private android.widget.FrameLayout lockRoot;
    private boolean shown;
    /** One typed sign-in, at most, if the cookie is ever refused. See signInWithForm(). */
    private boolean formSignInTried;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Theme.apply(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // An agent can work for minutes with nothing typed. Without this the screen sleeps
        // while it does, and the owner is watching a phone that keeps going dark. The
        // service's wake lock is the CPU's, not the screen's, and does not cover this.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // The lock matters most here. This is the screen with the editor on it, the terminal,
        // the agents' own panels and whatever source is open -- everything an app lock exists
        // to keep behind a fingerprint.
        AppLock.applyWindowSecurity(this);
        try {
            lockRoot = new android.widget.FrameLayout(this);
            lockRoot.addView(build());
            addRestoreButton();
            setContentView(lockRoot);
        } catch (Throwable failure) {
            // The same reasoning as MainActivity's guard: this screen creates a WebView, and a
            // phone that cannot give it one throws from the constructor. Closing this screen
            // leaves the app standing; not catching it closes the app.
            Crash.save(this, failure);
            Dialogs.message(this, "The editor could not be opened",
                    "This phone would not give the app a browser window to draw the editor in. "
                            + "Nothing in Linux was touched.");
            finish();
            return;
        }
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
            // Turning the phone rebuilds the chrome, and the key row's own state -- which keys
            // are showing, which modifiers are latched -- used to be thrown away with it.
            int keyState = keys == null ? 0 : keys.snapshot();
            lockRoot.removeAllViews();
            lockRoot.addView(build());
            addRestoreButton();
            if (keys != null) keys.restore(keyState);
            if (AppLock.isLocked(this)) AppLock.show(this, lockRoot, null);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        Rotation.apply(this);
        if (AppLock.isLocked(this)) AppLock.show(this, lockRoot, null);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_FILE) {
            ValueCallback<android.net.Uri[]> waiting = pendingFiles;
            pendingFiles = null;
            if (waiting != null) {
                waiting.onReceiveValue(
                        android.webkit.WebChromeClient.FileChooserParams
                                .parseResult(result, data));
            }
            return;
        }
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
        toolbar = bottom;
        bottom.setVisibility(fullScreen ? View.GONE : View.VISIBLE);
        root.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Without this the toolbar's bottom 24 dp sat behind the gesture handle -- which is
        // exactly where Commands, Keys, Trackpad and Home have their labels -- and the WebView
        // started under the clock. targetSdk 35 draws every window edge to edge whether it asks
        // to or not, so a screen that does not handle insets does not get a choice.
        Theme.fitScreen(root, bottom);
        return root;
    }

    /** The one control left on screen in full screen, floating over the editor's corner. */
    private void addRestoreButton() {
        boolean dark = Ui.dark(this);
        restore = Ui.medium(this, "Menu", 13f, Ui.onAccentContainer(dark));
        restore.setGravity(Gravity.CENTER);
        int padX = Ui.dp(this, 14);
        restore.setPadding(padX, Ui.dp(this, 10), padX, Ui.dp(this, 10));
        restore.setMinHeight(Ui.dp(this, Ui.TOUCH_TARGET_DP));
        restore.setBackground(Ui.tappable(this,
                Ui.fill(this, Ui.alpha(Ui.accent(dark), dark ? 220 : 235), 999), dark));
        restore.setElevation(Ui.dp(this, 8));
        restore.setClickable(true);
        restore.setFocusable(true);
        restore.setContentDescription("Show this app's buttons again");
        Ui.asButton(restore);
        restore.setVisibility(fullScreen ? View.VISIBLE : View.GONE);
        restore.setOnClickListener(v -> toggleFullScreen());
        android.widget.FrameLayout.LayoutParams params =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.END);
        int margin = Ui.dp(this, 16);
        params.setMargins(margin, margin, margin, Ui.dp(this, 28));
        lockRoot.addView(restore, params);
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

        // Hidden until something goes wrong. A failure used to leave a dialog with a Copy
        // button and no way forward at all: the only route back to the editor was to leave the
        // screen and come in again.
        retry = Ui.primaryButton(this, "Open the editor again", dark);
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> tryAgain());
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 240), ViewGroup.LayoutParams.WRAP_CONTENT);
        retryParams.topMargin = Ui.dp(this, 22);
        column.addView(retry, retryParams);
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

        addBarButton(bar, dark, R.drawable.ic_apps, "Menu",
                "Open the editor menu", v -> menu());
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

            /**
             * The browser engine's own process died, and the app is not going with it.
             *
             * This is the most important twelve lines in the file. A WebView runs its page in a
             * separate renderer process, and when that process is killed -- which on a phone
             * means Android reclaiming memory from the most expensive thing in sight, and the
             * most expensive thing in sight is Visual Studio Code with an extension host and
             * three agent panels in it -- Android's rule is that an app which does not handle
             * the death is killed with it. Not the screen: the whole app, with no dialog, no
             * report and nothing in the log an owner could find.
             *
             * That is exactly the fault an owner reported as "app open karte hi apne aap close
             * ho raha" -- it closes by itself the moment it opens -- because opening the app
             * went straight back to the editor, and the editor was what could not fit.
             *
             * Returning true says the app has dealt with it. The dead WebView can never be used
             * again, so it is taken down and the screen offers to start over, which costs the
             * session but not the app, the workspace, or anything on disk.
             */
            @Override public boolean onRenderProcessGone(WebView web,
                    android.webkit.RenderProcessGoneDetail detail) {
                boolean crashed = Build.VERSION.SDK_INT < 26 || detail == null
                        || detail.didCrash();
                editorDied(crashed
                        ? "The editor's window stopped unexpectedly."
                        : "Android reclaimed the editor's memory for something else.");
                return true;
            }

            @Override public void onReceivedError(WebView web, WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                // Only the page itself. A panel inside it failing to fetch something is the
                // panel's business and is not worth taking the screen over.
                if (request == null || !request.isForMainFrame()) return;
                editorDied("The editor did not answer. "
                        + (error == null ? "" : error.getDescription()));
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

            /**
             * The file picker an agent's own "attach a file" button opens.
             *
             * Without this the button is inert: Android's default for a WebView is to refuse
             * the request, and the page is given no way to say so, so nothing happens at all.
             */
            @Override public boolean onShowFileChooser(WebView web,
                    ValueCallback<android.net.Uri[]> callback,
                    android.webkit.WebChromeClient.FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = callback;
                try {
                    startActivityForResult(params.createIntent(), PICK_FILE);
                    AppLock.expectReturn();
                    return true;
                } catch (Throwable noPicker) {
                    pendingFiles = null;
                    return false;
                }
            }
        });

        // A download started from inside the editor -- an agent offering a file, a link in a
        // panel -- goes to the phone's own downloads rather than nowhere. Without a listener a
        // WebView silently drops it.
        view.setDownloadListener((url, agent, disposition, mime, size) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable noBrowser) {
                Dialogs.message(this, "Nothing could open that",
                        "This phone has no app that will take the download.");
            }
        });
    }

    /** Where the editor's own file picker sends its answer. */
    private ValueCallback<android.net.Uri[]> pendingFiles;
    private static final int PICK_FILE = 8814;

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

    /**
     * The editor is gone and cannot be brought back in place: says so, and offers to start over.
     *
     * The WebView is destroyed rather than reloaded because a WebView whose renderer has died
     * is permanently unusable -- every later call on it throws -- so the next attempt builds a
     * new one from scratch.
     */
    private void editorDied(String reason) {
        if (isFinishing() || isDestroyed()) return;
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            try {
                web.destroy();
            } catch (Throwable alreadyGone) {
                // Destroying a dead WebView can itself throw. Nothing left to release.
            }
            web = null;
        }
        shown = false;
        formSignInTried = false;
        if (waiting != null) waiting.setVisibility(View.VISIBLE);
        if (loading != null) loading.setVisibility(View.GONE);
        if (waitingLine != null) {
            waitingLine.setText(reason + "\n\nNothing was lost — your files and everything "
                    + "running in Linux are untouched. Tap below to open it again.");
        }
        if (retry != null) retry.setVisibility(View.VISIBLE);
    }

    /** Starts the editor screen over, with a new WebView. */
    private void tryAgain() {
        if (retry != null) retry.setVisibility(View.GONE);
        if (waitingLine != null) waitingLine.setText("Starting the editor…");
        if (lockRoot != null) {
            lockRoot.removeAllViews();
            lockRoot.addView(build());
            addRestoreButton();
        }
        if (WorkspaceService.editorRunning()) open(WorkspaceService.editorUrl());
        else WorkspaceService.startEditor(this);
    }

    private void showEditor() {
        if (shown) return;
        shown = true;
        waiting.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        loadPanels();
    }

    /**
     * The editor menu: everything a phone cannot otherwise reach, in one sheet.
     *
     * This replaces a single "Commands" button that an owner reported as doing nothing. Two
     * things were wrong with it and both are fixed here.
     *
     * It pressed a key natively, through the WebView's key translation, which is the path that
     * had already swallowed Ctrl+Shift+P. It now dispatches the key as a DOM event instead --
     * see press() -- which is the same event the editor would have seen and does not depend on
     * where Android thinks the focus is.
     *
     * And a palette was the wrong answer to the question being asked. The owner's actual
     * problem was that Antigravity was installed and they could not find how to open it: an
     * agent lives in the activity bar, which on a phone is a row of small icons along the
     * bottom of the editor. So the installed agents are named here, each opening its own panel,
     * and the palette is one row among them rather than the only door.
     */
    private void menu() {
        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<Integer> icons = new java.util.ArrayList<>();
        final java.util.List<Integer> keys = new java.util.ArrayList<>();

        for (int i = 0; i < panels.size() && i < Extensions.PANEL_KEYS; i++) {
            labels.add("Open " + panels.get(i).title);
            icons.add(R.drawable.ic_bolt);
            keys.add(Extensions.FIRST_PANEL_KEY + i);
        }
        labels.add("Command palette");
        icons.add(R.drawable.ic_terminal);
        keys.add(1);
        labels.add("Files");
        icons.add(R.drawable.ic_storage);
        keys.add(2);
        labels.add("Terminal");
        icons.add(R.drawable.ic_code);
        keys.add(3);
        labels.add("Extensions");
        icons.add(R.drawable.ic_extension);
        keys.add(4);
        labels.add("Show or hide the side panel");
        icons.add(R.drawable.ic_apps);
        keys.add(11);
        labels.add(fullScreen ? "Show this app's buttons" : "Full screen");
        icons.add(R.drawable.ic_fit);
        keys.add(0);
        labels.add("Smaller text");
        icons.add(R.drawable.ic_fit);
        keys.add(8);
        labels.add("Larger text");
        icons.add(R.drawable.ic_fit);
        keys.add(9);
        labels.add("Reset the text size");
        icons.add(R.drawable.ic_rotate);
        keys.add(10);

        int[] iconIds = new int[icons.size()];
        for (int i = 0; i < icons.size(); i++) iconIds[i] = icons.get(i);
        Dialogs.choose(this, "Editor", labels.toArray(new String[0]), iconIds, -1,
                index -> {
                    int key = keys.get(index);
                    // Zero is not a function key: it is the one row here that the app itself
                    // acts on rather than passing to the editor.
                    if (key == 0) toggleFullScreen();
                    else press(key);
                });
    }

    /**
     * Presses one of the function keys the editor's own keybindings.json binds, as a DOM event.
     *
     * Not dispatchKeyEvent. That path goes through Android's key translation into the WebView
     * and depends on what currently holds focus inside the page; it is the path that arrived as
     * nothing when the button sent Ctrl+Shift+P, and a phone with no hardware keyboard gives it
     * no help. A KeyboardEvent dispatched on the focused element with bubbles set reaches the
     * editor's keybinding service the same way a real press would -- the service listens for
     * keydown and reads the code off the event, and does not ask where the event came from.
     *
     * Sent once, by one mechanism. Sending it natively as well would double every toggle in the
     * menu, so the terminal would open and close again on one tap.
     */
    private void press(int functionKey) {
        if (web == null) return;
        // F1 is DOM key code 112, and they run consecutively from there.
        int domCode = 111 + functionKey;
        String name = "F" + functionKey;
        String js = "(function(){var t=document.activeElement||document.body;if(!t)return;"
                + "['keydown','keyup'].forEach(function(type){"
                + "t.dispatchEvent(new KeyboardEvent(type,{key:'" + name + "',code:'" + name
                + "',keyCode:" + domCode + ",which:" + domCode
                + ",bubbles:true,cancelable:true}));});})();";
        web.evaluateJavascript(js, null);
    }

    /**
     * Which installed extensions have a panel of their own, read once the editor is up.
     *
     * Off the drawing thread because it opens one manifest per extension and an agent's
     * manifest runs to hundreds of kilobytes. The menu reads this field, so it opens at once.
     */
    private volatile java.util.List<Extensions.Panel> panels = java.util.Collections.emptyList();

    /** True while this app's own toolbar is hidden and the editor has the whole screen. */
    private boolean fullScreen;
    private View toolbar;
    private TextView restore;

    /**
     * Gives the editor the whole screen, leaving one small button to come back with.
     *
     * Held sideways a 64 dp toolbar is most of a fifth of the height, on the screen where the
     * extra room was the reason for turning the phone. The button that brings it back sits in
     * the corner rather than disappearing entirely, because a control with no way back is a
     * trap rather than a mode.
     */
    private void toggleFullScreen() {
        fullScreen = !fullScreen;
        if (toolbar != null) toolbar.setVisibility(fullScreen ? View.GONE : View.VISIBLE);
        if (keys != null && fullScreen) keys.hideAll();
        if (restore != null) restore.setVisibility(fullScreen ? View.VISIBLE : View.GONE);
    }

    private void loadPanels() {
        new Thread(() -> {
            final java.util.List<Extensions.Panel> found = Extensions.panels(this);
            runOnUiThread(() -> panels = found);
        }, "read-panels").start();
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

    /**
     * Sends a key, holding any modifier down as a real key rather than as a flag.
     *
     * A meta bit on a synthetic KeyEvent is not what a keyboard does and not what the browser
     * engine believes. A real Ctrl+C is four events -- Ctrl down, C down, C up, Ctrl up -- and
     * the engine tracks the modifier from the first of them. Passing META_CTRL_ON on a lone C
     * is a C, which is why the key row's Ctrl latch lit up and then did nothing: the report was
     * about the Commands button, but the same mistake was under every chord in the app.
     *
     * The modifiers are released in the reverse order they were pressed, which is the order a
     * hand lets go of them in and the order the engine expects.
     */
    @Override public void key(int keyCode, int metaState) {
        if (web == null) return;
        web.requestFocus();
        long now = android.os.SystemClock.uptimeMillis();
        int[] modifiers = {
                (metaState & KeyEvent.META_CTRL_ON) != 0 ? KeyEvent.KEYCODE_CTRL_LEFT : 0,
                (metaState & KeyEvent.META_ALT_ON) != 0 ? KeyEvent.KEYCODE_ALT_LEFT : 0,
                (metaState & KeyEvent.META_SHIFT_ON) != 0 ? KeyEvent.KEYCODE_SHIFT_LEFT : 0};
        for (int modifier : modifiers) {
            if (modifier != 0) send(now, KeyEvent.ACTION_DOWN, modifier, metaState);
        }
        send(now, KeyEvent.ACTION_DOWN, keyCode, metaState);
        send(now, KeyEvent.ACTION_UP, keyCode, metaState);
        for (int i = modifiers.length - 1; i >= 0; i--) {
            if (modifiers[i] != 0) send(now, KeyEvent.ACTION_UP, modifiers[i], metaState);
        }
    }

    private void send(long when, int action, int keyCode, int metaState) {
        if (web == null) return;
        web.dispatchKeyEvent(new KeyEvent(when, when, action, keyCode, 0, metaState));
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
                    if (waitingLine != null) {
                        waitingLine.setText(advice != null ? advice
                                : "Nothing in Linux was lost.");
                    }
                    if (retry != null) retry.setVisibility(View.VISIBLE);
                    Dialogs.details(WorkspaceActivity.this, "The editor did not start",
                            advice != null ? advice
                                    : "Nothing in Linux was lost. Tap Open the editor again.",
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
