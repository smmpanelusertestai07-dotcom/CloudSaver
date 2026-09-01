package com.pocketdesk;

import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

final class KeyboardInputView extends View {
    interface Listener {
        void typeCodePoint(int codePoint);
        void specialKey(int keysym);
    }

    private Listener listener;

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
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                sendText(text);
                return true;
            }

            @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
                return true;
            }

            @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (beforeLength > 0 && listener != null) listener.specialKey(0xff08);
                return true;
            }

            @Override public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN || listener == null) return true;
                int special = mapAndroidKey(event.getKeyCode());
                if (special != 0) listener.specialKey(special);
                else {
                    int unicode = event.getUnicodeChar();
                    if (unicode != 0) listener.typeCodePoint(unicode);
                }
                return true;
            }
        };
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (listener == null) return super.onKeyDown(keyCode, event);
        int special = mapAndroidKey(keyCode);
        if (special != 0) listener.specialKey(special);
        else {
            int unicode = event.getUnicodeChar();
            if (unicode != 0) listener.typeCodePoint(unicode);
        }
        return true;
    }

    private void sendText(CharSequence text) {
        if (text == null || listener == null) return;
        for (int offset = 0; offset < text.length();) {
            int codePoint = Character.codePointAt(text, offset);
            listener.typeCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
    }

    private static int mapAndroidKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL: return 0xff08;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 0xffff;
            case KeyEvent.KEYCODE_ENTER: return 0xff0d;
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
