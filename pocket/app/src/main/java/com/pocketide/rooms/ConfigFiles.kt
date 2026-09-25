package com.pocketide.rooms

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The configuration PocketIDE generates in each room (AgentFiles class GENERATED). Each writer
 * takes the file's current text and returns the new text: PocketIDE's keys are set, everything
 * else the file holds is kept. A file that cannot be read as what it should be gives null, and
 * the caller decides (set aside and rewrite, or leave alone).
 */
internal object ConfigFiles {

    /**
     * code-server's user settings: phone layout, no telemetry, no self-updates, the agent's own
     * keys, and with [careful] (someone else's code) the agent's ask-before-running settings.
     */
    fun codeServerSettings(existing: String?, profile: RoomProfile, fontSize: Int, careful: Boolean = false): String? {
        val current = Jsonc.parseObject(existing) ?: return null
        val managed = linkedMapOf<String, JsonElement>()
        CODE_SERVER_SETTINGS.forEach { (key, value) -> managed[key] = value }
        managed["editor.fontSize"] = JsonPrimitive(fontSize)
        managed["terminal.integrated.fontSize"] = JsonPrimitive(fontSize)
        managed.putAll(profile.extensionSettings)
        if (careful) managed.putAll(profile.carefulSettings)
        // Back on the owner's own code, a careful value PocketIDE set goes; any other value stays.
        val kept = if (careful) current else current.filterNot { (key, value) -> profile.carefulSettings[key] == value }
        return Jsonc.write(JsonObject(kept + managed))
    }

    /**
     * Claude Code's `~/.claude/settings.json`: [deny] rules added to the owner's, the updater and
     * error reporting off, transcripts kept for ten years (Claude deletes them after 30 days by
     * default, which could lose chats not yet backed up), and the notification hook.
     */
    fun claudeSettings(existing: String?, deny: List<String>, notifyCommand: String): String? {
        val current = Jsonc.parseObject(existing) ?: return null
        val permissions = current["permissions"] as? JsonObject ?: JsonObject(emptyMap())
        val ownDeny = (permissions["deny"] as? JsonArray).orEmpty()
        val mergedDeny = ownDeny + deny.filter { rule -> ownDeny.none { it.stringOrNull() == rule } }.map(::JsonPrimitive)
        val env = (current["env"] as? JsonObject ?: JsonObject(emptyMap())) + CLAUDE_ENV
        val keepDays = (current["cleanupPeriodDays"] as? JsonPrimitive)?.intOrNull
        val hooks = current["hooks"] as? JsonObject ?: JsonObject(emptyMap())
        val notification = ((hooks["Notification"] as? JsonArray).orEmpty())
            .filterNot { group -> mentions(group, RoomLayout.NOTIFY) } + notifyHook(notifyCommand)
        val updated = current + mapOf(
            "permissions" to JsonObject(permissions + ("deny" to JsonArray(mergedDeny))),
            "env" to JsonObject(env),
            "cleanupPeriodDays" to JsonPrimitive(maxOf(keepDays ?: 0, CLAUDE_KEEP_DAYS)),
            "hooks" to JsonObject(hooks + ("Notification" to JsonArray(notification))),
        )
        return Jsonc.write(JsonObject(updated))
    }

    /** The `~/.claude.json` entries room.py merges in Linux (that file also holds the sign-in). */
    fun claudeMcpEntries(servers: Map<String, McpServer?>): String = Json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            servers.mapValues { (_, server) ->
                server?.let {
                    buildJsonObject {
                        put("type", "stdio")
                        put("command", it.command)
                        put("args", JsonArray(it.args.map(::JsonPrimitive)))
                        put("env", JsonObject(it.env.mapValues { (_, value) -> JsonPrimitive(value) }))
                    }
                } ?: JsonNull
            },
        ),
    )

    /** Antigravity's `~/.gemini/config/mcp_config.json`; a null server is removed. */
    fun antigravityMcp(existing: String?, servers: Map<String, McpServer?>): String? {
        val current = Jsonc.parseObject(existing) ?: return null
        val own = (current["mcpServers"] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
        for ((name, server) in servers) {
            if (server == null) {
                own.remove(name)
            } else {
                own[name] = buildJsonObject {
                    put("command", server.command)
                    put("args", JsonArray(server.args.map(::JsonPrimitive)))
                    put("env", JsonObject(server.env.mapValues { (_, value) -> JsonPrimitive(value) }))
                    put("disabled", false)
                }
            }
        }
        return Jsonc.write(JsonObject(current + ("mcpServers" to JsonObject(own))))
    }

    /** The agy CLI's settings: telemetry off (it is on by default). */
    fun antigravitySettings(existing: String?): String? {
        val current = Jsonc.parseObject(existing) ?: return null
        return Jsonc.write(JsonObject(current + ("enableTelemetry" to JsonPrimitive(false))))
    }

    /**
     * Codex's `~/.codex/config.toml`: no update checks, analytics or feedback uploads, file-based
     * sign-in (proot has no keyring), the notify program, and the MCP servers ([servers], a null
     * one removed). The sandbox is set only when the owner has not chosen one: Codex's Linux
     * sandbox needs user namespaces, which proot does not give, so the room is the boundary.
     * With [careful] (someone else's code) Codex asks before it runs commands (approval on request);
     * back on the owner's own code that value goes again, and any other the owner chose stays.
     */
    fun codexConfig(existing: String?, servers: Map<String, McpServer?>, notify: List<String>, careful: Boolean = false): String {
        val toml = TomlDocument(existing.orEmpty())
        if (careful) {
            toml.setTopLevel(CODEX_APPROVAL, CODEX_CAREFUL_APPROVAL)
        } else if (toml.topLevelValue(CODEX_APPROVAL) == CODEX_CAREFUL_APPROVAL) {
            toml.removeTopLevel(CODEX_APPROVAL)
        }
        toml.setTopLevel("check_for_update_on_startup", "false")
        toml.setTopLevel("cli_auth_credentials_store", TomlDocument.string("file"))
        toml.setTopLevel("notify", TomlDocument.array(notify))
        if (!toml.has(listOf("sandbox_mode"))) toml.setTopLevel("sandbox_mode", TomlDocument.string("danger-full-access"))
        toml.set(listOf("analytics"), "enabled", "false")
        toml.set(listOf("feedback"), "enabled", "false")
        for ((name, server) in servers) {
            val table = listOf("mcp_servers", name)
            if (server == null) {
                toml.removeTable(table)
                continue
            }
            val entries = buildList {
                add("command" to TomlDocument.string(server.command))
                add("args" to TomlDocument.array(server.args))
                if (server.env.isNotEmpty()) add("env" to TomlDocument.inlineTable(server.env))
                add("startup_timeout_sec" to server.startupTimeoutSec.toString())
                add("tool_timeout_sec" to server.toolTimeoutSec.toString())
                add("enabled" to "true")
            }
            toml.replaceTable(table, entries)
        }
        return toml.text()
    }

    /**
     * code-server's list of installed extensions (`extensions/extensions.json`) with the
     * companion listed: once that file exists, code-server loads only the extensions it names.
     */
    fun extensionsRegistry(existing: String, id: String, version: String, folder: String, guestFolder: String, now: Long): String? {
        val entries = try {
            Json.parseToJsonElement(existing) as? JsonArray
        } catch (unreadable: SerializationException) {
            null
        } catch (unreadable: IllegalArgumentException) {
            null
        } ?: return null
        val others = entries.filterNot { entry ->
            ((entry as? JsonObject)?.get("identifier") as? JsonObject)?.get("id")?.stringOrNull().equals(id, ignoreCase = true)
        }
        val ours = buildJsonObject {
            put("identifier", buildJsonObject { put("id", id) })
            put("version", version)
            put("location", buildJsonObject {
                put("\$mid", 1)
                put("path", guestFolder)
                put("scheme", "file")
            })
            put("relativeLocation", folder)
            put("metadata", buildJsonObject {
                put("installedTimestamp", now)
                put("source", "vsix")
            })
        }
        return Json.encodeToString(JsonArray.serializer(), buildJsonArray {
            others.forEach { add(it) }
            add(ours)
        })
    }

    private fun notifyHook(command: String) = buildJsonObject {
        put("matcher", "")
        put("hooks", buildJsonArray {
            add(buildJsonObject {
                put("type", "command")
                put("command", command)
            })
        })
    }

    private fun mentions(element: JsonElement, text: String): Boolean = when (element) {
        is JsonPrimitive -> element.contentOrNull?.contains(text) == true
        is JsonArray -> element.any { mentions(it, text) }
        is JsonObject -> element.values.any { mentions(it, text) }
    }

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    const val CLAUDE_KEEP_DAYS = 3650

    private const val CODEX_APPROVAL = "approval_policy"
    private val CODEX_CAREFUL_APPROVAL = TomlDocument.string("on-request")

    /** Off: its self-updater (PocketIDE updates the extension) and error reports. */
    private val CLAUDE_ENV = mapOf(
        "DISABLE_AUTOUPDATER" to JsonPrimitive("1"),
        "DISABLE_ERROR_REPORTING" to JsonPrimitive("1"),
    )

    /** A phone-sized workbench: the agent's view and nothing else, no telemetry, no self-updates. */
    private val CODE_SERVER_SETTINGS: List<Pair<String, JsonElement>> = listOf(
        "workbench.startupEditor" to JsonPrimitive("none"),
        "chat.disableAIFeatures" to JsonPrimitive(true),
        "telemetry.telemetryLevel" to JsonPrimitive("off"),
        "update.mode" to JsonPrimitive("none"),
        "extensions.autoUpdate" to JsonPrimitive(false),
        "extensions.autoCheckUpdates" to JsonPrimitive(false),
        "security.workspace.trust.enabled" to JsonPrimitive(false),
        "git.autofetch" to JsonPrimitive(false),
        "workbench.activityBar.location" to JsonPrimitive("hidden"),
        "workbench.statusBar.visible" to JsonPrimitive(false),
        "window.menuBarVisibility" to JsonPrimitive("hidden"),
        "window.commandCenter" to JsonPrimitive(false),
        "workbench.layoutControl.enabled" to JsonPrimitive(false),
        "workbench.editor.showTabs" to JsonPrimitive("none"),
        "breadcrumbs.enabled" to JsonPrimitive(false),
        "editor.minimap.enabled" to JsonPrimitive(false),
        "editor.wordWrap" to JsonPrimitive("on"),
        "editor.lineNumbers" to JsonPrimitive("off"),
        "editor.folding" to JsonPrimitive(false),
        "editor.glyphMargin" to JsonPrimitive(false),
        "editor.stickyScroll.enabled" to JsonPrimitive(false),
        "editor.dragAndDrop" to JsonPrimitive(false),
        "editor.hover.delay" to JsonPrimitive(1500),
        "editor.scrollbar.verticalScrollbarSize" to JsonPrimitive(20),
        "editor.scrollbar.horizontalScrollbarSize" to JsonPrimitive(20),
        "workbench.sash.size" to JsonPrimitive(20),
        "workbench.editor.limit.enabled" to JsonPrimitive(true),
        "workbench.editor.limit.value" to JsonPrimitive(5),
        "workbench.editor.limit.excludeDirty" to JsonPrimitive(true),
        "diffEditor.renderSideBySide" to JsonPrimitive(false),
        "workbench.reduceMotion" to JsonPrimitive("on"),
        "files.autoSave" to JsonPrimitive("afterDelay"),
        "workbench.panel.opensMaximized" to JsonPrimitive("always"),
        "zenMode.silentNotifications" to JsonPrimitive(true),
        "workbench.tips.enabled" to JsonPrimitive(false),
        "window.autoDetectColorScheme" to JsonPrimitive(true),
        "workbench.preferredDarkColorTheme" to JsonPrimitive("Default Dark Modern"),
        "workbench.preferredLightColorTheme" to JsonPrimitive("Default Light Modern"),
    )
}
