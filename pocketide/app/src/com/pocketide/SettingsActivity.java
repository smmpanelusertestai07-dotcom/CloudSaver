package com.pocketide;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
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
public final class SettingsActivity extends Activity {

    private Ui.Row themeRow;
    private Ui.Row layoutRow;
    private Ui.Row zoomRow;
    private Ui.Row dataRow;
    private Ui.Row wifiRow;
    private Ui.Row sizeRow;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Theme.apply(this);
        setContentView(build());
    }

    @Override protected void onResume() {
        super.onResume();
        // Permissions change while the owner is away in the phone's own Settings.
        setContentView(build());
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] granted) {
        super.onRequestPermissionsResult(code, permissions, granted);
        setContentView(build());
    }

    private View build() {
        boolean dark = Ui.dark(this);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.bg(dark));
        root.addView(Ui.topBar(this, dark, "Settings", v -> finish()));

        LinearLayout content = Ui.column(this);
        content.addView(appearance(dark));
        content.addView(permissions(dark), Ui.wide(this, 18));
        content.addView(network(dark), Ui.wide(this, 18));
        content.addView(storage(dark), Ui.wide(this, 18));
        content.addView(about(dark), Ui.wide(this, 18));

        ScrollView page = Ui.page(this, content, dark);
        root.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    // ------------------------------------------------------------------ appearance

    private View appearance(boolean dark) {
        LinearLayout group = group(dark, "Appearance");
        LinearLayout list = list(dark);

        themeRow = Ui.row(this, dark, R.drawable.ic_palette, "Theme", Theme.label(this),
                v -> Dialogs.choose(this, "Theme", Theme.LABELS, Theme.ICONS,
                        indexOf(Theme.VALUES, Theme.choice(this)), index -> {
                            Theme.set(this, Theme.VALUES[index]);
                            recreate();
                        }));
        list.addView(themeRow);
        list.addView(Ui.divider(this, dark, true));

        String layout = Prefs.of(this).getString(Prefs.EDITOR_LAYOUT, "phone");
        layoutRow = Ui.row(this, dark, R.drawable.ic_phone, "Editor layout",
                "phone".equals(layout)
                        ? "Phone · agents in a bottom bar, chrome hidden"
                        : "Desktop · the full Visual Studio Code layout",
                v -> Dialogs.choose(this, "Editor layout",
                        new String[]{"Phone · recommended", "Desktop · everything shown"},
                        new int[]{R.drawable.ic_phone, R.drawable.ic_desktop},
                        "phone".equals(layout) ? 0 : 1, index -> {
                            Prefs.of(this).edit()
                                    .putString(Prefs.EDITOR_LAYOUT, index == 0 ? "phone" : "desktop")
                                    .apply();
                            setContentView(build());
                            restartNeeded();
                        }));
        list.addView(layoutRow);
        list.addView(Ui.divider(this, dark, true));

        int zoom = Prefs.of(this).getInt(Prefs.EDITOR_ZOOM, 15);
        String[] zoomLabels = {"Smaller", "Normal", "Larger · recommended", "Largest"};
        int[] zoomValues = {10, 12, 15, 18};
        zoomRow = Ui.row(this, dark, R.drawable.ic_fit, "Editor size",
                zoomLabels[Math.max(0, indexOfInt(zoomValues, zoom))],
                v -> Dialogs.choose(this, "Editor size", zoomLabels, null,
                        Math.max(0, indexOfInt(zoomValues, zoom)), index -> {
                            Prefs.of(this).edit()
                                    .putInt(Prefs.EDITOR_ZOOM, zoomValues[index]).apply();
                            setContentView(build());
                            restartNeeded();
                        }));
        list.addView(zoomRow);

        group.addView(list, Ui.wide(this, 8));
        group.addView(note(dark,
                "Phone layout uses Visual Studio Code's own settings to move the agents into a "
                        + "bottom bar and hide the desktop chrome. Nothing is removed — Desktop "
                        + "brings all of it back."), Ui.wide(this, 8));
        return group;
    }

    // ------------------------------------------------------------------ permissions

    private View permissions(boolean dark) {
        LinearLayout group = group(dark, "Permissions and background");
        LinearLayout list = list(dark);

        boolean notifications = Permissions.notificationsAllowed(this);
        Ui.Row notifyRow = Ui.row(this, dark, R.drawable.ic_notification, "Notifications",
                notifications ? "Allowed" : "Off — progress and the Stop button cannot be shown",
                v -> Permissions.askNotifications(this, true));
        notifyRow.setState(notifications ? Ui.RUNNING : Ui.NEEDS_YOU);
        list.addView(notifyRow);
        list.addView(Ui.divider(this, dark, true));

        boolean battery = Permissions.batteryUnrestricted(this);
        Ui.Row batteryRow = Ui.row(this, dark, R.drawable.ic_battery, "Battery",
                battery ? "Unrestricted — long work will not be cut off"
                        : "Restricted — Android may stop a long set-up",
                v -> Permissions.openBatterySettings(this));
        batteryRow.setState(battery ? Ui.RUNNING : Ui.NEEDS_YOU);
        list.addView(batteryRow);
        list.addView(Ui.divider(this, dark, true));

        list.addView(Ui.row(this, dark, R.drawable.ic_bolt, "Auto-launch",
                "Realme, OPPO, Xiaomi, vivo, OnePlus, Huawei and Samsung keep this in their own "
                        + "security app. Tap to open it.",
                v -> {
                    if (!Permissions.openAutoStartSettings(this)) {
                        Dialogs.message(this, "Auto-launch",
                                "This phone does not open its auto-launch page to other apps. "
                                        + "Open App info, then Battery, and turn on Allow "
                                        + "auto-launch.");
                        Permissions.openAppInfo(this);
                    }
                }));
        list.addView(Ui.divider(this, dark, true));

        list.addView(Ui.row(this, dark, R.drawable.ic_power, "Background activity",
                "On Realme and OPPO this is a separate switch from battery optimisation, and it "
                        + "is the one that ends a long download.",
                v -> {
                    if (!Permissions.openBackgroundActivitySettings(this)) {
                        Dialogs.message(this, "Background activity",
                                "Open Battery usage on the next page and turn on foreground and "
                                        + "background activity.");
                        Permissions.openAppInfo(this);
                    }
                }));
        list.addView(Ui.divider(this, dark, true));

        list.addView(Ui.row(this, dark, R.drawable.ic_network, "Data Saver",
                "Android's own Data Saver can block this app's downloads on mobile data.",
                v -> Permissions.openDataSaverSettings(this)));
        list.addView(Ui.divider(this, dark, true));

        list.addView(Ui.row(this, dark, R.drawable.ic_info, "All app permissions",
                "The phone's own page for this app", v -> Permissions.openAppInfo(this)));

        group.addView(list, Ui.wide(this, 8));
        group.addView(note(dark,
                "Every one of these is optional and the app works without them — just less "
                        + "reliably. Nothing here is requested silently."), Ui.wide(this, 8));
        return group;
    }

    // ------------------------------------------------------------------ network

    private View network(boolean dark) {
        LinearLayout group = group(dark, "Network and data");
        LinearLayout list = list(dark);

        long used = DataBudget.usedToday(this);
        dataRow = Ui.row(this, dark, R.drawable.ic_network, "Mobile data limit per day",
                DataBudget.label(this)
                        + (used >= 0 ? " · " + DeviceProbe.formatBytes(used) + " used today" : "")
                        + " · resets at midnight",
                v -> Dialogs.choose(this, "Mobile data limit per day", DataBudget.LABELS, null,
                        Math.max(0, indexOfInt(DataBudget.VALUES, DataBudget.capMb(this))),
                        index -> {
                            DataBudget.setCapMb(this, DataBudget.VALUES[index]);
                            setContentView(build());
                        }));
        list.addView(dataRow);
        list.addView(Ui.divider(this, dark, true));

        boolean wifiOnly = Prefs.of(this).getBoolean(Prefs.WIFI_ONLY, false);
        wifiRow = Ui.row(this, dark, R.drawable.ic_wifi, "Download on Wi-Fi only",
                wifiOnly ? "On — downloads wait for Wi-Fi" : "Off",
                v -> {
                    Prefs.of(this).edit().putBoolean(Prefs.WIFI_ONLY, !wifiOnly).apply();
                    setContentView(build());
                });
        if (wifiOnly) wifiRow.setState(Ui.accent(dark));
        list.addView(wifiRow);

        group.addView(list, Ui.wide(this, 8));
        group.addView(note(dark,
                "Wi-Fi is never counted against the limit. The figure comes from Android's own "
                        + "per-app counter and covers everything this app downloads, including "
                        + "what the workspace fetches."), Ui.wide(this, 8));
        return group;
    }

    // ------------------------------------------------------------------ storage

    private View storage(boolean dark) {
        LinearLayout group = group(dark, "Storage");
        LinearLayout list = list(dark);

        sizeRow = Ui.row(this, dark, R.drawable.ic_storage, "Workspace size",
                Workspace.installed(this) ? "Measuring…" : "Not set up yet", null);
        list.addView(sizeRow);
        if (Workspace.installed(this)) {
            new Thread(() -> {
                long bytes = Workspace.sizeBytes(this);
                runOnUiThread(() -> {
                    if (!isFinishing() && sizeRow != null) {
                        sizeRow.setValue(DeviceProbe.formatBytes(bytes)
                                + " · " + DeviceProbe.formatBytes(Workspace.freeBytes(this))
                                + " free on the phone");
                    }
                });
            }, "measure").start();
        }
        list.addView(Ui.divider(this, dark, true));

        list.addView(Ui.row(this, dark, R.drawable.ic_delete, "Remove everything",
                "Deletes Linux, the editor, the extensions and your projects",
                v -> confirmRemoveEverything()));

        group.addView(list, Ui.wide(this, 8));
        group.addView(note(dark,
                "Uninstalling the app does the same thing. Nothing is kept anywhere else, "
                        + "because nothing was ever anywhere else."), Ui.wide(this, 8));
        return group;
    }

    private void confirmRemoveEverything() {
        if (WorkspaceService.busy()) {
            Dialogs.message(this, "Still running",
                    "Stop the workspace from the notification first.");
            return;
        }
        Dialogs.confirm(this, "Remove everything?",
                "This deletes Ubuntu, the editor, every extension and every project in the "
                        + "workspace. It cannot be undone, and setting up again downloads "
                        + "everything from the start.",
                "Remove everything", true, () -> {
                    Workspace.removeEverything(this);
                    Dialogs.message(this, "Removed", "The workspace is gone. The app is back to "
                            + "how it was when it was installed.");
                    setContentView(build());
                });
    }

    // ------------------------------------------------------------------ about

    private View about(boolean dark) {
        LinearLayout group = group(dark, "About");
        LinearLayout list = list(dark);
        list.addView(Ui.row(this, dark, R.drawable.ic_help, "About, FAQ and terms",
                "What this app is, and what it is not",
                v -> startActivity(new Intent(this, HelpActivity.class))));
        list.addView(Ui.divider(this, dark, true));
        list.addView(Ui.row(this, dark, R.drawable.ic_info, "Version",
                BuildFacts.VERSION_NAME + " (" + BuildFacts.VERSION_CODE + ") · "
                        + "Android " + Build.VERSION.RELEASE, null));
        if (Crash.exists(this)) {
            list.addView(Ui.divider(this, dark, true));
            Ui.Row crash = Ui.row(this, dark, R.drawable.ic_info, "Last unexpected stop",
                    "Tap to read or copy the record",
                    v -> Dialogs.details(this, "What was recorded",
                            "The app itself stopped. Nothing in the workspace was lost.",
                            Crash.read(this), "Copy details"));
            crash.setState(Ui.NEEDS_YOU);
            list.addView(crash);
        }
        group.addView(list, Ui.wide(this, 8));
        return group;
    }

    // ------------------------------------------------------------------ helpers

    private LinearLayout group(boolean dark, String label) {
        LinearLayout column = Ui.column(this);
        column.addView(Ui.sectionLabel(this, label, dark));
        return column;
    }

    private LinearLayout list(boolean dark) {
        LinearLayout list = Ui.column(this);
        list.setBackground(Ui.glass(this, dark, 20));
        return list;
    }

    private View note(boolean dark, String words) {
        android.widget.TextView view = Ui.text(this, words, 12f, Ui.muted(dark));
        view.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        return view;
    }

    private void restartNeeded() {
        if (!WorkspaceService.editorRunning()) return;
        Dialogs.confirm(this, "Restart the editor?",
                "The new layout takes effect the next time the editor starts.",
                "Restart now", () -> WorkspaceService.stop(this));
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
