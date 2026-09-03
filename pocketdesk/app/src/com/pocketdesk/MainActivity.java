package com.pocketdesk;

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
    static final String VERSION = "10.0.35";
    static final String EXTRA_ROUTE = "com.pocketdesk.route";
    private static final int TAB_HOME = 0;
    private static final int TAB_APPS = 1;
    private static final int TAB_SETTINGS = 2;

    private SharedPreferences preferences;
    private boolean dark;
    private boolean safeMode;
    private boolean receiverRegistered;
    private String pendingRoute;

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
    private DeviceProbe lastProbe;
    private Ui.Row crashRow;
    private Ui.Row appLogRow;
    private Ui.Row dataCapRow;
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
        TextView name = Ui.bold(this, "PocketDesk", 30, Color.WHITE);
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
        TextView system = Ui.text(this, "Ubuntu 24.04 LTS · official AI desktop apps · everything on this phone",
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
        handler.postDelayed(() -> {
            first.animate().alpha(0f).setDuration(260).start();
            second.animate().alpha(1f).setDuration(420).start();
        }, 1300L);
        handler.postDelayed(() -> intro.animate().alpha(0f).setDuration(360)
                .withEndAction(() -> {
                    shell.removeView(intro);
                    introShowing = false;
                    // The lock belongs after the opening screen, never on top of it -- and never
                    // on a screen the owner has already left, where onStart raises it instead.
                    if (started && AppLock.isLocked(this)) AppLock.show(this, shell, this::consumeRoute);
                }).start(), 3100L);
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
        showCrashRowIfNeeded();
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
        TextView version = Ui.text(this, "PocketDesk " + VERSION + " · Ubuntu 24.04 LTS · works on "
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
        logo.setContentDescription("PocketDesk");
        header.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams headingLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        headingLp.setMarginStart(Ui.dp(this, 12));
        header.addView(heading, headingLp);
        TextView name = Ui.bold(this, "PocketDesk", 22, text);
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
                        + DeviceProbe.formatBytes(DeviceCheck.LOW_FREE_BYTES) + " free to run comfortably. "
                        + "Delete or move some files on the phone; nothing inside the Linux computer needs to go."), false);
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
        compatibleRow.setStatus(check.compatible ? "COMPATIBLE" : "NOT COMPATIBLE",
                check.compatible ? Ui.SUCCESS : Ui.DANGER);
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
                "The computer inside this app is Ubuntu 24.04 LTS, running on the phone's own "
                        + "processor. It is built for one purpose: an agentic development "
                        + "environment, where the official AI desktop apps, Google Chrome and the "
                        + "developer tools they use run locally. It is not a feature-rich "
                        + "general-purpose desktop, and it does not try to be. Tap a line for the "
                        + "facts behind it.", 12.5f, muted),
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

        addAnswer(card, R.drawable.ic_check, "Every AI desktop app here ships for Linux",
                "OpenAI released the ChatGPT desktop app for Linux (with Codex) as a public preview "
                        + "on 11 August 2026, for Ubuntu 24.04 LTS and 26.04 LTS, Debian 13 and "
                        + "Fedora, on x64 and ARM64. Anthropic released Claude Desktop for Linux "
                        + "(with Claude Code) as a beta on 30 June 2026, for Ubuntu and Debian on "
                        + "x64 and ARM64, from its own apt repository. Cursor publishes Linux ARM64 "
                        + ".deb and AppImage builds, and Google publishes Antigravity for Linux. "
                        + "PocketDesk installs exactly those packages, from each publisher's own "
                        + "servers, and every app updates the way its publisher ships updates.", false);
        addAnswer(card, R.drawable.ic_phone, "Why not Windows or macOS on the phone",
                "Windows and macOS can only run inside a virtual machine, and a virtual machine "
                        + "needs hardware virtualisation that Android does not offer to apps: a "
                        + "phone's kernel keeps it for itself, and no app, script or setting "
                        + "changes that. Emulating one instead would run ten to fifty times slower "
                        + "than the phone, with less memory than either system needs. macOS is "
                        + "additionally licensed only for Apple's own computers. Wine, which runs "
                        + "some Windows programs on Linux, can run small native ARM64 Windows "
                        + "programs, but not the Windows editions of these AI apps, which are the "
                        + "same programs as their Linux editions in any case. Linux is not the "
                        + "fallback; it is the one system that runs here as itself.", false);
        addAnswer(card, R.drawable.ic_shield, "Safe, private, and yours",
                "Everything lives in this app's private storage on this phone: the system, the "
                        + "apps, their logins, your files. No PocketDesk account, no server, no "
                        + "analytics; Android's cloud backup is switched off for this app. Ubuntu "
                        + "24.04 LTS receives security updates from Canonical until April 2029 "
                        + "(and to 2034 with Ubuntu Pro), so the base does not go stale, and the "
                        + "AI apps update from their publishers for as long as they ship updates.", false);
        addAnswer(card, R.drawable.ic_bolt, "Fast for a phone, and built to last",
                "Nothing is emulated: the apps are ARM64 programs running directly on the "
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
                        + "and open it. PocketDesk's installer names the app and its publisher, "
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
                        + "packages. Windows .exe files. Android .apk files. Apps that need a real "
                        + "graphics card or hardware virtualisation. The installer refuses each of "
                        + "these with the reason rather than failing silently.",
                12.5f, muted), Ui.matchWrap(this, 4));
        return card;
    }

    private void refreshAppRows(boolean linuxInstalled, boolean busy, boolean running) {
        int installed = 0;
        // Grey rows say why they are grey.
        if (appsNote != null) {
            String why = null;
            if (!linuxInstalled && busy) why = "The Linux computer is being set up. These can be added as soon as it is ready.";
            else if (!linuxInstalled) why = "Set up the Linux computer on the Home tab first. Then each of these installs with one tap.";
            else if (LinuxService.isInstalling()) why = "An app is installing. One at a time; the others follow.";
            else if (busy) why = "Another task is running. These can be added when it finishes.";
            appsNote.setText(why == null ? "" : why);
            appsNote.setVisibility(why == null ? View.GONE : View.VISIBLE);
        }
        for (LinuxApps.App app : LinuxApps.CATALOG) {
            Ui.Row row = appRows.get(app.id);
            if (row == null) continue;
            boolean present = linuxInstalled && ContainerRuntime.isAppInstalled(this, app);
            if (present && !"essentials".equals(app.id)) installed++;
            row.setStatus(present ? "ADDED" : "ADD", present ? Ui.SUCCESS : Ui.accent(dark));
            row.setValue(present
                    ? "Installed · tap to update" + (app.removable() ? " or uninstall" : "")
                    : app.summary + " · " + app.approximateSize);
            // An open desktop is no obstacle: the install runs beside it and the new app
            // appears on it. Only a task already running (setup, another install) waits.
            boolean usable = linuxInstalled && !busy && !LinuxService.isInstalling();
            row.setEnabled(usable);
            row.setAlpha(usable ? 1f : 0.45f);
        }
        // Linux is set up but no AI app is on it yet: the one trip worth a dot.
        if (navItems[TAB_APPS] != null) navItems[TAB_APPS].setDot(linuxInstalled && installed == 0 && !busy);
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
                : app.summary + "\n\nInstalled from the publisher's own official package, "
                        + "verified by their signature. Nothing is downloaded from a browser.");
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
                        preferences.getInt(ContainerRuntime.KEY_UI_SCALE, ContainerRuntime.DEFAULT_UI_SCALE)),
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
        data.addView(Ui.text(this, "What the computer downloads stays inside it, where no other "
                + "app on this phone can read it. To move a file out, save it into the computer's "
                + "Shared folder — that one appears in the phone's Files app — or turn on Phone "
                + "files below and save straight to the phone.", 12.5f, muted), Ui.matchWrap(this, 10));

        // Privacy and safety
        LinearLayout privacy = group(page, "Privacy and safety");
        appLockToggle = new Ui.Toggle(this, R.drawable.ic_lock, "App lock",
                "Asks for your fingerprint or the phone's PIN right after the opening screen, and "
                        + "again whenever PocketDesk comes back to the front — the home screen and "
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
        permissions.addView(Ui.text(this, "PocketDesk asks for the minimum it needs. Tap a row to change it.",
                12.5f, muted), Ui.matchWrap(this, 0));
        notificationRow = new Ui.Row(this, R.drawable.ic_notification, "Notifications", "Checking…",
                R.drawable.ic_open_in_new, dark, v -> requestNotificationPermission(true));
        permissions.addView(notificationRow, Ui.matchWrap(this, 10));
        batteryOptimisationRow = new Ui.Row(this, R.drawable.ic_bolt, "Battery usage", "Checking…",
                R.drawable.ic_open_in_new, dark, v -> openBatterySettings());
        permissions.addView(batteryOptimisationRow, Ui.matchWrap(this, 8));
        Ui.Row backgroundRow = new Ui.Row(this, R.drawable.ic_auto_mode, "Background activity",
                "On the phone's battery page for PocketDesk, turn ON Allow foreground activity and "
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
                                .setMessage("Android will ask you to allow All files access for PocketDesk. "
                                        + "With it on, your phone's Download, DCIM (photos), Documents and "
                                        + "other folders appear inside the Linux computer as the Phone folder, "
                                        + "so ChatGPT, Claude and the browser can attach a file from the phone "
                                        + "and save one to it. Nothing on the phone is touched unless you "
                                        + "pick it in an app.\n\nApplies the next time the desktop starts.")
                                .setNegativeButton("Not now", null)
                                .setPositiveButton("Allow", (d, w) -> PhoneFiles.request(this))
                                .show();
                    }
                });
        permissions.addView(phoneFilesRow, Ui.matchWrap(this, 8));
        permissions.addView(new Ui.Row(this, R.drawable.ic_info, "App info",
                "Android's full settings page for PocketDesk",
                R.drawable.ic_open_in_new, dark, v -> openAppInfo()), Ui.matchWrap(this, 8));

        // Storage
        LinearLayout storage = group(page, "Storage");
        linuxSize = Ui.text(this, "", 12.5f, muted);
        storage.addView(linuxSize, Ui.matchWrap(this, 0));
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
        TextView credits = Ui.text(this, "Runs Ubuntu 24.04 LTS. Tux, the Linux mascot, by Larry "
                + "Ewing and The GIMP. Ubuntu is a trademark of Canonical Ltd. App names and logos "
                + "are the property of their respective owners. Open-source notices ship with the app.",
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
                android.widget.Toast.makeText(this, "App lock is on: PocketDesk asks for your fingerprint "
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
                "A real Ubuntu 24.04 LTS computer running inside this app, on your phone's own "
                        + "processor — a container, not a cloud PC and not a virtual machine. It is "
                        + "an agentic development environment: a desktop, Google Chrome, the "
                        + "developer tools (Python, Node.js, Git and a C/C++ compiler), and the "
                        + "four AI desktop apps, each the publisher's own official Linux build. It is "
                        + "not a general-purpose desktop with every feature of a PC. The phone stays "
                        + "a phone; the computer lives in this app's private storage and is removed "
                        + "with it.", true);

        addAnswer(card, R.drawable.ic_phone, "Is it all on my phone?",
                "Yes. The entire Linux computer runs locally on this phone — no cloud, no "
                        + "server, no PocketDesk account, no tracking or analytics of any kind. "
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
                        + "Projects and Downloads, right beside it — two folders, your choice each "
                        + "time. Saving into Phone puts the file on the phone.", false);

        addAnswer(card, R.drawable.ic_lock, "Are my logins safe?",
                "When you sign in to ChatGPT or Claude inside Linux, the login is stored by that "
                        + "app inside /home/coder — which is this app's private storage on "
                        + "this phone. Android lets no other app read it, and PocketDesk itself "
                        + "never sees, stores or sends your passwords. They travel only to "
                        + "OpenAI's or Anthropic's own servers, exactly as on any computer.", false);

        addAnswer(card, R.drawable.ic_storage, "Where do my files go?",
                "Inside the computer:\n"
                        + "• Projects — /home/coder/Projects, your work.\n"
                        + "• Downloads — /home/coder/Downloads, what the browser and the apps save. "
                        + "It stays inside the computer, where no other app on the phone can read it.\n"
                        + "• Shared — /home/coder/Shared, the way out: this one folder also appears in "
                        + "the phone's Files app under Android/data/com.pocketdesk/files/Shared. Save "
                        + "or copy a file there and the phone can open it.\n"
                        + "• Phone — the phone's own storage, once Phone files is on in Settings → "
                        + "Permissions. Saving there puts the file on the phone itself.\n\n"
                        + "The Linux system itself lives in this app's private storage "
                        + "(/data/data/com.pocketdesk/files/ubuntu-rootfs), which no other app can open.",
                false);

        addAnswer(card, R.drawable.ic_shield, "Is there virus and malware protection?",
                "Yes, and it is on by default — the same layered kind a phone uses, not a "
                        + "scanner you have to run.\n\n"
                        + "• Google Play Protect scans PocketDesk itself on your phone: when it is "
                        + "installed and again in the background, as it does with every Android app, "
                        + "sideloaded ones included. What it cannot do is look inside the Linux "
                        + "computer — Android keeps every app's private files private, and that same "
                        + "rule is what stops any other app on this phone reading yours. So the "
                        + "checking inside the computer is PocketDesk's job, and these are it.\n"
                        + "• Anything you download and install yourself goes through PocketDesk's own "
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
                        + "• The computer is sealed in: it lives in this app's private storage, has "
                        + "no open network ports, cannot see your phone's files unless you turn on "
                        + "Phone files, and no other app on the phone can reach into it.\n\n"
                        + "A separate antivirus (ClamAV and the like) is deliberately not included: "
                        + "on a 4 GB phone its background scanning would take memory the AI apps "
                        + "need, to look for Windows viruses that cannot run here anyway.", false);

        addAnswer(card, R.drawable.ic_download, "Can I install an app I downloaded myself?",
                "Yes — it works like tapping an APK from a website on Android, and PocketDesk "
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
                        + "the camera, microphone, location, contacts, calls or messages.", false);

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
                        + "700 MB download should not start on mobile data without you choosing it.",
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
                "Almost always memory. The AI desktop apps are full computer programs — "
                        + "ChatGPT alone is 1.3 GB — and on a 4 GB phone the system ends the "
                        + "biggest program when memory runs out: sometimes the app, sometimes the "
                        + "whole desktop, which then drops you back on this screen. So: one AI app "
                        + "at a time, and the browser closed when you are done with it. PocketDesk "
                        + "now helps: an AI app started while memory is short closes the browser's "
                        + "windows first, and a sign-in closes the browser once it has handed the "
                        + "result back.\n\nPocketDesk itself never closes an app that has a window "
                        + "open. The Home tab says when and why the computer last stopped, and an app "
                        + "that the phone closed says so on the desktop. Window → Force close ends "
                        + "an app that has stopped answering.", false);

        addAnswer(card, R.drawable.ic_lock, "Do I need an account, password or lock?",
                "No account and no separate password — the Linux computer is yours, "
                        + "protected by the phone itself. If you want a lock, turn on App lock in "
                        + "Settings: it asks for the phone's own fingerprint or PIN right after the "
                        + "opening screen when you start PocketDesk, and again each time it comes "
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

        addAnswer(card, R.drawable.ic_delete, "What if I uninstall PocketDesk?",
                "Android deletes the whole Linux computer with the app — the system, the AI apps, "
                        + "their sign-ins and every file inside it. Nothing is kept, and nothing "
                        + "is copied anywhere first.\n\nSo before uninstalling, move anything you "
                        + "want to keep out of the computer: save it into the Shared folder (it "
                        + "appears in the phone's Files app) or, with Phone files on, straight into "
                        + "the phone's own Download or Documents folder. Uninstalling one AI app "
                        + "instead — Apps tab → the installed row → Uninstall — leaves the computer "
                        + "and your files exactly as they are.", false);

        addAnswer(card, R.drawable.ic_desktop, "Can it run Windows or macOS instead?",
                "No, on any phone: Windows and macOS need a virtual machine, and Android keeps "
                        + "hardware virtualisation away from apps; emulation would be ten to fifty "
                        + "times slower than the phone, and macOS is licensed only for Apple's own "
                        + "computers. The Linux only, on purpose card above has the whole answer. "
                        + "The AI desktop apps are the same programs on all three systems, so "
                        + "nothing is missing here that a Windows edition would add. For Windows "
                        + "or macOS itself, a cloud PC used over remote desktop is the real route.", false);

        addAnswer(card, R.drawable.ic_volume, "Does sound work?",
                "Yes. Whatever the Linux computer plays — a voice reply, a video in the browser, "
                        + "a notification — comes out of the phone's speaker or headphones while "
                        + "the desktop screen is open. The volume keys set it, as does Screen → "
                        + "Volume. Sound into the computer (a microphone) is not carried yet.", false);

        addAnswer(card, R.drawable.ic_info, "The honest limits",
                "These are the permanent ones. This is an agentic development environment, "
                        + "not a feature-rich general desktop: what it does, it does fully; what "
                        + "it does not include is not missing by accident. A phone has no graphics "
                        + "card and a fraction of a PC's memory, so the AI desktop apps draw on the "
                        + "processor and open more slowly than on a PC, and one heavy app at a time is the "
                        + "comfortable way to work. Windows and macOS cannot run on a phone, and "
                        + "neither can anything that needs a virtual machine. Your AI accounts' "
                        + "own plans and limits still apply; PocketDesk cannot change them. "
                        + "PocketDesk is provided as is: it is a computer inside an app, not a "
                        + "backup service — copy anything precious into the Shared folder or onto "
                        + "the phone.", false);
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

    private static final String[] THEME_LABELS = {"Match phone", "Light", "Dark"};
    private static final String[] THEME_VALUES = {"system", "light", "dark"};
    private static final int[] THEME_ICONS =
            {R.drawable.ic_auto_mode, R.drawable.ic_light_mode, R.drawable.ic_dark_mode};

    private static final String[] ROTATION_LABELS = {"Automatic", "Portrait", "Landscape"};
    private static final String[] ROTATION_VALUES = {"auto", "portrait", "landscape"};
    private static final int[] ROTATION_ICONS =
            {R.drawable.ic_rotate, R.drawable.ic_phone, R.drawable.ic_desktop};

    // A clock does not know whether you are using the desktop; it only knows how long ago you
    // opened it. Smart watches the phone instead -- it lets a session you are working in run,
    // and ends one you walked away from, or one the battery can no longer carry.
    private static final String[] TIMER_LABELS = {
            "Smart · recommended", "1 hour", "2 hours", "4 hours", "6 hours", "Never stop"};
    private static final int[] TIMER_VALUES = {
            ContainerRuntime.SESSION_SMART, 60, 120, 240, 360, 0};

    // Lower dpi means more of the desktop fits, which is what makes it read like a PC screen
    // rather than three oversized windows.
    private static final String[] SCALE_LABELS = {"Compact · PC-like", "Normal", "Large"};
    private static final int[] SCALE_VALUES = {96, 120, 144};

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
        int current = preferences.getInt(ContainerRuntime.KEY_UI_SCALE, ContainerRuntime.DEFAULT_UI_SCALE);
        int selected = 0;
        for (int i = 0; i < SCALE_VALUES.length; i++) if (SCALE_VALUES[i] == current) selected = i;
        int[] icons = {R.drawable.ic_desktop, R.drawable.ic_desktop, R.drawable.ic_desktop};
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
            storageTile.set(DeviceProbe.formatBytes(probe.freeStorage),
                    probe.freeStorage < DeviceCheck.MIN_FREE_BYTES ? Ui.WARNING : Ui.text(dark));
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
            removeButton.setVisibility(installed ? View.VISIBLE : View.GONE);
        }
        final boolean updateDue = ContainerRuntime.basicsUpdateDue(this);
        if (basicsUpdateRow != null) {
            basicsUpdateRow.setVisibility(installed && updateDue ? View.VISIBLE : View.GONE);
        }
        if (!installed) {
            linuxSize.setText("The Linux computer is not set up yet. Set-up downloads about 30 MB, "
                    + "then about 700 MB of packages; the finished computer uses 3.5–4.5 GB.");
            return;
        }
        linuxSize.setText("Measuring the Linux computer's size…");
        new Thread(() -> {
            final long bytes = ContainerRuntime.directorySize(ContainerRuntime.rootfs(this));
            handler.post(() -> linuxSize.setText("The Linux computer is using "
                    + DeviceProbe.formatBytes(bytes) + " of this phone's storage."
                    + (updateDue ? "" : " Its basics are up to date.")));
        }, "pocketdesk-size").start();
    }

    private boolean notificationsAllowed() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean batteryUnrestricted() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void refreshPermissionRows() {
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
                    ? "On · your phone's storage is the Phone files folder inside the computer"
                    + (LinuxService.isDesktopRunning() ? " (from the next desktop start)" : "")
                    + " · tap to change in Android settings"
                    : "Off · turn on so ChatGPT, Claude and the browser can attach a file from the phone");
        }
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
        preferences.edit().putBoolean(ContainerRuntime.KEY_PERMISSION_INTRO, true).apply();
        if (notificationsAllowed() && batteryUnrestricted()) return;
        dialogBuilder()
                .setTitle("Allow three things first")
                .setMessage("Setting up the Linux computer downloads for 10–30 minutes in the background. "
                        + "Without these, the phone stops it half way.\n\n"
                        + "1. Notifications — ON, so you can watch progress and stop it any time.\n\n"
                        + "2. Battery usage — Unrestricted, so the download is not killed when the "
                        + "screen turns off.\n\n"
                        + "3. Background activity and Auto-launch — ON, on the phone's battery page for "
                        + "PocketDesk (Settings → Permissions opens it).\n\n"
                        + "Nothing else is requested. All three can be changed later under Settings → Permissions.")
                .setNegativeButton("Later", null)
                .setPositiveButton("Allow", (dialog, which) -> startPermissionFlow())
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
            statusHeadline.setText("Please wait");
            statusNote.setText("Keep this screen open or use other apps; a notification shows progress.");
        } else if (installed) {
            statusBadge.setText("Ready");
            statusHeadline.setText("The Linux computer is set up");
            long openedAt = preferences.getLong(ContainerRuntime.KEY_LAST_OPENED_AT, 0L);
            String note = "Ubuntu 24.04 LTS is on this phone. Open the desktop and tap an app.";
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
                    + "About 30 MB now, then about 700 MB of packages; 3.5–4.5 GB when finished. "
                    + "Then add the AI desktop apps from the Apps tab."
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
                        + "• Download: about 30 MB, then about 700 MB of packages\n"
                        + "• Final size: 3.5–4.5 GB\n"
                        + "• Wi-Fi or mobile data both work\n"
                        + "• Takes 15–40 minutes depending on your connection\n"
                        + "• Safe to stop: tapping Continue set-up later carries on from the step "
                        + "it reached, without downloading anything twice"
                        + warning)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Set up", (d, which) -> sendServiceAction(LinuxService.ACTION_SETUP))
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
                    + "of PocketDesk. This row comes back when a newer version has something to add.");
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
                        + "or on the phone itself are not touched. PocketDesk stays installed, and "
                        + "set-up can build the computer again. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, which) -> sendServiceAction(LinuxService.ACTION_REMOVE))
                .show();
    }

    private void startDesktop() {
        if (!LinuxService.isDesktopRunning()) sendServiceAction(LinuxService.ACTION_START_DESKTOP);
        startActivity(new Intent(this, DesktopActivity.class));
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

    /** A report the user has not seen yet is worth interrupting for; an old one just sits in the list. */
    private void showCrashRowIfNeeded() {
        if (appLogRow != null) {
            appLogRow.setVisibility(AppLogs.any(this) ? View.VISIBLE : View.GONE);
        }
        if (crashRow == null) return;
        long recordedAt = Crash.recordedAt(this);
        crashRow.setVisibility(recordedAt == 0 ? View.GONE : View.VISIBLE);
        if (recordedAt == 0) return;
        preferences.edit().putLong(ContainerRuntime.KEY_CRASH_SEEN, recordedAt).apply();
    }

    /**
     * What Linux printed when an app was launched. Without this the only symptom of a failed
     * start is that nothing happened, which is not something anyone can act on.
     */
    private void showAppLogs() {
        java.io.File[] logs = AppLogs.newestFirst(this);
        if (logs.length == 0) {
            showMessage("Nothing to show yet",
                    "Open the desktop and tap an app first. Whatever it prints is kept here.");
            return;
        }
        String report = AppLogs.readAll(this);
        String shown = report.length() > 4000 ? report.substring(0, 4000) + "…" : report;
        dialogBuilder()
                .setTitle("Why an app didn't open")
                .setMessage(shown)
                .setNegativeButton("Close", null)
                .setPositiveButton("Share", (dialog, which) -> {
                    Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "PocketDesk app report")
                            .putExtra(Intent.EXTRA_TEXT, report);
                    launch(Intent.createChooser(share, "Share app report"));
                })
                .show();
    }

    /** The recorded stack is the difference between "keeps stopping" and a fixable report. */
    private void showCrashReport() {
        String report = Crash.read(this);
        if (report.isEmpty()) {
            crashRow.setVisibility(View.GONE);
            return;
        }
        String shown = report.length() > 3000 ? report.substring(0, 3000) + "…" : report;
        dialogBuilder()
                .setTitle("Last error report")
                .setMessage(shown)
                .setNegativeButton("Close", null)
                .setNeutralButton("Clear", (dialog, which) -> {
                    Crash.clear(this);
                    crashRow.setVisibility(View.GONE);
                })
                .setPositiveButton("Share", (dialog, which) -> {
                    Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "PocketDesk error report")
                            .putExtra(Intent.EXTRA_TEXT, report);
                    launch(Intent.createChooser(share, "Share error report"));
                })
                .show();
    }

    private void showMessage(String title, String message) {
        dialogBuilder().setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
    }

    private AlertDialog.Builder dialogBuilder() {
        return new AlertDialog.Builder(this, dark
                ? R.style.Theme_PocketDesk_Dialog
                : R.style.Theme_PocketDesk_Dialog_Light);
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
        String value = preferences.getString(ContainerRuntime.KEY_ORIENTATION, "auto");
        if ("landscape".equals(value)) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
        else if ("portrait".equals(value)) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
        // "auto" leaves the phone's own rotation lock in charge. Forcing FULL_USER during
        // first launch restarts the activity on some OEM builds, so it is deliberately skipped.
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
            root.addView(Ui.bold(this, "PocketDesk", 26, Ui.LIGHT_TEXT), Ui.matchWrap(this, 16));
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
            emergency.setText("PocketDesk safe mode\nPlease reinstall the latest APK.");
            emergency.setTextSize(19);
            emergency.setPadding(48, 64, 48, 64);
            setContentView(emergency);
        }
    }
}
