package com.pocketdesk;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
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
import android.widget.ProgressBar;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

/**
 * The Linux computer's screen, with one row of controls around it.
 *
 * One bar, not two: Home, the computer's status, and then one button per category -- Screen,
 * the pointer, Keyboard, Keys, Window. A category button opens its choices as a vertical list,
 * so nothing is spread along a strip that has to be scrolled to be read. The bar sits at the
 * top or the bottom, the owner's choice, and the row of special keys appears only when asked
 * for. Full screen hides all of it behind one draggable chip.
 */
public final class DesktopActivity extends Activity implements KeyboardInputView.Listener {
    private static final int MENU_FIT = 1, MENU_ZOOM_IN = 2, MENU_ZOOM_OUT = 3, MENU_ROTATE = 4,
            MENU_FULL_SCREEN = 5, MENU_BAR_POSITION = 6, MENU_VOLUME_UP = 7, MENU_VOLUME_DOWN = 8,
            MENU_VOLUME_MUTE = 9, MENU_CLOSE = 10, MENU_FORCE_CLOSE = 11, MENU_SWITCH = 12, MENU_ALL_WINDOWS = 13,
            MENU_MINIMISE_ALL = 14, MENU_PASTE = 15, MENU_APPS = 16, MENU_PHONE_FILES = 17,
            MENU_RELOAD = 18, MENU_FIT_WINDOW = 19, MENU_MINIMISE = 20, MENU_MICROPHONE = 21;
    /** The request code the microphone prompt comes back on; above AppLock's own codes. */
    private static final int REQUEST_MICROPHONE = 4711;

    private SharedPreferences preferences;
    private FrameLayout outer;
    private LinearLayout column;
    private HorizontalScrollView bar;
    private HorizontalScrollView keyRow;
    /** The line between the desktop and the bar; it lifts with the bar when the keyboard opens. */
    private View barDivider;
    private VncView desktop;
    private TextView status;
    private Button pointerButton;
    private Button keysButton;
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
    private boolean controlsAtTop;
    private boolean keyRowShown;
    /** The Linux computer's sound, streamed to the phone's speaker while this screen is open. */
    private final AudioBridge audio = new AudioBridge();
    private final MicBridge microphone = new MicBridge(this);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            preferences = getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE);
            // The bar sits at the bottom unless the owner moved it: that is where a thumb is.
            controlsAtTop = "top".equals(preferences.getString(ContainerRuntime.KEY_CONTROLS_AT, "bottom"));
            keyRowShown = preferences.getBoolean(ContainerRuntime.KEY_KEY_ROW, false);
            applyOrientation();
            // The volume keys change the media volume here, which is what the desktop's sound
            // plays at. Without this they did nothing on this screen.
            setVolumeControlStream(AudioManager.STREAM_MUSIC);
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
            AppLock.applyWindowSecurity(this);
            audio.setSocketPath(new java.io.File(ContainerRuntime.rootfs(this),
                    "home/coder/.pocketdesk/audio.sock").getAbsolutePath());
            audio.start();
        } catch (Throwable error) {
            // Going back to the home screen with the reason recorded beats a crash loop.
            Crash.save(this, error);
            finish();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        AppLock.applyWindowSecurity(this);
        // The lock covers this screen too: the desktop, with every AI app signed in, is the
        // one screen that matters most.
        if (outer != null && AppLock.isLocked(this)) {
            AppLock.show(this, outer, null);
            // The phone keyboard, if it was open, must not keep typing into the desktop.
            InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (input != null && keyboardInput != null) {
                input.hideSoftInputFromWindow(keyboardInput.getWindowToken(), 0);
            }
        }
    }

    @Override protected void onStop() {
        super.onStop();
        // A microphone must never outlive the screen that turned it on. Leaving the desktop --
        // to another app, to the lock screen, to Home -- stops recording, every time.
        if (microphone.isRunning()) {
            microphone.stop();
            Toast.makeText(this, "Microphone off: the desktop screen was left.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** True while the locked screen covers the desktop: keys and taps stop here. */
    private boolean lockedNow() {
        return outer != null && AppLock.showing(outer);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        if (AppLock.handleResult(this, outer, request, result, null)) return;
        super.onActivityResult(request, result, data);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] granted) {
        super.onRequestPermissionsResult(request, permissions, granted);
        if (request != REQUEST_MICROPHONE) return;
        if (granted.length > 0 && granted[0] == PackageManager.PERMISSION_GRANTED) {
            startMicrophone();
        } else {
            Toast.makeText(this, "Without microphone permission the Linux computer has no "
                    + "microphone. You can allow it later from the phone's app settings.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The phone's microphone, handed to the Linux computer while this screen is open.
     *
     * Off at every start, asked for the first time it is used, and stopped the moment this
     * screen is left -- the three things that make a microphone on someone else's computer
     * something they can trust rather than something they have to watch.
     */
    private void toggleMicrophone() {
        if (microphone.isRunning()) {
            microphone.stop();
            Toast.makeText(this, "Microphone off.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!MicBridge.available(this)) {
            showMessage("No microphone yet", "The desktop makes the microphone when it starts. "
                    + "This computer was set up by an earlier version: stop the desktop and open "
                    + "it again, and the microphone will be there.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            return;
        }
        startMicrophone();
    }

    /** One themed dialog, in the viewer's own style, for the microphone's few honest answers. */
    private void showMessage(String title, String text) {
        new AlertDialog.Builder(this, R.style.Theme_PocketDesk_Dialog)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton("OK", null)
                .show();
    }

    private void startMicrophone() {
        microphone.start();
        String problem = microphone.problem();
        if (problem != null) {
            showMessage("The microphone did not start", problem);
            return;
        }
        Toast.makeText(this, "Microphone on. It stops when you leave this screen.",
                Toast.LENGTH_LONG).show();
    }

    @Override protected void onDestroy() {
        finished = true;
        audio.stop();
        microphone.stop();
        if (desktop != null && desktop.getClient() != null) desktop.getClient().close();
        if (connectionThread != null) connectionThread.interrupt();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
    }

    private View buildScreen() {
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Color.rgb(5, 7, 17));

        // ---- The control bar ----------------------------------------------------------------
        bar = strip();
        LinearLayout barRow = (LinearLayout) bar.getChildAt(0);

        Button home = toolButton("Home", R.drawable.ic_arrow_back);
        home.setContentDescription("Back to PocketDesk home");
        home.setOnClickListener(v -> finish());
        barRow.addView(home, barItem(88));

        status = Ui.text(this, "Starting…", 12.5f, Color.rgb(194, 202, 230));
        status.setBackground(Ui.tappable(this, Ui.background(Color.rgb(24, 31, 61), 12, this), true));
        status.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setGravity(Gravity.CENTER);
        status.setContentDescription("Linux computer status. Tap for details.");
        status.setOnClickListener(v -> showDetails());
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 122), ViewGroup.LayoutParams.MATCH_PARENT);
        statusLp.setMarginEnd(Ui.dp(this, 5));
        barRow.addView(status, statusLp);

        Button screen = toolButton("Screen ▾", R.drawable.ic_fit);
        screen.setContentDescription("Screen: fit, zoom, rotate, full screen, bar position");
        screen.setOnClickListener(v -> showScreenMenu(v));
        barRow.addView(screen, barItem(104));

        pointerButton = toolButton("Finger", R.drawable.ic_touch);
        pointerButton.setContentDescription("Switch between finger and mouse control");
        pointerButton.setOnClickListener(v -> togglePointerMode());
        barRow.addView(pointerButton, barItem(96));

        Button keyboard = toolButton("Keyboard", R.drawable.ic_keyboard);
        keyboard.setContentDescription("Open the phone keyboard");
        keyboard.setOnClickListener(v -> showKeyboard());
        barRow.addView(keyboard, barItem(116));

        keysButton = toolButton("Keys", R.drawable.ic_terminal);
        keysButton.setContentDescription("Show or hide the row of special keys");
        keysButton.setOnClickListener(v -> setKeyRowShown(!keyRowShown));
        barRow.addView(keysButton, barItem(82));

        Button window = toolButton("Window ▾", R.drawable.ic_desktop);
        window.setContentDescription("Window: switch apps, minimise, close, force close, paste");
        window.setOnClickListener(v -> showWindowMenu(v));
        barRow.addView(window, barItem(112));

        // ---- The row of special keys, shown on request ----------------------------------------
        keyRow = strip();
        LinearLayout keys = (LinearLayout) keyRow.getChildAt(0);
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
        addKey(keys, "PgUp", 0xff55);
        addKey(keys, "PgDn", 0xff56);

        // ---- The desktop itself -----------------------------------------------------------------
        desktop = new VncView(this);
        desktop.setPointerMode("mouse".equals(preferences.getString(ContainerRuntime.KEY_POINTER_MODE, "finger"))
                ? VncView.PointerMode.TOUCHPAD : VncView.PointerMode.DIRECT);
        stylePointerButton();
        desktop.setStateListener((text, connected) -> {
            // The bar has room for the headline only; the full sentence is on the card.
            text = text.contains(". ") ? text.substring(0, text.indexOf(". ")) : text;
            status.setText(text);
            status.setTextColor(connected ? Color.rgb(170, 190, 255) : Color.rgb(239, 170, 57));
        });

        keyboardInput = new KeyboardInputView(this);
        keyboardInput.setListener(this);
        keyboardInput.setAlpha(0.01f);

        layoutBars();

        // A floating chip is the only thing left on screen in full-screen mode, so the bar can
        // always be brought back.
        outer = new FrameLayout(this);
        outer.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout overlay = new FrameLayout(this);
        overlay.addView(keyboardInput, new FrameLayout.LayoutParams(1, 1));
        outer.addView(overlay, new FrameLayout.LayoutParams(1, 1));
        FrameLayout.LayoutParams volumeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        volumeLp.topMargin = Ui.dp(this, 74);
        outer.addView(buildVolumePanel(), volumeLp);
        restoreBars = toolButton("Controls", R.drawable.ic_settings);
        restoreBars.setVisibility(View.GONE);
        // Solid rather than see-through: while the bar is hidden this chip is the only way
        // back to it, so it has to stay readable over a bright window.
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

    /** A 56 dp horizontal strip whose buttons measure the 48 dp a finger needs. */
    private HorizontalScrollView strip() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setBackgroundColor(Color.rgb(15, 19, 39));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        scroller.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return scroller;
    }

    /** Puts the bar, the key row and the desktop in the order the owner chose. */
    private void layoutBars() {
        column.removeAllViews();
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56));
        LinearLayout.LayoutParams desktopLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        keyRow.setVisibility(keyRowShown ? View.VISIBLE : View.GONE);
        barDivider = hairline();
        if (controlsAtTop) {
            column.addView(bar, stripLp);
            column.addView(barDivider, dividerLp());
            column.addView(keyRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));
            column.addView(desktop, desktopLp);
        } else {
            column.addView(desktop, desktopLp);
            column.addView(keyRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));
            column.addView(barDivider, dividerLp());
            column.addView(bar, stripLp);
        }
        styleToggle(keysButton, keyRowShown);
    }

    /** A one-pixel line between the desktop and the control bar, so the bar reads as a shelf. */
    private View hairline() {
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(35, 48, 74));
        return line;
    }

    private LinearLayout.LayoutParams dividerLp() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1)));
    }

    private void setKeyRowShown(boolean shown) {
        keyRowShown = shown;
        preferences.edit().putBoolean(ContainerRuntime.KEY_KEY_ROW, shown).apply();
        keyRow.setVisibility(shown ? View.VISIBLE : View.GONE);
        styleToggle(keysButton, shown);
    }

    private void styleToggle(Button button, boolean active) {
        button.setBackground(Ui.tappable(this, active
                ? Ui.outlined(Color.rgb(35, 42, 73), Color.rgb(122, 155, 255), 12, this)
                : Ui.background(Color.rgb(35, 42, 73), 12, this), true));
    }

    private void showScreenMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.setForceShowIcon(true);
        Menu items = menu.getMenu();
        items.add(0, MENU_FIT, 0, "Fit: whole desktop, centred").setIcon(R.drawable.ic_fit);
        items.add(0, MENU_ZOOM_IN, 1, "Zoom in (" + desktop.zoomPercent() + " %)").setIcon(R.drawable.ic_fullscreen);
        items.add(0, MENU_ZOOM_OUT, 2, "Zoom out").setIcon(R.drawable.ic_fullscreen);
        items.add(0, MENU_ROTATE, 3, "Rotate").setIcon(R.drawable.ic_rotate);
        items.add(0, MENU_FULL_SCREEN, 4, "Full screen: hide the controls").setIcon(R.drawable.ic_desktop);
        items.add(0, MENU_BAR_POSITION, 5, controlsAtTop ? "Move controls to the bottom" : "Move controls to the top")
                .setIcon(R.drawable.ic_settings);
        items.add(0, MENU_VOLUME_UP, 6, "Media volume up").setIcon(R.drawable.ic_volume);
        items.add(0, MENU_VOLUME_DOWN, 7, "Media volume down").setIcon(R.drawable.ic_volume);
        AudioManager sound = (AudioManager) getSystemService(AUDIO_SERVICE);
        boolean silent = sound != null
                && (sound.isStreamMute(AudioManager.STREAM_MUSIC)
                    || sound.getStreamVolume(AudioManager.STREAM_MUSIC) == 0);
        items.add(0, MENU_VOLUME_MUTE, 8, silent ? "Media volume: unmute" : "Media volume: mute")
                .setIcon(R.drawable.ic_volume);
        items.add(0, MENU_MICROPHONE, 9, microphone.isRunning()
                        ? "Microphone: turn off" : "Microphone: let the computer hear you")
                .setIcon(R.drawable.ic_volume);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_FIT:
                    desktop.resetView();
                    Toast.makeText(this, "The whole desktop is on screen", Toast.LENGTH_SHORT).show();
                    return true;
                case MENU_ZOOM_IN: desktop.zoomBy(1.25f); return true;
                case MENU_ZOOM_OUT:
                    if (!desktop.zoomBy(1f / 1.25f)) {
                        Toast.makeText(this, "Already showing the whole desktop", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                case MENU_ROTATE: toggleOrientation(); return true;
                case MENU_FULL_SCREEN: setBarsHidden(true); return true;
                case MENU_BAR_POSITION:
                    controlsAtTop = !controlsAtTop;
                    preferences.edit().putString(ContainerRuntime.KEY_CONTROLS_AT, controlsAtTop ? "top" : "bottom").apply();
                    layoutBars();
                    return true;
                case MENU_VOLUME_UP: adjustVolume(AudioManager.ADJUST_RAISE); return true;
                case MENU_VOLUME_DOWN: adjustVolume(AudioManager.ADJUST_LOWER); return true;
                case MENU_VOLUME_MUTE: adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE); return true;
                case MENU_MICROPHONE: toggleMicrophone(); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void showWindowMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.setForceShowIcon(true);
        Menu items = menu.getMenu();
        // Switching comes first: on a phone that is the common act, and the one item that
        // throws work away should not be the one under the thumb.
        items.add(0, MENU_SWITCH, 0, "Switch to the next app").setIcon(R.drawable.ic_switch);
        items.add(0, MENU_ALL_WINDOWS, 1, "All open apps").setIcon(R.drawable.ic_apps);
        items.add(0, MENU_APPS, 2, "Apps menu: every installed app").setIcon(R.drawable.ic_apps);
        items.add(0, MENU_FIT_WINDOW, 3, "Fit this window to the screen").setIcon(R.drawable.ic_fit);
        items.add(0, MENU_MINIMISE, 4, "Minimise this window").setIcon(R.drawable.ic_desktop);
        items.add(0, MENU_MINIMISE_ALL, 5, "Minimise all: show the desktop").setIcon(R.drawable.ic_desktop);
        items.add(0, MENU_CLOSE, 6, "Close this window").setIcon(R.drawable.ic_close);
        items.add(0, MENU_FORCE_CLOSE, 7, "Force close (stuck app)").setIcon(R.drawable.ic_stop);
        items.add(0, MENU_PASTE, 8, "Paste from the phone").setIcon(R.drawable.ic_download);
        items.add(0, MENU_PHONE_FILES, 9, "Phone files").setIcon(R.drawable.ic_phone);
        items.add(0, MENU_RELOAD, 10, "Reload the screen").setIcon(R.drawable.ic_rotate);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                // Alt+F4 and Alt+Tab are Openbox's own bindings; Super+F4, Super+Tab, Super+D,
                // Super+A, Super+P and Super+R are set up by pocketdesk-menu.
                case MENU_CLOSE: chord(0xffe9, 0xffc1); return true;
                case MENU_FORCE_CLOSE: chord(0xffeb, 0xffc1); return true;
                case MENU_SWITCH: chord(0xffe9, 0xff09); return true;
                case MENU_ALL_WINDOWS: chord(0xffeb, 0xff09); return true;
                case MENU_MINIMISE_ALL: chord(0xffeb, 'd'); return true;
                case MENU_PASTE: pasteClipboard(); return true;
                case MENU_APPS: chord(0xffeb, 'a'); return true;
                case MENU_PHONE_FILES: chord(0xffeb, 'p'); return true;
                case MENU_RELOAD: chord(0xffeb, 'r'); return true;
                case MENU_FIT_WINDOW: chord(0xffeb, 'f'); return true;
                case MENU_MINIMISE: chord(0xffeb, 'm'); return true;
                default: return false;
            }
        });
        menu.show();
    }

    /**
     * Media volume, one step, with the level shown on the desktop itself.
     *
     * Everything the Linux computer plays arrives on the phone as media audio -- there is no
     * call, ring or alarm stream in this app at all -- so this is the one volume that matters,
     * and the indicator says so rather than leaving the owner guessing which slider moved.
     */
    private void adjustVolume(int direction) {
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (manager == null) return;
        try {
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
        } catch (RuntimeException blocked) {
            // Do Not Disturb can put media under the notification policy, and Android then
            // refuses the change to an app without policy access instead of ignoring it.
            Toast.makeText(this, "Do Not Disturb is holding this phone's media volume. Turn it "
                    + "off in the phone's settings to change the desktop's sound.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        showVolume(manager);
    }

    private TextView volumeChip;
    private TextView volumeNote;
    private ProgressBar volumeBar;
    private LinearLayout volumePanel;
    private final Runnable hideVolume = () -> {
        if (volumePanel != null) volumePanel.animate().alpha(0f).setDuration(220)
                .withEndAction(() -> { if (volumePanel != null) volumePanel.setVisibility(View.GONE); }).start();
    };

    /** "Media volume  ·  60 %", a bar and the step, on the desktop, for a second and a half. */
    private void showVolume(AudioManager manager) {
        int max = Math.max(1, manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int step = manager.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean silent = step == 0 || manager.isStreamMute(AudioManager.STREAM_MUSIC);
        int percent = silent ? 0 : Math.round(step * 100f / max);
        if (volumePanel == null || outer == null) return;
        volumeChip.setText(silent ? "Media volume  ·  silent"
                : "Media volume  ·  " + percent + " %");
        volumeNote.setText(silent
                ? "Volume up to hear the desktop again"
                : "Step " + step + " of " + max + " — the desktop plays as media audio");
        volumeBar.setProgress(percent);
        volumePanel.setVisibility(View.VISIBLE);
        volumePanel.animate().cancel();
        volumePanel.setAlpha(1f);
        volumePanel.removeCallbacks(hideVolume);
        volumePanel.postDelayed(hideVolume, 1600L);
    }

    /** The indicator itself: built once, hidden until a volume key is pressed. */
    private View buildVolumePanel() {
        volumePanel = new LinearLayout(this);
        volumePanel.setOrientation(LinearLayout.VERTICAL);
        volumePanel.setBackground(Ui.outlined(
                Color.argb(242, 15, 21, 44), Color.rgb(58, 74, 130), 14, this));
        volumePanel.setElevation(Ui.dp(this, 6));
        int pad = Ui.dp(this, 14);
        volumePanel.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 12));
        volumeChip = Ui.bold(this, "Media volume", 14, Color.rgb(230, 236, 247));
        volumePanel.addView(volumeChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        volumeBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        volumeBar.setMax(100);
        volumeBar.setProgressTintList(ColorStateList.valueOf(Color.rgb(122, 155, 255)));
        volumeBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(44, 54, 96)));
        LinearLayout.LayoutParams barLp =
                new LinearLayout.LayoutParams(Ui.dp(this, 188), Ui.dp(this, 7));
        barLp.topMargin = Ui.dp(this, 9);
        volumePanel.addView(volumeBar, barLp);
        volumeNote = Ui.text(this, "", 11, Color.rgb(158, 172, 208));
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = Ui.dp(this, 7);
        volumePanel.addView(volumeNote, noteLp);
        volumePanel.setVisibility(View.GONE);
        return volumePanel;
    }

    /** What the status label opens: the plain facts about this session and how to drive it. */
    private void showDetails() {
        boolean mouse = desktop.getPointerMode() == VncView.PointerMode.TOUCHPAD;
        String text = "Linux computer: Ubuntu 24.04 LTS on this phone's own processor, inside "
                + "this app — a container, not a virtual machine. The desktop is Openbox for "
                + "the windows, with the tint2 bar along the bottom.\n\n"
                + "Screen: " + desktop.desktopSize() + " pixels, the size of this display, so "
                + "the whole desktop fits at 100 %. Zoom " + desktop.zoomPercent() + " %. Pinch, "
                + "or Screen → Zoom, to look closer; Fit brings it all back.\n\n"
                + "Pointer: " + (mouse ? "Mouse" : "Finger") + ".\n"
                + "Finger — tap where you touch, swipe to scroll (a fast swipe keeps going), "
                + "hold for a right-click; the hand shows where the pointer is.\n"
                + "Mouse — drag anywhere to move the arrow, tap to click, hold to right-click, "
                + "two fingers to scroll, tap then press-and-move to drag.\n\n"
                + "Keyboard opens the phone keyboard; Keys adds Esc, Tab, Ctrl, arrows and more. "
                + "Window switches between open apps, minimises or closes the one in front, "
                + "fits a stray window back to the screen, opens the apps menu or Phone files, "
                + "and pastes from the phone.\n\n"
                + "Several apps at once: one AI app at a time, plus Files, the Terminal and a "
                + "browser page — four windows in all. Every open window has a button on the bar "
                + "at the bottom of the desktop: tap to switch, hold to minimise. Window → All "
                + "open apps lists them by name, with no limit.\n\n"
                + "Sound: everything the computer plays comes out of this phone as MEDIA audio — "
                + "there is no call, ring or alarm sound in PocketDesk at all. The phone's volume "
                + "keys set it while this screen is open and show the level, and Screen → Media "
                + "volume does the same from the menu. Inside the computer, Tools → Volume and "
                + "sound balances one app against another; the phone still decides how loud it "
                + "ends up. Screen → Microphone hands the phone's microphone to the computer; it "
                + "is off at every start and stops the moment you leave this screen.\n\n"
                + "Super+Space takes an appshot — the window in front, its words read, pasted "
                + "straight into whichever AI app is open.\n\n"
                + "Stopping the computer keeps everything: apps stay signed in and files stay "
                + "where they are, so the next open continues from here.";
        new AlertDialog.Builder(this, R.style.Theme_PocketDesk_Dialog)
                .setTitle(status.getText())
                .setMessage(text)
                .setPositiveButton("OK", null)
                .show();
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
        bar.setVisibility(hidden ? View.GONE : View.VISIBLE);
        keyRow.setVisibility(hidden || !keyRowShown ? View.GONE : View.VISIBLE);
        restoreBars.setVisibility(hidden ? View.VISIBLE : View.GONE);
    }

    /** A slow phone's first desktop start can take well over a minute, so wait that long. */
    private static final int CONNECT_ATTEMPTS = 600;

    private void connectWithRetry() {
        connectionThread = new Thread(() -> {
            String lastError = "The Linux computer did not come up. Go back and open it again.";
            long startedAt = SystemClock.elapsedRealtime();
            for (int attempt = 0; attempt < CONNECT_ATTEMPTS && !finished; attempt++) {
                if (!VncClient.canConnect(vncSocketPath()) && !VncClient.canConnect("127.0.0.1", 5901, 250)) {
                    // The service's "running" flag is set late and cleared early, so it is not a
                    // reliable failure signal while starting up: reading it as one is what put
                    // "Desktop stopped" on a desktop that was still on its way. The wait simply
                    // runs its course now, and the only thing reported is how long it has been.
                    if (attempt % 8 == 0) {
                        long seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000L;
                        desktop.onDisconnected(seconds < 25
                                ? "Starting your Linux computer… " + seconds + "s"
                                : "Starting your Linux computer… " + seconds
                                        + "s. The first start after an update is the slow one — "
                                        + "please keep waiting.");
                    }
                    SystemClock.sleep(250);
                    continue;
                }
                VncClient client = new VncClient("127.0.0.1", 5901, vncSocketPath(), desktop);
                desktop.setClient(client);
                try {
                    client.connectAndRun();
                    lastError = "The Linux computer was stopped";
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

    /**
     * Where the desktop's private socket lives, inside this app's own storage. The desktop
     * script creates it there; nothing outside this app can open it. Null is never returned --
     * when the socket is absent the client falls back to the old local port by itself.
     */
    private String vncSocketPath() {
        return new java.io.File(ContainerRuntime.rootfs(this), "home/coder/.pocketdesk/vnc.sock")
                .getAbsolutePath();
    }

    private void togglePointerMode() {
        VncView.PointerMode next = desktop.getPointerMode() == VncView.PointerMode.TOUCHPAD
                ? VncView.PointerMode.DIRECT : VncView.PointerMode.TOUCHPAD;
        desktop.setPointerMode(next);
        boolean mouse = next == VncView.PointerMode.TOUCHPAD;
        preferences.edit().putString(ContainerRuntime.KEY_POINTER_MODE, mouse ? "mouse" : "finger").apply();
        stylePointerButton();
        Toast.makeText(this, mouse
                        ? "Mouse: drag to move the arrow, tap to click, two fingers to scroll"
                        : "Finger: tap where you touch, swipe to scroll, hold to right-click",
                Toast.LENGTH_SHORT).show();
    }

    /** The button shows what is on the screen: an arrow for the mouse, a hand for touch. */
    private void stylePointerButton() {
        boolean mouse = desktop.getPointerMode() == VncView.PointerMode.TOUCHPAD;
        pointerButton.setText(mouse ? "Mouse" : "Finger");
        Ui.setStartIcon(pointerButton, mouse ? R.drawable.ic_cursor : R.drawable.ic_touch,
                Color.rgb(150, 175, 255), this, 18);
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

    /**
     * Ctrl, Alt and Super hold until the next key and then let go, the way a phone's Shift
     * does: Ctrl then C is a copy, and the next letter is a plain letter again.
     */
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

    private void releaseModifiers() {
        if (ctrl) { ctrl = false; sendKey(0xffe3, false); styleModifier(ctrlButton, false); }
        if (alt) { alt = false; sendKey(0xffe9, false); styleModifier(altButton, false); }
        if (superKey) { superKey = false; sendKey(0xffeb, false); styleModifier(superButton, false); }
    }

    private void styleModifier(Button button, boolean active) {
        button.setTextColor(active ? Color.rgb(12, 18, 45) : Color.rgb(232, 236, 255));
        button.setBackground(active ? Ui.brandGradient(this, 10) : Ui.background(Color.rgb(35, 42, 73), 10, this));
    }

    /** Modifier held, key tapped, modifier released: Alt+F4, Alt+Tab, Super+D. */
    private void chord(int modifier, int keysym) {
        VncView.lastInteractionAt = System.currentTimeMillis();
        sendKey(modifier, true);
        sendKey(keysym, true);
        sendKey(keysym, false);
        sendKey(modifier, false);
    }

    @Override public void typeCodePoint(int codePoint) {
        if (lockedNow()) return;
        VncView.lastInteractionAt = System.currentTimeMillis();
        VncClient client = desktop.getClient();
        if (client != null) client.typeCodePoint(codePoint);
        releaseModifiers();
    }

    @Override public void specialKey(int keysym) {
        if (lockedNow()) return;
        // Typing is using the desktop just as much as touching it is.
        VncView.lastInteractionAt = System.currentTimeMillis();
        sendKey(keysym, true);
        sendKey(keysym, false);
        releaseModifiers();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Taken before the window's own volume handling so the desktop can show the level and
        // name the stream. Long-press repeats arrive here too, so holding the key still ramps.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            adjustVolume(AudioManager.ADJUST_RAISE);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            adjustVolume(AudioManager.ADJUST_LOWER);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return true;                        // swallow the pair, or the system panel appears
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
        // The volume keys belong to the phone, in front of the lock as much as behind it:
        // never an X keysym, never swallowed.
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);
        }
        if (lockedNow()) return true;
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
        // Every key the owner presses -- the on-screen keyboard, the toolbar row, a paired
        // Bluetooth keyboard through dispatchKeyEvent -- is the owner being here. Without this
        // stamp, Smart stopping counted a two-hour typing session as idle and closed it.
        VncView.lastInteractionAt = System.currentTimeMillis();
        VncClient client = desktop.getClient();
        if (client != null) client.sendKey(keysym, down);
    }

    private void pasteClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (!clipboard.hasPrimaryClip()) {
            Toast.makeText(this, "Nothing copied on the phone yet", Toast.LENGTH_SHORT).show();
            return;
        }
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

    /** Keeps the bar clear of the status bar and the gesture bar, whichever end it sits at. */
    private void applySystemInsets(View root) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            // The keyboard's height goes to the viewer, which slides up under it; it never
            // reaches the layout, so the Linux desktop is never resized for it.
            int ime = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(android.view.WindowInsets.Type.ime()).bottom : 0;
            if (desktop != null) desktop.setKeyboardInset(ime);
            // The controls ride above the keyboard instead of hiding under it. A translation,
            // not a layout change: resizing this window would resize the Linux desktop itself,
            // which is the very thing adjustNothing is here to prevent.
            float lift = controlsAtTop ? 0f : -ime;
            if (bar != null) bar.setTranslationY(lift);
            if (keyRow != null) keyRow.setTranslationY(lift);
            if (barDivider != null) barDivider.setTranslationY(lift);
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
