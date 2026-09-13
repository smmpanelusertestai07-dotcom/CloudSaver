package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

import static com.pocketagent.mobile.AgentProtocol.*;

/** Native management of the connected official engine. Never owns credentials or invokes model tools. */
final class CodexIntegrations {
    interface Reply { void receive(JSONObject result, JSONObject error); }
    interface Host {
        void request(String method, JSONObject params, Reply reply) throws Exception;
        void createSkill(String name, String description, String instructions) throws Exception;
        void changed();
    }
    private static final class Section {
        JSONArray rows = new JSONArray(); boolean loading, known, truncated; String error = ""; long updated, sequence;
        void reset() { rows = new JSONArray(); loading = false; known = false; truncated = false; error = ""; updated = 0; sequence++; }
    }
    private final Host host;
    private final Section mcp = new Section(), skills = new Section(), apps = new Section(), plugins = new Section();
    private String cwd = "", thread = "", operation = "", status = "Connect to load your tools", error = "";
    private String oauthUrl = "", oauthServer = "", browserUrl = "", browserKind = "", browserTitle = "";
    private boolean oauthPending, pluginPreview;
    private Boolean appsFeatureEnabled;
    private JSONArray skillErrors = new JSONArray(), marketplaceErrors = new JSONArray(), appsNeedingAuth = new JSONArray();
    private JSONObject pluginDetails = new JSONObject();
    private JSONArray installedApps = new JSONArray();
    private boolean appRuntimeKnown;
    private long epoch, appRuntimeUpdatedAt, appsFeatureSequence, oauthSequence, appRuntimeSequence, nextAutoRefresh;
    private String appRuntimeError = "", appsFeatureError = "";

    CodexIntegrations(Host host) { this.host = host; }

    void reset(String workingDirectory, String threadId) {
        epoch++; cwd = workingDirectory; thread = threadId; nextAutoRefresh = 0;
        for (Section section : new Section[]{mcp, skills, apps, plugins}) section.reset();
        operation = ""; status = "Connect to load your tools"; error = "";
        oauthUrl = ""; oauthServer = ""; oauthPending = false; oauthSequence++; browserUrl = ""; browserKind = ""; browserTitle = "";
        pluginPreview = false; pluginDetails = new JSONObject(); appsNeedingAuth = new JSONArray();
        skillErrors = new JSONArray(); marketplaceErrors = new JSONArray(); installedApps = new JSONArray();
        appsFeatureEnabled = null; appRuntimeKnown = false; appRuntimeError = ""; appsFeatureError = "";
        appRuntimeUpdatedAt = 0; appsFeatureSequence++; appRuntimeSequence++;
    }

    /** Read actual inventories while the service has a visible verified account, with bounded retry. */
    void autoRefresh(long now) {
        if (cwd.isEmpty() || thread.isEmpty() || !operation.isEmpty() || oauthPending || now < nextAutoRefresh) return;
        // Cached reads do not install, authorize, enable, or call any tool. Plugins keep their explicit preview gate.
        boolean failed = !mcp.error.isEmpty() || !skills.error.isEmpty() || !apps.error.isEmpty() || !appRuntimeError.isEmpty();
        nextAutoRefresh = now + (failed ? 60000L : 30000L);
        if (!mcp.loading) loadMcp();
        if (!skills.loading) loadSkills(false);
        if (!apps.loading) loadApps(false);
    }

    void thread(String threadId) {
        if (!thread.equals(threadId) && oauthPending) {
            oauthSequence++; oauthPending = false; oauthUrl = "";
            if ("mcp_oauth".equals(operation)) operation = "";
            status = "Conversation changed. Checking tools before another sign-in.";
        }
        thread = threadId;
    }
    void closed() {
        epoch++; operation = ""; oauthUrl = ""; oauthPending = false; browserUrl = "";
        for (Section section : new Section[]{mcp, skills, apps, plugins}) { section.loading = false; section.known = false; section.sequence++; }
        appRuntimeKnown = false; apps.rows = CodexIntegrationsProtocol.mergeAppRuntime(apps.rows, new JSONArray(), false);
        status = "Connect Codex to load tools"; host.changed();
    }

    static boolean mutates(String op) {
        return "mcp_add".equals(op) || "mcp_toggle".equals(op) || "mcp_oauth".equals(op) || "mcp_reload".equals(op)
                || "skill_toggle".equals(op) || "skill_create".equals(op) || "app_toggle".equals(op) || "apps_enable".equals(op)
                || "plugin_install".equals(op) || "plugin_uninstall".equals(op);
    }
    boolean changing() { return mutates(operation); }

    void reject(String message) { error = clean(message, 600); status = error; host.changed(); }

    void dispatch(String op, JSONObject payload) {
        if (!operation.isEmpty()) { reject("Wait for the current integration request to finish."); return; }
        try {
            if (op == null) throw new IllegalArgumentException("Choose an integration action.");
            error = "";
            if ("refresh".equals(op)) { status = "Refreshing your Codex integrations"; loadMcp(); loadSkills(true); loadApps(true); }
            else if ("mcp_refresh".equals(op)) loadMcp();
            else if ("skills_refresh".equals(op)) loadSkills(true);
            else if ("apps_refresh".equals(op)) loadApps(true);
            else if ("plugins_load".equals(op)) { pluginPreview = true; loadPlugins(true); }
            else if ("mcp_reload".equals(op)) { begin(op, "Reloading configured MCP servers"); reload(() -> { finish("MCP configuration reloaded"); loadMcp(); }); }
            else if ("mcp_oauth".equals(op)) oauth(require(mcp, "name", payload.optString("name")));
            else if ("mcp_add".equals(op)) addMcp(payload);
            else if ("mcp_toggle".equals(op)) configChange(op, CodexIntegrationsProtocol.mcpToggleParams(require(mcp, "name", payload.optString("name")), bool(payload, "enabled")), "MCP setting saved");
            else if ("app_toggle".equals(op)) configChange(op, CodexIntegrationsProtocol.appToggleParams(require(apps, "id", payload.optString("id")), bool(payload, "enabled")), "App setting saved");
            else if ("apps_enable".equals(op)) configChange(op, CodexIntegrationsProtocol.appsEnableParams(), "Apps enabled in your Codex configuration");
            else if ("skill_toggle".equals(op)) toggleSkill(payload);
            else if ("skill_create".equals(op)) {
                begin(op, "Saving your project skill");
                host.createSkill(payload.optString("name"), payload.optString("description"), payload.optString("instructions"));
                finish("Skill file saved · checking Codex discovery"); loadSkills(true);
            } else if ("plugin_read".equals(op)) readPlugin(payload.optString("id"));
            else if ("plugin_install".equals(op) || "plugin_uninstall".equals(op)) changePlugin(op, payload);
            else if ("app_open".equals(op)) {
                JSONObject app = require(apps, "id", payload.optString("id"));
                browserUrl = CodexIntegrationsProtocol.appInstallUrl(app);
                if (browserUrl.isEmpty()) throw new IllegalArgumentException("Codex did not provide an official connection page for this app.");
                browserKind = "app"; browserTitle = app.optString("name", "Connect app");
                status = "Open the official page, then return to check access";
            } else if ("dismiss_browser".equals(op)) { browserUrl = ""; browserKind = ""; browserTitle = ""; }
            else throw new IllegalArgumentException("This integration action is not supported by this build.");
            host.changed();
        } catch (Exception failure) { operation = ""; reject(failure.getMessage()); }
    }

    JSONObject skillInput(String path) {
        JSONObject skill = require(skills, "path", path);
        if (!Boolean.TRUE.equals(skill.opt("enabled"))) throw new IllegalStateException("Enable this skill in Connections before attaching it.");
        return CodexIntegrationsProtocol.skillInput(skill);
    }
    JSONObject appInput(String id) {
        JSONObject app = require(apps, "id", id);
        if (!Boolean.TRUE.equals(app.opt("runtimeKnown")) || !Boolean.TRUE.equals(app.opt("runtimeCallable")))
            throw new IllegalStateException("Complete this app's official connection first. Codex has not reported its tools ready yet.");
        return object("type", "mention", "name", app.optString("name"), "path", "app://" + app.optString("id"));
    }

    private void loadMcp() {
        if (mcp.loading) return;
        long own = start(mcp);
        pageMcp(own, "", new JSONArray(), new HashSet<String>(), 0);
    }
    private void pageMcp(long own, String cursor, JSONArray rows, Set<String> seen, int page) {
        call("mcpServerStatus/list", CodexIntegrationsProtocol.listMcpParams(cursor, ""), (result, failure) -> {
            if (own != mcp.sequence) return;
            if (failure != null) { failed(mcp, failure); return; }
            append(rows, CodexIntegrationsProtocol.mcpRows(list(result, "data")), "name");
            if (rows.length() > 100) { trim(rows, 100); mcp.truncated = true; }
            String next = textValue(result, "nextCursor");
            if (!next.isEmpty()) {
                if (!seen.add(next)) { failed(mcp, problem("MCP list was incomplete. Refresh to retry.")); return; }
                if (page >= 19 || rows.length() >= 100) { mcp.truncated = true; complete(mcp, rows); return; }
                pageMcp(own, next, rows, seen, page + 1);
            } else complete(mcp, rows);
        });
    }

    private void loadSkills(boolean force) {
        if (skills.loading) return;
        long own = start(skills);
        call("skills/list", CodexIntegrationsProtocol.skillsListParams(cwd, force), (result, failure) -> {
            if (own != skills.sequence) return;
            if (failure != null) { failed(skills, failure); return; }
            JSONArray groups = list(result, "data"); skillErrors = new JSONArray();
            int count = 0;
            for (int i = 0; i < groups.length(); i++) { count += list(childAt(groups, i), "skills").length(); append(skillErrors, list(childAt(groups, i), "errors"), "path"); }
            skillErrors = errors(skillErrors);
            skills.truncated = count > 150;
            complete(skills, limit(CodexIntegrationsProtocol.skillRows(groups), 150));
        });
    }

    private void loadPlugins(boolean force) {
        if (!pluginPreview || plugins.loading) return;
        long own = start(plugins);
        call("plugin/list", CodexIntegrationsProtocol.pluginListParams(cwd, force), (result, failure) -> {
            if (own != plugins.sequence) return;
            if (failure != null) { failed(plugins, failure); return; }
            marketplaceErrors = errors(list(result, "marketplaceLoadErrors"));
            JSONArray markets = list(result, "marketplaces"); int count = 0;
            for (int i = 0; i < markets.length(); i++) count += list(childAt(markets, i), "plugins").length();
            plugins.truncated = count > 100;
            complete(plugins, limit(CodexIntegrationsProtocol.pluginRows(markets), 100));
        });
    }

    private void loadApps(boolean force) {
        if (apps.loading) return;
        long own = start(apps);
        appRuntimeKnown = false; appRuntimeError = ""; installedApps = new JSONArray();
        apps.rows = CodexIntegrationsProtocol.mergeAppRuntime(apps.rows, installedApps, false);
        loadFeatures();
        readAppRuntime(own, force);
        pageApps(own, "", new JSONArray(), new HashSet<String>(), 0, force);
    }
    private void readAppRuntime(long own, boolean force) {
        final long runtimeRead = ++appRuntimeSequence;
        call("app/installed", CodexIntegrationsProtocol.appsInstalledParams(force, ""), (result, failure) -> {
            if (own != apps.sequence || runtimeRead != appRuntimeSequence) return;
            if (failure != null) { appRuntimeKnown = false; appRuntimeError = message(failure); }
            else { installedApps = list(result, "apps"); appRuntimeKnown = true; appRuntimeError = ""; appRuntimeUpdatedAt = System.currentTimeMillis(); }
            apps.rows = CodexIntegrationsProtocol.boundedRows(CodexIntegrationsProtocol.mergeAppRuntime(apps.rows, installedApps, appRuntimeKnown));
            apps.truncated |= CodexIntegrationsProtocol.catalogTruncated(apps.rows);
            host.changed();
        });
    }
    private void pageApps(long own, String cursor, JSONArray rows, Set<String> seen, int page, boolean force) {
        call("app/list", CodexIntegrationsProtocol.appsListParams(cursor, "", force), (result, failure) -> {
            if (own != apps.sequence) return;
            if (failure != null) { failed(apps, failure); return; }
            append(rows, CodexIntegrationsProtocol.appRows(list(result, "data")), "id");
            if (rows.length() > 200) { trim(rows, 200); apps.truncated = true; }
            String next = textValue(result, "nextCursor");
            if (!next.isEmpty()) {
                if (!seen.add(next)) { failed(apps, problem("App list was incomplete. Refresh to retry.")); return; }
                if (page >= 19 || rows.length() >= 200) { apps.truncated = true; complete(apps, CodexIntegrationsProtocol.mergeAppRuntime(rows, installedApps, appRuntimeKnown)); return; }
                pageApps(own, next, rows, seen, page + 1, false);
            } else complete(apps, CodexIntegrationsProtocol.mergeAppRuntime(rows, installedApps, appRuntimeKnown));
        });
    }

    private void loadFeatures() {
        long own = ++appsFeatureSequence; appsFeatureError = "";
        featurePage(own, "", new HashSet<String>(), 0);
    }
    private void featurePage(long own, String cursor, Set<String> seen, int page) {
        call("experimentalFeature/list", CodexIntegrationsProtocol.featuresListParams(cursor), (result, failure) -> {
            if (own != appsFeatureSequence) return;
            if (failure != null) { appsFeatureError = message(failure); appsFeatureEnabled = null; host.changed(); return; }
            JSONArray values = list(result, "data");
            for (int i = 0; i < values.length(); i++) {
                JSONObject row = childAt(values, i);
                if ("apps".equals(row.optString("name")) && row.opt("enabled") instanceof Boolean) {
                    appsFeatureEnabled = row.optBoolean("enabled"); host.changed(); return;
                }
            }
            String next = textValue(result, "nextCursor");
            if (!next.isEmpty() && page < 19 && seen.add(next)) featurePage(own, next, seen, page + 1);
            else { appsFeatureEnabled = null; host.changed(); }
        });
    }

    private void toggleSkill(JSONObject payload) {
        JSONObject row = require(skills, "path", payload.optString("path"));
        boolean enabled = bool(payload, "enabled");
        begin("skill_toggle", "Updating skill preference");
        call("skills/config/write", CodexIntegrationsProtocol.skillToggleParams(row, enabled), (result, failure) -> {
            if (failure != null) { operationFailed(failure); return; }
            finish(result.opt("effectiveEnabled") instanceof Boolean && result.optBoolean("effectiveEnabled") == enabled
                    ? "Skill preference applied" : "Skill preference saved; checking its effective state");
            skills.loading = false; skills.sequence++; loadSkills(true);
        });
    }

    private void addMcp(JSONObject payload) {
        final String name = payload.optString("name");
        final JSONObject write = CodexIntegrationsProtocol.mcpAddParams(name, payload.optString("url"));
        begin("mcp_add", "Checking existing MCP configuration");
        call("config/read", CodexIntegrationsProtocol.configReadParams(cwd), (result, failure) -> {
            if (failure != null) { operationFailed(failure); return; }
            JSONObject servers = child(result, "config").optJSONObject("mcp_servers");
            if (servers == null) { operationFailed(problem("Codex did not expose its configured MCP names. Add was stopped to protect existing servers.")); return; }
            if (servers.has(name) || find(mcp.rows, "name", name) != null) {
                operationFailed(problem("An MCP server with this name already exists. Choose a new name; Add does not replace existing servers.")); return;
            }
            writeConfig(write, "MCP server saved");
        });
    }

    private void configChange(String op, JSONObject params, String done) {
        begin(op, "Saving Codex configuration"); writeConfig(params, done);
    }
    private void writeConfig(JSONObject params, String done) {
        call("config/value/write", params, (result, failure) -> {
            if (failure != null) { operationFailed(failure); return; }
            reload(() -> {
                finish(done + " · checking runtime status");
                mcp.loading = false; mcp.sequence++; apps.loading = false; apps.sequence++;
                loadMcp(); loadApps(true);
            });
        });
    }
    private void reload(Runnable after) {
        // Pinned ClientRequest explicitly uses null params for this method.
        call("config/mcpServer/reload", null, (result, failure) -> {
            if (failure != null) { operationFailed(problem("Configuration was saved, but reload failed: " + message(failure) + " Reconnect before relying on new tools.")); return; }
            after.run();
        });
    }

    private void oauth(JSONObject row) {
        String name = row.optString("name");
        if (oauthPending) {
            if (name.equals(oauthServer)) { status = "Finish the existing official sign-in, then refresh"; return; }
            throw new IllegalStateException("Finish the current MCP sign-in before connecting another server.");
        }
        begin("mcp_oauth", "Requesting the server's official sign-in"); oauthServer = name; oauthUrl = ""; oauthPending = true;
        final long attempt = ++oauthSequence;
        call("mcpServer/oauth/login", CodexIntegrationsProtocol.oauthParams(name, thread), (result, failure) -> {
            if (attempt != oauthSequence) return;
            if (failure != null) { oauthPending = false; operationFailed(failure); return; }
            String url = textValue(result, "authorizationUrl");
            try { oauthUrl = CodexIntegrationsProtocol.oauthUrl(url); }
            catch (IllegalArgumentException invalid) { oauthPending = false; throw invalid; }
            finish("Open the sign-in page after checking its host. Returning alone does not confirm connected access.");
        });
    }

    private void readPlugin(String id) {
        requirePreview(); JSONObject row = require(plugins, "id", id);
        pluginDetails = new JSONObject();
        begin("plugin_read", "Reading plugin capabilities and permissions");
        call("plugin/read", CodexIntegrationsProtocol.pluginParams(row), (result, failure) -> {
            if (failure != null) { operationFailed(failure); return; }
            pluginDetails = CodexIntegrationsProtocol.pluginDetails(child(result, "plugin")); finish("Review the plugin before changing installation");
        });
    }
    private void changePlugin(String op, JSONObject payload) {
        requirePreview(); JSONObject row = require(plugins, "id", payload.optString("id"));
        if (!Boolean.TRUE.equals(payload.opt("confirmed"))) throw new IllegalArgumentException("Review and confirm this plugin change first.");
        boolean install = "plugin_install".equals(op);
        if (install && !row.optString("id").equals(child(pluginDetails, "summary").optString("id")))
            throw new IllegalStateException("Open the plugin details before confirming installation.");
        if (install && pluginDetails.optBoolean("detailsTruncated"))
            throw new IllegalStateException("This plugin's full permission and capability details exceed the native review limit. Installation was stopped until a complete review is available.");
        JSONObject params = install ? CodexIntegrationsProtocol.pluginInstallParams(row) : CodexIntegrationsProtocol.pluginUninstallParams(row);
        begin(op, install ? "Installing plugin preview" : "Removing plugin preview");
        call(install ? "plugin/install" : "plugin/uninstall", params, (result, failure) -> {
            if (failure != null) { operationFailed(failure); return; }
            appsNeedingAuth = install ? CodexIntegrationsProtocol.appRows(list(result, "appsNeedingAuth")) : new JSONArray();
            finish(appsNeedingAuth.length() > 0 ? "Plugin installed; its apps still need their own official authorization" : "Plugin change saved; checking actual runtime state");
            plugins.loading = false; plugins.sequence++; skills.loading = false; skills.sequence++;
            loadPlugins(true); loadSkills(true); loadMcp(); loadApps(true);
        });
    }

    boolean event(String method, JSONObject params) {
        if ("mcpServer/oauthLogin/completed".equals(method)) {
            if (!oauthPending || !oauthServer.equals(params.optString("name"))) return true;
            oauthSequence++; oauthPending = false; oauthUrl = "";
            if ("mcp_oauth".equals(operation)) operation = "";
            if (Boolean.TRUE.equals(params.opt("success"))) {
                status = "Sign-in completed; checking server runtime status"; error = ""; host.changed();
                mcp.loading = false; mcp.sequence++; loadMcp();
            }
            else reject(textValue(params, "error").isEmpty() ? "MCP sign-in did not complete." : textValue(params, "error"));
            return true;
        }
        if ("mcpServer/startupStatus/updated".equals(method)) {
            JSONObject row = find(mcp.rows, "name", params.optString("name"));
            if (row != null) {
                String value = params.optString("status");
                // Startup success is not equivalent to an inventory snapshot showing callable tools.
                put(row, "startupStatus", value); put(row, "startupError", params.opt("error")); host.changed();
            }
            return true;
        }
        if ("skills/changed".equals(method)) { skills.known = false; skills.error = ""; nextAutoRefresh = 0; host.changed(); return true; }
        if ("app/list/updated".equals(method)) {
            JSONArray incoming = list(params, "data"); apps.truncated = incoming.length() > 100;
            apps.rows = CodexIntegrationsProtocol.boundedRows(CodexIntegrationsProtocol.mergeAppRuntime(CodexIntegrationsProtocol.appRows(incoming), new JSONArray(), false));
            apps.truncated |= CodexIntegrationsProtocol.catalogTruncated(apps.rows);
            apps.known = true; apps.updated = System.currentTimeMillis(); appRuntimeKnown = false;
            installedApps = new JSONArray(); appRuntimeSequence++;
            appRuntimeError = "Checking connected tools…"; host.changed();
            // If this is part of the user's refresh, read the newly committed runtime,
            // without triggering another remote refresh or accepting an older response.
            readAppRuntime(apps.sequence, false);
            return true;
        }
        return false;
    }

    JSONObject snapshot() {
        JSONObject result = object("mcp", mcp.rows, "skills", skills.rows, "apps", apps.rows, "plugins", plugins.rows,
                "pluginDetails", pluginDetails, "appsNeedingAuth", appsNeedingAuth, "skillErrors", skillErrors,
                "marketplaceErrors", marketplaceErrors, "status", status, "error", error, "operation", operation,
                "loading", mcp.loading || skills.loading || apps.loading || plugins.loading || !operation.isEmpty(),
                "oauthUrl", oauthUrl, "oauthServer", oauthServer, "oauthPending", oauthPending, "pluginPreview", pluginPreview,
                "browserUrl", browserUrl, "browserKind", browserKind, "browserTitle", browserTitle,
                "appsFeatureEnabled", appsFeatureEnabled, "appsFeatureError", appsFeatureError,
                "runtimeScope", "account",
                "appRuntimeError", appRuntimeError, "appRuntimeUpdatedAt", appRuntimeUpdatedAt);
        sectionSnapshot(result, "mcp", mcp); sectionSnapshot(result, "skills", skills); sectionSnapshot(result, "apps", apps); sectionSnapshot(result, "plugins", plugins);
        return result;
    }
    private void sectionSnapshot(JSONObject result, String prefix, Section section) {
        put(result, prefix + "Loading", section.loading); put(result, prefix + "Known", section.known);
        put(result, prefix + "Error", section.error); put(result, prefix + "UpdatedAt", section.updated);
        put(result, prefix + "Truncated", section.truncated);
    }

    private long start(Section section) { section.loading = true; section.error = ""; section.truncated = false; long value = ++section.sequence; host.changed(); return value; }
    private void complete(Section section, JSONArray rows) {
        section.rows = CodexIntegrationsProtocol.boundedRows(rows);
        section.truncated |= CodexIntegrationsProtocol.catalogTruncated(section.rows);
        section.loading = false; section.known = true; section.updated = System.currentTimeMillis(); host.changed();
    }
    private void failed(Section section, JSONObject failure) { section.loading = false; section.known = false; section.error = message(failure); host.changed(); }
    private void begin(String value, String text) { operation = value; status = text; error = ""; host.changed(); }
    private void finish(String text) { operation = ""; error = ""; status = text; host.changed(); }
    private void operationFailed(JSONObject failure) { operation = ""; reject(message(failure)); }
    private void requirePreview() { if (!pluginPreview) throw new IllegalStateException("Open the Plugins preview explicitly before using under-development plugin APIs."); }
    private JSONObject require(Section section, String key, String id) {
        if (!section.known || section.loading) throw new IllegalStateException("Refresh this list before changing an integration.");
        JSONObject row = CodexIntegrationsProtocol.findRow(section.rows, key, id);
        if (row == null) throw new IllegalArgumentException("Choose an integration reported by the connected Codex engine.");
        return row;
    }
    private void call(String method, JSONObject params, Reply callback) {
        final long own = epoch;
        try {
            host.request(method, params, (result, failure) -> {
                if (own != epoch) return;
                deliver(method, callback, result, failure);
            });
        } catch (Exception error) { deliver(method, callback, new JSONObject(), problem(error.getMessage())); }
    }
    private void deliver(String method, Reply callback, JSONObject result, JSONObject failure) {
        try { callback.receive(result, failure); }
        catch (Exception error) {
            Section section = "mcpServerStatus/list".equals(method) ? mcp : "skills/list".equals(method) ? skills
                    : "app/list".equals(method) ? apps : "plugin/list".equals(method) ? plugins : null;
            if (section != null) failed(section, problem(error.getMessage()));
            else operationFailed(problem(error.getMessage()));
        }
    }
    private static JSONObject find(JSONArray rows, String key, String id) {
        if (id == null || id.isEmpty()) return null;
        for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.optJSONObject(i); if (row != null && id.equals(row.optString(key))) return row; }
        return null;
    }
    private static void append(JSONArray target, JSONArray rows, String key) {
        for (int i = 0; i < rows.length() && target.length() < 1000; i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && (row.optString(key).isEmpty() || find(target, key, row.optString(key)) == null)) target.put(row);
        }
    }
    private static boolean bool(JSONObject payload, String key) {
        if (!(payload.opt(key) instanceof Boolean)) throw new IllegalArgumentException("Choose an explicit enabled or disabled setting.");
        return payload.optBoolean(key);
    }
    private static JSONArray limit(JSONArray rows, int count) {
        JSONArray result = new JSONArray(); for (int i = 0; i < rows.length() && i < count; i++) result.put(rows.opt(i)); return result;
    }
    private static void trim(JSONArray rows, int count) { while (rows.length() > count) rows.remove(rows.length() - 1); }
    private static JSONArray errors(JSONArray source) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length() && result.length() < 20; i++) {
            JSONObject row = source.optJSONObject(i); if (row == null) continue;
            result.put(object("path", clean(row.optString("path", row.optString("marketplacePath", "")), 400),
                    "message", clean(row.optString("message", row.optString("error", "The engine could not load this entry.")), 600)));
        }
        return result;
    }
    private static String textValue(JSONObject value, String key) { Object text = value.opt(key); return text instanceof String ? (String) text : ""; }
    private static JSONObject childAt(JSONArray rows, int index) { JSONObject row = rows.optJSONObject(index); return row == null ? new JSONObject() : row; }
    private static JSONObject problem(String message) { return object("code", -32000, "message", message == null ? "The integration request could not complete." : message); }
    private static String message(JSONObject error) { return clean(error.optString("message", "Codex did not expose this integration capability."), 700); }
    private static void put(JSONObject value, String key, Object item) { try { value.put(key, item == null ? JSONObject.NULL : item); } catch (Exception error) { throw new IllegalArgumentException(error); } }
}
