package com.pocketide;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
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
    /** Set by the launcher shortcut: go on into the editor once Home is up, if there is one. */
    static final String EXTRA_EDITOR = "com.pocketide.editor";

    private static final int HOME = 0;
    private static final int ACTIVITY = 1;
    private static final int AGENTS = 2;
    private static final int SETTINGS = 3;

    private final List<Pane> panes = new ArrayList<>();
    private int selected = HOME;
    private FrameLayout lockRoot;
    private Pane showing;
    /**
     * Set on the way out, so that coming back refreshes the pane -- and a cold start, which
     * has no way out before its first onResume, does not build the same screen twice.
     */
    private boolean returning;
    /** Back's handler while a destination other than Home is showing; see Back. */
    private Object backToHome;
    /** What the lock runs when it comes down: a fresh pane, and no second one on resume. */
    private final Runnable unlocked = () -> {
        returning = false;
        render();
    };

    private List<Shell.Tab> tabs() {
        return Arrays.asList(
                new Shell.Tab("Home", R.drawable.ic_home,
                        "Home. Whether Linux is ready, and the phone it runs on."),
                new Shell.Tab("Activity", R.drawable.ic_pulse,
                        "Activity. What Linux and the agents are doing right now."),
                new Shell.Tab("Agents", R.drawable.ic_extension,
                        "Agents. The installed coding agents and the whole Open VSX registry."),
                new Shell.Tab("Settings", R.drawable.ic_settings,
                        "Settings. Theme, data, permissions, storage, and about this app."));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Theme.apply(this);
        buildEverything(state);
    }

    private void buildEverything(Bundle state) {
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
        continueToEditorIfAsked(getIntent());
    }

    /**
     * The launcher shortcut's request, honoured only when there is an editor to open.
     *
     * Home is drawn first either way: a locked app raises its lock there before the editor
     * can appear behind it, and a phone with nothing set up is left looking at the Set up
     * button rather than at an editor that cannot start.
     */
    private void continueToEditorIfAsked(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_EDITOR, false)) return;
        intent.removeExtra(EXTRA_EDITOR);
        if (WorkspaceService.editorRunning() || Workspace.installed(this)) {
            startActivity(new Intent(this, WorkspaceActivity.class));
        }
    }

    @Override protected void onStart() {
        super.onStart();
        // Both caught: onStart and onResume run BEFORE the first frame, so a failure in either
        // is an app that closes with nothing said -- and one that would close the recovery
        // screen the same way, since that screen goes through the same onStart.
        try {
            // From onStart, so changing the setting on the other screen reaches this one
            // without it having to be closed and opened again.
            Rotation.apply(this);
        } catch (Throwable notApplied) {
            Log.w(App.TAG, "The rotation setting could not be applied", notApplied);
        }
        try {
            // onStart, not onResume: the locked screen must be up before anything is drawn
            // that a shoulder could read, and onResume runs after the first frame.
            raiseLockIfNeeded();
        } catch (Throwable notRaised) {
            Log.w(App.TAG, "The lock could not be raised", notRaised);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (AppLock.handleResult(this, lockRoot, request, result, unlocked)) return;
    }

    private void raiseLockIfNeeded() {
        if (lockRoot != null && AppLock.isLocked(this)) {
            AppLock.show(this, lockRoot, unlocked);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int asked = intent.getIntExtra(EXTRA_TAB, -1);
        if (asked >= 0 && asked < panes.size()) {
            selected = asked;
            returning = false;
            render();
        }
        continueToEditorIfAsked(intent);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(EXTRA_TAB, selected);
    }

    /**
     * The phone's Dark theme flipped while this screen was on it.
     *
     * Every activity in this app declares uiMode in android:configChanges, so Android does NOT
     * recreate them for a theme change -- and nothing overrode this, so the app went on
     * painting the old palette while every other app on the phone flipped. It is a quick
     * settings tile, so it happens with the app fully on screen.
     *
     * Theme.apply() has to run too, not just the rebuild: setSystemBarsAppearance is sticky
     * per window, so a light-to-dark switch left the clock and battery dark on the app's own
     * near-black strip until the activity was recreated for some other reason.
     */
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Theme.apply(this);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (returning) {
            returning = false;
            // Permissions, free space and the workspace's own state all change while the
            // owner is away in the phone's own Settings, so what is showing is refreshed on
            // return rather than left showing what happened to be true when it was opened.
            // A pane that keeps itself fresh is told it is back; the rest are built again.
            // Neither happens under the lock, which rebuilds when it comes down.
            if (AppLock.showing(lockRoot)) {
                // Waiting on the fingerprint. See unlocked.
            } else if (showing != null && !showing.rebuildOnReturn()) {
                showing.shown(this);
            } else {
                render();
            }
        }
        // And the machine catches itself up, quietly, if a day has passed and the phone is on
        // Wi-Fi with nothing else running. It returns immediately when any of that is untrue,
        // which is most of the time -- see Updates.whyNotNow. Caught, both of them: neither
        // is worth the screen, and both run before the first frame on a cold start.
        try {
            Updates.maybeRunInBackground(this);
        } catch (Throwable notStarted) {
            Log.w(App.TAG, "The update check could not start", notStarted);
        }
        // And once a day, whether a newer PocketIDE has been published. A few kilobytes of
        // public text; nothing about the owner goes with it.
        try {
            AppUpdates.maybeCheckInBackground(this);
        } catch (Throwable notStarted) {
            Log.w(App.TAG, "The version check could not start", notStarted);
        }
    }

    @Override protected void onPause() {
        if (showing != null) showing.hidden(this);
        returning = true;
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
        returning = false;
        render();
    }

    /**
     * Back leaves the app from Home, and returns to Home from anywhere else.
     *
     * This method is what runs before Android 13. From 13 the same rule is registered through
     * Back, and only while it applies: on Home nothing is registered, so the phone's own
     * predictive animation out of the app is left alone.
     */
    @SuppressWarnings("deprecation") // the pre-13 path, as the comment above says
    @Override public void onBackPressed() {
        if (selected != HOME) {
            select(HOME);
            return;
        }
        super.onBackPressed();
    }

    private void syncBack() {
        boolean wanted = selected != HOME;
        if (wanted && backToHome == null) {
            backToHome = Back.register(this, () -> select(HOME));
        } else if (!wanted && backToHome != null) {
            Back.unregister(this, backToHome);
            backToHome = null;
        }
    }

    // ------------------------------------------------------------------ the frame

    private void render() {
        if (panes.isEmpty()) return;
        if (showing != null) showing.hidden(this);
        if (selected < 0 || selected >= panes.size()) selected = HOME;
        Pane pane = panes.get(selected);
        // The pane's own view goes straight into the frame, not wrapped: the frame pads a
        // ScrollView so the page can slide under the floating bar, and a wrapper would hide
        // the ScrollView from it.
        View content = pane.build(this);
        // The lock lives in a frame of its own above everything, so it covers the bars as well
        // as the pane. A lock the bottom bar sticks out from under is not a lock.
        lockRoot = new FrameLayout(this);
        lockRoot.addView(Shell.frame(this, content, tabs(), selected, this::select,
                editorAction()));
        setContentView(lockRoot);
        showing = pane;
        pane.shown(this);
        // Before the lock, so a locked app does not show its contents behind the brand frame.
        BrandFrame.openOver(this, lockRoot);
        raiseLockIfNeeded();
        syncBack();
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
        // A word beside the glyph, in a tonal pill. A bare glyph here read as decoration -- an
        // owner described it as "the code-looking thing in the top right corner" and did not
        // know it opened anything -- and a circle around the same glyph did not fix that.
        return Ui.tonalButton(this, dark, R.drawable.ic_code, "Editor", "Open the editor",
                v -> startActivity(new Intent(this, WorkspaceActivity.class)));
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
