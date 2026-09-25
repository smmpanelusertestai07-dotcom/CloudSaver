package com.pocketide.ui.nav

/**
 * Navigation actions every screen may take. The navigation graph (AppNav) implements it; screens
 * never build routes themselves.
 */
interface PocketNav {
    fun back()
    fun home()
    fun chats()
    fun activity()
    fun settings()
    fun project(projectId: String)
    /** Opens the agent full screen on a session (its room, on its worktree). */
    fun agent(sessionId: String)
    /** The read-only transcript of a session. */
    fun transcript(sessionId: String)
    /** False where [transcript] and [agent] open nothing (while locked), so chats must not look tappable. */
    val opensChats: Boolean get() = true
    fun yourData()
    fun computer()
    fun usage()
    fun moreAgents()
    fun help(sectionId: String? = null)
    fun recentlyDeleted()
    fun waitingUploads()
    fun secrets(projectId: String?)
    fun schedules(projectId: String?)
    /** Opens a web page in Chrome (outside the app). */
    fun openExternal(url: String)
}
