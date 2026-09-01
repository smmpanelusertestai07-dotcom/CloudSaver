package com.pocketdesk;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;

public final class DesktopActivity extends Activity implements KeyboardInputView.Listener {
    private VncView desktop;
    private TextView status;
    private Button pointerMode;
    private Button zoomLabel;
    private Button fitButton;
    private LinearLayout toolbarRow;
    private View keyRow;
    private Button restoreBars;
    private Button ctrlButton;
    private Button altButton;
    private Button superButton;
    private KeyboardInputView keyboardInput;
    private Thread connectionThread;
    private volatile boolean finished;
    private boolean ctrl;
    private boolean alt;
    private boolean superKey;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            applyOrientation();
            // The phone's own clock, battery and signal stay visible: hiding them was hiding
            // exactly the information this app is otherwise careful to show.
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (Build.VERSION.SDK_INT < 35) {
                getWindow().setStatusBarColor(Color.rgb(15, 19, 39));
                getWindow().setNavigationBarColor(Color.rgb(5, 7, 17));
            }
            View content = buildScreen();
            setContentView(content);
            applySystemInsets(content);
            connectWithRetry();
        } catch (Throwable error) {
            // Going back to the home screen with the reason recorded beats a crash loop.
            Crash.save(this, error);
            finish();
        }
    }

    @Override protected void onDestroy() {
        finished = true;
        if (desktop != null && desktop.getClient() != null) desktop.getClient().close();
        if (connectionThread != null) connectionThread.interrupt();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
    }

    private View buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(5, 7, 17));

        // Everything sits in one horizontally scrollable strip, so no control is ever cut off
        // on a narrow phone while landscape still shows the whole set at once.
        HorizontalScrollView toolbar = new HorizontalScrollView(this);
        toolbar.setHorizontalScrollBarEnabled(false);
        toolbar.setBackgroundColor(Color.rgb(15, 19, 39));
        toolbarRow = new LinearLayout(this);
        toolbarRow.setGravity(Gravity.CENTER_VERTICAL);
        toolbarRow.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        toolbar.addView(toolbarRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));

        Button back = toolButton("Home", R.drawable.ic_arrow_back);
        back.setContentDescription("Back to PocketDesk home");
        back.setOnClickListener(v -> finish());
        toolbarRow.addView(back, barItem(92));

        status = Ui.text(this, "Starting…", 12.5f, Color.rgb(194, 202, 230));
        status.setSingleLine(true);
        // A fixed-width label with no ellipsis cut words in half: "Starting your Linu".
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 92), ViewGroup.LayoutParams.MATCH_PARENT);
        statusLp.setMarginStart(Ui.dp(this, 8));
        statusLp.setMarginEnd(Ui.dp(this, 8));
        toolbarRow.addView(status, statusLp);

        Button zoomOut = toolButton("−");
        zoomOut.setTextSize(19);
        zoomOut.setContentDescription("Zoom out");
        zoomOut.setOnClickListener(v -> desktop.zoomBy(1f / 1.25f));
        toolbarRow.addView(zoomOut, barItem(46));

        zoomLabel = toolButton("100%");
        zoomLabel.setContentDescription("Reset zoom");
        zoomLabel.setOnClickListener(v -> desktop.resetView());
        toolbarRow.addView(zoomLabel, barItem(58));

        Button zoomIn = toolButton("+");
        zoomIn.setTextSize(19);
        zoomIn.setContentDescription("Zoom in");
        zoomIn.setOnClickListener(v -> desktop.zoomBy(1.25f));
        toolbarRow.addView(zoomIn, barItem(46));

        fitButton = toolButton("Fill", R.drawable.ic_fullscreen);
        fitButton.setOnClickListener(v -> {
            desktop.setFillMode(!desktop.isFillMode());
            fitButton.setText(desktop.isFillMode() ? "Fill" : "Fit");
        });
        toolbarRow.addView(fitButton, barItem(96));

        pointerMode = toolButton("Mouse", R.drawable.ic_mouse);
        pointerMode.setOnClickListener(v -> togglePointerMode());
        toolbarRow.addView(pointerMode, barItem(124));

        Button keyboard = toolButton("Keyboard", R.drawable.ic_keyboard);
        keyboard.setContentDescription("Open the phone keyboard");
        keyboard.setOnClickListener(v -> showKeyboard());
        toolbarRow.addView(keyboard, barItem(126));

        Button rotate = toolButton("Rotate", R.drawable.ic_rotate);
        rotate.setContentDescription("Turn the desktop between portrait and landscape");
        rotate.setOnClickListener(v -> toggleOrientation());
        toolbarRow.addView(rotate, barItem(104));

        Button hideBars = toolButton("Full screen", R.drawable.ic_fit);
        hideBars.setOnClickListener(v -> setBarsHidden(true));
        toolbarRow.addView(hideBars, barItem(132));

        desktop = new VncView(this);
        desktop.setStateListener((text, connected) -> {
            // The toolbar has room for the headline only; the full sentence is on the card.
            text = text.contains(". ") ? text.substring(0, text.indexOf(". ")) : text;
            status.setText(text);
            status.setTextColor(connected ? Ui.SUCCESS : Color.rgb(239, 170, 57));
        });
        root.addView(desktop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setFillViewport(false);
        scroller.setBackgroundColor(Color.rgb(15, 19, 39));
        LinearLayout keys = new LinearLayout(this);
        keys.setGravity(Gravity.CENTER_VERTICAL);
        keys.setPadding(Ui.dp(this, 5), Ui.dp(this, 4), Ui.dp(this, 5), Ui.dp(this, 4));
        scroller.addView(keys, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        keyRow = scroller;
        root.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));

        addKey(keys, "Esc", 0xff1b);
        addKey(keys, "Tab", 0xff09);
        ctrlButton = addModifier(keys, "Ctrl", 0);
        altButton = addModifier(keys, "Alt", 1);
        superButton = addModifier(keys, "Super", 2);
        addKey(keys, "←", 0xff51);
        addKey(keys, "↑", 0xff52);
        addKey(keys, "↓", 0xff54);
        addKey(keys, "→", 0xff53);
        addKey(keys, "Enter", 0xff0d);
        addKey(keys, "Back", 0xff08);
        addKey(keys, "Del", 0xffff);
        addKey(keys, "Home", 0xff50);
        addKey(keys, "End", 0xff57);
        Button paste = toolButton("Paste", R.drawable.ic_apps);
        paste.setOnClickListener(v -> pasteClipboard());
        keys.addView(paste, keyLayout(92));

        keyboardInput = new KeyboardInputView(this);
        keyboardInput.setListener(this);
        keyboardInput.setAlpha(0.01f);
        FrameLayout overlay = new FrameLayout(this);
        overlay.addView(keyboardInput, new FrameLayout.LayoutParams(1, 1));
        root.addView(overlay, new LinearLayout.LayoutParams(1, 1));

        desktop.setZoomListener((percent, fill) -> {
            zoomLabel.setText(percent + "%");
            fitButton.setText(fill ? "Fill" : "Fit");
        });

        // A floating chip is the only thing left on screen in full-screen mode, so the bars can
        // always be brought back.
        FrameLayout outer = new FrameLayout(this);
        outer.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        restoreBars = toolButton("Controls", R.drawable.ic_settings);
        restoreBars.setVisibility(View.GONE);
        // Solid rather than see-through: while the bars are hidden this chip is the only way
        // back to them, so it has to stay readable over a bright window.
        restoreBars.setBackground(Ui.outlined(
                Color.rgb(28, 36, 70), Color.rgb(96, 118, 190), 12, this));
        restoreBars.setElevation(Ui.dp(this, 6));
        restoreBars.setOnClickListener(v -> setBarsHidden(false));
        FrameLayout.LayoutParams chip = new FrameLayout.LayoutParams(
                Ui.dp(this, 122), Ui.dp(this, 42), Gravity.TOP | Gravity.START);
        chip.topMargin = Ui.dp(this, 8);
        chip.leftMargin = Ui.dp(this, 8);
        outer.addView(restoreBars, chip);
        // Wherever it lands it can cover something, so it can be dragged anywhere on the screen.
        makeChipDraggable(outer);
        outer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> keepChipOnScreen(outer));
        return outer;
    }

    /** True once the chip has been dragged, after which it stays where it was put. */
    private boolean chipMoved;

    /** Keeps the chip inside the screen, and parks it top-right until it is first moved. */
    private void keepChipOnScreen(FrameLayout parent) {
        if (restoreBars == null || parent.getWidth() == 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) restoreBars.getLayoutParams();
        int width = restoreBars.getWidth() > 0 ? restoreBars.getWidth() : Ui.dp(this, 122);
        int height = restoreBars.getHeight() > 0 ? restoreBars.getHeight() : Ui.dp(this, 42);
        int maxLeft = Math.max(0, parent.getWidth() - width);
        int maxTop = Math.max(0, parent.getHeight() - height);
        int left = chipMoved ? lp.leftMargin : Math.max(0, maxLeft - Ui.dp(this, 8));
        left = Math.max(0, Math.min(left, maxLeft));
        int top = Math.max(0, Math.min(lp.topMargin, maxTop));
        if (left == lp.leftMargin && top == lp.topMargin) return;
        lp.leftMargin = left;
        lp.topMargin = top;
        restoreBars.setLayoutParams(lp);
    }

    private void makeChipDraggable(FrameLayout parent) {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        restoreBars.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private int startLeft;
            private int startTop;
            private boolean dragging;

            @Override public boolean onTouch(View view, MotionEvent event) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startLeft = lp.leftMargin;
                        startTop = lp.topMargin;
                        dragging = false;
                        return false;   // let the button keep its ripple and its tap
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) < slop) return false;
                        if (!dragging) {
                            dragging = true;
                            chipMoved = true;
                            view.setPressed(false);
                        }
                        int maxLeft = Math.max(0, parent.getWidth() - view.getWidth());
                        int maxTop = Math.max(0, parent.getHeight() - view.getHeight());
                        lp.leftMargin = Math.max(0, Math.min(startLeft + Math.round(dx), maxLeft));
                        lp.topMargin = Math.max(0, Math.min(startTop + Math.round(dy), maxTop));
                        view.setLayoutParams(lp);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!dragging) return false;   // a tap: the click listener takes it
                        dragging = false;
                        view.setPressed(false);
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private LinearLayout.LayoutParams barItem(int widthDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(this, widthDp), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMarginEnd(Ui.dp(this, 5));
        return lp;
    }

    private void setBarsHidden(boolean hidden) {
        int visibility = hidden ? View.GONE : View.VISIBLE;
        ((View) toolbarRow.getParent()).setVisibility(visibility);
        if (keyRow != null) keyRow.setVisibility(visibility);
        restoreBars.setVisibility(hidden ? View.VISIBLE : View.GONE);
    }

    /** A slow phone's first desktop start can take well over a minute, so wait that long. */
    private static final int CONNECT_ATTEMPTS = 600;

    private void connectWithRetry() {
        connectionThread = new Thread(() -> {
            String lastError = "The desktop did not come up. Go back and open it again.";
            long startedAt = SystemClock.elapsedRealtime();
            for (int attempt = 0; attempt < CONNECT_ATTEMPTS && !finished; attempt++) {
                if (!VncClient.canConnect("127.0.0.1", 5901, 250)) {
                    // The service's "running" flag is set late and cleared early, so it is not a
                    // reliable failure signal while starting up: reading it as one is what put
                    // "Desktop stopped" on a desktop that was still on its way. The wait simply
                    // runs its course now, and the only thing reported is how long it has been.
                    if (attempt % 8 == 0) {
                        long seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000L;
                        desktop.onDisconnected(seconds < 25
                                ? "Starting your Linux desktop… " + seconds + "s"
                                : "Starting your Linux desktop… " + seconds
                                        + "s. The first start after an update is the slow one — "
                                        + "please keep waiting.");
                    }
                    SystemClock.sleep(250);
                    continue;
                }
                VncClient client = new VncClient("127.0.0.1", 5901, desktop);
                desktop.setClient(client);
                try {
                    client.connectAndRun();
                    lastError = "Desktop connection ended";
                } catch (IOException error) {
                    lastError = error.getMessage() == null ? "Connection failed" : error.getMessage();
                } catch (Throwable error) {
                    // An OutOfMemoryError here used to take the whole app down. Ending the
                    // session with a message is always better than "PocketDesk keeps stopping".
                    Crash.save(DesktopActivity.this, error);
                    lastError = "Viewer ran out of memory or hit an error ("
                            + error.getClass().getSimpleName() + "). Close other apps and reopen.";
                    client.close();
                }
                if (!finished) desktop.onDisconnected(lastError);
                return;
            }
            if (!finished) desktop.onDisconnected(lastError);
        }, "pocketdesk-vnc-client");
        connectionThread.start();
    }

    private void togglePointerMode() {
        VncView.PointerMode next = desktop.getPointerMode() == VncView.PointerMode.TOUCHPAD
                ? VncView.PointerMode.DIRECT : VncView.PointerMode.TOUCHPAD;
        desktop.setPointerMode(next);
        boolean mouse = next == VncView.PointerMode.TOUCHPAD;
        // "Touchpad" and "Direct touch" named the mechanism, not what it does to the arrow.
        pointerMode.setText(mouse ? "Mouse" : "Finger");
        android.widget.Toast.makeText(this, mouse
                        ? "Mouse: drag anywhere to move the arrow, tap to click"
                        : "Finger: the arrow jumps to wherever you touch",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showKeyboard() {
        keyboardInput.requestFocus();
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        input.showSoftInput(keyboardInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private Button addKey(LinearLayout parent, String label, int keysym) {
        Button button = toolButton(label);
        button.setOnClickListener(v -> specialKey(keysym));
        parent.addView(button, keyLayout(label.length() > 3 ? 66 : 52));
        return button;
    }

    private Button addModifier(LinearLayout parent, String label, int type) {
        Button button = toolButton(label);
        button.setOnClickListener(v -> toggleModifier(type));
        parent.addView(button, keyLayout(label.length() > 4 ? 70 : 58));
        return button;
    }

    private void toggleModifier(int type) {
        if (type == 0) {
            ctrl = !ctrl;
            sendKey(0xffe3, ctrl);
            styleModifier(ctrlButton, ctrl);
        } else if (type == 1) {
            alt = !alt;
            sendKey(0xffe9, alt);
            styleModifier(altButton, alt);
        } else {
            superKey = !superKey;
            sendKey(0xffeb, superKey);
            styleModifier(superButton, superKey);
        }
    }

    private void styleModifier(Button button, boolean active) {
        button.setTextColor(active ? Color.rgb(12, 18, 45) : Color.rgb(232, 236, 255));
        button.setBackground(active ? Ui.brandGradient(this, 10) : Ui.background(Color.rgb(35, 42, 73), 10, this));
    }

    @Override public void typeCodePoint(int codePoint) {
        VncClient client = desktop.getClient();
        if (client != null) client.typeCodePoint(codePoint);
    }

    @Override public void specialKey(int keysym) {
        sendKey(keysym, true);
        sendKey(keysym, false);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
        int keysym = androidKeySym(event);
        if (keysym == 0 || desktop == null || desktop.getClient() == null) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            sendKey(keysym, true);
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            sendKey(keysym, false);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static int androidKeySym(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_ESCAPE: return 0xff1b;
            case KeyEvent.KEYCODE_TAB: return 0xff09;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return 0xff0d;
            case KeyEvent.KEYCODE_DEL: return 0xff08;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 0xffff;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0xff51;
            case KeyEvent.KEYCODE_DPAD_UP: return 0xff52;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0xff53;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0xff54;
            case KeyEvent.KEYCODE_MOVE_HOME: return 0xff50;
            case KeyEvent.KEYCODE_MOVE_END: return 0xff57;
            case KeyEvent.KEYCODE_PAGE_UP: return 0xff55;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 0xff56;
            case KeyEvent.KEYCODE_INSERT: return 0xff63;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 0xffe1;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 0xffe2;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 0xffe3;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 0xffe4;
            case KeyEvent.KEYCODE_CAPS_LOCK: return 0xffe5;
            case KeyEvent.KEYCODE_ALT_LEFT: return 0xffe9;
            case KeyEvent.KEYCODE_ALT_RIGHT: return 0xffea;
            case KeyEvent.KEYCODE_META_LEFT: return 0xffeb;
            case KeyEvent.KEYCODE_META_RIGHT: return 0xffec;
            case KeyEvent.KEYCODE_F1: return 0xffbe;
            case KeyEvent.KEYCODE_F2: return 0xffbf;
            case KeyEvent.KEYCODE_F3: return 0xffc0;
            case KeyEvent.KEYCODE_F4: return 0xffc1;
            case KeyEvent.KEYCODE_F5: return 0xffc2;
            case KeyEvent.KEYCODE_F6: return 0xffc3;
            case KeyEvent.KEYCODE_F7: return 0xffc4;
            case KeyEvent.KEYCODE_F8: return 0xffc5;
            case KeyEvent.KEYCODE_F9: return 0xffc6;
            case KeyEvent.KEYCODE_F10: return 0xffc7;
            case KeyEvent.KEYCODE_F11: return 0xffc8;
            case KeyEvent.KEYCODE_F12: return 0xffc9;
            default:
                int codePoint = event.getUnicodeChar(0);
                if (codePoint == 0) codePoint = event.getUnicodeChar();
                return codePoint <= 0xff ? codePoint : (codePoint == 0 ? 0 : 0x01000000 | codePoint);
        }
    }

    private void sendKey(int keysym, boolean down) {
        VncClient client = desktop.getClient();
        if (client != null) client.sendKey(keysym, down);
    }

    private void pasteClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (!clipboard.hasPrimaryClip()) return;
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        VncClient client = desktop.getClient();
        if (client == null || text == null) return;
        client.sendClipboard(text.toString());
        client.sendKey(0xffe3, true);
        client.sendKey('v', true);
        client.sendKey('v', false);
        client.sendKey(0xffe3, false);
    }

    private Button toolButton(String label) {
        return toolButton(label, 0);
    }

    /** Toolbar button with an icon in front of its word, matching the rest of the app. */
    private Button toolButton(String label, int iconRes) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13.5f);
        button.setTextColor(Color.rgb(232, 236, 255));
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (iconRes != 0) Ui.setStartIcon(button, iconRes, Color.rgb(150, 175, 255), this, 18);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(this, 5), 0, Ui.dp(this, 5), 0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setBackground(Ui.tappable(this, Ui.background(Color.rgb(35, 42, 73), 12, this), true));
        return button;
    }

    private LinearLayout.LayoutParams keyLayout(int widthDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, widthDp), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        return lp;
    }

    private void applyOrientation() {
        SharedPreferences preferences = getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE);
        String value = preferences.getString(ContainerRuntime.KEY_ORIENTATION, "auto");
        if ("portrait".equals(value)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
            return;
        }
        if ("landscape".equals(value)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
            return;
        }
        // FULL_SENSOR, not USER: USER obeys the phone's rotation lock, which is why turning the
        // phone did nothing until the screen was reopened. The desktop resizes itself to match,
        // so following the sensor is safe here even when the rest of the system is locked.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
    }

    private void toggleOrientation() {
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(landscape
                ? ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
    }

    /** Keeps the toolbar clear of the status bar and the key row clear of the gesture bar. */
    private void applySystemInsets(View root) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            int left;
            int right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }
}
