package com.pocketide;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
        content.addView(footer(dark), Ui.wide(host, 18));

        refreshEverything();
        return Ui.page(host, content, dark);
    }

    @Override public void shown(Activity activity) {
        host = activity;
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

    private View footer(boolean dark) {
        TextView line = Ui.text(host, "PocketIDE " + BuildFacts.VERSION_NAME
                + " · everything runs on this phone", 12f, Ui.muted(dark));
        line.setGravity(Gravity.CENTER);
        return line;
    }

    // ------------------------------------------------------------------ live state

    private void refreshEverything() {
        if (host == null || host.isFinishing()) return;
        boolean dark = Ui.dark(host);
        boolean installed = Workspace.installed(host);
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
                stateLine.setText("The editor is running.");
            } else if (busy) {
                stateLine.setText("Working. Tap to watch what it is doing.");
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
            action.setText(running ? "Open the editor"
                    : busy ? "See what it is doing"
                    : installed ? "Open the editor" : "Set up");
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
            boolean here = present.contains(agent.id);
            String value = here ? "Installed · open it from the editor's side panel" : agent.plan;
            if (!first) agentList.addView(Ui.divider(host, dark, true));
            first = false;
            Ui.Row row = Ui.row(host, dark,
                    here ? R.drawable.ic_check : R.drawable.ic_install,
                    agent.name + " · " + agent.publisher, value,
                    v -> MainActivity.open(host, "agents"));
            if (here) row.setState(Ui.RUNNING);
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

    private int countRecommendedInstalled(List<String> present) {
        int count = 0;
        for (Agents.Agent agent : Agents.ALL) if (present.contains(agent.id)) count++;
        return count;
    }

    private void refreshAttention(boolean dark) {
        if (attention == null) return;
        attention.removeAllViews();
        LinearLayout list = Ui.column(host);
        list.setBackground(Ui.glass(host, dark, 20));
        boolean any = false;

        if (!Permissions.notificationsAllowed(host)) {
            list.addView(Ui.row(host, dark, R.drawable.ic_notification, "Notifications are off",
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
        if (Crash.exists(host)) {
            if (any) list.addView(Ui.divider(host, dark, true));
            list.addView(Ui.row(host, dark, R.drawable.ic_info, "The app stopped unexpectedly",
                    "Nothing in the workspace was lost. Tap to see the record.",
                    v -> Dialogs.details(host, "What was recorded",
                            "The app itself stopped. The workspace and its files live in their "
                                    + "own storage, so nothing in them was lost.",
                            Crash.read(host), "Copy details")));
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

        healthList.addView(Ui.row(host, dark, R.drawable.ic_phone, probe.model,
                probe.androidVersion + " · " + probe.abi + " · "
                        + DeviceProbe.formatBytes(probe.totalRam) + " RAM", null));
        healthList.addView(Ui.divider(host, dark, true));

        Ui.Row space = Ui.row(host, dark, R.drawable.ic_storage, "Free space",
                DeviceProbe.formatBytes(probe.freeStorage), null);
        if (probe.freeStorage < DeviceCheck.NEEDED_BYTES && !Workspace.installed(host)) {
            space.setState(Ui.NEEDS_YOU);
            space.setValue(DeviceProbe.formatBytes(probe.freeStorage) + " · about "
                    + DeviceProbe.formatBytes(DeviceCheck.NEEDED_BYTES) + " needed");
        }
        healthList.addView(space);
        healthList.addView(Ui.divider(host, dark, true));

        Ui.Row network = Ui.row(host, dark,
                DeviceProbe.isWifi(host) ? R.drawable.ic_wifi : R.drawable.ic_network,
                "Network", probe.network + dataSuffix(), null);
        if ("Offline".equals(probe.network)) network.setState(Ui.NEEDS_YOU);
        healthList.addView(network);
        healthList.addView(Ui.divider(host, dark, true));

        Ui.Row heat = Ui.row(host, dark, R.drawable.ic_temperature, "Battery",
                (probe.batteryPercent >= 0 ? probe.batteryPercent + "%" : "Unknown")
                        + (probe.batteryTempC > 0
                                ? " · " + Math.round(probe.batteryTempC) + " °C" : "")
                        + " · " + DeviceProbe.thermalName(probe.thermalStatus), null);
        if (probe.thermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE) {
            heat.setState(Ui.NEEDS_YOU);
        }
        healthList.addView(heat);

        if (Workspace.installed(host)) {
            healthList.addView(Ui.divider(host, dark, true));
            Ui.Row size = Ui.row(host, dark, R.drawable.ic_memory, "Workspace size",
                    "Measuring…", null);
            healthList.addView(size);
            final Activity measuring = host;
            // Walking the tree touches thousands of files, which is not something to do on the
            // thread that draws the screen.
            new Thread(() -> {
                long bytes = Workspace.sizeBytes(measuring);
                measuring.runOnUiThread(() -> {
                    if (!measuring.isFinishing()) size.setValue(DeviceProbe.formatBytes(bytes));
                });
            }, "measure-workspace").start();
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
        if (WorkspaceService.editorRunning()) {
            host.startActivity(new Intent(host, WorkspaceActivity.class));
            return;
        }
        if (WorkspaceService.busy()) {
            host.startActivity(new Intent(host, SetupActivity.class));
            return;
        }
        if (Workspace.installed(host)) {
            host.startActivity(new Intent(host, WorkspaceActivity.class));
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
