package com.pocketide.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.pocketide.MainActivity
import com.pocketide.PocketApp
import com.pocketide.agents.Agent
import com.pocketide.docs.DocsContent
import com.pocketide.graph
import com.pocketide.ui.lock.LockScreen
import com.pocketide.ui.nav.ComputerRoute
import com.pocketide.ui.nav.DataRoute
import com.pocketide.ui.nav.HelpPageRoute
import com.pocketide.ui.nav.HelpRoute
import com.pocketide.ui.nav.HomeRoute
import com.pocketide.ui.nav.NewProjectRoute
import com.pocketide.ui.nav.OpenRepoRoute
import com.pocketide.ui.nav.SettingsRoute
import com.pocketide.ui.nav.UsageRoute
import com.pocketide.ui.screens.computer.ComputerScreen
import com.pocketide.ui.screens.data.YourDataScreen
import com.pocketide.ui.screens.help.HelpPageScreen
import com.pocketide.ui.screens.help.HelpScreen
import com.pocketide.ui.screens.home.HomeScreen
import com.pocketide.ui.screens.home.NewProjectScreen
import com.pocketide.ui.screens.home.OpenRepoScreen
import com.pocketide.ui.screens.onboarding.Onboarding
import com.pocketide.ui.screens.settings.SettingsScreen
import com.pocketide.ui.screens.usage.UsageScreen
import com.pocketide.ui.theme.PocketTheme

/** Theme, app lock, first-run set-up, then the app with its bottom bar. */
@Composable
fun PocketRoot(activity: MainActivity) {
    val graph = activity.graph
    val appLock = (activity.application as PocketApp).appLock
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    PocketTheme(settings.theme, settings.dynamicColor) {
        val locked by appLock.locked.collectAsStateWithLifecycle()
        val account by graph.gitHubAuth.account.collectAsStateWithLifecycle()
        when {
            settings.appLock && locked -> LockScreen(activity, onUnlocked = appLock::unlock)
            !settings.onboardingDone || account == null -> Onboarding(graph)
            else -> MainScreens(activity)
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector, val route: Any) {
    HOME("Home", Icons.Outlined.Home, HomeRoute),
    COMPUTER("Computer", Icons.Outlined.Terminal, ComputerRoute),
    USAGE("Usage", Icons.Outlined.Insights, UsageRoute),
    SETTINGS("Settings", Icons.Outlined.Settings, SettingsRoute),
}

@Composable
private fun MainScreens(activity: MainActivity) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    // The agent the owner picked on Home, shown once the computer's page is ready.
    var agentToShow by remember { mutableStateOf<Agent?>(null) }
    val openComputer by activity.openComputer.collectAsStateWithLifecycle()
    LaunchedEffect(openComputer) {
        if (openComputer) {
            nav.openTab(Tab.COMPUTER)
            activity.computerOpened()
        }
    }
    val fullScreen = destination?.hasRoute(ComputerRoute::class) == true
    Scaffold(
        bottomBar = {
            if (!fullScreen) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = destination?.hasRoute(tab.route::class) == true,
                            onClick = { nav.openTab(tab) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = HomeRoute, modifier = Modifier.fillMaxSize().padding(if (fullScreen) PaddingValues() else padding)) {
            composable<HomeRoute> {
                HomeScreen(
                    onOpenComputer = { agent ->
                        agentToShow = agent
                        nav.openTab(Tab.COMPUTER)
                    },
                    onNewProject = { nav.navigate(NewProjectRoute) },
                    onOpenRepo = { nav.navigate(OpenRepoRoute) },
                    onUsage = { nav.openTab(Tab.USAGE) },
                    onHelp = { nav.navigate(HelpRoute) },
                )
            }
            composable<ComputerRoute> {
                ComputerScreen(
                    agentToShow = agentToShow,
                    onAgentShown = { agentToShow = null },
                    onHome = { nav.openTab(Tab.HOME) },
                    onHelp = { nav.navigate(HelpRoute) },
                )
            }
            composable<UsageRoute> { UsageScreen(onHelp = { nav.navigate(HelpPageRoute(DocsContent.USAGE_ID)) }) }
            composable<SettingsRoute> {
                SettingsScreen(
                    onYourData = { nav.navigate(DataRoute) },
                    onHelp = { nav.navigate(HelpRoute) },
                    onHelpPage = { nav.navigate(HelpPageRoute(it)) },
                )
            }
            composable<DataRoute> { YourDataScreen(onBack = { nav.popBackStack() }, onHelpPage = { nav.navigate(HelpPageRoute(it)) }) }
            composable<HelpRoute> { HelpScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(HelpPageRoute(it)) }) }
            composable<HelpPageRoute> { backStack ->
                HelpPageScreen(id = backStack.toRoute<HelpPageRoute>().id, onBack = { nav.popBackStack() }, onOpen = { nav.navigate(HelpPageRoute(it)) })
            }
            composable<NewProjectRoute> {
                NewProjectScreen(
                    onBack = { nav.popBackStack() },
                    onReady = {
                        nav.popBackStack()
                        nav.openTab(Tab.COMPUTER)
                    },
                )
            }
            composable<OpenRepoRoute> {
                OpenRepoScreen(
                    onBack = { nav.popBackStack() },
                    onReady = {
                        nav.popBackStack()
                        nav.openTab(Tab.COMPUTER)
                    },
                )
            }
        }
    }
}

/** Material's tab behaviour: one copy of each tab, its state kept when the owner comes back to it. */
private fun NavHostController.openTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
