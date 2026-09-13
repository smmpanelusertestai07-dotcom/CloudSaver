package com.pocketagent.mobile;

/** Short-lived dictation session boundaries. Contains no Android or network code. */
final class VoiceTypingPolicy {
    static final int MAX_TEXT = 16384;
    private long generation;
    private boolean listening;

    long begin() { listening = true; return ++generation; }
    void cancel() { listening = false; ++generation; }
    boolean accepts(long token, boolean visible, boolean unlocked) {
        return listening && token == generation && visible && unlocked;
    }
    boolean active() { return listening; }

    static String clean(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(Math.min(value.length(), MAX_TEXT));
        for (int i = 0; i < value.length() && out.length() < MAX_TEXT; i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))
                        && out.length() + 2 <= MAX_TEXT) { out.append(ch).append(value.charAt(++i)); }
            } else if (!Character.isLowSurrogate(ch) && (!Character.isISOControl(ch) || ch == '\n' || ch == '\t')) {
                out.append(ch);
            }
        }
        return out.toString().trim();
    }
}
