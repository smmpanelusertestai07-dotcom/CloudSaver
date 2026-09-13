package com.pocketagent.mobile;

import org.json.JSONObject;
import org.json.JSONArray;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Presents server-reported quota and token counters without estimating subscription entitlement. */
final class UsageDisplay {
    final String compact, details, tokens;
    final boolean hasQuota, blocked;
    final JSONArray windows;
    final String creditsBalance, resetStatus, resetError;
    final long resetCredits, updatedAt;
    final boolean canResetCredits, resetCanRetry, refreshing, stale;

    private UsageDisplay(String compact, String details, String tokens, boolean hasQuota, boolean blocked) {
        this(compact, details, tokens, hasQuota, blocked, new JSONObject(), new JSONObject());
    }
    private UsageDisplay(String compact, String details, String tokens, boolean hasQuota, boolean blocked, JSONObject state, JSONObject bucket) {
        this.compact = compact; this.details = details; this.tokens = tokens;
        this.hasQuota = hasQuota; this.blocked = blocked;
        windows = new JSONArray(); typedWindow(windows, bucket.optJSONObject("primary"), "Primary"); typedWindow(windows, bucket.optJSONObject("secondary"), "Secondary");
        creditsBalance = creditBalance(bucket.optJSONObject("credits"));
        JSONObject payload = child(state, "rateLimits"), reset = child(state, "creditsReset"), freshness = child(child(state, "refresh"), "usage");
        resetCredits = number(child(payload, "rateLimitResetCredits"), "availableCount");
        updatedAt = Math.max(0, number(state, "usageUpdatedAt"));
        refreshing = freshness.optBoolean("loading");
        stale = freshness.has("stale") ? freshness.optBoolean("stale") : updatedAt == 0 || System.currentTimeMillis() - updatedAt > 120000L;
        resetStatus = text(reset, "status"); resetError = text(reset, "error");
        resetCanRetry = reset.optBoolean("canRetry");
        canResetCredits = state.optBoolean("connected") && state.optBoolean("accountConnected", true) && !state.optBoolean("busy")
                && !state.optBoolean("sessionOpening") && !stale && !refreshing && (resetCredits > 0 || reset.optBoolean("canRetry"))
                && text(reset, "operation").isEmpty() && !reset.optBoolean("awaitingRefresh");
    }

    static UsageDisplay read(JSONObject state, String selectedProvider) {
        if (!"codex".equals(selectedProvider)) return new UsageDisplay("Usage · dashboard",
                "This provider does not expose Codex account limits. Open its official usage page for your plan and remaining allowance.", "", false, false);
        if (!"codex".equals(state.optString("provider")) || !(state.has("accountConnected") ? state.optBoolean("accountConnected") : state.optBoolean("connected")))
            return new UsageDisplay("Usage · sign in", "Connect your ChatGPT account to view the usage reported by Codex.", "", false, false);
        JSONObject payload = state.optJSONObject("rateLimits");
        if (payload == null) payload = new JSONObject();
        JSONObject buckets = payload.optJSONObject("rateLimitsByLimitId");
        JSONObject primary = payload.optJSONObject("rateLimits");
        if (primary == null && (payload.has("primary") || payload.has("secondary"))) primary = payload;
        String effectiveModel = first(state.optString("effectiveModel"), state.optString("model"));
        if (buckets != null && !effectiveModel.isEmpty()) {
            JSONObject exact = buckets.optJSONObject(effectiveModel);
            if (exact != null) primary = exact;
            else for (Iterator<String> keys = buckets.keys(); keys.hasNext();) {
                JSONObject bucket = buckets.optJSONObject(keys.next());
                if (bucket != null && effectiveModel.equals(text(bucket, "normalModelSlug"))) { primary = bucket; break; }
            }
        }
        if (primary == null && buckets != null) primary = buckets.optJSONObject("codex");
        boolean blocked = Boolean.FALSE.equals(payload.opt("ordinaryUsageAllowed"))
                || (primary != null && Boolean.TRUE.equals(primary.opt("spendControlReached")));
        StringBuilder compact = new StringBuilder();
        if (primary != null) {
            compactWindow(compact, primary.optJSONObject("primary"), "Primary");
            compactWindow(compact, primary.optJSONObject("secondary"), "Secondary");
        }
        boolean hasQuota = compact.length() > 0;
        String summary = blocked ? "Usage · limit reached" : hasQuota ? compact + " left" : "Usage · unavailable";
        StringBuilder details = new StringBuilder();
        if (blocked) details.append("The provider last reported that included usage or a spending limit is restricted. Access stays unconfirmed until the provider explicitly reports recovery. A reset timestamp alone does not confirm restored access.\n\n");
        if (primary != null) appendBucket(details, primary, "Codex account");
        if (buckets != null) {
            List<String> names = new ArrayList<>();
            for (Iterator<String> keys = buckets.keys(); keys.hasNext() && names.size() < 20;) names.add(keys.next());
            Collections.sort(names);
            for (String name : names) {
                JSONObject other = buckets.optJSONObject(name);
                if (other == null || other == primary || (primary != null && !text(primary,"limitId").isEmpty()
                        && text(primary,"limitId").equals(text(other,"limitId")))) continue;
                appendBucket(details, other, name);
            }
        }
        if (!hasQuota && details.length() == 0) details.append("Codex has not provided account quota data. This is unavailable, not 0% used or unlimited.\n\n");
        long updated = number(state, "usageUpdatedAt");
        if (updated > 0) details.append("Quota updated: ").append(date(updated)).append("\n\n");
        JSONObject refresh = child(child(state, "refresh"), "usage");
        if (refresh.optBoolean("loading")) details.append("Refreshing account usage…\n");
        if (!text(refresh, "error").isEmpty()) details.append("Refresh unavailable: ").append(text(refresh, "error")).append("\nLast reported values are retained.\n");
        long retry = number(refresh, "nextRetryAt");
        if (retry > System.currentTimeMillis()) details.append("Next automatic retry: ").append(date(retry)).append('\n');
        long earned = number(child(payload, "rateLimitResetCredits"), "availableCount");
        if (earned >= 0) details.append("Earned rate-limit resets reported by provider: ").append(earned).append("\nEach reset action consumes one earned credit only if the provider confirms a reset. Credit balance and included quota are separate.\n");

        JSONObject usage = state.optJSONObject("tokenUsage");
        // Older snapshots kept the notification under rateLimits.threadUsage.
        if (usage == null) usage = payload.optJSONObject("threadUsage");
        if (usage != null && usage.optJSONObject("tokenUsage") != null) usage = usage.optJSONObject("tokenUsage");
        String tokens = "";
        if (usage != null) {
            JSONObject total = usage.optJSONObject("total"), last = usage.optJSONObject("last");
            long all = number(total, "totalTokens");
            if (all >= 0) {
                tokens = "Thread · " + shortNumber(all) + " tokens";
                details.append("Conversation tokens: ").append(formatNumber(all)).append("\n");
            }
            if (last != null) {
                long input = number(last, "inputTokens"), output = number(last, "outputTokens");
                if (input >= 0) details.append("Last model request input: ").append(formatNumber(input)).append("\n");
                if (output >= 0) details.append("Last model request output: ").append(formatNumber(output)).append("\n");
                long cached = number(last, "cachedInputTokens");
                if (cached >= 0) details.append("Cached input (included above): ").append(formatNumber(cached)).append("\n");
            }
            long capacity = number(usage, "modelContextWindow");
            if (capacity > 0) details.append("Model context capacity: ").append(formatNumber(capacity)).append(" tokens\n");
        }
        details.append("\nQuota percentages come from your account. Token counts describe model requests; they are not your remaining subscription allowance. Model access remains controlled by your provider.");
        return new UsageDisplay(summary, details.toString().trim(), tokens, hasQuota, blocked, state, primary == null ? new JSONObject() : primary);
    }

    private static void compactWindow(StringBuilder out, JSONObject window, String fallback) {
        Double used = percent(window); if (used == null) return;
        if (out.length() > 0) out.append(" · ");
        out.append(duration(number(window, "windowDurationMins"), fallback)).append(' ').append(decimal(Math.max(0, 100d - used))).append('%');
    }

    private static void appendBucket(StringBuilder out, JSONObject bucket, String fallback) {
        StringBuilder body = new StringBuilder();
        appendWindow(body, bucket.optJSONObject("primary"), "Primary window");
        appendWindow(body, bucket.optJSONObject("secondary"), "Secondary window");
        JSONObject credits = bucket.optJSONObject("credits");
        if (credits != null) {
            if (Boolean.TRUE.equals(credits.opt("unlimited"))) body.append("Extra-credit balance: unlimited (account windows still apply)\n");
            else if (!text(credits,"balance").isEmpty())
                body.append("Extra-credit balance reported by provider: ").append(text(credits,"balance")).append('\n');
            else if (Boolean.FALSE.equals(credits.opt("hasCredits"))) body.append("Provider reports no extra credits available; numeric balance was not supplied.\n");
        }
        if (body.length() == 0) return;
        out.append(first(text(bucket,"limitName"), text(bucket,"limitId"), fallback)).append('\n').append(body).append('\n');
    }

    private static void appendWindow(StringBuilder out, JSONObject window, String fallback) {
        Double used = percent(window); if (used == null) return;
        out.append(duration(number(window, "windowDurationMins"), fallback)).append(": ").append(decimal(used)).append("% used");
        long reset = number(window, "resetsAt");
        if (reset > 0 && reset < Long.MAX_VALUE / 1000L) out.append("\nResets: ").append(date(reset * 1000L));
        out.append('\n');
    }

    private static Double percent(JSONObject source) {
        if (source == null || !(source.opt("usedPercent") instanceof Number)) return null;
        double value = ((Number) source.opt("usedPercent")).doubleValue();
        return !Double.isNaN(value) && !Double.isInfinite(value) && value >= 0 ? value : null;
    }
    private static long number(JSONObject source, String key) {
        if (source == null || !(source.opt(key) instanceof Number)) return -1;
        return ((Number) source.opt(key)).longValue();
    }
    private static String text(JSONObject source, String key) { Object value = source.opt(key); return value instanceof String ? (String)value : ""; }
    private static String duration(long minutes, String fallback) {
        if (minutes <= 0) return fallback;
        if (minutes == 10080) return "Weekly";
        if (minutes % 1440 == 0) return (minutes / 1440) + "d";
        if (minutes % 60 == 0) return (minutes / 60) + "h";
        return minutes + "m";
    }
    private static String date(long millis) { return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(millis)); }
    private static String decimal(double value) { return value == Math.floor(value) ? String.format(Locale.ROOT,"%.0f",value) : String.format(Locale.ROOT,"%.1f",value); }
    private static String formatNumber(long value) { return java.text.NumberFormat.getIntegerInstance().format(value); }
    private static String shortNumber(long value) {
        if (value < 1000) return Long.toString(value);
        if (value < 1000000) return String.format(Locale.ROOT,"%.1fk", value / 1000.0);
        return String.format(Locale.ROOT,"%.1fM", value / 1000000.0);
    }
    private static String first(String... values) { for(String v:values) if(v != null && !v.isEmpty() && !"null".equals(v)) return v; return ""; }
    private static JSONObject child(JSONObject value, String key) { JSONObject result = value == null ? null : value.optJSONObject(key); return result == null ? new JSONObject() : result; }
    private static String creditBalance(JSONObject credits) {
        if (credits == null) return "";
        if (Boolean.TRUE.equals(credits.opt("unlimited"))) return "Unlimited";
        return text(credits, "balance");
    }
    private static void typedWindow(JSONArray target, JSONObject window, String fallback) {
        Double used = percent(window); if (used == null) return;
        long reset = number(window, "resetsAt");
        try { target.put(new JSONObject().put("label", duration(number(window, "windowDurationMins"), fallback))
            .put("usedPercent", used).put("remainingPercent", Math.max(0, 100d - used))
            .put("resetsAt", reset > 0 && reset < Long.MAX_VALUE / 1000 ? reset : JSONObject.NULL)); }
        catch (Exception ignored) { }
    }
}
