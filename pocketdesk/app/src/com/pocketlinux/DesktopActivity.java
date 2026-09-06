package com.pocketlinux;

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
            MENU_FULL_SCREEN = 5, MENU_BAR_POSITION = 6,
            MENU_CLOSE = 10, MENU_FORCE_CLOSE = 11, MENU_SWITCH = 12, MENU_ALL_WINDOWS = 13,
            MENU_MINIMISE_ALL = 14, MENU_PASTE = 15, MENU_APPS = 16, MENU_PHONE_FILES = 17,
            MENU_RELOAD = 18, MENU_FIT_WINDOW = 19, MENU_MINIMISE = 20, MENU_MICROPHONE = 21, MENU_PHOTO = 22,
            MENU_WIDE_WORKSPACE = 23, MENU_VOLUME_PANEL = 24, MENU_ROTATION_LOCK = 25,
            MENU_TOUCH_LOCK = 26, MENU_BIGGER = 27, MENU_AUTO_HIDE = 28, MENU_RESIZE = 29,
            MENU_CLOUD_FILE = 30;
    private static final String KEY_WIDE_WORKSPACE = "viewer_wide_workspace";
    /** The request code the microphone prompt comes back on; above AppLock's own codes. */
    private static final int REQUEST_MICROPHONE = 4711;
    /** The request code the phone's camera app comes back on. */
    private static final int REQUEST_PHOTO = 4712;

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
    private Button dragButton;
    private Button keysButton;
    private Button restoreBars;
    private Button ctrlButton;
    private Button altButton;
    private Button superButton;
    private Button shiftButton;
    private KeyboardInputView keyboardInput;
    private Thread connectionThread;
    private boolean reconnectWhenIdle;
    private volatile boolean finished;
    private volatile boolean viewerVisible;
    private final java.util.Set<Integer> hardwareKeys = new java.util.LinkedHashSet<>();
    private boolean controlsAtTop;
    private boolean keyRowShown;
    /** The Linux computer's sound, streamed to the phone's speaker while this screen is open. */
    private final AudioBridge audio = new AudioBridge();
    /** Created only after Activity.attach() has supplied a base context. */
    private MicBridge microphone;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            // Activity fields are initialised before Android attaches the base context. Creating
            // this as a field called getApplicationContext() too early and made DesktopActivity
            // fail before onCreate on Android 13, followed by a missing activity-record error.
            microphone = new MicBridge(getApplicationContext());
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
            Crash.save(this, error);
            showLaunchFailure(error);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        // onCreate can fail before it has read anything -- that is what the launch-failure screen
        // exists for -- and Android calls onStart afterwards regardless. Everything below reads
        // fields that onCreate assigns, so without this the diagnostic screen it just built is
        // replaced by a crash with no handler at all.
        if (preferences == null) return;
        // The rotation setting lives on the other screen; picking it up here is what makes the
        // change reach the computer without closing the desktop first.
        applyOrientation();
        armAutoHide();
        viewerVisible = true;
        VncClient active = desktop == null ? null : desktop.getClient();
        if (active != null) active.setUpdatesPaused(false);
        AppLock.applyWindowSecurity(this);
        // The lock covers this screen too: the desktop, with every AI app signed in, is the
        // one screen that matters most.
        if (outer != null && AppLock.isLocked(this)) {
            AppLock.show(this, outer, null);
            // The phone keyboard, if it was open, must not keep typing into the desktop.
            hideKeyboard();
        }
    }

    @Override protected void onPause() {
        releaseRemoteInput();
        super.onPause();
    }

    /**
     * Nothing posted from this screen may outlive it.
     *
     * The auto-hide timer re-posts itself while the desktop is being used, so leaving the screen
     * with it pending meant it fired several seconds later against views whose Activity had
     * stopped -- and held that window alive to do it.
     */
    private void cancelPostedWork() {
        main.removeCallbacks(hideBarsSoon);
        if (volumePanel != null) volumePanel.removeCallbacks(hideVolume);
    }

    @Override protected void onStop() {
        releaseRemoteInput();
        cancelPostedWork();
        viewerVisible = false;
        VncClient active = desktop == null ? null : desktop.getClient();
        if (active != null) active.setUpdatesPaused(true);
        super.onStop();
        // A microphone must never outlive the screen that turned it on. Leaving the desktop --
        // to another app, to the lock screen, to Home -- stops recording, every time.
        if (microphone != null && microphone.isRunning()) {
            microphone.stop();
            Toast.makeText(this, "Microphone off: the desktop screen was left.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** True while something is covering the desktop: keys and taps stop here. */
    private boolean lockedNow() {
        return touchLocked || (outer != null && AppLock.showing(outer));
    }

    /**
     * Bring a file in from the phone, or from Drive, or from any other cloud app on it.
     *
     * A cloud drive is not a folder and cannot be made into one -- Android exposes it through a
     * document provider with no path at all, so there is nothing the container could mount. The
     * picker is the way in, and it lists every cloud app on the phone next to the phone's own
     * storage. Whatever is chosen becomes an ordinary file in the computer's Cloud folder, which
     * is in the sidebar of every Open dialog inside Linux -- ChatGPT's attach, Claude's upload,
     * Cursor's open, the browser's file field.
     */
    private void addFileFromPhone() {
        if (!ContainerRuntime.isInstalled(this)) {
            showMessage("No computer yet", "Set up the Linux computer first.");
            return;
        }
        if (!CloudFiles.pick(this)) {
            showMessage("No file picker", "This phone has no app that answers a request for a "
                    + "file. Turn on Phone files in Settings and use the Phone folder instead.");
        }
    }

    /**
     * Brings the chosen files in, on a thread of its own.
     *
     * The picker deliberately lists Drive and every other cloud app, and reading from one of
     * those blocks while the provider fetches the file -- a 300 MB video, or anything not synced
     * yet. Doing that where it was being done, straight inside onActivityResult, is the main
     * thread: the phone would show "PocketLinux isn't responding" and might end the app in the
     * middle of the copy.
     */
    private void copyChosenFiles(java.util.List<android.net.Uri> chosen) {
        if (chosen.isEmpty()) return;
        Toast.makeText(this, chosen.size() == 1
                ? "Bringing the file in\u2026" : "Bringing " + chosen.size() + " files in\u2026",
                Toast.LENGTH_SHORT).show();
        final Context appContext = getApplicationContext();
        new Thread(() -> {
            final String arrived = CloudFiles.copyIn(appContext, chosen);
            main.post(() -> {
                if (finished || isFinishing()) return;
                if (arrived == null) {
                    showMessage("Nothing came across", "The file could not be read. A file still "
                            + "being synced by a cloud app is the usual reason \u2014 open it once "
                            + "in that app so it is downloaded, then try again.");
                    return;
                }
                showMessage("In the computer's Cloud folder", arrived + "\n\nIt is in the Cloud "
                        + "folder, which every Open dialog inside Linux lists on the left. Attach "
                        + "it in ChatGPT or Claude, or open it in Cursor, from there.");
            });
        }, "pocketlinux-cloud-copy").start();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        if (AppLock.handleResult(this, outer, request, result, null)) return;
        if (request == CloudFiles.REQUEST_PICK) {
            if (result != RESULT_OK) return;
            copyChosenFiles(CloudFiles.urisOf(data));
            return;
        }
        if (request == REQUEST_PHOTO) {
            if (result == RESULT_OK) savePhoto(data);
            return;
        }
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
        if (microphone == null) {
            showMessage("Microphone unavailable", "Close this screen and open the desktop again.");
            return;
        }
        if (microphone.isRunning()) {
            microphone.stop();
            Toast.makeText(this, "Microphone off.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!MicBridge.available(this)) {
            showMessage("Microphone is not ready", "The desktop makes the microphone when it starts. "
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

    /**
     * A photo from the phone's camera, straight into the computer's Pictures folder.
     *
     * This asks the phone's OWN camera app to take it, so PocketLinux needs no camera permission
     * of its own -- there is no camera permission in this app at all, and the Privacy monitor
     * says so. A live camera INSIDE Linux is a different thing and is not possible here: a
     * program like Chrome looks for /dev/video0, and creating one needs a kernel module, which
     * an app on an unrooted phone cannot load.
     */
    private void takePhoto() {
        if (!ContainerRuntime.isInstalled(this)) {
            showMessage("No computer yet", "Set up the Linux computer first.");
            return;
        }
        try {
            Intent camera = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            if (camera.resolveActivity(getPackageManager()) == null) {
                showMessage("No camera app", "This phone has no app that answers a request for a "
                        + "photo. Save a picture into Phone files instead and open it from there.");
                return;
            }
            startActivityForResult(camera, REQUEST_PHOTO);
        } catch (Throwable refused) {
            showMessage("The camera did not open", "This phone would not hand over its camera app.");
        }
    }

    /** Writes the thumbnail the camera app hands back into the computer's Pictures folder. */
    private void savePhoto(Intent data) {
        Object extra = data == null || data.getExtras() == null ? null : data.getExtras().get("data");
        if (!(extra instanceof android.graphics.Bitmap)) {
            showMessage("No photo came back", "The camera app returned nothing to save. Some camera "
                    + "apps only save to the phone's gallery — turn on Phone files in Settings and "
                    + "the photo will be in the computer's Phone folder instead.");
            return;
        }
        android.graphics.Bitmap photo = (android.graphics.Bitmap) extra;
        java.io.File pictures = new java.io.File(ContainerRuntime.rootfs(this),
                "home/coder/Pictures");
        if (!pictures.isDirectory() && !pictures.mkdirs()) {
            showMessage("Could not save it", "The computer's Pictures folder could not be opened.");
            return;
        }
        java.io.File file = new java.io.File(pictures,
                "photo-" + System.currentTimeMillis() + ".png");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            photo.compress(android.graphics.Bitmap.CompressFormat.PNG, 95, out);
            out.getFD().sync();
        } catch (java.io.IOException problem) {
            showMessage("Could not save it", problem.getMessage() == null
                    ? "The photo could not be written." : problem.getMessage());
            return;
        }
        Toast.makeText(this, "Saved in the computer's Pictures as " + file.getName(),
                Toast.LENGTH_LONG).show();
    }

    /** One themed dialog, in the viewer's own style, for the microphone's few honest answers. */
    private void showMessage(String title, String text) {
        new AlertDialog.Builder(this, R.style.Theme_PocketLinux_Dialog)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton("OK", null)
                .show();
    }

    private void startMicrophone() {
        if (microphone == null) return;
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
        releaseRemoteInput();
        finished = true;
        audio.stop();
        if (microphone != null) microphone.stop();
        if (desktop != null && desktop.getClient() != null) desktop.getClient().close();
        if (connectionThread != null) connectionThread.interrupt();
        if (desktop != null) desktop.release();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
    }

    /**
     * Keep a failed viewer on screen with the primary cause instead of silently throwing the
     * owner back to Home. This deliberately uses only framework widgets so it still works when
     * the richer desktop UI was the part that failed.
     */
    private void showLaunchFailure(Throwable error) {
        finished = true;
        audio.stop();
        if (microphone != null) microphone.stop();
        try {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setGravity(Gravity.CENTER);
            page.setPadding(48, 48, 48, 48);
            page.setBackgroundColor(Color.rgb(5, 7, 17));

            TextView title = new TextView(this);
            title.setText("The desktop could not open");
            title.setTextColor(Color.WHITE);
            title.setTextSize(24);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            page.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView detail = new TextView(this);
            String reason = error.getMessage();
            detail.setText("Nothing in the Linux computer was changed. The original error was saved "
                    + "under Settings → Last error report.\n\n"
                    + error.getClass().getSimpleName()
                    + (reason == null || reason.trim().isEmpty() ? "" : ": " + reason));
            detail.setTextColor(Color.rgb(190, 204, 240));
            detail.setTextSize(15);
            detail.setPadding(0, 24, 0, 24);
            detail.setTextIsSelectable(true);
            page.addView(detail, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            Button retry = new Button(this);
            retry.setText("Try again");
            retry.setOnClickListener(v -> recreate());
            page.addView(retry, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            Button back = new Button(this);
            back.setText("Back to PocketLinux");
            back.setOnClickListener(v -> finish());
            page.addView(back, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            setContentView(page);
        } catch (Throwable screenFailure) {
            finish();
        }
    }

    private View buildScreen() {
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Color.rgb(5, 7, 17));

        // ---- The control bar ----------------------------------------------------------------
        bar = strip();
        LinearLayout barRow = (LinearLayout) bar.getChildAt(0);

        Button home = toolButton("Home", R.drawable.ic_arrow_back);
        home.setContentDescription("Back to PocketLinux home");
        home.setOnClickListener(v -> finish());
        barRow.addView(home, barItem(88));

        status = Ui.text(this, "Starting…", 12.5f, Color.rgb(194, 202, 230));
        status.setBackground(Ui.tappable(this,
                Ui.outlined(Color.rgb(24, 31, 61), Color.rgb(52, 66, 108), 16, this), true));
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

        // Mute earns a place on the bar because it is the one control that is needed NOW: an
        // AI app starts talking, or a page plays a video, in a room where it should not.
        muteBarButton = toolButton("Mute", R.drawable.ic_volume);
        muteBarButton.setContentDescription("Mute or unmute the computer's sound");
        muteBarButton.setOnClickListener(v -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE));
        barRow.addView(muteBarButton, barItem(88));

        Button screen = toolButton("Screen ▾", R.drawable.ic_fit);
        screen.setContentDescription("Screen: fit, zoom, rotate, full screen, bar position");
        screen.setOnClickListener(v -> showScreenMenu(v));
        barRow.addView(screen, barItem(104));

        pointerButton = toolButton("Finger", R.drawable.ic_touch);
        pointerButton.setContentDescription("Switch between finger, mouse and screen control");
        pointerButton.setOnClickListener(v -> togglePointerMode());
        barRow.addView(pointerButton, barItem(96));

        dragButton = toolButton("Drag", R.drawable.ic_cursor);
        dragButton.setContentDescription("Hold the left mouse button to resize a sidebar or move an item");
        dragButton.setOnClickListener(v -> {
            if (lockedNow()) return;
            if (!desktop.isLive()) {
                Toast.makeText(this, "Connect to the desktop first", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean held = desktop.toggleDrag();
            Toast.makeText(this, held
                    ? "Mouse held: swipe to resize or move. Tap Release when finished."
                    : "Mouse released", Toast.LENGTH_LONG).show();
        });
        barRow.addView(dragButton, barItem(94));

        Button keyboard = toolButton("Keyboard", R.drawable.ic_keyboard);
        keyboard.setContentDescription("Open the phone keyboard");
        keyboard.setOnClickListener(v -> showKeyboard());
        barRow.addView(keyboard, barItem(116));

        keysButton = toolButton("Keys", R.drawable.ic_terminal);
        keysButton.setContentDescription("Show or hide the row of special keys");
        keysButton.setOnClickListener(v -> setKeyRowShown(!keyRowShown));
        barRow.addView(keysButton, barItem(82));

        Button window = toolButton("Window ▾", R.drawable.ic_desktop);
        window.setContentDescription("Window: switch apps, minimise, resize, close, force close");
        window.setOnClickListener(v -> showWindowMenu(v));
        barRow.addView(window, barItem(112));

        // The phone's own things -- its volume, its microphone, its camera, its files, its
        // clipboard -- in one place, so the Screen menu is only about how the picture is shown
        // and the Window menu only about the computer's windows. Three menus, three owners.
        Button phone = toolButton("Phone ▾", R.drawable.ic_phone);
        phone.setContentDescription("Phone: volume, microphone, photo, files, paste, lock touches");
        phone.setOnClickListener(v -> showPhoneMenu(v));
        barRow.addView(phone, barItem(100));

        // ---- The row of special keys, shown on request ----------------------------------------
        keyRow = strip();
        LinearLayout keys = (LinearLayout) keyRow.getChildAt(0);
        addKey(keys, "Esc", 0xff1b);
        addKey(keys, "Tab", 0xff09);
        ctrlButton = addModifier(keys, "Ctrl", 0);
        altButton = addModifier(keys, "Alt", 1);
        superButton = addModifier(keys, "Super", 2);
        shiftButton = addModifier(keys, "Shift", 3);
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
        desktop.setWideWorkspace(preferences.getBoolean(KEY_WIDE_WORKSPACE, false));
        desktop.setMagnification(preferences.getInt(KEY_MAGNIFICATION, 100));
        autoHideBars = preferences.getBoolean(KEY_AUTO_HIDE, false);
        desktop.setPointerMode(pointerModeOf(
                preferences.getString(ContainerRuntime.KEY_POINTER_MODE, "finger")));
        stylePointerButton();
        desktop.setInputStateListener(this::styleHeldInput);
        desktop.setStateListener((text, connected) -> {
            if (!connected) releaseRemoteInput();
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
        // The right corner, which is where every phone puts its volume slider and where a
        // right-handed thumb already is. Below the bar when the bar is at the top.
        outer.addView(buildVolumePanel(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END));
        placeVolumePanel();
        outer.addView(buildTouchLock(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        // The same gradient the cards use, squared off: a lit top edge over a darker bottom is
        // what an iOS tab bar reads as, and it is the only "glass" Android will give a View
        // without a blur it does not have.
        android.graphics.drawable.GradientDrawable shelf = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(23, 30, 58), Color.rgb(11, 15, 32)});
        scroller.setBackground(shelf);
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
        // 48 dp is the smallest row that still gives every button a full-size touch target,
        // and it hands 8 dp of the phone's screen back to the computer -- twice, when the key
        // row is showing. The buttons are MATCH_PARENT inside it, so nothing shrinks.
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48));
        LinearLayout.LayoutParams desktopLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        keyRow.setVisibility(keyRowShown ? View.VISIBLE : View.GONE);
        barDivider = hairline();
        if (controlsAtTop) {
            column.addView(bar, stripLp);
            column.addView(barDivider, dividerLp());
            column.addView(keyRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));
            column.addView(desktop, desktopLp);
        } else {
            column.addView(desktop, desktopLp);
            column.addView(keyRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));
            column.addView(barDivider, dividerLp());
            column.addView(bar, stripLp);
        }
        styleToggle(keysButton, keyRowShown);
        if (outer != null) outer.requestApplyInsets();
    }

    /** A one-pixel line between the desktop and the control bar, so the bar reads as a shelf. */
    private View hairline() {
        View line = new View(this);
        // Brighter than the surfaces on both sides of it, so it reads as the lit edge of the
        // shelf rather than a seam between two dark rectangles.
        line.setBackgroundColor(Color.rgb(58, 74, 128));
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
                ? Ui.outlined(Color.rgb(38, 50, 110), Color.rgb(122, 155, 255), 16, this)
                : Ui.outlined(Color.rgb(32, 40, 70), Color.rgb(52, 66, 108), 16, this), true));
    }

    private void showScreenMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.setForceShowIcon(true);
        Menu items = menu.getMenu();
        items.add(0, MENU_FIT, 0, "Fit: whole desktop, centred").setIcon(R.drawable.ic_fit);
        items.add(0, MENU_ZOOM_IN, 1, "Zoom in (" + desktop.zoomPercent() + " %)").setIcon(R.drawable.ic_fullscreen);
        items.add(0, MENU_ZOOM_OUT, 2, "Zoom out").setIcon(R.drawable.ic_fullscreen);
        items.add(0, MENU_WIDE_WORKSPACE, 3, desktop.isWideWorkspace()
                ? "Phone-sized workspace" : "Wider workspace: more room, smaller text")
                .setIcon(R.drawable.ic_desktop);
        items.add(0, MENU_ROTATE, 4, "Rotate").setIcon(R.drawable.ic_rotate);
        items.add(0, MENU_FULL_SCREEN, 4, "Full screen: hide the controls").setIcon(R.drawable.ic_desktop);
        items.add(0, MENU_BAR_POSITION, 5, controlsAtTop ? "Move controls to the bottom" : "Move controls to the top")
                .setIcon(R.drawable.ic_settings);
        items.add(0, MENU_BIGGER, 5, "Bigger interface (" + desktop.getMagnification() + " %)")
                .setIcon(R.drawable.ic_fullscreen);
        items.add(0, MENU_AUTO_HIDE, 6, autoHideBars
                ? "Auto-hide the controls: off" : "Auto-hide the controls: on")
                .setIcon(R.drawable.ic_timer);
        items.add(0, MENU_ROTATION_LOCK, 6, rotationLocked
                ? "Rotation lock: off" : "Rotation lock: keep this way up").setIcon(R.drawable.ic_rotate);
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
                case MENU_WIDE_WORKSPACE: {
                    boolean wide = !desktop.isWideWorkspace();
                    desktop.setWideWorkspace(wide);
                    preferences.edit().putBoolean(KEY_WIDE_WORKSPACE, wide).apply();
                    Toast.makeText(this, wide
                            ? "More room for sidebars and settings. Pinch to enlarge the text."
                            : "Workspace matches the phone screen", Toast.LENGTH_LONG).show();
                    return true;
                }
                case MENU_ROTATE: rotateNow(); return true;
                case MENU_FULL_SCREEN: setBarsHidden(true); return true;
                case MENU_BAR_POSITION:
                    controlsAtTop = !controlsAtTop;
                    preferences.edit().putString(ContainerRuntime.KEY_CONTROLS_AT, controlsAtTop ? "top" : "bottom").apply();
                    layoutBars();
                    placeVolumePanel();
                    return true;
                case MENU_BIGGER: biggerInterface(); return true;
                case MENU_AUTO_HIDE: setAutoHideBars(!autoHideBars); return true;
                case MENU_ROTATION_LOCK: setRotationLocked(!rotationLocked); return true;
                default: return false;
            }
        });
        menu.show();
    }

    /** Everything that is the phone's rather than the computer's or the picture's. */
    private void showPhoneMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.setForceShowIcon(true);
        Menu items = menu.getMenu();
        AudioManager sound = (AudioManager) getSystemService(AUDIO_SERVICE);
        boolean silent = sound != null
                && (sound.isStreamMute(AudioManager.STREAM_MUSIC)
                    || sound.getStreamVolume(AudioManager.STREAM_MUSIC) == 0);
        items.add(0, MENU_VOLUME_PANEL, 0, silent
                ? "Volume: muted" : "Volume and mute").setIcon(R.drawable.ic_volume);
        items.add(0, MENU_MICROPHONE, 1, microphone.isRunning()
                        ? "Microphone: turn off" : "Microphone: let the computer hear you")
                .setIcon(R.drawable.ic_volume);
        items.add(0, MENU_PHOTO, 2, "Take a photo into the computer").setIcon(R.drawable.ic_phone);
        items.add(0, MENU_CLOUD_FILE, 3, "Add a file from the phone or a cloud drive")
                .setIcon(R.drawable.ic_download);
        items.add(0, MENU_PHONE_FILES, 4, "Phone files").setIcon(R.drawable.ic_phone);
        items.add(0, MENU_PASTE, 5, "Paste from the phone").setIcon(R.drawable.ic_download);
        items.add(0, MENU_TOUCH_LOCK, 6, "Lock the screen: ignore touches").setIcon(R.drawable.ic_lock);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_VOLUME_PANEL: showVolume(null, true); return true;
                case MENU_MICROPHONE: toggleMicrophone(); return true;
                case MENU_PHOTO: takePhoto(); return true;
                case MENU_CLOUD_FILE: addFileFromPhone(); return true;
                case MENU_PHONE_FILES: chord(0xffeb, 'p'); return true;
                case MENU_PASTE: pasteClipboard(); return true;
                case MENU_TOUCH_LOCK: setTouchLocked(true); return true;
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
        items.add(0, MENU_RESIZE, 3, "Resize this window by dragging").setIcon(R.drawable.ic_fullscreen);
        items.add(0, MENU_MINIMISE, 4, "Minimise this window").setIcon(R.drawable.ic_desktop);
        items.add(0, MENU_MINIMISE_ALL, 5, "Minimise all: show the desktop").setIcon(R.drawable.ic_desktop);
        items.add(0, MENU_CLOSE, 6, "Close this window").setIcon(R.drawable.ic_close);
        items.add(0, MENU_FORCE_CLOSE, 7, "Force close (stuck app)").setIcon(R.drawable.ic_stop);
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
                case MENU_APPS: chord(0xffeb, 'a'); return true;
                case MENU_RELOAD: chord(0xffeb, 'r'); return true;
                case MENU_FIT_WINDOW: chord(0xffeb, 'f'); return true;
                case MENU_RESIZE: startWindowResize(); return true;
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
        showVolume(manager, volumePanel != null && volumePanel.getVisibility() == View.VISIBLE
                && !volumeAutoHiding);
    }

    private Button muteBarButton;
    private TextView volumeChip;
    private TextView volumeNote;
    private ProgressBar volumeBar;
    private Button muteButton;
    private LinearLayout volumePanel;
    /** True while the panel is up only because a volume key nudged it. */
    private boolean volumeAutoHiding;
    private final Runnable hideVolume = () -> {
        if (volumePanel != null) volumePanel.animate().alpha(0f).setDuration(220)
                .withEndAction(() -> { if (volumePanel != null) volumePanel.setVisibility(View.GONE); }).start();
    };

    /**
     * The volume readout, in the right-hand corner: which volume it is, the percentage, a bar,
     * and the three buttons -- quieter, mute, louder -- so it can be changed without hunting for
     * the phone's own keys or leaving the desktop.
     *
     * "Media volume" is named rather than assumed. This app plays the Linux computer's sound on
     * the media stream and touches no other, so a phone on silent with media turned up still has
     * sound here, and that surprises people until they are told which volume they are moving.
     */
    /**
     * @param opened true when the owner asked for the panel (the Phone menu): it then stays until
     *               it is closed, with its own button or a tap anywhere else. A volume key shows
     *               it for a moment only, as every phone does.
     */
    private void showVolume(AudioManager manager, boolean opened) {
        if (volumePanel == null || outer == null) return;
        if (manager == null) manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        int percent = 0;
        boolean silent = true;
        if (manager != null) {
            int max = Math.max(1, manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int step = manager.getStreamVolume(AudioManager.STREAM_MUSIC);
            percent = Math.round(step * 100f / max);
            silent = step == 0 || manager.isStreamMute(AudioManager.STREAM_MUSIC);
        }
        volumeChip.setText(silent ? "Media volume  \u00b7  muted" : "Media volume  \u00b7  " + percent + " %");
        volumeNote.setText(silent
                ? "The computer cannot be heard"
                : "The Linux computer plays on this volume");
        volumeBar.setProgress(silent ? 0 : percent);
        muteButton.setText(silent ? "Unmute" : "Mute");
        muteButton.setContentDescription(silent ? "Turn the sound back on" : "Mute the computer");
        styleToggle(muteButton, silent);
        if (muteBarButton != null) {
            muteBarButton.setText(silent ? "Muted" : "Mute");
            styleToggle(muteBarButton, silent);
        }
        volumePanel.setVisibility(View.VISIBLE);
        volumePanel.animate().cancel();
        volumePanel.setAlpha(1f);
        volumePanel.removeCallbacks(hideVolume);
        // Opened on purpose: it stays. Nudged by a key: long enough to press the buttons that
        // are on it, which the old second and a half was not.
        volumeAutoHiding = !opened;
        if (!opened) volumePanel.postDelayed(hideVolume, 3200L);
    }

    private void hideVolumeNow() {
        if (volumePanel == null) return;
        // The panel is still VISIBLE for the 220 ms fade. A volume key pressed inside that
        // window used to read "visible and opened on purpose" and bring it straight back, sticky.
        volumeAutoHiding = true;
        volumePanel.removeCallbacks(hideVolume);
        hideVolume.run();
    }

    /**
     * A tap anywhere that is not the volume panel puts the panel away, exactly as the phone's
     * own does. Watched here rather than on the desktop view, because the desktop view must
     * never know that a panel exists.
     */
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (volumePanel != null && volumePanel.getVisibility() == View.VISIBLE
                && event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            int[] at = new int[2];
            volumePanel.getLocationOnScreen(at);
            float x = event.getRawX();
            float y = event.getRawY();
            boolean inside = x >= at[0] && x <= at[0] + volumePanel.getWidth()
                    && y >= at[1] && y <= at[1] + volumePanel.getHeight();
            if (!inside) hideVolumeNow();
        }
        return super.dispatchTouchEvent(event);
    }

    /** The indicator itself: built once, hidden until the volume is asked about. */
    private View buildVolumePanel() {
        volumePanel = new LinearLayout(this);
        volumePanel.setOrientation(LinearLayout.VERTICAL);
        // The same glass as the cards and the bar, not a black slab: what is behind it stays
        // suggested rather than blotted out, and it looks like part of the app it is in.
        volumePanel.setBackground(Ui.glass(this, true, 18));
        volumePanel.setElevation(Ui.dp(this, 10));
        int pad = Ui.dp(this, 12);
        volumePanel.setPadding(pad, Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 10));

        // Title and the close button on one row. The panel used to have no way to put it away
        // but waiting, which on a panel with buttons on it is the one thing it must not lack.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        volumeChip = Ui.bold(this, "Media volume", 13, Color.rgb(230, 236, 247));
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        chipLp.setMarginEnd(Ui.dp(this, 8));
        head.addView(volumeChip, chipLp);
        android.widget.ImageButton close = new android.widget.ImageButton(this);
        close.setImageResource(R.drawable.ic_close);
        close.setImageTintList(ColorStateList.valueOf(Color.rgb(170, 186, 224)));
        close.setBackground(Ui.tappable(this, Ui.background(Color.rgb(32, 42, 74), 14, this), true));
        close.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        close.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        close.setContentDescription("Close the volume panel");
        close.setOnClickListener(v -> hideVolumeNow());
        head.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));
        volumePanel.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        volumeBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        volumeBar.setMax(100);
        volumeBar.setProgressTintList(ColorStateList.valueOf(Color.rgb(122, 155, 255)));
        volumeBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(44, 54, 96)));
        LinearLayout.LayoutParams barLp =
                new LinearLayout.LayoutParams(Ui.dp(this, 186), Ui.dp(this, 6));
        barLp.topMargin = Ui.dp(this, 8);
        volumePanel.addView(volumeBar, barLp);
        volumeNote = Ui.text(this, "", 11, Color.rgb(158, 172, 208));
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = Ui.dp(this, 5);
        volumePanel.addView(volumeNote, noteLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button quieter = toolButton("\u2212", R.drawable.ic_volume);
        quieter.setContentDescription("Media volume down");
        quieter.setOnClickListener(v -> adjustVolume(AudioManager.ADJUST_LOWER));
        buttons.addView(quieter, volumeButton(56));
        muteButton = toolButton("Mute", 0);
        muteButton.setOnClickListener(v -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE));
        buttons.addView(muteButton, volumeButton(80));
        Button louder = toolButton("+", R.drawable.ic_volume);
        louder.setContentDescription("Media volume up");
        louder.setOnClickListener(v -> adjustVolume(AudioManager.ADJUST_RAISE));
        buttons.addView(louder, volumeButton(56));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36));
        rowLp.topMargin = Ui.dp(this, 9);
        volumePanel.addView(buttons, rowLp);

        volumePanel.setVisibility(View.GONE);
        return volumePanel;
    }

    /**
     * Keeps the volume corner clear of the control bar, whichever end the bar is at.
     *
     * The margin used to be worked out once, when the screen was built. Moving the bar to the top
     * afterwards left the panel where it was, on top of the bar it was meant to sit below, and it
     * covered the very buttons it appears next to.
     */
    private void placeVolumePanel() {
        if (volumePanel == null) return;
        ViewGroup.LayoutParams params = volumePanel.getLayoutParams();
        if (!(params instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) params;
        lp.topMargin = Ui.dp(this, controlsAtTop ? 66 : 12);
        lp.setMarginEnd(Ui.dp(this, 10));
        volumePanel.setLayoutParams(lp);
    }

    private LinearLayout.LayoutParams volumeButton(int widthDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(this, widthDp), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMarginEnd(Ui.dp(this, 5));
        return lp;
    }

    // ---- The screen lock: touches ignored until it is turned off ------------------------------

    private FrameLayout touchLock;
    private boolean touchLocked;

    /**
     * A lid over the desktop, so the computer can be watched, read or listened to without a
     * palm, a pocket or a passenger touching anything. The only thing that answers is the
     * chip in the middle, and it wants two taps -- one is exactly what a stray touch is.
     */
    private View buildTouchLock() {
        touchLock = new FrameLayout(this);
        touchLock.setBackgroundColor(Color.argb(56, 3, 5, 12));
        touchLock.setClickable(true);
        touchLock.setFocusable(true);
        touchLock.setVisibility(View.GONE);
        TextView chip = Ui.bold(this, "Screen locked \u00b7 double tap to unlock", 13,
                Color.rgb(226, 233, 248));
        chip.setBackground(Ui.outlined(
                Color.argb(238, 15, 21, 44), Color.rgb(96, 118, 190), 16, this));
        chip.setPadding(Ui.dp(this, 16), Ui.dp(this, 11), Ui.dp(this, 16), Ui.dp(this, 11));
        chip.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        touchLock.addView(chip, chipLp);
        final long[] lastTap = { 0L };
        touchLock.setOnClickListener(v -> {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastTap[0] < 700L) {
                setTouchLocked(false);
                Toast.makeText(this, "Screen unlocked", Toast.LENGTH_SHORT).show();
            } else {
                lastTap[0] = now;
                chip.animate().cancel();
                chip.setAlpha(1f);
                chip.animate().alpha(0.35f).setStartDelay(1400L).setDuration(400L).start();
            }
        });
        return touchLock;
    }

    boolean isTouchLocked() { return touchLocked; }

    private void setTouchLocked(boolean locked) {
        touchLocked = locked;
        if (touchLock == null) return;
        touchLock.setVisibility(locked ? View.VISIBLE : View.GONE);
        if (locked) {
            // Above the full-screen chip and everything else, or the one thing it is meant to
            // stop -- a stray tap on a control -- would still get through.
            touchLock.bringToFront();
            releaseRemoteInput();
            hideKeyboard();
            Toast.makeText(this, "Screen locked. Double tap the middle to unlock.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** What the status label opens: the plain facts about this session and how to drive it. */
    private void showDetails() {
        if (!desktop.isLive()) {
            boolean active = LinuxService.isDesktopRunning() || LinuxService.isDesktopStarting()
                        || LinuxService.isReopening();
            String reason = active ? "The Linux computer is still active. Reconnect this screen to it."
                    : preferences.getString(ContainerRuntime.KEY_LAST_STOP_REASON,
                            "The desktop connection ended. Your installed apps and saved files are kept.");
            new AlertDialog.Builder(this, R.style.Theme_PocketLinux_Dialog)
                    .setTitle("Desktop connection")
                    .setMessage(reason + "\n\nSettings → Linux app reports includes the desktop and viewer reports.")
                    .setNegativeButton("Close", null)
                    .setPositiveButton(active ? "Reconnect" : "Open desktop", (dialog, which) -> {
                        if (!LinuxService.isDesktopRunning() && !LinuxService.isDesktopStarting()) {
                            Intent start = new Intent(this, LinuxService.class)
                                    .setAction(LinuxService.ACTION_START_DESKTOP);
                            startForegroundService(start);
                        }
                        connectWithRetry();
                    }).show();
            return;
        }
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
                + "there is no call, ring or alarm sound in PocketLinux at all. The phone's volume "
                + "keys set it while this screen is open and show the level, and Screen → Media "
                + "volume does the same from the menu. Inside the computer, Tools → Volume and "
                + "sound balances one app against another; the phone still decides how loud it "
                + "ends up. Screen → Microphone hands the phone's microphone to the computer; it "
                + "is off at every start and stops the moment you leave this screen.\n\n"
                + "Super+Space takes an appshot — the window in front, its words read, pasted "
                + "straight into whichever AI app is open.\n\n"
                + "Stopping the computer keeps everything: apps stay signed in and files stay "
                + "where they are, so the next open continues from here.";
        new AlertDialog.Builder(this, R.style.Theme_PocketLinux_Dialog)
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
        if (hidden) releaseRemoteInput();
        bar.setVisibility(hidden ? View.GONE : View.VISIBLE);
        keyRow.setVisibility(hidden || !keyRowShown ? View.GONE : View.VISIBLE);
        restoreBars.setVisibility(hidden ? View.VISIBLE : View.GONE);
        if (hidden) main.removeCallbacks(hideBarsSoon); else armAutoHide();
    }

    // ---- Auto-hide: the bar steps out of the way while the computer is being used -----------

    private static final String KEY_AUTO_HIDE = "viewer_auto_hide";
    private static final long AUTO_HIDE_AFTER_MS = 6_000L;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean autoHideBars;
    private long autoHideArmedAt;

    private final Runnable hideBarsSoon = new Runnable() {
        @Override public void run() {
            if (!autoHideBars || bar == null || bar.getVisibility() != View.VISIBLE) return;
            // A touch during the wait re-arms rather than cancels, so this only fires when the
            // desktop really has been left alone -- and never while a finger is on the glass.
            long quiet = System.currentTimeMillis() - Math.max(autoHideArmedAt, VncView.lastInteractionAt);
            if (quiet < AUTO_HIDE_AFTER_MS - 250L) {
                main.postDelayed(this, AUTO_HIDE_AFTER_MS - quiet);
                return;
            }
            setBarsHidden(true);
        }
    };

    private void armAutoHide() {
        main.removeCallbacks(hideBarsSoon);
        if (!autoHideBars) return;
        autoHideArmedAt = System.currentTimeMillis();
        main.postDelayed(hideBarsSoon, AUTO_HIDE_AFTER_MS);
    }

    private void setAutoHideBars(boolean on) {
        autoHideBars = on;
        preferences.edit().putBoolean(KEY_AUTO_HIDE, on).apply();
        if (on) {
            armAutoHide();
            Toast.makeText(this, "The controls step aside after a few seconds. The chip in the "
                    + "corner brings them back.", Toast.LENGTH_LONG).show();
        } else {
            main.removeCallbacks(hideBarsSoon);
            setBarsHidden(false);
        }
    }

    /**
     * Everything on the Linux desktop, drawn bigger, at once.
     *
     * The screen's dpi is the proper answer and it lives in Settings, but every Linux program
     * reads it when it starts, so it can only apply to the next session. This asks the desktop
     * to be smaller than the phone screen and lets the viewer scale it up to fill it -- so type,
     * icons, title bars and close buttons all grow together, now, with nothing cropped.
     */
    private void biggerInterface() {
        int current = desktop.getMagnification();
        int next = ViewerSize.STEPS[0];
        for (int index = 0; index < ViewerSize.STEPS.length; index++) {
            if (ViewerSize.STEPS[index] == current) {
                next = ViewerSize.STEPS[(index + 1) % ViewerSize.STEPS.length];
                break;
            }
        }
        desktop.setMagnification(next);
        preferences.edit().putInt(KEY_MAGNIFICATION, next).apply();
        Toast.makeText(this, next == 100
                        ? "Back to the sharpest size: one Linux pixel per phone pixel."
                        : "Everything on the desktop is " + next + " % of its size. Sharper "
                                + "still: Settings \u2192 Desktop text size.",
                Toast.LENGTH_SHORT).show();
    }

    private static final String KEY_MAGNIFICATION = "viewer_magnification";

    private void connectWithRetry() {
        // A status-button tap must not race an existing connection reader or start Linux again.
        if (finished) return;
        if (connectionThread != null && connectionThread.isAlive()) {
            reconnectWhenIdle = true;
            return;
        }
        reconnectWhenIdle = false;
        connectionThread = new Thread(() -> {
            try {
            long startedAt = SystemClock.elapsedRealtime();
            DesktopRetry retries = new DesktopRetry(startedAt);
            long nextStatusAt = 0L;
            String lastError = "The desktop did not become ready. Tap the status below to retry.";
            while (!finished && !Thread.currentThread().isInterrupted()) {
                // Connect once, for real. Probe-then-connect opened and discarded an extra
                // client on every attempt and introduced a readiness race under heavy load.
                VncClient client = new VncClient("127.0.0.1", 5901, vncSocketPath(), desktop);
                client.setUpdatesPaused(!viewerVisible);
                desktop.setClient(client);
                // A visibility change between construction and publication must reach this client.
                client.setUpdatesPaused(!viewerVisible);
                if (finished) { client.close(); desktop.setClient(null); return; }
                boolean retryable = true;
                try {
                    client.connectAndRun();
                    lastError = "Desktop connection closed";
                } catch (IOException error) {
                    lastError = error.getMessage() == null ? "Connection failed" : error.getMessage();
                } catch (Throwable error) {
                    Crash.save(DesktopActivity.this, error);
                    lastError = "Viewer ran out of memory or hit an error ("
                            + error.getClass().getSimpleName() + "). Close other apps and reopen.";
                    retryable = false;
                } finally {
                    client.close();
                }
                if (finished) return;
                if (desktop.getFatalError() != null) {
                    lastError = desktop.getFatalError();
                    retryable = false;
                }
                long now = SystemClock.elapsedRealtime();
                boolean active = LinuxService.isDesktopRunning() || LinuxService.isDesktopStarting()
                        || LinuxService.isReopening();
                if (client.hasConnected()) {
                    RuntimeDiagnostics.snap(DesktopActivity.this, "viewer-disconnected: " + lastError);
                }
                if (!retryable || !retries.retry(now, client.hasConnected(), client.connectedMillis(), active)
                        || (!active && now - startedAt > 5_000L)) break;
                if (now >= nextStatusAt) {
                    desktop.onDisconnected(LinuxService.isReopening()
                            ? "The phone stopped the computer. Reopening it — nothing was lost…"
                            : retries.hasConnected() ? "Reconnecting to your running desktop…"
                            : "Starting your Linux computer… " + (now - startedAt) / 1000L + "s");
                    nextStatusAt = now + 2_000L;
                }
                SystemClock.sleep(750L);
            }
            if (!finished) {
                RuntimeDiagnostics.snap(DesktopActivity.this, "viewer-retry-ended: " + lastError);
                String detail = LinuxService.lastDetail();
                if (!LinuxService.isDesktopRunning() && !LinuxService.isDesktopStarting()
                        && LinuxService.lastWasError() && detail != null && !detail.isEmpty()) {
                    desktop.onDisconnected(detail);
                } else {
                    desktop.onDisconnected(lastError);
                }
            }
            } finally {
                Thread reader = Thread.currentThread();
                runOnUiThread(() -> {
                    if (connectionThread == reader) connectionThread = null;
                    if (!finished && reconnectWhenIdle && !desktop.isLive()) connectWithRetry();
                });
            }
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

    /** Finger -> Mouse -> Screen -> Finger. Three ways one finger can be a pointer. */
    private void togglePointerMode() {
        VncView.PointerMode next;
        switch (desktop.getPointerMode()) {
            case DIRECT:   next = VncView.PointerMode.TOUCHPAD; break;
            case TOUCHPAD: next = VncView.PointerMode.TOUCH; break;
            default:       next = VncView.PointerMode.DIRECT; break;
        }
        desktop.setPointerMode(next);
        preferences.edit().putString(ContainerRuntime.KEY_POINTER_MODE, pointerModeName(next)).apply();
        stylePointerButton();
        String said;
        switch (next) {
            case TOUCHPAD:
                said = "Mouse: move the arrow, then tap Drag to resize an edge. Two fingers scroll.";
                break;
            case TOUCH:
                said = "Screen: the finger holds the button down, so a swipe drags, draws and "
                        + "plays. Two fingers zoom.";
                break;
            default:
                said = "Finger: tap to click, swipe to scroll either way. Drag holds a divider.";
                break;
        }
        Toast.makeText(this, said, Toast.LENGTH_SHORT).show();
    }

    static String pointerModeName(VncView.PointerMode mode) {
        switch (mode) {
            case TOUCHPAD: return "mouse";
            case TOUCH: return "touch";
            default: return "finger";
        }
    }

    static VncView.PointerMode pointerModeOf(String saved) {
        if ("mouse".equals(saved)) return VncView.PointerMode.TOUCHPAD;
        if ("touch".equals(saved)) return VncView.PointerMode.TOUCH;
        return VncView.PointerMode.DIRECT;
    }

    /**
     * Resize the window in front by dragging anywhere inside it.
     *
     * A window edge on a phone screen is about a millimetre wide and there is no cursor to see
     * it change shape, so aiming at one is not a real option. Openbox's own Alt + right-drag is:
     * it grabs the whole window, and the corner nearest the finger is the one that moves. The
     * held-input machinery that already exists for dragging a divider does the rest, so the
     * button stays down while the thumb lifts and repositions.
     */
    private void startWindowResize() {
        if (lockedNow()) return;
        if (!desktop.isLive()) {
            Toast.makeText(this, "Connect to the desktop first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (desktop.isHeldDragging()) {          // already holding something: let it go first
            desktop.toggleDrag();
            return;
        }
        // Un-maximise first: PocketLinux opens windows full-screen, and a maximised window
        // cannot be resized -- the drag would appear to do nothing at all.
        chord(0xffeb, 'u');                       // Super+U: pocketdesk-windows unmaximise
        desktop.postDelayed(() -> {
            desktop.toggleDrag(4, 0xffe9);        // Alt held, right button down
            Toast.makeText(this, "Swipe to resize the window. Tap Release when it is the size "
                    + "you want.", Toast.LENGTH_LONG).show();
        }, 220L);
    }

    /** The button shows what is on the screen: an arrow for the mouse, a hand for the other two. */
    private void stylePointerButton() {
        VncView.PointerMode mode = desktop.getPointerMode();
        boolean mouse = mode == VncView.PointerMode.TOUCHPAD;
        pointerButton.setText(mouse ? "Mouse" : mode == VncView.PointerMode.TOUCH ? "Screen" : "Finger");
        Ui.setStartIcon(pointerButton, mouse ? R.drawable.ic_cursor : R.drawable.ic_touch,
                Color.rgb(150, 175, 255), this, 18);
    }

    private void showKeyboard() {
        keyboardInput.requestFocus();
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        input.showSoftInput(keyboardInput, InputMethodManager.SHOW_IMPLICIT);
    }

    /** Puts the phone keyboard away, so nothing keeps typing into a desktop that is not listening. */
    private void hideKeyboard() {
        InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null && keyboardInput != null) {
            input.hideSoftInputFromWindow(keyboardInput.getWindowToken(), 0);
        }
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

    /** Toolbar modifiers combine for one key, then release; Shift also supports selection. */
    private void toggleModifier(int type) {
        if (lockedNow()) return;
        int keysym = type == 0 ? 0xffe3 : type == 1 ? 0xffe9 : type == 2 ? 0xffeb : 0xffe1;
        desktop.toggleHeldModifier(keysym);
    }

    private void releaseModifiers() {
        if (desktop != null) desktop.releaseModifiers();
    }

    private void styleHeldInput() {
        if (desktop == null) return;
        styleModifier(ctrlButton, desktop.isModifierHeld(0xffe3));
        styleModifier(altButton, desktop.isModifierHeld(0xffe9));
        styleModifier(superButton, desktop.isModifierHeld(0xffeb));
        styleModifier(shiftButton, desktop.isModifierHeld(0xffe1));
        if (dragButton != null) {
            boolean held = desktop.isDragHeld();
            dragButton.setText(held ? "Release" : "Drag");
            dragButton.setContentDescription(held ? "Release the held left mouse button"
                    : "Place the pointer on an edge, then hold the left mouse button to drag it");
            styleToggle(dragButton, held);
        }
    }

    private void releaseRemoteInput() {
        if (desktop == null) return;
        for (int keysym : hardwareKeys) sendKey(keysym, false);
        hardwareKeys.clear();
        desktop.releaseInput();
    }

    private void styleModifier(Button button, boolean active) {
        if (button == null) return;
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

    @Override public void replaceText(int backspaces, int deletes, String text) {
        if (lockedNow() || desktop == null) return;
        VncClient active = desktop.getClient();
        if (active == null) return;
        backspaces = Math.max(0, backspaces);
        deletes = Math.max(0, deletes);
        if (text == null) text = "";
        if (backspaces == 0 && deletes == 0 && text.isEmpty()) return;
        VncView.lastInteractionAt = System.currentTimeMillis();
        boolean modified = desktop.isModifierHeld(0xffe1) || desktop.isModifierHeld(0xffe3)
                || desktop.isModifierHeld(0xffe9) || desktop.isModifierHeld(0xffeb);
        if (modified) {
            // Keep toolbar modifiers scoped to the first key, as before. The remainder of an
            // IME paste/composition replacement is one queued write, even for thousands of letters.
            if (backspaces > 0) { specialKey(0xff08); backspaces--; }
            else if (deletes > 0) { specialKey(0xffff); deletes--; }
            else {
                int first = text.codePointAt(0);
                if (first == '\n' || first == '\r') specialKey(0xff0d);
                else typeCodePoint(first);
                text = text.substring(Character.charCount(first));
            }
        }
        if (backspaces > 0 || deletes > 0 || !text.isEmpty()) active.replaceText(backspaces, deletes, text);
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
            hardwareKeys.add(keysym);
            sendKey(keysym, true);
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            hardwareKeys.remove(keysym);
            sendKey(keysym, false);
            if (keysym < 0xffe1 || keysym > 0xffee) releaseModifiers();
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
        VncClient client = desktop == null ? null : desktop.getClient();
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
        // A pill, not a rectangle: 16 dp of radius inside a 48 dp row is the shape every phone
        // bottom bar has settled on, and the hairline is what keeps eight of them from reading
        // as one grey block.
        button.setBackground(Ui.tappable(this,
                Ui.outlined(Color.rgb(32, 40, 70), Color.rgb(52, 66, 108), 16, this), true));
        return button;
    }

    private LinearLayout.LayoutParams keyLayout(int widthDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, widthDp), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        return lp;
    }

    /**
     * The setting made in PocketLinux, applied to this screen -- and through it to the Linux
     * desktop, which is kept exactly the shape of this view, so Portrait here really is a
     * portrait computer and not a landscape one squeezed sideways.
     *
     * Called from onStart as well as onCreate: the setting is changed on the other screen, and
     * before this it took closing and reopening the desktop for the change to be seen.
     */
    private void applyOrientation() {
        if (rotationLocked) return;      // the viewer's own lock outranks the setting while it is on
        setRequestedOrientation(ScreenRotation.of(
                preferences.getString(ContainerRuntime.KEY_ORIENTATION, ScreenRotation.AUTO)));
        // The phone window can already be this way up while the computer inside it is not: a
        // session started before the setting changed keeps the shape it was born with until it
        // is asked to match. Posted so the new configuration lands first; if the desktop is
        // already the right size, nothing is sent.
        if (desktop != null) desktop.post(desktop::requestDesktopMatch);
    }

    /** True while the viewer's rotation lock is holding the screen where it is. */
    private boolean rotationLocked;

    boolean isRotationLocked() { return rotationLocked; }

    /**
     * Pins the screen exactly as it is now, or hands it back to the setting.
     *
     * This is the phone's rotation lock, for this screen only: a long read or a game in
     * landscape should not turn over because the phone was put down flat.
     */
    private void setRotationLocked(boolean locked) {
        rotationLocked = locked;
        if (locked) {
            boolean landscape = getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            setRequestedOrientation(ScreenRotation.pin(
                    getWindowManager().getDefaultDisplay().getRotation(), landscape));
        } else {
            applyOrientation();
        }
    }

    /**
     * Turn the screen the other way, now, and hold it there.
     *
     * Holding it is the point -- a rotation that the sensor undoes the moment the phone moves is
     * not a rotation. It is the same lock the Screen menu offers, so it can be let go again;
     * an earlier version set the flag here with nothing that could clear it, which quietly
     * disabled the Screen rotation setting for the rest of the session.
     */
    private void rotateNow() {
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        rotationLocked = true;
        setRequestedOrientation(landscape
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        Toast.makeText(this, "Turned, and held this way up. Screen \u25be Rotation lock: off "
                + "hands it back to the setting.", Toast.LENGTH_LONG).show();
    }

    /** Keeps the bar clear of the status bar and the gesture bar, whichever end it sits at. */
    private void applySystemInsets(View root) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int ime = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(android.view.WindowInsets.Type.ime()).bottom : 0;
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
            // The IME inset includes the navigation area already excluded by this padding.
            // Count it once so neither the control row nor the desktop floats too far upward.
            int keyboardCover = Math.max(0, ime - bottom);
            if (desktop != null) desktop.setKeyboardInset(keyboardCover);
            float lift = controlsAtTop ? 0f : -keyboardCover;
            if (bar != null) bar.setTranslationY(lift);
            if (keyRow != null) keyRow.setTranslationY(lift);
            if (barDivider != null) barDivider.setTranslationY(lift);
            return insets;
        });
        root.requestApplyInsets();
    }
}
