package com.pocketagent.mobile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Side-effect-free composer lexer. Tokens are suggestions, never authorization.
 * Callers must resolve skill/app names against the current provider catalog and
 * validate project paths again at the file boundary before attaching anything.
 */
final class ComposerTokens {
    private static final int MAX_TEXT = 64000;

    static final class Token {
        final char prefix;
        final int start, end;
        final String query, raw;
        Token(String text, int start, int end) {
            this.prefix = text.charAt(start); this.start = start; this.end = end;
            this.raw = text.substring(start, end); this.query = raw.substring(1);
        }
    }

    static final class Command {
        final String key, arguments;
        final int start, end;
        Command(String text, int start, int end) {
            this.start = start; this.end = end;
            this.key = text.substring(start + 1, end).toLowerCase(Locale.ROOT);
            this.arguments = text.substring(end).trim();
        }
    }

    private ComposerTokens() {}

    /** A command is only recognized at the beginning of the draft, never in prose. */
    static Command leadingCommand(String text) {
        if (!bounded(text)) return null;
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        if (start == text.length() || text.charAt(start) != '/') return null;
        int end = start + 1;
        while (end < text.length() && nameChar(text.charAt(end))) end++;
        if (end == start + 1 || end - start > 65) return null;
        if (end < text.length() && !Character.isWhitespace(text.charAt(end))) return null;
        return new Command(text, start, end);
    }

    /** A token at the cursor can open a chooser. Content after the cursor is preserved. */
    static Token atCursor(String text, int cursor) {
        if (!bounded(text) || cursor < 1 || cursor > text.length()) return null;
        int start = cursor - 1;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) start--;
        char prefix = text.charAt(start);
        if (prefix != '/' && prefix != '@' && prefix != '$') return null;
        if (maskedCode(text)[start]) return null;
        if (prefix == '/') {
            if (!text.substring(0, start).trim().isEmpty() || cursor - start > 65) return null;
            for (int i = start + 1; i < cursor; i++) if (!nameChar(text.charAt(i))) return null;
        } else {
            for (int i = start + 1; i < cursor; i++) if (!referenceChar(text.charAt(i), prefix)) return null;
            if (prefix == '$' && cursor > start + 1 && !nameStart(text.charAt(start + 1))) return null;
            if (cursor - start > 4096) return null;
            if (prefix == '@' && cursor > start + 1) {
                String path = text.substring(start + 1, cursor);
                if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
                if (!".".equals(path) && !isProjectPath(path)) return null;
            }
        }
        // Do not offer replacement of only half a token while moving through a draft.
        if (cursor < text.length() && referenceChar(text.charAt(cursor), prefix)) return null;
        return new Token(text, start, cursor);
    }

    /** Explicit references outside backtick code. Bare @/$ are left to the chooser. */
    static List<Token> references(String text) {
        List<Token> result = new ArrayList<>();
        if (!bounded(text)) return result;
        boolean[] code = maskedCode(text);
        for (int i = 0; i < text.length() && result.size() < 64; i++) {
            char prefix = text.charAt(i);
            if ((prefix != '@' && prefix != '$') || code[i]
                    || (i > 0 && !Character.isWhitespace(text.charAt(i - 1)))) continue;
            int end = i + 1;
            while (end < text.length() && !code[end] && referenceChar(text.charAt(end), prefix)) end++;
            if (end == i + 1 || end - i > 4096) continue;
            if (prefix == '$' && !nameStart(text.charAt(i + 1))) continue;
            if (end < text.length() && !Character.isWhitespace(text.charAt(end))
                    && ",;:!?)]}".indexOf(text.charAt(end)) < 0) continue;
            // Dot can be part of a path/name; punctuation after a sentence is not.
            while (end > i + 1 && text.charAt(end - 1) == '.') end--;
            if (end == i + 1) continue;
            result.add(new Token(text, i, end)); i = end - 1;
        }
        return result;
    }

    /** Replace exactly the captured range only if the draft still contains it. */
    static String replace(String text, Token token, String replacement) {
        if (text == null || token == null || replacement == null || token.start < 0
                || token.end > text.length() || token.end < token.start
                || !text.substring(token.start, token.end).equals(token.raw)) return text;
        return text.substring(0, token.start) + replacement + text.substring(token.end);
    }

    /** Syntactic check only: WorkspaceMedia/WorkspaceTools must enforce canonical containment. */
    static boolean isProjectPath(String path) {
        if (path == null || path.isEmpty() || path.length() > 4096 || path.startsWith("/")
                || path.endsWith("/") || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) return false;
        for (int i = 0; i < path.length(); i++) if (Character.isISOControl(path.charAt(i))) return false;
        for (String part : path.split("/", -1)) if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return false;
        return true;
    }

    private static boolean bounded(String text) { return text != null && !text.isEmpty() && text.length() <= MAX_TEXT; }
    private static boolean nameStart(char value) { return Character.isLetter(value) || value == '_'; }
    private static boolean nameChar(char value) { return Character.isLetterOrDigit(value) || value == '-' || value == '_'; }
    private static boolean referenceChar(char value, char prefix) {
        return nameChar(value) || value == '.' || (prefix == '@' && value == '/');
    }

    /** Masks inline backticks and fenced backtick/tilde blocks, including unfinished code. */
    private static boolean[] maskedCode(String text) {
        boolean[] mask = new boolean[text.length()];
        char fence = 0; int fenceLength = 0, inlineTicks = 0;
        boolean linePrefix = true; int lineSpaces = 0;
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i);
            int run = 1;
            if (c == '`' || c == '~') while (i + run < text.length() && text.charAt(i + run) == c) run++;
            boolean lineStart = linePrefix && lineSpaces <= 3;
            boolean escaped = i > 0 && text.charAt(i - 1) == '\\';
            if (fence != 0) {
                for (int j = i; j < i + run; j++) mask[j] = true;
                if (c == fence && run >= fenceLength && lineStart && blankLineTail(text, i + run)) { fence = 0; fenceLength = 0; }
            } else if (!escaped && inlineTicks == 0 && (c == '`' || c == '~') && run >= 3 && lineStart) {
                fence = c; fenceLength = run;
                for (int j = i; j < i + run; j++) mask[j] = true;
            } else if (!escaped && c == '`') {
                for (int j = i; j < i + run; j++) mask[j] = true;
                if (inlineTicks == 0) inlineTicks = run;
                else if (inlineTicks == run) inlineTicks = 0;
            } else if (inlineTicks != 0) {
                for (int j = i; j < i + run; j++) mask[j] = true;
            }
            for (int j = i; j < i + run; j++) {
                if (text.charAt(j) == '\n') { linePrefix = true; lineSpaces = 0; }
                else if (text.charAt(j) == ' ' && linePrefix) lineSpaces++;
                else linePrefix = false;
            }
            i += run;
        }
        return mask;
    }

    private static boolean blankLineTail(String text, int start) {
        for (int i = start; i < text.length() && text.charAt(i) != '\n'; i++)
            if (text.charAt(i) != ' ' && text.charAt(i) != '\t' && text.charAt(i) != '\r') return false;
        return true;
    }
}
