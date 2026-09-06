package com.pocketlinux;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The home screen: three tabs on a bottom bar, the way CloudSaver lays itself out.
 *
 * Home is the Linux computer -- its state, the one button that opens it, whether this phone
 * qualifies, and the questions anyone asks before trusting a phone with a computer. Apps is
 * the four AI desktop apps. Settings is everything adjustable, grouped and named, with the
 * permissions and the reports at the end. No separate About or FAQ page: every fact lives
 * next to the thing it is about.
 */
public final class MainActivity extends Activity {
    static final String VERSION = "12.0.0";
    static final String EXTRA_ROUTE = "com.pocketlinux.route";
    private static final int TAB_HOME = 0;
    private static final int TAB_APPS = 1;
    private static final int TAB_SETTINGS = 2;

    private SharedPreferences preferences;
    private boolean dark;
    private boolean safeMode;
    private boolean receiverRegistered;
    private String pendingRoute;
    /** Debounces two taps that would otherwise enqueue two top-resumed Activity transactions. */
    private boolean desktopOpening;

    private FrameLayout shell;
    private FrameLayout pageHost;
    private LinearLayout navBar;
    private final ScrollView[] pages = new ScrollView[3];
    private final Ui.NavItem[] navItems = new Ui.NavItem[3];
    private int selectedTab = TAB_HOME;
    private int openAnswer = -1;
    private final List<View[]> answers = new ArrayList<>();

    private Ui.Tile networkTile;
    private Ui.Tile batteryTile;
    private Ui.Tile storageTile;
    private Ui.Tile heatTile;

    private TextView statusBadge;
    private TextView statusHeadline;
    private TextView statusNote;
    private TextView deviceDetails;
    private TextView linuxSize;
    private Ui.Row basicsUpdateRow;
    private AlertDialog permissionIntro;
    private Ui.Row compatibleRow;
    /** True while the opening screen is on top: the lock waits for it rather than covering it. */
    private boolean introShowing;
    /** True between onStart and onStop: the lock prompt is only ever raised on a visible screen. */
    private boolean started;

    private LinearLayout progressCard;
    private TextView progressTitle;
    private TextView progressDetail;
    private TextView progressPercent;
    private ProgressBar progressBar;

    private Button setupButton;
    private Button startButton;
    private Button stopButton;
    private Button removeButton;

    private LinearLayout attentionCard;
    private Ui.Row attentionNotifications;
    private Ui.Row attentionBattery;
    private Ui.Row attentionSpace;
    private Ui.Row attentionHeat;
    private Ui.Row attentionData;
    private Ui.Row attentionLock;
    private Ui.Row attentionCompatible;

    private LinearLayout dataCard;
    private TextView dataFigure;
    private ProgressBar dataBar;
    private TextView dataNote;

    private Ui.Row appearanceRow;
    private Ui.Row rotationRow;
    private Ui.Row autoStopRow;
    private Ui.Row desktopScaleRow;
    private Ui.Row notificationRow;
    private Ui.Row batteryOptimisationRow;
    private Ui.Row autoStartRow;
    private Ui.Row phoneFilesRow;
    private Ui.Row microphoneRow;
    private Ui.Row errorReportRow;
    private DeviceProbe lastProbe;
    private Ui.Row dataCapRow;
    private Ui.Row downloadTargetRow;
    private Ui.Row lockNoticeRow;
    private Ui.Toggle appLockToggle;
    private boolean askBatteryAfterNotifications;

    private final java.util.Map<String, Ui.Row> appRows = new java.util.LinkedHashMap<>();
    private TextView appsNote;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable liveRefresh = new Runnable() {
        @Override public void run() {
            refreshLiveTiles();
            refreshState();
            handler.postDelayed(this, 5_000L);
        }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            renderProgress(intent.getStringExtra(LinuxService.EXTRA_MESSAGE),
                    intent.getStringExtra(LinuxService.EXTRA_DETAIL),
                    intent.getIntExtra(LinuxService.EXTRA_PROGRESS, -1),
                    intent.getBooleanExtra(LinuxService.EXTRA_BUSY, false),
                    intent.getBooleanExtra(LinuxService.EXTRA_ERROR, false));
            refreshState();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Crash.install(getApplicationContext());
        try {
            preferences = getSharedPreferences(ContainerRuntime.PREFS, MODE_PRIVATE);
            dark = resolveDarkMode();
            if (state != null) {
                selectedTab = state.getInt("tab", TAB_HOME);
                openAnswer = state.getInt("answer", -1);
            }
            // Only a fresh start honours the shortcut; a rotation or a theme change recreates
            // this screen with the same intent and must not open the desktop again.
            pendingRoute = state == null && getIntent() != null ? getIntent().getStringExtra(EXTRA_ROUTE) : null;
            applyOrientation();
            View content = buildScreen();
            setContentView(content);
            configureSystemBars();
            AppLock.applyWindowSecurity(this);
            applySystemInsets();
            if (state == null) showIntro();
        } catch (Throwable error) {
            Crash.save(this, error);
            showSafeScreen(error);
        }
    }

    /**
     * The opening: the app's mark and name, then Tux with the system it runs, then the home
     * screen -- and, when App lock is on, the fingerprint or PIN prompt right after it. Only on
     * a cold start; a rotation or a return from another app skips straight to the lock.
     */
    private void showIntro() {
        // A shortcut is an instruction, not a visit: someone who long-pressed the icon and chose
        // "Open desktop" asked for the desktop, and holding that behind three and a half seconds
        // of brand is the app taking time that is not its to take. Same on a phone Android calls
        // low-memory, where those seconds are spent on the one thing nobody needs.
        if (pendingRoute != null || DeviceCheck.isSmallPhone(this)) {
            introShowing = false;
            if (started) {
                if (AppLock.isLocked(this)) AppLock.show(this, shell, this::consumeRoute);
                else consumeRoute();
            }
            return;
        }
        introShowing = true;
        final FrameLayout intro = new FrameLayout(this);
        intro.setBackgroundColor(Color.rgb(13, 27, 62));
        intro.setClickable(true);
        intro.setElevation(Ui.dp(this, 20));

        final LinearLayout first = introColumn();
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.icon_in_app);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        first.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 104)));
        TextView name = Ui.bold(this, "PocketLinux", 30, Color.WHITE);
        name.setGravity(Gravity.CENTER);
        name.setLetterSpacing(-0.02f);
        first.addView(name, Ui.matchWrap(this, 18));
        TextView line = Ui.text(this, "A Linux computer that runs locally on your phone", 14.5f, Color.rgb(190, 204, 240));
        line.setGravity(Gravity.CENTER);
        first.addView(line, Ui.matchWrap(this, 6));

        final LinearLayout second = introColumn();
        ImageView tux = new ImageView(this);
        tux.setImageResource(R.drawable.tux);
        tux.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tux.setContentDescription("Tux, the Linux mascot");
        second.addView(tux, new LinearLayout.LayoutParams(Ui.dp(this, 132), Ui.dp(this, 156)));
        TextView powered = Ui.bold(this, "Powered by Linux", 24, Color.WHITE);
        powered.setGravity(Gravity.CENTER);
        second.addView(powered, Ui.matchWrap(this, 18));
        TextView system = Ui.text(this, "Ubuntu 24.04 LTS · native ARM64 Linux apps · "
                + "the whole computer is on this phone",
                14f, Color.rgb(190, 204, 240));
        system.setGravity(Gravity.CENTER);
        system.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0);
        second.addView(system, Ui.matchWrap(this, 6));
        second.setAlpha(0f);

        FrameLayout.LayoutParams centre = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        intro.addView(first, centre);
        intro.addView(second, new FrameLayout.LayoutParams(centre));
        shell.addView(intro, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mark.setScaleX(0.9f); mark.setScaleY(0.9f);
        mark.animate().scaleX(1f).scaleY(1f).setDuration(420).start();
        // The opening screen must never end without deciding what happens next: with the lock on
        // it asks, and without it the shortcut the owner tapped is honoured here, because
        // onResume already ran and skipped it while this was up. Written once and called from
        // both the timer and a tap, so a tap really does skip it rather than running it twice.
        final Runnable[] finish = new Runnable[1];
        finish[0] = () -> {
            if (!introShowing) return;
            introShowing = false;
            intro.animate().alpha(0f).setDuration(220)
                    .withEndAction(() -> {
                        shell.removeView(intro);
                        if (started) {
                            if (AppLock.isLocked(this)) AppLock.show(this, shell, this::consumeRoute);
                            else consumeRoute();
                        }
                    }).start();
        };
        intro.setOnClickListener(v -> finish[0].run());
        handler.postDelayed(() -> {
            if (!introShowing) return;
            first.animate().alpha(0f).setDuration(220).start();
            second.animate().alpha(1f).setDuration(320).start();
        }, 700L);
        // 1.6 seconds, not 3.5. Long enough to read the name, short enough that nobody waits.
        handler.postDelayed(() -> finish[0].run(), 1600L);
    }

    private LinearLayout introColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        return column;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingRoute = intent == null ? null : intent.getStringExtra(EXTRA_ROUTE);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("tab", selectedTab);
        state.putInt("answer", openAnswer);
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        if (safeMode) return;
        AppLock.applyWindowSecurity(this);
        try {
            IntentFilter filter = new IntentFilter(LinuxService.ACTION_STATUS);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(statusReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Throwable error) {
            Crash.save(this, error);
        }
        if (AppLock.isLocked(this) && !introShowing) AppLock.show(this, shell, this::consumeRoute);
    }

    @Override protected void onResume() {
        super.onResume();
        desktopOpening = false;
        if (safeMode) return;
        handler.removeCallbacks(liveRefresh);
        LinuxService.reconcileUncleanStop(this);
        handler.post(liveRefresh);
        if (dataCapRow != null) dataCapRow.setValue(dataCapLabel());
        refreshDeviceCard();
        refreshPermissionRows();
        refreshLockRows();
        measureLinuxSize();
        maybeShowPermissionIntro();
        // Re-entering mid-setup should show the running job straight away, not an empty card.
        if (LinuxService.isBusy() || LinuxService.lastMessage() != null) {
            renderProgress(LinuxService.lastMessage(), LinuxService.lastDetail(),
                    LinuxService.lastProgress(), LinuxService.isBusy(), LinuxService.lastWasError());
        }
        if (!AppLock.showing(shell) && !introShowing) consumeRoute();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(liveRefresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        // The intro's two delayed callbacks and the 5-second live refresh must not run against
        // a destroyed screen: a theme change during the opening screen used to do exactly that.
        handler.removeCallbacksAndMessages(null);
        if (permissionIntro != null && permissionIntro.isShowing()) permissionIntro.dismiss();
        permissionIntro = null;
        super.onDestroy();
    }

    @Override protected void onStop() {
        started = false;
        if (receiverRegistered) {
            try { unregisterReceiver(statusReceiver); } catch (Throwable ignored) {}
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        if (AppLock.handleResult(this, shell, request, result, this::consumeRoute)) return;
        super.onActivityResult(request, result, data);
    }

    /** A launcher shortcut asked for the desktop: honoured once the lock has passed. */
    private void consumeRoute() {
        if (pendingRoute == null || AppLock.isLocked(this)) return;
        String route = pendingRoute;
        pendingRoute = null;
        if (getIntent() != null) getIntent().removeExtra(EXTRA_ROUTE);
        if ("desktop".equals(route) && ContainerRuntime.isInstalled(this) && !LinuxService.isBusy()) {
            startDesktop();
        } else {
            selectTab(TAB_HOME);
        }
    }

    // ---------------------------------------------------------------- shell and tabs

    private View buildScreen() {
        shell = new FrameLayout(this);
        shell.setBackgroundColor(dark ? Ui.DARK_BG : Ui.LIGHT_BG);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        shell.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pageHost = new FrameLayout(this);
        pages[TAB_HOME] = page(buildHomePage());
        pages[TAB_APPS] = page(buildAppsPage());
        pages[TAB_SETTINGS] = page(buildSettingsPage());
        for (ScrollView page : pages) {
            pageHost.addView(page, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        column.addView(pageHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        column.addView(buildNavBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        selectTab(selectedTab);
        return shell;
    }

    /** One scrolling page, its content no wider than a comfortable reading width. */
    private ScrollView page(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        content.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 24));
        FrameLayout centre = new FrameLayout(this);
        int max = Ui.dp(this, Ui.CONTENT_MAX_WIDTH_DP);
        int width = getResources().getDisplayMetrics().widthPixels > max ? max : ViewGroup.LayoutParams.MATCH_PARENT;
        centre.addView(content, new FrameLayout.LayoutParams(width,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        scroll.addView(centre, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /**
     * The bottom bar: opaque and one step off the page, so nothing scrolls under it; the
     * same three destinations always, with a dot only when something needs a person.
     */
    private View buildNavBar() {
        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.VERTICAL);
        navBar.setBackgroundColor(Ui.surface(dark));
        navBar.setElevation(Ui.dp(this, 3));
        View hairline = new View(this);
        hairline.setBackgroundColor(Ui.line(dark));
        navBar.addView(hairline, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1))));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        navItems[TAB_HOME] = new Ui.NavItem(this, R.drawable.ic_home, "Home", dark);
        navItems[TAB_APPS] = new Ui.NavItem(this, R.drawable.ic_apps, "Apps", dark);
        navItems[TAB_SETTINGS] = new Ui.NavItem(this, R.drawable.ic_settings, "Settings", dark);
        for (int i = 0; i < navItems.length; i++) {
            final int tab = i;
            navItems[i].setOnClickListener(v -> selectTab(tab));
            row.addView(navItems[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        navBar.addView(row);
        return navBar;
    }

    private void selectTab(int tab) {
        selectedTab = tab;
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] != null) pages[i].setVisibility(i == tab ? View.VISIBLE : View.GONE);
            if (navItems[i] != null) navItems[i].setActive(i == tab);
        }
    }

    // ---------------------------------------------------------------- Home

    private LinearLayout buildHomePage() {
        int text = Ui.text(dark);
        int muted = Ui.muted(dark);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        // One list of expandable answers for the whole page (the Linux-only card and the
        // questions card both add to it), reset once here and never again mid-build.
        answers.clear();
        page.addView(buildHeader(text, muted));
        page.addView(buildLiveTiles(), Ui.matchWrap(this, 14));
        page.addView(buildDesktopCard(text, muted), Ui.matchWrap(this, 14));
        page.addView(buildAttentionCard(text, muted));
        page.addView(buildDataCard(text, muted));
        Ui.addSpace(page, this, 6);
        page.addView(buildPhoneCard(text, muted));
        page.addView(buildWhyLinuxCard(text, muted));
        page.addView(buildQuestionsCard(text, muted));
        page.addView(versionLine(muted), Ui.matchWrap(this, 2));
        return page;
    }

    private TextView versionLine(int muted) {
        TextView version = Ui.text(this, "PocketLinux " + VERSION + " · Ubuntu 24.04 LTS · works on "
                + DeviceCheck.releaseName(DeviceCheck.MIN_SDK) + " and above", 12, muted);
        version.setGravity(Gravity.CENTER);
        return version;
    }

    private View buildHeader(int text, int muted) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.icon_in_app);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setContentDescription("PocketLinux");
        header.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams headingLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        headingLp.setMarginStart(Ui.dp(this, 12));
        header.addView(heading, headingLp);
        TextView name = Ui.bold(this, "PocketLinux", 22, text);
        name.setLetterSpacing(-0.015f);
        heading.addView(name);
        heading.addView(Ui.text(this, "A Linux computer that runs locally on your phone", 12.5f, muted));

        ImageView themeButton = Ui.icon(this, dark ? R.drawable.ic_light_mode : R.drawable.ic_dark_mode,
                Ui.accent(dark), 22);
        int pad = Ui.dp(this, 12);
        themeButton.setPadding(pad, pad, pad, pad);
        themeButton.setBackground(Ui.tappable(this, Ui.background(Ui.field(dark), 99, this), dark));
        themeButton.setContentDescription(dark ? "Switch to light theme" : "Switch to dark theme");
        themeButton.setOnClickListener(v -> {
            preferences.edit().putString(ContainerRuntime.KEY_THEME, dark ? "light" : "dark").apply();
            recreate();
        });
        header.addView(themeButton, new LinearLayout.LayoutParams(
                Ui.dp(this, Ui.TOUCH_TARGET_DP), Ui.dp(this, Ui.TOUCH_TARGET_DP)));
        return header;
    }

    private View buildLiveTiles() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        networkTile = new Ui.Tile(this, R.drawable.ic_network, "Network", dark);
        batteryTile = new Ui.Tile(this, R.drawable.ic_battery, "Battery", dark);
        storageTile = new Ui.Tile(this, R.drawable.ic_storage, "Free space", dark);
        heatTile = new Ui.Tile(this, R.drawable.ic_temperature, "Temperature", dark);
        Ui.Tile[] tiles = {networkTile, batteryTile, storageTile, heatTile};
        for (int i = 0; i < tiles.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < tiles.length - 1) lp.setMarginEnd(Ui.dp(this, 8));
            row.addView(tiles[i], lp);
        }
        return row;
    }

    private View buildDesktopCard(int text, int muted) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18));
        card.setBackground(Ui.brandGradient(this, 22));
        card.setElevation(Ui.dp(this, 2));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = Ui.dp(this, 12);
        card.setLayoutParams(cardLp);

        // The state on the left, Tux on the right: the mascot of the system that is running.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        top.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView tux = new ImageView(this);
        tux.setImageResource(R.drawable.tux);
        tux.setScaleType(ImageView.ScaleType.FIT_END);
        tux.setContentDescription("Tux, the Linux mascot");
        LinearLayout.LayoutParams tuxLp = new LinearLayout.LayoutParams(Ui.dp(this, 62), Ui.dp(this, 74));
        tuxLp.setMarginStart(Ui.dp(this, 12));
        top.addView(tux, tuxLp);
        card.addView(top);

        statusBadge = Ui.badge(this, "Checking", Color.WHITE, Color.argb(56, 255, 255, 255));
        heading.addView(statusBadge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusHeadline = Ui.bold(this, "Setting up", 24, Color.WHITE);
        statusHeadline.setLetterSpacing(-0.02f);
        heading.addView(statusHeadline, Ui.matchWrap(this, 12));

        statusNote = Ui.text(this, "", 13.5f, Color.rgb(214, 226, 255));
        card.addView(statusNote, Ui.matchWrap(this, 6));

        progressCard = new LinearLayout(this);
        progressCard.setOrientation(LinearLayout.VERTICAL);
        progressCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 14));
        progressCard.setBackground(Ui.background(Color.argb(48, 255, 255, 255), 15, this));
        progressCard.setVisibility(View.GONE);

        LinearLayout progressHead = new LinearLayout(this);
        progressHead.setOrientation(LinearLayout.HORIZONTAL);
        progressHead.setGravity(Gravity.CENTER_VERTICAL);
        progressTitle = Ui.title(this, "Working", 14, Color.WHITE);
        progressHead.addView(progressTitle,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        progressPercent = Ui.bold(this, "", 14, Color.WHITE);
        progressHead.addView(progressPercent);
        progressCard.addView(progressHead);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        progressCard.addView(progressBar, Ui.matchWrap(this, 8));

        progressDetail = Ui.text(this, "", 12.5f, Color.rgb(224, 233, 255));
        progressCard.addView(progressDetail, Ui.matchWrap(this, 6));
        card.addView(progressCard, Ui.matchWrap(this, 14));

        setupButton = Ui.primaryButton(this, "Set up Linux", R.drawable.ic_download);
        setupButton.setTextColor(Ui.PRIMARY_DEEP);
        setupButton.setBackground(Ui.tappable(this, Ui.background(Color.WHITE, 16, this), false));
        Ui.setStartIcon(setupButton, R.drawable.ic_download, Ui.PRIMARY_DEEP, this, 21);
        setupButton.setOnClickListener(v -> confirmSetup());
        card.addView(setupButton, Ui.matchWrap(this, 16));

        startButton = Ui.primaryButton(this, "Open desktop", R.drawable.ic_desktop);
        startButton.setTextColor(Ui.PRIMARY_DEEP);
        startButton.setBackground(Ui.tappable(this, Ui.background(Color.WHITE, 16, this), false));
        Ui.setStartIcon(startButton, R.drawable.ic_desktop, Ui.PRIMARY_DEEP, this, 21);
        startButton.setOnClickListener(v -> startDesktop());
        card.addView(startButton, Ui.matchWrap(this, 10));

        stopButton = translucentButton("Stop", R.drawable.ic_stop);
        stopButton.setOnClickListener(v -> sendServiceAction(LinuxService.ACTION_STOP));
        card.addView(stopButton, Ui.matchWrap(this, 10));
        return card;
    }

    private Button translucentButton(String label, int iconRes) {
        Button button = Ui.primaryButton(this, label, 0);
        button.setTextColor(Color.WHITE);
        button.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        button.setBackground(Ui.tappable(this,
                Ui.outlined(Color.argb(38, 255, 255, 255), Color.argb(96, 255, 255, 255), 16, this), true));
        Ui.setStartIcon(button, iconRes, Color.WHITE, this, 19);
        return button;
    }

    /**
     * What needs a person, and nothing else. Each row opens the place that fixes it. Shown
     * only while something is wrong; a settled phone never sees this card at all.
     */
    private View buildAttentionCard(int text, int muted) {
        attentionCard = Ui.card(this, dark);
        attentionCard.addView(Ui.sectionTitle(this, "Needs attention", R.drawable.ic_shield, dark));
        attentionNotifications = attentionRow(R.drawable.ic_notification, "Notifications are off",
                "Setup progress and the Stop button cannot be shown. Tap to allow.",
                v -> requestNotificationPermission(true), true);
        attentionBattery = attentionRow(R.drawable.ic_bolt, "Battery use is restricted",
                "Android may stop a long setup when the screen turns off. Tap to set Unrestricted.",
                v -> openBatterySettings(), false);
        attentionSpace = attentionRow(R.drawable.ic_storage, "Free space is low",
                "", v -> toggleDetail(attentionSpace, spaceDetail, "The Linux computer needs "
                        + DeviceProbe.formatBytes(DeviceCheck.MIN_FREE_BYTES) + " free to set up and about "
                        + DeviceProbe.formatBytes(DeviceCheck.LOW_FREE_BYTES) + " free to run "
                        + "comfortably. PocketLinux has no quota of its own — the computer grows into "
                        + "the phone's free space, so this is the phone filling up, not the computer. "
                        + "Around 500 MB free Android clears app caches by itself; your files and the "
                        + "computer are not touched, but a new download or install would stop part-way. "
                        + "Delete or move some files on the phone, or remove an AI app you are not "
                        + "using from the Apps tab; nothing inside the Linux computer has to go."), false);
        spaceDetail = detailUnder(attentionCard);
        attentionHeat = attentionRow(R.drawable.ic_temperature, "The phone is hot",
                "Let it cool before opening the desktop; it stops itself above 49 °C to protect the battery.",
                v -> toggleDetail(attentionHeat, heatDetail, "A warm phone is normal while an AI app runs. "
                        + "Above 49 °C, or when Android reports critical heat, the Linux computer stops "
                        + "itself and everything on it is kept. Overheat protection can be turned off in Settings, "
                        + "but there is no good reason to."), false);
        heatDetail = detailUnder(attentionCard);
        attentionData = attentionRow(R.drawable.ic_network, "Today's mobile data limit is used up",
                "Downloads and the desktop wait for Wi-Fi, midnight, or a higher limit. Tap to change it.",
                v -> { selectTab(TAB_SETTINGS); chooseDataCap(); }, false);
        attentionLock = attentionRow(R.drawable.ic_lock, "App lock turned itself off",
                "The phone's screen lock was removed. Set one, then turn App lock on again.",
                v -> { selectTab(TAB_SETTINGS); openSecuritySettings(); }, false);
        attentionCompatible = attentionRow(R.drawable.ic_stop, "This phone does not meet the requirements",
                "Tap for what is missing.", v -> toggleDetail(attentionCompatible, compatibleDetail, DeviceCheck.run(this).detail), false);
        compatibleDetail = detailUnder(attentionCard);
        attentionCard.setVisibility(View.GONE);
        return attentionCard;
    }

    private TextView spaceDetail;
    private TextView heatDetail;
    private TextView compatibleDetail;
    private TextView phoneDetail;

    /** A hidden paragraph under a row, for the row's tap to open in place: no dialog to dismiss. */
    private TextView detailUnder(LinearLayout card) {
        TextView body = Ui.text(this, "", 12.5f, Ui.muted(dark));
        body.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 10));
        body.setTextIsSelectable(true);
        body.setVisibility(View.GONE);
        card.addView(body, Ui.matchWrap(this, 0));
        return body;
    }

    private void toggleDetail(Ui.Row row, TextView body, String text) {
        if (body == null) return;
        if (body.getVisibility() == View.VISIBLE) {
            body.setVisibility(View.GONE);
            if (row != null) row.setExpanded(false);
            return;
        }
        body.setText(text);
        body.setVisibility(View.VISIBLE);
        if (row != null) row.setExpanded(true);
    }

    private Ui.Row attentionRow(int icon, String title, String value, View.OnClickListener onClick, boolean first) {
        Ui.Row row = new Ui.Row(this, icon, title, value, R.drawable.ic_chevron, dark, onClick);
        row.setVisibility(View.GONE);
        attentionCard.addView(row, Ui.matchWrap(this, first ? 12 : 8));
        return row;
    }

    /**
     * Today's mobile data, as a meter, while a limit is set. An app that is deliberately
     * holding downloads back looks like an app that has quietly stopped, unless it says so.
     */
    private View buildDataCard(int text, int muted) {
        dataCard = Ui.card(this, dark);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.sectionTitle(this, "Mobile data today", R.drawable.ic_network, dark),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        dataFigure = Ui.title(this, "", 13f, muted);
        head.addView(dataFigure);
        dataCard.addView(head);
        dataBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        dataBar.setMax(100);
        dataBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.accent(dark)));
        dataBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.line(dark)));
        dataCard.addView(dataBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 10)));
        ((LinearLayout.LayoutParams) dataBar.getLayoutParams()).topMargin = Ui.dp(this, 12);
        dataNote = Ui.text(this, "", 12.5f, muted);
        dataCard.addView(dataNote, Ui.matchWrap(this, 8));
        dataCard.setClickable(true);
        dataCard.setOnClickListener(v -> { selectTab(TAB_SETTINGS); chooseDataCap(); });
        dataCard.setVisibility(View.GONE);
        return dataCard;
    }

    private View buildPhoneCard(int text, int muted) {
        LinearLayout card = Ui.card(this, dark);
        card.addView(Ui.sectionTitle(this, "Your phone", R.drawable.ic_phone, dark));
        card.addView(Ui.text(this, "Detected automatically. Nothing to type in.", 12.5f, muted),
                Ui.matchWrap(this, 6));
        deviceDetails = Ui.text(this, "Reading…", 13.5f, text);
        deviceDetails.setLineSpacing(Ui.dp(this, 5), 1f);
        card.addView(deviceDetails, Ui.matchWrap(this, 12));

        DeviceCheck.Result check = DeviceCheck.run(this);
        compatibleRow = new Ui.Row(this, check.compatible ? R.drawable.ic_check : R.drawable.ic_stop,
                check.headline, "Tap for the requirements and what this phone has",
                R.drawable.ic_chevron, dark, v -> toggleDetail(compatibleRow, phoneDetail, DeviceCheck.run(this).detail));
        compatibleRow.setStatus(!check.compatible ? "NOT COMPATIBLE"
                        : check.desktopOnly ? "DESKTOP ONLY" : "COMPATIBLE",
                !check.compatible ? Ui.DANGER : check.desktopOnly ? Ui.WARNING : Ui.SUCCESS);
        card.addView(compatibleRow, Ui.matchWrap(this, 10));
        phoneDetail = detailUnder(card);
        return card;
    }

    /**
     * Why the computer is Linux and only Linux, in checked facts rather than opinion. Every
     * date, name and number here was confirmed against the publisher's own announcement or a
     * published survey when this version was built; nothing that could change next month is
     * stated as permanent.
     */
    private View buildWhyLinuxCard(int text, int muted) {
        LinearLayout card = Ui.card(this, dark);
        card.addView(Ui.sectionTitle(this, "Linux only, on purpose", R.drawable.ic_desktop, dark));
        card.addView(Ui.text(this,
                "The computer inside this app is Ubuntu 24.04 LTS — Canonical's own ARM64 system "
                        + "— running on the phone's own processor. Its desktop is built from small "
                        + "standard parts (Openbox for the windows, tint2 for the bar, PCManFM for the "
                        + "wallpaper and files), not from a large desktop system like GNOME or KDE, so "
                        + "the memory goes to the apps. It is built for one job and complete for it: an "
                        + "agentic development environment where the official AI desktop apps, Google "
                        + "Chrome and the developer tools run locally. Basic on purpose — and because "
                        + "it is a real Ubuntu, with apt and sudo, you can make it as advanced as you "
                        + "like. Tap a line for the facts behind it.", 12.5f, muted),
                Ui.matchWrap(this, 6));
        addAnswer(card, R.drawable.ic_desktop, "Why Ubuntu — the system AI agents work in",
                "Ubuntu is the Linux that development runs on, and the one AI agents are given "
                        + "when they are given a computer.\n\n"
                        + "• When an AI agent works in the cloud, it works inside a Linux container, "
                        + "and Ubuntu is the usual choice: OpenAI's Codex cloud environments start "
                        + "from an image built on Ubuntu 24.04 — the same release running in this "
                        + "app.\n"
                        + "• It is the Linux developers themselves use most: about 28 % of the "
                        + "49,000 developers in Stack Overflow's 2025 survey, and the same share at "
                        + "work as at home — roughly two and a half times the next distribution.\n"
                        + "• Every publisher here ships their Linux build for Ubuntu and Debian "
                        + "first, so their instructions, their packages and their support all match "
                        + "what is on this phone.\n"
                        + "• 24.04 LTS is a long-term-support release: Canonical delivers security "
                        + "updates for it until April 2029, so a computer set up today is still "
                        + "supported years from now.\n\n"
                        + "So this is not a cut-down phone Linux — it is the same Ubuntu 24.04 LTS "
                        + "that developers and AI agents work on every day, running on your phone's "
                        + "own processor.", true);

        addAnswer(card, R.drawable.ic_info, "Is this a complete operating system?",
                "Almost, and here is the honest line. Everything an operating system has above the "
                        + "kernel is here, and it is Ubuntu's own: apt and dpkg, the C library, the "
                        + "shell, the desktop, the fonts, the drivers Ubuntu ships in user space. The "
                        + "kernel is the phone's — and the phone's kernel is Linux, which is why "
                        + "Ubuntu's ARM64 programs run on it unchanged.\n\n"
                        + "So \u201ca real Ubuntu 24.04 LTS system running on this phone\u201d is "
                        + "true, and this app will not tell you otherwise: it is not a second "
                        + "operating system on your phone, not dual boot, and not an emulator. None "
                        + "of those is what this is, and on a phone each would be slower or "
                        + "impossible.\n\n"
                        + "What the phone's kernel does not hand over, this system does not have: no "
                        + "control of the phone's hardware, no graphics chip, no kernel modules of its "
                        + "own, no systemd. That is the whole of the difference — and none of it stops "
                        + "apt, the desktop or the AI apps from working exactly as they do on a PC.",
                false);

        addAnswer(card, R.drawable.ic_check, "Every AI desktop app here ships for Linux",
                "OpenAI released the ChatGPT desktop app for Linux (with Codex) as a public preview "
                        + "on 11 August 2026, for Ubuntu 24.04 LTS and 26.04 LTS, Debian 13 and "
                        + "Fedora, on x64 and ARM64. Anthropic released Claude Desktop for Linux "
                        + "(with Claude Code) as a beta on 30 June 2026, for Ubuntu and Debian on "
                        + "x64 and ARM64, from its own apt repository. Cursor publishes Linux ARM64 "
                        + ".deb and AppImage builds, and Google publishes Antigravity for Linux. "
                        + "PocketLinux installs exactly those packages, from each publisher's own "
                        + "servers, and every app updates the way its publisher ships updates.", false);
        addAnswer(card, R.drawable.ic_info, "Some tools only exist on one system \u2014 and the AI ones are on Linux",
                "Software is not always free to run anywhere. Some of it is tied to one system by "
                        + "the frameworks it is built on, and sometimes by a licence that says so "
                        + "outright, and no amount of cleverness moves it.\n\n"
                        + "• Xcode, the iOS Simulator and SwiftUI previews are macOS programs. "
                        + "They need Apple's own frameworks and Apple's licence keeps macOS on "
                        + "Apple hardware, so an iPhone app is signed on a Mac or on someone "
                        + "else's Mac. Nothing anywhere changes that.\n"
                        + "• Visual Studio, DirectX and the old .NET Framework are Windows "
                        + "programs, for the same kind of reason from the other direction.\n"
                        + "• Containers, systemd, the whole server world: Linux. Docker on a Mac "
                        + "or on Windows is a Linux machine running quietly underneath \u2014 that "
                        + "is what it has always been.\n\n"
                        + "So the system you are on decides what you can build, and that is worth "
                        + "knowing before you pick one. Here it decided well: every AI desktop app "
                        + "in this app ships a Linux build, and the two coding agents \u2014 Claude "
                        + "Code and Codex \u2014 were Linux-shaped from the start, because a Linux "
                        + "shell is what an agent is given when it is given a computer. This is "
                        + "not a phone imitating a desktop and hoping the apps follow. It is the "
                        + "system those apps are written for, on the processor in your hand, with "
                        + "the same apt, the same packages and the same instructions their own "
                        + "documentation gives.", false);
        addAnswer(card, R.drawable.ic_check, "Why Linux and not Windows or macOS",
                "Because on a phone, Linux is the only one of the three that is real \u2014 and, "
                        + "on this processor, it is also the best supported.\n\n"
                        + "WINDOWS CANNOT RUN HERE. Not as a preference: as a fact, with three "
                        + "separate causes. Android's own hypervisor (the Android Virtualization "
                        + "Framework) is documented as being for privileged and platform apps, so "
                        + "an ordinary installed app cannot start a virtual machine. A "
                        + "compatibility layer instead of a virtual machine hits the second wall: "
                        + "the one project that ran Windows programs on ARM64 dropped its "
                        + "Android support. And the third is this container itself \u2014 it "
                        + "already uses ptrace for every system call, and an x86 translator "
                        + "layered on top of that is the exact combination that fails.\n\n"
                        + "ON ARM64, LINUX IS AHEAD OF WINDOWS. Not behind it. Claude's Cowork is "
                        + "not supported on Windows ARM64 at all, and Claude Code on Windows ARM64 "
                        + "has an open crash report; both work on Linux ARM64. Windows' own ARM "
                        + "story still leans on translating Intel code, and these apps are among "
                        + "the ones that lean hardest.\n\n"
                        + "macOS IS NOT LICENSED TO RUN ANYWHERE BUT APPLE'S OWN HARDWARE, so it "
                        + "was never a candidate.\n\n"
                        + "AND LINUX IS THE ONE THAT LASTS. Ubuntu 24.04 LTS has security updates "
                        + "to April 2029, to April 2036 with Ubuntu Pro (free for personal use), "
                        + "and to April 2039 with the Legacy add-on \u2014 fifteen years, on a "
                        + "base that never forces an upgrade. Each Windows release gets about "
                        + "twenty-four months before the next one is required.", false);
        addAnswer(card, R.drawable.ic_shield, "Safe, private, and yours",
                "Everything lives in this app's private storage on this phone: the system, the "
                        + "apps, their logins, your files. No PocketLinux account, no server, no "
                        + "analytics; Android's cloud backup is switched off for this app. Ubuntu "
                        + "24.04 LTS receives security updates from Canonical until April 2029 "
                        + "(and to 2034 with Ubuntu Pro), so the base does not go stale, and the "
                        + "AI apps update from their publishers for as long as they ship updates.", false);
        addAnswer(card, R.drawable.ic_bolt, "Fast for a phone, and built to last",
                "The native Linux apps are ARM64 programs running directly on the "
                        + "phone's ARM64 processor. What a phone lacks is a graphics card and a "
                        + "PC's memory, so a heavy app takes a minute or two to open the first "
                        + "time and one AI app at a time is the comfortable way to work. Linux "
                        + "runs most of the world's servers and every one of these companies' "
                        + "own engineering desktops, which is why it is the platform they keep "
                        + "shipping to first; there is nothing to move to later.", false);
        return card;
    }

    // ---------------------------------------------------------------- Apps

    private LinearLayout buildAppsPage() {
        int text = Ui.text(dark);
        int muted = Ui.muted(dark);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        appRows.clear();
        page.addView(buildAppsCard(text, muted));
        page.addView(buildDeveloperCard(text, muted));
        page.addView(buildOtherAppsCard(text, muted));
        page.addView(versionLine(muted), Ui.matchWrap(this, 2));
        return page;
    }

    private View buildAppsCard(int text, int muted) {
        LinearLayout card = Ui.card(this, dark);
        card.addView(Ui.sectionTitle(this, "AI desktop apps", R.drawable.ic_apps, dark));
        card.addView(Ui.text(this, "The official Linux apps from OpenAI, Anthropic, Anysphere and "
                + "Google. Tap a row to install; tap an installed row to update or uninstall it.",
                13f, text), Ui.matchWrap(this, 8));
        card.addView(Ui.text(this, "ARM64 · runs locally on your phone · updates from the publisher",
                12f, Ui.accent(dark)), Ui.matchWrap(this, 6));
        card.addView(Ui.text(this, "The computer already has everything these apps need: the desktop, "
                + "Google Chrome and the developer tools (Python, Node.js, Git and a C/C++ compiler), "
                + "all installed by set-up. Nothing else to add before these.",
                12.5f, muted), Ui.matchWrap(this, 6));
        appsNote = Ui.text(this, "", 12.5f, Ui.WARNING);
        appsNote.setVisibility(View.GONE);
        card.addView(appsNote, Ui.matchWrap(this, 8));

        int added = 0;
        for (LinuxApps.App app : LinuxApps.CATALOG) {
            if (!LinuxApps.isAiApp(app)) continue;
            card.addView(appRow(app), Ui.matchWrap(this, added++ == 0 ? 12 : 8));
        }
        card.addView(Ui.text(this, "Signing in happens in the browser inside the desktop, exactly as "
                + "on a computer, and the app stays signed in afterwards. Home → Privacy and your "
                + "questions explains each app's sign-in.", 12.5f, muted), Ui.matchWrap(this, 12));
        return card;
    }

    private Ui.Row appRow(LinuxApps.App app) {
        Ui.Row row = new Ui.Row(this, app.displayIcon(), app.logoRes != 0, app.name,
                app.summary + " · " + app.approximateSize, R.drawable.ic_download, dark,
                v -> confirmApp(app));
        appRows.put(app.id, row);
        return row;
    }

    /**
     * Developer tools that are not part of the basics: the mobile-app toolchain.
     *
     * Its own card because it is a different promise from the four AI apps -- 700 MB that most
     * owners will never need, and one real limit inside it that has to be said before it is
     * installed, not after.
     */
    private View buildDeveloperCard(int text, int muted) {
        LinearLayout card = Ui.card(this, dark);
        card.addView(Ui.sectionTitle(this, "Mobile app development", R.drawable.ic_terminal, dark));
        card.addView(Ui.text(this,
                "Java 21, Gradle, adb, fastboot, aapt and scrcpy — everything for writing an "
                        + "Android app here and trying it on a real phone.",
                12.5f, muted), Ui.matchWrap(this, 6));
        LinuxApps.App mobile = LinuxApps.byId("mobiledev");
        if (mobile != null) card.addView(appRow(mobile), Ui.matchWrap(this, 12));
        card.addView(Ui.text(this, "Test on a real phone — including this one", 13.5f, text),
                Ui.matchWrap(this, 14));
        card.addView(Ui.text(this,
                "Android's Wireless debugging listens on the phone's own network, and this "
                        + "computer shares that network — so 127.0.0.1 reaches the phone it is "
                        + "running on. Build an APK here, install it here, and it opens on this "
                        + "screen. Another phone on the same Wi-Fi works the same way.\n\n"
                        + "Inside the desktop: Tools → Phone app testing → Pair a phone. It walks "
                        + "you through turning Wireless debugging on and takes the pairing code.",
                12.5f, muted), Ui.matchWrap(this, 6));
        card.addView(Ui.text(this,
                "Two honest limits. Google publishes no ARM64 Linux build of aapt2, so a full "
                        + "Android Gradle build may stop at that one tool — compiling and testing "
                        + "on a device work, and that gap is Google's to close. And an Android "
                        + "emulator cannot run here at all: it needs hardware virtualisation, "
                        + "which no app on an unrooted phone can have. A real phone is the test "
                        + "device.",
                12.5f, Ui.WARNING), Ui.matchWrap(this, 10));
        return card;
    }



    /** What the browser can install and what it cannot, in one card, short. */
    private View buildOtherAppsCard(int text, int muted) {
        LinearLayout card = Ui.card(this, dark);
        card.addView(Ui.sectionTitle(this, "Install an app you downloaded", R.drawable.ic_download, dark));
        card.addView(Ui.text(this,
                "The four above are simply the best of their kind today — you can install any "
                        + "other Linux app yourself, and it works the way installing an APK from a "
                        + "website works on the phone.", 12.5f, muted),
                Ui.matchWrap(this, 6));
        card.addView(Ui.text(this, "How", 13.5f, text), Ui.matchWrap(this, 12));
        card.addView(Ui.text(this,
                "In the desktop, download the app's Linux build for ARM64 (a .deb file) in Chrome "
                        + "and open it. PocketLinux's installer names the app and its publisher, "
                        + "shows its size against this phone's free space, checks the processor and "
                        + "the software it needs, and says plainly that a downloaded file is not "
                        + "signed. Then Install anyway, or a blocked install with the reason. The "
                        + "Apps menu on the desktop also has “Install a downloaded app”.",
                12.5f, muted), Ui.matchWrap(this, 4));
        card.addView(Ui.text(this, "Works", 13.5f, text), Ui.matchWrap(this, 10));
        card.addView(Ui.text(this,
                "Ubuntu and Debian packages for ARM64: .deb files marked arm64 or aarch64. "
                        + "Programs in .tar.gz form built for arm64.", 12.5f, muted),
                Ui.matchWrap(this, 4));
        card.addView(Ui.text(this, "Does not work", 13.5f, text), Ui.matchWrap(this, 10));
        card.addView(Ui.text(this,
                "Anything built only for amd64 or x86 PCs. AppImage files — they mount themselves "
                        + "with FUSE, which a phone container cannot provide. Snap and Flatpak "
                        + "packages. Android .apk files. Apps that need a real "
                        + "graphics card or hardware virtualisation. The installer refuses each of "
                        + "these with the reason rather than failing silently.",
                12.5f, muted), Ui.matchWrap(this, 4));
        return card;
    }

    private void refreshAppRows(boolean linuxInstalled, boolean busy, boolean running) {
        int installed = 0;
        // BUSY includes the desktop's long-lived process. A running desktop is allowed beside an
        // app install; only setup/remove/another non-desktop task makes the rows unavailable.
        boolean taskBusy = busy && !running;
        // Grey rows say why they are grey.
        if (appsNote != null) {
            String why = null;
            if (!linuxInstalled && taskBusy) why = "The Linux computer is being set up. These can be added as soon as it is ready.";
            else if (!linuxInstalled) why = "Set up the Linux computer on the Home tab first. Then each of these installs with one tap.";
            else if (LinuxService.isInstalling()) why = "An app is installing. One at a time; the others follow.";
            else if (taskBusy) why = "Another task is running. These can be added when it finishes.";
            appsNote.setText(why == null ? "" : why);
            appsNote.setVisibility(why == null ? View.GONE : View.VISIBLE);
        }
        for (LinuxApps.App app : LinuxApps.CATALOG) {
            Ui.Row row = appRows.get(app.id);
            if (row == null) continue;
            boolean present = linuxInstalled && ContainerRuntime.isAppInstalled(this, app);
            if (present && LinuxApps.isAiApp(app)) installed++;
            row.setStatus(present ? "ADDED" : "ADD", present ? Ui.SUCCESS : Ui.accent(dark));
            row.setValue(present
                    ? "Installed · tap to update" + (app.removable() ? " or uninstall" : "")
                    : app.summary + " · " + app.approximateSize);
            // An open desktop is no obstacle: the install runs beside it and the new app
            // appears on it. Only a task already running (setup, another install) waits.
            boolean usable = linuxInstalled && !taskBusy && !LinuxService.isInstalling();
            row.setEnabled(usable);
            row.setAlpha(usable ? 1f : 0.45f);
        }
        // Linux is set up but no AI app is on it yet: the one trip worth a dot.
        if (navItems[TAB_APPS] != null) {
            navItems[TAB_APPS].setDot(linuxInstalled && installed == 0 && !taskBusy);
        }
    }

    private void confirmApp(LinuxApps.App app) {
        if (!ContainerRuntime.isInstalled(this)) {
            showMessage("Set up Linux first", "Set up the Linux computer once on the Home tab; "
                    + "then the AI desktop apps can be added to it.");
            return;
        }
        boolean present = ContainerRuntime.isAppInstalled(this, app);
        StringBuilder message = new StringBuilder(present
                ? "Already installed. This fetches the newest build from the publisher and "
                        + "updates it in place — your login and settings stay."
                : app.summary + "\n\n" + (app.repoSigned
                        ? "Installed from the publisher's own apt repository: the package is checked "
                                + "against their signing key, and one that does not match is refused."
                        : "Downloaded straight from the publisher's own site over an encrypted "
                                + "connection — they publish no Linux repository yet, so there is no "
                                + "signature to check, only who it came from.")
                        + " Nothing is downloaded from a browser or a link.");
        message.append("\n\nDownload size: ").append(app.approximateSize)
                .append(present ? "" : "\nAlways installs the newest build.");
        // Measured on this phone, now: the app's own size is the same everywhere, the space
        // and memory it has to fit into are not.
        message.append("\n\n").append(fitOnThisPhone(app));
        if (app.caution != null) message.append("\n\n").append(app.caution);
        if (LinuxService.isDesktopRunning()) {
            message.append("\n\nThe desktop keeps running while this installs; the app appears on it when done.");
        }
        AlertDialog.Builder builder = dialogBuilder()
                .setTitle((present ? "Update " : "Install ") + app.name + "?")
                .setMessage(message.toString())
                .setNegativeButton("Cancel", null)
                .setPositiveButton(present ? "Update" : "Install", (dialog, which) ->
                        sendAppTask(LinuxService.ACTION_INSTALL_APP, app.id));
        // An installed AI app can be uninstalled on its own, so a phone tight on space can take
        // one back without touching the computer. The computer's own basics have no such button:
        // they are the computer, and go only when it does.
        if (present && app.removable()) {
            builder.setNeutralButton("Uninstall", (dialog, which) -> confirmRemoveApp(app));
        }
        builder.show();
    }

    /**
     * What this app needs, against what this phone has, read now rather than quoted.
     *
     * The download size and what an app needs are the same on every phone; the free space,
     * the memory and therefore the answer are not. This is the line that turns a requirement
     * into "yes, on this phone".
     */
    private String fitOnThisPhone(LinuxApps.App app) {
        DeviceProbe probe = lastProbe;
        try {
            if (probe == null) probe = DeviceProbe.read(this);
        } catch (Throwable error) {
            return "Needs " + DeviceProbe.formatBytes(app.needsBytes) + " of free space.";
        }
        String needs = DeviceProbe.formatBytes(app.needsBytes);
        String free = DeviceProbe.formatBytes(probe.freeStorage);
        StringBuilder line = new StringBuilder("On this phone: ").append(free).append(" free");
        if (probe.freeStorage >= app.needsBytes) {
            line.append(" — enough (it needs ").append(needs).append(").");
        } else {
            line.append(" — not enough: it needs ").append(needs)
                    .append(". Free some space first, or uninstall an app you are not using.");
        }
        if (LinuxApps.isAiApp(app) && probe.totalRam > 0) {
            line.append("\nMemory: ").append(DeviceProbe.formatBytes(probe.totalRam))
                    .append(" — enough for one AI app at a time.");
        }
        return line.toString();
    }

    private void confirmRemoveApp(LinuxApps.App app) {
        dialogBuilder()
                .setTitle("Uninstall " + app.name + "?")
                .setMessage("Deletes the app and frees the space it uses. Its sign-in and its "
                        + "settings go with it; install it again any time from this tab and sign in "
                        + "once more. The Linux computer, your files and every other app stay as "
                        + "they are.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Uninstall", (d, w) -> sendAppTask(LinuxService.ACTION_UNINSTALL_APP, app.id))
                .show();
    }




    private void sendAppTask(String action, String appId) {
        Intent intent = new Intent(this, LinuxService.class).setAction(action)
                .putExtra(LinuxService.EXTRA_APP_ID, appId);
        requestNotificationPermission(false);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (Throwable error) {
            showMessage("Could not start", "Android refused to start the background task.");
        }
        selectTab(TAB_HOME);
        refreshState();
    }

    // ---------------------------------------------------------------- Settings

    private LinearLayout buildSettingsPage() {
        int text = Ui.text(dark);
        int muted = Ui.muted(dark);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        // Appearance
        LinearLayout appearance = group(page, "Appearance");
        appearanceRow = new Ui.Row(this, R.drawable.ic_palette, "Theme",
                labelOf(THEME_LABELS, THEME_VALUES, preferences.getString(ContainerRuntime.KEY_THEME, "system")),
                R.drawable.ic_chevron, dark, v -> chooseText("Theme", THEME_LABELS, THEME_ICONS,
                THEME_VALUES, ContainerRuntime.KEY_THEME, "system", appearanceRow, true));
        appearance.addView(appearanceRow, Ui.matchWrap(this, 0));
        rotationRow = new Ui.Row(this, R.drawable.ic_rotate, "Screen rotation",
                labelOf(ROTATION_LABELS, ROTATION_VALUES,
                        preferences.getString(ContainerRuntime.KEY_ORIENTATION, "auto")),
                R.drawable.ic_chevron, dark, v -> chooseText("Screen rotation", ROTATION_LABELS, ROTATION_ICONS,
                ROTATION_VALUES, ContainerRuntime.KEY_ORIENTATION, "auto", rotationRow, false));
        appearance.addView(rotationRow, Ui.matchWrap(this, 8));
        desktopScaleRow = new Ui.Row(this, R.drawable.ic_desktop, "Desktop text size",
                labelOfInt(SCALE_LABELS, SCALE_VALUES,
                        preferences.getInt(ContainerRuntime.KEY_UI_SCALE,
                                ContainerRuntime.defaultUiScale(this))),
                R.drawable.ic_chevron, dark, v -> chooseScale());
        appearance.addView(desktopScaleRow, Ui.matchWrap(this, 8));

        // Running
        LinearLayout running = group(page, "Running");
        autoStopRow = new Ui.Row(this, R.drawable.ic_timer, "When to stop by itself",
                labelOfInt(TIMER_LABELS, TIMER_VALUES,
                        preferences.getInt(ContainerRuntime.KEY_SESSION_MINUTES,
                                ContainerRuntime.SESSION_SMART)),
                R.drawable.ic_chevron, dark, v -> chooseTimer());
        running.addView(autoStopRow, Ui.matchWrap(this, 0));
        Ui.Toggle guard = new Ui.Toggle(this, R.drawable.ic_shield, "Overheat protection",
                "Stops the Linux computer if the phone gets too hot; everything on it is kept",
                preferences.getBoolean(ContainerRuntime.KEY_THERMAL_GUARD, true), dark);
        guard.control.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(ContainerRuntime.KEY_THERMAL_GUARD, checked).apply());
        running.addView(guard, Ui.matchWrap(this, 8));

        // Data and files
        LinearLayout data = group(page, "Data and files");
        dataCapRow = new Ui.Row(this, R.drawable.ic_network, "Mobile data limit per day",
                dataCapLabel(), R.drawable.ic_chevron, dark, v -> chooseDataCap());
        data.addView(dataCapRow, Ui.matchWrap(this, 0));
        Ui.Toggle wifiOnly = new Ui.Toggle(this, R.drawable.ic_wifi, "Download on Wi-Fi only",
                "Off means mobile data is allowed for setup and app installs",
                preferences.getBoolean(ContainerRuntime.KEY_WIFI_ONLY, false), dark);
        wifiOnly.control.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(ContainerRuntime.KEY_WIFI_ONLY, checked).apply());
        data.addView(wifiOnly, Ui.matchWrap(this, 8));
        downloadTargetRow = new Ui.Row(this, R.drawable.ic_download, "Downloads go to",
                downloadTargetValue(), R.drawable.ic_chevron, dark, v -> chooseDownloadTarget());
        data.addView(downloadTargetRow, Ui.matchWrap(this, 8));
        data.addView(Ui.text(this, "Ask every time is safest: Chrome asks for each download and "
                + "Computer Downloads is the fallback for an app that cannot ask. Phone Downloads "
                + "saves into Download/PocketLinux, visible to Android's Files app; it needs Phone "
                + "files permission below. Existing files are never moved when this changes.",
                12.5f, muted), Ui.matchWrap(this, 10));

        // Privacy and safety
        LinearLayout privacy = group(page, "Privacy and safety");
        appLockToggle = new Ui.Toggle(this, R.drawable.ic_lock, "App lock",
                "Asks for your fingerprint or the phone's PIN right after the opening screen, and "
                        + "again whenever PocketLinux comes back to the front — the home screen and "
                        + "the desktop both. No separate password to remember.",
                preferences.getBoolean(ContainerRuntime.KEY_APP_LOCK, false), dark);
        appLockToggle.control.setOnCheckedChangeListener((b, checked) -> onAppLockToggled(checked));
        privacy.addView(appLockToggle, Ui.matchWrap(this, 0));
        lockNoticeRow = new Ui.Row(this, R.drawable.ic_lock, "App lock turned itself off",
                "The phone's screen lock was removed. Set one in Android settings, then turn App lock on again.",
                R.drawable.ic_open_in_new, dark, v -> openSecuritySettings());
        lockNoticeRow.setStatus("NOTE", Ui.WARNING);
        lockNoticeRow.setVisibility(View.GONE);
        privacy.addView(lockNoticeRow, Ui.matchWrap(this, 8));
        privacy.addView(Ui.text(this, "Everything — the Linux computer, its apps, their logins "
                + "and your files — stays in this app's private storage on this phone. Android's "
                + "own cloud backup is switched off for this app, so nothing is ever copied to a "
                + "server.", 12.5f, muted), Ui.matchWrap(this, 10));

        // Permissions
        LinearLayout permissions = group(page, "Permissions");
        permissions.addView(Ui.text(this, "PocketLinux asks for the minimum it needs. Tap a row to change it.",
                12.5f, muted), Ui.matchWrap(this, 0));
        notificationRow = new Ui.Row(this, R.drawable.ic_notification, "Notifications", "Checking…",
                R.drawable.ic_open_in_new, dark, v -> requestNotificationPermission(true));
        permissions.addView(notificationRow, Ui.matchWrap(this, 10));
        batteryOptimisationRow = new Ui.Row(this, R.drawable.ic_bolt, "Battery usage", "Checking…",
                R.drawable.ic_open_in_new, dark, v -> openBatterySettings());
        permissions.addView(batteryOptimisationRow, Ui.matchWrap(this, 8));
        Ui.Row backgroundRow = new Ui.Row(this, R.drawable.ic_auto_mode, "Background activity",
                "On the phone's battery page for PocketLinux, turn ON Allow foreground activity and "
                        + "Allow background activity, so the computer keeps running with the screen off",
                R.drawable.ic_open_in_new, dark, v -> openBackgroundActivitySettings());
        backgroundRow.setStatus("CHECK", Ui.muted(dark));
        permissions.addView(backgroundRow, Ui.matchWrap(this, 8));
        autoStartRow = new Ui.Row(this, R.drawable.ic_power, "Auto-launch",
                "Turn ON Allow auto-launch (some phones call it Auto-start), so a long set-up can "
                        + "continue after the phone restarts",
                R.drawable.ic_open_in_new, dark, v -> openAutoStartSettings());
        autoStartRow.setStatus("CHECK", Ui.muted(dark));
        permissions.addView(autoStartRow, Ui.matchWrap(this, 8));
        phoneFilesRow = new Ui.Row(this, R.drawable.ic_phone, "Phone files", "Checking…",
                R.drawable.ic_open_in_new, dark, v -> {
                    if (PhoneFiles.allowed(this)) {
                        // Already on: the row says so, and the tap goes straight to the Android
                        // page where it can be turned off.
                        PhoneFiles.request(this);
                    } else {
                        dialogBuilder()
                                .setTitle("Show the phone's files inside the computer?")
                                .setMessage("Android will ask you to allow All files access for PocketLinux. "
                                        + "PocketLinux then connects six of the phone's folders — Download, "
                                        + "DCIM (photos), Documents, Pictures, Music and Movies — into the "
                                        + "computer as the Phone folder, so ChatGPT, Claude and the browser "
                                        + "can attach a file from the phone and save one to it.\n\n"
                                        + "Six, and no more. Android hands over the whole card; PocketLinux "
                                        + "connects only those, so nothing else on the phone can be reached "
                                        + "from inside the computer — not another app's data, not its private "
                                        + "storage, not a backup. What is not connected cannot be named, "
                                        + "however an app or an AI agent in there asks for it.\n\n"
                                        + "In those six, changes are real: a file deleted there is deleted on "
                                        + "the phone. To hand over just one file instead, leave this off and "
                                        + "use the desktop's Window → Add a file from the phone or a cloud "
                                        + "drive.\n\nApplies the next time the desktop starts.")
                                .setNegativeButton("Not now", null)
                                .setPositiveButton("Allow", (d, w) -> PhoneFiles.request(this))
                                .show();
                    }
                });
        permissions.addView(phoneFilesRow, Ui.matchWrap(this, 8));
        microphoneRow = new Ui.Row(this, R.drawable.ic_volume, "Microphone", "Checking…",
                R.drawable.ic_open_in_new, dark, v -> dialogBuilder()
                        .setTitle("The computer's microphone")
                        .setMessage("Turn it on from the desktop itself: Screen \u2192 Microphone. "
                                + "The phone asks you the first time, and Android offers \u201cOnly "
                                + "this time\u201d as well as \u201cWhile using the app\u201d \u2014 "
                                + "either is enough.\n\nIt is off every time the desktop starts, and "
                                + "it stops the moment you leave the desktop screen. To take the "
                                + "permission back for good, use the phone's app settings.")
                        .setNegativeButton("Close", null)
                        .setPositiveButton("Phone settings", (d, w) -> openAppInfo())
                        .show());
        permissions.addView(microphoneRow, Ui.matchWrap(this, 8));
        Ui.Row privacyRow = new Ui.Row(this, R.drawable.ic_shield, "Privacy monitor",
                PrivacyMonitor.summary(this), R.drawable.ic_chevron, dark, v -> showPrivacyMonitor());
        privacyRow.setStatus("SEE", Ui.muted(dark));
        permissions.addView(privacyRow, Ui.matchWrap(this, 8));
        // "See Last error report" was on screen with nothing behind it: the report was written
        // and never read by anything. This is the screen the app was pointing at.
        errorReportRow = new Ui.Row(this, R.drawable.ic_stop, "Last error report",
                "Checking…", R.drawable.ic_chevron, dark, v -> showErrorReport());
        permissions.addView(errorReportRow, Ui.matchWrap(this, 8));
        permissions.addView(new Ui.Row(this, R.drawable.ic_terminal, "Linux app reports",
                "Startup, sign-in handoff and exit logs for Linux apps",
                R.drawable.ic_chevron, dark, v -> showLinuxAppReports()), Ui.matchWrap(this, 8));
        permissions.addView(new Ui.Row(this, R.drawable.ic_info, "App info",
                "Android's full settings page for PocketLinux",
                R.drawable.ic_open_in_new, dark, v -> openAppInfo()), Ui.matchWrap(this, 8));

        // Storage
        LinearLayout storage = group(page, "Storage");
        linuxSize = Ui.text(this, "", 12.5f, muted);
        storage.addView(linuxSize, Ui.matchWrap(this, 0));
        TextView storageExplain = Ui.text(this,
                "PocketLinux has no size limit of its own. The computer grows into whatever this "
                + "phone has free and gives the space straight back when you remove an app, and "
                + "Android sets no quota for it — the free figure above is the whole ceiling, "
                + "shared with your photos, your Android apps and everything else on the phone.\n\n"
                + "Keep about 2 GB free. At around 500 MB Android says storage is running out and "
                + "starts clearing app caches by itself; at zero, a download or an install inside "
                + "Linux stops with \"no space left on device\" and says so — nothing you have "
                + "already saved is lost, and the desktop keeps running. Inside the computer, the "
                + "bar along the bottom shows the same free figure, and tapping it explains where "
                + "that number comes from.\n\n"
                + "What is installed is complete for running the AI desktop apps, and it is "
                + "ordinary Ubuntu underneath: from the Terminal you can apt install any ARM64 "
                + "Linux program, add a repository, or build from source. Anything you add comes "
                + "out of the free space above — there is no other limit.",
                12.5f, muted);
        storage.addView(storageExplain, Ui.matchWrap(this, 8));
        // Only when there is something to install: this row stays hidden while the computer's
        // basics match this version of the app (see measureLinuxSize).
        basicsUpdateRow = new Ui.Row(this, R.drawable.ic_download, "Update the computer's basics",
                "This version has newer desktop packages, Google Chrome and developer tools, plus "
                        + "Ubuntu's security updates. Nothing of yours changes.",
                R.drawable.ic_chevron, dark, v -> confirmBasicsUpdate());
        basicsUpdateRow.setStatus("UPDATE", Ui.WARNING);
        basicsUpdateRow.setVisibility(ContainerRuntime.basicsUpdateDue(this) ? View.VISIBLE : View.GONE);
        storage.addView(basicsUpdateRow, Ui.matchWrap(this, 10));
        removeButton = Ui.secondaryButton(this, "Delete the Linux computer and free space", dark, R.drawable.ic_delete);
        removeButton.setOnClickListener(v -> confirmRemove());
        storage.addView(removeButton, Ui.matchWrap(this, 10));

        TextView footer = Ui.text(this, "Changing a setting never deletes the Linux computer or your "
                + "files. Desktop text size applies the next time the desktop starts.", 12.5f, muted);
        footer.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        page.addView(footer, Ui.matchWrap(this, 4));
        storage.addView(new Ui.Row(this, R.drawable.ic_shield, "Terms",
                "What this app is, what it is not, and whose terms apply to what",
                R.drawable.ic_chevron, dark, v -> showTerms()), Ui.matchWrap(this, 10));
        storage.addView(new Ui.Row(this, R.drawable.ic_info, "Open-source notices",
                "The licences of everything bundled with this app, including PRoot (GPL-2.0)",
                R.drawable.ic_chevron, dark, v -> showNotices()), Ui.matchWrap(this, 10));

        TextView credits = Ui.text(this, "Runs Ubuntu 24.04 LTS on the phone's own processor. "
                + "Ubuntu is a registered trademark of Canonical Ltd and Linux is a registered "
                + "trademark of Linus Torvalds; PocketLinux is not affiliated with either. Every "
                + "other name and logo belongs to its owner.",
                11.5f, muted);
        credits.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        page.addView(credits, Ui.matchWrap(this, 10));
        page.addView(versionLine(muted), Ui.matchWrap(this, 14));
        return page;
    }

    /** One named card per group of settings. */
    private LinearLayout group(LinearLayout page, String label) {
        page.addView(Ui.groupLabel(this, label, dark));
        LinearLayout card = Ui.card(this, dark);
        page.addView(card);
        return card;
    }

    private void onAppLockToggled(boolean checked) {
        boolean current = preferences.getBoolean(ContainerRuntime.KEY_APP_LOCK, false);
        if (checked == current) return;
        if (!checked) {
            preferences.edit().putBoolean(ContainerRuntime.KEY_APP_LOCK, false).apply();
            return;
        }
        if (!AppLock.hasScreenLock(this)) {
            appLockToggle.control.setChecked(false);
            showMessage("Set a screen lock first",
                    "The app lock uses the phone's own fingerprint or PIN. Set one in Android "
                            + "settings, then turn this on.");
            return;
        }
        // Prove it works before it is on: the same prompt the lock will use from now on.
        AppLock.prompt(this, unlocked -> {
            if (unlocked) {
                preferences.edit().putBoolean(ContainerRuntime.KEY_APP_LOCK, true)
                        .putBoolean(ContainerRuntime.KEY_LOCK_NOTICE, false).apply();
                refreshLockRows();
                android.widget.Toast.makeText(this, "App lock is on: PocketLinux asks for your fingerprint "
                        + "or PIN whenever it comes to the front", android.widget.Toast.LENGTH_LONG).show();
            } else {
                appLockToggle.control.setChecked(false);
            }
        });
    }

    private void refreshLockRows() {
        if (appLockToggle == null) return;
        boolean on = preferences.getBoolean(ContainerRuntime.KEY_APP_LOCK, false);
        if (appLockToggle.control.isChecked() != on) appLockToggle.control.setChecked(on);
        boolean notice = preferences.getBoolean(ContainerRuntime.KEY_LOCK_NOTICE, false);
        lockNoticeRow.setVisibility(notice ? View.VISIBLE : View.GONE);
    }

    private void openSecuritySettings() {
        preferences.edit().putBoolean(ContainerRuntime.KEY_LOCK_NOTICE, false).apply();
        refreshLockRows();
        if (!launch(new Intent(Settings.ACTION_SECURITY_SETTINGS))) launch(new Intent(Settings.ACTION_SETTINGS));
    }

    // ---------------------------------------------------------------- questions

    /**
     * The questions anyone would ask before trusting a phone with a computer, answered in the
     * app itself, each under its question, one open at a time.
     */
    private View buildQuestionsCard(int text, int muted) {
        LinearLayout card = Ui.card(this, dark);
        card.addView(Ui.sectionTitle(this, "Privacy and your questions", R.drawable.ic_shield, dark));
        card.addView(Ui.text(this,
                "Short version: the whole Linux computer lives inside this app, on this phone, "
                        + "and belongs to you. Nothing is uploaded anywhere.", 12.5f, muted),
                Ui.matchWrap(this, 6));

        addAnswer(card, R.drawable.ic_desktop, "What exactly is this?",
                "A real Ubuntu 24.04 LTS system, running on your phone's own processor, inside this "
                        + "app.\n\n"
                        + "It is Ubuntu itself: Canonical's own ARM64 base system, with every package "
                        + "installed by apt from Ubuntu's own servers, exactly as on a PC. One part is "
                        + "not Ubuntu's — the kernel. Android is already Linux, so this system uses the "
                        + "phone's own Linux kernel, through PRoot: an ordinary program that gives "
                        + "Ubuntu its own root folder and needs no root on the phone. That makes it a "
                        + "container — not a second operating system, not an emulator, not dual "
                        + "boot, and not a cloud PC. Android keeps running the phone throughout, and "
                        + "nothing is emulated: these are ARM64 programs on an ARM64 processor.\n\n"
                        + "What is here: apt and dpkg, the desktop, Google Chrome, the developer tools "
                        + "(Python, Node.js, Git and a C/C++ compiler) and the four AI desktop apps, "
                        + "each the publisher's own official Linux build.\n\n"
                        + "What is not here: the phone's graphics chip (the processor does the "
                        + "drawing), a sound card (sound is streamed to the phone's speaker instead), "
                        + "systemd-style background services, and any power over Android itself. The "
                        + "computer lives in this app's private storage and is removed with the app.",
                true);

        addAnswer(card, R.drawable.ic_terminal, "What can I actually build on this computer?",
                "Everything below was checked against what really installs and runs on an ARM64 "
                        + "phone. Nothing is listed that only half works.\n\n"
                        + "WORKS PROPERLY\n"
                        + "• Web and back-end: Node.js, npm, Python 3, Go, Rust and PHP from "
                        + "Ubuntu, any framework on top, and a local server you open in the "
                        + "browser here\n"
                        + "• Anything an AI agent writes: Claude Code, Codex, Cursor's and "
                        + "Antigravity's agents all read, write, run and test in the Terminal, "
                        + "with git, ripgrep, SQLite and a C/C++ compiler already installed\n"
                        + "• Scripts, data work, automation, APIs, bots\n"
                        + "• Git and GitHub over SSH or HTTPS\n\n"
                        + "ANDROID APPS — yes, with one real limit\n"
                        + "Apps tab → Mobile app development installs Java 21, Gradle, adb, "
                        + "fastboot, aapt and scrcpy. Kotlin and Java compile, and Gradle is set "
                        + "up for a 4 GB phone (no daemon, 1 GB heap).\n"
                        + "You can install and TEST an app on a real phone — including this one: "
                        + "Android's Wireless debugging listens on the phone's own network, and "
                        + "this computer shares it, so 127.0.0.1 reaches the phone it is running "
                        + "on. Desktop → Tools → Phone app testing walks you through it. Another "
                        + "phone on the same Wi-Fi works the same way.\n"
                        + "The limit: Google publishes no ARM64 Linux build of aapt2, so a full "
                        + "Android Gradle build may stop there. Compiling, testing on a device, "
                        + "and everything around it works; that one tool is the gap, and it is "
                        + "Google's to close.\n\n"
                        + "iOS APPS — written and tried here, built on a Mac\n"
                        + "Xcode, the iOS Simulator and SwiftUI previews are macOS-only programs "
                        + "that need Apple's own frameworks. Nothing runs them here or on any "
                        + "phone. What does work, with no Mac at all: write the app here as React "
                        + "Native through Expo and run it on a real iPhone by scanning a code with "
                        + "Expo Go — the code is served from this computer, the app runs on the "
                        + "iPhone, and the same project runs on this Android phone at the same "
                        + "time. A real iOS build for TestFlight or the App Store is compiled by "
                        + "Expo's build service on their Macs, from this same project.\n\n"
                        + "GAMES, 3D AND DESIGN — yes, on the processor\n"
                        + "Apps tab → Design and game tools installs Blender, Godot, GIMP and "
                        + "Inkscape, all ARM64 builds from Ubuntu's own catalogue. There is no "
                        + "graphics chip in reach — no app in a container on an unrooted phone "
                        + "has one — so everything draws on the processor. That is a real limit "
                        + "and it is not the whole story: modelling, sculpting, animating, a 2D "
                        + "game, a vector drawing and a photo edit are all responsive; a lit 3D "
                        + "viewport and a full render are slow, and a render can simply be left "
                        + "to run while the phone is in a pocket. Godot exports Android builds "
                        + "from here, and you can install them on this phone to play.\n\n"
                        + "TESTING, INCLUDING FOR AN AI AGENT\n"
                        + "• Android: a real phone over adb — this one. Build, install, open and "
                        + "read the logs, all from the Terminal, so an agent can do the whole "
                        + "loop by itself.\n"
                        + "• Web and mobile web: the browser here, plus headless Chromium for "
                        + "automated tests\n"
                        + "• Everything else: the ordinary test runners — pytest, jest, go test, "
                        + "cargo test, JUnit through Gradle\n\n"
                        + "NOT POSSIBLE HERE\n"
                        + "• An Android or iOS emulator — an emulator needs hardware "
                        + "virtualisation, and Android does not give it to apps on an unrooted "
                        + "phone. This is not a missing feature that could be added: it is a "
                        + "permission the system holds back. A real phone is the test device, and "
                        + "for testing an app it is the better one anyway.\n"
                        + "• Docker and virtual machines — same reason\n"
                        + "• Hardware-accelerated 3D and fast video encoding — the graphics chip "
                        + "is out of reach, so both fall back to the processor\n\n"
                        + "HOW HEAVY CAN IT GET\n"
                        + "This phone has 4 GB and no graphics chip. One AI app plus a build is "
                        + "the ceiling. A big compile will take minutes where a laptop takes "
                        + "seconds — it finishes, it is just slower. For work bigger than that, "
                        + "the honest answer is a machine with more memory, and the AI agents "
                        + "here can drive one over SSH.", false);

        addAnswer(card, R.drawable.ic_desktop, "Why not Windows apps, or a Windows layer?",
                "Because on this hardware it cannot be done, and where it can be done it is worse "
                        + "than what is already here. PocketLinux carried a Windows compatibility "
                        + "layer for several releases; it was removed on purpose, and this is the "
                        + "whole reasoning.\n\n"
                        + "THREE WALLS, ANY ONE OF WHICH IS ENOUGH\n"
                        + "1. Real Windows needs a virtual machine, and Android's own "
                        + "virtualisation framework is documented as being for privileged and "
                        + "platform apps \u2014 an installed app cannot start one.\n"
                        + "2. A compatibility layer instead: the one project that ran Windows "
                        + "programs on ARM64 dropped its Android support.\n"
                        + "3. This container already traces every system call with ptrace. An x86 "
                        + "translator on top of that is the known-broken combination, and it "
                        + "reports exactly that when tried.\n\n"
                        + "AND EVEN WHERE IT WORKS, IT LOSES\n"
                        + "The two most important apps ship for Windows as store packages, which "
                        + "a compatibility layer installs without their package identity. Without "
                        + "it, the sign-in that comes back through a custom link may never reach "
                        + "the app, and the app's own updater stops working \u2014 so every update "
                        + "becomes a manual download, for ever. These are Chromium apps, the "
                        + "hardest kind to translate: they lose their sandbox and gain a whole "
                        + "second system's worth of processes, roughly a third more memory on a "
                        + "phone that has under four gigabytes.\n\n"
                        + "THE FEATURE PEOPLE WANT IT FOR IS THE FIRST TO BREAK\n"
                        + "The one thing the Windows builds have that the Linux builds do not is "
                        + "the apps' own Computer Use. It works by driving Windows programs with "
                        + "Windows automation \u2014 and inside a compatibility layer there are no "
                        + "Windows programs to drive. PocketLinux supplies that capability itself "
                        + "instead: appshot with the words on screen, click, type, key and scroll, "
                        + "offered to any AI agent here, natively.\n\n"
                        + "ALL FOUR APPS SHIP FOR LINUX ARM64 OFFICIALLY. That is the sentence "
                        + "that ends the argument: there is nothing to gain and a great deal to "
                        + "lose. The Linux builds are native, auto-updating, and on this processor "
                        + "better supported than the Windows ones.", false);

        addAnswer(card, R.drawable.ic_info, "Do Mac, Windows and Linux get the same features?",
                "No, and the pattern is worth knowing before you choose anything.\n\n"
                        + "\u2022 Cursor and Antigravity: THE SAME on all three. Both are built on "
                        + "VS Code, so one set of code ships everywhere at once. There is no "
                        + "\u201cMac first\u201d here at all.\n"
                        + "\u2022 ChatGPT and Claude: macOS first, Windows next, Linux last. Their "
                        + "Mac apps are written natively for macOS; the Linux ones arrived in 2026 "
                        + "and are still catching up.\n\n"
                        + "What is genuinely Mac-only, and stays that way, is always the same kind "
                        + "of thing \u2014 something that calls the operating system's own "
                        + "frameworks:\n"
                        + "\u2022 Codex Appshots (macOS only \u2014 not even on Windows)\n"
                        + "\u2022 The apps' own Computer Use \u2014 on macOS and Windows, not in "
                        + "the Linux builds yet\n"
                        + "\u2022 Claude's Dictation, and Cowork, which needs hardware "
                        + "virtualisation no phone gives an app\n"
                        + "\u2022 Xcode and the iOS Simulator \u2014 macOS only, always\n\n"
                        + "PocketLinux answers the first two itself: appshot, and click, type, key "
                        + "and scroll, given to any AI agent here over MCP, plus Super+Space. Those "
                        + "are this app's own, not the publishers' \u2014 the capability is the "
                        + "same, the feature name is not.\n\n"
                        + "Everything else is equal: Chat, Codex, Claude Code, MCP, projects, the "
                        + "in-app browser and Chrome extensions all work here.", false);

        addAnswer(card, R.drawable.ic_desktop, "Is the desktop GNOME, KDE or Cinnamon?",
                "None of them. This desktop is a small set of standard Ubuntu parts, each doing one "
                        + "job, chosen so the phone's memory goes to the apps and not to the "
                        + "desktop:\n"
                        + "• Openbox draws the windows and their title bars, and holds the menu you get "
                        + "by long-pressing the wallpaper.\n"
                        + "• tint2 is the bar along the bottom: the Apps button, the open windows, this "
                        + "phone's battery, temperature, free memory and free storage, the clock, and "
                        + "the PocketLinux mark that shows the desktop.\n"
                        + "• PCManFM paints the wallpaper and the desktop icons, and is the Files "
                        + "window.\n"
                        + "• LXTerminal is the terminal, dunst shows the messages, PulseAudio carries "
                        + "the sound to your phone's speaker, and a TigerVNC display server is the "
                        + "screen this app shows you.\n\n"
                        + "GNOME, KDE, Xfce and Cinnamon are whole desktop systems that want a gigabyte "
                        + "or more of memory and a graphics chip before an app has even opened; this "
                        + "phone has neither to spare. Everything here is ordinary Ubuntu packages from "
                        + "Ubuntu's own servers, so anything you install later behaves exactly as its "
                        + "own documentation says.", false);

        addAnswer(card, R.drawable.ic_phone, "Is it all on my phone?",
                "Yes. The Linux computer and every app on it run "
                        + "locally on this phone — no cloud, no "
                        + "server, no PocketLinux account, no tracking or analytics of any kind. "
                        + "Android's own cloud backup is switched off for this app. The internet is "
                        + "used only to download Ubuntu, the apps you choose, and whatever you "
                        + "yourself open in the browser or an AI app.", false);

        addAnswer(card, R.drawable.ic_lock, "How do I sign in to ChatGPT or Claude?",
                "Exactly as on a computer: the app opens its sign-in page in the browser inside "
                        + "the desktop, and the result comes back to the app by itself.\n\n"
                        + "ChatGPT: enter your email. If your account was made with Google, "
                        + "Google's own sign-in page appears — that is normal; finish it there.\n\n"
                        + "Claude: enter your email and Anthropic sends a code, or a sign-in link, "
                        + "to your inbox. Read the mail on your phone. A code goes straight into "
                        + "the app. A link: open it in the phone's browser, and the page it shows "
                        + "gives you a code to type into the app.\n\n"
                        + "Signing in once is enough. The app stays signed in through stops, "
                        + "restarts and updates.", false);

        addAnswer(card, R.drawable.ic_storage, "How do I attach a file from my phone to ChatGPT or Claude?",
                "Turn on Settings → Permissions → Phone files (Android calls it All files "
                        + "access), then open the desktop again. Your phone's storage is now the "
                        + "Phone folder inside the computer. In ChatGPT's attach dialog, the "
                        + "browser's upload dialog or Files, pick Phone in the left-hand list, then "
                        + "Download, DCIM for photos, or Documents. The computer's own files are "
                        + "Projects and Computer Downloads, right beside it. Saving into Phone puts "
                        + "the file on the phone. Settings → Data and files → Downloads go to also "
                        + "lets every new download ask, stay private, or go to Phone Downloads.", false);

        addAnswer(card, R.drawable.ic_lock, "Are my logins safe?",
                "When you sign in to ChatGPT or Claude inside Linux, the login is stored by that "
                        + "app inside /home/coder — which is this app's private storage on "
                        + "this phone. Android lets no other app read it, and PocketLinux itself "
                        + "never sees, stores or sends your passwords. They travel only to "
                        + "OpenAI's or Anthropic's own servers, exactly as on any computer.", false);

        addAnswer(card, R.drawable.ic_storage, "Where do my files go?",
                "Inside the computer:\n"
                        + "• Projects — /home/coder/Projects, your work.\n"
                        + "• Computer Downloads — /home/coder/Downloads, private to PocketLinux.\n"
                        + "• Phone Downloads — /home/coder/Phone/Download/PocketLinux, the phone's "
                        + "public Download folder when Phone files permission is on.\n"
                        + "• Shared — /home/coder/Shared, the way out: this one folder also appears in "
                        + "the phone's Files app under Android/data/com.pocketlinux/files/Shared. Save "
                        + "or copy a file there and the phone can open it.\n"
                        + "• Phone — the phone's own storage, once Phone files is on in Settings → "
                        + "Permissions. Saving there puts the file on the phone itself.\n\n"
                        + "Settings → Data and files → Downloads go to chooses Ask every time, "
                        + "Computer, or Phone. Changing it never moves an existing file.\n\n"
                        + "The Linux system itself lives in this app's private storage "
                        + "(/data/data/com.pocketlinux/files/ubuntu-rootfs), which no other app can open.",
                false);

        addAnswer(card, R.drawable.ic_desktop, "Is this a basic computer or a full one?",
                "Basic on purpose, and complete for the job it is built for. Out of the box it runs "
                        + "the four official AI desktop apps properly — the one thing it is meant to be "
                        + "best at — and everything they need is already installed: the Ubuntu 24.04 "
                        + "LTS desktop, Google Chrome, sound, a file manager, a terminal, on-screen "
                        + "messages, the Software catalogue, your phone's own files, a text editor, an archive tool, a picture "
                        + "viewer, a calculator, a task manager, screenshots, colour emoji and Indian "
                        + "and other scripts, the manual pages, and the developer tools an AI coding "
                        + "agent expects from its first minute — a C/C++ compiler, Python 3 with its "
                        + "headers, Node.js and npm, Git, SSH, ripgrep, SQLite. There is nothing to add "
                        + "before you start.\n\n"
                        + "It is not a cut-down system, though. It is ordinary Ubuntu, so from the "
                        + "Terminal you can take it as far as you like: sudo apt install any of the "
                        + "tens of thousands of ARM64 programs Ubuntu publishes, add a publisher's own "
                        + "repository, or build from source with the compiler that is already here. "
                        + "Chinese, Japanese and Korean text, for example, is one line: sudo apt "
                        + "install fonts-noto-cjk (about 93 MB).\n\n"
                        + "Two honest limits, so nothing surprises you. A program must have an ARM64 "
                        + "Linux build: software built only for Intel and AMD computers will not run "
                        + "here, and the installer says so rather than leaving half of it behind. And "
                        + "anything that needs a kernel of its own — Docker, virtual machines, snap — "
                        + "cannot work inside a container on a phone. Everything else is fair game.",
                false);

        addAnswer(card, R.drawable.ic_shield, "Is there virus and malware protection?",
                "Yes, and it is on by default — the same layered kind a phone uses, not a "
                        + "scanner you have to run.\n\n"
                        + "• Google Play Protect scans PocketLinux itself on your phone: when it is "
                        + "installed and again in the background, as it does with every Android app, "
                        + "sideloaded ones included. What it cannot do is look inside the Linux "
                        + "computer — Android keeps every app's private files private, and that same "
                        + "rule is what stops any other app on this phone reading yours. So the "
                        + "checking inside the computer is PocketLinux's job, and these are it.\n"
                        + "• Anything you download and install yourself goes through PocketLinux's own "
                        + "installer first: the processor it was built for, the space it needs against "
                        + "the space this phone has, the software it depends on, and a plain warning "
                        + "that a downloaded file is not signed. See “Can I install an app I "
                        + "downloaded myself?” below.\n"
                        + "• Google Chrome inside the computer runs Safe Browsing at its Enhanced "
                        + "level: dangerous sites and downloads are blocked before they open, and a "
                        + "malware or phishing warning cannot be clicked through. That check is done "
                        + "by Google's Safe Browsing service, exactly as in Chrome on your phone.\n"
                        + "• Nothing is installed from a browser or a random link: every app comes "
                        + "from its publisher's own repository and apt refuses a package whose "
                        + "signature does not match.\n"
                        + "• Ubuntu's security updates install with the computer's basics, and "
                        + "Settings → Storage offers that update when a new version brings one.\n"
                        + "• The computer is sealed in: it lives in this app's private storage, "
                        + "listens on no network port at all, and reaches the phone's screen and "
                        + "speaker through sockets inside that private storage that no other app "
                        + "can open. It cannot see your phone's files unless you turn on Phone "
                        + "files.\n\n"
                        + "A separate antivirus (ClamAV and the like) is deliberately not included: "
                        + "on a 4 GB phone its background scanning would take memory the AI apps "
                        + "need, to look for viruses that cannot run on Linux anyway.", false);

        addAnswer(card, R.drawable.ic_lock, "If something bad got in, how far could it get?",
                "Worth knowing exactly, because the honest answer is what makes the rules above "
                        + "worth following.\n\n"
                        + "Everything in the Linux computer runs as PocketLinux's own Android user, "
                        + "inside PocketLinux's private storage. Android gives every app its own user "
                        + "id and keeps them apart, so nothing in there can read another app's data, "
                        + "change the phone's system, or become root on the phone. There is no way "
                        + "out of that box, and uninstalling PocketLinux takes all of it with you.\n\n"
                        + "What a bad Linux app could reach: what is inside the computer — your files "
                        + "there and the sign-ins of the AI apps installed there, which are files in "
                        + "the same home folder — and, only while Phone files is on, six folders of "
                        + "the phone: Download, DCIM, Documents, Pictures, Music and Movies, to read "
                        + "and to change. Six, and no more. Nothing else on the phone is connected "
                        + "to the computer at all, so no app and no AI agent in there can name it, "
                        + "however it is asked. Phone files is still off until you turn it on, and "
                        + "still worth turning off when you are not using it.\n\n"
                        + "A change in those six folders is a real change: a file deleted there is "
                        + "deleted on the phone, and Android keeps no bin for it. If you only need "
                        + "to hand one file to an AI app, do not turn Phone files on at all — the "
                        + "desktop screen's Window → Add a file from the phone or a cloud drive "
                        + "opens the phone's own picker, which also lists Drive and every other "
                        + "cloud app, and copies just that file into the computer.\n\n"
                        + "What it cannot reach, at all: your other apps and their data, your "
                        + "messages, anything outside that shared storage, the camera, your location "
                        + "or your contacts. PocketLinux holds no permission for any of them, so "
                        + "nothing inside can ask for one.\n\n"
                        + "The microphone is the one exception, and it is yours to give: Screen → "
                        + "Microphone on the desktop hands it over, the phone asks you the first "
                        + "time, it is off at every start, and it stops the moment you leave the "
                        + "desktop screen. Android's own microphone dot shows the whole time.\n\n"
                        + "So the whole risk is what you install. Apps from the Apps tab come signed "
                        + "by their publishers; anything you download yourself, treat like an APK "
                        + "from a website — read what the installer says before you tap Install "
                        + "anyway, and turn Phone files off when you are not using it.", false);

        addAnswer(card, R.drawable.ic_download, "Can I install an app I downloaded myself?",
                "Yes — it works like tapping an APK from a website on Android, and PocketLinux "
                        + "adds the installer screen that a Linux desktop normally does not have.\n\n"
                        + "In the desktop: download the app's Linux build for ARM64 (a .deb file) in "
                        + "Chrome, then open it — Chrome's download bar, or the Downloads folder. The "
                        + "installer appears with the app's name, version, publisher, its size, and "
                        + "how much space this phone has free right now. The Apps menu also has "
                        + "“Install a downloaded app” if you would rather pick the file.\n\n"
                        + "Four checks run before anything is installed:\n"
                        + "• Processor — a build for Intel and AMD computers (amd64) is blocked; a "
                        + "phone needs the ARM64 build.\n"
                        + "• Space — blocked if it needs more than this phone has free, with both "
                        + "numbers shown.\n"
                        + "• What it needs — the install is tried out first, so software it is "
                        + "missing is named instead of leaving a half-installed app behind.\n"
                        + "• Where it came from — a downloaded file carries no signature of its own, "
                        + "and it says so; if the app is one of the four in the Apps tab, it points "
                        + "you at the signed copy there.\n\n"
                        + "A check you can judge for yourself ends in Install anyway, exactly as "
                        + "Android does for an app from outside the Play Store. A check that cannot "
                        + "work here — the wrong processor, no space — blocks the install and says "
                        + "why. AppImage files are refused with a reason: they need FUSE, which a "
                        + "phone container cannot provide, so look for the .deb build.\n\n"
                        + "One thing to know: an app's own size is the same on every phone, but "
                        + "whether it fits is not. Every number about your phone — free space, "
                        + "memory, temperature, what fits — is read from this phone, at that moment.",
                false);

        addAnswer(card, R.drawable.ic_shield, "What can this app touch on my phone?",
                "Its permissions are: internet, network status, notifications, running in "
                        + "the background with battery settings, and the phone's fingerprint prompt "
                        + "for the optional App lock.\n\nYour phone's storage is reachable only if "
                        + "you turn on Phone files in Settings → Permissions; off (the default) the "
                        + "computer cannot see a single file on the phone. It has NO permission for "
                        + "the camera, location, contacts, calls or messages. The microphone it can "
                        + "have, but only while you turn it on from the desktop screen, and never "
                        + "after you leave it.", false);

        addAnswer(card, R.drawable.ic_check, "Are these real Linux apps, and the best ones?",
                "Yes. Every app here is the publisher's own official Linux build — OpenAI's "
                        + "ChatGPT (with Codex), Anthropic's Claude Desktop (with Claude Code), "
                        + "Anysphere's Cursor and Google's Antigravity — the leading AI assistants "
                        + "and AI coding environments, from the companies leading AI. Install "
                        + "them from the Apps tab, not from a browser: each row fetches the publisher's "
                        + "own signed package, so what runs is exactly what they published, and a "
                        + "tap on an installed row updates it. The Linux only, on purpose card "
                        + "above has the dates and the facts.", false);

        addAnswer(card, R.drawable.ic_download, "Do I have to reinstall or update?",
                "Install once — never again.\n\nNew features arrive by themselves: "
                        + "ChatGPT and Claude Desktop load their publishers' live service, exactly as "
                        + "the phone apps do, so new models and tools appear without you doing "
                        + "anything.\n\nThe app program itself updates with one tap on its row in "
                        + "Apps. ChatGPT registers OpenAI's official update channel when it installs "
                        + "and Claude registers Anthropic's, so a tap pulls exactly what they "
                        + "published — login and settings kept. It is never forced, because a "
                        + "download of several hundred megabytes should not start on mobile data "
                        + "without you choosing it.",
                false);

        addAnswer(card, R.drawable.ic_wifi, "Does it work without internet?",
                "The Linux computer itself runs fully offline — desktop, files, browser for "
                        + "saved pages, and any app that does not need the internet. The AI apps "
                        + "need the internet to talk to ChatGPT, Claude and the others, exactly as "
                        + "on any computer. Installing and updating apps needs the internet too.", false);

        addAnswer(card, R.drawable.ic_auto_mode, "When does the computer stop or restart by itself?",
                "Only when you let it: with Smart stopping (the default) it closes after 25 "
                        + "minutes of nothing being touched, when the battery drops under 15 % off "
                        + "the charger (and it will not open below 15 % on battery either — plug in, "
                        + "or choose a fixed timer or Never stop), when the phone gets dangerously "
                        + "hot, or when today's mobile data limit is used up. Android may also end "
                        + "it when the phone runs very low on memory.\n\n"
                        + "Stopping never logs you out or loses anything: the whole computer is kept "
                        + "exactly as it was, and the next Open desktop continues from there. It "
                        + "never restarts on its own — you open it. The Home tab says when and "
                        + "why it last stopped.", false);

        addAnswer(card, R.drawable.ic_stop, "Why did an app close by itself, or the desktop go back to Home?",
                "A crash can come from the app, the desktop connection, memory pressure or an "
                        + "Android process stop. Exit 137 means SIGKILL; it does not identify who "
                        + "stopped it. On a 4 GB phone, keep one AI app open and close browser tabs "
                        + "when you have finished using them.\n\n"
                        + "PocketLinux preserves a running app during repeated taps and sign-in "
                        + "callbacks. If available memory is very low, a new heavy Linux app waits "
                        + "until you free memory and retry. The browser stays open. Settings → Linux "
                        + "app reports has startup and exit output; Home records desktop stops. "
                        + "Saved files remain, but unsaved work may need recovery.", false);

        addAnswer(card, R.drawable.ic_desktop, "Live voice, camera and screen share \u2014 what works?",
                "Voice: YES. Now that the microphone works, a live voice conversation runs in the "
                        + "browser \u2014 ChatGPT's and Claude's own voice modes on their websites "
                        + "hear you and answer through the phone's speaker. Where a desktop app has "
                        + "voice on Linux, that works too; where it does not, the browser does.\n\n"
                        + "Screen share: YES, in the browser. Chrome can share this desktop's screen "
                        + "or one of its windows into a meeting or into a website that asks for it, "
                        + "the same way as on a PC.\n\n"
                        + "Live camera: NO, and here is exactly why, so it does not sound like a "
                        + "missing feature that might arrive. A program like Chrome looks for a "
                        + "camera at /dev/video0. Creating one for it needs a kernel module, and an "
                        + "app on a phone that is not rooted cannot load a kernel module \u2014 no "
                        + "app can, on any phone. It is not a limit of this app.\n\n"
                        + "What you get instead: Screen \u2192 Take a photo into the computer hands "
                        + "you the phone's own camera app and drops the picture straight into the "
                        + "computer's Pictures folder, ready to attach. PocketLinux itself holds no "
                        + "camera permission at all \u2014 the Privacy monitor shows that.", false);

        addAnswer(card, R.drawable.ic_volume, "Can the computer hear me? (microphone)",
                "Yes. Screen → Microphone, on the desktop screen, hands the phone's microphone to "
                        + "the Linux computer as an ordinary recording device — inside it appears as "
                        + "\u201cPhone microphone\u201d, and every program finds it: a voice reply "
                        + "in an AI app, a meeting page in the browser, dictation.\n\n"
                        + "Three rules it keeps, because a microphone is the one thing you should "
                        + "never have to wonder about:\n"
                        + "• It is OFF every time the desktop starts. Nothing is remembered as "
                        + "\u201calways on\u201d.\n"
                        + "• It asks the phone's own microphone permission the first time, and you "
                        + "can take that back in the phone's app settings whenever you like.\n"
                        + "• It stops the instant you leave the desktop screen — to another app, to "
                        + "the lock screen, to Home. The screen says so when it does.\n\n"
                        + "The sound goes straight into the computer through this app's own private "
                        + "storage. It is not recorded to a file, PocketLinux sends it nowhere, and "
                        + "Android shows its own microphone dot the whole time it is on.", false);

        addAnswer(card, R.drawable.ic_desktop, "Can the AI see the screen and use the computer?",
                "Yes — PocketLinux provides that itself, because the publishers' own versions of it "
                        + "do not exist on Linux. Codex's Appshots are a macOS feature, and Claude "
                        + "Desktop's Computer Use is not in its Linux beta. Neither is coming to a "
                        + "phone, so this app builds the capability from the desktop's own parts.\n\n"
                        + "Any AI agent on this computer that speaks MCP — Claude Code and Codex "
                        + "both do, and it is switched on for them automatically — gets these:\n"
                        + "• appshot — a picture of the window in front, and the words on it\n"
                        + "• click, type, press a key, scroll — working that window\n"
                        + "• the list of open windows, and bringing one to the front\n"
                        + "• running a command in a terminal window you can watch\n\n"
                        + "Ask Claude Code to \"take an appshot and tell me what is on screen\", or "
                        + "to fix what it sees. Tools → AI computer use, inside the desktop, shows "
                        + "whether each agent is wired up.\n\n"
                        + "It only ever looks when an agent asks. Nothing is watched in the "
                        + "background, nothing is recorded, and every picture is taken and read on "
                        + "this phone — the reading is done by Tesseract, installed inside the "
                        + "computer, so the screen never goes anywhere for that.", false);

        addAnswer(card, R.drawable.ic_apps, "How many apps can I have open at once?",
                "On a 4 GB phone, start with one AI app and only the browser tabs or tools you "
                        + "need. Memory use varies by task; a fixed number of windows cannot guarantee "
                        + "that everything fits. Android may stop a process under memory pressure. "
                        + "Save your work regularly. PocketLinux checks available memory before a new "
                        + "heavy Linux launch and leaves existing apps and sign-in pages open.\n\n"
                        + "Every open window gets a button on the bar at the bottom of the desktop — "
                        + "about four of them fit across a portrait screen, more in landscape. Tap one "
                        + "to bring it forward, hold it to minimise it, and tap the clock for the full "
                        + "list, which has no limit. There is one desktop, not four: on a phone there "
                        + "is no way to reach a second one, so nothing can go missing on it.\n\n"
                        + "The bar itself can move: long-press the wallpaper and choose Move the bar to "
                        + "the top. The desktop remembers it.", false);

        addAnswer(card, R.drawable.ic_lock, "Do I need an account, password or lock?",
                "No account and no separate password — the Linux computer is yours, "
                        + "protected by the phone itself. If you want a lock, turn on App lock in "
                        + "Settings: it asks for the phone's own fingerprint or PIN right after the "
                        + "opening screen when you start PocketLinux, and again each time it comes "
                        + "back to the front, the desktop included. Inside Linux the user is ‘coder’ "
                        + "with no password, which is fine because nothing outside this app can "
                        + "reach it.", false);

        addAnswer(card, R.drawable.ic_network, "Can I limit mobile data?",
                "Yes. Settings → Mobile data limit per day. When today's use reaches the "
                        + "limit on mobile data, downloads and installs stop and the Linux computer "
                        + "will not run until Wi-Fi, midnight (when it resets, the same time most "
                        + "daily SIM allowances do) or a higher limit. Wi-Fi is never limited. The "
                        + "Home tab shows today's meter while a limit is set.", false);

        addAnswer(card, R.drawable.ic_phone, "Which phones and Android versions?",
                DeviceCheck.requirements() + " That is nearly every phone made since 2017. The "
                        + "Your phone card above says whether this one qualifies, and the app is "
                        + "built for " + DeviceCheck.releaseName(DeviceCheck.TARGET_SDK) + ".", false);

        addAnswer(card, R.drawable.ic_download, "Can I add an app while the desktop is open?",
                "Yes. Tap the row on the Apps tab; the desktop keeps running while the "
                        + "package downloads and installs, and the new app's icon appears on it "
                        + "when it is done. Only one install runs at a time.", false);

        addAnswer(card, R.drawable.ic_timer, "Why is an app slow to open, or the desktop slow?",
                "This phone runs full computer programs with a fraction of a PC's memory and "
                        + "no graphics card, so everything is drawn by the processor. The first "
                        + "open after installing is the slowest. If one fails, the reason appears "
                        + "on the desktop screen. For speed: one AI app at a time, close the "
                        + "browser when done, Desktop text size Compact, and keep the phone cool.", false);

        addAnswer(card, R.drawable.ic_delete, "What if I uninstall PocketLinux?",
                "Android deletes the whole Linux computer with the app — the system, the AI apps, "
                        + "their sign-ins and every file inside it. The Shared folder goes too: it "
                        + "belongs to this app as well, even though the phone can see it. Nothing "
                        + "is kept and nothing is copied anywhere first.\n\nSo before uninstalling, "
                        + "move anything you want to keep onto the phone itself: turn on Phone files "
                        + "in Settings → Permissions and save it into the phone's own Download or "
                        + "Documents folder, or copy it out of Android/data/com.pocketlinux/files/"
                        + "Shared with the phone's Files app first. Uninstalling one AI app "
                        + "instead — Apps tab → the installed row → Uninstall — leaves the computer "
                        + "and your files exactly as they are.", false);

        addAnswer(card, R.drawable.ic_desktop, "Can it run Windows or macOS instead?",
                "No, on any phone: Windows and macOS need a virtual machine, and Android keeps "
                        + "hardware virtualisation away from apps; emulation would be ten to fifty "
                        + "times slower than the phone, and macOS is licensed only for Apple's own "
                        + "computers. \u201cWhy Linux and not Windows or macOS\u201d on the Home "
                        + "tab, and \u201cWhy not Windows apps, or a Windows layer?\u201d above, "
                        + "have the whole answer with its evidence. "
                        + "The AI desktop apps are the same programs on all three systems, so "
                        + "nothing is missing here that a Windows edition would add. For Windows "
                        + "or macOS itself, a cloud PC used over remote desktop is the real route.", false);

        addAnswer(card, R.drawable.ic_volume, "Does sound work?",
                "Yes. Whatever the Linux computer plays — a voice reply, a video in the browser, "
                        + "a notification — comes out of the phone's speaker or headphones while "
                        + "the desktop screen is open.\n\nIt arrives as media audio, which is the "
                        + "only kind this app carries: there is no call, ring or alarm sound "
                        + "involved. The phone's volume keys set it while the desktop is open and "
                        + "show the level on screen — \u201cMedia volume · 60%\u201d — and Screen → "
                        + "Media volume does the same from the menu.\n\nInside the computer, Tools "
                        + "→ Volume and sound balances one app against another; the desktop's own "
                        + "output is set to full at every start, so your phone's volume keys stay the "
                        + "one control that matters.\n\nThe microphone works both ways now: "
                        + "Screen → Microphone hands the phone's microphone to the computer as a "
                        + "recording device called \u201cPhone microphone\u201d, so a voice reply, "
                        + "a meeting page in the browser or an AI app's dictation all find one. Off "
                        + "at every start, asks permission the first time, and stops the moment you "
                        + "leave the desktop screen.", false);

        addAnswer(card, R.drawable.ic_info, "The honest limits",
                "These are the permanent ones. This is an agentic development environment, "
                        + "not a feature-rich general desktop: what it does, it does fully; what "
                        + "it does not include is not missing by accident. A phone has no graphics "
                        + "card and a fraction of a PC's memory, so the AI desktop apps draw on the "
                        + "processor and open more slowly than on a PC, and one heavy app at a time is the "
                        + "comfortable way to work. Windows and macOS cannot run on a phone, and "
                        + "neither can anything that needs a virtual machine. Your AI accounts' "
                        + "own plans and limits still apply; PocketLinux cannot change them. "
                        + "PocketLinux is provided as is: it is a computer inside an app, not a "
                        + "backup service — copy anything precious onto the phone itself, because "
                        + "uninstalling the app deletes everything that belongs to it, the Shared "
                        + "folder included.", false);
        return card;
    }

    private void addAnswer(LinearLayout card, int iconRes, String question, String answer, boolean first) {
        final int index = answers.size();
        TextView body = Ui.text(this, answer, 12.5f, Ui.muted(dark));
        body.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 10));
        body.setTextIsSelectable(true);
        body.setVisibility(index == openAnswer ? View.VISIBLE : View.GONE);
        Ui.Row row = new Ui.Row(this, iconRes, question, null, R.drawable.ic_chevron, dark,
                v -> toggleAnswer(index));
        if (index == openAnswer) row.setExpanded(true);
        card.addView(row, Ui.matchWrap(this, first ? 12 : 8));
        card.addView(body, Ui.matchWrap(this, 0));
        answers.add(new View[]{row, body});
    }

    private void toggleAnswer(int index) {
        if (openAnswer == index) {
            answers.get(index)[1].setVisibility(View.GONE);
            ((Ui.Row) answers.get(index)[0]).setExpanded(false);
            openAnswer = -1;
            return;
        }
        if (openAnswer >= 0 && openAnswer < answers.size()) {
            answers.get(openAnswer)[1].setVisibility(View.GONE);
            ((Ui.Row) answers.get(openAnswer)[0]).setExpanded(false);
        }
        openAnswer = index;
        answers.get(index)[1].setVisibility(View.VISIBLE);
        ((Ui.Row) answers.get(index)[0]).setExpanded(true);
    }

    // --------------------------------------------------------------- data cap

    private static final String[] CAP_LABELS = {"No limit", "250 MB", "500 MB", "1 GB", "2 GB", "5 GB"};
    private static final int[] CAP_VALUES = {0, 250, 500, 1000, 2000, 5000};

    private static final String[] DOWNLOAD_LABELS = {
            "Ask every time · recommended", "Computer Downloads · private", "Phone Downloads · Android"};
    private static final String[] DOWNLOAD_VALUES = {
            ContainerRuntime.DOWNLOAD_ASK, ContainerRuntime.DOWNLOAD_COMPUTER,
            ContainerRuntime.DOWNLOAD_PHONE};
    private static final int[] DOWNLOAD_ICONS = {
            R.drawable.ic_download, R.drawable.ic_desktop, R.drawable.ic_phone};

    private String downloadTargetValue() {
        String value = ContainerRuntime.normaliseDownloadTarget(preferences.getString(
                ContainerRuntime.KEY_DOWNLOAD_TARGET, ContainerRuntime.DOWNLOAD_ASK));
        String label = labelOf(DOWNLOAD_LABELS, DOWNLOAD_VALUES, value);
        if (ContainerRuntime.DOWNLOAD_PHONE.equals(value) && !PhoneFiles.allowed(this)) {
            label += " · allow Phone files first";
        }
        if (LinuxService.isDesktopRunning()) label += " · applies next start";
        return label;
    }

    private void chooseDownloadTarget() {
        String current = ContainerRuntime.normaliseDownloadTarget(preferences.getString(
                ContainerRuntime.KEY_DOWNLOAD_TARGET, ContainerRuntime.DOWNLOAD_ASK));
        int selected = 0;
        for (int i = 0; i < DOWNLOAD_VALUES.length; i++) {
            if (DOWNLOAD_VALUES[i].equals(current)) selected = i;
        }
        showChooser("Where new downloads go", DOWNLOAD_LABELS, DOWNLOAD_ICONS, selected, index -> {
            String picked = DOWNLOAD_VALUES[index];
            preferences.edit().putString(ContainerRuntime.KEY_DOWNLOAD_TARGET, picked).apply();
            if (downloadTargetRow != null) downloadTargetRow.setValue(downloadTargetValue());
            if (ContainerRuntime.DOWNLOAD_PHONE.equals(picked) && !PhoneFiles.allowed(this)) {
                dialogBuilder()
                        .setTitle("Allow Phone Downloads?")
                        .setMessage("Android's All files access is required to save into the phone's "
                                + "public Download/PocketLinux folder. Until it is allowed, the desktop "
                                + "falls back to Ask every time and keeps the file private.")
                        .setNegativeButton("Later", null)
                        .setPositiveButton("Allow", (d, w) -> PhoneFiles.request(this))
                        .show();
            }
        });
    }

    private String dataCapLabel() {
        int cap = DataBudget.capMb(preferences);
        String label = labelOfInt(CAP_LABELS, CAP_VALUES, cap);
        long used = DataBudget.usedToday(this);
        String usedText = used < 0 ? "" : " · used today " + DeviceProbe.formatBytes(used);
        return label + usedText + " · resets at midnight";
    }

    private void chooseDataCap() {
        int current = DataBudget.capMb(preferences);
        int selected = 0;
        for (int i = 0; i < CAP_VALUES.length; i++) if (CAP_VALUES[i] == current) selected = i;
        int[] icons = new int[CAP_LABELS.length];
        for (int i = 0; i < icons.length; i++) icons[i] = R.drawable.ic_network;
        icons[0] = R.drawable.ic_power;
        showChooser("Mobile data limit per day", CAP_LABELS, icons, selected, index -> {
            preferences.edit().putInt(DataBudget.KEY_CAP_MB, CAP_VALUES[index]).apply();
            dataCapRow.setValue(dataCapLabel());
            refreshLiveTiles();
        });
    }

    /** The Home meter: hidden without a limit, red at the limit, and it says what happens next. */
    private void refreshDataCard(DeviceProbe probe) {
        if (dataCard == null) return;
        int cap = DataBudget.capMb(preferences);
        if (cap <= 0) { dataCard.setVisibility(View.GONE); return; }
        long used = Math.max(0, DataBudget.usedToday(this));
        long capBytes = cap * 1_000_000L;
        boolean exhausted = used >= capBytes;
        boolean mobile = "Mobile data".equals(probe.network);
        dataCard.setVisibility(View.VISIBLE);
        dataFigure.setText(DeviceProbe.formatBytes(used) + " of " + DeviceProbe.formatBytes(capBytes));
        dataBar.setProgress((int) Math.min(100, used * 100 / capBytes));
        dataBar.setProgressTintList(android.content.res.ColorStateList.valueOf(
                exhausted ? Ui.DANGER : Ui.accent(dark)));
        dataNote.setText(!mobile
                ? "On Wi-Fi now, so the limit is not counting. It applies on mobile data only and resets at midnight."
                : exhausted
                ? "That is all for today on mobile data. Downloads and the desktop continue after midnight, on Wi-Fi, or with a higher limit."
                : "Downloads, installs and the desktop stop at the limit on mobile data. Resets at midnight; Wi-Fi is never limited.");
    }

    // --------------------------------------------------------------- choosers

    // Android's own words for these, so a setting here reads exactly like the same setting on
    // the phone: the system theme is "System default", not a phrase invented for this app.
    private static final String[] THEME_LABELS = {"System default", "Light", "Dark"};
    private static final String[] THEME_VALUES = {"system", "light", "dark"};
    private static final int[] THEME_ICONS =
            {R.drawable.ic_auto_mode, R.drawable.ic_light_mode, R.drawable.ic_dark_mode};

    private static final String[] ROTATION_LABELS = {"Auto-rotate", "Portrait", "Landscape"};
    private static final String[] ROTATION_VALUES = {"auto", "portrait", "landscape"};
    private static final int[] ROTATION_ICONS =
            {R.drawable.ic_rotate, R.drawable.ic_phone, R.drawable.ic_desktop};

    // A clock does not know whether you are using the desktop; it only knows how long ago you
    // opened it. Smart watches the phone instead -- it lets a session you are working in run,
    // and ends one you walked away from, or one the battery can no longer carry. "Working in"
    // counts the computer's own work as well as your fingers: a build, a download or an AI
    // agent left running with the phone in a pocket is exactly when this matters, and closing
    // that for "nothing was touched" was closing it at full stretch.
    private static final String[] TIMER_LABELS = {
            "Smart · recommended", "1 hour", "2 hours", "4 hours", "6 hours", "Never stop"};
    private static final int[] TIMER_VALUES = {
            ContainerRuntime.SESSION_SMART, 60, 120, 240, 360, 0};

    // Lower dpi means more of the desktop fits; higher means type you can actually read at arm's
    // length from a phone. The default is no longer in this list at all -- it is worked out from
    // the phone's own screen (ContainerRuntime.defaultUiScale) -- so these are the deliberate
    // choices around it, and the old three values stay so a stored preference still has a label.
    private static final String[] SCALE_LABELS = {
            "Compact · PC-like", "Normal", "Large", "Larger", "Largest"};
    private static final int[] SCALE_VALUES = {96, 120, 144, 168, 192};

    private String labelOf(String[] labels, String[] values, String current) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) return labels[i];
        return labels[0];
    }

    private String labelOfInt(String[] labels, int[] values, int current) {
        for (int i = 0; i < values.length; i++) if (values[i] == current) return labels[i];
        return labels[0];
    }

    private void chooseText(String title, String[] labels, int[] icons, String[] values,
                            String key, String fallback, Ui.Row row, boolean restart) {
        String current = preferences.getString(key, fallback);
        int selected = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) selected = i;
        showChooser(title, labels, icons, selected, index -> {
            preferences.edit().putString(key, values[index]).apply();
            row.setValue(labels[index]);
            if (ContainerRuntime.KEY_ORIENTATION.equals(key)) applyOrientation();
            if (restart) recreate();
        });
    }

    private void chooseTimer() {
        int current = preferences.getInt(ContainerRuntime.KEY_SESSION_MINUTES,
                ContainerRuntime.SESSION_SMART);
        int selected = 0;
        for (int i = 0; i < TIMER_VALUES.length; i++) if (TIMER_VALUES[i] == current) selected = i;
        int[] icons = new int[TIMER_LABELS.length];
        for (int i = 0; i < icons.length; i++) icons[i] = R.drawable.ic_timer;
        icons[0] = R.drawable.ic_auto_mode;
        icons[icons.length - 1] = R.drawable.ic_power;
        showChooser("When to stop by itself", TIMER_LABELS, icons, selected, index -> {
            preferences.edit().putInt(ContainerRuntime.KEY_SESSION_MINUTES, TIMER_VALUES[index]).apply();
            autoStopRow.setValue(TIMER_LABELS[index]);
        });
    }




    /** Bigger type on the Linux desktop, without shrinking the picture. */
    private void chooseScale() {
        int current = preferences.getInt(ContainerRuntime.KEY_UI_SCALE,
                ContainerRuntime.defaultUiScale(this));
        int selected = 0;
        for (int i = 0; i < SCALE_VALUES.length; i++) if (SCALE_VALUES[i] == current) selected = i;
        int[] icons = new int[SCALE_VALUES.length];
        java.util.Arrays.fill(icons, R.drawable.ic_desktop);
        showChooser("Desktop text size", SCALE_LABELS, icons, selected, index -> {
            preferences.edit().putInt(ContainerRuntime.KEY_UI_SCALE, SCALE_VALUES[index]).apply();
            desktopScaleRow.setValue(LinuxService.isDesktopRunning()
                    ? SCALE_LABELS[index] + " · applies next time the desktop starts"
                    : SCALE_LABELS[index]);
        });
    }

    private interface OnChoice { void picked(int index); }

    /** Every option is shown with its own icon, not plain text in a dropdown. */
    private void showChooser(String title, String[] labels, int[] icons, int selected, OnChoice choice) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 12);
        list.setPadding(pad, Ui.dp(this, 6), pad, Ui.dp(this, 6));
        list.setBackgroundColor(Ui.surface(dark));

        final AlertDialog dialog = dialogBuilder().setTitle(title).setView(list)
                .setNegativeButton("Cancel", null).create();

        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout option = new LinearLayout(this);
            option.setOrientation(LinearLayout.HORIZONTAL);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setMinimumHeight(Ui.dp(this, 54));
            option.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            boolean active = index == selected;
            option.setBackground(Ui.tappable(this, active
                    ? Ui.outlined(Ui.field(dark), Ui.accent(dark), 14, this)
                    : Ui.background(Ui.field(dark), 14, this), dark));
            option.addView(Ui.icon(this, icons[index], Ui.accent(dark), 22));
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelLp.setMarginStart(Ui.dp(this, 12));
            option.addView(Ui.title(this, labels[index], 15, Ui.text(dark)), labelLp);
            if (active) option.addView(Ui.icon(this, R.drawable.ic_check, Ui.accent(dark), 20));
            option.setOnClickListener(v -> {
                dialog.dismiss();
                choice.picked(index);
            });
            LinearLayout.LayoutParams optionLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            optionLp.topMargin = Ui.dp(this, index == 0 ? 0 : 8);
            list.addView(option, optionLp);
        }
        dialog.show();
    }

    // --------------------------------------------------------------- state

    private void refreshLiveTiles() {
        if (networkTile == null) return;
        try {
            DeviceProbe probe = DeviceProbe.read(this);
            lastProbe = probe;
            networkTile.set(probe.network);
            batteryTile.set(probe.batteryPercent < 0 ? "—" : probe.batteryPercent + "%",
                    probe.batteryPercent >= 0 && probe.batteryPercent <= 15 ? Ui.WARNING : Ui.text(dark));
            // An installed, healthy computer with 5 GB free is not a warning: the 6 GB line is
            // what set-up needs, not what running needs.
            long lowMark = ContainerRuntime.isInstalled(this)
                    ? DeviceCheck.LOW_FREE_BYTES : DeviceCheck.MIN_FREE_BYTES;
            storageTile.set(DeviceProbe.formatBytes(probe.freeStorage),
                    probe.freeStorage < lowMark ? Ui.WARNING : Ui.text(dark));
            if (probe.batteryTempC > 0) {
                heatTile.set(String.format(Locale.ROOT, "%.0f°C", probe.batteryTempC),
                        probe.batteryTempC >= 44f ? Ui.WARNING : Ui.text(dark));
            } else {
                heatTile.set(DeviceProbe.thermalName(probe.thermalStatus));
            }
            refreshDataCard(probe);
            refreshHealth(probe);
        } catch (Throwable ignored) {
            networkTile.set("—");
        }
    }

    /** The attention rows and the Settings dot, from one reading. */
    private void refreshHealth(DeviceProbe probe) {
        if (attentionCard == null) return;
        DeviceCheck.Result check = DeviceCheck.run(this);
        Health health = Health.read(this, probe, check.compatible);
        attentionNotifications.setVisibility(health.notificationsOff ? View.VISIBLE : View.GONE);
        attentionBattery.setVisibility(health.batteryRestricted ? View.VISIBLE : View.GONE);
        attentionSpace.setVisibility(health.spaceLow ? View.VISIBLE : View.GONE);
        attentionSpace.setValue(DeviceProbe.formatBytes(probe.freeStorage) + " free. Tap for what is needed.");
        if (!health.spaceLow && spaceDetail != null) spaceDetail.setVisibility(View.GONE);
        attentionHeat.setVisibility(health.hot ? View.VISIBLE : View.GONE);
        if (!health.hot && heatDetail != null) heatDetail.setVisibility(View.GONE);
        attentionData.setVisibility(health.dataCapReached ? View.VISIBLE : View.GONE);
        attentionLock.setVisibility(health.lockDisabledItself ? View.VISIBLE : View.GONE);
        attentionCompatible.setVisibility(health.notCompatible ? View.VISIBLE : View.GONE);
        // Low free space before setup already makes the phone "not compatible": one row, not two.
        if (health.spaceLow && health.notCompatible && check.onlySpace) attentionCompatible.setVisibility(View.GONE);
        if (attentionCompatible.getVisibility() != View.VISIBLE && compatibleDetail != null) {
            compatibleDetail.setVisibility(View.GONE);
        }
        boolean any = health.notificationsOff || health.batteryRestricted || health.spaceLow
                || health.hot || health.dataCapReached || health.lockDisabledItself || health.notCompatible;
        attentionCard.setVisibility(any ? View.VISIBLE : View.GONE);
        if (navItems[TAB_SETTINGS] != null) navItems[TAB_SETTINGS].setDot(health.settingsDot());
        if (compatibleRow != null) {
            compatibleRow.setStatus(check.compatible ? "COMPATIBLE" : "NOT COMPATIBLE",
                    check.compatible ? Ui.SUCCESS : Ui.DANGER);
        }
        refreshLockRows();
    }

    private void refreshDeviceCard() {
        if (deviceDetails == null) return;
        try {
            DeviceProbe probe = DeviceProbe.read(this);
            deviceDetails.setText(probe.model
                    + "\n" + probe.androidVersion + " · " + probe.abi
                    + "\n" + DeviceProbe.formatBytes(probe.totalRam) + " RAM · "
                    + DeviceProbe.formatBytes(probe.freeStorage) + " free");
        } catch (Throwable error) {
            deviceDetails.setText("Android phone detected\nSome details are unavailable.");
        }
    }

    private void measureLinuxSize() {
        if (linuxSize == null) return;
        final boolean installed = ContainerRuntime.isInstalled(this);
        if (removeButton != null) {
            // Also when Ubuntu is on the phone but not usable: that is exactly when the owner
            // needs a way out, and set-up no longer deletes anything by itself.
            boolean present = installed
                    || new java.io.File(ContainerRuntime.rootfs(this), "etc/os-release").isFile();
            removeButton.setVisibility(present ? View.VISIBLE : View.GONE);
        }
        final boolean updateDue = ContainerRuntime.basicsUpdateDue(this);
        if (basicsUpdateRow != null) {
            basicsUpdateRow.setVisibility(installed && updateDue ? View.VISIBLE : View.GONE);
        }
        if (!installed) {
            linuxSize.setText("The Linux computer is not set up yet. Set-up downloads about 30 MB, "
                    + "then about 550 MB of packages; the finished computer uses 2–3 GB. "
                    + "This phone has " + DeviceProbe.formatBytes(freeSpaceNow()) + " free.");
            return;
        }
        if (!ContainerRuntime.hasFreshSize()) {
            linuxSize.setText("Measuring the Linux computer's size…");
        }
        // Nothing here holds this screen: the walk is cached, single-flight, and drops its
        // result if the view it was for is gone. Three concurrent walks over the whole system used to
        // start every time the owner came back from the desktop.
        final TextView target = linuxSize;
        final boolean due = updateDue;
        ContainerRuntime.measureSize(this, ContainerRuntime.rootfs(this), handler, null, bytes -> {
            if (!target.isAttachedToWindow()) return;
            target.setText("The Linux computer is using " + DeviceProbe.formatBytes(bytes)
                    + ". This phone has " + DeviceProbe.formatBytes(freeSpaceNow()) + " free."
                    + (due ? "" : " Its basics are up to date."));
        });
    }

    /** The phone's free space, right now, in one syscall -- the same figure the home tile shows. */
    private long freeSpaceNow() {
        try {
            return new android.os.StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes();
        } catch (RuntimeException error) {
            return lastProbe == null ? -1L : lastProbe.freeStorage;
        }
    }

    private boolean notificationsAllowed() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean batteryUnrestricted() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
    }

    /**
     * Every permission this app can hold, read off the phone rather than off a promise.
     *
     * Built from the manifest that actually shipped, so a permission added in a later version
     * appears here by itself, and the ones this app never asks for are listed too -- an absence
     * the owner can check is worth more than a sentence saying it is absent.
     */
    private void showPrivacyMonitor() {
        StringBuilder held = new StringBuilder();
        StringBuilder never = new StringBuilder();
        for (PrivacyMonitor.Entry entry : PrivacyMonitor.read(this)) {
            StringBuilder target = entry.neverAsked ? never : held;
            target.append(entry.neverAsked ? "\u2715  " : (entry.held ? "\u25cf  " : "\u25cb  "))
                    .append(entry.name);
            if (!entry.neverAsked) target.append("  \u2014  ").append(entry.state());
            target.append('\n').append("     ").append(entry.purpose).append("\n\n");
        }
        String text = "What PocketLinux holds right now\n\n" + held
                + "What it never asks for\n\n" + never
                + "A filled circle is on, an empty one is off, a cross means the app cannot ask "
                + "at all \u2014 the permission is not in the app, so no dialog for it exists.\n\n"
                + "The Linux computer has no permissions of its own. It reaches only what this app "
                + "reaches, which is why this list is the whole answer.";
        dialogBuilder()
                .setTitle("Privacy monitor")
                .setMessage(text)
                .setNegativeButton("Close", null)
                .setPositiveButton("Phone settings", (d, w) -> openAppInfo())
                .show();
    }

    /**
     * The last thing that went wrong, in full, with a way to hand it on.
     *
     * A message that says "see the report" and then has no report is worse than no message: the
     * owner is told there is an answer and given no way to it. The Copy button is the point --
     * an owner with no PC cannot read a log file, but they can paste one.
     */
    private void showErrorReport() {
        String report = Crash.read(this);
        if (report.isEmpty()) {
            showMessage("No error report", "Nothing has gone wrong since this was last cleared.");
            return;
        }
        dialogBuilder()
                .setTitle("Last error report")
                .setMessage(report)
                .setNeutralButton("Clear", (d, w) -> {
                    Crash.clear(this);
                    refreshPermissionRows();
                })
                .setNegativeButton("Close", null)
                .setPositiveButton("Copy", (d, w) -> {
                    android.content.ClipboardManager board =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (board != null) {
                        board.setPrimaryClip(android.content.ClipData.newPlainText(
                                "PocketLinux error report", report));
                        android.widget.Toast.makeText(this, "Copied. Paste it wherever you are "
                                + "asking for help.", android.widget.Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }



    private void showLinuxAppReports() {
        final String[] names = {"ChatGPT", "Chrome", "Browser sign-in handoff", "Claude", "Cursor",
                "Antigravity", "Desktop session", "Previous desktop session", "Runtime and viewer"};
        final String[] files = {"chatgpt.log", "google-chrome.log", "browser-handoff.log", "claude-desktop.log",
                "cursor.log", "antigravity.log", "desktop-session.log", "desktop-session.previous.log", "runtime-events.log"};
        dialogBuilder().setTitle("Linux app reports").setItems(names, (dialog, index) -> {
            java.io.File folder = new java.io.File(ContainerRuntime.rootfs(this),
                    "home/coder/.pocketdesk/logs");
            java.io.File reportFile = index == files.length - 1 ? RuntimeDiagnostics.file(this)
                    : new java.io.File(folder, files[index]);
            String output = readReportTail(reportFile);
            if (output.isEmpty()) output = "No startup report yet. Open this Linux app once, then check here.";
            if (index < 6) {
                long desktopOpenedAt = preferences.getLong(ContainerRuntime.KEY_LAST_OPENED_AT, 0L);
                output = DiagnosticReport.ageNotice(reportFile.lastModified(), desktopOpenedAt) + output;
                java.io.File failureFile = new java.io.File(folder, files[index] + ".failure");
                String failure = readReportTail(failureFile);
                if (!failure.isEmpty()) {
                    output += "\n\n=== " + names[index] + " · retained failure ===\n"
                            + DiagnosticReport.failureNotice(failureFile.lastModified()) + failure;
                }
            }
            // Old launcher versions could echo OAuth URLs. Redact their queries when displaying
            // and copying too, so existing reports do not expose sign-in credentials.
            output = DiagnosticReport.redact(output);
            final String report = "PocketLinux " + VERSION + " · " + names[index] + " (Linux)\n\n" + output;
            dialogBuilder().setTitle(names[index] + " · Linux report").setMessage(report)
                    .setNegativeButton("Close", null)
                    .setPositiveButton("Copy", (entry, which) -> {
                        android.content.ClipboardManager board =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (board != null) {
                            board.setPrimaryClip(android.content.ClipData.newPlainText(
                                    "PocketLinux Linux app report", report));
                            android.widget.Toast.makeText(this, "Linux app report copied.", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }).show();
        }).setNegativeButton("Close", null).setPositiveButton("Copy all", (dialog, which) -> {
            StringBuilder combined = new StringBuilder("PocketLinux " + VERSION + " | Android "
                    + android.os.Build.VERSION.RELEASE + " | " + android.os.Build.MODEL + "\n");
            String reason = preferences.getString(ContainerRuntime.KEY_LAST_STOP_REASON, "");
            if (reason != null && !reason.isEmpty()) combined.append("Last stop: ").append(reason).append('\n');
            java.io.File folder = new java.io.File(ContainerRuntime.rootfs(this), "home/coder/.pocketdesk/logs");
            String[] reports = new String[files.length];
            String[] failures = new String[6];
            long[] modifiedAt = new long[6];
            long[] failureModifiedAt = new long[6];
            for (int i = 0; i < files.length; i++) {
                java.io.File reportFile = i == files.length - 1 ? RuntimeDiagnostics.file(this)
                        : new java.io.File(folder, files[i]);
                reports[i] = readReportTail(reportFile);
                if (i < 6) {
                    modifiedAt[i] = reportFile.lastModified();
                    java.io.File failureFile = new java.io.File(folder, files[i] + ".failure");
                    failures[i] = readReportTail(failureFile);
                    failureModifiedAt[i] = failureFile.lastModified();
                }
            }
            String report = DiagnosticReport.combine(combined.toString(), names, reports, failures,
                    modifiedAt, failureModifiedAt, preferences.getLong(ContainerRuntime.KEY_LAST_OPENED_AT, 0L));
            android.content.ClipboardManager board = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (board != null) {
                board.setPrimaryClip(android.content.ClipData.newPlainText("PocketLinux Linux reports", report));
                android.widget.Toast.makeText(this, "Linux reports copied.", android.widget.Toast.LENGTH_SHORT).show();
            }
        }).show();
    }


    private String readReportTail(java.io.File file) {
        if (!file.isFile() || file.length() <= 0L) return "";
        final int limit = 160 * 1024;
        try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(file, "r")) {
            long length = input.length();
            long start = Math.max(0L, length - limit);
            input.seek(start);
            byte[] bytes = new byte[(int) (length - start)];
            input.readFully(bytes);
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (start > 0L) {
                int line = text.indexOf('\n');
                if (line >= 0) text = text.substring(line + 1);
                text = "…showing the latest part of the report…\n\n" + text;
            }
            return text.trim();
        } catch (Exception error) {
            return "The report exists, but Android could not read it: " + error.getMessage();
        }
    }

    private void refreshPermissionRows() {
        if (errorReportRow != null) {
            long at = Crash.recordedAt(this);
            boolean any = at > 0;
            errorReportRow.setStatus(any ? "SEE" : "NONE", any ? Ui.WARNING : Ui.muted(dark));
            errorReportRow.setValue(any
                    ? "Something went wrong " + clock(at) + ". Tap to read it, and to copy it."
                    : "Nothing has gone wrong. Anything that does is kept here.");
        }
        if (microphoneRow != null) {
            boolean on = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            microphoneRow.setStatus(on ? "ALLOWED" : "OFF", on ? Ui.SUCCESS : Ui.muted(dark));
            microphoneRow.setValue(on
                    ? "Allowed · the desktop can use it when you turn it on there; never after you leave"
                    : "Off · the computer has no microphone until you turn it on from the desktop");
        }
        if (notificationRow != null) {
            boolean on = notificationsAllowed();
            notificationRow.setStatus(on ? "ON" : "OFF", on ? Ui.SUCCESS : Ui.WARNING);
            notificationRow.setValue(on
                    ? "On · you can see setup progress and a Stop button"
                    : "Off · turn ON to see setup progress and a Stop button");
        }
        if (phoneFilesRow != null) {
            boolean on = PhoneFiles.allowed(this);
            phoneFilesRow.setStatus(on ? "ON" : "OFF", on ? Ui.SUCCESS : Ui.muted(dark));
            phoneFilesRow.setValue(on
                    ? "On · six of the phone's folders are in the computer: Download, DCIM, "
                    + "Documents, Pictures, Music, Movies"
                    + (LinuxService.isDesktopRunning() ? " (from the next desktop start)" : "")
                    + " · tap to change in Android settings"
                    : "Off · for one file at a time use the desktop's Window → Add a file from the "
                    + "phone or a cloud drive; turn this on for the whole folders");
        }
        if (downloadTargetRow != null) downloadTargetRow.setValue(downloadTargetValue());
        if (batteryOptimisationRow != null) {
            boolean on = batteryUnrestricted();
            batteryOptimisationRow.setStatus(on ? "ON" : "OFF", on ? Ui.SUCCESS : Ui.WARNING);
            batteryOptimisationRow.setValue(on
                    ? "Unrestricted · a 30-minute setup keeps running"
                    : "Restricted · set to Unrestricted, or Android stops setup in the background");
        }
    }

    /** Asks for what the app needs on first launch, before the user starts a long install. */
    private void maybeShowPermissionIntro() {
        if (preferences.getBoolean(ContainerRuntime.KEY_PERMISSION_INTRO, false)) return;
        // Never over the opening screen or the lock: it was being raised behind both, dismissed
        // by the next tap, and never shown again -- because the flag was written before the
        // dialog appeared. It is written when the owner answers it, or when there is nothing
        // left to ask for.
        if (introShowing || AppLock.showing(shell) || AppLock.isLocked(this)) return;
        if (permissionIntro != null && permissionIntro.isShowing()) return;
        if (notificationsAllowed() && batteryUnrestricted()) {
            preferences.edit().putBoolean(ContainerRuntime.KEY_PERMISSION_INTRO, true).apply();
            return;
        }
        permissionIntro = dialogBuilder()
                .setTitle("Allow three things first")
                .setMessage("Setting up the Linux computer downloads for 10–30 minutes in the background. "
                        + "Without these, the phone stops it half way.\n\n"
                        + "1. Notifications — ON, so you can watch progress and stop it any time.\n\n"
                        + "2. Battery usage — Unrestricted, so the download is not killed when the "
                        + "screen turns off.\n\n"
                        + "3. Background activity and Auto-launch — ON, on the phone's battery page for "
                        + "PocketLinux (Settings → Permissions opens it).\n\n"
                        + "Nothing else is requested. All three can be changed later under Settings → Permissions.")
                .setNegativeButton("Later", (dialog, which) -> preferences.edit()
                        .putBoolean(ContainerRuntime.KEY_PERMISSION_INTRO, true).apply())
                .setPositiveButton("Allow", (dialog, which) -> {
                    preferences.edit().putBoolean(ContainerRuntime.KEY_PERMISSION_INTRO, true).apply();
                    startPermissionFlow();
                })
                .show();
    }

    private void startPermissionFlow() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed()) {
            askBatteryAfterNotifications = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
            return;
        }
        askBatteryPermission();
    }

    private void askBatteryPermission() {
        if (batteryUnrestricted()) return;
        dialogBuilder()
                .setTitle("Allow background battery use")
                .setMessage("On the next screen choose Unrestricted (some phones call it "
                        + "\"Don't optimise\" or \"Allow background activity\").\n\n"
                        + "This does not drain the battery on its own — it only stops Android from "
                        + "killing a setup that is already running.")
                .setNegativeButton("Later", null)
                .setPositiveButton("Open settings", (dialog, which) -> openBatterySettings())
                .show();
    }

    /** When and why the Linux computer last stopped by itself, if that is the latest event. */
    private String stopStory() {
        long stoppedAt = preferences.getLong(ContainerRuntime.KEY_LAST_STOP_AT, 0L);
        long openedAt = preferences.getLong(ContainerRuntime.KEY_LAST_OPENED_AT, 0L);
        String reason = preferences.getString(ContainerRuntime.KEY_LAST_STOP_REASON, null);
        if (stoppedAt == 0 || reason == null || stoppedAt < openedAt) return null;
        return "Stopped by itself at " + clock(stoppedAt) + ": " + reason;
    }

    private String clock(long at) {
        Date date = new Date(at);
        String time = android.text.format.DateFormat.getTimeFormat(this).format(date);
        boolean today = android.text.format.DateUtils.isToday(at);
        return today ? time : android.text.format.DateFormat.getMediumDateFormat(this).format(date) + ", " + time;
    }

    private void refreshState() {
        if (statusBadge == null) return;
        boolean installed = ContainerRuntime.isInstalled(this);
        boolean running = LinuxService.isDesktopRunning();
        boolean busy = LinuxService.isBusy();

        if (running) {
            statusBadge.setText("Running");
            statusHeadline.setText("The Linux computer is running");
            statusNote.setText(LinuxService.isInstalling()
                    ? "An app is installing beside it; it appears on the desktop when done."
                    : "Back to desktop to continue. Apps can be added while it runs; stopping keeps everything as it is.");
        } else if (busy) {
            statusBadge.setText("Working");
            String task = LinuxService.lastMessage();
            String detail = LinuxService.lastDetail();
            statusHeadline.setText(task == null ? "Please wait" : task);
            statusNote.setText((detail == null || detail.trim().isEmpty()
                    ? "The background task is starting."
                    : detail) + "\n\nYou may use another app or turn the screen off; keep internet "
                    + "on and leave PocketLinux's battery usage Unrestricted.");
        } else if (installed) {
            statusBadge.setText("Ready");
            statusHeadline.setText("The Linux computer is set up");
            long openedAt = preferences.getLong(ContainerRuntime.KEY_LAST_OPENED_AT, 0L);
            String note = "Ubuntu 24.04 LTS is set up on this phone: a basic computer on purpose, "
                    + "and complete for the one job it is built for — running the official AI "
                    + "desktop apps. Open the desktop and tap an app.";
            note += "\n\nTools → Software installs any native ARM64 Ubuntu package, and a .deb "
                    + "you downloaded yourself opens in PocketLinux's own installer.";
            if (openedAt > 0) note += " Last opened " + clock(openedAt) + ".";
            String story = stopStory();
            if (story != null) note += "\n\n" + story;
            DeviceProbe probe = lastProbe;
            boolean smart = preferences.getInt(ContainerRuntime.KEY_SESSION_MINUTES,
                    ContainerRuntime.SESSION_SMART) == ContainerRuntime.SESSION_SMART;
            if (smart && probe != null && probe.batteryPercent >= 0
                    && probe.batteryPercent < ContainerRuntime.SMART_BATTERY_FLOOR
                    && !DeviceProbe.isCharging(this)) {
                note += "\n\nBattery " + probe.batteryPercent + " %: plug in to open. Smart stopping keeps "
                        + "the computer off below " + ContainerRuntime.SMART_BATTERY_FLOOR
                        + " % on battery; a fixed timer or Never stop in Settings lifts that.";
            }
            statusNote.setText(note);
        } else {
            boolean started = preferences.contains(ContainerRuntime.KEY_SETUP_STAGE);
            statusBadge.setText(started ? "Part way" : "Not set up");
            statusHeadline.setText(started ? "Continue setting up the computer"
                    : "Set up the Linux computer once");
            statusNote.setText("One set-up does it all: Ubuntu 24.04 LTS, the desktop, sound, Google "
                    + "Chrome and the developer tools (Python, Node.js, Git and a C/C++ compiler). "
                    + "About 30 MB now, then about 550 MB of packages; 2–3 GB when finished. "
                    + "Then add the supported Linux ARM64 AI desktop apps from the Apps tab."
                    + (started ? "\n\nA set-up was started and did not finish. Nothing is lost and "
                            + "nothing is downloaded twice: this carries on from the step it reached."
                            : ""));
            setupButton.setText(started ? "Continue set-up" : "Set up Linux");
        }

        setupButton.setVisibility(installed || busy ? View.GONE : View.VISIBLE);
        setupButton.setEnabled(!busy);
        startButton.setVisibility(installed ? View.VISIBLE : View.GONE);
        startButton.setEnabled(installed && !busy);
        startButton.setText(running ? "Back to desktop" : "Open desktop");
        stopButton.setEnabled(running || busy);
        for (Button button : new Button[]{setupButton, startButton, stopButton}) {
            button.setAlpha(button.isEnabled() ? 1f : 0.45f);
        }
        refreshAppRows(installed, busy, running);
        refreshPermissionRows();
    }

    private void renderProgress(String message, String detail, int progress, boolean busy, boolean error) {
        if (message == null || progressCard == null) return;
        progressCard.setVisibility(View.VISIBLE);
        progressTitle.setText(message);
        progressDetail.setText(detail == null ? "" : detail);
        progressDetail.setVisibility(detail == null || detail.isEmpty() ? View.GONE : View.VISIBLE);
        if (progress < 0) {
            progressBar.setIndeterminate(busy);
            progressPercent.setText("");
        } else {
            progressBar.setIndeterminate(false);
            progressBar.setProgress(progress);
            progressPercent.setText(progress + "%");
        }
        if (error) {
            progressTitle.setText(message);
            progressPercent.setText("");
            progressBar.setIndeterminate(false);
            progressBar.setProgress(0);
        }
    }

    // -------------------------------------------------------------- actions

    private void confirmSetup() {
        DeviceCheck.Result check = DeviceCheck.run(this);
        if (!check.compatible) {
            if (phoneDetail != null) {
                phoneDetail.setText(check.detail);
                phoneDetail.setVisibility(View.VISIBLE);
            }
            android.widget.Toast.makeText(this, check.headline + " — see Your phone below",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String warning = batteryUnrestricted() ? ""
                : "\n\nBattery usage is still Restricted. Android may stop the setup when the screen "
                + "turns off — set it to Unrestricted under Settings → Permissions first.";
        dialogBuilder()
                .setTitle("Set up the Linux computer?")
                .setMessage("Ubuntu 24.04 LTS will be downloaded and set up inside this app, with "
                        + "the desktop, Google Chrome and the developer tools.\n\n"
                        + "• Download: about 30 MB, then about 550 MB of packages\n"
                        + "• Final size: 2–3 GB, and 6 GB free to start\n"
                        + "• After that it grows into whatever the phone has free — no fixed limit, "
                        + "and every AI app you add takes 2–4 GB more\n"
                        + "• Wi-Fi or mobile data both work\n"
                        + "• Takes 15–45 minutes depending on your connection\n"
                        + "• Safe to stop: tapping Continue set-up later carries on from the step "
                        + "it reached, without downloading anything twice"
                        + warning)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Set up", (d, which) -> sendServiceAction(LinuxService.ACTION_SETUP))
                .show();
    }

    /**
     * The terms, in the fewest words that are still true.
     *
     * There are three different sets of them and confusing the three is how people end up
     * surprised: PocketLinux's own, Ubuntu's, and the AI companies'. Each paragraph below says
     * whose is whose, and nothing is padded out with the sort of sentence nobody reads.
     */
    private void showTerms() {
        dialogBuilder()
                .setTitle("Terms")
                .setMessage("PocketLinux is provided as it is, with no warranty of any kind. It is a "
                        + "way to run Ubuntu on your own phone; what you do in it is yours, and so "
                        + "is the responsibility for it.\n\n"
                        + "It is not affiliated with, endorsed by or sponsored by Canonical, "
                        + "OpenAI, Anthropic, Anysphere, Google or the Linux Foundation. Their "
                        + "names and logos appear here only to identify their software.\n\n"
                        + "The system is Ubuntu 24.04 LTS, downloaded at set-up from Canonical's "
                        + "own ARM64 base image and updated from Ubuntu's own servers. Every "
                        + "package in it stays under its own licence, and PocketLinux changes "
                        + "none of them.\n\n"
                        + "The AI apps are not PocketLinux's. Installing one downloads it from its "
                        + "publisher; using it needs your own account with them; and their terms, "
                        + "their prices and their usage limits are the ones that apply. What you "
                        + "type into ChatGPT, Claude, Cursor or Antigravity goes to that company, "
                        + "exactly as it would on a laptop.\n\n"
                        + "PocketLinux itself sends nothing anywhere. It has no account, no server "
                        + "and no analytics; the only things it downloads are Ubuntu, the packages "
                        + "you ask for, and the apps you choose.\n\n"
                        + "Parts of this app are other people's free software, PRoot (GPL-2.0-or-"
                        + "later) among them, and the notices row lists every one with its licence "
                        + "and where its source is. Removing PocketLinux removes the Linux "
                        + "computer and everything in it.")
                .setPositiveButton("Close", null)
                .setNeutralButton("Open-source notices", (dialog, which) -> showNotices())
                .show();
    }

    /** The bundled licence notices, read from the APK's own assets. */
    private void showNotices() {
        String text;
        try (java.io.InputStream input = getAssets().open("open-source-notices.md");
             java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) != -1) buffer.write(chunk, 0, read);
            text = new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable error) {
            text = "The notices could not be read from this build. They are also in the project's "
                    + "OPEN_SOURCE_NOTICES.md file.";
        }
        TextView body = Ui.text(this, text, 11.5f, Ui.text(dark));
        body.setTextIsSelectable(true);
        int pad = Ui.dp(this, 18);
        body.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.addView(body);
        dialogBuilder()
                .setTitle("Open-source notices")
                .setView(scroller)
                .setPositiveButton("Close", null)
                .show();
    }

    private void confirmBasicsUpdate() {
        if (!ContainerRuntime.isInstalled(this)) {
            showMessage("Set up Linux first", "Set-up installs the desktop, Google Chrome and the developer "
                    + "tools. This row only brings an already set-up computer up to date.");
            return;
        }
        if (!ContainerRuntime.basicsUpdateDue(this)) {
            showMessage("Already up to date", "The computer's basics were installed by this version "
                    + "of PocketLinux. This row comes back when a newer version has something to add.");
            return;
        }
        LinuxApps.App basics = LinuxApps.byId("basics");
        if (basics == null) return;
        dialogBuilder()
                .setTitle("Update the computer's basics?")
                .setMessage("Fetches the newest desktop packages, Google Chrome and developer tools "
                        + "from their own repositories, and installs Ubuntu's security updates for "
                        + "everything already on the computer. Your files, your apps and their "
                        + "sign-ins are untouched. Download: up to " + basics.approximateSize
                        + " (usually far less); usually " + basics.typicalTime + "."
                        + (LinuxService.isDesktopRunning() ? "\n\nThe desktop keeps running meanwhile." : ""))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Update", (d, w) -> sendAppTask(LinuxService.ACTION_INSTALL_APP, basics.id))
                .show();
    }

    private void confirmRemove() {
        dialogBuilder()
                .setTitle("Delete the Linux computer?")
                .setMessage("Deletes Ubuntu and everything inside it: the AI apps, their sign-ins and "
                        + "every file in the Linux home folder. Files you saved in the Shared folder "
                        + "or on the phone itself are not touched. PocketLinux stays installed, and "
                        + "set-up can build the computer again. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, which) -> sendServiceAction(LinuxService.ACTION_REMOVE))
                .show();
    }

    private void startDesktop() {
        if (desktopOpening) return;
        desktopOpening = true;
        if (startButton != null) startButton.setEnabled(false);
        if (!LinuxService.isDesktopRunning()) sendServiceAction(LinuxService.ACTION_START_DESKTOP);
        try {
            startActivity(new Intent(this, DesktopActivity.class));
        } catch (Throwable error) {
            desktopOpening = false;
            if (startButton != null) startButton.setEnabled(true);
            Crash.save(this, error);
            showMessage("The desktop did not open", "Android refused to open the desktop screen. "
                    + "The error is saved under Settings → Last error report.");
        }
    }

    private void sendServiceAction(String action) {
        requestNotificationPermission(false);
        try {
            Intent intent = new Intent(this, LinuxService.class).setAction(action);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        } catch (Throwable error) {
            showMessage("Could not start", "Android refused to start the background task: "
                    + error.getClass().getSimpleName() + ". Open App info and allow background activity.");
        }
    }

    private void requestNotificationPermission(boolean fromRow) {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            if (fromRow) openAppInfo();
            return;
        }
        if (fromRow && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            openAppInfo();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        refreshPermissionRows();
        if (askBatteryAfterNotifications) {
            askBatteryAfterNotifications = false;
            askBatteryPermission();
        }
    }

    private void openBatterySettings() {
        if (batteryUnrestricted()) {
            openAppInfo();
            return;
        }
        // The targeted action is a single yes/no prompt; the list is the fallback for OEMs
        // that block it, and App info is the last resort.
        if (launch(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())))) {
            return;
        }
        if (!launch(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppInfo();
    }

    /**
     * The phone's battery page for this app, where Realme/OPPO (ColorOS) keep "Allow foreground
     * activity" and "Allow background activity". The page has no public intent, so the known
     * ColorOS activities are tried and App info (whose Battery usage row leads there) is the
     * fallback every phone has.
     */
    private void openBackgroundActivitySettings() {
        String[][] targets = {
                {"com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"},
                {"com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"},
                {"com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"},
        };
        for (String[] target : targets) {
            Intent intent = new Intent().setComponent(new ComponentName(target[0], target[1]));
            intent.putExtra("package_name", getPackageName());
            intent.putExtra("packageName", getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (launch(intent)) return;
        }
        android.widget.Toast.makeText(this, "Open Battery usage on this page, then turn on foreground and background activity",
                android.widget.Toast.LENGTH_LONG).show();
        openAppInfo();
    }

    /** Realme, OPPO, Xiaomi, vivo and Huawei each hide auto-launch in their own security app. */
    private void openAutoStartSettings() {
        String[][] targets = {
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
        };
        for (String[] target : targets) {
            Intent intent = new Intent().setComponent(new ComponentName(target[0], target[1]));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (launch(intent)) return;
        }
        showMessage("Auto-launch", "This phone does not expose an auto-launch page to other apps. "
                + "Open App info, then Battery usage, and turn on Allow auto-launch.");
        openAppInfo();
    }

    private void openAppInfo() {
        if (!launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())))) {
            launch(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private boolean launch(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }


    private void showMessage(String title, String message) {
        dialogBuilder().setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
    }

    private AlertDialog.Builder dialogBuilder() {
        return new AlertDialog.Builder(this, dark
                ? R.style.Theme_PocketLinux_Dialog
                : R.style.Theme_PocketLinux_Dialog_Light);
    }

    // --------------------------------------------------------------- system

    private boolean resolveDarkMode() {
        String mode = preferences.getString(ContainerRuntime.KEY_THEME, "system");
        if ("dark".equals(mode)) return true;
        if ("light".equals(mode)) return false;
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyOrientation() {
        // One rule for both screens, and it is written down in ScreenRotation: Portrait is
        // portrait and stays that way up, Landscape works either way round, and Auto-rotate
        // follows the phone even when the phone's own rotation lock is on -- because picking
        // Auto-rotate here IS the owner saying what they want.
        setRequestedOrientation(ScreenRotation.of(
                preferences.getString(ContainerRuntime.KEY_ORIENTATION, ScreenRotation.AUTO)));
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT < 35) {
            window.setStatusBarColor(dark ? Ui.DARK_BG : Ui.LIGHT_BG);
            // The gesture bar continues the bottom bar's own colour, so the two read as one.
            window.setNavigationBarColor(Ui.surface(dark));
        }
        // Some Android 13 OEM builds throw inside Window#getInsetsController() while their
        // DecorView controller is still null, so the stable view flags are used instead.
        int lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(dark ? 0 : lightBars);
    }

    /** The status bar goes above the page, the gesture bar under the bottom bar, a notch beside. */
    private void applySystemInsets() {
        shell.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = windowInsets.getSystemWindowInsetLeft();
                top = windowInsets.getSystemWindowInsetTop();
                right = windowInsets.getSystemWindowInsetRight();
                bottom = windowInsets.getSystemWindowInsetBottom();
            }
            pageHost.setPadding(left, top, right, 0);
            navBar.setPadding(left, 0, right, bottom);
            return windowInsets;
        });
        shell.requestApplyInsets();
    }

    private void showSafeScreen(Throwable error) {
        safeMode = true;
        try {
            ScrollView scroll = new ScrollView(this);
            scroll.setBackgroundColor(Ui.LIGHT_BG);
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(Ui.dp(this, 24), Ui.dp(this, 32), Ui.dp(this, 24), Ui.dp(this, 32));
            scroll.addView(root);

            ImageView logo = new ImageView(this);
            logo.setImageResource(R.drawable.icon_in_app);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            root.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 68), Ui.dp(this, 68)));
            root.addView(Ui.bold(this, "PocketLinux", 26, Ui.LIGHT_TEXT), Ui.matchWrap(this, 16));
            root.addView(Ui.title(this, "Recovery mode", 16, Ui.PRIMARY), Ui.matchWrap(this, 4));

            String reason = error == null ? "Unknown startup issue"
                    : error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : " · " + error.getMessage());
            if (reason.length() > 300) reason = reason.substring(0, 300);
            root.addView(Ui.text(this, "The main screen could not open, so the app stayed running instead of "
                    + "closing. Tap Try again. If this keeps happening, share the line below.\n\n" + reason,
                    14.5f, Ui.LIGHT_MUTED), Ui.matchWrap(this, 14));

            Button retry = Ui.primaryButton(this, "Try again", R.drawable.ic_rotate);
            retry.setOnClickListener(v -> recreate());
            root.addView(retry, Ui.matchWrap(this, 22));

            Button settings = Ui.secondaryButton(this, "App info", false, R.drawable.ic_info);
            settings.setOnClickListener(v -> openAppInfo());
            root.addView(settings, Ui.matchWrap(this, 10));
            setContentView(scroll);
            final int pad = Ui.dp(this, 24);
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                int top = Build.VERSION.SDK_INT >= 30
                        ? insets.getInsets(WindowInsets.Type.systemBars()).top : insets.getSystemWindowInsetTop();
                int bottom = Build.VERSION.SDK_INT >= 30
                        ? insets.getInsets(WindowInsets.Type.systemBars()).bottom : insets.getSystemWindowInsetBottom();
                root.setPadding(pad, pad + top, pad, pad + bottom);
                return insets;
            });
            scroll.requestApplyInsets();
        } catch (Throwable ignored) {
            TextView emergency = new TextView(this);
            emergency.setText("PocketLinux safe mode\nPlease reinstall the latest APK.");
            emergency.setTextSize(19);
            emergency.setPadding(48, 64, 48, 64);
            setContentView(emergency);
        }
    }
}
