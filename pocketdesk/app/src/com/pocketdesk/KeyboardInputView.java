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
        /** One ordered IME edit; the transport batches its key events without losing text. */
        void replaceText(int backspaces, int deletes, String text);
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
                deleteText(beforeLength, afterLength, false);
                return true;
            }

            @Override public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
                deleteText(beforeLength, afterLength, true);
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
        String insert = next.substring(common);
        if (erase != 0 || !insert.isEmpty()) listener.replaceText(erase, 0, insert);
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

    private void deleteText(int beforeLength, int afterLength, boolean codePoints) {
        if (listener == null) return;
        int before = Math.max(0, beforeLength);
        int after = Math.max(0, afterLength);
        int available = codePoints ? composing.codePointCount(0, composing.length()) : composing.length();
        int removed = Math.min(before, available);
        int keep = codePoints ? composing.offsetByCodePoints(composing.length(), -removed)
                : composing.length() - removed;
        // A UTF-16 deletion can request half an emoji. Linux backspace acts on the complete
        // code point; mirror that rather than retaining an unpaired surrogate as composing text.
        if (keep > 0 && keep < composing.length()
                && Character.isHighSurrogate(composing.charAt(keep - 1))
                && Character.isLowSurrogate(composing.charAt(keep))) keep--;
        int erase = (before - removed) + composing.codePointCount(keep, composing.length());
        composing = composing.substring(0, keep);
        if (erase != 0 || after != 0) listener.replaceText(erase, after, "");
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
