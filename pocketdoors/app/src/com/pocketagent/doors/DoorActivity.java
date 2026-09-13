package com.pocketagent.doors;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.KeyEvent;
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

        screen = new VncView(this);
        screen.setStateListener((text, connected) -> runOnUiThread(() -> {
            if (connected) {
                status.setTextColor(Ui.muted(Ui.dark(this)));
                status.setText(agent.name + " · running on this phone");
            } else if (text != null && !text.isEmpty()) {
                status.setText(text);
            }
        }));
        root.addView(screen, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(Ui.divider(this, dark), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1))));
        keys = new KeyBar(this, this);
        root.addView(keys, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        listen();
        DoorService.start(this, agent.id);
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
        final String socketPath = where.startsWith("unix:") ? where.substring(5) : null;
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

    /** Keeps the last part of what the workspace has said, which is all a phone screen holds. */
    private void note(String line) {
        conversation.append(line).append('\n');
        if (conversation.length() > 8000) conversation.delete(0, conversation.length() - 8000);
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
