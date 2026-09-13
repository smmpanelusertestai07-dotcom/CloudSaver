package com.pocketagent.doors;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The keys a phone keyboard does not have, above the one it does.
 *
 * Microsoft say plainly in their own issues that VS Code on the web assumes a mouse and a
 * physical keyboard: Escape cannot be reached, the function keys are absent, and a good deal
 * of the interface only appears on hover. That is the single biggest thing standing between
 * this app and a usable editor on a phone, and it is also the one thing a native shell can
 * fix without touching anybody's interface.
 *
 * So this is the whole of PocketAgent's contribution to the editor: a row of keys, sent to the
 * page as real key events. Tab and Escape first because they are what an agent's prompt needs
 * most; the slash and the at-sign because that is how every one of these agents is commanded.
 */
final class KeyBar extends HorizontalScrollView {

    interface Sender {
        /** Send one key to whatever has focus. */
        void key(int keyCode, int metaState);
        /** Type one character, for keys that are really just text. */
        void type(String text);
    }

    private static final class Key {
        final String label;
        final int code;
        final int meta;
        final String text;

        Key(String label, int code, int meta) { this(label, code, meta, null); }
        Key(String label, String text) { this(label, 0, 0, text); }
        Key(String label, int code, int meta, String text) {
            this.label = label; this.code = code; this.meta = meta; this.text = text;
        }
    }

    private static final Key[] KEYS = {
            new Key("esc", KeyEvent.KEYCODE_ESCAPE, 0),
            new Key("tab", KeyEvent.KEYCODE_TAB, 0),
            new Key("/", "/"),
            new Key("@", "@"),
            new Key("←", KeyEvent.KEYCODE_DPAD_LEFT, 0),
            new Key("→", KeyEvent.KEYCODE_DPAD_RIGHT, 0),
            new Key("↑", KeyEvent.KEYCODE_DPAD_UP, 0),
            new Key("↓", KeyEvent.KEYCODE_DPAD_DOWN, 0),
            new Key("^C", KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON),
            new Key("^V", KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON),
            new Key("^S", KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON),
            new Key("^`", KeyEvent.KEYCODE_GRAVE, KeyEvent.META_CTRL_ON),
            new Key("|", "|"),
            new Key("~", "~"),
            new Key("-", "-"),
            new Key("_", "_"),
    };

    KeyBar(Context context, Sender sender) {
        super(context);
        boolean dark = Ui.dark(context);
        setHorizontalScrollBarEnabled(false);
        setBackgroundColor(Ui.card(dark));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(context, 6);
        row.setPadding(pad, pad, pad, pad);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        for (Key key : KEYS) row.addView(build(context, key, sender, dark), params(context));
    }

    private LinearLayout.LayoutParams params(Context context) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(context, 40));
        lp.rightMargin = Ui.dp(context, 6);
        return lp;
    }

    private View build(Context context, Key key, Sender sender, boolean dark) {
        TextView view = Ui.mono(context, key.label, 14.5f, Ui.text(dark));
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(Ui.dp(context, 44));
        int padX = Ui.dp(context, 10);
        view.setPadding(padX, 0, padX, 0);
        view.setBackground(Ui.tappable(context,
                Ui.outlined(context, Ui.bg(dark), Ui.line(dark), 8), dark));
        view.setClickable(true);
        view.setFocusable(true);
        view.setContentDescription(key.label);
        view.setOnClickListener(v -> {
            // A tap on a key should feel like a key, not like a button on a web page.
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            if (key.text != null) sender.type(key.text);
            else sender.key(key.code, key.meta);
        });
        return view;
    }
}
