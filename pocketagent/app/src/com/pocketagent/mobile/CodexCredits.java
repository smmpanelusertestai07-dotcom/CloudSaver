package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Earned reset credits are redeemed by Codex, never applied to local counters. */
final class CodexCredits {
    private CodexCredits() {}
    static void consent(String expected, String current) {
        if (expected == null || expected.isEmpty() || current == null || current.isEmpty() || !expected.equals(current))
            throw new IllegalStateException("The account or workspace changed after the confirmation opened. Review its current usage and confirm again.");
    }
    static long available(JSONObject limits) {
        Object value = child(limits, "rateLimitResetCredits").opt("availableCount");
        if (!(value instanceof Number)) return -1;
        long count = ((Number) value).longValue();
        return count >= 0 ? count : -1;
    }
    static void validateSelection(JSONObject limits, String creditId) {
        if (available(limits) <= 0) throw new IllegalStateException("Codex has not reported an available earned reset credit. Refresh usage first.");
        if (creditId == null || creditId.isEmpty()) return; // The official service chooses its next available credit.
        if (creditId.length() > 500) throw new IllegalArgumentException("Choose an earned credit reported by Codex.");
        JSONArray rows = list(child(limits, "rateLimitResetCredits"), "credits");
        int found = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && creditId.equals(row.optString("id")) && "available".equals(row.optString("status")) && "codexRateLimits".equals(row.optString("resetType"))) found++;
        }
        if (found != 1) throw new IllegalArgumentException("Choose an available earned reset credit from the latest Codex response.");
    }
    static JSONObject params(String key, String creditId) {
        if (key == null || !key.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("The saved reset request is invalid. Refresh the account before retrying.");
        JSONObject result = object("idempotencyKey", key);
        if (creditId != null && !creditId.isEmpty()) {
            if (creditId.length() > 500 || creditId.indexOf('\0') >= 0) throw new IllegalArgumentException("The earned reset credit identifier is invalid.");
            try { result.put("creditId", creditId); } catch (Exception impossible) { throw new IllegalArgumentException(impossible); }
        }
        return result;
    }
    static String outcome(String value) {
        if ("reset".equals(value)) return "Codex consumed one earned reset credit. Refreshing the actual account limits…";
        if ("alreadyRedeemed".equals(value)) return "Codex confirmed this reset was already redeemed. Refreshing account limits…";
        if ("nothingToReset".equals(value)) return "Codex reports no eligible usage window to reset.";
        if ("noCredit".equals(value)) return "Codex reports no earned reset credit available.";
        return "";
    }
    static String scopeKey(String accountScope, String accountId) {
        if (accountScope == null || accountScope.isEmpty()) throw new IllegalStateException("Verify your ChatGPT account first.");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((accountScope + "\n" + (accountId == null ? "" : accountId)).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("reset-"); for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (Exception failure) { throw new IllegalStateException("The reset request could not be scoped to this account."); }
    }
}
