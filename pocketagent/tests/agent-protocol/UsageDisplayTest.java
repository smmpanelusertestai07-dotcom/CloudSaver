package com.pocketagent.mobile;

import org.json.JSONObject;
import java.util.Locale;
import java.util.TimeZone;

/** Actual quota is distinct from missing data, thread tokens and model context capacity. */
public final class UsageDisplayTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US); TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        unknownAndZero(); windowNames(); modelBuckets(); restrictions(); tokenAccounting(); inactive(); malformed(); sparseUpdates();
        System.out.println("PASS UsageDisplayTest (" + assertions + " assertions)");
    }

    private static void unknownAndZero() throws Exception {
        for (String payload : new String[]{"{}", "{\"rateLimits\":null}", "{\"rateLimits\":{\"primary\":null}}",
                "{\"rateLimits\":{\"primary\":{\"usedPercent\":null}}}"}) {
            UsageDisplay result = read(payload);
            check(!result.hasQuota, "unavailable account data must not become measured usage");
            check(result.compact.contains("unavailable") && !result.compact.contains("0%"), "unknown is never zero used");
            check(!result.blocked, "unavailable restriction state is not an invented explicit block");
        }
        UsageDisplay zero = read("{\"rateLimits\":{\"primary\":{\"usedPercent\":0}}}");
        check(zero.hasQuota && zero.compact.contains("100%"), "real provider-reported zero used leaves 100%");
    }

    private static void windowNames() throws Exception {
        UsageDisplay result = read("{\"rateLimits\":{\"primary\":{\"usedPercent\":24,\"windowDurationMins\":300},"
                + "\"secondary\":{\"usedPercent\":67,\"windowDurationMins\":10080,\"resetsAt\":2000000000}}}");
        equal("5h 76% · Weekly 33% left", result.compact, "display actual durations and arithmetic remaining percentages");
        check(result.details.contains("Resets:"), "valid provider reset timestamp is shown");
        UsageDisplay shortWindow = read("{\"rateLimits\":{\"primary\":{\"usedPercent\":7,\"windowDurationMins\":90}}}");
        check(shortWindow.compact.startsWith("90m"), "non-hour window retains its real minutes");
        UsageDisplay unspecified = read("{\"rateLimits\":{\"primary\":{\"usedPercent\":12},\"secondary\":{\"usedPercent\":9}}}");
        check(unspecified.compact.contains("Primary") && unspecified.compact.contains("Secondary"), "missing duration cannot become guessed five-hour/week limits");
    }

    private static void modelBuckets() throws Exception {
        JSONObject state = state("{\"rateLimits\":{\"limitId\":\"codex\",\"primary\":{\"usedPercent\":11}},"
                + "\"rateLimitsByLimitId\":{\"codex\":{\"limitId\":\"codex\",\"primary\":{\"usedPercent\":11}},"
                + "\"model-x\":{\"limitId\":\"model-x\",\"limitName\":\"Model X quota\",\"primary\":{\"usedPercent\":72}},"
                + "\"alias-y\":{\"limitId\":\"alias-y\",\"normalModelSlug\":\"model-y\",\"primary\":{\"usedPercent\":43}}}} ");
        state.put("effectiveModel", "model-x");
        UsageDisplay x = UsageDisplay.read(state, "codex");
        check(x.compact.contains("28%") && !x.compact.contains("89%"), "exact selected-model bucket drives composer");
        check(x.details.contains("Model X quota") && x.details.contains("11%") && x.details.contains("43%"), "other quota buckets remain inspectable");
        state.put("effectiveModel", "model-y");
        check(UsageDisplay.read(state, "codex").compact.contains("57%"), "normalModelSlug resolves alias bucket");
        state.put("effectiveModel", "unmatched");
        check(UsageDisplay.read(state, "codex").compact.contains("89%"), "unmatched model uses actual historical account bucket");
        UsageDisplay onlyBuckets = read("{\"rateLimitsByLimitId\":{\"codex\":{\"primary\":{\"usedPercent\":28}}}}");
        check(onlyBuckets.compact.contains("72%"), "multi-bucket-only snapshot can expose codex account quota");
    }

    private static void restrictions() throws Exception {
        UsageDisplay disallowed = read("{\"ordinaryUsageAllowed\":false,\"rateLimits\":{\"primary\":{\"usedPercent\":0,\"resetsAt\":1}}}");
        check(disallowed.blocked && disallowed.compact.contains("limit reached"), "zero usage/past reset cannot override explicit ordinary usage block");
        UsageDisplay spending = read("{\"ordinaryUsageAllowed\":true,\"rateLimits\":{\"spendControlReached\":true}} ");
        check(spending.blocked && !spending.hasQuota, "spending restriction works even without quota percentages");
        check(read("{\"ordinaryUsageAllowed\":null,\"rateLimits\":{\"spendControlReached\":null}}").compact.contains("unavailable"), "null flags alone do not claim restored allowance");
        UsageDisplay credits = read("{\"rateLimits\":{\"credits\":{\"unlimited\":true,\"hasCredits\":true},\"primary\":{\"usedPercent\":88}}}");
        check(credits.details.contains("account windows still apply") && credits.compact.contains("12%"), "unlimited credits do not erase account windows");
    }

    private static void tokenAccounting() throws Exception {
        JSONObject state = state("{\"rateLimits\":{\"primary\":{\"usedPercent\":35}}}");
        state.put("usageUpdatedAt", 1800000000000L).put("tokenUsage", new JSONObject("{\"total\":{\"totalTokens\":100000},"
                + "\"last\":{\"inputTokens\":18000,\"outputTokens\":2000,\"cachedInputTokens\":5000},\"modelContextWindow\":32000}"));
        UsageDisplay result = UsageDisplay.read(state, "codex");
        equal("Thread · 100.0k tokens", result.tokens, "cumulative conversation tokens have their own label");
        check(result.details.contains("Conversation tokens: 100,000") && result.details.contains("Model context capacity: 32,000 tokens"), "cumulative tokens and context capacity are separate facts");
        check(!result.details.contains("312") && !result.details.contains("context used"), "cumulative usage is not divided by context capacity");
        check(result.details.contains("Cached input (included above): 5,000"), "cached tokens are not double-counted");
        check(result.details.contains("Quota updated:"), "account freshness timestamp is inspectable");
        JSONObject legacy = state("{\"threadUsage\":{\"tokenUsage\":{\"total\":{\"totalTokens\":1250}}}}");
        equal("Thread · 1.3k tokens", UsageDisplay.read(legacy, "codex").tokens, "legacy token event wrapper still parses");
        state.put("tokenUsage", new JSONObject("{\"total\":{\"totalTokens\":0}}"));
        equal("Thread · 0 tokens", UsageDisplay.read(state, "codex").tokens, "actual zero tokens are distinct from missing counts");
    }

    private static void inactive() throws Exception {
        JSONObject state = state("{\"rateLimits\":{\"primary\":{\"usedPercent\":55}}}");
        state.put("connected", false);
        UsageDisplay disconnected = UsageDisplay.read(state, "codex");
        check(!disconnected.hasQuota && disconnected.compact.contains("sign in"), "disconnected composer cannot present stale account usage as live");
        state.put("accountConnected", true);
        UsageDisplay authenticatedSessionError = UsageDisplay.read(state, "codex");
        check(authenticatedSessionError.hasQuota && authenticatedSessionError.compact.contains("45%"), "authenticated account quota remains visible while the saved engine conversation needs recovery");
        state.put("accountConnected", false);
        check(UsageDisplay.read(state, "codex").compact.contains("sign in"), "no account or session connection continues to require sign-in");
        state.put("connected", true).put("provider", "claude");
        check(UsageDisplay.read(state, "codex").compact.contains("sign in"), "another active provider's state is not Codex quota");
        state.put("provider", "codex");
        UsageDisplay other = UsageDisplay.read(state, "cursor");
        check(!other.hasQuota && other.compact.contains("dashboard") && other.tokens.isEmpty(), "Codex limits never become another provider's limits");
    }

    private static void malformed() throws Exception {
        for (String value : new String[]{"\"0\"", "-1", "true", "{}", "[]"}) {
            UsageDisplay result = read("{\"rateLimits\":{\"primary\":{\"usedPercent\":" + value + "}}}");
            check(!result.hasQuota, "malformed percentages cannot fabricate usage: " + value);
        }
        JSONObject state = state("{\"rateLimits\":{\"primary\":{\"usedPercent\":12,\"windowDurationMins\":\"300\",\"resetsAt\":9223372036854775807}}}");
        state.put("tokenUsage", new JSONObject("{\"total\":{\"totalTokens\":\"3000\"},\"modelContextWindow\":-1}"));
        UsageDisplay result = UsageDisplay.read(state, "codex");
        check(result.compact.contains("Primary") && !result.details.contains("Resets:"), "malformed duration and overflowing reset do not invent times");
        check(result.tokens.isEmpty() && !result.details.contains("context capacity"), "invalid token/capacity data stays unavailable");
        check(read("{\"rateLimits\":[],\"rateLimitsByLimitId\":{\"codex\":false}}").compact.contains("unavailable"), "wrong object types degrade safely");
    }

    private static void sparseUpdates() throws Exception {
        JSONObject original = new JSONObject("{\"accountId\":\"account-a\",\"ordinaryUsageAllowed\":false,"
                + "\"rateLimits\":{\"limitId\":\"codex\",\"spendControlReached\":true,\"primary\":{\"usedPercent\":90,\"windowDurationMins\":300}},"
                + "\"rateLimitsByLimitId\":{\"model-x\":{\"limitId\":\"model-x\",\"primary\":{\"usedPercent\":66}}}}");
        JSONObject rolling = new JSONObject("{\"rateLimits\":{\"limitId\":\"codex\",\"spendControlReached\":null,\"primary\":{\"usedPercent\":0,\"windowDurationMins\":null}}}");
        JSONObject merged = RateLimitState.rolling(original, rolling);
        check(Boolean.FALSE.equals(merged.opt("ordinaryUsageAllowed")), "sparse event cannot discard active account restriction");
        check(merged.getJSONObject("rateLimits").getBoolean("spendControlReached"), "null spending flag cannot confirm recovery");
        check(merged.getJSONObject("rateLimits").getJSONObject("primary").getInt("usedPercent") == 0, "new measured window usage still updates");
        check(merged.getJSONObject("rateLimits").getJSONObject("primary").isNull("windowDurationMins"), "an explicitly unknown current duration cannot inherit an old measured duration");
        check(merged.getJSONObject("rateLimitsByLimitId").has("model-x"), "sparse update does not erase other model quota buckets");
        check(original.getJSONObject("rateLimits").getJSONObject("primary").getInt("usedPercent") == 90, "merging never mutates input snapshots");
        JSONObject shown = state("{}").put("rateLimits", merged);
        check(UsageDisplay.read(shown, "codex").blocked, "composer stays blocked after unknown notification with zero percentage");

        JSONObject knownRecovery = new JSONObject("{\"accountId\":\"account-a\",\"ordinaryUsageAllowed\":true,\"rateLimits\":{\"limitId\":\"codex\",\"spendControlReached\":false}} ");
        JSONObject recovered = RateLimitState.read(merged, knownRecovery);
        check(!UsageDisplay.read(state("{}").put("rateLimits", recovered), "codex").blocked, "explicit full-read recovery clears restrictions");
        JSONObject unavailable = new JSONObject("{\"ordinaryUsageAllowed\":null,\"rateLimits\":{\"limitId\":\"codex\",\"spendControlReached\":null}} ");
        check(UsageDisplay.read(state("{}").put("rateLimits", RateLimitState.read(merged, unavailable)), "codex").blocked, "unavailable full-read flags also cannot prove recovery");
        equal("account-a", RateLimitState.read(merged, unavailable).getString("accountId"), "unavailable identity cannot erase the boundary needed for a later account change");
        JSONObject otherAccount = new JSONObject("{\"accountId\":\"account-b\",\"ordinaryUsageAllowed\":null,\"rateLimits\":{}} ");
        check(!RateLimitState.read(merged, otherAccount).has("rateLimitsByLimitId"), "different known account cannot inherit former account buckets");
        check(RateLimitState.read(merged, otherAccount).isNull("ordinaryUsageAllowed"), "different known account cannot inherit former restriction state");
        JSONObject differentBucket = RateLimitState.rolling(merged, new JSONObject("{\"rateLimits\":{\"limitId\":\"model-z\",\"primary\":{\"usedPercent\":25}}}"));
        check(!differentBucket.getJSONObject("rateLimits").has("spendControlReached"), "another model bucket does not inherit previous bucket restriction");
        JSONObject matchedBucket = RateLimitState.rolling(merged, new JSONObject("{\"rateLimits\":{\"limitId\":\"model-x\",\"secondary\":{\"usedPercent\":4}}}"));
        check(matchedBucket.getJSONObject("rateLimits").getJSONObject("primary").getInt("usedPercent") == 66, "rolling alias resolves its own previous bucket");
    }

    private static JSONObject state(String payload) throws Exception { return new JSONObject().put("provider", "codex").put("connected", true).put("rateLimits", new JSONObject(payload)); }
    private static UsageDisplay read(String payload) throws Exception { return UsageDisplay.read(state(payload), "codex"); }
    private static void equal(String expected, String actual, String why) { check(expected.equals(actual), why + ": " + actual); }
    private static void check(boolean okay, String why) { assertions++; if (!okay) throw new AssertionError(why); }
}
