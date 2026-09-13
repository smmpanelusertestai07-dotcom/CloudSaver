package com.pocketagent.doors;

import android.view.KeyEvent;

/**
 * Android key codes to X keysyms, for the row of keys a touch keyboard does not have.
 *
 * The editor is a real X program now, so it wants X keysyms rather than the Android key codes
 * the key row speaks. Only the keys the row actually offers are here: a full table would be
 * hundreds of lines of guesses, and every one of them would be untested.
 *
 * Printable characters are their own code point in X, which is why typing needs no table at all
 * below 0x100; anything above it takes X's Unicode form.
 */
final class Keysyms {

    static final int CONTROL_L = 0xFFE3;

    /** The keysym for one Android key code, or 0 when this key row does not offer it. */
    static int of(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ESCAPE:     return 0xFF1B;
            case KeyEvent.KEYCODE_TAB:        return 0xFF09;
            case KeyEvent.KEYCODE_DPAD_UP:    return 0xFF52;
            case KeyEvent.KEYCODE_DPAD_DOWN:  return 0xFF54;
            case KeyEvent.KEYCODE_DPAD_LEFT:  return 0xFF51;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0xFF53;
            case KeyEvent.KEYCODE_ENTER:      return 0xFF0D;
            case KeyEvent.KEYCODE_DEL:        return 0xFF08;
            case KeyEvent.KEYCODE_GRAVE:      return '`';
            case KeyEvent.KEYCODE_C:          return 'c';
            case KeyEvent.KEYCODE_V:          return 'v';
            case KeyEvent.KEYCODE_S:          return 's';
            default:                          return 0;
        }
    }

    /**
     * The keysym for one character being typed.
     *
     * Latin-1 is its own value, and everything above it is X's Unicode form -- 0x01000000 plus
     * the code point, which is how X carries characters that predate none of this.
     */
    static int ofCharacter(int codePoint) {
        return codePoint < 0x100 ? codePoint : 0x01000000 + codePoint;
    }

    private Keysyms() {}
}
