package app.litesaver.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.litesaver.R
import app.litesaver.ui.components.GlassBackground
import app.litesaver.ui.screens.CalculatorScreen
import app.litesaver.ui.screens.FilesScreen
import app.litesaver.ui.screens.FreeUpScreen
import app.litesaver.ui.screens.HelpAboutScreen
import app.litesaver.ui.screens.HelpFaqScreen
import app.litesaver.ui.screens.HelpLicensesScreen
import app.litesaver.ui.screens.HelpLogsScreen
import app.litesaver.ui.screens.HelpPrivacyScreen
import app.litesaver.ui.screens.HelpQualityScreen
import app.litesaver.ui.screens.HelpScreen
import app.litesaver.ui.screens.HomeScreen
import app.litesaver.ui.screens.LockedScreen
import app.litesaver.ui.screens.OnboardingScreen
import app.litesaver.ui.screens.OptionsScreen
import app.litesaver.ui.screens.StorageScreen
import app.litesaver.ui.theme.LiteSaverTheme

object Routes {
    const val HOME = "home"
    const val FILES = "files"
    const val STORAGE = "storage"
    const val OPTIONS = "options"
    const val FREE_UP = "freeup"
    const val CALC = "calc"
    const val HELP = "help"
    const val HELP_FAQ = "help_faq"
    const val HELP_QUALITY = "help_quality"
    const val HELP_LOGS = "help_logs"
    const val HELP_PRIVACY = "help_privacy"
    const val HELP_LICENSES = "help_licenses"
    const val HELP_ABOUT = "help_about"

    /** Screens behind the optional app lock. */
    val LOCKED = setOf(FILES, OPTIONS, FREE_UP)
}

@Composable
fun App(vm: AppViewModel) {
    val options by vm.options.collectAsStateWithLifecycle()
    LiteSaverTheme(mode = options.theme, dynamicColor = options.dynamicColor) {
        GlassBackground {
            if (!options.onboardingDone) {
                OnboardingScreen(vm)
            } else {
                MainNav(vm)
            }
        }
    }
}

@Composable
private fun MainNav(vm: AppViewModel) {
    val nav: NavHostController = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Routes.HOME
    val options by vm.options.collectAsStateWithLifecycle()
    var unlocked by remember { mutableStateOf(false) }
    val activity = androidx.activity.compose.LocalActivity.current as? FragmentActivity

    val needsLock = options.appLock && !unlocked && route in Routes.LOCKED

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (route in setOf(Routes.HOME, Routes.FILES, Routes.STORAGE, Routes.OPTIONS)) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                ) {
                    TabItem(nav, route, Routes.HOME, R.drawable.ic_tab_home, R.string.nav_home)
                    TabItem(nav, route, Routes.FILES, R.drawable.ic_tab_files, R.string.nav_files)
                    TabItem(nav, route, Routes.STORAGE, R.drawable.ic_tab_storage, R.string.nav_storage)
                    TabItem(nav, route, Routes.OPTIONS, R.drawable.ic_tab_options, R.string.nav_options)
                }
            }
        }
    ) { padding ->
        if (needsLock) {
            LockedScreen(
                modifier = Modifier.padding(padding),
                onUnlock = {
                    val act = activity ?: return@LockedScreen
                    Lock.authenticate(
                        act,
                        act.getString(R.string.lock_title),
                        act.getString(R.string.lock_subtitle)
                    ) { ok -> if (ok) unlocked = true }
                }
            )
        } else {
            NavHost(
                navController = nav,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(padding)
            ) {
                composable(Routes.HOME) { HomeScreen(vm, nav) }
                composable(Routes.FILES) { FilesScreen(vm) }
                composable(Routes.STORAGE) { StorageScreen(vm, nav) }
                composable(Routes.OPTIONS) { OptionsScreen(vm, nav) }
                composable(Routes.FREE_UP) { FreeUpScreen(vm, nav) }
                composable(Routes.CALC) { CalculatorScreen(vm, nav) }
                composable(Routes.HELP) { HelpScreen(vm, nav) }
                composable(Routes.HELP_FAQ) { HelpFaqScreen(nav) }
                composable(Routes.HELP_QUALITY) { HelpQualityScreen(nav) }
                composable(Routes.HELP_LOGS) { HelpLogsScreen(nav) }
                composable(Routes.HELP_PRIVACY) { HelpPrivacyScreen(nav) }
                composable(Routes.HELP_LICENSES) { HelpLicensesScreen(nav) }
                composable(Routes.HELP_ABOUT) { HelpAboutScreen(vm, nav) }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    nav: NavHostController,
    current: String,
    route: String,
    iconRes: Int,
    labelRes: Int
) {
    NavigationBarItem(
        selected = current == route,
        onClick = {
            if (current != route) {
                nav.navigate(route) {
                    popUpTo(Routes.HOME) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        },
        icon = {
            Icon(
                painterResource(iconRes),
                contentDescription = stringResource(labelRes)
            )
        },
        label = { Text(stringResource(labelRes)) }
    )
}
