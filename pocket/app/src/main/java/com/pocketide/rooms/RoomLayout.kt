package com.pocketide.rooms

import com.pocketide.bridge.PhoneGuestTools
import com.pocketide.core.AppDirs
import com.pocketide.linux.Bind
import com.pocketide.linux.ProotCommand
import java.io.File

/**
 * What a room sees and what its programs are told, in one place. A room binds its own home,
 * temporary folder, shared-memory folder, bridge folder and worktrees, plus the shared bare
 * repositories; nothing of any other room is ever bound, so from inside a room the others do not exist.
 */
internal object RoomLayout {
    const val GUEST_TMP = "/tmp"
    const val GUEST_SHM = ProotCommand.GUEST_SHM
    const val TOOLS = AppDirs.GUEST_TOOLS
    /** First on every room's PATH (ProotCommand), so the phone's xdg-open wins over any other. */
    const val TOOLS_BIN = PhoneGuestTools.BIN_DIR
    const val XDG_OPEN = PhoneGuestTools.XDG_OPEN
    const val MCP_SERVER = "$TOOLS/mcp.py"
    const val ROOM_LAUNCHER = "$TOOLS/room.py"
    const val TERMINAL_SERVER = "$TOOLS/term.py"
    const val NOTIFY = "$TOOLS/notify.py"
    const val TERMINAL_WEB = "$TOOLS/web/terminal"
    const val PYTHON = "/usr/bin/python3"

    /** The room's folders, host side, created when missing. */
    fun hostFolders(dirs: AppDirs, agentId: String): List<File> = listOf(
        dirs.roomHome(agentId),
        dirs.roomTmp(agentId),
        shm(dirs, agentId),
        dirs.roomBridge(agentId),
        dirs.repos,
        dirs.roomWork(agentId),
    )

    fun binds(dirs: AppDirs, agentId: String): List<Bind> = listOf(
        Bind(dirs.roomHome(agentId).absolutePath, AppDirs.GUEST_HOME),
        Bind(dirs.roomTmp(agentId).absolutePath, GUEST_TMP),
        Bind(shm(dirs, agentId).absolutePath, GUEST_SHM),
        Bind(dirs.roomBridge(agentId).absolutePath, AppDirs.GUEST_BRIDGE),
        Bind(dirs.repos.absolutePath, AppDirs.GUEST_REPOS),
        Bind(dirs.roomWork(agentId).absolutePath, AppDirs.GUEST_WORK),
    )

    /**
     * The room's own /dev/shm. Android has none, and glibc keeps POSIX semaphores and shared
     * memory there, so without it Python's multiprocessing and every parallel test runner fail.
     * Per room, beside its temporary folder, so rooms still share no files.
     */
    fun shm(dirs: AppDirs, agentId: String): File = File(dirs.roomTmp(agentId).parentFile, "shm")

    /**
     * Where [guestPath] lives on the host, for the room's own writable places (home, temporary
     * folder, worktrees): the place's root and the path under it. Null for anything else, and for
     * paths that climb out. Links are not resolved here; callers walk the path without following them.
     */
    fun hostPath(dirs: AppDirs, agentId: String, guestPath: String): RoomPath? {
        if (!guestPath.startsWith("/") || guestPath.contains('\u0000')) return null
        val parts = guestPath.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.size < 2 || parts.any { it == ".." }) return null
        val root = when ("/" + parts.first()) {
            AppDirs.GUEST_HOME -> dirs.roomHome(agentId)
            GUEST_TMP -> dirs.roomTmp(agentId)
            AppDirs.GUEST_WORK -> dirs.roomWork(agentId)
            else -> return null
        }
        return RoomPath(root, parts.drop(1).joinToString("/"))
    }

    /** The home-relative path of [guestPath] when it is inside the room's home, else null. */
    fun homeRelative(guestPath: String): String? {
        val prefix = AppDirs.GUEST_HOME + "/"
        if (!guestPath.startsWith(prefix)) return null
        return guestPath.removePrefix(prefix).split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }

    /**
     * The room's environment, the same for every program in it (engine, `>_` terminal, scheduled
     * run): who it is, where media goes, the project's Variables, the agent's own self-updater off,
     * then the program's own settings ([engine] wins over Variables). Variables that would change
     * how the room itself runs, or that name a credential the app holds, are left out and reported
     * in [dropped]. Never a token: the app's GitHub and Drive credentials are not Variables.
     */
    fun environment(
        agentId: String,
        variables: Map<String, String>,
        engine: Map<String, String>,
        dropped: (String) -> Unit = {},
    ): Map<String, String> {
        val env = linkedMapOf<String, String>()
        for ((name, value) in variables.toSortedMap()) {
            if (isReserved(name)) dropped(name) else env[name] = value
        }
        env["POCKETIDE_ROOM"] = agentId
        env["POCKETIDE_MEDIA_ROOT"] = AppDirs.GUEST_WORK
        env["BROWSER"] = XDG_OPEN
        env["PLAYWRIGHT_BROWSERS_PATH"] = BrowserTools.BROWSERS
        env.putAll(updatesOff(agentId))
        env.putAll(engine)
        return env
    }

    /**
     * PocketIDE installs and checks each agent's pinned binary, so no program in the room may
     * replace it: agy updates itself unless told not to, wherever it is started.
     */
    private fun updatesOff(agentId: String): Map<String, String> = when (agentId) {
        RoomProfiles.ANTIGRAVITY -> mapOf("AGY_CLI_DISABLE_AUTO_UPDATE" to "true")
        else -> emptyMap()
    }

    fun isReserved(name: String): Boolean =
        name in RESERVED || RESERVED_PREFIXES.any { name.startsWith(it) }

    /** The basics Linux sets for every program, and the names that decide how a room runs. */
    private val RESERVED = setOf(
        "HOME", "USER", "LOGNAME", "SHELL", "PATH", "TERM", "LANG", "TZ", "TMPDIR", "BROWSER",
        // code-server reads these as its password and its GitHub credential.
        "PASSWORD", "HASHED_PASSWORD", "GITHUB_TOKEN",
        "GH_TOKEN", "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN", "GIT_ASKPASS", "SSH_ASKPASS",
    )
    private val RESERVED_PREFIXES = listOf("POCKETIDE_", "LD_", "CODE_SERVER_", "VSCODE_", "AGY_", "ANTIGRAVITY_")
}

/** A file inside one of a room's places: [root] on the host, and [relative] under it. */
internal data class RoomPath(val root: File, val relative: String) {
    val file: File get() = File(root, relative)
}
