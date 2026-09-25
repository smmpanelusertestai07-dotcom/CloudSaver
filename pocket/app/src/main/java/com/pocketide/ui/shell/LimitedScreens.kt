package com.pocketide.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.pocketide.AppGraph
import com.pocketide.graph
import com.pocketide.ui.nav.PocketNav
import com.pocketide.ui.screens.chats.RecentlyDeletedScreen
import com.pocketide.ui.screens.chats.WaitingUploadsScreen
import com.pocketide.ui.screens.data.YourDataScreen
import com.pocketide.ui.screens.help.HelpScreen

/** The app graph from any composable. */
@Composable
fun rememberGraph(): AppGraph {
    val context = LocalContext.current
    return remember(context) { context.graph }
}

/**
 * The few screens that stay reachable while the main app is not: Help during set-up, and Your
 * data, Recently deleted and Waiting to upload while storage is full. Everything else a screen
 * may ask for (agents, projects) waits until the app is open, so those calls do nothing here.
 */
@Composable
fun LimitedScreens(content: @Composable (nav: PocketNav) -> Unit) {
    val context = LocalContext.current
    // One entry per line: "help", "help:<section>", "your-data", ... Saved as a plain string.
    var saved by rememberSaveable { mutableStateOf("") }
    val stack = LimitedStack.parse(saved)
    val nav = remember(context) {
        LimitedNav(openUrl = { url -> External.openUrl(context, url) }) { next -> saved = next(LimitedStack.parse(saved)).joinToString("\n") }
    }

    BackHandler(enabled = stack.isNotEmpty()) { nav.back() }
    val top = stack.lastOrNull()
    if (top == null) {
        content(nav)
        return
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        when (top) {
            LimitedStack.YOUR_DATA -> YourDataScreen(nav)
            LimitedStack.RECENTLY_DELETED -> RecentlyDeletedScreen(nav)
            LimitedStack.WAITING_UPLOADS -> WaitingUploadsScreen(nav)
            else -> HelpScreen(LimitedStack.helpSection(top), nav)
        }
    }
}

/** The entries [LimitedScreens] can show, as strings so the stack survives in saved state. */
object LimitedStack {
    const val YOUR_DATA = "your-data"
    const val RECENTLY_DELETED = "recently-deleted"
    const val WAITING_UPLOADS = "waiting-uploads"
    private const val HELP = "help"

    fun help(section: String?): String = if (section.isNullOrBlank()) HELP else "$HELP:${section.replace("\n", "")}"

    fun helpSection(entry: String): String? = entry.substringAfter("$HELP:", "").ifEmpty { null }

    fun parse(saved: String): List<String> = saved.split('\n').filter { it.isNotEmpty() }
}

internal class LimitedNav(
    private val openUrl: (String) -> Unit,
    private val change: ((List<String>) -> List<String>) -> Unit,
) : PocketNav {
    private fun push(entry: String) = change { stack -> if (stack.lastOrNull() == entry) stack else stack + entry }

    override fun back() = change { it.dropLast(1) }
    override fun help(sectionId: String?) = push(LimitedStack.help(sectionId))
    override fun yourData() = push(LimitedStack.YOUR_DATA)
    override fun recentlyDeleted() = push(LimitedStack.RECENTLY_DELETED)
    override fun waitingUploads() = push(LimitedStack.WAITING_UPLOADS)
    override fun openExternal(url: String) = openUrl(url)

    // Not reachable before set-up is finished or while the app is locked.
    override val opensChats: Boolean get() = false
    override fun home() = Unit
    override fun chats() = Unit
    override fun activity() = Unit
    override fun settings() = Unit
    override fun project(projectId: String) = Unit
    override fun agent(sessionId: String) = Unit
    override fun transcript(sessionId: String) = Unit
    override fun computer() = Unit
    override fun usage() = Unit
    override fun moreAgents() = Unit
    override fun secrets(projectId: String?) = Unit
    override fun schedules(projectId: String?) = Unit
}
