package com.pocketide;

import java.util.regex.Pattern;

/**
 * Takes secrets out of text before it is shown in a dialog or copied to the clipboard.
 *
 * The logs this app hands the owner -- the workspace's recent output, a failed install's
 * transcript, a crash record -- are exactly where a coding agent's sign-in token, a git
 * credential or an API key can appear, printed by some tool that did not expect its output to
 * be pasted into a chat asking for help. The owner pastes it anyway, because that is what
 * "Copy details" is for. So the well-known shapes are blanked first, here, in one place.
 *
 * This is a best effort, not a guarantee: a token that looks like an ordinary word passes. What
 * it catches is the shapes that are unmistakably credentials -- bearer headers, key=value pairs
 * whose key says "token" or "secret", the prefixed keys the large providers issue, and the
 * query string of any URL, which is where sign-in links carry their codes.
 */
final class Redact {
    private static final String HIDDEN = "[hidden]";

    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern PAIR = Pattern.compile(
            "(?i)((?:access[_-]?|refresh[_-]?|id[_-]?|auth[_-]?|api[_-]?)?(?:token|key|secret|"
                    + "password|passwd|credential|authorization)s?)(\\s*[:=]\\s*[\"']?)"
                    + "[^\\s\"'&,;]{4,}");
    private static final Pattern PREFIXED = Pattern.compile(
            "\\b(?:sk-[A-Za-z0-9_-]{12,}|sk-ant-[A-Za-z0-9_-]{12,}|ghp_[A-Za-z0-9]{20,}|"
                    + "gho_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z_-]{30,}|"
                    + "xox[abp]-[A-Za-z0-9-]{10,}|ya29\\.[A-Za-z0-9._-]{20,})");
    private static final Pattern QUERY = Pattern.compile("(https?://[^\\s\"'<>?#]+)\\?[^\\s\"'<>#]*");

    private Redact() {}

    /** The same text with anything that looks like a credential blanked. Null-safe. */
    static String secrets(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = BEARER.matcher(text).replaceAll("$1" + HIDDEN);
        out = PAIR.matcher(out).replaceAll("$1$2" + HIDDEN);
        out = PREFIXED.matcher(out).replaceAll(HIDDEN);
        out = QUERY.matcher(out).replaceAll("$1?" + HIDDEN);
        return out;
    }
}
