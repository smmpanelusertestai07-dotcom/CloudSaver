package com.pocketide;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.EnumMap;
import java.util.Map;

/**
 * Set-up, shown as a list of steps rather than a spinner.
 *
 * This screen exists because of a specific failure. Set-up takes twenty minutes or more, and for
 * most of that nothing visible happens: apt prints no per-file progress, and unpacking a base
 * image is thousands of small writes. An earlier build showed one sentence above a black
 * rectangle for half an hour, which is indistinguishable from a hang -- so the owner closed the
 * app, PRoot's --kill-on-exit killed dpkg mid-configure, and every later attempt failed before
 * it started.
 *
 * So: every step is listed with its own state and its own clock, the total elapsed time is
 * always on screen, what is being installed is named, and the transcript is underneath for
 * anyone who wants it. Nothing here is decoration. It is all the answer to "is this stuck?".
 */
public final class SetupActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver events;

    private final Map<Stage, Ui.Row> rows = new EnumMap<>(Stage.class);
    private Stage current = Stage.CHECK;
    private final Map<Stage, Long> finishedAt = new EnumMap<>(Stage.class);
    private long startedAt;
    private boolean failed;

    private ProgressBar bar;
    private TextView percentText;
    private TextView clockText;
    private TextView headline;
    private TextView transcript;
    private ScrollView transcriptScroll;
    private TextView primary;
    private final StringBuilder lines = new StringBuilder();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Theme.apply(this);
        startedAt = Prefs.of(this).getLong(Prefs.SETUP_STARTED_AT, 0);
        if (startedAt <= 0 || !WorkspaceService.busy()) startedAt = System.currentTimeMillis();
        setContentView(build());
        listen();
        if (!WorkspaceService.busy() && !Workspace.installed(this)) begin();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
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

    // ------------------------------------------------------------------ the screen

    private View build() {
        boolean dark = Ui.dark(this);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.bg(dark));
        root.addView(Ui.topBar(this, dark, "Set up", v -> finish()));

        LinearLayout content = Ui.column(this);
        content.addView(machineCard(dark));
        content.addView(progressCard(dark), Ui.wide(this, 14));
        content.addView(stepsCard(dark), Ui.wide(this, 14));
        content.addView(transcriptCard(dark), Ui.wide(this, 14));

        primary = Ui.button(this, "Stop", false, dark);
        primary.setOnClickListener(v -> onPrimary());
        content.addView(primary, Ui.wide(this, 18));

        ScrollView page = Ui.page(this, content, dark);
        root.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    /**
     * What is being built, with the Linux mascot on it.
     *
     * The owner is about to spend twenty minutes and several hundred megabytes; naming the
     * thing they are getting -- Ubuntu, this release, from Canonical -- is the least this
     * screen can do while they wait.
     */
    private View machineCard(boolean dark) {
        LinearLayout card = Ui.card(this, dark);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView tux = new ImageView(this);
        tux.setImageResource(R.drawable.tux);
        tux.setAdjustViewBounds(true);
        int height = Ui.dp(this, 56);
        row.addView(tux, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, height));

        LinearLayout words = Ui.column(this);
        words.addView(Ui.sectionLabel(this, "The computer being built", dark));
        words.addView(Ui.bold(this, Workspace.IMAGE_LABEL, 16, Ui.text(dark)),
                Ui.wide(this, 4));
        words.addView(Ui.text(this,
                "Canonical's own base image, checked against its published SHA-256, running on "
                        + "this phone's own kernel. No virtual machine and no root.",
                12.5f, Ui.muted(dark)), Ui.wide(this, 4));
        LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wordParams.leftMargin = Ui.dp(this, 14);
        row.addView(words, wordParams);
        card.addView(row);

        card.addView(Ui.divider(this, dark, false), Ui.wide(this, 14));
        LinearLayout editorRow = new LinearLayout(this);
        editorRow.setOrientation(LinearLayout.HORIZONTAL);
        editorRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView code = new ImageView(this);
        code.setImageResource(R.drawable.logo_vscode);
        int codeSize = Ui.dp(this, 28);
        editorRow.addView(code, new LinearLayout.LayoutParams(codeSize, codeSize));
        TextView codeWords = Ui.text(this,
                "Visual Studio Code 1.137 · code-server 4.137 · MIT licensed, no Microsoft "
                        + "account required", 12.5f, Ui.muted(dark));
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        codeParams.leftMargin = Ui.dp(this, 12);
        editorRow.addView(codeWords, codeParams);
        card.addView(editorRow, Ui.wide(this, 12));
        return card;
    }

    private View progressCard(boolean dark) {
        LinearLayout card = Ui.card(this, dark);

        headline = Ui.bold(this, "Starting…", 18, Ui.text(dark));
        card.addView(headline);

        LinearLayout figures = new LinearLayout(this);
        figures.setOrientation(LinearLayout.HORIZONTAL);
        figures.setGravity(Gravity.CENTER_VERTICAL);
        percentText = Ui.bold(this, "0%", 28, Ui.accent(dark));
        figures.addView(percentText);
        clockText = Ui.mono(this, "0:00", 15, Ui.muted(dark));
        LinearLayout.LayoutParams clockParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clockParams.leftMargin = Ui.dp(this, 12);
        clockParams.bottomMargin = Ui.dp(this, 3);
        figures.setGravity(Gravity.BOTTOM);
        figures.addView(clockText, clockParams);
        card.addView(figures, Ui.wide(this, 8));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setProgressTintList(ColorStateList.valueOf(Ui.accent(dark)));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.line(dark)));
        LinearLayout.LayoutParams barParams = Ui.wide(this, 10);
        barParams.height = Ui.dp(this, 8);
        card.addView(bar, barParams);

        card.addView(Ui.text(this,
                "Leave the phone plugged in and the app open. Closing it part way through is "
                        + "the one thing that has to be repaired afterwards.",
                12.5f, Ui.muted(dark)), Ui.wide(this, 12));
        return card;
    }

    /** One row per step, each with its own state and its own elapsed time. */
    private View stepsCard(boolean dark) {
        LinearLayout column = Ui.column(this);
        column.addView(Ui.sectionLabel(this, "Steps", dark));
        LinearLayout list = Ui.column(this);
        list.setBackground(Ui.glass(this, dark, 20));
        boolean first = true;
        for (Stage stage : Stage.values()) {
            if (!first) list.addView(Ui.divider(this, dark, true));
            first = false;
            Ui.Row row = Ui.row(this, dark, R.drawable.ic_chevron, stage.title,
                    stage.detail, null);
            row.icon.setImageResource(R.drawable.ic_chevron);
            rows.put(stage, row);
            list.addView(row);
        }
        column.addView(list, Ui.wide(this, 8));
        return column;
    }

    private View transcriptCard(boolean dark) {
        LinearLayout column = Ui.column(this);
        column.addView(Ui.sectionLabel(this, "What it is doing", dark));
        transcript = Ui.mono(this, "", 11.5f, Ui.muted(dark));
        int pad = Ui.dp(this, 14);
        transcript.setPadding(pad, pad, pad, pad);
        transcript.setBackground(Ui.glass(this, dark, 16));
        transcript.setTextIsSelectable(true);
        transcriptScroll = new ScrollView(this);
        transcriptScroll.addView(transcript);
        LinearLayout.LayoutParams params = Ui.wide(this, 8);
        params.height = Ui.dp(this, 150);
        column.addView(transcriptScroll, params);
        return column;
    }

    // ------------------------------------------------------------------ state

    private void begin() {
        String blocked = DataBudget.whyBlocked(this);
        if (!blocked.isEmpty()) {
            showFailure(blocked);
            return;
        }
        failed = false;
        startedAt = System.currentTimeMillis();
        finishedAt.clear();
        for (Stage stage : Stage.values()) resetRow(stage);
        lines.setLength(0);
        transcript.setText("");
        WorkspaceService.setUp(this);
    }

    private void resetRow(Stage stage) {
        Ui.Row row = rows.get(stage);
        if (row == null) return;
        boolean dark = Ui.dark(this);
        row.icon.setImageResource(R.drawable.ic_chevron);
        row.setMutedIcon();
        row.setValue(stage.detail);
        row.title.setTextColor(Ui.muted(dark));
    }

    private void onStage(Stage stage, String message, int percent, long elapsed) {
        boolean dark = Ui.dark(this);
        if (stage != null && stage != current) {
            markDone(current);
            current = stage;
        }
        Ui.Row row = rows.get(current);
        if (row != null) {
            row.icon.setImageResource(R.drawable.ic_bolt);
            row.setState(Ui.accent(dark));
            row.title.setTextColor(Ui.text(dark));
            row.setValue(message);
        }
        if (percent >= 0) {
            bar.setProgress(percent);
            percentText.setText(percent + "%");
        }
        headline.setText(current.title);
        if (elapsed > 0) startedAt = System.currentTimeMillis() - elapsed;
        note(message);
    }

    private void markDone(Stage stage) {
        Ui.Row row = rows.get(stage);
        if (row == null) return;
        long took = System.currentTimeMillis() - startedAt;
        Long previous = finishedAt.get(stage);
        finishedAt.put(stage, took);
        row.icon.setImageResource(R.drawable.ic_check);
        row.setState(Ui.RUNNING);
        row.title.setTextColor(Ui.text(Ui.dark(this)));
        row.setValue("Done · " + Stage.clock(previous == null ? took : previous));
    }

    private void onReady() {
        for (Stage stage : Stage.values()) markDone(stage);
        bar.setProgress(100);
        percentText.setText("100%");
        headline.setText("Ready");
        primary.setText("Open the editor");
        primary.setOnClickListener(v -> {
            startActivity(new Intent(this, WorkspaceActivity.class));
            finish();
        });
        handler.removeCallbacks(tick);
    }

    private void showFailure(String raw) {
        failed = true;
        boolean dark = Ui.dark(this);
        headline.setText("Set-up stopped");
        headline.setTextColor(Ui.FAILED);
        Ui.Row row = rows.get(current);
        if (row != null) {
            row.icon.setImageResource(R.drawable.ic_close);
            row.setState(Ui.FAILED);
            row.setValue(raw);
        }
        String advice = Trouble.advice(raw);
        note(raw);
        primary.setText("Try again");
        primary.setOnClickListener(v -> begin());
        Dialogs.details(this, "Set-up stopped",
                advice != null ? advice
                        : "Nothing downloaded so far is lost. Tap Try again and it continues "
                                + "from where it stopped.",
                raw, "Copy details");
    }

    private void note(String line) {
        if (line == null || line.trim().isEmpty()) return;
        lines.append(line).append('\n');
        // A whole set-up is thousands of lines; keeping the tail is what a person reads anyway,
        // and keeping all of it is how a TextView starts dropping frames.
        if (lines.length() > 12000) lines.delete(0, lines.length() - 12000);
        transcript.setText(lines.toString().trim());
        transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void updateClock() {
        if (clockText == null) return;
        clockText.setText(Stage.clock(System.currentTimeMillis() - startedAt)
                + (failed ? "" : " elapsed"));
    }

    private void onPrimary() {
        if (failed) {
            begin();
            return;
        }
        if (WorkspaceService.busy()) {
            Dialogs.confirm(this, "Stop set-up?",
                    "Everything downloaded so far is kept, so starting again continues from "
                            + "where it stopped.", "Stop", true, () -> {
                        WorkspaceService.stop(this);
                        finish();
                    });
            return;
        }
        finish();
    }

    private void listen() {
        events = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String state = intent.getStringExtra(WorkspaceService.EXTRA_STATE);
                String line = intent.getStringExtra(WorkspaceService.EXTRA_LINE);
                String stageName = intent.getStringExtra(WorkspaceService.EXTRA_STAGE);
                int percent = intent.getIntExtra(WorkspaceService.EXTRA_PERCENT, -1);
                long elapsed = intent.getLongExtra(WorkspaceService.EXTRA_ELAPSED, 0);
                Stage stage = null;
                if (stageName != null) {
                    try { stage = Stage.valueOf(stageName); }
                    catch (IllegalArgumentException unknown) { stage = null; }
                }
                if ("failed".equals(state)) {
                    showFailure(line == null ? "Set-up stopped." : line);
                    return;
                }
                if (line != null) onStage(stage, line, percent, elapsed);
                if ("ready".equals(state)) onReady();
            }
        };
        registerReceiver(events, new IntentFilter(WorkspaceService.EVENT),
                Context.RECEIVER_NOT_EXPORTED);
    }
}
