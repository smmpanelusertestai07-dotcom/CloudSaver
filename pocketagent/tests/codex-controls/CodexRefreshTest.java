package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import static com.pocketagent.mobile.AgentProtocol.*;

/** Deterministic active-client clocks, identity races, quotas and earned-reset wire fixtures. */
public final class CodexRefreshTest {
    private static int assertions;
    private static final long NOW = 100000L, WALL = 1800000000000L;
    public static void main(String[] args) throws Exception {
        CodexRefresh r = new CodexRefresh();
        r.client("activity-A", true, NOW);
        CodexRefresh.Ticket account = r.next(NOW, WALL, true);
        eq("account", account.section, "identity read precedes dependent requests");
        check(r.next(NOW, WALL, true) == null, "account barrier and request dedupe");
        r.success(account, NOW, WALL);
        CodexRefresh.Ticket usage = r.next(NOW, WALL, true), models = r.next(NOW, WALL, true);
        eq("usage", usage.section, "quota follows verified identity");
        eq("models", models.section, "model metadata request scheduled once");
        check(r.next(NOW, WALL, true) == null, "no duplicate in-flight reads");
        r.success(usage, NOW, WALL); r.success(models, NOW, WALL);
        check(r.next(NOW + 59000, WALL + 59000, true) == null, "usage polling waits sixty seconds");
        CodexRefresh.Ticket scheduled = r.next(NOW + 60000, WALL + 60000, true);
        eq("usage", scheduled.section, "visible quota polling due at sixty seconds");
        r.success(scheduled, NOW + 60000, WALL + 60000);
        r.client("activity-B", true, NOW + 60001); r.client("activity-A", false, NOW + 60002);
        check(r.active(NOW + 60002), "old Activity pause does not remove new Activity lease");
        check(r.snapshot(NOW + 60002, WALL).getInt("clientCount") == 1, "client identities stay distinct");
        r.client("activity-B", false, NOW + 60003);
        check(r.next(NOW + 700000, WALL + 700000, true) == null, "background has no automatic polling");
        r.client("heartbeat", true, NOW + 800000);
        check(!r.active(NOW + 920000), "stale foreground client lease expires");

        CodexRefresh paused = new CodexRefresh(); paused.all(true);
        check(paused.next(NOW, WALL, false) == null, "OAuth/turn/mutation gate prevents even urgent polling");
        check(paused.next(NOW, WALL, true) != null, "manual/reconnect request resumes when eligible");
        CodexRefresh retries = new CodexRefresh(); retries.client("visible", true, NOW);
        CodexRefresh.Ticket failed = retries.next(NOW, WALL, true); retries.failure(failed, NOW, "offline");
        retries.all(true);
        check(retries.next(NOW + 14999, WALL + 14999, true) == null, "manual taps do not bypass fifteen-second failure backoff");
        CodexRefresh.Ticket retry = retries.next(NOW + 15000, WALL + 15000, true);
        check(retry != null, "first failure retry becomes eligible");
        retries.failure(retry, NOW + 15000, "still offline");
        long retryAt = retries.snapshot(NOW + 15000, WALL + 15000).getJSONObject("account").getLong("nextRetryAt");
        check(retryAt == WALL + 45000, "second failure backs off thirty seconds");
        check(!retries.snapshot(NOW, WALL).getJSONObject("account").getBoolean("loading"), "error ends loading honestly");

        CodexRefresh observed = ready();
        observed.need("usage", true);
        CodexRefresh.Ticket pending = observed.next(NOW + 10000, WALL + 10000, true);
        eq("usage", pending.section, "explicit read has a distinct ticket");
        observed.observed("usage", NOW + 11000, WALL + 11000);
        check(!observed.current(pending), "new live notification invalidates older quota response");
        observed.success(pending, NOW + 12000, WALL + 12000);
        check(observed.snapshot(NOW + 12000, WALL + 12000).getJSONObject("usage").getLong("updatedAt") == WALL + 11000, "late read cannot replace notification freshness");
        check(!observed.snapshot(NOW + 12000, WALL + 12000).getJSONObject("usage").getBoolean("loading"), "discard releases only its own request");
        observed.need("models", true); CodexRefresh.Ticket old = observed.next(NOW + 20000, WALL + 20000, true);
        observed.reset(); check(!observed.current(old), "account epoch invalidates all old metadata callbacks");
        observed.discard(old);
        check(observed.snapshot(NOW + 21000, WALL + 21000).getJSONObject("models").getLong("updatedAt") == 0, "old account data never becomes new account freshness");

        CodexRefresh deadline = ready();
        JSONObject actual = object("ordinaryUsageAllowed", false, "rateLimits", object("primary", object("usedPercent", 100, "resetsAt", WALL / 1000 + 15)));
        String untouched = actual.toString(); deadline.resetDeadline(actual);
        check(deadline.next(NOW + 16000, WALL + 16000, true) == null, "deadline includes a short server observation grace");
        CodexRefresh.Ticket resetRead = deadline.next(NOW + 17000, WALL + 17000, true);
        eq("usage", resetRead.section, "reset deadline schedules read-only quota fetch");
        deadline.success(resetRead, NOW + 17000, WALL + 17000); deadline.resetDeadline(actual);
        check(deadline.next(NOW + 28000, WALL + 28000, true) == null, "unchanged past reset timestamp cannot cause a poll loop");
        eq(untouched, actual.toString(), "timer never edits quota percentages or restricted access flags");

        JSONObject quota = object("rateLimitResetCredits", object("availableCount", 2, "credits", JSONObject.NULL));
        check(CodexCredits.available(quota) == 2, "server count authoritative when credit rows unavailable");
        CodexCredits.validateSelection(quota, ""); assertions++;
        reject(() -> CodexCredits.validateSelection(quota, "invented"), "specific credit cannot be guessed");
        reject(() -> CodexCredits.validateSelection(object(), ""), "unknown count never authorizes earned reset");
        quota.getJSONObject("rateLimitResetCredits").put("credits", array(object("id", "earned-1", "status", "available", "resetType", "codexRateLimits")));
        CodexCredits.validateSelection(quota, "earned-1"); assertions++;
        check(CodexCredits.available(quota) == 2, "detail row count does not override total available count");
        String receipt = "7aa865b8-cedf-4512-a8a4-668f99e13193";
        JSONObject wire = CodexCredits.params(receipt, "earned-1");
        JSONObject restored = new JSONObject(wire.toString());
        check(wire.similar(CodexCredits.params(restored.getString("idempotencyKey"), restored.getString("creditId"))), "durable retry reconstructs the same idempotent wire request");
        check(!CodexCredits.params(receipt, "").has("creditId"), "count-only redemption lets official service choose next credit");
        check(!CodexCredits.scopeKey("first@example|plus", "one").equals(CodexCredits.scopeKey("second@example|plus", "one")), "receipts do not cross account identities");
        check(!CodexCredits.scopeKey("first@example|plus", "one").equals(CodexCredits.scopeKey("first@example|plus", "two")), "receipts do not cross quota account IDs");
        check(!CodexCredits.scopeKey("private@example|plus", "one").contains("private"), "storage key contains no raw account email");
        CodexCredits.consent("instance:1:account", "instance:1:account"); assertions++;
        reject(() -> CodexCredits.consent("instance:1:account", "instance:2:other"), "stale confirmation cannot spend another account's credit");
        reject(() -> CodexCredits.consent("", ""), "unknown account cannot consent to reset");
        check(CodexCredits.outcome("newFutureOutcome").isEmpty(), "unknown backend reset result never pretends success");
        String[] outcomes = {"reset", "alreadyRedeemed", "nothingToReset", "noCredit"};
        for (String outcome : outcomes) check(!CodexCredits.outcome(outcome).isEmpty(), "official outcome supported");

        JSONObject state = object("provider", "codex", "connected", true, "accountConnected", true, "usageUpdatedAt", System.currentTimeMillis(),
            "refresh", object("usage", object("stale", false, "loading", false)), "rateLimits", quota);
        quota.put("rateLimits", object("primary", object("usedPercent", 25, "windowDurationMins", 300, "resetsAt", 2000000000L), "credits", object("balance", "0", "hasCredits", false, "unlimited", false)));
        UsageDisplay display = UsageDisplay.read(state, "codex");
        eq("0", display.creditsBalance, "real zero balance displayed even hasCredits false");
        check(display.resetCredits == 2 && display.canResetCredits, "fresh verified earned credits eligible for explicit reset");
        check(display.windows.getJSONObject(0).getDouble("remainingPercent") == 75, "remaining bar is arithmetic from real used percentage");
        check(display.windows.getJSONObject(0).getLong("resetsAt") == 2000000000L, "bar reset time remains actual server seconds");
        quota.getJSONObject("rateLimitResetCredits").put("availableCount", 0);
        state.put("creditsReset", object("canRetry", true)); display = UsageDisplay.read(state, "codex");
        check(display.canResetCredits && display.resetCanRetry, "pending idempotent retry remains possible even count reached zero");
        state.put("accountConnected", false);
        check(!UsageDisplay.read(state, "codex").hasQuota, "unverified account cannot render previous quota as signed-in");
        Files.write(Paths.get(args[0]), array(object("schema", "ConsumeAccountRateLimitResetCreditParams.json", "params", wire),
            object("schema", "ConsumeAccountRateLimitResetCreditParams.json", "params", CodexCredits.params(receipt, ""))).toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("PASS CodexRefreshTest (" + assertions + " assertions)");
    }
    private static CodexRefresh ready() { CodexRefresh r=new CodexRefresh();r.client("active",true,NOW);for(int i=0;i<3;i++){CodexRefresh.Ticket t=r.next(NOW,WALL,true);r.success(t,NOW,WALL);}return r; }
    private static void check(boolean value,String label){assertions++;if(!value)throw new AssertionError(label);}
    private static void eq(Object a,Object b,String label){check(a.equals(b),label+": "+b);}
    private interface Attempt{void run()throws Exception;}
    private static void reject(Attempt attempt,String label){try{attempt.run();}catch(IllegalArgumentException|IllegalStateException expected){assertions++;return;}catch(Exception failure){throw new AssertionError(failure);}throw new AssertionError(label);}
}
