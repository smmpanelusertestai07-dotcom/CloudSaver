package com.pocketagent.mobile;

import java.util.Locale;

/** Pure text policy for device read aloud; does not translate or contact a provider. */
final class MessageSpeechText {
    private MessageSpeechText() { }

    static String choice(String saved) {
        return "en".equals(saved) || "hi".equals(saved) ? saved : "auto";
    }

    static Locale locale(String saved, String text) {
        String selected = choice(saved);
        if ("hi".equals(selected)) return Locale.forLanguageTag("hi-IN");
        if ("en".equals(selected)) return Locale.ENGLISH;
        if (text != null) for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u0900' && c <= '\u097f' && Character.isLetter(c))
                return Locale.forLanguageTag("hi-IN");
        }
        return Locale.ENGLISH;
    }

    /** Returns a UTF-16 boundary no farther than the engine's input bound. */
    static int nextEnd(String text, int start, int maxChars) {
        if (text == null || start < 0 || start >= text.length()) return start;
        if (maxChars < 2) throw new IllegalArgumentException("Speech chunk size must be at least two characters.");
        int end = (int) Math.min((long) text.length(), (long) start + maxChars);
        if (end == text.length()) return end;
        if (Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        int floor = start + (end - start) / 2;
        for (int i = end - 1; i >= floor; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '.' || c == '!' || c == '?' || c == '\u0964') return i + 1;
        }
        for (int i = end - 1; i >= floor; i--)
            if (Character.isWhitespace(text.charAt(i))) return i + 1;
        return end;
    }
}
