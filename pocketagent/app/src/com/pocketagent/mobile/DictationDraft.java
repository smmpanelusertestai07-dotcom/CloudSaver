package com.pocketagent.mobile;

/** A reviewed speech insertion. Never guesses how to merge a concurrently edited draft. */
final class DictationDraft {
    final String scope, expected;
    final int start, end;

    DictationDraft(String scope, String draft, int first, int last) {
        this.scope = scope == null ? "" : scope;
        this.expected = draft == null ? "" : draft;
        int a = first < 0 ? expected.length() : Math.min(first, expected.length());
        int b = last < 0 ? a : Math.min(last, expected.length());
        start = boundary(expected, Math.min(a, b), false);
        end = boundary(expected, Math.max(a, b), a != b);
    }

    Proposal propose(String currentScope, String currentDraft, String speech) {
        if (!scope.equals(currentScope) || !expected.equals(currentDraft)) return null;
        String words = VoiceTypingPolicy.clean(speech);
        if (words.isEmpty()) return null;
        String prefix = expected.substring(0, start), suffix = expected.substring(end);
        String before = separated(prefix, words) ? " " : "";
        String after = separated(words, suffix) ? " " : "";
        return new Proposal(expected, prefix + before + words + after + suffix,
                prefix.length() + before.length() + words.length());
    }

    private static boolean separated(String before, String after) {
        if (before.isEmpty() || after.isEmpty()) return false;
        int left = before.codePointBefore(before.length()), right = after.codePointAt(0);
        return isWord(left) && isWord(right);
    }

    private static boolean isWord(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isLetterOrDigit(codePoint) || type == Character.OTHER_SYMBOL
                || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
    }

    private static int boundary(String value, int index, boolean forward) {
        if (index > 0 && index < value.length() && Character.isHighSurrogate(value.charAt(index - 1))
                && Character.isLowSurrogate(value.charAt(index))) return forward ? index + 1 : index - 1;
        return index;
    }

    static final class Proposal {
        final String expected, text;
        final int cursor;
        Proposal(String expected, String text, int cursor) {
            this.expected = expected; this.text = text; this.cursor = cursor;
        }
    }
}
