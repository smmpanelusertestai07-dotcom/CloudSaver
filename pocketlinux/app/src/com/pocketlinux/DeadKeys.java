package com.pocketlinux;

/**
 * Dead keys, the ones an international keyboard composes accents with.
 *
 * Android hands a dead key back with KeyCharacterMap.COMBINING_ACCENT set, which makes the code
 * point negative; that raw value used to go on the wire, where X ignores it, so an accent could
 * never be composed and the letter after it lost its mark. The two constants are written out
 * rather than imported so the on-screen keyboard's own unit test can compile this file without
 * the whole of Android.
 */
final class DeadKeys {
    /** KeyCharacterMap.COMBINING_ACCENT. */
    static final int ACCENT = 0x80000000;
    /** KeyCharacterMap.COMBINING_ACCENT_MASK. */
    static final int ACCENT_MASK = 0x7fffffff;

    private DeadKeys() { }

    /** The X keysym for a dead key, or 0 when this code point is an ordinary character. */
    static int keysym(int codePoint) {
        if ((codePoint & ACCENT) == 0) return 0;
        switch (codePoint & ACCENT_MASK) {
            case 0x0060: return 0xfe50;       // dead_grave
            case 0x00b4: return 0xfe51;       // dead_acute
            case 0x005e: return 0xfe52;       // dead_circumflex
            case 0x007e: return 0xfe53;       // dead_tilde
            case 0x00a8: return 0xfe57;       // dead_diaeresis
            case 0x00b8: return 0xfe5e;       // dead_cedilla
            default: return 0;
        }
    }

    /** The character itself, with the accent flag taken off. */
    static int plain(int codePoint) { return codePoint & ~ACCENT; }
}
