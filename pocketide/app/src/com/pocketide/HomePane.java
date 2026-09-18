package com.pocketide;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The first destination: is it ready, what is installed, and is the phone in a state to run it.
 *
 * Those are the three questions an owner actually arrives with, in that order, and the screen
 * answers them in that order. Anything that is not one of those three now lives behind its own
 * tab on the bottom bar instead of as a row here, which is what the bar bought.
 *
 * The card at the top no longer repeats the app's name and mark -- the bar above it carries
 * both. What it does instead is lead with the state, because on a screen someone opens twenty
 * times a day, "READY" in the first line is worth more than a logo they have already seen.
 */
final class HomePane implements Pane {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Activity host;
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

    @Override public String key() { return "home"; }

    /** Refreshes itself every five seconds; being told it is back is enough. */
    @Override public boolean rebuildOnReturn() { return false; }

    /**
     * The last exit worth mentioning, read once per showing rather than on every tick.
     *
     * It is a call into the system server, and the answer cannot change while the app is
     * running: the only exit it could add is this one's.
     */
    private Exits.Exit lastExit;

    @Override public View build(Activity activity) {
        host = activity;
        boolean dark = Ui.dark(host);
        LinearLayout content = Ui.column(host);

        content.addView(hero(dark));
        content.addView(agentsCard(dark), Ui.wide(host, 14));
        attention = new LinearLayout(host);
        attention.setOrientation(LinearLayout.VERTICAL);
        attention.setVisibility(View.GONE);
        content.addView(attention, Ui.wide(host, 14));
        content.addView(healthCard(dark), Ui.wide(host, 14));

        lastExit = Exits.last(host);
        refreshEverything();
        return Ui.page(host, content, dark);
    }

    @Override public void shown(Activity activity) {
        host = activity;
        lastExit = Exits.last(host);
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        if (events == null) {
            events = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    refreshEverything();
                }
            };
            host.registerReceiver(events, new IntentFilter(WorkspaceService.EVENT),
                    Context.RECEIVER_NOT_EXPORTED);
        }
    }

    @Override public void hidden(Activity activity) {
        handler.removeCallbacks(refresh);
        if (events != null) {
            try {
                activity.unregisterReceiver(events);
            } catch (IllegalArgumentException alreadyGone) {
                // Unregistering twice throws rather than no-opping. It happens when the host is
                // destroyed straight after being paused, and it is not a fault worth a crash.
            }
            events = null;
        }
    }

    // ------------------------------------------------------------------ the screen

    /** The state card. Violet, because it is the one place the brand colour belongs. */
    private View hero(boolean dark) {
        LinearLayout card = Ui.column(host);
        int pad = Ui.dp(host, 20);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(Ui.metal(host, 24));

        statePill = Ui.pill(host, "CHECKING", Brand.ON_BRAND_MUTED);
        LinearLayout pillRow = new LinearLayout(host);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pillRow.addView(statePill);
        card.addView(pillRow);

        stateLine = Ui.text(host, "", 16f, Brand.ON_BRAND);
        card.addView(stateLine, Ui.wide(host, 12));

        card.addView(Ui.text(host,
                Workspace.IMAGE_LABEL + " · Visual Studio Code · any coding agent",
                12.5f, Brand.ON_BRAND_MUTED), Ui.wide(host, 8));

        action = Ui.bold(host, "Set up", 16, Brand.TILE_BOTTOM);
        action.setGravity(Gravity.CENTER);
        int actionPad = Ui.dp(host, 15);
        action.setPadding(actionPad, actionPad, actionPad, actionPad);
        action.setMinHeight(Ui.dp(host, Ui.TOUCH_TARGET_DP));
        action.setBackground(Ui.tappable(host, Ui.fill(host, Brand.MARK, 14), false));
        action.setClickable(true);
        action.setFocusable(true);
        action.setOnClickListener(v -> onPrimaryAction());
        card.addView(action, Ui.wide(host, 18));
        return card;
    }

    private View agentsCard(boolean dark) {
        LinearLayout column = Ui.column(host);
        column.addView(Ui.sectionLabel(host, "Coding agents", dark));
        agentList = Ui.column(host);
        agentList.setBackground(Ui.glass(host, dark, 20));
        column.addView(agentList, Ui.wide(host, 8));
        return column;
    }

    private View healthCard(boolean dark) {
        LinearLayout column = Ui.column(host);
        column.addView(Ui.sectionLabel(host, "This phone", dark));
        healthList = Ui.column(host);
        healthList.setBackground(Ui.glass(host, dark, 20));
        column.addView(healthList, Ui.wide(host, 8));
        return column;
    }

    // ------------------------------------------------------------------ live state

    private void refreshEverything() {
        if (host == null || host.isFinishing()) return;
        boolean dark = Ui.dark(host);
        boolean installed = Workspace.installed(host);
        boolean running = WorkspaceService.editorRunning();
        boolean busy = WorkspaceService.busy();

        if (statePill != null) {
            if (running) Ui.recolour(statePill, "RUNNING", Brand.ON_BRAND);
            else if (busy) Ui.recolour(statePill, installed ? "STARTING" : "WORKING",
                    Brand.ON_BRAND);
            else if (installed) Ui.recolour(statePill, "READY", Brand.ON_BRAND);
            else Ui.recolour(statePill, "NOT SET UP", Brand.ON_BRAND_MUTED);
        }

        if (stateLine != null) {
            if (running) {
                stateLine.setText("The editor is running.");
            } else if (busy) {
                // Busy with Linux installed is the editor starting, not set-up running.
                stateLine.setText(installed ? "The editor is starting."
                        : "Setting up. Tap to watch what it is doing.");
            } else if (installed) {
                long took = Prefs.of(host).getLong(Prefs.SETUP_ELAPSED_MS, 0);
                stateLine.setText(took > 0
                        ? "Set up in " + Stage.clock(took) + ". Ready when you are."
                        : "Ready when you are.");
            } else {
                String refusal = DeviceCheck.refusal(host);
                stateLine.setText(refusal != null ? refusal
                        : "First set-up downloads about "
                                + DeviceProbe.formatBytes(BuildFacts.BASE_DOWNLOAD_BYTES)
                                + " and takes 20–40 minutes. It can be paused and resumed.");
            }
        }

        if (action != null) {
            action.setText(running || installed ? "Open the editor"
                    : busy ? "See what it is doing" : "Set up");
            action.setEnabled(installed || busy || DeviceCheck.ready(host));
            action.setAlpha(action.isEnabled() ? 1f : 0.55f);
        }

        refreshAgents(dark, installed);
        refreshAttention(dark);
        refreshHealth(dark);
    }

    private void refreshAgents(boolean dark, boolean installed) {
        if (agentList == null) return;
        agentList.removeAllViews();
        List<String> present = Registry.installed(host);
        boolean first = true;
        for (Agents.Agent agent : Agents.ALL) {
            boolean here = Extensions.has(present, agent.id);
            boolean refused = Agents.ANTIGRAVITY_ID.equals(agent.id) && Kernel.narrow();
            String value = refused ? "Cannot run on this phone's 39-bit kernel · tap to read why"
                    : here ? "Installed · tap to open it in the editor" : agent.plan;
            if (!first) agentList.addView(Ui.divider(host, dark, true));
            first = false;
            Ui.Row row = Ui.row(host, dark,
                    refused ? R.drawable.ic_info : here ? R.drawable.ic_check : R.drawable.ic_install,
                    agent.name + " · " + agent.publisher, value,
                    v -> {
                        if (here && !refused) openPanel(agent);
                        else MainActivity.open(host, "agents");
                    });
            if (refused) row.setState(Ui.needsYou(dark));
            else if (here) row.setState(Ui.running(dark));
            else if (agent.free) row.setState(Ui.accent(dark));
            agentList.addView(row);
        }
        int others = Math.max(0, present.size() - countRecommendedInstalled(present));
        agentList.addView(Ui.divider(host, dark, true));
        agentList.addView(Ui.row(host, dark, R.drawable.ic_extension,
                others > 0 ? others + " other extension" + (others == 1 ? "" : "s")
                        : "Browse all extensions",
                installed ? "Open VSX · verified publishers by default"
                        : "Available once set-up has finished",
                v -> MainActivity.open(host, "agents")));
    }

    /** The editor, with this agent's panel in front, through the companion extension. */
    private void openPanel(Agents.Agent agent) {
        new Thread(() -> {
            String command = Extensions.panelCommand(host, agent.id);
            if (!command.isEmpty()) Companion.command(host, command);
            host.runOnUiThread(() -> {
                if (!host.isFinishing()) host.startActivity(new Intent(host, WorkspaceActivity.class));
            });
        }, "open-panel").start();
    }

    private int countRecommendedInstalled(List<String> present) {
        int count = 0;
        for (Agents.Agent agent : Agents.ALL) if (Extensions.has(present, agent.id)) count++;
        return count;
    }

    private void refreshAttention(boolean dark) {
        if (attention == null) return;
        attention.removeAllViews();
        LinearLayout list = Ui.column(host);
        list.setBackground(Ui.glass(host, dark, 20));
        boolean any = false;

        if (!Permissions.notificationsAllowed(host)) {
            list.addView(Ui.row(host, dark, R.drawable.ic_bell, "Notifications are off",
                    "Set-up progress and the Stop button cannot be shown. Tap to allow.",
                    v -> Permissions.askNotifications(host, true)));
            any = true;
        }
        if (!Permissions.batteryUnrestricted(host)) {
            if (any) list.addView(Ui.divider(host, dark, true));
            list.addView(Ui.row(host, dark, R.drawable.ic_battery,
                    "Battery may interrupt a long set-up",
                    "Android can stop a 20-minute download when the screen goes off. Tap to allow.",
                    v -> Permissions.openBatterySettings(host)));
            any = true;
        }
        String blocked = DataBudget.whyBlocked(host);
        if (!blocked.isEmpty()) {
            if (any) list.addView(Ui.divider(host, dark, true));
            list.addView(Ui.row(host, dark, R.drawable.ic_network, "Downloads are waiting",
                    blocked, v -> MainActivity.open(host, "settings")));
            any = true;
        }
        Exits.Exit exit = lastExit;
        if (exit != null && exit.when > Prefs.of(host).getLong(Prefs.EXIT_SEEN_AT, 0)) {
            if (any) list.addView(Ui.divider(host, dark, true));
            final Exits.Exit shown = exit;
            list.addView(Ui.row(host, dark, R.drawable.ic_memory, shown.headline,
                    "Linux stopped while you were away. Tap to see why.",
                    v -> {
                        Prefs.of(host).edit().putLong(Prefs.EXIT_SEEN_AT, shown.when).apply();
                        Dialogs.message(host, shown.headline, shown.explanation);
                        MainActivity.rebuild(host);
                    }));
            any = true;
        }
        AppUpdates.Status app = AppUpdates.last(host);
        if (app.newer()) {
            if (any) list.addView(Ui.divider(host, dark, true));
            list.addView(Ui.row(host, dark, R.drawable.ic_download,
                    "PocketIDE " + app.latest + " is available",
                    "A newer version of this app was published. Tap to get it.",
                    v -> MainActivity.open(host, "settings")));
            any = true;
        }
        if (!any) {
            attention.setVisibility(View.GONE);
            return;
        }
        attention.setVisibility(View.VISIBLE);
        attention.addView(Ui.sectionLabel(host, "Needs you", dark));
        attention.addView(list, Ui.wide(host, 8));
    }

    private void refreshHealth(boolean dark) {
        if (healthList == null) return;
        healthList.removeAllViews();
        DeviceProbe probe = DeviceProbe.read(host);

        // The core count only, not a whole Capacity.read(): this runs every five seconds on
        // the thread that draws, and a second StatFs and getMemoryInfo per tick is jank paid
        // for nothing. The full reading is taken when the row is actually tapped.
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        healthList.addView(Ui.row(host, dark, R.drawable.ic_phone, probe.model,
                probe.androidVersion + " · " + probe.abi + " · " + cores
                        + (cores == 1 ? " core · " : " cores · ")
                        + DeviceProbe.formatBytes(probe.totalRam) + " RAM",
                v -> Dialogs.message(host, "What this computer is",
                        Capacity.describe(host, Capacity.read(host)))));
        healthList.addView(Ui.divider(host, dark, true));

        Ui.Row space = Ui.row(host, dark, R.drawable.ic_storage, "Free space",
                DeviceProbe.formatBytes(probe.freeStorage), null);
        if (probe.freeStorage < DeviceCheck.NEEDED_BYTES && !Workspace.installed(host)) {
            space.setState(Ui.needsYou(dark));
            space.setValue(DeviceProbe.formatBytes(probe.freeStorage) + " · about "
                    + DeviceProbe.formatBytes(DeviceCheck.NEEDED_BYTES) + " needed");
        }
        healthList.addView(space);
        healthList.addView(Ui.divider(host, dark, true));

        Ui.Row network = Ui.row(host, dark,
                DeviceProbe.isWifi(host) ? R.drawable.ic_wifi : R.drawable.ic_network,
                "Network", probe.network + dataSuffix(), null);
        if ("Offline".equals(probe.network)) network.setState(Ui.needsYou(dark));
        healthList.addView(network);
        healthList.addView(Ui.divider(host, dark, true));

        Ui.Row heat = Ui.row(host, dark, R.drawable.ic_temperature, "Battery",
                (probe.batteryPercent >= 0 ? probe.batteryPercent + "%" : "Unknown")
                        + (probe.batteryTempC > 0
                                ? " · " + Math.round(probe.batteryTempC) + " °C" : "")
                        + " · " + DeviceProbe.thermalName(probe.thermalStatus), null);
        if (probe.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            heat.setState(Ui.needsYou(dark));
        }
        healthList.addView(heat);

        if (Workspace.installed(host)) {
            healthList.addView(Ui.divider(host, dark, true));
            final Ui.Row size = Ui.row(host, dark, R.drawable.ic_memory, "Linux size",
                    "Measuring…", null);
            healthList.addView(size);
            // Measured off the drawing thread and at most once a minute -- see Workspace.size.
            // This row is redrawn every five seconds, and each redraw used to start a fresh
            // walk over tens of thousands of files.
            Workspace.size(host, bytes -> size.setValue(DeviceProbe.formatBytes(bytes)));
        }
    }

    private String dataSuffix() {
        int cap = DataBudget.capMb(host);
        if (cap <= 0) return "";
        long used = DataBudget.usedToday(host);
        if (used < 0) return "";
        return " · " + DeviceProbe.formatBytes(used) + " of "
                + DeviceProbe.formatBytes(cap * 1_000_000L) + " used today";
    }

    // ------------------------------------------------------------------ actions

    private void onPrimaryAction() {
        // Installed and busy is the editor starting, and the editor screen is where its
        // progress shows. Sending that tap to the set-up screen, as it used to, showed a
        // set-up that was not happening.
        if (WorkspaceService.editorRunning() || Workspace.installed(host)) {
            host.startActivity(new Intent(host, WorkspaceActivity.class));
            return;
        }
        if (WorkspaceService.busy()) {
            host.startActivity(new Intent(host, SetupActivity.class));
            return;
        }
        String refusal = DeviceCheck.refusal(host);
        if (refusal != null) {
            Dialogs.message(host, "This phone cannot run it", refusal);
            return;
        }
        host.startActivity(new Intent(host, SetupActivity.class));
    }
}
