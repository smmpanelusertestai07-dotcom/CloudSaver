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
 * Four destinations, and the editor is not one of them.
 *
 * That is Material's rule rather than a preference: navigation bars belong to primary pages and
 * toolbars to the pages reached from them, and the two must never share a screen. Visual Studio
 * Code at phone width also needs every pixel it can get -- a bar across the bottom would take
 * sixty-four of them from the terminal and put a row of app chrome between the owner and the
 * thing they opened the app for. So the editor is a full-screen window with its own toolbar and
 * no navigation bar, reached from the button on Home and from the action in the top bar, which
 * appears on every destination the moment the workspace is running. One tap from anywhere,
 * which is what a tab would have given, without breaking the rule to get it.
 *
 * Order is by how often a destination is wanted: is it ready, what is it doing, what is
 * installed, everything else.
 */
public final class MainActivity extends Activity {

    static final String EXTRA_TAB = "com.pocketide.tab";

    private static final int HOME = 0;
    private static final int ACTIVITY = 1;
    private static final int AGENTS = 2;
    private static final int SETTINGS = 3;

    private final List<Pane> panes = new ArrayList<>();
    private int selected = HOME;
    private FrameLayout slot;
    private FrameLayout lockRoot;
    private Pane showing;

    private List<Shell.Tab> tabs() {
        return Arrays.asList(
                new Shell.Tab("Home", R.drawable.ic_home,
                        "Home. Whether the workspace is ready, and the phone it runs on."),
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
        panes.add(new ActivityPane());
        panes.add(new AgentsPane());
        panes.add(new SettingsPane());

        if (state != null) selected = state.getInt(EXTRA_TAB, HOME);
        int asked = getIntent().getIntExtra(EXTRA_TAB, -1);
        if (asked >= 0 && asked < panes.size()) selected = asked;

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
        if (asked >= 0 && asked < panes.size()) {
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
        if (selected < 0 || selected >= panes.size()) selected = HOME;
        Pane pane = panes.get(selected);
        slot = new FrameLayout(this);
        View content = pane.build(this);
        slot.addView(content);
        // The lock lives in a frame of its own above everything, so it covers the bars as well
        // as the pane. A lock the bottom bar sticks out from under is not a lock.
        lockRoot = new FrameLayout(this);
        lockRoot.addView(Shell.frame(this, slot, tabs(), selected, this::select, editorAction()));
        setContentView(lockRoot);
        showing = pane;
        pane.shown(this);
        raiseLockIfNeeded();
    }

    private void select(int index) {
        selected = index;
        render();
    }

    /**
     * The one action in the top bar: open the editor, shown only once there is one to open.
     *
     * A control that is present but does nothing teaches an owner to distrust the whole bar, so
     * before the workspace exists there is simply nothing here and the primary button on Home
     * is the only way forward.
     */
    private View editorAction() {
        if (!WorkspaceService.editorRunning() && !Workspace.installed(this)) return null;
        boolean dark = Ui.dark(this);
        android.widget.ImageView open = new android.widget.ImageView(this);
        open.setImageResource(R.drawable.ic_code);
        open.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.accent(dark)));
        int target = Ui.dp(this, Ui.TOUCH_TARGET_DP);
        int pad = Ui.dp(this, 12);
        open.setPadding(pad, pad, pad, pad);
        open.setBackground(Ui.tappable(this,
                Ui.fill(this, android.graphics.Color.TRANSPARENT, 999), dark));
        open.setClickable(true);
        open.setFocusable(true);
        open.setContentDescription("Open the editor");
        open.setOnClickListener(v -> startActivity(new Intent(this, WorkspaceActivity.class)));
        open.setLayoutParams(new android.widget.LinearLayout.LayoutParams(target, target));
        return open;
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
