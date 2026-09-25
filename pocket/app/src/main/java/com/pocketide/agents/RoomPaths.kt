package com.pocketide.agents

import com.pocketide.core.AppDirs

/**
 * Where an agent lives inside its room. Extensions go into the room's own code-server folders
 * (the ones its code-server starts with), and agy into the Antigravity room's home, where its
 * hub is started from. Guest paths are as the room sees them; home paths are under its home.
 */
internal object RoomPaths {
    const val CODE_SERVER = "/opt/code-server/bin/code-server"
    const val USER_DATA_IN_HOME = ".local/share/code-server"
    const val EXTENSIONS_IN_HOME = "$USER_DATA_IN_HOME/extensions"
    const val AGY_BIN_IN_HOME = ".gemini/bin"
    const val AGY_IN_HOME = "$AGY_BIN_IN_HOME/agy"
    const val GUEST_AGY = "${AppDirs.GUEST_HOME}/$AGY_IN_HOME"
    private const val GUEST_USER_DATA = "${AppDirs.GUEST_HOME}/$USER_DATA_IN_HOME"
    private const val GUEST_EXTENSIONS = "${AppDirs.GUEST_HOME}/$EXTENSIONS_IN_HOME"

    /** code-server's command line for the room's own user data and extensions. */
    fun codeServer(vararg arguments: String): List<String> =
        listOf(CODE_SERVER, "--user-data-dir", GUEST_USER_DATA, "--extensions-dir", GUEST_EXTENSIONS) + arguments

    /**
     * True when [folder] (a name in the extensions folder) holds [extensionId], in [version]
     * when given. code-server names folders `<id>-<version>` or `<id>-<version>-<target>`.
     */
    fun holds(folder: String, extensionId: String, version: String? = null): Boolean {
        val name = folder.lowercase()
        val prefix = extensionId.lowercase() + "-"
        if (!name.startsWith(prefix)) return false
        val rest = name.removePrefix(prefix)
        // "anthropic.claude-code-extra-1.0" is another extension, not a version of this one.
        if (rest.firstOrNull()?.isDigit() != true) return false
        if (version == null) return true
        val wanted = version.lowercase()
        return rest == wanted || rest.startsWith("$wanted-")
    }
}
