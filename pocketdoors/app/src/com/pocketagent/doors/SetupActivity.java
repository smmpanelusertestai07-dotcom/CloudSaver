package com.pocketagent.doors;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The one long wait in this app, with something true on screen for all of it.
 *
 * Ubuntu is about thirty megabytes; what follows it -- Node, the server, the agent -- is closer
 * to a gigabyte, on a connection that is very likely mobile data. A progress bar that only
 * spins would be a lie by omission, so every line the workspace prints is shown, and a set-up
 * interrupted by a flat battery or a lost signal picks up from the step it reached.
 */
public final class SetupActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView log;
    private TextView headline;
    private TextView action;
    private ScrollView scroll;
    private Thread worker;
    private final StringBuilder transcript = new StringBuilder();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean dark = Ui.dark(this);

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.bg(dark));
        LinearLayout root = Ui.column(this);
        int side = Ui.dp(this, 20);
        root.setPadding(side, Ui.dp(this, 28), side, Ui.dp(this, 32));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        headline = Ui.bold(this, "Setting up the workspace", 22, Ui.text(dark));
        root.addView(headline);
        root.addView(Ui.text(this,
                "Ubuntu downloads once and stays inside this app. Leave the screen on and stay "
                        + "on one connection if you can; if it stops, opening this again continues "
                        + "from where it got to.", 14, Ui.muted(dark)), Ui.wide(this, 8));

        log = Ui.mono(this, "", 12, Ui.muted(dark));
        int pad = Ui.dp(this, 12);
        log.setPadding(pad, pad, pad, pad);
        log.setBackground(Ui.fill(this, Ui.card(dark), 10));
        root.addView(log, Ui.wide(this, 18));

        action = Ui.button(this, "Close", false, dark);
        action.setVisibility(android.view.View.GONE);
        action.setOnClickListener(v -> finish());
        root.addView(action, Ui.wide(this, 18));

        long free = Ubuntu.freeBytes(this);
        long needed = Ubuntu.IMAGE_BYTES + Ubuntu.WORKSPACE_BYTES;
        if (free < needed) {
            // Better to say it now than after thirty megabytes of someone's data.
            headline.setText("Not enough room");
            say("This needs about " + megabytes(needed) + " free and the phone has "
                    + megabytes(free) + ".");
            say("Free some space and open this again.");
            action.setVisibility(android.view.View.VISIBLE);
            return;
        }

        worker = new Thread(this::install, "setup");
        worker.start();
    }

    private void install() {
        try {
            Ubuntu.install(this, this::say);
            main.post(() -> {
                headline.setText("Workspace ready");
                action.setVisibility(android.view.View.VISIBLE);
            });
        } catch (Throwable problem) {
            String reason = problem.getMessage() == null
                    ? problem.getClass().getSimpleName() : problem.getMessage();
            say(reason);
            main.post(() -> {
                headline.setText("Set-up stopped");
                headline.setTextColor(Ui.FAILED);
                action.setVisibility(android.view.View.VISIBLE);
                ((TextView) action).setText("Try again");
                action.setOnClickListener(v -> recreate());
            });
        }
    }

    private void say(String line) {
        main.post(() -> {
            transcript.append(line).append('\n');
            // A screenful is plenty; the whole transcript would grow without limit on a retry.
            if (transcript.length() > 8000) transcript.delete(0, transcript.length() - 8000);
            log.setText(transcript.toString().trim());
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private static String megabytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024)
            return String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824.0);
        return (bytes / (1024 * 1024)) + " MB";
    }

    @Override
    protected void onDestroy() {
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }
}
