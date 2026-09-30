package com.pocketide.ui

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
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
import com.pocketide.docs.AppPlace
import com.pocketide.graph
import com.pocketide.ui.lock.HiddenContentCover
import com.pocketide.ui.lock.LockScreen
import com.pocketide.ui.nav.AddAgentsRoute
import com.pocketide.ui.nav.CloudShellRoute
import com.pocketide.ui.nav.ComputerRoute
import com.pocketide.ui.nav.DataRoute
import com.pocketide.ui.nav.HelpPageRoute
import com.pocketide.ui.nav.HelpRoute
import com.pocketide.ui.nav.HomeRoute
import com.pocketide.ui.nav.KeysRoute
import com.pocketide.ui.nav.SettingsRoute
import com.pocketide.ui.nav.WorkspaceRoute
import com.pocketide.ui.screens.agents.AddAgentsScreen
import com.pocketide.ui.screens.cloudshell.CloudShellScreen
import com.pocketide.ui.screens.computer.ComputerScreen
import com.pocketide.ui.screens.data.YourDataScreen
import com.pocketide.ui.screens.help.HelpPageScreen
import com.pocketide.ui.screens.help.HelpScreen
import com.pocketide.ui.screens.help.LocalOpenPlace
import com.pocketide.ui.screens.home.HomeScreen
import com.pocketide.ui.screens.keys.KeysScreen
import com.pocketide.ui.screens.onboarding.Onboarding
import com.pocketide.ui.screens.settings.SettingsScreen
import com.pocketide.ui.screens.workspace.WorkspaceScreen
import com.pocketide.ui.shell.LocalBottomBarPadding
import com.pocketide.ui.theme.PocketTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * Theme, app lock, first-run set-up, then the app with its bottom bar. Everything sits on one
 * [Surface], so text takes the theme's colour on every screen. With App lock on, the app is
 * covered whenever it is not in front, so Recents keeps a picture of the cover, not the screen.
 */
@Composable
fun PocketRoot(activity: MainActivity) {
    val graph = activity.graph
    val appLock = (activity.application as PocketApp).appLock
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    PocketTheme(settings.theme, settings.dynamicColor) {
        val locked by appLock.locked.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize()) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when {
                    settings.appLock && locked -> LockScreen(activity, onUnlocked = appLock::unlock)
                    !settings.onboardingDone -> Onboarding(graph)
                    else -> MainScreens()
                }
            }
            if (settings.appLock && !state.isAtLeast(Lifecycle.State.RESUMED)) HiddenContentCover()
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector, val route: Any) {
    HOME("Home", Icons.Outlined.Home, HomeRoute),
    COMPUTER("Computer", Icons.Outlined.Terminal, ComputerRoute),
    SETTINGS("Settings", Icons.Outlined.Settings, SettingsRoute),
}

@Composable
private fun MainScreens() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val fullScreen = destination?.hasRoute(WorkspaceRoute::class) == true
    val showBar = Tab.entries.any { destination?.hasRoute(it.route::class) == true }
    val haze = rememberHazeState()
    val openPlace: (String) -> Unit = { id ->
        when (AppPlace.of(id)) {
            AppPlace.KEYS -> nav.navigate(KeysRoute)
            AppPlace.SETTINGS -> nav.openTab(Tab.SETTINGS)
            AppPlace.DATA -> nav.navigate(DataRoute)
            AppPlace.COMPUTER -> nav.openTab(Tab.COMPUTER)
            AppPlace.AGENTS -> nav.navigate(AddAgentsRoute)
            AppPlace.CLOUD_SHELL -> nav.navigate(CloudShellRoute)
            null -> Unit
        }
    }
    CompositionLocalProvider(LocalOpenPlace provides openPlace) {
        Scaffold(bottomBar = { if (showBar) GlassBar(nav, destination, haze) }) { padding ->
            // The pages run behind the bottom bar, which blurs them; each one ends with room to scroll clear of it.
            val bar = if (showBar) padding.calculateBottomPadding() else 0.dp
            CompositionLocalProvider(LocalBottomBarPadding provides bar) {
                NavHost(
                    nav,
                    startDestination = HomeRoute,
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(haze)
                        .padding(if (fullScreen) PaddingValues() else PaddingValues(top = padding.calculateTopPadding())),
                ) {
                    composable<HomeRoute> {
                        HomeScreen(
                            onOpenAgent = { id -> nav.navigate(WorkspaceRoute(agentId = id)) },
                            onSignIn = { id, command, title -> nav.navigate(WorkspaceRoute(agentId = id, terminal = true, command = command, title = title)) },
                            onTerminal = { nav.navigate(WorkspaceRoute(terminal = true, title = "Terminal")) },
                            onAddAgents = { nav.navigate(AddAgentsRoute) },
                            onComputer = { nav.openTab(Tab.COMPUTER) },
                            onCloudShell = { nav.navigate(CloudShellRoute) },
                            onHelp = { nav.navigate(HelpRoute) },
                            onHelpPage = { nav.navigate(HelpPageRoute(it)) },
                        )
                    }
                    composable<ComputerRoute> {
                        ComputerScreen(onHelp = { nav.navigate(HelpRoute) }, onHelpPage = { nav.navigate(HelpPageRoute(it)) })
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(
                            onKeys = { nav.navigate(KeysRoute) },
                            onYourData = { nav.navigate(DataRoute) },
                            onHelp = { nav.navigate(HelpRoute) },
                            onHelpPage = { nav.navigate(HelpPageRoute(it)) },
                        )
                    }
                    composable<WorkspaceRoute> { backStack ->
                        val route = backStack.toRoute<WorkspaceRoute>()
                        WorkspaceScreen(
                            route = route,
                            onBack = { nav.popBackStack() },
                            onHelpPage = { nav.navigate(HelpPageRoute(it)) },
                        )
                    }
                    composable<AddAgentsRoute> { AddAgentsScreen(onBack = { nav.popBackStack() }) }
                    composable<KeysRoute> { KeysScreen(onBack = { nav.popBackStack() }, onHelpPage = { nav.navigate(HelpPageRoute(it)) }) }
                    composable<DataRoute> { YourDataScreen(onBack = { nav.popBackStack() }, onHelpPage = { nav.navigate(HelpPageRoute(it)) }) }
                    composable<CloudShellRoute> { CloudShellScreen(onBack = { nav.popBackStack() }, onHelpPage = { nav.navigate(HelpPageRoute(it)) }) }
                    composable<HelpRoute> { HelpScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(HelpPageRoute(it)) }) }
                    composable<HelpPageRoute> { backStack ->
                        HelpPageScreen(id = backStack.toRoute<HelpPageRoute>().id, onBack = {
                            nav.popBackStack()
                        }, onOpen = { nav.navigate(HelpPageRoute(it)) })
                    }
                }
            }
        }
    }
}

/**
 * The bottom bar, frosted: the page scrolls behind it, blurred (Android 12 and newer can blur; older
 * phones get a solid tint instead).
 */
@Composable
private fun GlassBar(nav: NavHostController, destination: androidx.navigation.NavDestination?, haze: HazeState) {
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val style = HazeStyle(
        backgroundColor = surface,
        tint = HazeTint(surface.copy(alpha = if (canBlur) GLASS_TINT else 1f)),
        blurRadius = 24.dp,
        noiseFactor = 0f,
    )
    NavigationBar(
        modifier = Modifier.hazeEffect(state = haze, style = style),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
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

private const val GLASS_TINT = 0.72f

/** Material's tab behaviour: one copy of each tab, its state kept when the owner comes back to it. */
private fun NavHostController.openTab(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
