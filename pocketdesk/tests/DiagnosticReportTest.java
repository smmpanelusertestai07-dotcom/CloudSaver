package com.pocketdesk;

public final class DiagnosticReportTest {
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    public static void main(String[] args) {
        String[] names = {"ChatGPT", "Chrome", "Browser sign-in handoff", "Claude", "Cursor",
                "Antigravity", "Desktop session", "Previous desktop session", "Runtime and viewer"};
        String[] reports = new String[9];
        StringBuilder noise = new StringBuilder();
        for (int i = 0; i < 10000; i++) noise.append("Routine publisher warning ").append(i).append('\n');
        for (int i = 0; i < 9; i++) reports[i] = noise + "FINAL-" + i;
        reports[8] = "Host RAM MiB: available=800\ntracked children=24\ntracer exit=137";
        reports[1] = "--- 2026-09-05 12:54:59 PM ---\nOLD-ATTEMPT\n"
                + "--- 2026-09-05 01:35:37 PM ---\n" + noise
                + "https://example.test/callback?code=secret-value&state=secret-state\n"
                + "Chrome ended exit159";
        String text = DiagnosticReport.combine("PocketLinux test\nLast stop: exit137", names, reports);
        check(text.length() <= DiagnosticReport.LIMIT, "copy exceeds chat budget");
        check(text.indexOf("Runtime and viewer") < text.indexOf("=== ChatGPT"), "runtime buried after app noise");
        check(text.indexOf("tracer exit=137") < 1000, "runtime evidence not near beginning");
        check(!text.contains("OLD-ATTEMPT"), "old Chrome attempt copied as current");
        check(text.contains("01:35:37 PM"), "current launch timestamp lost");
        check(text.contains("Chrome ended exit159"), "browser final failure lost");
        check(text.contains("FINAL-0") && text.contains("FINAL-6"), "app or desktop final output lost");
        check(!text.contains("secret-value") && !text.contains("secret-state"), "URL credentials copied");
        check(DiagnosticReport.redact("state=private access_token=private-token").equals(
                "state=[redacted] access_token=[redacted]"), "standalone credentials not redacted");
        check(DiagnosticReport.redact("Authorization: Bearer header-private\nnext message").equals(
                "Authorization: [redacted]\nnext message"), "Bearer header credential leaked");
        check(DiagnosticReport.redact("authorization=Bearer assigned-private status=ok").equals(
                "authorization=[redacted] status=ok"), "Bearer assignment credential leaked");
        String tokenJson = "{\"access_token\":\"access-private\",\"refresh_token\":\"refresh-private\","
                + "\"id_token\":\"id-private\",\"authorization\":\"Bearer json-private\","
                + "\"code\":139,\"state\":\"crashed\"}";
        String redactedJson = DiagnosticReport.redact(tokenJson);
        check(!redactedJson.contains("private") && !redactedJson.contains("Bearer"),
                "quoted JSON credentials leaked");
        check(redactedJson.contains("\"access_token\":\"[redacted]\"")
                && redactedJson.contains("\"refresh_token\":\"[redacted]\"")
                && redactedJson.contains("\"id_token\":\"[redacted]\"")
                && redactedJson.contains("\"authorization\":\"[redacted]\""),
                "JSON token field structure was damaged");
        check(redactedJson.contains("\"code\":139") && redactedJson.contains("\"state\":\"crashed\""),
                "non-secret JSON error code or state was erased");
        check(DiagnosticReport.redact("{\"Access_Token\" : \"escaped\\\"private\",\"other\":\"keep\"}")
                .equals("{\"Access_Token\" : \"[redacted]\",\"other\":\"keep\"}"),
                "escaped JSON credential or mixed-case key was not redacted");
        StringBuilder giant = new StringBuilder("https://example.test/?code=");
        for (int i = 0; i < 20000; i++) giant.append('s');
        check(!DiagnosticReport.excerpt(giant.toString(), 200, false).contains("ssss"),
                "truncation exposed a secret's tail");
        String longToken = giant.substring(giant.indexOf("ssss"));
        check(!DiagnosticReport.excerpt("{\"access_token\":\"" + longToken + "\"}", 200, false).contains("ssss"),
                "JSON truncation exposed a secret's tail");
        check(DiagnosticReport.redact(longToken).equals(longToken), "ordinary long word was changed");
        check(DiagnosticReport.redact(longToken + " https://example.test/path?token=url-private")
                .equals(longToken + " https://example.test/path?[redacted]"),
                "URL redaction failed after a long word");

        // A successful reopen must not erase the retained evidence from the failed launch.
        String[] failures = new String[6];
        long[] modifiedAt = {9000000L, 3600000L, 0L, 0L, 0L, 0L};
        long[] failureModifiedAt = {8100000L, 0L, 0L, 0L, 0L, 0L};
        reports[0] = "--- 2026-09-05 03:11:27 PM ---\nNEW-STARTUP: window ready-to-show";
        failures[0] = "Previous launch failure\nhttps://example.test/?code=retained-secret\n"
                + "Memory sample: available=1135 MiB\nRenderer failed: out-of-memory\nApp exit 139";
        text = DiagnosticReport.combine("PocketLinux reopened", names, reports, failures,
                modifiedAt, failureModifiedAt, 7200000L);
        check(text.contains("NEW-STARTUP") && text.contains("App exit 139"),
                "reopening lost the previous failure or current startup");
        check(text.contains("=== ChatGPT · retained failure ==="), "failure was mixed with latest startup");
        check(text.contains("Retained across app restarts; this does not establish a new failure."),
                "retained failure presented as a new failure");
        check(text.contains("Memory sample: available=1135 MiB"), "retained resource evidence lost");
        check(!text.contains("retained-secret"), "retained failure credentials leaked");
        String chrome = text.substring(text.indexOf("=== Chrome ==="), text.indexOf("=== ChatGPT ==="));
        check(chrome.contains("Older app launch log: last updated 1970-01-01 01:00:00 UTC"),
                "old Chrome log was not labeled with its metadata timestamp");
        check(chrome.contains("desktop opened 1970-01-01 02:00:00 UTC"), "session comparison timestamp lost");
        String chatgpt = text.substring(text.indexOf("=== ChatGPT ==="), text.indexOf("=== ChatGPT · retained"));
        check(!chatgpt.contains("Older app launch log"), "current launch was mislabeled as stale");
        check(DiagnosticReport.ageNotice(0L, 7200000L).isEmpty()
                && DiagnosticReport.ageNotice(3600000L, 0L).isEmpty()
                && DiagnosticReport.ageNotice(7200000L, 7200000L).isEmpty(),
                "missing or equal timestamps invented a stale launch");

        // Exercise the worst budget: all nine logs plus all six retained failures and age notices.
        for (int i = 0; i < reports.length; i++) reports[i] = noise + "LATEST-" + i;
        for (int i = 0; i < failures.length; i++) {
            failures[i] = noise + "FAILURE-" + i;
            modifiedAt[i] = 3600000L;
            failureModifiedAt[i] = 3600000L;
        }
        text = DiagnosticReport.combine(noise.toString(), names, reports, failures,
                modifiedAt, failureModifiedAt, 7200000L);
        check(text.length() <= DiagnosticReport.LIMIT, "retained failures exceeded total copy budget");
        check(text.indexOf("=== Runtime and viewer") < text.indexOf("retained failure ==="),
                "retained failures displaced runtime-first ordering");
        for (int i = 0; i < 6; i++) check(text.contains("FAILURE-" + i) && text.contains("LATEST-" + i),
                "budget lost latest launch or failure for app " + i);
        reports[0] = "";
        text = DiagnosticReport.combine("Rotated log", names, reports, failures,
                modifiedAt, failureModifiedAt, 7200000L);
        check(text.contains("=== ChatGPT · retained failure ===") && text.contains("FAILURE-0"),
                "empty current log hid its retained failure");
        check(DiagnosticReport.excerpt(noise.toString(), 20, false).length() <= 20,
                "small remaining budget exceeded by omission marker");
        System.out.println("PASS DiagnosticReport (runtime priority, retained failures, stale timestamps, bounded copy, redaction)");
    }
}
