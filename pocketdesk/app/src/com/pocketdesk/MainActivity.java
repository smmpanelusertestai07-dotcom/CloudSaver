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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

public final class MainActivity extends Activity {
    static final String VERSION = "3.0.0";

    private SharedPreferences preferences;
    private boolean dark;
    private boolean safeMode;
    private boolean receiverRegistered;

    private Ui.Tile networkTile;
    private Ui.Tile batteryTile;
    private Ui.Tile storageTile;
    private Ui.Tile heatTile;

    private TextView statusBadge;
    private TextView statusHeadline;
    private TextView statusNote;
    private TextView deviceDetails;
    private TextView linuxSize;

    private LinearLayout progressCard;
    private TextView progressTitle;
    private TextView progressDetail;
    private TextView progressPercent;
    private ProgressBar progressBar;

    private Button setupButton;
    private Button startButton;
    private Button stopButton;
    private Button removeButton;

    private Ui.Row appearanceRow;
    private Ui.Row rotationRow;
    private Ui.Row autoStopRow;
    private Ui.Row desktopScaleRow;
    private Ui.Row notificationRow;
    private Ui.Row batteryOptimisationRow;
    private Ui.Row autoStartRow;
    private Ui.Row crashRow;
    private Ui.Row appLogRow;
    private boolean askBatteryAfterNotifications;

    private final java.util.Map<String, Ui.Row> appRows = new java.util.LinkedHashMap<>();
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
            applyOrientation();
            View content = buildScreen();
            setContentView(content);
            configureSystemBars();
            applySystemInsets(content);
        } catch (Throwable error) {
            Crash.save(this, error);
            showSafeScreen(error);
        }
    }

    @Override protected void onStart() {
        super.onStart();
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
    }

    @Override protected void onResume() {
        super.onResume();
        if (safeMode) return;
        handler.removeCallbacks(liveRefresh);
        handler.post(liveRefresh);
        refreshDeviceCard();
        refreshPermissionRows();
        measureLinuxSize();
        maybeShowPermissionIntro();
        showCrashRowIfNeeded();
        // Re-entering mid-setup should show the running job straight away, not an empty card.
        if (LinuxService.isBusy() || LinuxService.lastMessage() != null) {
            renderProgress(LinuxService.lastMessage(), LinuxService.lastDetail(),
                    LinuxService.lastProgress(), LinuxService.isBusy(), LinuxService.lastWasError());
        }
    }

    @Override protected void onPause() {
        handler.removeCallbacks(liveRefresh);
        super.onPause();
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            try { unregisterReceiver(statusReceiver); } catch (Throwable ignored) {}
            receiverRegistered = false;
        }
        super.onStop();
    }

    // ---------------------------------------------------------------- screen

    private View buildScreen() {
        int text = Ui.text(dark);
        int muted = Ui.muted(dark);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(dark ? Ui.DARK_BG : Ui.LIGHT_BG);
        scroll.setClipToPadding(false);
        scroll.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 32));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(buildHeader(text, muted));
        page.addView(buildLiveTiles(), Ui.matchWrap(this, 14));
        page.addView(buildDesktopCard(text, muted), Ui.matchWrap(this, 14));
        page.addView(buildAppsCard(text, muted));
        page.addView(buildPhoneCard(text, muted));
        page.addView(buildSettingsCard(text, muted));
        page.addView(buildPermissionCard(text, muted));
        page.addView(buildPrivacyCard(text, muted));
        page.addView(buildAboutCard(text, muted));

        TextView version = Ui.text(this, "PocketDesk " + VERSION + " · Ubuntu 24.04 LTS ARM64", 12, muted);
        version.setGravity(Gravity.CENTER);
        page.addView(version, Ui.matchWrap(this, 2));
        return scroll;
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
        int pad = Ui.dp(this, 10);
        themeButton.setPadding(pad, pad, pad, pad);
        themeButton.setBackground(Ui.tappable(this, Ui.background(Ui.field(dark), 99, this), dark));
        themeButton.setContentDescription(dark ? "Switch to light theme" : "Switch to dark theme");
        themeButton.setOnClickListener(v -> {
            preferences.edit().putString(ContainerRuntime.KEY_THEME, dark ? "light" : "dark").apply();
            recreate();
        });
        header.addView(themeButton, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
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

        statusBadge = Ui.badge(this, "Checking", Color.WHITE, Color.argb(56, 255, 255, 255));
        card.addView(statusBadge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusHeadline = Ui.bold(this, "Setting up", 24, Color.WHITE);
        statusHeadline.setLetterSpacing(-0.02f);
        card.addView(statusHeadline, Ui.matchWrap(this, 12));

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

        setupButton = Ui.primaryButton(this, "Install Linux", R.drawable.ic_download);
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

    private View buildAppsCard(int text, int muted) {
        LinearLayout card = Ui.card(this, dark);
        card.addView(Ui.sectionTitle(this, "AI desktop apps", R.drawable.ic_apps, dark));
        card.addView(Ui.text(this,
                "The real desktop apps, not websites. Each tap fetches the newest build, so the same "
                        + "row also updates one you already have.", 12.5f, muted), Ui.matchWrap(this, 6));

        appRows.clear();
        for (LinuxApps.App app : LinuxApps.CATALOG) {
            Ui.Row row = new Ui.Row(this, app.displayIcon(), app.logoRes != 0, app.name,
                    app.summary + " · " + app.approximateSize, R.drawable.ic_download, dark,
                    v -> confirmApp(app));
            appRows.put(app.id, row);
            card.addView(row, Ui.matchWrap(this, appRows.size() == 1 ? 12 : 8));
        }
        return card;
    }

    private void refreshAppRows(boolean linuxInstalled, boolean busy, boolean running) {
        for (LinuxApps.App app : LinuxApps.CATALOG) {
            Ui.Row row = appRows.get(app.id);
            if (row == null) continue;
            boolean present = linuxInstalled && ContainerRuntime.isAppInstalled(this, app);
            row.setStatus(present ? "ADDED" : "ADD", present ? Ui.SUCCESS : Ui.accent(dark));
            row.setValue(present
                    ? "Installed \u00b7 tap any time to update to the newest build"
                    : app.summary + " \u00b7 " + app.approximateSize);
            boolean usable = linuxInstalled && !busy && !running;
            row.setEnabled(usable);
            row.setAlpha(usable ? 1f : 0.45f);
        }
    }

    private void confirmApp(LinuxApps.App app) {
        if (!ContainerRuntime.isInstalled(this)) {
            showMessage("Install Linux first", "Set up Linux once, then you can add desktop apps to it.");
            return;
        }
        boolean present = ContainerRuntime.isAppInstalled(this, app);
        StringBuilder message = new StringBuilder(present
                ? "Already installed. This fetches the newest build from the maker and updates "
                        + "it in place \u2014 your login and settings stay."
                : app.summary);
        message.append("\n\nDownload size: ").append(app.approximateSize)
                .append(present ? "" : "\nAlways installs the newest build.");
        if (app.caution != null) message.append("\n\n").append(app.caution);
        dialogBuilder()
                .setTitle((present ? "Update " : "Install ") + app.name + "?")
                .setMessage(message.toString())
                .setNegativeButton("Cancel", null)
                .setPositiveButton(present ? "Update" : "Install", (dialog, which) -> {
                    Intent intent = new Intent(this, LinuxService.class)
                            .setAction(LinuxService.ACTION_INSTALL_APP)
                            .putExtra(LinuxService.EXTRA_APP_ID, app.id);
                    requestNotificationPermission(false);
                    try {
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
                        else startService(intent);
                    } catch (Throwable error) {
                        showMessage("Could not start", "Android refused to start the background task.");
                    }
                })
                .show();
    }

    private View buildPhoneCard(int text, int muted) {
        LinearLayout card = Ui.card(this, dark);
        card.addView(Ui.sectionTitle(this, "Your phone", R.drawable.ic_phone, dark));
        card.addView(Ui.text(this, "Detected automatically. Nothing to type in.", 12.5f, muted),
                Ui.matchWrap(this, 6));
        deviceDetails = Ui.text(this, "Reading…", 13.5f, text);
        deviceDetails.setLineSpacing(Ui.dp(this, 5), 1f);
        card.addView(deviceDetails, Ui.matchWrap(this, 12));

        linuxSize = Ui.text(this, "", 12.5f, muted);
        card.addView(linuxSize, Ui.matchWrap(this, 10));

        removeButton = Ui.secondaryButton(this, "Remove Linux and free space", dark, R.drawable.ic_delete);
        removeButton.setOnClickListener(v -> confirmRemove());
        card.addView(removeButton, Ui.matchWrap(this, 12));
        return card;
    }

    private View buildSettingsCard(int text, int muted) {
        LinearLayout card = Ui.card(this, dark);
        card.addView(Ui.sectionTitle(this, "Settings", R.drawable.ic_settings, dark));

        appearanceRow = new Ui.Row(this, R.drawable.ic_palette, "Appearance",
                labelOf(THEME_LABELS, THEME_VALUES, preferences.getString(ContainerRuntime.KEY_THEME, "system")),
                R.drawable.ic_chevron, dark, v -> chooseText("Appearance", THEME_LABELS, THEME_ICONS,
                THEME_VALUES, ContainerRuntime.KEY_THEME, "system", appearanceRow, true));
        card.addView(appearanceRow, Ui.matchWrap(this, 12));

        rotationRow = new Ui.Row(this, R.drawable.ic_rotate, "Screen rotation",
                labelOf(ROTATION_LABELS, ROTATION_VALUES,
                        preferences.getString(ContainerRuntime.KEY_ORIENTATION, "auto")),
                R.drawable.ic_chevron, dark, v -> chooseText("Screen rotation", ROTATION_LABELS, ROTATION_ICONS,
                ROTATION_VALUES, ContainerRuntime.KEY_ORIENTATION, "auto", rotationRow, false));
        card.addView(rotationRow, Ui.matchWrap(this, 8));

        autoStopRow = new Ui.Row(this, R.drawable.ic_timer, "When to stop by itself",
                labelOfInt(TIMER_LABELS, TIMER_VALUES,
                        preferences.getInt(ContainerRuntime.KEY_SESSION_MINUTES,
                                ContainerRuntime.SESSION_SMART)),
                R.drawable.ic_chevron, dark, v -> chooseTimer());
        card.addView(autoStopRow, Ui.matchWrap(this, 8));

        desktopScaleRow = new Ui.Row(this, R.drawable.ic_desktop, "Desktop text size",
                labelOfInt(SCALE_LABELS, SCALE_VALUES,
                        preferences.getInt(ContainerRuntime.KEY_UI_SCALE, ContainerRuntime.DEFAULT_UI_SCALE)),
                R.drawable.ic_chevron, dark, v -> chooseScale());
        card.addView(desktopScaleRow, Ui.matchWrap(this, 8));

        Ui.Toggle wifiOnly = new Ui.Toggle(this, R.drawable.ic_wifi, "Download on Wi-Fi only",
                "Off means mobile data is allowed", preferences.getBoolean(ContainerRuntime.KEY_WIFI_ONLY, false), dark);
        wifiOnly.control.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(ContainerRuntime.KEY_WIFI_ONLY, checked).apply());
        card.addView(wifiOnly, Ui.matchWrap(this, 8));

        Ui.Toggle guard = new Ui.Toggle(this, R.drawable.ic_shield, "Overheat protection",
                "Stops Linux if the phone gets too hot", preferences.getBoolean(ContainerRuntime.KEY_THERMAL_GUARD, true), dark);
        guard.control.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(ContainerRuntime.KEY_THERMAL_GUARD, checked).apply());
        card.addView(guard, Ui.matchWrap(this, 8));
        return card;
    }

    private View buildPermissionCard(int text, int muted) {
        LinearLayout card = Ui.card(this, dark);
        card.addView(Ui.sectionTitle(this, "Permissions", R.drawable.ic_lock, dark));
        card.addView(Ui.text(this, "PocketDesk asks for the minimum it needs. Tap a row to change it.",
                12.5f, muted), Ui.matchWrap(this, 6));

        notificationRow = new Ui.Row(this, R.drawable.ic_notification, "Notifications", "Checking…",
                R.drawable.ic_open_in_new, dark, v -> requestNotificationPermission(true));
        card.addView(notificationRow, Ui.matchWrap(this, 12));

        batteryOptimisationRow = new Ui.Row(this, R.drawable.ic_bolt, "Battery usage", "Checking…",
                R.drawable.ic_open_in_new, dark, v -> openBatterySettings());
        card.addView(batteryOptimisationRow, Ui.matchWrap(this, 8));

        autoStartRow = new Ui.Row(this, R.drawable.ic_power, "Auto-start",
                "Turn this ON in the list that opens, so the desktop keeps running with the screen off",
                R.drawable.ic_open_in_new, dark, v -> openAutoStartSettings());
        autoStartRow.setStatus("CHECK", Ui.muted(dark));
        card.addView(autoStartRow, Ui.matchWrap(this, 8));

        card.addView(new Ui.Row(this, R.drawable.ic_info, "App info",
                "Android's full settings page for PocketDesk",
                R.drawable.ic_open_in_new, dark, v -> openAppInfo()), Ui.matchWrap(this, 8));

        appLogRow = new Ui.Row(this, R.drawable.ic_terminal, "Why an app didn't open",
                "The report Linux wrote the last time you tapped an app.",
                R.drawable.ic_chevron, dark, v -> showAppLogs());
        appLogRow.setVisibility(AppLogs.any(this) ? View.VISIBLE : View.GONE);
        card.addView(appLogRow, Ui.matchWrap(this, 8));

        crashRow = new Ui.Row(this, R.drawable.ic_help, "Last error report",
                "Something went wrong earlier. Tap to view or share it.",
                R.drawable.ic_chevron, dark, v -> showCrashReport());
        crashRow.setStatus("NEW", Ui.WARNING);
        crashRow.setVisibility(Crash.read(this).isEmpty() ? View.GONE : View.VISIBLE);
        card.addView(crashRow, Ui.matchWrap(this, 8));
        return card;
    }

    /**
     * The questions anyone would ask before trusting a phone with a computer, answered in the
     * app itself rather than in a chat thread that scrolls away.
     */
    private View buildPrivacyCard(int text, int muted) {
        LinearLayout card = Ui.card(this, dark);
        card.addView(Ui.sectionTitle(this, "Privacy and your questions", R.drawable.ic_shield, dark));
        card.addView(Ui.text(this,
                "Short version: the whole Linux computer lives inside this app, on this phone, "
                        + "and belongs to you. Nothing is uploaded anywhere.", 12.5f, muted),
                Ui.matchWrap(this, 6));

        addAnswer(card, R.drawable.ic_phone, "Is it all on my phone?",
                "Yes. The entire Linux computer runs locally on this phone \u2014 no cloud, no "
                        + "server, no PocketDesk account, no tracking or analytics of any kind. "
                        + "The internet is used only to download Ubuntu, the apps you choose, and "
                        + "whatever you yourself open in the browser or an AI app.", true);

        addAnswer(card, R.drawable.ic_lock, "Are my logins safe?",
                "When you sign in to ChatGPT or Claude inside Linux, the login is stored by that "
                        + "app inside /home/coder \u2014 which is this app's private storage on "
                        + "this phone. Android lets no other app read it, and PocketDesk itself "
                        + "never sees, stores or sends your passwords. They travel only to "
                        + "OpenAI's or Anthropic's own servers, exactly as on any computer.", false);

        addAnswer(card, R.drawable.ic_storage, "Where do my files go?",
                "Your work: /home/coder/Projects, inside Linux.\n\n"
                        + "Browser downloads: /home/coder/Downloads \u2014 the same folder also "
                        + "appears in your phone's Files app at Android/data/com.pocketdesk/"
                        + "files/Shared/Downloads.\n\n"
                        + "The Linux system itself: this app's private storage "
                        + "(/data/data/com.pocketdesk/files/ubuntu-rootfs), which no other app "
                        + "can open.", false);

        addAnswer(card, R.drawable.ic_shield, "What can this app touch on my phone?",
                "Its permissions are: internet, network status, notifications, and running in "
                        + "the background with battery settings.\n\nIt has NO permission for "
                        + "your storage, camera, microphone, location, contacts, calls or "
                        + "messages \u2014 so it cannot read your photos, files or chats even if "
                        + "it wanted to.", false);

        addAnswer(card, R.drawable.ic_delete, "What if I uninstall?",
                "Android deletes the whole Linux computer with the app \u2014 system, apps, "
                        + "logins, files, everything. Before uninstalling, copy anything you want "
                        + "to keep into Downloads, which stays visible to your phone.", false);

        addAnswer(card, R.drawable.ic_timer, "Why is an app slow to open?",
                "The AI desktop apps are full computer programs \u2014 ChatGPT alone is 1.3 GB "
                        + "\u2014 and this phone runs them with a fraction of a PC's memory. The "
                        + "first open after installing is the slowest. If one fails, the reason "
                        + "appears on screen and in \u201cWhy an app didn't open\u201d above.", false);
        return card;
    }

    private void addAnswer(LinearLayout card, int iconRes, String question, String answer,
                           boolean first) {
        card.addView(new Ui.Row(this, iconRes, question, null, R.drawable.ic_chevron, dark,
                v -> showMessage(question, answer)), Ui.matchWrap(this, first ? 12 : 8));
    }

    private View buildAboutCard(int text, int muted) {
        LinearLayout card = Ui.card(this, dark);
        card.addView(Ui.sectionTitle(this, "About", R.drawable.ic_help, dark));
        card.addView(Ui.text(this,
                "PocketDesk runs a real Ubuntu 24.04 ARM64 desktop inside this app, on your phone. "
                        + "It uses your phone's own Linux kernel, so it is a container — not Windows and not a hardware VM.",
                13.5f, muted), Ui.matchWrap(this, 8));

        card.addView(featureRow(R.drawable.ic_terminal, "Terminal, files and Openbox desktop", text), Ui.matchWrap(this, 12));
        card.addView(featureRow(R.drawable.ic_keyboard, "Hardware keyboard and coding key row", text), Ui.matchWrap(this, 8));
        card.addView(featureRow(R.drawable.ic_mouse, "Touchpad, left/right click and USB or Bluetooth mouse", text), Ui.matchWrap(this, 8));
        card.addView(featureRow(R.drawable.ic_apps, "ChatGPT, Claude Desktop, Cursor and Antigravity", text), Ui.matchWrap(this, 8));
        card.addView(featureRow(R.drawable.ic_network, "A browser is installed with Linux, so one is there from the start", text), Ui.matchWrap(this, 8));
        card.addView(featureRow(R.drawable.ic_storage, "Downloads open in your phone's Files app too: Android/data/com.pocketdesk/files/Shared", text), Ui.matchWrap(this, 8));

        card.addView(Ui.text(this,
                "Limits: needs an ARM64 phone with 4 GB RAM and 4 GB free space. The AI desktop apps are "
                        + "large Electron builds and run slowly on 4 GB. Computer Use is not offered on Linux by "
                        + "either OpenAI or Anthropic, and your account limits still apply. Files stay in this "
                        + "app's private storage: /home/coder inside Linux, which is this app's own data "
                        + "folder on Android. Projects and Downloads are the two folders you will use; "
                        + "removing Linux deletes both, so copy anything you want to keep out first.",
                12.5f, muted), Ui.matchWrap(this, 12));
        return card;
    }

    private View featureRow(int iconRes, String label, int text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.icon(this, iconRes, Ui.accent(dark), 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(Ui.dp(this, 12));
        row.addView(Ui.text(this, label, 13.5f, text), lp);
        return row;
    }

    // ------------------------------------------------------------- choosers

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
            networkTile.set(probe.network);
            batteryTile.set(probe.batteryPercent < 0 ? "—" : probe.batteryPercent + "%",
                    probe.batteryPercent >= 0 && probe.batteryPercent <= 15 ? Ui.WARNING : Ui.text(dark));
            storageTile.set(DeviceProbe.formatBytes(probe.freeStorage),
                    probe.freeStorage < 4L * 1024 * 1024 * 1024 ? Ui.WARNING : Ui.text(dark));
            if (probe.batteryTempC > 0) {
                heatTile.set(String.format(Locale.ROOT, "%.0f°C", probe.batteryTempC),
                        probe.batteryTempC >= 44f ? Ui.WARNING : Ui.text(dark));
            } else {
                heatTile.set(DeviceProbe.thermalName(probe.thermalStatus));
            }
        } catch (Throwable ignored) {
            networkTile.set("—");
        }
    }

    private void refreshDeviceCard() {
        if (deviceDetails == null) return;
        try {
            DeviceProbe probe = DeviceProbe.read(this);
            String verdict;
            if (!DeviceProbe.isArm64()) verdict = "Not supported · ARM64 phone required";
            else if (probe.freeStorage < 4L * 1024 * 1024 * 1024) verdict = "Free up space · 4 GB needed";
            else verdict = "Ready for Linux";
            deviceDetails.setText(probe.model
                    + "\n" + probe.androidVersion + " · " + probe.abi
                    + "\n" + DeviceProbe.formatBytes(probe.totalRam) + " RAM · "
                    + DeviceProbe.formatBytes(probe.freeStorage) + " free"
                    + "\n" + verdict);
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
        if (!installed) {
            linuxSize.setText("Linux is not installed yet. Download is about 30 MB; the finished system uses 1.5–3 GB.");
            return;
        }
        linuxSize.setText("Measuring installed size…");
        new Thread(() -> {
            final long bytes = ContainerRuntime.directorySize(ContainerRuntime.rootfs(this));
            handler.post(() -> linuxSize.setText("Linux is using " + DeviceProbe.formatBytes(bytes)
                    + " of this phone's storage."));
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
                .setTitle("Allow two things first")
                .setMessage("Installing Linux downloads for 10–30 minutes in the background. "
                        + "Without these, Android stops it half way.\n\n"
                        + "1. Notifications — ON, so you can watch progress and stop it any time.\n\n"
                        + "2. Battery usage — Unrestricted, so the download is not killed when the "
                        + "screen turns off.\n\n"
                        + "Nothing else is requested. You can change both later under Permissions.")
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

    private void refreshState() {
        if (statusBadge == null) return;
        boolean installed = ContainerRuntime.isInstalled(this);
        boolean running = LinuxService.isDesktopRunning();
        boolean busy = LinuxService.isBusy();

        if (running) {
            statusBadge.setText("Running");
            statusHeadline.setText("Desktop is running");
            statusNote.setText("Tap Open desktop to go back to your Linux screen.");
        } else if (busy) {
            statusBadge.setText("Working");
            statusHeadline.setText("Please wait");
            statusNote.setText("Keep this screen open. You can use other apps; a notification shows progress.");
        } else if (installed) {
            statusBadge.setText("Ready");
            statusHeadline.setText("Ready to open");
            statusNote.setText("Ubuntu 24.04 is installed on this phone. Open the desktop to start coding.");
        } else {
            statusBadge.setText("Not installed");
            statusHeadline.setText("Install Linux once");
            statusNote.setText("Downloads Ubuntu 24.04 ARM64 and sets up a desktop, terminal and coding tools.");
        }

        setupButton.setVisibility(installed ? View.GONE : View.VISIBLE);
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
        DeviceProbe probe = DeviceProbe.read(this);
        if (!DeviceProbe.isArm64()) {
            showMessage("Not compatible", "PocketDesk needs an ARM64 phone. This phone reports " + probe.abi + ".");
            return;
        }
        if (probe.freeStorage < 4L * 1024 * 1024 * 1024) {
            showMessage("More space needed", "Keep at least 4 GB free before installing. You have "
                    + DeviceProbe.formatBytes(probe.freeStorage) + " free right now.");
            return;
        }
        String warning = batteryUnrestricted() ? ""
                : "\n\nBattery usage is still Restricted. Android may stop the setup when the screen "
                + "turns off — set it to Unrestricted under Permissions first.";
        dialogBuilder()
                .setTitle("Install Linux?")
                .setMessage("Ubuntu 24.04 ARM64 will be downloaded and set up inside this app.\n\n"
                        + "• Download: about 30 MB, then desktop packages\n"
                        + "• Final size: 1.5–3 GB\n"
                        + "• Wi-Fi or mobile data both work\n"
                        + "• Takes 10–30 minutes depending on your connection"
                        + warning)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Install", (d, which) -> sendServiceAction(LinuxService.ACTION_SETUP))
                .show();
    }

    private void confirmRemove() {
        dialogBuilder()
                .setTitle("Remove Linux?")
                .setMessage("Deletes the Ubuntu system and everything inside it, including files saved in the Linux home folder. "
                        + "Files you kept in the Shared folder are not deleted. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, which) -> sendServiceAction(LinuxService.ACTION_REMOVE))
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

    /** Realme, OPPO, Xiaomi, vivo and Huawei each hide auto-start in their own security app. */
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
        showMessage("Auto-start", "This phone does not expose an auto-start page to other apps. "
                + "Open App info, then Battery, and allow background activity.");
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
        if (preferences.getLong(ContainerRuntime.KEY_CRASH_SEEN, 0L) == recordedAt) return;
        preferences.edit().putLong(ContainerRuntime.KEY_CRASH_SEEN, recordedAt).apply();
        showCrashReport();
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
        String shown = report.length() > 4000 ? report.substring(0, 4000) + "\u2026" : report;
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
        String shown = report.length() > 3000 ? report.substring(0, 3000) + "\u2026" : report;
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
                ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert);
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
            window.setNavigationBarColor(dark ? Ui.DARK_BG : Ui.LIGHT_BG);
        }
        // Some Android 13 OEM builds throw inside Window#getInsetsController() while their
        // DecorView controller is still null, so the stable view flags are used instead.
        int lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(dark ? 0 : lightBars);
    }

    private void applySystemInsets(View root) {
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
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
            view.setPadding(baseLeft + left, baseTop + top, baseRight + right, baseBottom + bottom);
            return windowInsets;
        });
        root.requestApplyInsets();
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
            applySystemInsets(root);
        } catch (Throwable ignored) {
            TextView emergency = new TextView(this);
            emergency.setText("PocketDesk safe mode\nPlease reinstall the latest APK.");
            emergency.setTextSize(19);
            emergency.setPadding(48, 64, 48, 64);
            setContentView(emergency);
        }
    }
}
