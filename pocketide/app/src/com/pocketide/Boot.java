package com.pocketide;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Makes it impossible for this app to disappear at startup without saying why.
 *
 * An owner reported the worst shape a bug can take: "app open karte hi apne aap close ho raha"
 * -- it closes the moment it opens, every time. That report is almost unactionable on a
 * sideloaded app, because there is no store console, no crash upload and nothing on screen: the
 * one process that could have explained it is the one that died. The owner is left tapping an
 * icon that flashes and vanishes.
 *
 * Two things here make that a solved problem rather than a guess.
 *
 * FIRST, a counter that survives being killed. The count of launches is written -- with
 * commit(), synchronously, before anything is built -- and set back to zero once a frame has
 * actually reached the screen. A launch that dies in between leaves its count behind. Three in a
 * row and the app stops trying to open normally and opens this screen instead, which states what
 * happened, shows whatever the crash handler recorded, and offers the two repairs that do not
 * need a working app: reset the settings, or remove the workspace.
 *
 * SECOND, the normal path is wrapped. Anything thrown while the first screen is being built is
 * caught, recorded, and shown here -- so even the very first failure is a readable screen with a
 * Copy button instead of a window that closes.
 *
 * The counter is deliberately not reset by merely reaching onCreate. It is reset from a post()
 * on the decor view, which runs after the first traversal, so "it started" means a frame was
 * drawn and not merely that no exception had been thrown yet.
 */
final class Boot {

    /** Launches begun since one last finished. Written synchronously; see the class note. */
    private static final String ATTEMPTS = "boot_attempts";
    /** How far the last opening got: application, home, built, drawn. Written synchronously. */
    private static final String STAGE = "boot_stage";

    /** Three strikes: two silent deaths are a pattern, and the third opening is the repair. */
    private static final int GIVE_UP_AFTER = 3;

    /** Counted once per process, however many screens ask. */
    private static boolean counted;
    /** Where the opening BEFORE this one got to, read before this one overwrites it. */
    private static String previousStage = "";

    private Boot() {}

    /**
     * Called first thing when the process starts (App.onCreate), and again, harmlessly, by
     * the first screen: the count is taken once, before any view exists and before anything
     * that could fail.
     */
    static void starting(Context context) {
        if (counted) return;
        counted = true;
        SharedPreferences prefs = Prefs.of(context);
        previousStage = prefs.getString(STAGE, "");
        int attempts = prefs.getInt(ATTEMPTS, 0) + 1;
        // commit(), not apply(). A process killed a hundred milliseconds from now must still
        // find this number on disk, and apply() only promises to get there eventually.
        prefs.edit().putInt(ATTEMPTS, attempts).putString(STAGE, "application").commit();
    }

    /**
     * A milestone on the way to the first frame, written synchronously for the same reason
     * as the count: an opening that dies leaves behind how far it got, and "it got as far as
     * building the screen" narrows the search from the whole app to one method.
     */
    static void mark(Context context, String stage) {
        try {
            Prefs.of(context).edit().putString(STAGE, stage).commit();
        } catch (Throwable unwritable) {
            // The mark is a diagnostic, never the thing that fails.
        }
    }

    /** Called once a frame has been drawn, which is the only honest definition of "it opened". */
    static void reached(Context context) {
        SharedPreferences prefs = Prefs.of(context);
        prefs.edit().putInt(ATTEMPTS, 0).putString(STAGE, "drawn").apply();
    }

    /** True when the last two openings died before drawing anything. */
    static boolean failing(Context context) {
        return Prefs.of(context).getInt(ATTEMPTS, 0) >= GIVE_UP_AFTER;
    }

    static void forgive(Context context) {
        Prefs.of(context).edit().putInt(ATTEMPTS, 0).commit();
    }

    // ------------------------------------------------------------------ the screen

    /**
     * The recovery screen, built without touching anything that could be the cause.
     *
     * No panes, no workspace probe, no free-space walk, no permissions, no theme lookup beyond
     * the one colour it paints with. If the normal screen cannot be built, a recovery screen
     * that rebuilds half of it is not a recovery screen.
     */
    static void show(final Activity activity, Throwable failure) {
        boolean readTheme = true;
        try {
            readTheme = Ui.dark(activity);
        } catch (Throwable evenThat) {
            // A theme lookup should not be able to fail, and if it has, dark is a safe ground
            // to paint a failure on.
        }
        final boolean dark = readTheme;
        if (failure != null) Crash.save(activity, failure);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Ui.bg(dark));
        int pad = Ui.dp(activity, 24);
        column.setPadding(pad, pad, pad, pad);

        column.addView(Ui.bold(activity, "PocketIDE did not open", 22f, Ui.text(dark)));
        column.addView(Ui.text(activity,
                failure != null
                        ? "Something went wrong while this screen was being built. Nothing in "
                                + "Linux was touched — it and your projects live in their own "
                                + "storage and are exactly as you left them."
                        : "The app closed twice while opening, so it has stopped trying and "
                                + "opened this instead. Nothing in Linux was touched — it and "
                                + "your projects are exactly as you left them.",
                14.5f, Ui.muted(dark)), Ui.wide(activity, 12));

        final String record = details(activity, failure);
        if (!record.isEmpty()) {
            TextView block = Ui.mono(activity, record, 11.5f, Ui.muted(dark));
            int blockPad = Ui.dp(activity, 12);
            block.setPadding(blockPad, blockPad, blockPad, blockPad);
            block.setBackground(Ui.fill(activity, dark ? android.graphics.Color.rgb(18, 18, 18)
                    : android.graphics.Color.rgb(246, 244, 238), 12));
            block.setTextIsSelectable(true);
            column.addView(block, Ui.wide(activity, 16));
        }

        TextView again = Ui.primaryButton(activity, "Try opening it again", dark);
        again.setOnClickListener(v -> {
            forgive(activity);
            Crash.clear(activity);
            restart(activity);
        });
        column.addView(again, Ui.wide(activity, 20));

        if (!record.isEmpty()) {
            TextView copy = Ui.button(activity, "Copy the details", false, dark);
            copy.setOnClickListener(v -> {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager)
                                activity.getSystemService(Activity.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("PocketIDE", record));
                    android.widget.Toast.makeText(activity, "Copied",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            });
            column.addView(copy, Ui.wide(activity, 10));
        }

        TextView reset = Ui.button(activity, "Reset the app's settings", false, dark);
        reset.setOnClickListener(v -> confirmReset(activity, dark));
        column.addView(reset, Ui.wide(activity, 10));

        column.addView(Ui.text(activity,
                "Resetting settings puts the theme, the permissions this app remembers and the "
                        + "update schedule back to new. It does not delete Linux, the editor, "
                        + "the extensions or your projects.",
                12f, Ui.muted(dark)), Ui.wide(activity, 10));

        ScrollView page = new ScrollView(activity);
        page.setBackgroundColor(Ui.bg(dark));
        page.setFillViewport(true);
        page.addView(column, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        // Its own insets, because the frame that normally handles them is the thing that
        // could not be built. The page is passed as BOTH bars: fitScreen writes the top inset
        // first and the bottom one second, so one view takes both. Passing null for the bottom
        // gave this screen no gesture-bar padding at all and put its last button underneath it.
        Theme.fitScreen(page, page);
        activity.setContentView(page);
    }

    /**
     * Wipes what the app remembers, keeping what describes Linux.
     *
     * INSTALLED and the editor's password are kept deliberately. Clearing them would leave a
     * complete, working Ubuntu on the disk that the app believes is not there, and the only way
     * an owner could get back to it would be to download all of it again.
     */
    private static void confirmReset(final Activity activity, boolean dark) {
        Dialogs.confirm(activity, "Reset the app's settings?",
                "Everything this app remembers goes back to new: the theme, the permissions it "
                        + "has recorded, the data limit and the update schedule.\n\n"
                        + "Linux, the editor, the extensions and your projects are not touched.",
                "Reset", true, () -> {
                    SharedPreferences prefs = Prefs.of(activity);
                    boolean installed = prefs.getBoolean(Prefs.INSTALLED, false);
                    String password = prefs.getString(Prefs.EDITOR_PASSWORD, "");
                    prefs.edit().clear()
                            .putBoolean(Prefs.INSTALLED, installed)
                            .putString(Prefs.EDITOR_PASSWORD, password)
                            .commit();
                    Crash.clear(activity);
                    restart(activity);
                });
    }

    private static void restart(Activity activity) {
        Intent again = new Intent(activity, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(again);
        activity.finish();
    }

    /**
     * What went wrong, as much of it as there is, with anything secret blanked out.
     *
     * Three sources, because each catches what the others miss: the failure this screen was
     * given, the last one the crash handler wrote down, and what Android itself recorded
     * about the last exits -- which is the only account there is of a process the system
     * killed rather than one that threw, and names the killer.
     */
    private static String details(Context context, Throwable failure) {
        StringBuilder all = new StringBuilder();
        all.append("PocketIDE ").append(BuildFacts.VERSION_NAME)
                .append(" · Android ").append(android.os.Build.VERSION.SDK_INT)
                .append(" · ").append(android.os.Build.MANUFACTURER)
                .append(' ').append(android.os.Build.MODEL).append('\n');
        if (!previousStage.isEmpty()) {
            all.append("The last opening got as far as: ").append(previousStage)
                    .append(" (application → home → built → drawn)\n");
        }
        try {
            String exits = Exits.recent(context);
            if (!exits.isEmpty()) all.append("Android's record of the last exits:\n").append(exits);
        } catch (Throwable unreadable) {
            // Android 11 and later only, and not worth failing the screen over.
        }
        if (failure != null) {
            java.io.StringWriter writer = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(writer));
            all.append('\n').append(writer);
        }
        try {
            String recorded = Crash.read(context);
            if (recorded != null && !recorded.isEmpty()) all.append('\n').append(recorded);
        } catch (Throwable unreadable) {
            // The record is a convenience here, not the point. The header above is already
            // enough to say which build on which phone.
        }
        return Redact.secrets(all.toString());
    }
}
