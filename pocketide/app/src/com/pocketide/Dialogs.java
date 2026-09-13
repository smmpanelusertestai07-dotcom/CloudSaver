package com.pocketide;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Dialogs that belong to this app rather than to whichever Android version is underneath.
 *
 * The platform's own AlertDialog is a grey box with blue buttons that matches nothing else on
 * these screens, and on some manufacturer skins it is a different grey box with different
 * buttons. Building the body view keeps a message, a choice and a confirmation looking like the
 * rest of the app on every phone -- and costs one file, which is the same trade the whole app
 * makes by not carrying a components library.
 */
final class Dialogs {

    interface Picked { void index(int which); }

    private Dialogs() {}

    /** A message with one button. Used when there is nothing to decide. */
    static void message(Activity activity, String title, CharSequence body) {
        boolean dark = Ui.dark(activity);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(body(activity, dark, title, body))
                .setPositiveButton("OK", null)
                .create();
        show(activity, dialog, dark);
    }

    /** A question with a named action, so the button says what it does rather than "OK". */
    static void confirm(Activity activity, String title, CharSequence body, String action,
                        Runnable onConfirmed) {
        confirm(activity, title, body, action, false, onConfirmed);
    }

    static void confirm(Activity activity, String title, CharSequence body, String action,
                        boolean destructive, Runnable onConfirmed) {
        boolean dark = Ui.dark(activity);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(body(activity, dark, title, body))
                .setNegativeButton("Cancel", null)
                .setPositiveButton(action, (d, which) -> onConfirmed.run())
                .create();
        show(activity, dialog, dark);
        TextView positive = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (positive != null && destructive) positive.setTextColor(Ui.FAILED);
    }

    /**
     * A list of choices with the current one marked.
     *
     * Rows rather than a platform single-choice list, because the platform's radio rows are
     * 40 dp tall on some skins and this app does not ask a finger to hit anything under 48.
     */
    static void choose(Activity activity, String title, String[] labels, int[] icons,
                       int selected, Picked picked) {
        boolean dark = Ui.dark(activity);
        LinearLayout column = Ui.column(activity);
        int pad = Ui.dp(activity, 20);
        column.setPadding(pad, pad, pad, Ui.dp(activity, 8));
        column.addView(Ui.bold(activity, title, 18, Ui.text(dark)));

        LinearLayout list = Ui.column(activity);
        list.setBackground(Ui.glass(activity, dark, 16));
        LinearLayout.LayoutParams listParams = Ui.wide(activity, 14);
        column.addView(list, listParams);

        final AlertDialog[] holder = new AlertDialog[1];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            int iconRes = icons != null && icons.length > i ? icons[i] : 0;
            Ui.Row row = Ui.row(activity, dark, iconRes, labels[i], null, v -> {
                if (holder[0] != null) holder[0].dismiss();
                picked.index(index);
            });
            if (i == selected) {
                row.chevron.setVisibility(View.VISIBLE);
                row.chevron.setImageResource(R.drawable.ic_check);
                row.chevron.setImageTintList(ColorStateList.valueOf(Ui.accent(dark)));
                row.title.setTextColor(Ui.accent(dark));
            }
            if (i > 0) list.addView(Ui.divider(activity, dark, true));
            list.addView(row);
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(column);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .create();
        holder[0] = dialog;
        show(activity, dialog, dark);
    }

    /**
     * A dialog that shows work happening, line by line, and cannot be dismissed until it ends.
     *
     * Used for installing the browser and the build tools, which take minutes and hundreds of
     * megabytes. A spinner would say nothing; apt's own output says which package is being
     * fetched, and that is the difference between waiting and wondering whether it has hung.
     *
     * Not cancellable on purpose: interrupting apt half way through leaves dpkg in a state the
     * next attempt has to repair, and this app already carries a repair path for exactly that
     * because an earlier version let it happen.
     */
    static Live live(Activity activity, String title, String subtitle) {
        boolean dark = Ui.dark(activity);
        LinearLayout column = Ui.column(activity);
        int pad = Ui.dp(activity, 20);
        column.setPadding(pad, pad, pad, pad);
        column.addView(Ui.bold(activity, title, 18, Ui.text(dark)));
        TextView note = Ui.text(activity, subtitle, 14f, Ui.muted(dark));
        column.addView(note, Ui.wide(activity, 8));

        TextView output = Ui.mono(activity, "", 11.5f, Ui.muted(dark));
        int blockPad = Ui.dp(activity, 12);
        output.setPadding(blockPad, blockPad, blockPad, blockPad);
        output.setBackground(Ui.fill(activity, dark ? Color.rgb(18, 18, 18)
                : Color.rgb(246, 244, 238), 12));
        column.addView(output, Ui.wide(activity, 14));

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(column);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(scroll)
                .setCancelable(false)
                .create();
        show(activity, dialog, dark);
        return new Live(activity, dialog, note, output, scroll);
    }

    /** The handle the caller writes lines to, from whatever thread it is working on. */
    static final class Live {
        private final Activity activity;
        private final AlertDialog dialog;
        private final TextView note;
        private final TextView output;
        private final ScrollView scroll;
        private final StringBuilder lines = new StringBuilder();

        Live(Activity activity, AlertDialog dialog, TextView note, TextView output,
             ScrollView scroll) {
            this.activity = activity;
            this.dialog = dialog;
            this.note = note;
            this.output = output;
            this.scroll = scroll;
        }

        /** Safe to call from a background thread; the work here is not on one. */
        void line(String text) {
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                if (lines.length() > 0) lines.append('\n');
                lines.append(text);
                // The last twenty lines. apt prints hundreds and a dialog that grows without
                // limit pushes its own buttons off the bottom of the screen.
                String[] all = lines.toString().split("\n");
                int from = Math.max(0, all.length - 20);
                StringBuilder shown = new StringBuilder();
                for (int i = from; i < all.length; i++) {
                    if (shown.length() > 0) shown.append('\n');
                    shown.append(all[i]);
                }
                output.setText(shown.toString());
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            });
        }

        /** Ends it: the dialog becomes dismissible and says how it went. */
        void done(boolean ok, String message) {
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) {
                    dialog.dismiss();
                    return;
                }
                note.setText(message);
                note.setTextColor(ok ? Ui.RUNNING : Ui.FAILED);
                dialog.setCancelable(true);
                dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Done", (d, which) -> d.dismiss());
                // setButton after show() needs the button re-laid out, which re-showing does.
                dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                try {
                    dialog.show();
                } catch (Throwable alreadyGone) {
                    // The window went away while work was running. Nothing to show it on.
                }
            });
        }
    }

    /** A long body with a monospace block under it, for raw technical output. */
    static void details(Activity activity, String title, CharSequence explanation, String raw,
                        String copyLabel) {
        boolean dark = Ui.dark(activity);
        LinearLayout column = Ui.column(activity);
        int pad = Ui.dp(activity, 20);
        column.setPadding(pad, pad, pad, Ui.dp(activity, 8));
        column.addView(Ui.bold(activity, title, 18, Ui.text(dark)));
        if (explanation != null && explanation.length() > 0) {
            column.addView(Ui.text(activity, explanation, 14.5f, Ui.muted(dark)),
                    Ui.wide(activity, 10));
        }
        if (raw != null && !raw.isEmpty()) {
            TextView block = Ui.mono(activity, raw, 11.5f, Ui.muted(dark));
            int blockPad = Ui.dp(activity, 12);
            block.setPadding(blockPad, blockPad, blockPad, blockPad);
            block.setBackground(Ui.fill(activity, dark ? Color.rgb(18, 18, 18)
                    : Color.rgb(246, 244, 238), 12));
            block.setTextIsSelectable(true);
            column.addView(block, Ui.wide(activity, 14));
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(column);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setView(scroll)
                .setNegativeButton("Close", null);
        if (raw != null && !raw.isEmpty()) {
            builder.setPositiveButton(copyLabel == null ? "Copy" : copyLabel, (d, which) -> {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager)
                                activity.getSystemService(Activity.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("PocketIDE", raw));
                    android.widget.Toast.makeText(activity, "Copied",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }
        show(activity, builder.create(), dark);
    }

    // ------------------------------------------------------------------ internals

    private static View body(Activity activity, boolean dark, String title, CharSequence text) {
        LinearLayout column = Ui.column(activity);
        int pad = Ui.dp(activity, 20);
        column.setPadding(pad, pad, pad, Ui.dp(activity, 4));
        if (title != null && !title.isEmpty()) {
            column.addView(Ui.bold(activity, title, 18, Ui.text(dark)));
        }
        TextView body = Ui.text(activity, text, 14.5f, Ui.muted(dark));
        column.addView(body, Ui.wide(activity, title == null || title.isEmpty() ? 0 : 10));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(column);
        return scroll;
    }

    private static void show(Activity activity, AlertDialog dialog, boolean dark) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        dialog.show();
        View decor = dialog.getWindow() == null ? null : dialog.getWindow().getDecorView();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(Ui.fill(activity, Ui.card(dark), 24));
            dialog.getWindow().setDimAmount(0.5f);
        }
        for (int which : new int[]{DialogInterface.BUTTON_POSITIVE,
                DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL}) {
            View button = dialog.getButton(which);
            if (button instanceof TextView) {
                TextView view = (TextView) button;
                view.setTextColor(Ui.accent(dark));
                view.setAllCaps(false);
                view.setTextSize(15f);
                view.setMinHeight(Ui.dp(activity, Ui.TOUCH_TARGET_DP));
            }
        }
        if (decor != null) decor.setBackgroundColor(Color.TRANSPARENT);
    }
}
