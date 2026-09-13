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
import java.util.Locale;

/**
 * What the computer in the phone is doing, right now.
 *
 * This screen exists because of a question with no other answer: an agent has been told to
 * build something, the phone is warm, the battery is going down, and nothing on screen says
 * whether it is compiling, waiting on the network, or stuck. On a laptop that question answers
 * itself -- a fan, a task manager, a terminal you can see. On a phone the work happens behind
 * whatever app is in front, and until this screen there was no way to look at it.
 *
 * It is deliberately NOT a remote control. There is nothing here to drive the agent with,
 * because the agent is driven in the editor, where its own publisher draws its own interface,
 * and a second half-built control surface beside that one would be worse than none. What this
 * screen offers is the three things the editor cannot show: which processes exist, what they
 * are costing, and a way to stop them. Everything on it is read from the app's own /proc, which
 * Android restricts to a process's own descendants -- so this can only ever show this app's
 * work, never the phone's.
 */
final class ActivityPane implements Pane {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Activity host;
    private BroadcastReceiver events;

    private TextView statePill;
    private TextView uptime;
    private TextView summary;
    private LinearLayout processList;
    private TextView processEmpty;
    private LinearLayout logBlock;
    private TextView logText;
    private TextView stop;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            // Two seconds. Fast enough that a build looks alive, slow enough that reading a few
            // dozen /proc entries is not itself the thing draining the battery.
            handler.postDelayed(this, 2000);
        }
    };

    @Override public String key() { return "activity"; }

    @Override public View build(Activity activity) {
        host = activity;
        boolean dark = Ui.dark(host);
        LinearLayout content = Ui.column(host);

        content.addView(stateCard(dark));
        content.addView(processCard(dark), Ui.wide(host, 16));
        content.addView(logCard(dark), Ui.wide(host, 16));
        content.addView(note(dark), Ui.wide(host, 18));

        refresh();
        return Ui.page(host, content, dark);
    }

    @Override public void shown(Activity activity) {
        host = activity;
        handler.removeCallbacks(tick);
        handler.post(tick);
        if (events == null) {
            events = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) { refresh(); }
            };
            host.registerReceiver(events, new IntentFilter(WorkspaceService.EVENT),
                    Context.RECEIVER_NOT_EXPORTED);
        }
    }

    @Override public void hidden(Activity activity) {
        handler.removeCallbacks(tick);
        if (events != null) {
            try {
                activity.unregisterReceiver(events);
            } catch (IllegalArgumentException alreadyGone) {
                // Already gone. Not a fault.
            }
            events = null;
        }
    }

    // ------------------------------------------------------------------ the screen

    private View stateCard(boolean dark) {
        LinearLayout card = Ui.card(host, dark);

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        statePill = Ui.pill(host, "CHECKING", Ui.muted(dark));
        row.addView(statePill);
        uptime = Ui.mono(host, "", 12.5f, Ui.muted(dark));
        LinearLayout.LayoutParams uptimeParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        uptimeParams.leftMargin = Ui.dp(host, 10);
        uptime.setGravity(Gravity.END);
        row.addView(uptime, uptimeParams);
        card.addView(row);

        summary = Ui.text(host, "", 14f, Ui.text(dark));
        card.addView(summary, Ui.wide(host, 12));

        stop = Ui.button(host, "Stop the workspace", false, dark);
        stop.setOnClickListener(v -> confirmStop());
        card.addView(stop, Ui.wide(host, 16));
        return card;
    }

    private View processCard(boolean dark) {
        LinearLayout column = Ui.column(host);
        column.addView(Ui.sectionLabel(host, "Running now", dark));
        processList = Ui.column(host);
        processList.setBackground(Ui.glass(host, dark, 20));
        processEmpty = Ui.text(host, "Nothing is running.", 14f, Ui.muted(dark));
        int pad = Ui.dp(host, 16);
        processEmpty.setPadding(pad, pad, pad, pad);
        column.addView(processList, Ui.wide(host, 8));
        return column;
    }

    private View logCard(boolean dark) {
        logBlock = Ui.column(host);
        logBlock.addView(Ui.sectionLabel(host, "Recent output", dark));
        LinearLayout box = Ui.column(host);
        box.setBackground(Ui.glass(host, dark, 20));
        int pad = Ui.dp(host, 14);
        box.setPadding(pad, pad, pad, pad);
        logText = Ui.mono(host, "", 11.5f, Ui.muted(dark));
        // Long lines from a compiler wrap rather than run off the side, because a phone has no
        // horizontal scroll to find the end of them with.
        logText.setHorizontallyScrolling(false);
        box.addView(logText);

        TextView copy = Ui.button(host, "Copy everything", false, dark);
        copy.setOnClickListener(v -> Dialogs.details(host, "Recent output",
                "Everything the workspace has printed since it started. Useful to paste into a "
                        + "bug report; it contains file paths from this phone and nothing else.",
                joined(), "Copy"));
        box.addView(copy, Ui.wide(host, 12));

        logBlock.addView(box, Ui.wide(host, 8));
        return logBlock;
    }

    private View note(boolean dark) {
        TextView line = Ui.text(host,
                "Only this app's own processes can be listed here. Android does not let one app "
                        + "see another's, and this screen asks for no permission that would "
                        + "change that.", 12f, Ui.muted(dark));
        line.setGravity(Gravity.CENTER);
        return line;
    }

    // ------------------------------------------------------------------ live state

    private void refresh() {
        if (host == null || host.isFinishing()) return;
        boolean dark = Ui.dark(host);
        boolean running = WorkspaceService.editorRunning();
        boolean busy = WorkspaceService.busy();

        if (statePill != null) {
            if (running) {
                statePill.setText("RUNNING");
                statePill.setTextColor(Ui.RUNNING);
            } else if (busy) {
                statePill.setText("WORKING");
                statePill.setTextColor(Ui.NEEDS_YOU);
            } else {
                statePill.setText("STOPPED");
                statePill.setTextColor(Ui.muted(dark));
            }
        }

        long since = WorkspaceService.runningSince();
        if (uptime != null) {
            uptime.setText(since > 0
                    ? "up " + Stage.clock(System.currentTimeMillis() - since) : "");
        }

        List<Running.Process> processes = Running.workspace(host);
        long resident = Running.totalResidentBytes(processes);

        if (summary != null) {
            if (running || busy) {
                DeviceProbe probe = DeviceProbe.read(host);
                summary.setText(processes.size() + (processes.size() == 1 ? " process" : " processes")
                        + " · " + DeviceProbe.formatBytes(resident) + " of "
                        + DeviceProbe.formatBytes(probe.totalRam) + " memory"
                        + (probe.batteryPercent >= 0 ? " · battery " + probe.batteryPercent + "%" : ""));
            } else {
                summary.setText("The workspace is not running. Your files are where you left "
                        + "them; starting the editor again picks up where you stopped.");
            }
        }

        if (stop != null) {
            stop.setEnabled(running || busy);
            stop.setAlpha(stop.isEnabled() ? 1f : 0.45f);
        }

        refreshProcesses(dark, processes);
        refreshLog(dark);
    }

    private void refreshProcesses(boolean dark, List<Running.Process> processes) {
        if (processList == null) return;
        processList.removeAllViews();
        if (processes.isEmpty()) {
            processList.addView(processEmpty);
            return;
        }
        boolean first = true;
        for (Running.Process process : processes) {
            if (!first) processList.addView(Ui.divider(host, dark, true));
            first = false;
            Ui.Row row = Ui.row(host, dark,
                    process.working() ? R.drawable.ic_bolt : R.drawable.ic_timer,
                    process.command,
                    process.stateWords() + " · " + DeviceProbe.formatBytes(process.residentBytes)
                            + " · pid " + process.pid,
                    v -> Dialogs.details(host, process.command,
                            "State: " + process.stateWords() + "\nMemory: "
                                    + DeviceProbe.formatBytes(process.residentBytes)
                                    + "\nProcess id: " + process.pid,
                            process.detail, "Copy the command"));
            if (process.working()) row.setState(Ui.RUNNING);
            processList.addView(row);
        }
    }

    private void refreshLog(boolean dark) {
        if (logBlock == null || logText == null) return;
        List<String> lines = WorkspaceService.recentLog();
        if (lines.isEmpty()) {
            logBlock.setVisibility(View.GONE);
            return;
        }
        logBlock.setVisibility(View.VISIBLE);
        // The last dozen, newest at the bottom, which is the direction a log is read in.
        int from = Math.max(0, lines.size() - 12);
        StringBuilder text = new StringBuilder();
        for (int i = from; i < lines.size(); i++) {
            if (text.length() > 0) text.append('\n');
            text.append(lines.get(i));
        }
        logText.setText(text.toString());
    }

    private String joined() {
        StringBuilder all = new StringBuilder();
        for (String line : WorkspaceService.recentLog()) all.append(line).append('\n');
        return all.length() == 0 ? "Nothing has been printed yet." : all.toString();
    }

    private void confirmStop() {
        long since = WorkspaceService.runningSince();
        String been = since > 0
                ? " It has been running for " + Stage.clock(System.currentTimeMillis() - since)
                        + "." : "";
        Dialogs.confirm(host, "Stop the workspace?",
                "The editor and everything it is running will stop, including anything an agent "
                        + "is part way through." + been
                        + "\n\nNothing is deleted. Your files stay exactly as they are and the "
                        + "editor reopens where you left it.",
                "Stop", () -> {
                    WorkspaceService.stop(host);
                    handler.postDelayed(this::refresh, 400);
                });
    }

    static String percent(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value * 100);
    }
}
