package com.pocketide;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The app's one home: a bar along the bottom, a pane in the middle, the name and mark on top.
 *
 * Four destinations live here. The fifth -- the editor -- is not a pane and deliberately so.
 * Visual Studio Code at phone width needs every pixel of the screen, and a navigation bar
 * across the bottom of it would take eighty of them from the terminal and put a row of app
 * chrome between the owner and the thing they opened the app to use. So Editor starts its own
 * full-screen window, and coming back lands here on the destination that was showing before.
 *
 * Order is by how often a destination is wanted, which is also the order they were listed in
 * when the app was described: what is my phone doing, take me to the editor, what is running
 * right now, which agents are installed, everything else.
 */
public final class MainActivity extends Activity {

    static final String EXTRA_TAB = "com.pocketide.tab";

    private static final int HOME = 0;
    private static final int EDITOR = 1;
    private static final int ACTIVITY = 2;
    private static final int AGENTS = 3;
    private static final int SETTINGS = 4;

    private final List<Pane> panes = new ArrayList<>();
    private int selected = HOME;
    private FrameLayout slot;
    private FrameLayout lockRoot;
    private Pane showing;

    private List<Shell.Tab> tabs() {
        return Arrays.asList(
                new Shell.Tab("Home", R.drawable.ic_home,
                        "Home. Whether the workspace is ready, and the phone it runs on."),
                new Shell.Tab("Editor", R.drawable.ic_code,
                        "Editor. Opens Visual Studio Code full screen."),
                new Shell.Tab("Activity", R.drawable.ic_pulse,
                        "Activity. What the workspace and the agents are doing right now."),
                new Shell.Tab("Agents", R.drawable.ic_extension,
                        "Agents. The installed coding agents and the whole Open VSX registry."),
                new Shell.Tab("Settings", R.drawable.ic_settings,
                        "Settings. Theme, data, permissions, storage, and about this app."));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Theme.apply(this);
        panes.clear();
        panes.add(new HomePane());
        panes.add(null);                 // Editor is a window, not a pane. See the note above.
        panes.add(new ActivityPane());
        panes.add(new AgentsPane());
        panes.add(new SettingsPane());

        if (state != null) selected = state.getInt(EXTRA_TAB, HOME);
        int asked = getIntent().getIntExtra(EXTRA_TAB, -1);
        if (asked >= 0 && asked < panes.size() && asked != EDITOR) selected = asked;

        AppLock.applyWindowSecurity(this);
        render();
    }

    @Override protected void onStart() {
        super.onStart();
        // onStart, not onResume: the locked screen must be up before anything is drawn that a
        // shoulder could read, and onResume runs after the first frame.
        raiseLockIfNeeded();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (AppLock.handleResult(this, lockRoot, request, result, this::render)) return;
    }

    private void raiseLockIfNeeded() {
        if (lockRoot != null && AppLock.isLocked(this)) {
            AppLock.show(this, lockRoot, this::render);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int asked = intent.getIntExtra(EXTRA_TAB, -1);
        if (asked >= 0 && asked < panes.size() && asked != EDITOR) {
            selected = asked;
            render();
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(EXTRA_TAB, selected);
    }

    @Override protected void onResume() {
        super.onResume();
        // Permissions, free space and the workspace's own state all change while the owner is
        // away in the phone's own Settings, so the pane is rebuilt on return rather than left
        // showing what happened to be true when it was opened.
        render();
    }

    @Override protected void onPause() {
        if (showing != null) showing.hidden(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (showing != null) {
            showing.hidden(this);
            showing = null;
        }
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] granted) {
        super.onRequestPermissionsResult(code, permissions, granted);
        render();
    }

    /** Back leaves the app from Home, and returns to Home from anywhere else. */
    @Override public void onBackPressed() {
        if (selected != HOME) {
            select(HOME);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ the frame

    private void render() {
        if (showing != null) showing.hidden(this);
        // select() and both intent paths already refuse to leave EDITOR selected, because it has
        // no pane. Belt and braces: a null here would be a blank screen with a bar under it,
        // which is the kind of fault that reaches a phone rather than a review.
        if (selected == EDITOR || selected < 0 || selected >= panes.size()) selected = HOME;
        Pane pane = panes.get(selected);
        slot = new FrameLayout(this);
        View content = pane.build(this);
        slot.addView(content);
        // The lock lives in a frame of its own above everything, so it covers the bars as well
        // as the pane. A lock the bottom bar sticks out from under is not a lock.
        lockRoot = new FrameLayout(this);
        lockRoot.addView(Shell.frame(this, slot, tabs(), selected, this::select, null));
        setContentView(lockRoot);
        showing = pane;
        pane.shown(this);
        raiseLockIfNeeded();
    }

    private void select(int index) {
        if (index == EDITOR) {
            // Not a pane: its own window, with the bars gone.
            startActivity(new Intent(this, WorkspaceActivity.class));
            return;
        }
        selected = index;
        render();
    }

    /**
     * Redraws the pane that is showing.
     *
     * A pane changes something -- a setting toggled, an extension installed -- and needs the
     * screen to reflect it. It used to call setContentView(build()) on itself, which it can no
     * longer do because it is not the Activity. It asks here instead, and the bar is redrawn
     * with it rather than being left behind pointing at the wrong destination.
     */
    static void rebuild(Activity host) {
        if (host instanceof MainActivity) ((MainActivity) host).render();
    }

    /** Lets a pane send the owner somewhere else without knowing how the bar works. */
    static void open(Activity from, String destination) {
        Intent intent = new Intent(from, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_TAB, index(destination));
        from.startActivity(intent);
    }

    private static int index(String destination) {
        switch (destination) {
            case "activity": return ACTIVITY;
            case "agents": return AGENTS;
            case "settings": return SETTINGS;
            default: return HOME;
        }
    }
}
