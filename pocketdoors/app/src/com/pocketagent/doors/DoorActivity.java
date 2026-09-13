package com.pocketagent.doors;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;

/**
 * The editor, full screen, with the phone's missing keys underneath.
 *
 * This screen draws almost nothing of its own. What fills it is Antigravity -- Google's editor,
 * running on this phone -- and whichever maker's extension the owner asked for, inside it. It
 * updates when they update it and it has every option they ship.
 *
 * It is a picture of a real program rather than a web page, because that is what the three
 * actually publish: Google's interface is a desktop application, and Anthropic's and OpenAI's
 * are extensions for one. So the display server draws it and this view shows what it drew.
 *
 * There is nowhere else to go from here. Earlier builds offered a second surface for two of the
 * three -- Anthropic's phone app, Google's web dashboard -- which was the same session seen from
 * somewhere else, and "somewhere else" is one more thing to learn and be disappointed by.
 */
public final class DoorActivity extends android.app.Activity implements KeyBar.Sender {
    static final String EXTRA_AGENT = "agent";

    private VncView screen;
    private TextView status;
    private KeyBar keys;
    /** What fills the screen while there is no editor to fill it: a state and a transcript. */
    private android.widget.ScrollView waiting;
    private TextView stateLabel;
    private TextView log;
    private Doors.Agent agent;
    private volatile VncClient client;
    private volatile boolean finished;
    private Thread connector;
    private BroadcastReceiver events;
    /** Everything the workspace has said, for the screen to show while there is nothing to draw. */
    private final StringBuilder conversation = new StringBuilder();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        agent = Doors.byId(getIntent().getStringExtra(EXTRA_AGENT));
        if (agent == null) { finish(); return; }
        setTitle(agent.name);

        boolean dark = Ui.dark(this);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.bg(dark));
        setContentView(root);

        status = Ui.text(this, "Starting " + agent.name + "…", 13.5f, Ui.muted(dark));
        int pad = Ui.dp(this, 14);
        status.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 10));
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Something to look at while a gigabyte arrives.
        //
        // The first run installs the editor and this agent's extension, and apt gives no
        // progress line per file. Without this the owner watches a black rectangle with one
        // sentence above it for half an hour, which is indistinguishable from a hang -- and
        // closing the app during a long silence is exactly what leaves dpkg half-applied and
        // makes the next attempt fail before it starts.
        root.addView(buildWaiting(dark), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        screen = new VncView(this);
        // The one moment the screen changes hands: connected means there is a picture, and only
        // then is the editor worth showing instead of what it is doing.
        screen.setStateListener((text, connected) -> runOnUiThread(() -> {
            if (connected) {
                showEditor();
                status.setTextColor(Ui.muted(Ui.dark(this)));
                status.setText(agent.name + " · running on this phone");
            } else if (text != null && !text.isEmpty()) {
                status.setText(text);
            }
        }));
        screen.setVisibility(View.GONE);
        root.addView(screen, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(Ui.divider(this, dark), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1))));
        keys = new KeyBar(this, this);
        // Hidden until there is something to type into. A row of keys that does nothing is a row
        // somebody presses and concludes the app is broken.
        keys.setVisibility(View.GONE);
        root.addView(keys, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        listen();
        DoorService.start(this, agent.id);
    }

    /** The status card and transcript that stand in for the editor until it is running. */
    private android.widget.ScrollView buildWaiting(boolean dark) {
        waiting = new android.widget.ScrollView(this);
        LinearLayout inner = Ui.column(this);
        int side = Ui.dp(this, 16);
        inner.setPadding(side, Ui.dp(this, 6), side, Ui.dp(this, 20));
        waiting.addView(inner, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout card = Ui.card(this, dark);
        stateLabel = Ui.sectionLabel(this, "Starting", dark);
        card.addView(stateLabel);
        card.addView(Ui.bold(this, agent.name, 20, Ui.text(dark)), Ui.wide(this, 4));
        card.addView(Ui.text(this,
                "The first run downloads Google's editor and this agent's own extension, once. "
                        + "The editor is about 143 MB to fetch and 702 MB once unpacked; the "
                        + "extension is a few hundred more. apt prints nothing per file, so the "
                        + "megabytes below are counted from what has actually arrived. Leave the "
                        + "phone plugged in: closing the app part way through is the thing that "
                        + "has to be repaired afterwards.", 13.5f, Ui.muted(dark)), Ui.wide(this, 8));
        inner.addView(card);

        inner.addView(Ui.sectionLabel(this, "Progress", dark), Ui.wide(this, 20));
        log = Ui.mono(this, "", 12, Ui.muted(dark));
        int pad = Ui.dp(this, 14);
        log.setPadding(pad, pad, pad, pad);
        log.setBackground(Ui.glass(this, dark, 14));
        inner.addView(log, Ui.wide(this, 8));
        return waiting;
    }

    /** Hands the screen to the editor, once there is a picture of one. */
    private void showEditor() {
        if (waiting != null) waiting.setVisibility(View.GONE);
        screen.setVisibility(View.VISIBLE);
        keys.setVisibility(View.VISIBLE);
    }

    private void listen() {
        events = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String line = intent.getStringExtra(DoorService.EXTRA_LINE);
                String state = intent.getStringExtra(DoorService.EXTRA_STATE);
                String where = intent.getStringExtra(DoorService.EXTRA_URL);
                if (line != null) {
                    status.setText(line);
                    note(line);
                }
                if ("failed".equals(state) && stateLabel != null) {
                    stateLabel.setText("STOPPED");
                    stateLabel.setTextColor(Ui.FAILED);
                }
                if ("ready".equals(state) && where != null && !where.isEmpty()) connect(where);
                if ("failed".equals(state)) showFailure(line);
            }
        };
        registerReceiver(events, new IntentFilter(DoorService.EVENT), Context.RECEIVER_NOT_EXPORTED);
    }

    /**
     * Opens the editor's screen, at whichever of the two addresses the workspace said it got.
     *
     * A unix socket in this app's private storage is the one that is wanted: Android does not
     * keep loopback apart between applications, so a display on 127.0.0.1 with no password is
     * one any other app on this phone could open and watch. The port is only the fallback for a
     * build of the display server without the socket option, and the script says which it used
     * rather than leaving this to guess.
     */
    private void connect(String where) {
        if (connector != null) return;
        // Two namespaces, and the path has to cross between them.
        //
        // The script runs inside proot and says what it sees: /var/lib/doors/display.sock. This
        // app runs outside it, where that path is nothing at all -- the file is really at
        // <files>/ubuntu/var/lib/doors/display.sock, because that is where the workspace's root
        // actually sits on the phone. Handing the container's own path to a socket call outside
        // the container is what produced "The desktop's private display socket is not ready"
        // while the socket was there and listening the whole time.
        final String socketPath = where.startsWith("unix:")
                ? new java.io.File(Ubuntu.root(this),
                        where.substring(5).replaceFirst("^/+", "")).getAbsolutePath()
                : null;
        final String host;
        final int port;
        if (socketPath == null && where.startsWith("tcp:")) {
            String[] parts = where.substring(4).split(":");
            host = parts[0];
            port = parts.length > 1 ? Integer.parseInt(parts[1]) : 5901;
        } else {
            host = "127.0.0.1";
            port = 5901;
        }

        connector = new Thread(() -> {
            // The workspace says READY the moment the editor has survived its first seconds, and
            // the display socket can appear a breath later. Retrying beats reporting a failure
            // that is only earliness.
            while (!finished) {
                VncClient attempt = new VncClient(host, port, socketPath, screen);
                client = attempt;
                screen.setClient(attempt);
                try {
                    attempt.connectAndRun();
                    if (finished) return;
                    say("The editor's screen closed.");
                } catch (IOException notYet) {
                    if (finished) return;
                    say(notYet.getMessage() == null ? "Waiting for the editor…" : notYet.getMessage());
                } catch (Throwable broken) {
                    if (finished) return;
                    say("The screen could not be drawn: " + broken.getClass().getSimpleName());
                    return;
                } finally {
                    attempt.close();
                }
                if (DoorService.runningAgent() == null) return;
                try { Thread.sleep(1200); } catch (InterruptedException stop) { return; }
            }
        }, "screen-" + agent.id);
        connector.start();
    }

    private void say(String words) {
        runOnUiThread(() -> { if (!finished) status.setText(words); });
    }

    /** Keeps the last part of what the workspace has said, and puts it on the screen. */
    private void note(String line) {
        conversation.append(line).append('\n');
        if (conversation.length() > 8000) conversation.delete(0, conversation.length() - 8000);
        if (log == null) return;
        log.setText(conversation.toString().trim());
        // The newest line is the one worth seeing; a transcript that must be scrolled to be read
        // is one nobody reads.
        if (waiting != null) waiting.post(() -> waiting.fullScroll(View.FOCUS_DOWN));
    }

    private void showFailure(String line) {
        status.setTextColor(Ui.FAILED);
        status.setText(line == null ? "This did not start." : line);
    }

    // ------------------------------------------------------------------ keys

    /**
     * One key from the row, as an X keysym.
     *
     * The editor is a real X program, so it wants keysyms rather than the Android key codes this
     * row speaks. Control is held down around the key when the row asks for it, and released
     * after, because a chord that leaves its modifier down turns every later keystroke into a
     * shortcut.
     */
    @Override public void key(int keyCode, int metaState) {
        VncClient active = client;
        int keysym = Keysyms.of(keyCode);
        if (active == null || keysym == 0) return;
        boolean control = (metaState & KeyEvent.META_CTRL_ON) != 0;
        if (control) active.sendKey(Keysyms.CONTROL_L, true);
        active.sendKey(keysym, true);
        active.sendKey(keysym, false);
        if (control) active.sendKey(Keysyms.CONTROL_L, false);
    }

    @Override public void type(String text) {
        VncClient active = client;
        if (active == null || text == null) return;
        for (int at = 0; at < text.length(); ) {
            int codePoint = text.codePointAt(at);
            int keysym = Keysyms.ofCharacter(codePoint);
            active.sendKey(keysym, true);
            active.sendKey(keysym, false);
            at += Character.charCount(codePoint);
        }
    }

    @Override protected void onDestroy() {
        finished = true;
        if (events != null) unregisterReceiver(events);
        VncClient active = client;
        if (active != null) active.close();
        if (connector != null) connector.interrupt();
        if (screen != null) screen.release();
        super.onDestroy();
    }
}
