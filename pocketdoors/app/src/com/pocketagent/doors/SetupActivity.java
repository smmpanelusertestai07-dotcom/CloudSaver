package com.pocketagent.doors;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.WindowManager;
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
    private PowerManager.WakeLock awake;
    private final StringBuilder transcript = new StringBuilder();
    /**
     * The one-word state above the headline: WORKING, READY or STOPPED.
     *
     * Named stateLabel rather than state because onCreate's own parameter is called state, and
     * a field shadowed by a Bundle is a compile error that reads like a type mystery.
     */
    private TextView stateLabel;
    /** The sentence under the headline, which changes with the state. */
    private TextView explain;

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

        // A status card, not a bare heading. The state reads first, the name of the step
        // second, and what it means third -- which is the order somebody actually reads a
        // screen they are waiting on.
        LinearLayout status = Ui.card(this, dark);
        stateLabel = Ui.sectionLabel(this, "Working", dark);
        status.addView(stateLabel);
        headline = Ui.bold(this, "Setting up the workspace", 21, Ui.text(dark));
        status.addView(headline, Ui.wide(this, 4));
        explain = Ui.text(this,
                "Ubuntu downloads once and stays inside this app. The screen is held on until "
                        + "this finishes, so leave the phone plugged in and stay on one connection "
                        + "if you can; if it stops, opening this again continues from where it got "
                        + "to.", 14, Ui.muted(dark));
        status.addView(explain, Ui.wide(this, 8));
        root.addView(status, Ui.wide(this, 0));

        // The raw output, labelled as what it is and put below the plain words rather than
        // handed over as the whole explanation.
        root.addView(Ui.sectionLabel(this, "Details", dark), Ui.wide(this, 22));
        log = Ui.mono(this, "", 12, Ui.muted(dark));
        int pad = Ui.dp(this, 14);
        log.setPadding(pad, pad, pad, pad);
        log.setBackground(Ui.glass(this, dark, 14));
        root.addView(log, Ui.wide(this, 8));

        action = Ui.button(this, "Close", false, dark);
        action.setVisibility(android.view.View.GONE);
        action.setOnClickListener(v -> finish());
        root.addView(action, Ui.wide(this, 18));

        long free = Ubuntu.freeBytes(this);
        long needed = Ubuntu.IMAGE_BYTES + Ubuntu.WORKSPACE_BYTES;
        if (free < needed) {
            // Better to say it now than after thirty megabytes of someone's data.
            stateLabel.setText("STOPPED");
            stateLabel.setTextColor(Ui.FAILED);
            headline.setText("Not enough room");
            say("This needs about " + megabytes(needed) + " free and the phone has "
                    + megabytes(free) + ".");
            say("Free some space and open this again.");
            action.setVisibility(android.view.View.VISIBLE);
            return;
        }

        holdAwake();
        worker = new Thread(this::install, "setup");
        worker.start();
    }

    /**
     * Keeps the phone awake for the length of the install.
     *
     * Unpacking a base image and configuring a few hundred packages takes tens of minutes under
     * PRoot, and none of it survives being frozen halfway: dpkg left mid-transaction has to be
     * repaired before anything else can be installed. Android is entitled to freeze an app whose
     * screen has gone dark, so the screen is held on and a wake lock is taken -- and both are
     * released the moment the install ends, successfully or not.
     */
    private void holdAwake() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            PowerManager power = getSystemService(PowerManager.class);
            if (power == null) return;
            awake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketdoors:setup");
            // A ceiling, so a set-up that somehow never returns cannot hold the phone awake
            // for the rest of the day.
            awake.acquire(3 * 60 * 60 * 1000L);
        } catch (Exception denied) {
            // Without it the install still runs; it is simply more fragile if the screen sleeps.
            awake = null;
        }
    }

    private void releaseAwake() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (awake == null) return;
        try { if (awake.isHeld()) awake.release(); } catch (Exception ignored) { }
        awake = null;
    }

    private void install() {
        try {
            Ubuntu.install(this, this::say);
            main.post(() -> {
                releaseAwake();
                stateLabel.setText("READY");
                stateLabel.setTextColor(Ui.RUNNING);
                headline.setText("Workspace ready");
                explain.setText("Ubuntu is installed and stays inside this app. Choose an agent "
                        + "on the previous screen to open the editor.");
                action.setVisibility(android.view.View.VISIBLE);
            });
        } catch (Throwable problem) {
            String reason = problem.getMessage() == null
                    ? problem.getClass().getSimpleName() : problem.getMessage();
            say(reason);
            main.post(() -> {
                releaseAwake();
                // What happened and what to do, in words, above the raw output rather than
                // instead of it. An owner handed "run 'dpkg --configure -a'" has been given a
                // dead end: there is no prompt on this screen to type it at.
                Trouble.Advice advice = Trouble.read(transcript.toString());
                stateLabel.setText("STOPPED");
                stateLabel.setTextColor(Ui.FAILED);
                headline.setText(advice.what);
                headline.setTextSize(18);
                explain.setText(advice.next);
                action.setVisibility(android.view.View.VISIBLE);
                ((TextView) action).setText(advice.action);
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
        releaseAwake();
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }
}
