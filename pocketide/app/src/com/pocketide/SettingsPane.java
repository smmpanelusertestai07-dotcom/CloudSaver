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
        LinearLayout root = Ui.column(host);
        root.setBackgroundColor(Ui.bg(dark));

        LinearLayout content = Ui.column(host);
        content.addView(appearance(dark));
        content.addView(safety(dark), Ui.wide(host, 18));
        content.addView(permissions(dark), Ui.wide(host, 18));
        content.addView(network(dark), Ui.wide(host, 18));
        content.addView(storage(dark), Ui.wide(host, 18));
        content.addView(about(dark), Ui.wide(host, 18));

        ScrollView page = Ui.page(host, content, dark);
        root.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
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
        if (on) lock.setState(Ui.RUNNING);
        else if (canLock) lock.setState(Ui.NEEDS_YOU);
        lock.setEnabled(canLock);
        lock.setAlpha(canLock ? 1f : 0.55f);
        list.addView(lock);

        list.addView(Ui.divider(host, dark, true));

        boolean files = PhoneFiles.enabled(host);
        Ui.Row phone = Ui.row(host, dark, R.drawable.ic_phone, "The phone's files",
                PhoneFiles.state(host), v -> togglePhoneFiles(files));
        if (files) phone.setState(Ui.RUNNING);
        list.addView(phone);

        group.addView(list, Ui.wide(host, 8));

        // Shown once, after the lock turned itself off because the phone's own screen lock was
        // removed. Silently disabling it would leave an owner believing they were protected.
        if (Prefs.of(host).getBoolean(Prefs.LOCK_NOTICE, false)) {
            group.addView(note(dark,
                    "The app lock was turned off because this phone no longer has a screen "
                            + "lock. Set one in the phone's own Settings and you can turn it "
                            + "back on here."), Ui.wide(host, 10));
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
            Dialogs.confirm(host, "Stop the workspace seeing the phone's files?",
                    "~/phone disappears from inside the workspace the next time it starts. "
                            + "Nothing on the phone is deleted, and nothing already copied into "
                            + "the workspace is affected.\n\nAndroid's own permission stays "
                            + "granted until you remove it in the phone's Settings.",
                    "Turn off", () -> {
                        Prefs.of(host).edit().putBoolean(Prefs.PHONE_FILES, false).apply();
                        MainActivity.rebuild(host);
                        restartNeeded();
                    });
            return;
        }
        Dialogs.confirm(host, "Let the workspace see the phone's files?",
                "The phone's storage -- Download, DCIM, Documents and the rest -- appears "
                        + "inside the workspace as ~/phone. An agent working in the editor can "
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
                            host.recreate();
                        }));
        list.addView(themeRow);
        list.addView(Ui.divider(host, dark, true));

        String layout = Prefs.of(host).getString(Prefs.EDITOR_LAYOUT, "phone");
        layoutRow = Ui.row(host, dark, R.drawable.ic_phone, "Editor layout",
                "phone".equals(layout)
                        ? "Phone · agents in a bottom bar, chrome hidden"
                        : "Desktop · the full Visual Studio Code layout",
                v -> Dialogs.choose(host, "Editor layout",
                        new String[]{"Phone · recommended", "Desktop · everything shown"},
                        new int[]{R.drawable.ic_phone, R.drawable.ic_desktop},
                        "phone".equals(layout) ? 0 : 1, index -> {
                            Prefs.of(host).edit()
                                    .putString(Prefs.EDITOR_LAYOUT, index == 0 ? "phone" : "desktop")
                                    .apply();
                            MainActivity.rebuild(host);
                            restartNeeded();
                        }));
        list.addView(layoutRow);
        list.addView(Ui.divider(host, dark, true));

        int zoom = Prefs.of(host).getInt(Prefs.EDITOR_ZOOM, 15);
        String[] zoomLabels = {"Smaller", "Normal", "Larger · recommended", "Largest"};
        int[] zoomValues = {10, 12, 15, 18};
        zoomRow = Ui.row(host, dark, R.drawable.ic_fit, "Editor size",
                zoomLabels[Math.max(0, indexOfInt(zoomValues, zoom))],
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
                "Phone layout uses Visual Studio Code's own settings to move the agents into a "
                        + "bottom bar and hide the desktop chrome. Nothing is removed — Desktop "
                        + "brings all of it back."), Ui.wide(host, 8));
        return group;
    }

    // ------------------------------------------------------------------ permissions

    private View permissions(boolean dark) {
        LinearLayout group = group(dark, "Permissions and background");
        LinearLayout list = list(dark);

        boolean notifications = Permissions.notificationsAllowed(host);
        Ui.Row notifyRow = Ui.row(host, dark, R.drawable.ic_notification, "Notifications",
                notifications ? "Allowed" : "Off — progress and the Stop button cannot be shown",
                v -> Permissions.askNotifications(host, true));
        notifyRow.setState(notifications ? Ui.RUNNING : Ui.NEEDS_YOU);
        list.addView(notifyRow);
        list.addView(Ui.divider(host, dark, true));

        boolean battery = Permissions.batteryUnrestricted(host);
        Ui.Row batteryRow = Ui.row(host, dark, R.drawable.ic_battery, "Battery",
                battery ? "Unrestricted — long work will not be cut off"
                        : "Restricted — Android may stop a long set-up",
                v -> Permissions.openBatterySettings(host));
        batteryRow.setState(battery ? Ui.RUNNING : Ui.NEEDS_YOU);
        list.addView(batteryRow);
        list.addView(Ui.divider(host, dark, true));

        list.addView(Ui.row(host, dark, R.drawable.ic_bolt, "Auto-launch",
                "Realme, OPPO, Xiaomi, vivo, OnePlus, Huawei and Samsung keep this in their own "
                        + "security app. Tap to open it.",
                v -> {
                    if (!Permissions.openAutoStartSettings(host)) {
                        Dialogs.message(host, "Auto-launch",
                                "This phone does not open its auto-launch page to other apps. "
                                        + "Open App info, then Battery, and turn on Allow "
                                        + "auto-launch.");
                        Permissions.openAppInfo(host);
                    }
                }));
        list.addView(Ui.divider(host, dark, true));

        list.addView(Ui.row(host, dark, R.drawable.ic_power, "Background activity",
                "On Realme and OPPO this is a separate switch from battery optimisation, and it "
                        + "is the one that ends a long download.",
                v -> {
                    if (!Permissions.openBackgroundActivitySettings(host)) {
                        Dialogs.message(host, "Background activity",
                                "Open Battery usage on the next page and turn on foreground and "
                                        + "background activity.");
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
                        + "reliably. Nothing here is requested silently."), Ui.wide(host, 8));
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
                        + "what the workspace fetches."), Ui.wide(host, 8));
        return group;
    }

    // ------------------------------------------------------------------ storage

    private View storage(boolean dark) {
        LinearLayout group = group(dark, "Storage");
        LinearLayout list = list(dark);

        sizeRow = Ui.row(host, dark, R.drawable.ic_storage, "Workspace size",
                Workspace.installed(host) ? "Measuring…" : "Not set up yet", null);
        list.addView(sizeRow);
        if (Workspace.installed(host)) {
            new Thread(() -> {
                long bytes = Workspace.sizeBytes(host);
                host.runOnUiThread(() -> {
                    if (!host.isFinishing() && sizeRow != null) {
                        sizeRow.setValue(DeviceProbe.formatBytes(bytes)
                                + " · " + DeviceProbe.formatBytes(Workspace.freeBytes(host))
                                + " free on the phone");
                    }
                });
            }, "measure").start();
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
                    "Stop the workspace from the notification first.");
            return;
        }
        Dialogs.confirm(host, "Remove everything?",
                "This deletes Ubuntu, the editor, every extension and every project in the "
                        + "workspace. It cannot be undone, and setting up again downloads "
                        + "everything from the start.",
                "Remove everything", true, () -> {
                    Workspace.removeEverything(host);
                    Dialogs.message(host, "Removed", "The workspace is gone. The app is back to "
                            + "how it was when it was installed.");
                    MainActivity.rebuild(host);
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
                            "The app itself stopped. Nothing in the workspace was lost.",
                            Crash.read(host), "Copy details"));
            crash.setState(Ui.NEEDS_YOU);
            list.addView(crash);
        }
        group.addView(list, Ui.wide(host, 8));
        return group;
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
