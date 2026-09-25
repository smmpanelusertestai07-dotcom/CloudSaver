package com.pocketide.rooms

import com.pocketide.bridge.PhoneGuestTools
import com.pocketide.core.AgentFiles
import com.pocketide.core.AppDirs
import com.pocketide.core.FileClass
import java.io.IOException

/**
 * Writes what each room needs before its engine starts: PocketIDE's tools inside the computer,
 * and in the room's home the agent's rules, MCP registration and phone-friendly settings.
 * Everything written here is AgentFiles GENERATED (or the managed block of a SYNC instructions
 * file), rewritten at every start from these templates; the owner's own keys and lines stay.
 * Settings that can run code are rebuilt from PocketIDE's templates and what the owner kept
 * ([changes]); what an agent added there is taken out and waits for the owner in Your data.
 * [claudeAccountChats] is the owner's choice to keep Claude's sessions in their Claude account too.
 */
internal class RoomConfigurator(
    private val dirs: AppDirs,
    private val assets: RoomAssets,
    private val now: () -> Long,
    private val changes: ConfigChangeBook = ConfigChangeBook(dirs.rooms),
    private val claudeAccountChats: () -> Boolean = { true },
    private val log: (agentId: String, line: String) -> Unit,
) {
    /** Copies the scripts, the terminal page and the browser installer into /opt/pocketide. */
    fun installTools() {
        val rootfs = RoomFiles(dirs.rootfs, guardSecrets = false)
        val tools = RoomLayout.TOOLS.removePrefix("/")
        for (name in listOf("mcp.py", "term.py", "room.py", "notify.py")) {
            rootfs.write("$tools/$name", assets.read("$ROOM_ASSETS/$name"))
        }
        rootfs.write(PhoneGuestTools.XDG_OPEN.removePrefix("/"), PhoneGuestTools.xdgOpen, executable = true)
        copyFolder(rootfs, "$ROOM_ASSETS/browser", BrowserTools.SETUP.removePrefix("/"))
        copyFolder(rootfs, TERMINAL_ASSETS, RoomLayout.TERMINAL_WEB.removePrefix("/"))
    }

    fun browserInstalled(): Boolean = RoomFiles(dirs.rootfs, guardSecrets = false).isFile(BrowserTools.INSTALLED.removePrefix("/"))

    /**
     * Writes [profile]'s room configuration. [otherRooms] are the other agents' ids (for Claude's
     * deny rules); [fontSize] follows the phone's font scale. [careful] is for someone else's
     * code: the agent asks before running anything and the browser tools stay off.
     */
    fun configure(profile: RoomProfile, otherRooms: List<String>, fontSize: Int, careful: Boolean = false) {
        val home = RoomFiles(dirs.roomHome(profile.agentId), guardSecrets = true)
        if (!home.makeFolder("")) throw IOException("The room's home is not a folder.")
        home.makePrivateHome()
        writeRules(profile, home)
        val servers = mcpServers(careful)
        val agentId = profile.agentId
        if (profile.engine == Engine.CODE_SERVER) {
            generate(agentId, home, CODE_SERVER_SETTINGS) { text, kept -> ConfigFiles.codeServerSettings(text, profile, fontSize, careful, kept) }
            installCompanion(agentId, home)
        }
        val notify = listOf("python3", RoomLayout.NOTIFY, agentId)
        when (agentId) {
            RoomProfiles.CLAUDE -> generate(agentId, home, ".claude/settings.json") { text, kept ->
                ConfigFiles.claudeSettings(text, claudeDenyRules(otherRooms), notify.joinToString(" "), kept, claudeAccountChats())
            }
            RoomProfiles.CODEX -> {
                generate(agentId, home, ".codex/config.toml") { text, kept -> ConfigFiles.codexConfig(text, servers, notify, careful, kept) }
                generate(agentId, home, ".codex/hooks.json") { text, kept -> ConfigFiles.codexHooks(text, kept) }
            }
            RoomProfiles.ANTIGRAVITY -> {
                generate(agentId, home, ".gemini/config/mcp_config.json") { text, kept -> ConfigFiles.antigravityMcp(text, servers, kept) }
                generate(agentId, home, ".gemini/antigravity-cli/settings.json") { text, kept -> ConfigFiles.antigravitySettings(text, kept) }
                generate(agentId, home, ".gemini/config/hooks.json") { text, kept -> ConfigFiles.antigravityHooks(text, kept) }
            }
        }
    }

    /** POCKETIDE_CLAUDE_KEEP for room.py: what the owner kept in Claude's ~/.claude.json, by SHA-256. */
    fun claudeStateKept(agentId: String): String = ClaudeState.keepList(changes.kept(agentId, ClaudeState.FILE))

    /**
     * What room.py took out of Claude's ~/.claude.json (the app never reads that file) and listed
     * in the room's bridge folder: each waits for the owner in Your data, as a setting taken out
     * of the other files does. The list is removed once read; room.py lists each setting once.
     */
    fun collectClaudeState(agentId: String) {
        val bridge = RoomFiles(dirs.roomBridge(agentId), guardSecrets = false)
        val report = try {
            bridge.read(ClaudeState.REPORT, ClaudeState.REPORT_BYTES)
        } finally {
            bridge.delete(ClaudeState.REPORT)
        }
        for (change in changes.found(agentId, ClaudeState.FILE, ClaudeState.parse(report ?: return))) log(agentId, takenOut(change))
    }

    /**
     * PocketIDE's MCP server, and the browser servers once the browser is installed (removed
     * otherwise, and while [careful]: a page of someone else's project could steer the agent).
     */
    fun mcpServers(careful: Boolean = false): Map<String, McpServer?> {
        val browser = BrowserTools.servers()
        val enabled = !careful && browserInstalled()
        return mapOf(PocketMcp.NAME to PocketMcp.SERVER) + browser.mapValues { (_, server) -> server.takeIf { enabled } }
    }

    /**
     * Claude's deny rules: the other rooms' folders (by their real paths, the only way they could
     * ever be reached from inside a room) and other processes' views of the file system.
     */
    fun claudeDenyRules(otherRooms: List<String>): List<String> {
        val paths = otherRooms.flatMap { other ->
            listOf(dirs.roomHome(other), dirs.roomTmp(other), RoomLayout.shm(dirs, other), dirs.roomWork(other), dirs.roomBridge(other)).map { it.absolutePath }
        } + listOf("/proc/*/root", "/proc/*/cwd")
        return paths.flatMap { path -> listOf("Read(/$path/**)", "Edit(/$path/**)") } +
            listOf("Read(//proc/*/environ)", "Read(~/.claude/.credentials.json)", "Edit(~/.claude/.credentials.json)")
    }

    private fun writeRules(profile: RoomProfile, home: RoomFiles) {
        val files = profile.instructionFiles.toMutableList()
        // Codex reads AGENTS.override.md instead of AGENTS.md when the owner has written one.
        if (profile.agentId == RoomProfiles.CODEX && !home.read(CODEX_OVERRIDE).isNullOrBlank()) files += CODEX_OVERRIDE
        for (file in files) {
            check(AgentFiles.classify(file) != FileClass.SECRET) { "$file is not an instructions file." }
            home.write(file, ManagedBlock.apply(home.read(file), RoomRules.text(profile.name)))
        }
    }

    /**
     * Rebuilds a generated config file with what the owner kept in it. One that cannot be read is
     * set aside (kept beside it, where the agent does not look) and written fresh; one left with
     * nothing in it is removed. What an agent had added is out of the file and waits for the owner.
     */
    private fun generate(agentId: String, home: RoomFiles, file: String, rebuild: (String?, List<Entry>) -> Rebuilt?) {
        val kept = changes.kept(agentId, file)
        val rebuilt = rebuild(home.read(file), kept) ?: run {
            home.setAside(file)
            log(agentId, "$file could not be read; it was kept as $file.pocketide-broken and written again.")
            rebuild(null, kept) ?: return
        }
        if (rebuilt.empty) home.delete(file) else home.write(file, rebuilt.text)
        for (change in changes.found(agentId, file, rebuilt.added)) log(agentId, takenOut(change))
    }

    private fun takenOut(change: ConfigChange) =
        "~/${change.file}: an agent added a setting that can run code (${change.place}${change.key.let { if (it.isEmpty()) "" else " $it" }}); " +
            "it was taken out and waits in Your data."

    /**
     * The companion opens the agent full screen. It is copied in as an unpacked extension; when
     * code-server already keeps its list of installed extensions, the companion is added to it,
     * because from then on code-server loads only what the list names.
     */
    private fun installCompanion(agentId: String, home: RoomFiles) {
        val folder = "$COMPANION_ID-$COMPANION_VERSION"
        copyFolder(home, "$ROOM_ASSETS/companion", "$EXTENSIONS/$folder")
        val registry = "$EXTENSIONS/extensions.json"
        val current = home.read(registry) ?: return
        val updated = ConfigFiles.extensionsRegistry(
            existing = current,
            id = COMPANION_ID,
            version = COMPANION_VERSION,
            folder = folder,
            guestFolder = "${AppDirs.GUEST_HOME}/$EXTENSIONS/$folder",
            now = now(),
        )
        if (updated == null) log(agentId, "code-server's extension list could not be read; the companion may not load.")
        else home.write(registry, updated)
    }

    private fun copyFolder(target: RoomFiles, assetFolder: String, into: String) {
        for (file in assets.files(assetFolder)) target.write("$into/$file", assets.read("$assetFolder/$file"))
    }

    /** Home 0700 and every credential file 0600 (AgentFiles SECRET), without reading any of them. */
    private fun RoomFiles.makePrivateHome() {
        makePrivate("")
        for (file in files("", depth = PRIVACY_DEPTH, skip = PRIVACY_SKIP)) {
            if (AgentFiles.isSecret(file)) makePrivate(file)
        }
    }

    /** The folders in a room's code-server extensions folder, to tell whether its agent is installed. */
    fun extensionFolders(agentId: String): List<String> =
        RoomFiles(dirs.roomHome(agentId), guardSecrets = true).folders(EXTENSIONS)

    companion object {
        const val ROOM_ASSETS = "rooms"
        const val TERMINAL_ASSETS = "web/terminal"
        const val USER_DATA = ".local/share/code-server"
        const val EXTENSIONS = "$USER_DATA/extensions"
        const val CODE_SERVER_SETTINGS = "$USER_DATA/User/settings.json"
        const val COMPANION_ID = "pocketide.pocketide-companion"
        const val COMPANION_VERSION = "3.0.0"
        private const val CODEX_OVERRIDE = ".codex/AGENTS.override.md"
        private const val PRIVACY_DEPTH = 4
        private val PRIVACY_SKIP = setOf(EXTENSIONS, ".cache", ".npm", ".local/share/code-server/CachedExtensionVSIXs")
    }
}
