package com.pocketide;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * Settings, including a permission manager that does what the phone's own Settings will not:
 * gather every switch this app depends on into one list and say what each one is for.
 *
 * The permission rows matter more here than in most apps. Set-up runs for twenty minutes, and
 * on a Realme, OPPO, Xiaomi, vivo, OnePlus, Huawei or Samsung phone there are two or three
 * separate switches -- battery optimisation, auto-launch, background activity -- kept in the
 * manufacturer's own security app, under a different name and a different screen on each. An
 * owner who does not find them sees a download that stops for no reason. So each row opens the
 * real page on this phone rather than dumping everyone into App info.
 */
final class SettingsPane implements Pane {

    private Activity host;

    /**
     * What the workspace reported last time it was asked, so Settings can draw immediately.
     *
     * Static because the answer belongs to the workspace rather than to one instance of this
     * screen: leaving Settings and coming back should not make the rows say "not installed"
     * for a second while a fresh check runs.
     */
    private static Tools.State lastKnownTools;
    private static boolean toolsCheckRunning;

    @Override public void shown(Activity activity) { host = activity; }

    @Override public void hidden(Activity activity) {}

    private Ui.Row themeRow;
    private Ui.Row layoutRow;
    private Ui.Row zoomRow;
    private Ui.Row dataRow;
    private Ui.Row wifiRow;
    private Ui.Row sizeRow;




    @Override public String key() { return "settings"; }

    @Override public View build(Activity activity) {
        host = activity;
        boolean dark = Ui.dark(host);

        LinearLayout content = Ui.column(host);
        content.addView(appearance(dark));
        content.addView(safety(dark), Ui.wide(host, 18));
        content.addView(permissions(dark), Ui.wide(host, 18));
        content.addView(network(dark), Ui.wide(host, 18));
        content.addView(computer(dark), Ui.wide(host, 18));
        content.addView(updates(dark), Ui.wide(host, 18));
        content.addView(storage(dark), Ui.wide(host, 18));
        content.addView(about(dark), Ui.wide(host, 18));

        // The page itself, unwrapped, so the frame can let it scroll under the floating bar.
        return Ui.page(host, content, dark);
    }

    // ------------------------------------------------------------------ safety

    /**
     * The app lock, and the one honest note about what it can and cannot do.
     *
     * It is here rather than buried under Permissions because of what is behind it: an editor
     * with three coding agents signed into their publishers' accounts, a terminal holding git
     * credentials, and whatever source the owner is being paid to write. Anyone who picks up an
     * unlocked phone has every bit of that.
     */
    private View safety(boolean dark) {
        LinearLayout group = group(dark, "Safety");
        LinearLayout list = list(dark);

        boolean canLock = AppLock.hasScreenLock(host);
        boolean on = AppLock.enabled(host) && canLock;

        Ui.Row lock = Ui.row(host, dark, R.drawable.ic_lock, "App lock",
                !canLock
                        ? "Set a PIN, pattern or fingerprint on this phone first"
                        : on
                            ? "On · asked every time PocketIDE comes back to the front"
                            : "Off · anyone holding this phone can open the editor",
                v -> toggleLock(canLock, on));
        if (on) lock.setState(Ui.running(dark));
        else if (canLock) lock.setState(Ui.needsYou(dark));
        lock.setEnabled(canLock);
        lock.setAlpha(canLock ? 1f : 0.55f);
        list.addView(lock);

        list.addView(Ui.divider(host, dark, true));

        boolean files = PhoneFiles.enabled(host);
        Ui.Row phone = Ui.row(host, dark, R.drawable.ic_phone, "The phone's files",
                PhoneFiles.state(host), v -> togglePhoneFiles(files));
        if (files) phone.setState(Ui.running(dark));
        list.addView(phone);

        group.addView(list, Ui.wide(host, 8));

        // Shown once, after the lock turned itself off because the phone's own screen lock was
        // removed. Silently disabling it would leave an owner believing they were protected.
        if (Prefs.of(host).getBoolean(Prefs.LOCK_NOTICE, false)) {
            group.addView(note(dark,
                    "The app lock was turned off because this phone no longer has a screen "
                            + "lock. Set one in the phone's own Settings and you can turn it "
                            + "back on here."), Ui.wide(host, 10));
            android.widget.TextView setOne = Ui.button(host, "Open the phone's security settings",
                    false, dark);
            setOne.setOnClickListener(v -> Permissions.openSecuritySettings(host));
            group.addView(setOne, Ui.wide(host, 10));
            Prefs.of(host).edit().putBoolean(Prefs.LOCK_NOTICE, false).apply();
        } else {
            group.addView(note(dark,
                    "The lock uses the phone's own fingerprint or PIN; PocketIDE never stores "
                            + "one of its own. While it is on, the app is also kept out of the "
                            + "recent-apps preview, so the editor cannot be read from there."),
                    Ui.wide(host, 10));
        }
        return group;
    }

    /**
     * Turning it on asks for the fingerprint first.
     *
     * A switch that turns a lock on without checking anything is a switch that can lock the
     * owner out on a phone whose sensor does not work. Proving it works before relying on it
     * costs one tap.
     */
    private void toggleLock(boolean canLock, boolean on) {
        if (!canLock) {
            Dialogs.message(host, "This phone has no screen lock",
                    "The app lock uses the phone's own fingerprint, PIN, pattern or password. "
                            + "Set one in the phone's Settings and this can be turned on.");
            return;
        }
        if (on) {
            Prefs.of(host).edit().putBoolean(Prefs.APP_LOCK, false).apply();
            AppLock.applyWindowSecurity(host);
            MainActivity.rebuild(host);
            return;
        }
        AppLock.prompt(host, unlocked -> {
            if (!unlocked) return;
            Prefs.of(host).edit().putBoolean(Prefs.APP_LOCK, true).apply();
            AppLock.applyWindowSecurity(host);
            MainActivity.rebuild(host);
        });
    }

    /**
     * The phone's storage, in or out of the workspace.
     *
     * Turning it OFF is immediate and needs nothing from Android -- the bind simply stops being
     * added the next time the workspace starts. Turning it ON records the intent here and then
     * sends the owner to Android's own page, because that grant is Android's to give and this
     * app cannot fake having it. PhoneFiles.state() reports the in-between state honestly.
     */
    private void togglePhoneFiles(boolean on) {
        if (on) {
            Dialogs.confirm(host, "Stop Linux seeing the phone's files?",
                    "~/phone disappears from inside Linux the next time it starts. "
                            + "Nothing on the phone is deleted, and nothing already copied into "
                            + "Linux is affected.\n\nAndroid's own permission stays "
                            + "granted until you remove it in the phone's Settings.",
                    "Turn off", () -> {
                        Prefs.of(host).edit().putBoolean(Prefs.PHONE_FILES, false).apply();
                        MainActivity.rebuild(host);
                        restartNeeded();
                    });
            return;
        }
        Dialogs.confirm(host, "Let Linux see the phone's files?",
                "The phone's storage -- Download, DCIM, Documents and the rest -- appears "
                        + "inside Linux as ~/phone. An agent working in the editor can "
                        + "then read a file you put in Downloads, and write a finished build "
                        + "somewhere that survives this app being uninstalled.\n\nIt can also "
                        + "read everything else on that storage. Leave it off unless you want "
                        + "that.\n\nAndroid asks for this on a page of its own; you will be "
                        + "sent there next.",
                "Continue", () -> {
                    Prefs.of(host).edit().putBoolean(Prefs.PHONE_FILES, true).apply();
                    if (!PhoneFiles.allowed(host)) PhoneFiles.request(host);
                    MainActivity.rebuild(host);
                });
    }

    // ------------------------------------------------------------------ appearance

    private View appearance(boolean dark) {
        LinearLayout group = group(dark, "Appearance");
        LinearLayout list = list(dark);

        themeRow = Ui.row(host, dark, R.drawable.ic_palette, "Theme", Theme.label(host),
                v -> Dialogs.choose(host, "Theme", Theme.LABELS, Theme.ICONS,
                        indexOf(Theme.VALUES, Theme.choice(host)), index -> {
                            Theme.set(host, Theme.VALUES[index]);
                            // Repainted in place. recreate() replayed the opening frame --
                            // the brand splash -- for a change of colour, which read as the
                            // app restarting.
                            Theme.apply(host);
                            MainActivity.rebuild(host);
                        }));
        list.addView(themeRow);
        list.addView(Ui.divider(host, dark, true));

        // Its own row rather than a corner of the editor, because it decides both screens and
        // because the reason it exists is a phone-wide setting: with the phone's auto-rotate
        // off, nothing in this app can ever be shown sideways until this row says so.
        list.addView(Ui.row(host, dark, R.drawable.ic_rotate, "Screen rotation",
                Rotation.label(host),
                v -> Dialogs.choose(host, "Screen rotation", Rotation.LABELS, Rotation.ICONS,
                        indexOf(Rotation.VALUES, Rotation.choice(host)), index -> {
                            Rotation.set(host, Rotation.VALUES[index]);
                            Rotation.apply(host);
                            MainActivity.rebuild(host);
                        })));
        list.addView(Ui.divider(host, dark, true));

        String layout = Prefs.of(host).getString(Prefs.EDITOR_LAYOUT, "auto");
        String[] layoutLabels = {"Automatic · recommended", "Phone", "Desktop"};
        String[] layoutValues = {"auto", "phone", "desktop"};
        layoutRow = Ui.row(host, dark, R.drawable.ic_phone, "Editor layout",
                "auto".equals(layout)
                        ? "Automatic · " + (Screen.wideEnoughForDesktop(host)
                                ? "desktop, this screen is wide enough"
                                : "phone, agents in a bottom bar")
                        : "phone".equals(layout)
                            ? "Phone · agents in a bottom bar, chrome hidden"
                            : "Desktop · the full Visual Studio Code layout",
                v -> Dialogs.choose(host, "Editor layout", layoutLabels,
                        new int[]{R.drawable.ic_auto_mode, R.drawable.ic_phone,
                                R.drawable.ic_desktop},
                        Math.max(0, indexOf(layoutValues, layout)), index -> {
                            Prefs.of(host).edit()
                                    .putString(Prefs.EDITOR_LAYOUT, layoutValues[index]).apply();
                            MainActivity.rebuild(host);
                            restartNeeded();
                        }));
        list.addView(layoutRow);
        list.addView(Ui.divider(host, dark, true));

        int zoom = Prefs.of(host).getInt(Prefs.EDITOR_ZOOM, 0);
        String[] zoomLabels = {"Automatic · recommended", "Smaller", "Normal", "Larger", "Largest"};
        int[] zoomValues = {0, 10, 12, 15, 18};
        zoomRow = Ui.row(host, dark, R.drawable.ic_fit, "Editor size",
                zoom == 0 ? "Automatic · " + Screen.describeAutomatic(host)
                        : zoomLabels[Math.max(1, indexOfInt(zoomValues, zoom))],
                v -> Dialogs.choose(host, "Editor size", zoomLabels, null,
                        Math.max(0, indexOfInt(zoomValues, zoom)), index -> {
                            Prefs.of(host).edit()
                                    .putInt(Prefs.EDITOR_ZOOM, zoomValues[index]).apply();
                            MainActivity.rebuild(host);
                            restartNeeded();
                        }));
        list.addView(zoomRow);

        group.addView(list, Ui.wide(host, 8));
        group.addView(note(dark,
                "Left alone, the editor sizes itself to this screen: it works out the zoom from "
                        + "the phone's own width and your text-size setting, and chooses the "
                        + "desktop layout on a screen wide enough for it. Phone layout uses "
                        + "Visual Studio Code's own settings to move the agents into a bottom "
                        + "bar and hide the desktop chrome — nothing is removed, and Desktop "
                        + "brings all of it back."), Ui.wide(host, 8));
        return group;
    }

    // ------------------------------------------------------------------ the computer

    /**
     * What the agents can reach beyond the editor, and what they can never reach.
     *
     * None of it ships with set-up. Set-up is already 410 MB and twenty minutes, a browser is
     * another 120 and the build tools 340 on top, and most people need none of it on the first
     * day. So each layer says what it costs before it spends anything.
     *
     * The last row is the one that matters most and is the easiest to leave out: what cannot be
     * done here. An owner who discovers the emulator is impossible after an afternoon of trying
     * has been misled by silence, so it is a row rather than a footnote.
     */
    private View computer(boolean dark) {
        LinearLayout group = group(dark, "The computer");
        LinearLayout list = list(dark);
        boolean ready = Workspace.installed(host);
        // NOT Tools.read(host) here. That starts PRoot and runs a script inside it, which takes
        // seconds -- on the thread that draws the screen it is an ANR, and Settings is the one
        // screen an owner opens when something is already going wrong. The rows are built with
        // what was learned last time and corrected in the background; see refreshTools().
        Tools.State tools = lastKnownTools == null
                ? new Tools.State(false, false, false, "") : lastKnownTools;
        if (ready) refreshTools(tools);

        Capacity.Reading reading = Capacity.read(host);
        list.addView(Ui.row(host, dark, R.drawable.ic_memory, "What this computer is",
                Capacity.oneLine(reading) + " · " + Capacity.shortVerdict(reading),
                v -> Dialogs.message(host, "What this computer is",
                        Capacity.describe(host, reading))));
        list.addView(Ui.divider(host, dark, true));

        Ui.Row browser = Ui.row(host, dark, R.drawable.ic_globe, "Browser and screenshots",
                !ready ? "Available once Linux is set up"
                        : tools.browser
                            ? (tools.chromiumVersion.isEmpty() ? "Installed" : tools.chromiumVersion)
                            : "Not installed · about "
                                    + DeviceProbe.formatBytes(Tools.BROWSER_BYTES),
                v -> offerTools("browser", "Browser and screenshots",
                        "Installs Chromium inside Linux so an agent can open what it "
                                + "has built, screenshot it, and read the page back. There is no "
                                + "desktop and none is needed — it runs headless.\n\n"
                                + "It comes from the xtradeb package source rather than Ubuntu's "
                                + "own, because Ubuntu ships Chromium only as a snap and a snap "
                                + "cannot run in this kind of container at all.\n\n"
                                + "It runs without Chromium's own sandbox, which cannot work "
                                + "here. What contains it is Android: the whole Linux is "
                                + "this app's private storage, under this app's identity.",
                        Tools.BROWSER_BYTES, tools.browser));
        if (tools.browser) browser.setState(Ui.running(dark));
        list.addView(browser);
        list.addView(Ui.divider(host, dark, true));

        Ui.Row automation = Ui.row(host, dark, R.drawable.ic_camera, "Page testing and video",
                !tools.browser ? "Needs the browser first"
                        : tools.playwright ? "Installed · Playwright"
                            : "Not installed · about "
                                    + DeviceProbe.formatBytes(Tools.PLAYWRIGHT_BYTES),
                v -> {
                    if (!tools.browser) {
                        Dialogs.message(host, "The browser first",
                                "Page testing drives the browser, so install that first.");
                        return;
                    }
                    offerTools("playwright", "Page testing and video",
                            "Adds Playwright, so an agent can click through a page, assert what "
                                    + "it finds, and record a video of a run to show you.\n\n"
                                    + "The video is recorded with no display server at all — the "
                                    + "browser streams frames and Playwright's own encoder writes "
                                    + "them out.",
                            Tools.PLAYWRIGHT_BYTES, tools.playwright);
                });
        if (tools.playwright) automation.setState(Ui.running(dark));
        list.addView(automation);
        list.addView(Ui.divider(host, dark, true));

        Ui.Row android = Ui.row(host, dark, R.drawable.ic_apps, "Java toolchain (JDK)",
                !ready ? "Available once Linux is set up"
                        : tools.android ? "Installed · the first step towards Android builds"
                            : "Not installed · about "
                                    + DeviceProbe.formatBytes(Tools.ANDROID_BYTES),
                v -> offerTools("android", "Java toolchain (JDK)",
                        "Installs a JDK and writes Gradle settings sized to this phone. It is "
                                + "what Java and Kotlin need, and it is the first step towards "
                                + "building an Android app here.\n\n"
                                + "It is NOT a finished Android setup, and saying so here is "
                                + "the point — two pieces are still missing and neither is "
                                + "small:\n\n"
                                + "• The Android SDK. Google's command-line tools install it, "
                                + "and ANDROID_HOME has to point at it.\n"
                                + "• aarch64 builds of aapt2, aidl, zipalign and split-select. "
                                + "Google ships those four as x86-64 only, so a Gradle build "
                                + "stops on the first one with an Exec format error until they "
                                + "are replaced.\n\n"
                                + "Two limits are permanent whatever you install: apps "
                                + "containing C or C++ cannot be built, because Google "
                                + "publishes no arm64 NDK; and the Android emulator cannot run "
                                + "on this phone at all. The phone itself is the test device "
                                + "instead.\n\n"
                                + "Everything else on this screen — web, servers, "
                                + "command-line programs, and JVM tests — works with just this.",
                        Tools.ANDROID_BYTES, tools.android));
        if (tools.android) android.setState(Ui.running(dark));
        list.addView(android);
        list.addView(Ui.divider(host, dark, true));

        list.addView(Ui.row(host, dark, R.drawable.ic_info, "What can be built here",
                "Including the two things that cannot, and why",
                v -> Dialogs.message(host, "What can be built here", Tools.WHAT_CAN_BE_BUILT)));

        group.addView(list, Ui.wide(host, 8));
        group.addView(note(dark,
                "Each of these is a download into Linux, not part of the app. They can "
                        + "be removed from the editor's own terminal like any other package, and "
                        + "this screen will notice."), Ui.wide(host, 8));
        return group;
    }

    /**
     * Re-reads what is installed, off the drawing thread, and redraws only if it changed.
     *
     * "Only if it changed" matters: this is called from build(), and a redraw that always
     * followed would call build() again, which would call this again. Comparing first ends it
     * after one round.
     */
    private void refreshTools(final Tools.State drawn) {
        if (toolsCheckRunning) return;
        toolsCheckRunning = true;
        final Activity checking = host;
        new Thread(() -> {
            final Tools.State found = Tools.read(checking);
            checking.runOnUiThread(() -> {
                toolsCheckRunning = false;
                if (checking.isFinishing()) return;
                lastKnownTools = found;
                // Compared with what the rows were DRAWN from, not with whether anything was
                // known yet: a first opening with nothing installed used to count as a change
                // and build the whole screen twice.
                boolean changed = drawn.browser != found.browser
                        || drawn.playwright != found.playwright
                        || drawn.android != found.android;
                if (changed) MainActivity.rebuild(checking);
            });
        }, "check-tools").start();
    }

    /**
     * Asks first, then installs with its output on screen.
     *
     * The size is in the question rather than discovered afterwards, because on a phone the
     * difference between 60 MB and 340 MB is the difference between yes and not today.
     */
    private void offerTools(String layer, String title, String explanation, long bytes,
                            boolean already) {
        if (!Workspace.installed(host)) {
            Dialogs.message(host, title,
                    "Linux has to be set up before anything can be installed into it.");
            return;
        }
        if (already) {
            Dialogs.message(host, title, explanation
                    + "\n\nThis is already installed. To remove it, use the editor's terminal.");
            return;
        }
        Dialogs.confirm(host, title,
                explanation + "\n\nAbout " + DeviceProbe.formatBytes(bytes)
                        + " to download. It can be left running while you use the phone.",
                "Install", () -> {
                    Dialogs.Live live = Dialogs.live(host, title, "Installing…");
                    new Thread(() -> {
                        boolean ok = Tools.install(host, layer, live::line);
                        live.done(ok, ok ? "Installed." : "It did not finish. Nothing was left "
                                + "half-installed that the next attempt cannot repair.");
                        host.runOnUiThread(() -> {
                            if (!host.isFinishing()) MainActivity.rebuild(host);
                        });
                    }, "install-" + layer).start();
                });
    }

    // ------------------------------------------------------------------ permissions

    private View permissions(boolean dark) {
        LinearLayout group = group(dark, "Permissions and background");
        LinearLayout list = list(dark);

        boolean notifications = Permissions.notificationsAllowed(host);
        Ui.Row notifyRow = Ui.row(host, dark, R.drawable.ic_bell, "Notifications",
                notifications ? "Allowed" : "Off — progress and the Stop button cannot be shown",
                v -> Permissions.askNotifications(host, true));
        notifyRow.setState(notifications ? Ui.running(dark) : Ui.needsYou(dark));
        list.addView(notifyRow);
        list.addView(Ui.divider(host, dark, true));

        boolean battery = Permissions.batteryUnrestricted(host);
        Ui.Row batteryRow = Ui.row(host, dark, R.drawable.ic_battery, "Battery",
                battery ? "Unrestricted — long work will not be cut off"
                        : "Restricted — Android may stop a long set-up",
                v -> Permissions.openBatterySettings(host));
        batteryRow.setState(battery ? Ui.running(dark) : Ui.needsYou(dark));
        list.addView(batteryRow);
        list.addView(Ui.divider(host, dark, true));

        // The two switches no app can read. The rows say so, and then say where the switch is
        // in this phone's own menus -- which is the only honest thing a row can do about a
        // state it cannot see. A row that always said "CHECK" taught people to ignore it.
        list.addView(Ui.row(host, dark, R.drawable.ic_bolt, "Auto-launch",
                Permissions.CANNOT_READ + " · " + Permissions.autoLaunchPath(),
                v -> {
                    if (!Permissions.openAutoStartSettings(host)) {
                        Dialogs.message(host, "Auto-launch",
                                "This phone does not open its auto-launch page to other apps. "
                                        + "The switch is at:\n\n" + Permissions.autoLaunchPath()
                                        + "\n\nApp info opens next; Battery is usually the "
                                        + "way in from there.");
                        Permissions.openAppInfo(host);
                    }
                }));
        list.addView(Ui.divider(host, dark, true));

        list.addView(Ui.row(host, dark, R.drawable.ic_power, "Background activity",
                Permissions.CANNOT_READ + " · " + Permissions.backgroundPath(),
                v -> {
                    if (!Permissions.openBackgroundActivitySettings(host)) {
                        Dialogs.message(host, "Background activity",
                                "This phone does not open its battery page to other apps. The "
                                        + "switch is at:\n\n" + Permissions.backgroundPath()
                                        + "\n\nApp info opens next; Battery is usually the "
                                        + "way in from there.");
                        Permissions.openAppInfo(host);
                    }
                }));
        list.addView(Ui.divider(host, dark, true));

        list.addView(Ui.row(host, dark, R.drawable.ic_network, "Data Saver",
                "Android's own Data Saver can block this app's downloads on mobile data.",
                v -> Permissions.openDataSaverSettings(host)));
        list.addView(Ui.divider(host, dark, true));

        list.addView(Ui.row(host, dark, R.drawable.ic_info, "All app permissions",
                "The phone's own page for this app", v -> Permissions.openAppInfo(host)));

        group.addView(list, Ui.wide(host, 8));
        group.addView(note(dark,
                "Every one of these is optional and the app works without them — just less "
                        + "reliably. Nothing here is requested silently. Battery and "
                        + "notifications are read from the phone; auto-launch and background "
                        + "activity are the maker's own switches, which no app can read, so "
                        + "those two rows show the way to them instead."), Ui.wide(host, 8));
        return group;
    }

    // ------------------------------------------------------------------ network

    private View network(boolean dark) {
        LinearLayout group = group(dark, "Network and data");
        LinearLayout list = list(dark);

        long used = DataBudget.usedToday(host);
        dataRow = Ui.row(host, dark, R.drawable.ic_network, "Mobile data limit per day",
                DataBudget.label(host)
                        + (used >= 0 ? " · " + DeviceProbe.formatBytes(used) + " used today" : "")
                        + " · resets at midnight",
                v -> Dialogs.choose(host, "Mobile data limit per day", DataBudget.LABELS, null,
                        Math.max(0, indexOfInt(DataBudget.VALUES, DataBudget.capMb(host))),
                        index -> {
                            DataBudget.setCapMb(host, DataBudget.VALUES[index]);
                            MainActivity.rebuild(host);
                        }));
        list.addView(dataRow);
        list.addView(Ui.divider(host, dark, true));

        boolean wifiOnly = Prefs.of(host).getBoolean(Prefs.WIFI_ONLY, false);
        wifiRow = Ui.row(host, dark, R.drawable.ic_wifi, "Download on Wi-Fi only",
                wifiOnly ? "On — downloads wait for Wi-Fi" : "Off",
                v -> {
                    Prefs.of(host).edit().putBoolean(Prefs.WIFI_ONLY, !wifiOnly).apply();
                    MainActivity.rebuild(host);
                });
        if (wifiOnly) wifiRow.setState(Ui.accent(dark));
        list.addView(wifiRow);

        group.addView(list, Ui.wide(host, 8));
        group.addView(note(dark,
                "Wi-Fi is never counted against the limit. The figure comes from Android's own "
                        + "per-app counter and covers everything this app downloads, including "
                        + "what Linux fetches."), Ui.wide(host, 8));
        return group;
    }

    // ------------------------------------------------------------------ storage

    private View storage(boolean dark) {
        LinearLayout group = group(dark, "Storage");
        LinearLayout list = list(dark);

        sizeRow = Ui.row(host, dark, R.drawable.ic_storage, "Linux size",
                Workspace.installed(host) ? "Measuring…" : "Not set up yet", null);
        list.addView(sizeRow);
        if (Workspace.installed(host)) {
            final Ui.Row row = sizeRow;
            Workspace.size(host, bytes -> row.setValue(DeviceProbe.formatBytes(bytes)
                    + " · " + DeviceProbe.formatBytes(Workspace.freeBytes(host))
                    + " free on the phone"));
        }
        list.addView(Ui.divider(host, dark, true));

        list.addView(Ui.row(host, dark, R.drawable.ic_delete, "Remove everything",
                "Deletes Linux, the editor, the extensions and your projects",
                v -> confirmRemoveEverything()));

        group.addView(list, Ui.wide(host, 8));
        group.addView(note(dark,
                "Uninstalling the app does the same thing. Nothing is kept anywhere else, "
                        + "because nothing was ever anywhere else."), Ui.wide(host, 8));
        return group;
    }

    private void confirmRemoveEverything() {
        if (WorkspaceService.busy()) {
            Dialogs.message(host, "Still running",
                    "Stop Linux first: the Stop button on Activity, or the one on the "
                            + "notification.");
            return;
        }
        Dialogs.confirm(host, "Remove everything?",
                "This deletes Ubuntu, the editor, every extension and every project in the "
                        + "Linux. It cannot be undone, and setting up again downloads "
                        + "everything from the start.",
                "Remove everything", true, () -> {
                    // Off the drawing thread, with a dialog up while it runs. A Linux is tens
                    // of thousands of files, and deleting them on the thread that draws the
                    // screen froze it for as long as that took -- on a slow phone, long
                    // enough for Android to call the app unresponsive.
                    final Activity on = host;
                    final Dialogs.Live live = Dialogs.live(on, "Removing everything",
                            "Deleting Linux, the editor, the extensions and the projects…");
                    new Thread(() -> {
                        Workspace.removeEverything(on);
                        lastKnownTools = null;
                        live.done(true, "Removed. The app is back to how it was when it was "
                                + "installed.");
                        on.runOnUiThread(() -> {
                            if (!on.isFinishing()) MainActivity.rebuild(on);
                        });
                    }, "remove-everything").start();
                });
    }

    // ------------------------------------------------------------------ about

    private View about(boolean dark) {
        LinearLayout group = group(dark, "About");
        LinearLayout list = list(dark);
        list.addView(Ui.row(host, dark, R.drawable.ic_help, "About, FAQ and terms",
                "What this app is, and what it is not",
                v -> host.startActivity(new Intent(host, HelpActivity.class))));
        list.addView(Ui.divider(host, dark, true));
        list.addView(Ui.row(host, dark, R.drawable.ic_info, "Version",
                BuildFacts.VERSION_NAME + " (" + BuildFacts.VERSION_CODE + ") · "
                        + "Android " + Build.VERSION.RELEASE, null));
        if (Crash.exists(host)) {
            list.addView(Ui.divider(host, dark, true));
            Ui.Row crash = Ui.row(host, dark, R.drawable.ic_info, "Last unexpected stop",
                    "Tap to read or copy the record",
                    v -> Dialogs.details(host, "What was recorded",
                            "The app itself stopped. Nothing in Linux was lost.",
                            Crash.read(host), "Copy details"));
            crash.setState(Ui.needsYou(dark));
            list.addView(crash);
        }
        group.addView(list, Ui.wide(host, 8));
        return group;
    }

    // ------------------------------------------------------------------ staying current

    /**
     * Keeping the computer current, and saying plainly what moves on its own and what does not.
     *
     * The distinction is the whole of this group. Ubuntu's security fixes are taken
     * automatically because they are small, they are the ones that matter, and Ubuntu's own
     * maintainers have already decided they are safe on a stable release. The editor's version
     * and the extensions are not, because both change what the workspace looks like, and an
     * interface that rearranged itself overnight without being asked is not a kindness.
     */
    private View updates(boolean dark) {
        LinearLayout group = group(dark, "Staying current");
        LinearLayout list = list(dark);
        boolean ready = Workspace.installed(host);
        Updates.Status status = Updates.last(host);
        boolean on = Updates.automatic(host);
        boolean editorAuto = Updates.automaticEditor(host);

        Ui.Row automatic = Ui.row(host, dark, R.drawable.ic_shield, "Security updates",
                !ready ? "Available once Linux is set up"
                        : on ? "On · Ubuntu's security fixes, on Wi-Fi, once a day"
                             : "Off · nothing is updated unless you ask",
                v -> {
                    Updates.setAutomatic(host, !on);
                    MainActivity.rebuild(host);
                });
        automatic.setState(on ? Ui.running(dark) : Ui.needsYou(dark));
        list.addView(automatic);
        list.addView(Ui.divider(host, dark, true));

        Ui.Row editorSwitch = Ui.row(host, dark, R.drawable.ic_auto_mode, "Editor updates",
                !ready ? "Available once Linux is set up"
                        : !on ? "Off · automatic updates are off above"
                        : editorAuto
                            ? "Automatic · on Wi-Fi, while the editor is closed, with a rollback"
                            : "Only when you ask · from the row below",
                v -> {
                    Updates.setAutomaticEditor(host, !editorAuto);
                    MainActivity.rebuild(host);
                });
        editorSwitch.setState(on && editorAuto ? Ui.running(dark) : Ui.muted(dark));
        list.addView(editorSwitch);
        list.addView(Ui.divider(host, dark, true));

        Ui.Row state = Ui.row(host, dark, R.drawable.ic_download, "What is waiting",
                !ready ? "Nothing to check yet" : status.summary(),
                v -> { if (ready) checkNow(); });
        if (ready && status.anythingWaiting()) state.setState(Ui.needsYou(dark));
        list.addView(state);
        list.addView(Ui.divider(host, dark, true));

        list.addView(Ui.row(host, dark, R.drawable.ic_code, "The editor",
                !ready ? "Available once Linux is set up"
                        : status.editorCurrent.isEmpty()
                            ? "Version not read yet"
                            : status.editorOutOfDate()
                                ? status.editorCurrent + " · " + status.editorLatest + " available"
                                : status.editorCurrent + " · newest",
                v -> { if (ready) offerEditorUpdate(status); }));
        list.addView(Ui.divider(host, dark, true));

        list.addView(Ui.row(host, dark, R.drawable.ic_extension, "Extensions",
                !ready ? "Available once Linux is set up"
                        : "Updated by the editor while it is open · tap to do it now",
                v -> { if (ready) updateNow("extensions", "Extensions",
                        "Brings every installed extension up to the newest version Open VSX "
                                + "serves. The editor already does this by itself while it is "
                                + "open; this is for when it has not been opened in a while."); }));
        list.addView(Ui.divider(host, dark, true));

        list.addView(Ui.row(host, dark, R.drawable.ic_apps, "Everything, now",
                !ready ? "Available once Linux is set up"
                        : "Ubuntu, the editor and the extensions in one run",
                v -> { if (ready) updateNow("all", "Update everything",
                        "Runs all three in order: Ubuntu's security fixes, then the editor, then "
                                + "the extensions. It can take a while on a slow connection and "
                                + "can be left running while you use the phone.\n\n"
                                + "The editor has to be closed for its own update, and this will "
                                + "not start one while it is open."); }));

        list.addView(Ui.divider(host, dark, true));

        // The app itself: the one thing that used to have no idea it could be out of date.
        AppUpdates.Status app = AppUpdates.last(host);
        boolean looks = AppUpdates.enabled(host);
        Ui.Row self = Ui.row(host, dark, R.drawable.ic_download, "This app",
                app.newer()
                        ? "PocketIDE " + app.latest + " is available · tap to get it"
                                + (app.apkBytes > 0
                                        ? " · " + DeviceProbe.formatBytes(app.apkBytes) : "")
                        : !looks ? BuildFacts.VERSION_NAME + " · not looking for new versions"
                        : app.everChecked()
                            ? BuildFacts.VERSION_NAME + " · newest · looks once a day"
                            : BuildFacts.VERSION_NAME + " · not checked yet · tap to check",
                v -> {
                    if (app.newer()) offerAppUpdate(app);
                    else checkAppNow();
                });
        if (app.newer()) self.setState(Ui.needsYou(dark));
        list.addView(self);
        list.addView(Ui.divider(host, dark, true));

        Ui.Row looking = Ui.row(host, dark, R.drawable.ic_info, "Look for new versions",
                looks ? "On · asks GitHub once a day whether a newer PocketIDE exists"
                      : "Off · you will not be told about new versions",
                v -> {
                    AppUpdates.setEnabled(host, !looks);
                    MainActivity.rebuild(host);
                });
        looking.setState(looks ? Ui.running(dark) : Ui.muted(dark));
        list.addView(looking);

        group.addView(list, Ui.wide(host, 8));

        String note = "Ubuntu 24.04 LTS receives security updates until " + status.supportedUntil
                + " — that is Canonical's published date for this release, not an estimate. "
                + "Updates to Linux and the editor run only while PocketIDE is open, because "
                + "Linux only runs while PocketIDE is open: Android does not keep it alive "
                + "behind a closed app. A new version of the app itself is a download you "
                + "start; it installs over this one and touches nothing in Linux.";
        String ran = Updates.lastRunNote(host);
        if (!ran.isEmpty()) note = ran + ".\n\n" + note;
        group.addView(note(dark, note), Ui.wide(host, 8));
        return group;
    }

    private void checkAppNow() {
        Dialogs.Live live = Dialogs.live(host, "Checking for a new version", "Asking GitHub…");
        new Thread(() -> {
            AppUpdates.Status found = AppUpdates.check(host);
            live.done(found != null, found == null
                    ? "GitHub could not be reached."
                    : found.newer()
                        ? "PocketIDE " + found.latest + " is available."
                        : "This is the newest version.");
            host.runOnUiThread(() -> {
                if (!host.isFinishing()) MainActivity.rebuild(host);
            });
        }, "check-app-update").start();
    }

    /**
     * Hands the download to the phone's browser and Android's own installer.
     *
     * Every release is signed with the same key, so the new one installs over this one and
     * Linux, the editor and the projects are untouched -- they live in the app's storage, which
     * an update keeps. Said in the dialog, because "install a new version" sounds like the
     * thing that loses a workspace, and here it is not.
     */
    private void offerAppUpdate(AppUpdates.Status app) {
        String link = !app.apkUrl.isEmpty() ? app.apkUrl : app.pageUrl;
        if (link.isEmpty()) {
            Dialogs.message(host, "PocketIDE " + app.latest,
                    "A newer version was published but its download link could not be read. "
                            + "Tap \"Look for new versions\" again later.");
            return;
        }
        Dialogs.confirm(host, "Get PocketIDE " + app.latest + "?",
                "The download opens in the phone's browser"
                        + (app.apkBytes > 0
                                ? " (about " + DeviceProbe.formatBytes(app.apkBytes) + ")" : "")
                        + ". When it finishes, open the file and Android installs it over this "
                        + "version.\n\nLinux, the editor, the extensions and your projects "
                        + "stay exactly as they are: an update keeps the app's storage. Every "
                        + "release is signed with the same key, which is what lets it install "
                        + "over this one.",
                "Download", () -> {
                    try {
                        AppLock.expectReturn();
                        host.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Throwable noBrowser) {
                        AppLock.returned();
                        Dialogs.details(host, "No browser", "This phone has no browser to open "
                                + "the download with. The link is:", link, "Copy the link");
                    }
                });
    }

    private void checkNow() {
        if (Updates.busy()) {
            Dialogs.message(host, "Already checking",
                    "A check is running. It will finish on its own.");
            return;
        }
        Dialogs.Live live = Dialogs.live(host, "Checking for updates", "Asking Ubuntu and GitHub…");
        new Thread(() -> {
            Updates.Status found = Updates.check(host, live::line);
            live.done(found != null, found == null
                    ? "The workspace could not be reached."
                    : found.summary());
            host.runOnUiThread(() -> {
                if (!host.isFinishing()) MainActivity.rebuild(host);
            });
        }, "check-updates").start();
    }

    private void offerEditorUpdate(Updates.Status status) {
        if (editorInTheWay()) return;
        if (!status.editorOutOfDate()) {
            Dialogs.message(host, "The editor",
                    (status.editorCurrent.isEmpty()
                            ? "The installed version has not been read yet. "
                            : "Version " + status.editorCurrent + " is installed. ")
                            + "Tap \"What is waiting\" to check for a newer one.");
            return;
        }
        updateNow("editor", "Update the editor",
                "Replaces code-server " + status.editorCurrent + " with " + status.editorLatest
                        + ".\n\nAbout 224 MB to download, and roughly 1.2 GB of free space is "
                        + "needed while the new copy is unpacked beside the old one.\n\n"
                        + "The new copy has to unpack, contain a runnable editor, and report "
                        + "the version that was asked for before anything installed is touched. "
                        + "If the swap leaves anything unrunnable, the previous editor goes "
                        + "straight back. Your projects, settings and extensions are in your "
                        + "home folder and are not part of the replacement.");
    }

    /**
     * Refuses, out loud, anything that would replace the editor while it is running.
     *
     * "Everything, now" used to walk straight past this. Its own dialog said "the editor has to
     * be closed for its own update, and this will not start one while it is open" -- and
     * nothing enforced it, in the Java or in the script, so the one row most likely to be
     * tapped was the one that could pull the tree out from under a running editor and lose
     * whatever was unsaved in it. Both ends check now; this is the one that can explain itself.
     */
    private boolean editorInTheWay() {
        if (!WorkspaceService.editorRunning()) return false;
        Dialogs.message(host, "Close the editor first",
                "The editor cannot replace itself while it is running. Stop it from the "
                        + "Activity screen, then come back here.");
        return true;
    }

    private void updateNow(String what, String title, String explanation) {
        if (("editor".equals(what) || "all".equals(what)) && editorInTheWay()) return;
        Dialogs.confirm(host, title, explanation, "Update", () -> {
            if (("editor".equals(what) || "all".equals(what)) && editorInTheWay()) return;
            Dialogs.Live live = Dialogs.live(host, title, "Starting…");
            new Thread(() -> {
                boolean ok = Updates.run(host, what, live::line);
                live.done(ok, ok ? "Done." : "It did not finish. Nothing was left half-applied "
                        + "that the next attempt cannot repair.");
                Updates.check(host, line -> {});
                host.runOnUiThread(() -> {
                    if (!host.isFinishing()) MainActivity.rebuild(host);
                });
            }, "update-" + what).start();
        });
    }

    // ------------------------------------------------------------------ helpers

    private LinearLayout group(boolean dark, String label) {
        LinearLayout column = Ui.column(host);
        column.addView(Ui.sectionLabel(host, label, dark));
        return column;
    }

    private LinearLayout list(boolean dark) {
        LinearLayout list = Ui.column(host);
        list.setBackground(Ui.glass(host, dark, 20));
        return list;
    }

    private View note(boolean dark, String words) {
        android.widget.TextView view = Ui.text(host, words, 12f, Ui.muted(dark));
        view.setPadding(Ui.dp(host, 4), 0, Ui.dp(host, 4), 0);
        return view;
    }

    private void restartNeeded() {
        if (!WorkspaceService.editorRunning()) return;
        Dialogs.confirm(host, "Restart the editor?",
                "The new layout takes effect the next time the editor starts.",
                "Restart now", () -> WorkspaceService.stop(host));
    }

    private static int indexOf(String[] values, String current) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) return i;
        return 0;
    }

    private static int indexOfInt(int[] values, int current) {
        for (int i = 0; i < values.length; i++) if (values[i] == current) return i;
        return 0;
    }
}
