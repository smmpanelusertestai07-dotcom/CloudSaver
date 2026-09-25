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
 * takes the file's current text and gives the new text. Settings that can run code (hooks, MCP
 * servers, permission allow rules and modes, environment blocks, commands the agent runs for
 * itself) are rebuilt every time: PocketIDE's own, then those the owner kept ([kept]); anything
 * else an agent put there is taken out and handed back as [Rebuilt.added], for the owner to see.
 * Every other key the file holds is kept. A file that cannot be read as what it should be gives
 * null, and the caller decides (set aside and rewrite, or leave alone).
 */
internal object ConfigFiles {

    /**
     * code-server's user settings: phone layout, no telemetry, no self-updates, the agent's own
     * keys, and with [careful] (someone else's code) the agent's ask-before-running settings.
     * The settings that start programs or loosen the agent's permissions are rebuilt.
     */
    fun codeServerSettings(existing: String?, profile: RoomProfile, fontSize: Int, careful: Boolean = false, kept: List<Entry> = emptyList()): Rebuilt? {
        val current = Jsonc.parseObject(existing) ?: return null
        val managed = linkedMapOf<String, JsonElement>()
        CODE_SERVER_SETTINGS.forEach { (key, value) -> managed[key] = value }
        managed["editor.fontSize"] = JsonPrimitive(fontSize)
        managed["terminal.integrated.fontSize"] = JsonPrimitive(fontSize)
        managed.putAll(profile.extensionSettings)
        if (careful) managed.putAll(profile.carefulSettings)
        // A careful value PocketIDE set is PocketIDE's: it goes again on the owner's own code.
        val carefulValues = profile.carefulSettings.map { (key, value) -> Entry(key, "", ExecutableJson.canonical(value)) }
        return rebuild(current, CODE_SERVER_SLOTS, ours = emptyList(), kept) { entry -> carefulValues.any { it.sameAs(entry) } }
            .let { (rebuilt, added) -> Rebuilt(Jsonc.write(JsonObject(rebuilt + managed)), added) }
    }

    /**
     * Claude Code's `~/.claude/settings.json`: [deny] rules added to the owner's, the updater and
     * error reporting off, transcripts kept for ten years (Claude deletes them after 30 days by
     * default, which could lose chats not yet backed up), the notification hook, and Remote Control
     * at every session's start as the owner chose ([accountChats]): while it is connected, Anthropic
     * keeps the session in the owner's Claude account, where the Claude app and claude.ai/code show it.
     * PocketIDE writes that key each time, so a value an agent set there does not last.
     */
    fun claudeSettings(
        existing: String?,
        deny: List<String>,
        notifyCommand: String,
        kept: List<Entry> = emptyList(),
        accountChats: Boolean = true,
    ): Rebuilt? {
        val current = Jsonc.parseObject(existing) ?: return null
        val ours = listOf(Entry(HOOKS, "Notification", ExecutableJson.canonical(notifyHook(notifyCommand)))) +
            CLAUDE_ENV.map { (name, value) -> Entry(ENV, name, ExecutableJson.canonical(value)) }
        // Hooks of an earlier PocketIDE (another notify.py line) are PocketIDE's too: replaced, not shown.
        val (rebuilt, added) = rebuild(current, CLAUDE_SLOTS, ours, kept) { it.place == HOOKS && it.value.contains(RoomLayout.NOTIFY) }
        val permissions = rebuilt["permissions"] as? JsonObject ?: JsonObject(emptyMap())
        val ownDeny = (permissions["deny"] as? JsonArray).orEmpty()
        val mergedDeny = ownDeny + deny.filter { rule -> ownDeny.none { it.stringOrNull() == rule } }.map(::JsonPrimitive)
        val keepDays = (rebuilt["cleanupPeriodDays"] as? JsonPrimitive)?.intOrNull
        val updated = rebuilt + mapOf(
            "permissions" to JsonObject(permissions + ("deny" to JsonArray(mergedDeny))),
            "cleanupPeriodDays" to JsonPrimitive(maxOf(keepDays ?: 0, CLAUDE_KEEP_DAYS)),
            CLAUDE_REMOTE_CONTROL to JsonPrimitive(accountChats),
        )
        return Rebuilt(Jsonc.write(JsonObject(updated)), added)
    }

    /** The `~/.claude.json` entries room.py merges in Linux (that file also holds the sign-in). */
    fun claudeMcpEntries(servers: Map<String, McpServer?>): String = Json.encodeToString(
        JsonObject.serializer(),
        JsonObject(servers.mapValues { (_, server) -> server?.let(::claudeServer) ?: JsonNull }),
    )

    /** Antigravity's `~/.gemini/config/mcp_config.json`: PocketIDE's servers (a null one removed) and those the owner kept. */
    fun antigravityMcp(existing: String?, servers: Map<String, McpServer?>, kept: List<Entry> = emptyList()): Rebuilt? {
        val current = Jsonc.parseObject(existing) ?: return null
        val ours = servers.map { (name, server) ->
            Entry(MCP_SERVERS, name, server?.let { ExecutableJson.canonical(antigravityServer(it)) }.orEmpty())
        }
        val (rebuilt, added) = rebuild(current, ANTIGRAVITY_MCP_SLOTS, ours.filter { it.value.isNotEmpty() }, kept, ourNames = ours)
        return Rebuilt(Jsonc.write(rebuilt), added)
    }

    /** The agy CLI's settings: telemetry off (it is on by default); its allow rules and hooks rebuilt. */
    fun antigravitySettings(existing: String?, kept: List<Entry> = emptyList()): Rebuilt? {
        val current = Jsonc.parseObject(existing) ?: return null
        val (rebuilt, added) = rebuild(current, ANTIGRAVITY_CLI_SLOTS, ours = emptyList(), kept)
        return Rebuilt(Jsonc.write(JsonObject(rebuilt + ("enableTelemetry" to JsonPrimitive(false)))), added)
    }

    /** Antigravity's `~/.gemini/config/hooks.json`, where every entry is a hook: only those the owner kept. */
    fun antigravityHooks(existing: String?, kept: List<Entry> = emptyList()): Rebuilt? =
        hooksFile(existing, ANTIGRAVITY_HOOKS_SLOTS, kept)

    /** Codex's `~/.codex/hooks.json`: only the hooks the owner kept. */
    fun codexHooks(existing: String?, kept: List<Entry> = emptyList()): Rebuilt? = hooksFile(existing, CODEX_HOOKS_SLOTS, kept)

    /**
     * Codex's `~/.codex/config.toml`: no update checks, analytics or feedback uploads, file-based
     * sign-in (proot has no keyring), the notify program, and the MCP servers ([servers], a null
     * one removed). The sandbox is set only when the owner has not chosen one: Codex's Linux
     * sandbox needs user namespaces, which proot does not give, so the room is the boundary.
     * With [careful] (someone else's code) Codex asks before it runs commands (approval on request);
     * back on the owner's own code that value goes again, and any other the owner chose stays.
     * MCP servers, inline hooks, model providers (they can run a command for their key), the
     * environment given to commands and the notify program are rebuilt. Null for a file with a
     * line this reader cannot place (TomlDocument.understood): Codex might read a setting there.
     */
    fun codexConfig(
        existing: String?,
        servers: Map<String, McpServer?>,
        notify: List<String>,
        careful: Boolean = false,
        kept: List<Entry> = emptyList(),
    ): Rebuilt? {
        val toml = TomlDocument(existing.orEmpty())
        if (!toml.understood()) return null
        val found = (CODEX_PLACES + CODEX_NOTIFY).flatMap { place ->
            toml.settings(listOf(place)).map { (name, text) -> Entry(place, name, text, keepable = name.isNotEmpty() || place != CODEX_MCP) }
        }
        val ours = { entry: Entry ->
            (entry.place == CODEX_MCP && entry.key in servers.keys) || (entry.place == CODEX_NOTIFY && entry.value.contains(RoomLayout.TOOLS))
        }
        val added = found.filter { entry -> !ours(entry) && kept.none { it.sameAs(entry) } }.distinct()
        if (careful) {
            toml.setTopLevel(CODEX_APPROVAL, CODEX_CAREFUL_APPROVAL)
        } else if (toml.topLevelValue(CODEX_APPROVAL) == CODEX_CAREFUL_APPROVAL) {
            toml.removeTopLevel(CODEX_APPROVAL)
        }
        toml.setTopLevel("check_for_update_on_startup", "false")
        toml.setTopLevel("cli_auth_credentials_store", TomlDocument.string("file"))
        val keptNotify = kept.lastOrNull { it.place == CODEX_NOTIFY }
        toml.setTopLevel(CODEX_NOTIFY, keptNotify?.value?.substringAfter('=')?.trim() ?: TomlDocument.array(notify))
        if (!toml.has(listOf("sandbox_mode"))) toml.setTopLevel("sandbox_mode", TomlDocument.string("danger-full-access"))
        toml.set(listOf("analytics"), "enabled", "false")
        toml.set(listOf("feedback"), "enabled", "false")
        CODEX_PLACES.forEach { toml.removeTable(listOf(it)) }
        for ((name, server) in servers) {
            if (server != null) toml.replaceTable(listOf(CODEX_MCP, name), codexServer(server))
        }
        kept.filter { it.place in CODEX_PLACES && it.keepable && !(it.place == CODEX_MCP && it.key in servers.keys) }.forEach { toml.add(it.value) }
        return Rebuilt(toml.text(), added)
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

    /**
     * [current] with [slots] holding PocketIDE's entries ([ours]) and then the owner's ([kept]),
     * and what it held there besides. A member under a name in [ourNames] (by default the names
     * in [ours]) is PocketIDE's, and so is an entry [isOurs] says is: rewritten, never shown.
     */
    private fun rebuild(
        current: JsonObject,
        slots: List<Slot>,
        ours: List<Entry>,
        kept: List<Entry>,
        ourNames: List<Entry> = ours,
        isOurs: (Entry) -> Boolean = { false },
    ): Pair<JsonObject, List<Entry>> {
        val found = ExecutableJson.entries(current, slots)
        val added = ExecutableJson.added(found, ourNames, kept, slots, isOurs)
        return ExecutableJson.rebuild(current, slots, ours + kept) to added
    }

    private fun hooksFile(existing: String?, slots: List<Slot>, kept: List<Entry>): Rebuilt? {
        val current = Jsonc.parseObject(existing) ?: return null
        val (rebuilt, added) = rebuild(current, slots, ours = emptyList(), kept)
        return Rebuilt(Jsonc.write(rebuilt), added, empty = rebuilt.isEmpty())
    }

    private fun claudeServer(server: McpServer) = buildJsonObject {
        put("type", "stdio")
        put("command", server.command)
        put("args", JsonArray(server.args.map(::JsonPrimitive)))
        put("env", JsonObject(server.env.mapValues { (_, value) -> JsonPrimitive(value) }))
    }

    private fun antigravityServer(server: McpServer) = buildJsonObject {
        put("command", server.command)
        put("args", JsonArray(server.args.map(::JsonPrimitive)))
        put("env", JsonObject(server.env.mapValues { (_, value) -> JsonPrimitive(value) }))
        put("disabled", false)
    }

    private fun codexServer(server: McpServer) = buildList {
        add("command" to TomlDocument.string(server.command))
        add("args" to TomlDocument.array(server.args))
        if (server.env.isNotEmpty()) add("env" to TomlDocument.inlineTable(server.env))
        add("startup_timeout_sec" to server.startupTimeoutSec.toString())
        add("tool_timeout_sec" to server.toolTimeoutSec.toString())
        add("enabled" to "true")
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

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    const val CLAUDE_KEEP_DAYS = 3650

    /** Claude Code's switch that connects each interactive session to Remote Control as it starts. */
    const val CLAUDE_REMOTE_CONTROL = "remoteControlAtStartup"

    private const val HOOKS = "hooks"
    private const val ENV = "env"
    private const val MCP_SERVERS = "mcpServers"
    private const val CODEX_MCP = "mcp_servers"
    private const val CODEX_NOTIFY = "notify"
    private const val CODEX_APPROVAL = "approval_policy"
    private val CODEX_CAREFUL_APPROVAL = TomlDocument.string("on-request")

    /** Codex's places that can run code, besides notify: rebuilt whole. */
    private val CODEX_PLACES = listOf(CODEX_MCP, "hooks", "model_providers", "shell_environment_policy")

    /**
     * Claude Code's settings that run a command, load code or loosen what it may do without
     * asking (code.claude.com settings reference): hooks, environment, allow rules, the
     * permission mode, extra folders, the helper commands, project MCP approvals and plugins.
     */
    private val CLAUDE_SLOTS = listOf(
        Slot.Grouped(listOf(HOOKS)),
        Slot.Members(listOf(ENV)),
        Slot.Items(listOf("permissions", "allow")),
        Slot.Value(listOf("permissions", "defaultMode")),
        Slot.Items(listOf("permissions", "additionalDirectories")),
        Slot.Value(listOf("apiKeyHelper")),
        Slot.Value(listOf("awsAuthRefresh")),
        Slot.Value(listOf("awsCredentialExport")),
        Slot.Value(listOf("otelHeadersHelper")),
        Slot.Value(listOf("statusLine")),
        Slot.Value(listOf("subagentStatusLine")),
        Slot.Value(listOf("fileSuggestion")),
        Slot.Value(listOf("enableAllProjectMcpServers")),
        Slot.Items(listOf("enabledMcpjsonServers")),
        Slot.Members(listOf("enabledPlugins")),
        Slot.Members(listOf("extraKnownMarketplaces")),
    )

    /**
     * code-server settings that start a program for the agent, give it an environment, or loosen
     * its permissions, and the built-in ones that name a program the editor runs by itself (git,
     * the TypeScript server, PHP's checker, the terminals).
     */
    private val CODE_SERVER_SLOTS = listOf(
        Slot.Value(listOf("chatgpt.cliExecutable")),
        Slot.Value(listOf("claudeCode.claudeProcessWrapper")),
        Slot.Value(listOf("claudeCode.environmentVariables")),
        Slot.Value(listOf("claudeCode.allowDangerouslySkipPermissions")),
        Slot.Value(listOf("claudeCode.initialPermissionMode")),
        Slot.Members(listOf("terminal.integrated.env.linux")),
        Slot.Members(listOf("terminal.integrated.profiles.linux")),
        Slot.Value(listOf("terminal.integrated.defaultProfile.linux")),
        Slot.Value(listOf("terminal.integrated.automationProfile.linux")),
        Slot.Value(listOf("terminal.integrated.shell.linux")),
        Slot.Value(listOf("terminal.integrated.shellArgs.linux")),
        Slot.Value(listOf("terminal.integrated.automationShell.linux")),
        Slot.Value(listOf("terminal.external.linuxExec")),
        Slot.Value(listOf("git.path")),
        Slot.Value(listOf("typescript.tsdk")),
        Slot.Value(listOf("php.validate.executablePath")),
    )

    private val ANTIGRAVITY_MCP_SLOTS = listOf(Slot.Members(listOf(MCP_SERVERS)))
    private val ANTIGRAVITY_CLI_SLOTS = listOf(Slot.Items(listOf("permissions", "allow")), Slot.Members(listOf(HOOKS)))
    private val ANTIGRAVITY_HOOKS_SLOTS = listOf(Slot.Members(emptyList()))
    private val CODEX_HOOKS_SLOTS = listOf(Slot.Grouped(listOf(HOOKS)), Slot.Members(emptyList(), except = setOf(HOOKS)))

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
