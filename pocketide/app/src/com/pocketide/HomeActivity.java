package com.pocketide;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The first screen: what this is, whether it is ready, and the one thing to do next.
 *
 * The whole screen answers three questions in order, because those are the three an owner
 * actually has: is it set up, what is on it, and is the phone in a state to run it. Everything
 * else -- extensions, settings, help -- is a row at the bottom, because it is not what anyone
 * opens the app for.
 */
public final class HomeActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver events;

    private TextView statePill;
    private TextView stateLine;
    private TextView action;
    private LinearLayout agentList;
    private LinearLayout attention;
    private LinearLayout healthList;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshEverything();
            handler.postDelayed(this, 5000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Theme.apply(this);
        setContentView(build());
        listen();
    }

    @Override protected void onResume() {
        super.onResume();
        // Permissions and free space change while the owner is away in the phone's own
        // Settings, so the screen is rebuilt on return rather than left showing what was true
        // when it was opened.
        setContentView(build());
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (events != null) {
            unregisterReceiver(events);
            events = null;
        }
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] granted) {
        super.onRequestPermissionsResult(code, permissions, granted);
        setContentView(build());
    }

    // ------------------------------------------------------------------ the screen

    private View build() {
        boolean dark = Ui.dark(this);
        LinearLayout content = Ui.column(this);

        content.addView(hero(dark));
        content.addView(agentsCard(dark), Ui.wide(this, 14));
        attention = attentionCard(dark);
        content.addView(attention, Ui.wide(this, 14));
        content.addView(healthCard(dark), Ui.wide(this, 14));
        content.addView(moreCard(dark), Ui.wide(this, 14));
        content.addView(footer(dark), Ui.wide(this, 18));

        refreshEverything();
        return Ui.page(this, content, dark);
    }

    /** The one card that carries the brand, and the only place the violet appears full width. */
    private View hero(boolean dark) {
        LinearLayout card = Ui.column(this);
        int pad = Ui.dp(this, 20);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(Ui.metal(this, 24));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.ImageView mark = new android.widget.ImageView(this);
        mark.setImageResource(R.mipmap.ic_launcher_foreground);
        int markSize = Ui.dp(this, 52);
        topRow.addView(mark, new LinearLayout.LayoutParams(markSize, markSize));
        LinearLayout words = Ui.column(this);
        words.addView(Ui.bold(this, getString(R.string.app_name), 24, Brand.ON_BRAND));
        statePill = Ui.pill(this, "CHECKING", Brand.ON_BRAND_MUTED);
        LinearLayout pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pillRow.addView(statePill);
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillParams.topMargin = Ui.dp(this, 5);
        words.addView(pillRow, pillParams);
        LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wordParams.leftMargin = Ui.dp(this, 10);
        topRow.addView(words, wordParams);
        card.addView(topRow);

        card.addView(Ui.text(this, getString(R.string.tagline), 15f, Brand.ON_BRAND),
                Ui.wide(this, 14));
        card.addView(Ui.text(this,
                "Ubuntu 24.04 LTS · Visual Studio Code · any coding agent · no computer required",
                12.5f, Brand.ON_BRAND_MUTED), Ui.wide(this, 6));

        stateLine = Ui.text(this, "", 13f, Brand.ON_BRAND_MUTED);
        card.addView(stateLine, Ui.wide(this, 10));

        action = Ui.bold(this, "Set up", 16, Brand.TILE_BOTTOM);
        action.setGravity(android.view.Gravity.CENTER);
        int actionPad = Ui.dp(this, 15);
        action.setPadding(actionPad, actionPad, actionPad, actionPad);
        action.setMinHeight(Ui.dp(this, Ui.TOUCH_TARGET_DP));
        action.setBackground(Ui.tappable(this, Ui.fill(this, Brand.MARK, 14), false));
        action.setClickable(true);
        action.setFocusable(true);
        action.setOnClickListener(v -> onPrimaryAction());
        card.addView(action, Ui.wide(this, 16));
        return card;
    }

    private View agentsCard(boolean dark) {
        LinearLayout column = Ui.column(this);
        column.addView(Ui.sectionLabel(this, "Coding agents", dark));
        agentList = Ui.column(this);
        agentList.setBackground(Ui.glass(this, dark, 20));
        column.addView(agentList, Ui.wide(this, 8));
        return column;
    }

    /** Only present when something actually needs the owner. Empty means hidden, not empty. */
    private LinearLayout attentionCard(boolean dark) {
        LinearLayout column = Ui.column(this);
        column.setVisibility(View.GONE);
        return column;
    }

    private View healthCard(boolean dark) {
        LinearLayout column = Ui.column(this);
        column.addView(Ui.sectionLabel(this, "This phone", dark));
        healthList = Ui.column(this);
        healthList.setBackground(Ui.glass(this, dark, 20));
        column.addView(healthList, Ui.wide(this, 8));
        return column;
    }

    private View moreCard(boolean dark) {
        LinearLayout list = Ui.column(this);
        list.setBackground(Ui.glass(this, dark, 20));
        list.addView(Ui.row(this, dark, R.drawable.ic_apps, "Extensions",
                "Add any agent or tool from Open VSX",
                v -> startActivity(new Intent(this, ExtensionsActivity.class))));
        list.addView(Ui.divider(this, dark, true));
        list.addView(Ui.row(this, dark, R.drawable.ic_settings, "Settings",
                "Appearance, permissions, data, storage",
                v -> startActivity(new Intent(this, SettingsActivity.class))));
        list.addView(Ui.divider(this, dark, true));
        list.addView(Ui.row(this, dark, R.drawable.ic_help, "About, FAQ and terms",
                "What this app is, and what it is not",
                v -> startActivity(new Intent(this, HelpActivity.class))));
        return list;
    }

    private View footer(boolean dark) {
        TextView line = Ui.text(this, getString(R.string.app_name) + " "
                + BuildFacts.VERSION_NAME + " · everything runs on this phone", 12f, Ui.muted(dark));
        line.setGravity(android.view.Gravity.CENTER);
        return line;
    }

    // ------------------------------------------------------------------ live state

    private void refreshEverything() {
        if (isFinishing()) return;
        boolean dark = Ui.dark(this);
        boolean installed = Workspace.installed(this);
        boolean running = WorkspaceService.editorRunning();
        boolean busy = WorkspaceService.busy();

        if (statePill != null) {
            if (running) {
                statePill.setText("RUNNING");
                statePill.setTextColor(Brand.ON_BRAND);
            } else if (busy) {
                statePill.setText("WORKING");
                statePill.setTextColor(Brand.ON_BRAND);
            } else if (installed) {
                statePill.setText("READY");
                statePill.setTextColor(Brand.ON_BRAND);
            } else {
                statePill.setText("NOT SET UP");
                statePill.setTextColor(Brand.ON_BRAND_MUTED);
            }
        }

        if (stateLine != null) {
            if (running) {
                stateLine.setText("The editor is running. Tap to open it.");
            } else if (busy) {
                stateLine.setText("Working. Tap to watch what it is doing.");
            } else if (installed) {
                long took = Prefs.of(this).getLong(Prefs.SETUP_ELAPSED_MS, 0);
                stateLine.setText(took > 0
                        ? "Set up in " + Stage.clock(took) + ". Ready when you are."
                        : "Ready when you are.");
            } else {
                String refusal = DeviceCheck.refusal(this);
                stateLine.setText(refusal != null ? refusal
                        : "First set-up downloads about "
                                + DeviceProbe.formatBytes(BuildFacts.BASE_DOWNLOAD_BYTES)
                                + " and takes 20–40 minutes. It can be paused and resumed.");
            }
        }

        if (action != null) {
            action.setText(running ? "Open the editor"
                    : busy ? "See what it is doing"
                    : installed ? "Open the editor" : "Set up");
            action.setEnabled(installed || busy || DeviceCheck.ready(this));
            action.setAlpha(action.isEnabled() ? 1f : 0.55f);
        }

        refreshAgents(dark, installed);
        refreshAttention(dark);
        refreshHealth(dark);
    }

    private void refreshAgents(boolean dark, boolean installed) {
        if (agentList == null) return;
        agentList.removeAllViews();
        List<String> present = Registry.installed(this);
        boolean first = true;
        for (Agents.Agent agent : Agents.ALL) {
            boolean here = present.contains(agent.id);
            String value = here ? "Installed · open it from the editor's side panel"
                    : agent.plan;
            if (!first) agentList.addView(Ui.divider(this, dark, true));
            first = false;
            Ui.Row row = Ui.row(this, dark,
                    here ? R.drawable.ic_check : R.drawable.ic_install,
                    agent.name + " · " + agent.publisher, value,
                    v -> startActivity(new Intent(this, ExtensionsActivity.class)));
            if (here) row.setState(Ui.RUNNING);
            else if (agent.free) row.setState(Ui.accent(dark));
            agentList.addView(row);
        }
        int others = Math.max(0, present.size() - countRecommendedInstalled(present));
        agentList.addView(Ui.divider(this, dark, true));
        agentList.addView(Ui.row(this, dark, R.drawable.ic_apps,
                others > 0 ? others + " other extension" + (others == 1 ? "" : "s")
                        : "Browse all extensions",
                installed ? "Open VSX · verified publishers by default"
                        : "Available once set-up has finished",
                v -> startActivity(new Intent(this, ExtensionsActivity.class))));
    }

    private int countRecommendedInstalled(List<String> present) {
        int count = 0;
        for (Agents.Agent agent : Agents.ALL) if (present.contains(agent.id)) count++;
        return count;
    }

    private void refreshAttention(boolean dark) {
        if (attention == null) return;
        attention.removeAllViews();
        LinearLayout list = Ui.column(this);
        list.setBackground(Ui.glass(this, dark, 20));
        boolean any = false;

        if (!Permissions.notificationsAllowed(this)) {
            list.addView(Ui.row(this, dark, R.drawable.ic_notification,
                    "Notifications are off",
                    "Set-up progress and the Stop button cannot be shown. Tap to allow.",
                    v -> Permissions.askNotifications(this, true)));
            any = true;
        }
        if (!Permissions.batteryUnrestricted(this)) {
            if (any) list.addView(Ui.divider(this, dark, true));
            list.addView(Ui.row(this, dark, R.drawable.ic_battery,
                    "Battery may interrupt a long set-up",
                    "Android can stop a 20-minute download when the screen goes off. Tap to allow.",
                    v -> Permissions.openBatterySettings(this)));
            any = true;
        }
        String blocked = DataBudget.whyBlocked(this);
        if (!blocked.isEmpty()) {
            if (any) list.addView(Ui.divider(this, dark, true));
            list.addView(Ui.row(this, dark, R.drawable.ic_network, "Downloads are waiting",
                    blocked, v -> startActivity(new Intent(this, SettingsActivity.class))));
            any = true;
        }
        if (Crash.exists(this)) {
            if (any) list.addView(Ui.divider(this, dark, true));
            list.addView(Ui.row(this, dark, R.drawable.ic_info, "The app stopped unexpectedly",
                    "Nothing in the workspace was lost. Tap to see the record.",
                    v -> Dialogs.details(this, "What was recorded",
                            "The app itself stopped. The workspace and its files live in their "
                                    + "own storage, so nothing in them was lost.",
                            Crash.read(this), "Copy details")));
            any = true;
        }

        if (!any) {
            attention.setVisibility(View.GONE);
            return;
        }
        attention.setVisibility(View.VISIBLE);
        attention.addView(Ui.sectionLabel(this, "Needs you", dark));
        attention.addView(list, Ui.wide(this, 8));
    }

    private void refreshHealth(boolean dark) {
        if (healthList == null) return;
        healthList.removeAllViews();
        DeviceProbe probe = DeviceProbe.read(this);

        healthList.addView(Ui.row(this, dark, R.drawable.ic_phone, probe.model,
                probe.androidVersion + " · " + probe.abi + " · "
                        + DeviceProbe.formatBytes(probe.totalRam) + " RAM", null));
        healthList.addView(Ui.divider(this, dark, true));

        Ui.Row space = Ui.row(this, dark, R.drawable.ic_storage, "Free space",
                DeviceProbe.formatBytes(probe.freeStorage), null);
        if (probe.freeStorage < DeviceCheck.NEEDED_BYTES && !Workspace.installed(this)) {
            space.setState(Ui.NEEDS_YOU);
            space.setValue(DeviceProbe.formatBytes(probe.freeStorage) + " · about "
                    + DeviceProbe.formatBytes(DeviceCheck.NEEDED_BYTES) + " needed");
        }
        healthList.addView(space);
        healthList.addView(Ui.divider(this, dark, true));

        Ui.Row network = Ui.row(this, dark,
                DeviceProbe.isWifi(this) ? R.drawable.ic_wifi : R.drawable.ic_network,
                "Network", probe.network + dataSuffix(), null);
        if ("Offline".equals(probe.network)) network.setState(Ui.NEEDS_YOU);
        healthList.addView(network);
        healthList.addView(Ui.divider(this, dark, true));

        Ui.Row heat = Ui.row(this, dark, R.drawable.ic_temperature, "Battery",
                (probe.batteryPercent >= 0 ? probe.batteryPercent + "%" : "Unknown")
                        + (probe.batteryTempC > 0
                                ? " · " + Math.round(probe.batteryTempC) + " °C" : "")
                        + " · " + DeviceProbe.thermalName(probe.thermalStatus), null);
        if (probe.thermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE) {
            heat.setState(Ui.NEEDS_YOU);
        }
        healthList.addView(heat);

        if (Workspace.installed(this)) {
            healthList.addView(Ui.divider(this, dark, true));
            Ui.Row size = Ui.row(this, dark, R.drawable.ic_memory, "Workspace size",
                    "Measuring…", null);
            healthList.addView(size);
            // Walking the tree touches thousands of files, which is not something to do on the
            // thread that draws the screen.
            new Thread(() -> {
                long bytes = Workspace.sizeBytes(this);
                runOnUiThread(() -> {
                    if (!isFinishing()) size.setValue(DeviceProbe.formatBytes(bytes));
                });
            }, "measure-workspace").start();
        }
    }

    private String dataSuffix() {
        int cap = DataBudget.capMb(this);
        if (cap <= 0) return "";
        long used = DataBudget.usedToday(this);
        if (used < 0) return "";
        return " · " + DeviceProbe.formatBytes(used) + " of "
                + DeviceProbe.formatBytes(cap * 1_000_000L) + " used today";
    }

    // ------------------------------------------------------------------ actions

    private void onPrimaryAction() {
        if (WorkspaceService.editorRunning()) {
            startActivity(new Intent(this, WorkspaceActivity.class));
            return;
        }
        if (WorkspaceService.busy()) {
            startActivity(new Intent(this, SetupActivity.class));
            return;
        }
        if (Workspace.installed(this)) {
            startActivity(new Intent(this, WorkspaceActivity.class));
            return;
        }
        String refusal = DeviceCheck.refusal(this);
        if (refusal != null) {
            Dialogs.message(this, "This phone cannot run it", refusal);
            return;
        }
        startActivity(new Intent(this, SetupActivity.class));
    }

    private void listen() {
        events = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                refreshEverything();
            }
        };
        registerReceiver(events, new IntentFilter(WorkspaceService.EVENT),
                Context.RECEIVER_NOT_EXPORTED);
    }
}
