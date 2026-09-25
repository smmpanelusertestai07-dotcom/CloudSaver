package com.pocketide.rooms

/**
 * What each port bridge is for, written into its purpose, and which room its traffic belongs
 * to. Only work the owner does counts: a terminal, a Preview of a dev server, or a dev server
 * opened through the agent's own page. The agent's page talking to its engine does not, or an
 * open page would keep an idle room awake forever.
 */
internal object RoomTraffic {
    private const val AGENT = "agent:"
    private const val TERMINAL = "terminal:"
    private const val PREVIEW = "preview:"

    fun agentPurpose(agentId: String) = AGENT + agentId

    fun terminalPurpose(sessionId: String) = TERMINAL + sessionId

    fun previewPurpose(sessionId: String) = PREVIEW + sessionId

    /** A Preview of a dev server: its target port is the owner's, not the app's. */
    fun isPreview(purpose: String) = purpose.startsWith(PREVIEW)

    /** The agent whose room [port]'s traffic keeps awake, or null when it keeps none awake. */
    fun agentOf(purpose: String, targetPort: Int, port: Int, agentOfSession: (String) -> String?): String? = when {
        purpose.startsWith(AGENT) -> purpose.removePrefix(AGENT).takeIf { port != targetPort && it.isNotEmpty() }
        purpose.startsWith(TERMINAL) -> agentOfSession(purpose.removePrefix(TERMINAL))
        purpose.startsWith(PREVIEW) -> agentOfSession(purpose.removePrefix(PREVIEW))
        else -> null
    }
}
