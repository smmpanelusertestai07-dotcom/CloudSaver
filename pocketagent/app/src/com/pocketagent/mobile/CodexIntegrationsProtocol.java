package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.Locale;

import static com.pocketagent.mobile.AgentProtocol.*;

/** Bounded native integration operations for the pinned Codex app-server protocol.
 * Mutation rows must first be selected from the controller's current catalog, not UI JSON.
 * All writes are JSON-RPC fields; this class never builds shell commands or generic settings.
 */
final class CodexIntegrationsProtocol {
    private static final int PAGE_SIZE = 100;
    static final int MAX_CATALOG_CHARS = 30000;
    static final int MAX_PLUGIN_DETAILS_CHARS = 24000;
    private CodexIntegrationsProtocol() {}

    static JSONObject listMcpParams(String cursor, String threadId) {
        return optional(optional(object("limit", PAGE_SIZE, "detail", "toolsAndAuthOnly"), "cursor", cursor), "threadId", threadId);
    }

    static JSONObject oauthParams(String name, String threadId) {
        return optional(object("name", opaque(name, "MCP server name", 256), "timeoutSecs", 600), "threadId", threadId);
    }

    static JSONObject skillsListParams(String cwd, boolean force) {
        return object("cwds", array(absolutePath(cwd)), "forceReload", force);
    }

    static JSONObject skillToggleParams(JSONObject row, boolean enabled) {
        return object("path", absolutePath(required(row, "path")), "enabled", enabled);
    }

    static JSONObject skillInput(JSONObject row) {
        if (!Boolean.TRUE.equals(row.opt("enabled"))) throw invalid("Enable this skill before adding it to a prompt.");
        return object("type", "skill", "name", opaque(required(row, "name"), "Skill name", 256),
                "path", absolutePath(required(row, "path")));
    }

    static JSONObject pluginListParams(String cwd, boolean force) {
        return object("cwds", array(absolutePath(cwd)), "forceRefetch", force);
    }

    static JSONObject pluginParams(JSONObject row) {
        String name = required(row, "name");
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw invalid("The catalog plugin name is not supported by this client.");
        JSONObject params = object("pluginName", name);
        String path = optionalString(row, "marketplacePath");
        if (!path.isEmpty()) return optional(params, "marketplacePath", absolutePath(path));
        return optional(params, "remoteMarketplaceName", opaque(required(row, "marketplaceName"), "Marketplace name", 256));
    }

    static JSONObject pluginInstallParams(JSONObject row) {
        if ("DISABLED_BY_ADMIN".equals(optionalString(row, "availability"))
                || "NOT_AVAILABLE".equals(optionalString(row, "installPolicy")))
            throw invalid("The provider does not allow this plugin to be installed for this account.");
        if (Boolean.TRUE.equals(row.opt("installed"))) throw invalid("This plugin is already installed.");
        return pluginParams(row);
    }

    static JSONObject pluginUninstallParams(JSONObject row) {
        if (!Boolean.TRUE.equals(row.opt("installed"))) throw invalid("This plugin is not reported as installed.");
        return object("pluginId", opaque(required(row, "id"), "Plugin ID", 512));
    }

    static JSONObject appsListParams(String cursor, String threadId, boolean force) {
        return optional(optional(object("limit", PAGE_SIZE, "forceRefetch", force), "cursor", cursor), "threadId", threadId);
    }

    static JSONObject appsInstalledParams(boolean forceRefresh, String threadId) {
        return optional(object("forceRefresh", forceRefresh), "threadId", threadId);
    }

    static JSONObject appsEnableParams() { return configWrite("features.apps", true); }

    static JSONObject configReadParams(String cwd) {
        return optional(object("includeLayers", false), "cwd", cwd == null || cwd.isEmpty() ? "" : absolutePath(cwd));
    }

    static JSONObject featuresListParams(String cursor) {
        return optional(object("limit", PAGE_SIZE), "cursor", cursor);
    }

    /** Add only a new name: controller must verify it does not already exist in current config. */
    static JSONObject mcpAddParams(String name, String url) {
        return configWrite("mcp_servers." + mcpName(name), object("url", remoteMcpUrl(url), "enabled", true));
    }

    static JSONObject mcpToggleParams(JSONObject row, boolean enabled) {
        if (!optionalString(row, "pluginId").isEmpty()) throw invalid("Manage this MCP server through its owning plugin.");
        return configWrite("mcp_servers." + mcpName(required(row, "name")) + ".enabled", enabled);
    }

    static JSONObject appToggleParams(JSONObject row, boolean enabled) {
        String id = required(row, "id");
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,255}")) throw invalid("This app ID cannot be safely written by the native client.");
        return configWrite("apps." + id + ".enabled", enabled);
    }

    private static JSONObject configWrite(String key, Object value) {
        // No filePath or arbitrary keyPath is accepted from the UI.
        return object("keyPath", key, "value", value, "mergeStrategy", "upsert");
    }

    static String mcpName(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
            throw invalid("Use 1–64 letters, digits, hyphens or underscores for the MCP name; start with a letter.");
        if ("codex_apps".equalsIgnoreCase(value)) throw invalid("This MCP name is reserved for account apps.");
        return value;
    }

    static String remoteMcpUrl(String value) {
        URI uri = httpsUri(value, "MCP URL");
        if (uri.getRawQuery() != null || uri.getRawFragment() != null)
            throw invalid("Use an HTTPS MCP endpoint without a query, token or fragment. Sign in through its OAuth flow after adding it.");
        return uri.toASCIIString();
    }

    /** Returned MCP issuer domains may differ from the server domain; UI shows the host first. */
    static String oauthUrl(String value) {
        URI uri = httpsUri(value, "MCP sign-in URL");
        if (uri.getRawFragment() != null) throw invalid("The MCP sign-in URL contains an unsupported fragment.");
        return uri.toASCIIString();
    }

    /** Only the installUrl in an actual current app/list record may be passed here. */
    static String appInstallUrl(JSONObject row) {
        URI uri = httpsUri(required(row, "installUrl"), "App connection URL");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!(domain(host, "chatgpt.com") || domain(host, "openai.com")))
            throw invalid("This connection URL is outside the supported official account sites. Open the provider's official app settings to connect it.");
        if (uri.getPort() != -1 && uri.getPort() != 443) throw invalid("The official app connection URL must use the standard HTTPS port.");
        if (uri.getRawFragment() != null) throw invalid("The app connection URL contains an unsupported fragment.");
        return uri.toASCIIString();
    }

    private static URI httpsUri(String value, String label) {
        try {
            opaque(value, label, 8192);
            if (!value.equals(value.trim()) || value.indexOf('\\') >= 0) throw invalid("Invalid " + label + ".");
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getPort() == 0 || uri.getPort() > 65535)
                throw invalid("Use an HTTPS address without an embedded username or password.");
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.endsWith(".localhost") || host.matches("127\\.[0-9.]+")
                    || host.equals("[::1]") || host.equals("0.0.0.0") || host.equals("[::]"))
                throw invalid("Use the remote server's HTTPS host, not a loopback address.");
            return uri;
        } catch (java.net.URISyntaxException e) { throw invalid("Invalid " + label + "."); }
    }

    static String absolutePath(String value) {
        opaque(value, "Catalog path", 4096);
        if (!value.startsWith("/") || value.indexOf('\\') >= 0 || value.endsWith("/") || value.contains("//"))
            throw invalid("The catalog path must be an absolute, normalized Linux path.");
        for (String part : value.substring(1).split("/"))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw invalid("The catalog path must be an absolute, normalized Linux path.");
        return value;
    }

    static JSONArray mcpRows(JSONArray data) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < data.length() && result.length() < PAGE_SIZE; i++) {
            JSONObject source = data.optJSONObject(i);
            if (source == null || optionalString(source, "name").isEmpty()) continue;
            JSONObject row = new JSONObject();
            identities(row, source, "name", "pluginId");
            texts(row, source, 256, "authStatus", "runtimeStatus");
            texts(row, source, 400, "toolsError");
            optional(row, "toolCount", child(source, "tools").length());
            optional(row, "resourceCount", list(source, "resources").length());
            optional(row, "resourceTemplateCount", list(source, "resourceTemplates").length());
            JSONObject info = new JSONObject(); texts(info, child(source, "serverInfo"), 200, "name", "title", "version", "description");
            optional(row, "serverInfo", info);
            JSONArray tools = new JSONArray(); JSONObject allTools = child(source, "tools");
            for (java.util.Iterator<String> keys = allTools.keys(); keys.hasNext() && tools.length() < 12;) {
                String key = keys.next(); JSONObject tool = child(allTools, key), summary = new JSONObject();
                texts(summary, tool, 160, "name", "title", "description");
                if (!summary.has("name")) optional(summary, "name", shortText(key, 160));
                tools.put(summary);
            }
            optional(row, "toolsSummary", tools);
            optional(row, "inventoryTruncated", tools.length() < allTools.length());
            result.put(row);
        }
        return boundedRows(result);
    }

    static JSONArray skillRows(JSONArray data) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < data.length() && result.length() < 500; i++) {
            JSONObject entry = data.optJSONObject(i); if (entry == null) continue;
            JSONArray skills = list(entry, "skills");
            for (int j = 0; j < skills.length() && result.length() < 500; j++) {
                JSONObject source = skills.optJSONObject(j);
                if (source == null || optionalString(source, "name").isEmpty() || optionalString(source, "path").isEmpty()) continue;
                JSONObject row = new JSONObject();
                identities(row, source, "name", "path", "pluginId");
                texts(row, source, 400, "description", "shortDescription", "scope");
                booleans(row, source, "enabled");
                JSONObject info = new JSONObject(); texts(info, child(source, "interface"), 200, "displayName", "shortDescription", "defaultPrompt");
                optional(row, "interface", info); optional(row, "displayName", optionalString(info, "displayName"));
                optional(row, "cwd", shortText(optionalString(entry, "cwd"), 4096)); result.put(row);
            }
        }
        return boundedRows(result);
    }

    static JSONArray pluginRows(JSONArray marketplaces) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < marketplaces.length() && result.length() < 500; i++) {
            JSONObject market = marketplaces.optJSONObject(i); if (market == null) continue;
            JSONArray plugins = list(market, "plugins");
            for (int j = 0; j < plugins.length() && result.length() < 500; j++) {
                JSONObject source = plugins.optJSONObject(j);
                if (source == null || optionalString(source, "id").isEmpty() || optionalString(source, "name").isEmpty()) continue;
                JSONObject row = pluginSummary(source);
                identities(row, object("marketplaceName", optionalString(market, "name"), "marketplacePath", optionalString(market, "path")), "marketplaceName", "marketplacePath");
                result.put(row);
            }
        }
        return boundedRows(result);
    }

    static JSONArray appRows(JSONArray data) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < data.length() && result.length() < PAGE_SIZE; i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null || optionalString(row, "id").isEmpty() || optionalString(row, "name").isEmpty()) continue;
            JSONObject compact = new JSONObject(); identities(compact, row, "id", "name", "installUrl");
            texts(compact, row, 400, "description"); booleans(compact, row, "isAccessible", "isEnabled");
            optional(compact, "pluginDisplayNames", strings(list(row, "pluginDisplayNames"), 8, 160)); result.put(compact);
        }
        return boundedRows(result);
    }

    /** Apply after pagination too. Together the four sections use at most 120K UTF-16 chars. */
    static JSONArray boundedRows(JSONArray rows) {
        JSONArray bounded = new JSONArray(); int size = 2; boolean truncated = catalogTruncated(rows);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            int length = row.toString().length() + 1;
            if (size + length > MAX_CATALOG_CHARS - 80) {
                if (bounded.length() == 0) throw invalid("A catalog entry is too large to display safely in the native client.");
                truncated = true; break;
            }
            bounded.put(row); size += length;
        }
        if (truncated && bounded.length() > 0) {
            JSONObject last = copy(bounded.optJSONObject(bounded.length() - 1)); optional(last, "catalogTruncated", true);
            try { bounded.put(bounded.length() - 1, last); } catch (Exception impossible) { throw new IllegalArgumentException(impossible); }
        }
        return bounded;
    }

    static boolean catalogTruncated(JSONArray rows) {
        for (int i = 0; i < rows.length(); i++) if (rows.optJSONObject(i) != null && rows.optJSONObject(i).optBoolean("catalogTruncated")) return true;
        return false;
    }

    static JSONArray errorRows(JSONArray errors) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < errors.length() && result.length() < 20; i++) {
            JSONObject source = errors.optJSONObject(i); if (source == null) continue;
            JSONObject row = new JSONObject(); texts(row, source, 400, "path", "marketplacePath", "message"); result.put(row);
        }
        if (errors.length() > result.length() && result.length() > 0) optional(result.optJSONObject(result.length() - 1), "catalogTruncated", true);
        return result;
    }

    /** Complete capability/policy review where small; truncation must disable native Install. */
    static JSONObject pluginDetails(JSONObject detail) {
        ReviewBudget budget = new ReviewBudget();
        JSONObject compact = (JSONObject) reviewValue(detail, 0, budget);
        optional(compact, "detailsTruncated", budget.truncated);
        if (compact.toString().length() > MAX_PLUGIN_DETAILS_CHARS) {
            // Keep identity and the explicit incomplete marker, never an apparently complete
            // partial permission summary when an unusual payload exceeds the bounded review.
            JSONObject identity = new JSONObject(), original = child(detail, "summary");
            for (String key : new String[]{"id", "name"}) {
                String value = optionalString(original, key);
                if (value.length() <= 512) optional(identity, key, value);
            }
            compact = object("summary", identity, "detailsTruncated", true,
                    "description", "The complete capability review is too large for this native screen. Installation is unavailable here.");
        }
        return compact;
    }

    private static JSONObject pluginSummary(JSONObject source) {
        JSONObject row = new JSONObject(); identities(row, source, "id", "name", "remotePluginId");
        texts(row, source, 160, "availability", "disabledReason", "installPolicy", "installPolicySource", "authPolicy", "version", "localVersion");
        booleans(row, source, "enabled", "installed", "mustShowInstallationInterstitial");
        JSONObject info = new JSONObject(); texts(info, child(source, "interface"), 400, "displayName", "shortDescription", "longDescription", "developerName", "category");
        texts(info, child(source, "interface"), 2048, "websiteUrl", "privacyPolicyUrl", "termsOfServiceUrl");
        optional(info, "capabilities", strings(list(child(source, "interface"), "capabilities"), 16, 120));
        optional(row, "interface", info); optional(row, "displayName", optionalString(info, "displayName"));
        optional(row, "description", optionalString(info, "shortDescription"));
        JSONObject origin = new JSONObject(); identities(origin, child(source, "source"), "type", "path", "url", "refName", "sha", "package", "registry", "version");
        optional(row, "source", origin); optional(row, "eligiblePlanTypes", strings(list(source, "eligiblePlanTypes"), 16, 80));
        return row;
    }

    private static void texts(JSONObject out, JSONObject source, int max, String... keys) {
        for (String key : keys) if (source.opt(key) instanceof String) optional(out, key, shortText(source.optString(key), max));
    }
    private static void identities(JSONObject out, JSONObject source, String... keys) {
        for (String key : keys) if (source.opt(key) instanceof String) {
            String value = source.optString(key);
            if (value.length() <= 8192) optional(out, key, value);
            else optional(out, "metadataTruncated", true); // Never mutate identity/URL into a different selector.
        }
    }
    private static void booleans(JSONObject out, JSONObject source, String... keys) {
        for (String key : keys) if (source.opt(key) instanceof Boolean) optional(out, key, source.optBoolean(key));
    }
    private static JSONArray strings(JSONArray source, int count, int max) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length() && result.length() < count; i++) if (source.opt(i) instanceof String) result.put(shortText(source.optString(i), max));
        return result;
    }
    private static String shortText(String value, int max) { return value.length() > max ? value.substring(0, max) + "…" : value; }

    private static final class ReviewBudget { int remaining = 20000; boolean truncated; }
    private static Object reviewValue(Object value, int depth, ReviewBudget budget) {
        if (value == null || value == JSONObject.NULL) { budget.remaining -= 4; return JSONObject.NULL; }
        if (budget.remaining < 100 || depth > 8) { budget.truncated = true; return JSONObject.NULL; }
        if (value instanceof JSONObject) {
            JSONObject result = new JSONObject(), source = (JSONObject) value; int count = 0; budget.remaining -= 2;
            for (java.util.Iterator<String> keys = source.keys(); keys.hasNext();) {
                String key = keys.next();
                if (key.equals("logo") || key.equals("logoDark") || key.equals("logoUrl") || key.equals("logoUrlDark")
                        || key.equals("screenshots") || key.equals("screenshotUrls") || key.equals("composerIcon")
                        || key.equals("composerIconUrl") || key.equals("iconAssets") || key.equals("iconDarkAssets")) continue;
                if (++count > 60 || budget.remaining < key.length() + 120) { budget.truncated = true; break; }
                budget.remaining -= key.length() + 4; optional(result, key, reviewValue(source.opt(key), depth + 1, budget));
            }
            return result;
        }
        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray) value, result = new JSONArray(); budget.remaining -= 2;
            for (int i = 0; i < source.length(); i++) {
                if (i >= 24 || budget.remaining < 120) { budget.truncated = true; break; }
                result.put(reviewValue(source.opt(i), depth + 1, budget)); budget.remaining--;
            }
            return result;
        }
        if (value instanceof String) {
            String text = (String) value; int max = Math.min(1200, Math.max(0, budget.remaining / 6 - 4));
            if (text.length() > max) { text = text.substring(0, max) + "…"; budget.truncated = true; }
            budget.remaining -= JSONObject.quote(text).length(); return text;
        }
        budget.remaining -= value.toString().length(); return value;
    }

    /** Missing entries and missing booleans remain unknown, not reported disabled or callable. */
    static JSONArray mergeAppRuntime(JSONArray apps, JSONArray installedApps, boolean runtimeKnown) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < apps.length(); i++) {
            JSONObject source = apps.optJSONObject(i); if (source == null) continue;
            JSONObject row = copy(source);
            row.remove("runtimeEnabled"); row.remove("runtimeCallable");
            optional(row, "runtimeKnown", false);
            JSONObject installed = runtimeKnown ? findRow(installedApps, "id", optionalString(row, "id")) : null;
            if (installed != null && installed.opt("enabled") instanceof Boolean && installed.opt("callable") instanceof Boolean) {
                optional(row, "runtimeKnown", true);
                optional(row, "runtimeEnabled", installed.optBoolean("enabled"));
                optional(row, "runtimeCallable", installed.optBoolean("callable"));
            }
            result.put(row);
        }
        return result;
    }

    /** Exact current-catalog lookup. An absent or ambiguous selector fails closed. */
    static JSONObject findRow(JSONArray rows, String key, String value) {
        if (value == null || value.isEmpty()) return null;
        JSONObject found = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && value.equals(optionalString(row, key))) {
                if (found != null) return null;
                found = row;
            }
        }
        return found;
    }

    private static boolean domain(String host, String domain) { return host.equals(domain) || host.endsWith("." + domain); }
    private static JSONObject copy(JSONObject source) {
        try { return new JSONObject(source.toString()); }
        catch (Exception malformed) { throw invalid("The agent supplied invalid integration metadata."); }
    }
    private static JSONObject optional(JSONObject target, String name, Object value) {
        if (value == null || (value instanceof String && ((String) value).isEmpty())) return target;
        try { target.put(name, value); return target; }
        catch (Exception impossible) { throw new IllegalArgumentException(impossible); }
    }
    private static String required(JSONObject row, String key) {
        String value = optionalString(row, key);
        if (value.isEmpty()) throw invalid("The selected catalog entry did not provide " + key + ". Refresh the list.");
        return value;
    }
    private static String optionalString(JSONObject row, String key) { return row != null && row.opt(key) instanceof String ? row.optString(key) : ""; }
    private static String opaque(String value, String label, int limit) {
        if (value == null || value.isEmpty() || value.length() > limit) throw invalid(label + " is missing or too long.");
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) throw invalid(label + " contains unsupported control characters.");
        return value;
    }
    private static IllegalArgumentException invalid(String value) { return new IllegalArgumentException(value); }
}
