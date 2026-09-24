package com.pocketide.ui.shell

/** The four bottom-bar destinations, in bar order. */
enum class Tab(val route: String, val label: String) {
    HOME(Routes.HOME, "Home"),
    CHATS(Routes.CHATS, "Chats"),
    ACTIVITY(Routes.ACTIVITY, "Activity"),
    SETTINGS(Routes.SETTINGS, "Settings"),
}

/**
 * Every route in the navigation graph. The patterns are what NavHost registers; the functions
 * build concrete routes. Ids are percent-encoded because project ids contain "/" and session ids
 * may contain anything a branch name can.
 */
object Routes {
    const val HOME = "home"
    const val CHATS = "chats"
    const val ACTIVITY = "activity"
    const val SETTINGS = "settings"

    const val ARG_PROJECT = "projectId"
    const val ARG_SESSION = "sessionId"
    const val ARG_SECTION = "section"

    const val PROJECT = "project/{$ARG_PROJECT}"
    const val AGENT = "agent/{$ARG_SESSION}"
    const val TRANSCRIPT = "transcript/{$ARG_SESSION}"
    const val YOUR_DATA = "your-data"
    const val COMPUTER = "computer"
    const val USAGE = "usage"
    const val MORE_AGENTS = "more-agents"
    const val HELP = "help?$ARG_SECTION={$ARG_SECTION}"
    const val RECENTLY_DELETED = "recently-deleted"
    const val WAITING_UPLOADS = "waiting-uploads"
    const val SECRETS = "secrets?$ARG_PROJECT={$ARG_PROJECT}"
    const val SCHEDULES = "schedules?$ARG_PROJECT={$ARG_PROJECT}"

    fun project(projectId: String) = "project/${encode(projectId)}"
    fun agent(sessionId: String) = "agent/${encode(sessionId)}"
    fun transcript(sessionId: String) = "transcript/${encode(sessionId)}"
    fun help(sectionId: String?) = withOptional("help", ARG_SECTION, sectionId)
    fun secrets(projectId: String?) = withOptional("secrets", ARG_PROJECT, projectId)
    fun schedules(projectId: String?) = withOptional("schedules", ARG_PROJECT, projectId)

    /**
     * The tab the owner is in, so the bar keeps showing where back leads: a tab itself, or the
     * tab a pushed screen was opened from. Switching tabs keeps Home at the bottom of the back
     * stack and at most one other tab above it, so that tab (when present) is the one.
     */
    fun tabOf(pattern: String?, inBackStack: (String) -> Boolean): Tab =
        Tab.entries.firstOrNull { it.route == pattern }
            ?: Tab.entries.firstOrNull { it != Tab.HOME && inBackStack(it.route) }
            ?: Tab.HOME

    /** Top-level screens get the shell's title bar (with Help); pushed screens draw their own. */
    fun isTab(pattern: String?): Boolean = Tab.entries.any { it.route == pattern }

    /** The agent is full screen: no bars at all. */
    fun showsBottomBar(pattern: String?): Boolean = pattern != AGENT

    private fun withOptional(base: String, name: String, value: String?): String =
        if (value.isNullOrEmpty()) base else "$base?$name=${encode(value)}"

    /**
     * Percent-encodes everything outside RFC 3986's unreserved set, as UTF-8. Navigation decodes
     * arguments with Uri.decode, which reverses exactly this (unlike URLEncoder's "+" for space).
     */
    fun encode(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            if (isUnreserved(c)) {
                out.append(c.toChar())
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    private fun isUnreserved(c: Int): Boolean =
        c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
            c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code

    private const val HEX = "0123456789ABCDEF"
}
