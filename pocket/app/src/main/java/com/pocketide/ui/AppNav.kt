package com.pocketide.ui

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pocketide.ui.nav.PocketNav
import com.pocketide.ui.screens.activity.ActivityScreen
import com.pocketide.ui.screens.agents.MoreAgentsScreen
import com.pocketide.ui.screens.chats.ChatsScreen
import com.pocketide.ui.screens.chats.RecentlyDeletedScreen
import com.pocketide.ui.screens.chats.TranscriptScreen
import com.pocketide.ui.screens.chats.WaitingUploadsScreen
import com.pocketide.ui.screens.computer.ComputerScreen
import com.pocketide.ui.screens.data.YourDataScreen
import com.pocketide.ui.screens.help.HelpScreen
import com.pocketide.ui.screens.home.HomeBarActions
import com.pocketide.ui.screens.home.HomeScreen
import com.pocketide.ui.screens.project.AgentScreen
import com.pocketide.ui.screens.project.ProjectScreen
import com.pocketide.ui.screens.schedules.SchedulesScreen
import com.pocketide.ui.screens.secrets.SecretsScreen
import com.pocketide.ui.screens.settings.SettingsScreen
import com.pocketide.ui.screens.usage.UsageScreen
import com.pocketide.ui.shell.BrandMark
import com.pocketide.ui.shell.External
import com.pocketide.ui.shell.Routes
import com.pocketide.ui.shell.Tab

/**
 * [PocketNav] over the navigation graph. Tabs keep their own back stack (switching away and back
 * returns to where the owner was); everything else is an ordinary push that back undoes.
 */
class AppNavigator(private val controller: NavHostController, private val context: Context) : PocketNav {
    override fun back() {
        // Popping the last entry would leave an empty screen; the system back exits instead.
        if (controller.previousBackStackEntry != null) controller.popBackStack()
    }

    override fun home() = tab(Tab.HOME)
    override fun chats() = tab(Tab.CHATS)
    override fun activity() = tab(Tab.ACTIVITY)
    override fun settings() = tab(Tab.SETTINGS)
    override fun project(projectId: String) = pushFor(projectId, Routes::project)
    override fun agent(sessionId: String) = pushFor(sessionId, Routes::agent)
    override fun transcript(sessionId: String) = pushFor(sessionId, Routes::transcript)
    override fun yourData() = push(Routes.YOUR_DATA)
    override fun computer() = push(Routes.COMPUTER)
    override fun usage() = push(Routes.USAGE)
    override fun moreAgents() = push(Routes.MORE_AGENTS)
    override fun help(sectionId: String?) = push(Routes.help(sectionId))
    override fun recentlyDeleted() = push(Routes.RECENTLY_DELETED)
    override fun waitingUploads() = push(Routes.WAITING_UPLOADS)
    override fun secrets(projectId: String?) = push(Routes.secrets(projectId))
    override fun schedules(projectId: String?) = push(Routes.schedules(projectId))
    override fun openExternal(url: String) = External.openUrl(context, url)

    /** Tapping the tab you are on returns to its first screen; another tab restores its stack. */
    fun tab(tab: Tab) {
        val current = controller.currentBackStackEntry?.destination?.route
        if (controller.tabOf(current) == tab && current != tab.route) {
            if (controller.popBackStack(tab.route, inclusive = false)) return
        }
        if (current == tab.route) return
        controller.navigate(tab.route) {
            popUpTo(controller.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    private fun push(route: String) = controller.navigate(route) { launchSingleTop = true }

    /** A screen that needs an id; a blank one (a record not loaded yet) opens nothing rather than crashing. */
    private fun pushFor(id: String, route: (String) -> String) {
        if (Routes.isUsableId(id)) push(route(id))
    }
}

private fun NavHostController.tabOf(pattern: String?): Tab = Routes.tabOf(pattern) { route ->
    runCatching { getBackStackEntry(route) }.isSuccess
}

/**
 * The main app: a title bar with Help on the four tabs, the bottom bar everywhere except the
 * full-screen agent, the access banner, and every screen of `ui/screens`.
 */
@Composable
fun AppNav(
    navController: NavHostController = rememberNavController(),
    banner: String? = null,
    launch: Launch = Launch.NONE,
) {
    val context = LocalContext.current
    val nav = remember(navController, context) { AppNavigator(navController, context) }
    // A notification or shortcut named a session: open its agent once, then forget it.
    LaunchedEffect(launch.sessionToOpen) {
        val sessionId = launch.sessionToOpen ?: return@LaunchedEffect
        nav.agent(sessionId)
        launch.onSessionOpened()
    }
    launch.sharedFile?.let { uri -> SharedFilePicker(uri, onOpenSession = nav::agent, onHandled = launch.onSharedFileHandled) }
    val entry by navController.currentBackStackEntryAsState()
    val pattern = entry?.destination?.route
    val tab = remember(entry) { navController.tabOf(pattern) }

    Scaffold(
        topBar = {
            if (Routes.isTab(pattern)) {
                ShellTopBar(tab, onHelp = { nav.help(null) }) { if (tab == Tab.HOME) HomeBarActions(nav) }
            }
        },
        bottomBar = { if (Routes.showsBottomBar(pattern)) ShellBottomBar(tab, onSelect = nav::tab) },
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            AnimatedVisibility(banner != null && pattern != Routes.AGENT, enter = fadeIn(), exit = fadeOut()) {
                AccessBanner(banner.orEmpty())
            }
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.weight(1f),
                enterTransition = { fadeIn(tween(220)) },
                exitTransition = { fadeOut(tween(160)) },
            ) {
                destinations(nav)
            }
        }
    }
}

/**
 * What the launch asked for, held by the activity until taken: a session to open (from a
 * notification or a pinned shortcut) and a file shared from another app.
 */
class Launch(
    val sessionToOpen: String? = null,
    val onSessionOpened: () -> Unit = {},
    val sharedFile: Uri? = null,
    val onSharedFileHandled: () -> Unit = {},
) {
    companion object {
        val NONE = Launch()
    }
}

private fun NavGraphBuilder.destinations(nav: PocketNav) {
    composable(Routes.HOME) { HomeScreen(nav) }
    composable(Routes.CHATS) { ChatsScreen(nav) }
    composable(Routes.ACTIVITY) { ActivityScreen(nav) }
    composable(Routes.SETTINGS) { SettingsScreen(nav) }
    composable(Routes.PROJECT, arguments = listOf(required(Routes.ARG_PROJECT))) { ProjectScreen(it.arg(Routes.ARG_PROJECT).orEmpty(), nav) }
    composable(Routes.AGENT, arguments = listOf(required(Routes.ARG_SESSION))) { AgentScreen(it.arg(Routes.ARG_SESSION).orEmpty(), nav) }
    composable(Routes.TRANSCRIPT, arguments = listOf(required(Routes.ARG_SESSION))) {
        TranscriptScreen(it.arg(Routes.ARG_SESSION).orEmpty(), nav)
    }
    composable(Routes.YOUR_DATA) { YourDataScreen(nav) }
    composable(Routes.COMPUTER) { ComputerScreen(nav) }
    composable(Routes.USAGE) { UsageScreen(nav) }
    composable(Routes.MORE_AGENTS) { MoreAgentsScreen(nav) }
    composable(Routes.HELP, arguments = listOf(optional(Routes.ARG_SECTION))) { HelpScreen(it.arg(Routes.ARG_SECTION), nav) }
    composable(Routes.RECENTLY_DELETED) { RecentlyDeletedScreen(nav) }
    composable(Routes.WAITING_UPLOADS) { WaitingUploadsScreen(nav) }
    composable(Routes.SECRETS, arguments = listOf(optional(Routes.ARG_PROJECT))) { SecretsScreen(it.arg(Routes.ARG_PROJECT), nav) }
    composable(Routes.SCHEDULES, arguments = listOf(optional(Routes.ARG_PROJECT))) { SchedulesScreen(it.arg(Routes.ARG_PROJECT), nav) }
}

private fun required(name: String) = navArgument(name) { type = NavType.StringType }

private fun optional(name: String) = navArgument(name) {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

private fun NavBackStackEntry.arg(name: String): String? = arguments?.getString(name)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShellTopBar(tab: Tab, onHelp: () -> Unit, tabActions: @Composable () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tab == Tab.HOME) {
                    BrandMark(size = 28.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    if (tab == Tab.HOME) "PocketIDE" else tab.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        actions = {
            IconButton(onClick = onHelp) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "Help")
            }
            tabActions()
        },
    )
}

@Composable
private fun ShellBottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(tab) },
                icon = { Icon(if (isSelected) tab.selectedIcon() else tab.icon(), contentDescription = null) },
                label = { Text(tab.label, maxLines = 1) },
            )
        }
    }
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.HOME -> Icons.Outlined.Home
    Tab.CHATS -> Icons.Outlined.ChatBubbleOutline
    Tab.ACTIVITY -> Icons.Outlined.Bolt
    Tab.SETTINGS -> Icons.Outlined.Settings
}

private fun Tab.selectedIcon(): ImageVector = when (this) {
    Tab.HOME -> Icons.Filled.Home
    Tab.CHATS -> Icons.Filled.ChatBubble
    Tab.ACTIVITY -> Icons.Filled.Bolt
    Tab.SETTINGS -> Icons.Filled.Settings
}

/** A calm, non-blocking notice under the title bar; TalkBack reads it when it appears. */
@Composable
private fun AccessBanner(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
