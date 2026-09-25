package com.pocketide.rooms

import com.pocketide.core.AppDirs

/**
 * The agents' own sign-out commands, for "Delete everything". Each runs in its agent's room with
 * the CLI that ships in the agent's extension, so the vendor ends the sign-in on its side too,
 * not only on this phone. Antigravity has no sign-out PocketIDE can run; its sign-in file is
 * deleted with the room.
 */
internal object SignOuts {
    private data class Cli(val extensionId: String, val path: String, val args: List<String>, val signInFile: String)

    private val CLIS = mapOf(
        RoomProfiles.CLAUDE to Cli("anthropic.claude-code", "resources/native-binary/claude", listOf("auth", "logout"), ".claude/.credentials.json"),
        RoomProfiles.CODEX to Cli("openai.chatgpt", "bin/linux-aarch64/codex", listOf("logout"), ".codex/auth.json"),
    )

    /** The file, relative to the room's home, that exists while the agent is signed in; null when unknown. */
    fun signInFile(agentId: String): String? = CLIS[agentId]?.signInFile

    /**
     * The sign-out command for [agentId], from the newest version of its extension among
     * [extensionFolders] (the folder names in the room's extensions folder); null when there is none.
     */
    fun command(agentId: String, extensionFolders: List<String>): List<String>? {
        val cli = CLIS[agentId] ?: return null
        val folder = newest(extensionFolders, cli.extensionId) ?: return null
        return listOf("${AppDirs.GUEST_HOME}/${RoomConfigurator.EXTENSIONS}/$folder/${cli.path}") + cli.args
    }

    /** The folder of the newest installed version of [extensionId]: "<id>-<version>[-<platform>]". */
    fun newest(folders: List<String>, extensionId: String): String? {
        val prefix = "${extensionId.lowercase()}-"
        return folders.filter { it.lowercase().startsWith(prefix) }
            .maxWithOrNull { a, b -> compareVersions(version(a.substring(prefix.length)), version(b.substring(prefix.length))) }
    }

    private fun version(text: String): List<Int> =
        text.substringBefore('-').split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val difference = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }
}
