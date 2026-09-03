package com.pocketdesk;

import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * The invisible text field the phone keyboard types into; every character goes to Linux.
 *
 * Keyboards do not all type the same way. Some commit each letter as it is pressed; many
 * (Gboard with suggestions, Samsung, SwiftKey) hold the current word as "composing" text and
 * rewrite it with every keystroke, committing only at the space. The old version ignored
 * composing text entirely, so on those keyboards nothing appeared until the word ended -- and a
 * correction picked from the suggestion strip could never reach Linux at all. Composing text is
 * now mirrored: what changed since the last update is sent as backspaces and new letters, so
 * what is on the Linux screen is always what the keyboard shows.
 */
final class KeyboardInputView extends View {
    interface Listener {
        void typeCodePoint(int codePoint);
        void specialKey(int keysym);
    }

    private Listener listener;
    /** The word the keyboard is still writing, as far as Linux has seen it. */
    private String composing = "";

    KeyboardInputView(Context context) {
        super(context);
        initialize();
    }

    KeyboardInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    private void initialize() {
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    void setListener(Listener listener) { this.listener = listener; }

    @Override public boolean onCheckIsTextEditor() { return true; }

    @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // VISIBLE_PASSWORD asks the keyboard for plain letters without auto-correction: what
        // is typed is what Linux gets, which matters in a terminal and a code editor alike.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_NONE;
        composing = "";
        return new BaseInputConnection(this, false) {
            @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                replaceComposing(text == null ? "" : text.toString());
                composing = "";
                return true;
            }

            @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
                replaceComposing(text == null ? "" : text.toString());
                return true;
            }

            @Override public boolean finishComposingText() {
                composing = "";
                return true;
            }

            @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (listener == null) return true;
                // The keyboard deletes what it can see, which for it is only the composing
                // word; anything beyond that is a backspace on the Linux side per character.
                if (!composing.isEmpty()) {
                    int keep = Math.max(0, composing.length() - beforeLength);
                    beforeLength -= composing.length() - keep;
                    replaceComposing(composing.substring(0, keep));
                    composing = keep == 0 ? "" : composing;
                }
                for (int i = 0; i < beforeLength; i++) listener.specialKey(0xff08);
                for (int i = 0; i < afterLength; i++) listener.specialKey(0xffff);
                return true;
            }

            @Override public boolean performEditorAction(int actionCode) {
                if (listener != null) listener.specialKey(0xff0d);
                return true;
            }

            @Override public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN || listener == null) return true;
                int special = mapAndroidKey(event.getKeyCode());
                if (special != 0) {
                    // Backspace shortens the word the keyboard is still writing; any other
                    // special key ends it.
                    if (special == 0xff08 && !composing.isEmpty()) {
                        composing = composing.substring(0, composing.offsetByCodePoints(composing.length(), -1));
                    } else if (special != 0xff08) {
                        composing = "";
                    }
                    listener.specialKey(special);
                } else {
                    int unicode = event.getUnicodeChar();
                    if (unicode != 0) listener.typeCodePoint(unicode);
                }
                return true;
            }
        };
    }

    /**
     * Makes the Linux side show {@code next} where it currently shows {@link #composing}:
     * backspace over the part that differs, then type the new tail.
     */
    private void replaceComposing(String next) {
        if (listener == null) { composing = next; return; }
        int common = 0;
        int limit = Math.min(composing.length(), next.length());
        while (common < limit && composing.charAt(common) == next.charAt(common)) common++;
        // Never split a surrogate pair: back up to the start of the code point.
        if (common > 0 && common < composing.length()
                && Character.isLowSurrogate(composing.charAt(common))) {
            common--;
        }
        int erase = composing.codePointCount(common, composing.length());
        for (int i = 0; i < erase; i++) listener.specialKey(0xff08);
        sendText(next.substring(common));
        composing = next;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (listener == null) return super.onKeyDown(keyCode, event);
        int special = mapAndroidKey(keyCode);
        if (special != 0) {
            listener.specialKey(special);
            return true;
        }
        int unicode = event.getUnicodeChar();
        if (unicode != 0) {
            listener.typeCodePoint(unicode);
            return true;
        }
        // Anything this field cannot type is the phone's: the volume rocker especially, which
        // this view used to swallow the moment the keyboard button was tapped.
        return super.onKeyDown(keyCode, event);
    }

    private void sendText(CharSequence text) {
        if (text == null || listener == null) return;
        for (int offset = 0; offset < text.length();) {
            int codePoint = Character.codePointAt(text, offset);
            // The keyboard's Enter arrives as a newline character on some keyboards.
            if (codePoint == '\n') listener.specialKey(0xff0d);
            else listener.typeCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
    }

    private static int mapAndroidKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL: return 0xff08;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 0xffff;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return 0xff0d;
            case KeyEvent.KEYCODE_TAB: return 0xff09;
            case KeyEvent.KEYCODE_ESCAPE: return 0xff1b;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0xff51;
            case KeyEvent.KEYCODE_DPAD_UP: return 0xff52;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0xff53;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0xff54;
            case KeyEvent.KEYCODE_MOVE_HOME: return 0xff50;
            case KeyEvent.KEYCODE_MOVE_END: return 0xff57;
            case KeyEvent.KEYCODE_PAGE_UP: return 0xff55;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 0xff56;
            default: return 0;
        }
    }
}
