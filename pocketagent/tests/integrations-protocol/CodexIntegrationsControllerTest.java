package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

import static com.pocketagent.mobile.AgentProtocol.*;

/** Uses real controller logic with a queued, deterministic JSON-RPC host. No network or account. */
public final class CodexIntegrationsControllerTest {
    private static int assertions;
    private static int failures;
    private interface Case { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        run("bounded automatic inventories", CodexIntegrationsControllerTest::automaticInventories);
        run("app notification verifies runtime", CodexIntegrationsControllerTest::notificationChecksRuntime);
        run("ordinary refresh and plugin opt-in", CodexIntegrationsControllerTest::refreshDoesNotLoadPlugins);
        run("MCP pagination", CodexIntegrationsControllerTest::mcpPagination);
        run("app pagination and actual runtime", CodexIntegrationsControllerTest::appPaginationAndRuntime);
        run("app mention requires exact callable catalog identity", CodexIntegrationsControllerTest::appMentionValidation);
        run("failed plugin re-read cannot authorize install", CodexIntegrationsControllerTest::failedPluginRereadClearsReview);
        run("repeated cursor rejection", CodexIntegrationsControllerTest::repeatedCursors);
        run("null reload params", CodexIntegrationsControllerTest::reloadUsesNull);
        run("current catalog identity", CodexIntegrationsControllerTest::identityValidation);
        run("reset rejects stale callbacks", CodexIntegrationsControllerTest::resetRejectsStaleCallbacks);
        run("overlap keeps first mutation locked", CodexIntegrationsControllerTest::overlapPreservesMutation);
        run("OAuth URL is not connected access", CodexIntegrationsControllerTest::oauthRequiresRuntimeRefresh);
        run("OAuth completion during status read", CodexIntegrationsControllerTest::oauthCompletionDuringRead);
        run("OAuth completion before URL reply", CodexIntegrationsControllerTest::oauthCompletionBeforeReply);
        System.out.println("CodexIntegrationsControllerTest: " + assertions + " assertions, " + failures + " failed cases");
        if (failures != 0) throw new AssertionError(failures + " controller regression cases failed");
    }

    private static void automaticInventories() throws Exception {
        Fixture f = new Fixture();
        f.controller.autoRefresh(1000);
        equal(1, f.host.count("app/list"), "first authorized connection reads apps");
        equal(1, f.host.count("app/installed"), "first read verifies actual runtime");
        equal(0, f.host.countPrefix("plugin/"), "auto reads never opt into plugin preview");
        equal(0, f.host.count("config/value/write"), "auto reads never change configuration");
        f.controller.autoRefresh(2000);
        equal(1, f.host.count("app/list"), "frequent UI events cannot duplicate inventory");
        f.host.take("app/list").failure(object("message", "Offline"));
        f.host.take("app/installed").failure(object("message", "Offline"));
        f.controller.autoRefresh(31000);
        equal(2, f.host.count("app/list"), "next allowed interval can retry");
        f.host.take("app/list").failure(object("message", "Offline"));
        f.controller.autoRefresh(61000);
        equal(2, f.host.count("app/list"), "failed inventory backs off for a minute");
        f.controller.reset("", ""); f.controller.autoRefresh(100000);
        equal(2, f.host.count("app/list"), "no account workspace means no automatic reads");
    }
    private static void notificationChecksRuntime() throws Exception {
        Fixture f = new Fixture(); f.dispatch("apps_refresh");
        f.host.take("app/list").success(object("data", array(app("known"))));
        Call old = f.host.take("app/installed");
        f.controller.event("app/list/updated", object("data", array(app("known"))));
        equal(2, f.host.count("app/installed"), "unsolicited catalog change verifies actual runtime");
        old.success(object("apps", array(object("id", "known", "enabled", true, "callable", true))));
        check(!rows(f.state(), "apps").getJSONObject(0).optBoolean("runtimeKnown"), "old runtime reply cannot certify updated app");
        f.host.take("app/installed").success(object("apps", array(object("id", "known", "enabled", true, "callable", true))));
        check(rows(f.state(), "apps").getJSONObject(0).optBoolean("runtimeCallable"), "new authoritative runtime reply enables app");
    }

    private static void refreshDoesNotLoadPlugins() throws Exception {
        Fixture f = new Fixture(); f.dispatch("refresh");
        equal(1, f.host.count("mcpServerStatus/list"), "MCP refresh sent");
        equal(1, f.host.count("skills/list"), "skills refresh sent");
        equal(1, f.host.count("app/list"), "apps catalog read sent");
        equal(1, f.host.count("app/installed"), "actual installed app runtime read sent");
        equal(1, f.host.count("experimentalFeature/list"), "apps feature status read sent");
        equal(0, f.host.countPrefix("plugin/"), "ordinary refresh must exclude preview plugin APIs");
        check(!f.state().optBoolean("pluginPreview"), "ordinary refresh keeps plugin opt-in false");
        f.dispatch("plugins_load");
        equal(1, f.host.count("plugin/list"), "explicit preview loads plugins");
        check(f.state().optBoolean("pluginPreview"), "explicit preview records opt-in");
        f.host.take("plugin/list").success(object("marketplaces", array(object("name", "trusted", "path", "/opt/catalog", "plugins", array(object("id", "demo@trusted", "name", "demo", "installed", false))))));
        equal(1, rows(f.state(), "plugins").length(), "preview rows retained");
    }

    private static void mcpPagination() throws Exception {
        Fixture f = new Fixture(); f.dispatch("mcp_refresh");
        Call first = f.host.take("mcpServerStatus/list");
        check(!first.params.has("cursor"), "first MCP page has no cursor");
        first.success(object("data", array(server("alpha")), "nextCursor", "page-2"));
        Call next = f.host.take("mcpServerStatus/list");
        equal("page-2", next.params.optString("cursor"), "next MCP cursor forwarded");
        check(f.state().optBoolean("mcpLoading"), "MCP stays loading until final page");
        next.success(object("data", array(server("alpha"), server("beta")), "nextCursor", JSONObject.NULL));
        JSONArray rows = rows(f.state(), "mcp");
        equal(2, rows.length(), "unique rows from both MCP pages retained");
        equal("alpha", rows.getJSONObject(0).optString("name"), "first MCP page retained");
        equal("beta", rows.getJSONObject(1).optString("name"), "second MCP page appended");
        check(f.state().optBoolean("mcpKnown") && !f.state().optBoolean("mcpLoading"), "final MCP inventory is known");
    }

    private static void appPaginationAndRuntime() throws Exception {
        Fixture f = new Fixture(); f.dispatch("apps_refresh");
        Call first = f.host.take("app/list");
        check(first.params.optBoolean("forceRefetch"), "first app page asks for fresh data");
        first.success(object("data", array(app("a")), "nextCursor", "apps-2"));
        Call second = f.host.take("app/list");
        equal("apps-2", second.params.optString("cursor"), "app cursor forwarded");
        check(!second.params.optBoolean("forceRefetch"), "later pages continue the same app snapshot");
        second.success(object("data", array(app("b"), app("c")), "nextCursor", JSONObject.NULL));
        JSONArray initial = rows(f.state(), "apps");
        equal(3, initial.length(), "all app pages retained");
        for (int i = 0; i < initial.length(); i++) check(!initial.getJSONObject(i).optBoolean("runtimeKnown"), "catalog connectivity alone is not runtime evidence");
        f.host.take("app/installed").success(object("apps", array(
                object("id", "a", "enabled", true, "callable", false),
                object("id", "b", "enabled", true, "callable", true))));
        JSONArray actual = rows(f.state(), "apps");
        check(actual.getJSONObject(0).optBoolean("runtimeKnown") && !actual.getJSONObject(0).optBoolean("runtimeCallable"), "enabled but uncallable app stays uncallable");
        check(actual.getJSONObject(1).optBoolean("runtimeKnown") && actual.getJSONObject(1).optBoolean("runtimeCallable"), "actual callable status retained");
        check(!actual.getJSONObject(2).optBoolean("runtimeKnown") && !actual.getJSONObject(2).has("runtimeCallable"), "missing runtime entry remains unknown");
        f.dispatch("apps_refresh");
        for (int i = 0; i < rows(f.state(), "apps").length(); i++) check(!rows(f.state(), "apps").getJSONObject(i).optBoolean("runtimeKnown"), "refresh invalidates old runtime claims");
    }

    private static void repeatedCursors() throws Exception {
        for (String kind : new String[]{"mcp", "apps"}) {
            Fixture f = new Fixture(); f.dispatch(kind.equals("mcp") ? "mcp_refresh" : "apps_refresh");
            String method = kind.equals("mcp") ? "mcpServerStatus/list" : "app/list";
            JSONObject row = kind.equals("mcp") ? server("a") : app("a");
            f.host.take(method).success(object("data", array(row), "nextCursor", "repeated"));
            f.host.take(method).success(object("data", array(row), "nextCursor", "repeated"));
            check(!f.state().optBoolean(kind + "Known"), "repeated " + kind + " cursor does not produce a trusted complete list");
            check(!f.state().optBoolean(kind + "Loading"), "repeated cursor terminates loading");
            check(!f.state().optString(kind + "Error").isEmpty(), "repeated cursor exposes an error");
            equal(2, f.host.count(method), "repeated cursor does not loop");
        }
    }

    private static void appMentionValidation() throws Exception {
        Fixture f = new Fixture(); f.dispatch("apps_refresh");
        f.host.take("app/list").success(object("data", array(app("app-exact_123"), app("not-callable"), app("runtime-unknown"))));
        f.host.take("app/installed").success(object("apps", array(
                object("id", "app-exact_123", "enabled", true, "callable", true),
                object("id", "not-callable", "enabled", true, "callable", false))));
        JSONObject mention = f.controller.appInput("app-exact_123");
        equal("mention", mention.optString("type"), "app attachment uses the engine's mention input");
        equal("App app-exact_123", mention.optString("name"), "app mention retains actual catalog name");
        equal("app://app-exact_123", mention.optString("path"), "app mention retains exact engine ID");
        equal(3, mention.length(), "app mention contains only type, name, and actual app reference");
        for (String id : new String[]{"not-callable", "runtime-unknown", "unlisted-id", "app-exact_123/../other"}) {
            boolean rejected = false;
            try { f.controller.appInput(id); }
            catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
            check(rejected, "uncallable, unknown, or unlisted app cannot be attached: " + id);
        }
    }

    private static void failedPluginRereadClearsReview() throws Exception {
        Fixture f = new Fixture(); f.dispatch("plugins_load");
        JSONObject plugin = object("id", "demo@trusted", "name", "demo", "installed", false);
        f.host.take("plugin/list").success(object("marketplaces", array(object("name", "trusted", "path", "/opt/catalog", "plugins", array(plugin)))));
        f.dispatch("plugin_read", object("id", "demo@trusted"));
        f.host.take("plugin/read").success(object("plugin", object("summary", plugin, "description", "Reviewable demo")));
        equal("demo@trusted", child(child(f.state(), "pluginDetails"), "summary").optString("id"), "successful review belongs to actual selected plugin");
        f.dispatch("plugin_read", object("id", "demo@trusted"));
        equal(0, child(f.state(), "pluginDetails").length(), "starting a re-read invalidates the previous review");
        f.host.take("plugin/read").failure(object("code", -32000, "message", "Marketplace unavailable"));
        equal(0, child(f.state(), "pluginDetails").length(), "failed re-read cannot restore stale review details");
        f.dispatch("plugin_install", object("id", "demo@trusted", "confirmed", true));
        equal(0, f.host.count("plugin/install"), "stale review cannot authorize plugin installation after a failed read");
        check(!f.state().optString("error").isEmpty(), "blocked installation explains missing current review");
    }

    private static void reloadUsesNull() throws Exception {
        Fixture f = new Fixture(); f.dispatch("mcp_reload");
        Call reload = f.host.take("config/mcpServer/reload");
        check(reload.params == null, "reload must send protocol null params, not an empty object");
        check(f.controller.changing(), "reload locks mutations until acknowledged");
        reload.success(new JSONObject());
        check(!f.controller.changing(), "acknowledged reload releases mutation lock");
        equal(1, f.host.count("mcpServerStatus/list"), "reload checks actual server runtime");
    }

    private static void identityValidation() throws Exception {
        Fixture f = new Fixture(); f.loadMcp(server("known"));
        f.dispatch("mcp_toggle", object("name", "not-in-catalog", "enabled", true));
        equal(0, f.host.count("config/value/write"), "unknown MCP identity cannot write config");
        check(!f.state().optString("error").isEmpty(), "invalid catalog selection exposes error");
        f.dispatch("mcp_toggle", object("name", "known", "enabled", "true"));
        equal(0, f.host.count("config/value/write"), "string boolean cannot authorize a write");
        f.dispatch("mcp_toggle", object("name", "known", "enabled", false, "keyPath", "unrelated.setting"));
        Call write = f.host.take("config/value/write");
        equal("mcp_servers.known.enabled", write.params.optString("keyPath"), "caller cannot substitute arbitrary config key");
        check(Boolean.FALSE.equals(write.params.opt("value")), "explicit requested enable state preserved");
        Fixture preview = new Fixture(); preview.dispatch("plugin_install", object("id", "invented", "confirmed", true));
        equal(0, preview.host.countPrefix("plugin/"), "plugin writes require opt-in and current catalog");
        Fixture skills = new Fixture(); skills.dispatch("skills_refresh");
        skills.host.take("skills/list").success(object("data", array(object("cwd", "/home/coder/Projects/demo", "skills", array(object("name", "review", "path", "/home/coder/Projects/demo/.agents/skills/review/SKILL.md", "enabled", false))))));
        boolean refused = false;
        try { skills.controller.skillInput("/home/coder/Projects/demo/.agents/skills/review/SKILL.md"); }
        catch (IllegalStateException expected) { refused = true; }
        check(refused, "disabled skill cannot be attached as enabled context");
    }

    private static void resetRejectsStaleCallbacks() throws Exception {
        Fixture f = new Fixture(); f.dispatch("refresh");
        Call oldMcp = f.host.take("mcpServerStatus/list"), oldApp = f.host.take("app/list"), oldRuntime = f.host.take("app/installed");
        f.controller.reset("/home/coder/Projects/other", "new-thread");
        oldMcp.success(object("data", array(server("old"))));
        oldApp.success(object("data", array(app("old"))));
        oldRuntime.success(object("apps", array(object("id", "old", "enabled", true, "callable", true))));
        equal(0, rows(f.state(), "mcp").length(), "old project MCP response ignored");
        equal(0, rows(f.state(), "apps").length(), "old project app response ignored");
        check(!f.state().optBoolean("mcpKnown"), "old response cannot mark new project inventory known");
        f.dispatch("mcp_add", object("name", "new_server", "url", "https://mcp.example.com/mcp"));
        Call pendingWrite = f.host.take("config/read");
        f.controller.reset("/home/coder/Projects/third", "third-thread");
        pendingWrite.success(object("config", object("mcp_servers", new JSONObject())));
        equal(0, f.host.count("config/value/write"), "stale config-read callback cannot write into a new scope");
        f.dispatch("mcp_refresh"); Call closing = f.host.take("mcpServerStatus/list"); f.controller.closed();
        closing.success(object("data", array(server("after-close"))));
        equal(0, rows(f.state(), "mcp").length(), "closed controller ignores late response");
    }

    private static void overlapPreservesMutation() throws Exception {
        Fixture f = new Fixture(); f.loadMcp(server("one"));
        f.dispatch("mcp_toggle", object("name", "one", "enabled", false));
        Call write = f.host.take("config/value/write");
        check(f.controller.changing(), "first mutation owns lock");
        f.dispatch("skill_create", object("name", "should-not-run", "description", "test", "instructions", "test"));
        equal(0, f.host.skillsCreated, "overlapping local mutation never runs");
        equal("mcp_toggle", f.state().optString("operation"), "rejected second request does not clear first operation");
        check(f.controller.changing(), "first mutation remains locked after rejection");
        f.dispatch("refresh"); equal(1, f.host.count("mcpServerStatus/list"), "overlapping refresh does not replace pending mutation");
        write.success(new JSONObject());
        check(f.controller.changing(), "config save keeps lock through reload");
        f.host.take("config/mcpServer/reload").success(new JSONObject());
        check(!f.controller.changing(), "completed write and reload release lock");
    }

    private static void oauthRequiresRuntimeRefresh() throws Exception {
        Fixture f = new Fixture(); f.loadMcp(server("oauth"));
        f.dispatch("mcp_oauth", object("name", "oauth"));
        Call login = f.host.take("mcpServer/oauth/login");
        login.success(object("authorizationUrl", "https://identity.example.com/authorize?state=opaque"));
        check(f.state().optBoolean("oauthPending"), "browser URL leaves authorization pending");
        equal("notLoggedIn", rows(f.state(), "mcp").getJSONObject(0).optString("authStatus"), "URL cannot claim login success");
        equal(1, f.host.count("mcpServerStatus/list"), "URL response does not invent runtime verification");
        f.controller.event("mcpServer/oauthLogin/completed", object("name", "another", "success", true));
        check(f.state().optBoolean("oauthPending"), "completion for other server ignored");
        f.controller.event("mcpServer/oauthLogin/completed", object("name", "oauth", "success", true));
        check(!f.state().optBoolean("oauthPending") && f.state().optString("oauthUrl").isEmpty(), "matching completion clears pending URL");
        equal(2, f.host.count("mcpServerStatus/list"), "completion triggers actual runtime read");
        equal("notLoggedIn", rows(f.state(), "mcp").getJSONObject(0).optString("authStatus"), "old runtime is not upgraded before reply");
        f.host.take("mcpServerStatus/list").success(object("data", array(object("name", "oauth", "authStatus", "oAuth", "tools", object("read", object("name", "read"))))));
        equal("oAuth", rows(f.state(), "mcp").getJSONObject(0).optString("authStatus"), "confirmed engine runtime becomes visible");
    }

    private static void oauthCompletionDuringRead() throws Exception {
        Fixture f = new Fixture(); f.loadMcp(server("oauth"));
        f.dispatch("mcp_oauth", object("name", "oauth"));
        f.host.take("mcpServer/oauth/login").success(object("authorizationUrl", "https://identity.example.com/authorize"));
        f.dispatch("mcp_refresh"); Call beforeCompletion = f.host.take("mcpServerStatus/list");
        f.controller.event("mcpServer/oauthLogin/completed", object("name", "oauth", "success", true));
        beforeCompletion.success(object("data", array(server("oauth"))));
        check(f.host.count("mcpServerStatus/list") >= 3, "OAuth completion must schedule a status read after completion, even if an older read is in flight");
    }

    private static void oauthCompletionBeforeReply() throws Exception {
        Fixture f = new Fixture(); f.loadMcp(server("oauth"));
        f.dispatch("mcp_oauth", object("name", "oauth")); Call login = f.host.take("mcpServer/oauth/login");
        f.controller.event("mcpServer/oauthLogin/completed", object("name", "oauth", "success", false, "error", "Sign-in cancelled"));
        login.success(object("authorizationUrl", "https://identity.example.com/stale"));
        check(!f.controller.changing(), "completed OAuth event must release its mutation lock even before URL reply");
        check(f.state().optString("oauthUrl").isEmpty(), "late URL cannot resurrect a finished login attempt");
        check(!f.state().optBoolean("oauthPending"), "late URL cannot restore pending state");
    }

    private static JSONObject server(String name) { return object("name", name, "authStatus", "notLoggedIn", "tools", new JSONObject()); }
    private static JSONObject app(String id) { return object("id", id, "name", "App " + id, "isConnected", true, "installUrl", "https://chatgpt.com/apps/" + id); }
    private static JSONArray rows(JSONObject state, String key) { return list(state, key); }

    private static final class Fixture {
        final FakeHost host = new FakeHost(); final CodexIntegrations controller = new CodexIntegrations(host);
        Fixture() { controller.reset("/home/coder/Projects/demo", "thread-demo"); }
        void dispatch(String op) { dispatch(op, new JSONObject()); }
        void dispatch(String op, JSONObject params) { controller.dispatch(op, params); }
        JSONObject state() { return controller.snapshot(); }
        void loadMcp(JSONObject row) { dispatch("mcp_refresh"); host.take("mcpServerStatus/list").success(object("data", array(row))); }
    }
    private static final class Call {
        final String method; final JSONObject params; final CodexIntegrations.Reply callback;
        boolean taken, answered;
        Call(String method, JSONObject params, CodexIntegrations.Reply callback) { this.method = method; this.params = params; this.callback = callback; }
        void success(JSONObject result) { if (answered) throw new AssertionError("Callback answered twice"); answered = true; callback.receive(result, null); }
        void failure(JSONObject error) { if (answered) throw new AssertionError("Callback answered twice"); answered = true; callback.receive(new JSONObject(), error); }
    }
    private static final class FakeHost implements CodexIntegrations.Host {
        final List<Call> calls = new ArrayList<>(); int changes, skillsCreated;
        public void request(String method, JSONObject params, CodexIntegrations.Reply reply) { calls.add(new Call(method, params, reply)); }
        public void createSkill(String name, String description, String instructions) { skillsCreated++; }
        public void changed() { changes++; }
        Call take(String method) { for (Call call : calls) if (!call.taken && method.equals(call.method)) { call.taken = true; return call; } throw new AssertionError("No pending request: " + method); }
        int count(String method) { int count = 0; for (Call call : calls) if (method.equals(call.method)) count++; return count; }
        int countPrefix(String prefix) { int count = 0; for (Call call : calls) if (call.method.startsWith(prefix)) count++; return count; }
    }
    private static void run(String name, Case test) throws Exception {
        try { test.run(); System.out.println("PASS " + name); }
        catch (AssertionError error) { failures++; System.out.println("FAIL " + name + ": " + error.getMessage()); }
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) { check(expected.equals(actual), message + " (expected " + expected + ", got " + actual + ")"); }
}
