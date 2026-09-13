package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.pocketagent.mobile.AgentProtocol.*;

/** Catalog identity, narrow settings writes and browser handoff boundary fixtures. */
public final class CodexIntegrationsProtocolTest {
    private static int assertions;
    private static Path schemas;
    private interface Action { void run(); }

    public static void main(String[] args) throws Exception {
        schemas = Paths.get(args[0], "tests/integrations-protocol/schemas/codex-0.154.0");
        safeMcpConfiguration();
        browserHandoff();
        skillIdentity();
        pluginIdentity();
        accountStateIsNotInvented();
        boundedNativeInventory();
        pinnedRequestShapes();
        System.out.println("PASS CodexIntegrationsProtocolTest (" + assertions + " assertions)");
    }

    private static void safeMcpConfiguration() {
        JSONObject add = CodexIntegrationsProtocol.mcpAddParams("my-server_2", "https://tools.example.com/mcp");
        equal("mcp_servers.my-server_2", add.optString("keyPath"), "MCP key must stay in its own configuration entry");
        check(add.length() == 3 && !add.has("filePath"), "UI cannot choose a config file or unrelated write option");
        JSONObject entry = child(add, "value");
        check(entry.length() == 2 && entry.optBoolean("enabled") && entry.has("url"), "no command, header or policy injected into URL-only setup");
        for (String name : new String[]{"", "_apps", "codex_apps", "foo.bar", "a\".approval_policy", "../bad", "x\nnext", "a b", "x;run"})
            rejected(() -> CodexIntegrationsProtocol.mcpAddParams(name, "https://tools.example.com/mcp"), "unsafe MCP name accepted");
        for (String url : new String[]{"http://tools.example.com/mcp", "https://user:secret@tools.example.com/mcp",
                "https://tools.example.com/mcp?api_key=secret", "https://tools.example.com/mcp#secret",
                "file:///root/.codex/auth.json", "https://127.0.0.2/mcp", "https://[::1]/mcp", "https://localhost/mcp",
                "https://tools.example.com\\@evil.example/mcp", "https://tools.example.com/mcp\nnext"})
            rejected(() -> CodexIntegrationsProtocol.remoteMcpUrl(url), "unsafe MCP endpoint accepted");
        JSONObject toggle = CodexIntegrationsProtocol.mcpToggleParams(object("name", "existing"), false);
        equal("mcp_servers.existing.enabled", toggle.optString("keyPath"), "toggle must address only enabled leaf");
        check(Boolean.FALSE.equals(toggle.opt("value")), "disabled value is a JSON boolean");
        rejected(() -> CodexIntegrationsProtocol.mcpToggleParams(object("name", "existing", "pluginId", "owned"), false), "plugin-owned MCP must stay under plugin management");
        rejected(() -> CodexIntegrationsProtocol.appToggleParams(object("id", "_default"), false), "app picker must not write all-app defaults");
        rejected(() -> CodexIntegrationsProtocol.appToggleParams(object("id", "a.default_tools_approval_mode"), true), "app ID changed another config field");
        equal("features.apps", CodexIntegrationsProtocol.appsEnableParams().optString("keyPath"), "only fixed apps feature may be enabled");
    }

    private static void browserHandoff() {
        String issuer = "https://identity.vendor.example/authorize?client_id=native&state=random&redirect_uri=http%3A%2F%2Flocalhost%3A5000";
        equal(issuer, CodexIntegrationsProtocol.oauthUrl(issuer), "MCP identity provider need not share endpoint host; official flow remains intact");
        for (String url : new String[]{"javascript:alert(1)", "http://identity.vendor.example", "https://user@identity.vendor.example", "https://identity.vendor.example/#access_token=secret"})
            rejected(() -> CodexIntegrationsProtocol.oauthUrl(url), "unsafe OAuth URL accepted");
        String githubApp = "https://chatgpt.com/apps/github?source=codex";
        equal(githubApp, CodexIntegrationsProtocol.appInstallUrl(object("id", "actual-returned-app-id", "installUrl", githubApp)), "use returned official URL, including GitHub app handoff");
        for (String url : new String[]{"https://chatgpt.com.evil.example/connect", "https://evilchatgpt.com/connect", "https://chatgpt.com@evil.example/connect", "https://evil@chatgpt.com/connect", "https://github.com/guessed/app/install", "https://chatgpt.com:8443/connect"})
            rejected(() -> CodexIntegrationsProtocol.appInstallUrl(object("installUrl", url)), "non-official account URL accepted");
    }

    private static void skillIdentity() {
        JSONObject skill = object("name", "web-build", "path", "/root/.codex/skills/web-build/SKILL.md", "enabled", true, "scope", "user");
        JSONArray rows = CodexIntegrationsProtocol.skillRows(array(object("cwd", "/home/coder/Projects/demo", "skills", array(skill))));
        JSONObject selected = CodexIntegrationsProtocol.findRow(rows, "path", skill.optString("path"));
        check(selected != null, "returned skill is selectable by exact path");
        JSONObject input = CodexIntegrationsProtocol.skillInput(selected);
        check(input.length() == 3 && "skill".equals(input.optString("type")), "skill invocation uses typed protocol input, not injected prompt/shell text");
        equal(skill.optString("path"), input.optString("path"), "exact returned skill path retained");
        check(CodexIntegrationsProtocol.findRow(rows, "path", "/root/.codex/auth.json") == null, "arbitrary path cannot resolve from current catalog");
        rejected(() -> CodexIntegrationsProtocol.skillInput(object("name", "off", "path", "/root/off/SKILL.md", "enabled", false)), "disabled skill invoked");
        for (String path : new String[]{"relative/SKILL.md", "/root/../etc/passwd", "/root//skill/SKILL.md", "/root/./skill/SKILL.md", "/root/skill\\SKILL.md", "/root/bad\nfile"})
            rejected(() -> CodexIntegrationsProtocol.skillToggleParams(object("path", path), true), "non-normalized skill path accepted");
        check(CodexIntegrationsProtocol.findRow(array(skill, skill), "path", skill.optString("path")) == null, "ambiguous catalog selections fail closed");
    }

    private static void pluginIdentity() {
        JSONObject plugin = object("id", "plugin-actual-id", "name", "research", "installed", false, "enabled", false,
                "installPolicy", "AVAILABLE", "availability", "AVAILABLE", "source", object("type", "remote"),
                "marketplacePath", "/root/forged.json", "marketplaceName", "forged");
        JSONArray rows = CodexIntegrationsProtocol.pluginRows(array(object("name", "returned-market", "path", JSONObject.NULL, "plugins", array(plugin))));
        JSONObject selected = CodexIntegrationsProtocol.findRow(rows, "id", "plugin-actual-id");
        JSONObject install = CodexIntegrationsProtocol.pluginInstallParams(selected);
        equal("research", install.optString("pluginName"), "plugin install uses exact catalog name");
        equal("returned-market", install.optString("remoteMarketplaceName"), "marketplace identity comes from parent catalog entry");
        check(!install.has("marketplacePath"), "remote plugin cannot inject a fake local marketplace path");
        JSONObject local = object("id", "local@market", "name", "tool", "marketplacePath", "/root/.codex/plugins/marketplace.json", "installed", true);
        equal(local.optString("marketplacePath"), CodexIntegrationsProtocol.pluginParams(local).optString("marketplacePath"), "local marketplace path retained exactly");
        equal("local@market", CodexIntegrationsProtocol.pluginUninstallParams(local).optString("pluginId"), "uninstall preserves opaque server ID");
        rejected(() -> CodexIntegrationsProtocol.pluginInstallParams(local), "already installed plugin should not create duplicate installation");
        rejected(() -> CodexIntegrationsProtocol.pluginInstallParams(object("name", "blocked", "marketplaceName", "official", "availability", "DISABLED_BY_ADMIN")), "admin-disabled plugin was installable");
        rejected(() -> CodexIntegrationsProtocol.pluginParams(object("name", "../escape", "marketplaceName", "official")), "plugin install name escaped its component");
    }

    private static void accountStateIsNotInvented() {
        JSONArray apps = CodexIntegrationsProtocol.appRows(array(object("id", "github-real", "name", "GitHub", "isAccessible", true), object("id", "unknown", "name", "Unknown")));
        JSONArray runtime = array(object("id", "github-real", "enabled", true, "callable", false));
        JSONArray merged = CodexIntegrationsProtocol.mergeAppRuntime(apps, runtime, true);
        JSONObject github = merged.optJSONObject(0), unknown = merged.optJSONObject(1);
        check(github.optBoolean("isAccessible") && github.optBoolean("runtimeEnabled") && !github.optBoolean("runtimeCallable"), "connected/accessible is not the same as callable tools");
        check(github.optBoolean("runtimeKnown"), "complete real runtime row is known");
        check(!unknown.optBoolean("runtimeKnown") && !unknown.has("runtimeEnabled"), "unlisted runtime remains unknown");
        JSONObject stale = CodexIntegrationsProtocol.mergeAppRuntime(merged, runtime, false).optJSONObject(0);
        check(!stale.optBoolean("runtimeKnown") && !stale.has("runtimeCallable"), "failed runtime refresh cannot preserve a fresh-looking callable badge");
        JSONObject malformed = CodexIntegrationsProtocol.mergeAppRuntime(apps, array(object("id", "github-real", "enabled", true)), true).optJSONObject(0);
        check(!malformed.optBoolean("runtimeKnown"), "missing callable field is not fabricated");
        check(!apps.optJSONObject(1).has("isEnabled"), "missing API metadata remains absent in catalog normalization");
    }

    private static void pinnedRequestShapes() throws Exception {
        shape("ListMcpServerStatusParams", CodexIntegrationsProtocol.listMcpParams("opaque-cursor", "thread-one"));
        shape("McpServerOauthLoginParams", CodexIntegrationsProtocol.oauthParams("vendor.plugin:server", "thread-one"));
        shape("SkillsListParams", CodexIntegrationsProtocol.skillsListParams("/home/coder/Projects/demo", true));
        shape("SkillsConfigWriteParams", CodexIntegrationsProtocol.skillToggleParams(object("path", "/root/skills/tool/SKILL.md"), false));
        shape("PluginListParams", CodexIntegrationsProtocol.pluginListParams("/home/coder/Projects/demo", true));
        shape("PluginInstallParams", CodexIntegrationsProtocol.pluginInstallParams(object("name", "plugin", "marketplaceName", "actual-market")));
        shape("PluginUninstallParams", CodexIntegrationsProtocol.pluginUninstallParams(object("id", "actual-id", "installed", true)));
        shape("AppsListParams", CodexIntegrationsProtocol.appsListParams("cursor", "thread-one", true));
        shape("AppsInstalledParams", CodexIntegrationsProtocol.appsInstalledParams(true, "thread-one"));
        shape("ConfigValueWriteParams", CodexIntegrationsProtocol.mcpAddParams("remote", "https://tools.example.com/mcp"));
        shape("ConfigReadParams", CodexIntegrationsProtocol.configReadParams("/home/coder/Projects/demo"));
        shape("ExperimentalFeatureListParams", CodexIntegrationsProtocol.featuresListParams("cursor"));
    }

    private static void boundedNativeInventory() {
        JSONObject tools = new JSONObject();
        String largeSchema = repeated('x', 8000);
        for (int i = 0; i < 30; i++) optionalForTest(tools, "tool_" + i,
                object("name", "tool_" + i, "description", "Tool description", "inputSchema", object("privateFixture", largeSchema)));
        JSONArray mcp = CodexIntegrationsProtocol.mcpRows(array(object("name", "server", "authStatus", "oAuth", "tools", tools,
                "resources", array(object("uri", "secret-resource-fixture")))));
        check(mcp.optJSONObject(0).optInt("toolCount") == 30, "tool count remains the real count despite compact display");
        check(mcp.optJSONObject(0).optBoolean("inventoryTruncated"), "partial tool summary is labeled");
        check(!mcp.toString().contains("inputSchema") && !mcp.toString().contains("secret-resource-fixture"), "inventory snapshot excludes schemas and raw resources");

        JSONArray manyApps = new JSONArray();
        for (int i = 0; i < 100; i++) manyApps.put(object("id", "id-" + i, "name", "App " + i,
                "description", repeated('d', 1000), "iconAssets", object("privateFixture", repeated('x', 12000))));
        JSONArray apps = CodexIntegrationsProtocol.appRows(manyApps);
        check(apps.toString().length() <= CodexIntegrationsProtocol.MAX_CATALOG_CHARS, "app section remains within native snapshot budget");
        check(CodexIntegrationsProtocol.catalogTruncated(apps), "partial app list is labeled");
        check(!apps.toString().contains("iconAssets"), "remote icon bundles do not enter snapshot");
        JSONArray combined = new JSONArray();
        for (int i = 0; i < 6; i++) for (int j = 0; j < apps.length(); j++) combined.put(apps.optJSONObject(j));
        check(CodexIntegrationsProtocol.boundedRows(combined).toString().length() <= CodexIntegrationsProtocol.MAX_CATALOG_CHARS, "pagination cannot bypass final section budget");

        JSONObject detail = object("summary", object("id", "plugin-exact", "name", "tool", "source", object("type", "remote")),
                "description", "Useful plugin", "marketplaceName", "returned-market", "apps", array(object("id", "app-exact", "name", "Service")),
                "mcpServers", array("mcp-exact"), "skills", array(object("name", "skill-exact")),
                "hooks", array(object("eventName", "SessionStart", "key", "reviewed_hook")),
                "scheduledTasks", array(object("name", "scheduled-exact")), "appTemplates", new JSONArray());
        JSONObject compact = CodexIntegrationsProtocol.pluginDetails(detail);
        check(!compact.optBoolean("detailsTruncated"), "complete small capability review remains install-reviewable");
        equal("plugin-exact", child(compact, "summary").optString("id"), "review identity remains exact");
        check(compact.toString().contains("reviewed_hook") && compact.has("apps") && compact.has("scheduledTasks"), "hook and service/schedule disclosures retained");
        optionalForTest(detail, "description", repeated('s', 50000));
        JSONObject truncated = CodexIntegrationsProtocol.pluginDetails(detail);
        check(truncated.optBoolean("detailsTruncated"), "incomplete permission review must block native installation");
        check(truncated.toString().length() <= CodexIntegrationsProtocol.MAX_PLUGIN_DETAILS_CHARS, "plugin review payload remains bounded");
        JSONObject pathological = object("summary", object("id", "exact-id", "name", "tool"), repeated('\0', 5000), "oversized escaped key");
        JSONObject limited = CodexIntegrationsProtocol.pluginDetails(pathological);
        check(limited.optBoolean("detailsTruncated") && limited.toString().length() <= CodexIntegrationsProtocol.MAX_PLUGIN_DETAILS_CHARS,
                "escaped object keys cannot defeat final serialized review limit");
    }

    private static String repeated(char letter, int length) { char[] value = new char[length]; java.util.Arrays.fill(value, letter); return new String(value); }
    private static void optionalForTest(JSONObject value, String key, Object item) { try { value.put(key, item); } catch (Exception impossible) { throw new AssertionError(impossible); } }

    private static void shape(String name, JSONObject params) throws Exception {
        JSONObject schema = new JSONObject(new String(Files.readAllBytes(schemas.resolve(name + ".json")), StandardCharsets.UTF_8));
        JSONArray required = schema.optJSONArray("required");
        if (required != null) for (int i = 0; i < required.length(); i++) check(params.has(required.optString(i)), name + " missing required field");
        JSONObject fields = schema.getJSONObject("properties");
        for (java.util.Iterator<String> keys = params.keys(); keys.hasNext();) check(fields.has(keys.next()), name + " sent an invented field");
    }
    private static void rejected(Action action, String why) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, why);
    }
    private static void equal(String expected, String actual, String why) { check(expected.equals(actual), why); }
    private static void check(boolean valid, String why) { assertions++; if (!valid) throw new AssertionError(why); }
}
