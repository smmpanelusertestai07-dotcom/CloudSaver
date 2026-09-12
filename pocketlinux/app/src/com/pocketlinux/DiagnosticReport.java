package com.pocketlinux;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A bounded copy keeps runtime evidence ahead of verbose publisher app output. */
final class DiagnosticReport {
    static final int LIMIT = 16000;
    private static final Pattern LAUNCH = Pattern.compile("(?m)^--- \\d{4}-\\d{2}-\\d{2} [^\\r\\n]*---$");
    private static final Pattern URL_QUERY = Pattern.compile(
            "(?<![A-Za-z0-9+.-])([A-Za-z][A-Za-z0-9+.-]*://[^\\s?#\"'<>]*)[?#][^\\s\"'<>]*");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(code|state|access_token|refresh_token|id_token|authorization)=([^\\s&]+)");
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\"(?:access_token|refresh_token|id_token|authorization)\"\\s*:\\s*\")"
                    + "(?:[^\"\\\\]++|\\\\.)*+\"");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(\\bauthorization[ \\t]*[:=][ \\t]*)(?:Bearer[ \\t]+)?[^\\s\"',;&}]+");

    private DiagnosticReport() {}

    static String redact(String text) {
        if (text == null) return "";
        // Redact the entire quoted value, including escaped characters. Do not erase generic
        // JSON code/state fields: they often identify a renderer exit or connection state.
        text = JSON_SECRET.matcher(text).replaceAll("$1[redacted]\"");
        // Consume "Bearer <token>" together before the assignment matcher can redact only
        // the word Bearer and leave its credential exposed in a durable failure snapshot.
        text = AUTHORIZATION.matcher(text).replaceAll("$1[redacted]");
        if (text.contains("://")) text = URL_QUERY.matcher(text).replaceAll("$1?[redacted]");
        return SECRET.matcher(text).replaceAll("$1=[redacted]");
    }

    static String latestLaunch(String text) {
        if (text == null) return "";
        Matcher launches = LAUNCH.matcher(text);
        int start = 0;
        while (launches.find()) start = launches.start();
        return text.substring(start).trim();
    }

    /** Redact before truncation, so a cut cannot leave a credential's unrecognizable tail. */
    static String excerpt(String text, int limit, boolean latestLaunch) {
        if (limit <= 0) return "";
        text = redact(text).trim();
        if (latestLaunch) text = latestLaunch(text);
        if (text.length() <= limit) return text;
        String marker = "[Earlier output omitted; full report stays in Settings.]\n";
        int newline = text.indexOf('\n');
        String header = latestLaunch && newline >= 0 && newline < 300 ? text.substring(0, newline + 1) : "";
        if (header.length() + marker.length() >= limit) header = "";
        if (marker.length() > limit) marker = marker.substring(0, limit);
        int tailStart = text.length() - Math.max(0, limit - marker.length() - header.length());
        int nextLine = text.indexOf('\n', tailStart);
        if (nextLine >= 0 && nextLine < text.length() - 1) tailStart = nextLine + 1;
        // Preserve UTF-16 pairs when the publisher printed a very long unbroken line.
        if (tailStart < text.length() && Character.isLowSurrogate(text.charAt(tailStart))) tailStart++;
        return header + marker + text.substring(tailStart);
    }

    static String combine(String header, String[] names, String[] reports) {
        return combine(header, names, reports, new String[6], new long[6], new long[6], 0L);
    }

    private static String timestamp(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    /** File metadata provides ordering without interpreting a publisher's locale-dependent date. */
    static String ageNotice(long modifiedAt, long desktopOpenedAt) {
        if (modifiedAt <= 0L || desktopOpenedAt <= 0L || modifiedAt >= desktopOpenedAt) return "";
        return "Older app launch log: last updated " + timestamp(modifiedAt)
                + "; desktop opened " + timestamp(desktopOpenedAt)
                + ". This log predates that desktop session.\n";
    }

    static String failureNotice(long modifiedAt) {
        return "Retained across app restarts; this does not establish a new failure."
                + (modifiedAt > 0L ? " Saved " + timestamp(modifiedAt) + "." : "") + "\n";
    }

    static String combine(String header, String[] names, String[] reports, String[] failures,
            long[] modifiedAt, long[] failureModifiedAt, long desktopOpenedAt) {
        // Inputs retain the UI order: ChatGPT, Chrome, handoff, Claude, Cursor,
        // Antigravity, desktop, previous desktop, Android runtime.
        if (names.length != 9 || reports.length != 9) throw new IllegalArgumentException("Report set is incomplete");
        if (failures.length != 6 || modifiedAt.length != 6 || failureModifiedAt.length != 6)
            throw new IllegalArgumentException("App report metadata is incomplete");
        int[] order = {8, 6, 2, 1, 0, 7, 3, 4, 5};
        int failureCount = 0;
        for (String failure : failures) if (failure != null && !failure.trim().isEmpty()) failureCount++;
        int[] budgets = failureCount == 0
                ? new int[]{4500, 1900, 500, 2500, 2500, 600, 400, 400, 400}
                : new int[]{4000, 1500, 350, 1900, 2100, 500, 300, 300, 300};
        int failureBudget = failureCount == 0 ? 0 : 2700 / failureCount;
        StringBuilder result = new StringBuilder(excerpt(header, 800, false));
        result.append("\nCompact report: runtime first; app sections show their latest launch.\n");
        for (int i = 0; i < order.length; i++) {
            int index = order[i];
            String text = reports[index];
            boolean app = index < 6;
            if (text != null && !text.trim().isEmpty()) {
                String notice = app ? ageNotice(modifiedAt[index], desktopOpenedAt) : "";
                result.append("\n=== ").append(names[index]).append(" ===\n");
                result.append(notice).append(excerpt(text, budgets[i] - notice.length(), app));
                result.append('\n');
            }
            // Preserve the previous failure even if the latest launch has only startup output,
            // or a rotation left its current log empty. Never mix it into the latest launch.
            if (app && failures[index] != null && !failures[index].trim().isEmpty()) {
                String notice = failureNotice(failureModifiedAt[index]);
                result.append("\n=== ").append(names[index]).append(" · retained failure ===\n");
                result.append(notice).append(excerpt(failures[index], failureBudget - notice.length(), false));
                result.append('\n');
            }
        }
        if (result.length() > LIMIT) throw new IllegalStateException("Report budget exceeded");
        return result.toString();
    }
}
