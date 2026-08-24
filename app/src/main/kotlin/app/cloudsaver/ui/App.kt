package app.cloudsaver.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.cloudsaver.R
import app.cloudsaver.ui.components.AppBackground
import app.cloudsaver.ui.screens.ActivityScreen
import app.cloudsaver.ui.screens.FilesScreen
import app.cloudsaver.ui.screens.BiggestFilesScreen
import app.cloudsaver.ui.screens.DuplicatesScreen
import app.cloudsaver.ui.screens.KeptCopiesScreen
import app.cloudsaver.ui.screens.ReclaimHistoryScreen
import app.cloudsaver.ui.screens.ReclaimScreen
import app.cloudsaver.ui.screens.CalculatorScreen
import app.cloudsaver.ui.screens.HelpAboutScreen
import app.cloudsaver.ui.screens.HelpFaqScreen
import app.cloudsaver.ui.screens.HelpLicensesScreen
import app.cloudsaver.ui.screens.HelpLogsScreen
import app.cloudsaver.ui.screens.HelpPrivacyScreen
import app.cloudsaver.ui.screens.HelpQualityScreen
import app.cloudsaver.ui.screens.HelpScreen
import app.cloudsaver.core.logic.TabBadges
import app.cloudsaver.ui.screens.HomeScreen
import app.cloudsaver.ui.screens.LockedScreen
import app.cloudsaver.ui.screens.OnboardingScreen
import app.cloudsaver.ui.screens.OptionsScreen
import app.cloudsaver.ui.screens.StorageScreen
import app.cloudsaver.ui.theme.CloudSaverTheme

object Routes {
    const val HOME = "home"
    const val FILES = "files"
    const val STORAGE = "storage"
    const val OPTIONS = "options"
    const val FREE_UP = "freeup"
    const val ACTIVITY = "activity"
    const val RECLAIM_HISTORY = "reclaim_history"
    const val DUPLICATES = "duplicates"
    const val BIGGEST = "biggest"
    const val KEPT = "kept"
    const val CALCULATOR = "calculator"
    const val HELP = "help"
    const val HELP_FAQ = "help_faq"
    const val HELP_QUALITY = "help_quality"
    const val HELP_LOGS = "help_logs"
    const val HELP_PRIVACY = "help_privacy"
    const val HELP_LICENSES = "help_licenses"
    const val HELP_ABOUT = "help_about"

    /** Screens behind the optional app lock. */
    val LOCKED = setOf(FILES, OPTIONS, FREE_UP, RECLAIM_HISTORY, DUPLICATES, BIGGEST, KEPT)
}

@Composable
fun App(vm: AppViewModel) {
    val options by vm.options.collectAsStateWithLifecycle()
    val loaded by vm.optionsLoaded.collectAsStateWithLifecycle()
    CloudSaverTheme(mode = options.theme, dynamicColor = options.dynamicColor) {
        AppBackground {
            when {
                // One frame of the app's own background rather than a flash of
                // the welcome card at someone who set this up months ago.
                !loaded -> Unit
                !options.onboardingDone -> OnboardingScreen(vm)
                else -> MainNav(vm)
            }
        }
    }
}

@Composable
private fun MainNav(vm: AppViewModel) {
    val nav: NavHostController = rememberNavController()
    // Reclaim keeps its own view model so a four-hundred-file selection
    // survives a rotation; losing it silently is how someone deletes the
    // wrong batch.
    val reclaimVm: ReclaimViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Routes.HOME
    val options by vm.options.collectAsStateWithLifecycle()
    // Tab dots. Both are claims on attention, so both come from one tested
    // rule rather than from whatever each screen happens to know.
    val reclaimable by vm.reclaimableBytes.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    var unlocked by remember { mutableStateOf(false) }
    val activity = androidx.activity.compose.LocalActivity.current as? FragmentActivity

    // The Settings dot has to be right on whichever tab the app opens on, so
    // health is refreshed here rather than only by Home, and again every time
    // the app comes back to the foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.refreshHealth() }

    // A lock that only ever asks once is not a lock: re-arm it whenever the
    // app leaves the foreground, so returning to it authenticates again.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { unlocked = false }

    val needsLock = options.appLock && !unlocked && route in Routes.LOCKED

    // An alert that opens the app should land on the screen it was about.
    // Consumed once, so rotating the phone does not navigate again.
    val deepLink by vm.deepLink.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(deepLink) {
        val target = deepLink ?: return@LaunchedEffect
        vm.clearDeepLink()
        if (target != route) {
            nav.navigate(target) { launchSingleTop = true }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        // contentColorFor(Transparent) is Unspecified, and Surface would then
        // hand that down as the content colour, undoing what AppBackground set
        // and leaving unstyled text black on the dark palette.
        contentColor = MaterialTheme.colorScheme.onBackground,
        bottomBar = {
            if (route in setOf(Routes.HOME, Routes.FILES, Routes.STORAGE, Routes.OPTIONS)) {
                // Opaque, and one step off the page rather than translucent:
                // a see-through bar let content slide under the selected pill,
                // which read as a stray shape floating over the screen.
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp
                ) {
                    TabItem(nav, route, Routes.HOME, R.drawable.ic_tab_home, R.string.nav_home)
                    TabItem(nav, route, Routes.FILES, R.drawable.ic_tab_files, R.string.nav_files)
                    TabItem(
                        nav, route, Routes.STORAGE, R.drawable.ic_tab_storage,
                        R.string.nav_storage,
                        badge = TabBadges.storage(reclaimable)
                    )
                    TabItem(
                        nav, route, Routes.OPTIONS, R.drawable.ic_tab_options,
                        R.string.nav_options,
                        badge = TabBadges.settings(
                            cloudMissing = health.cloudMissing,
                            usageAccessOff = health.usageAccessOff,
                            backgroundWorkStopped = health.backgroundWorkStopped,
                            spaceLow = health.spaceLow
                        )
                    )
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
                modifier = Modifier.padding(padding),
                enterTransition = {
                    fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 }
                },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = {
                    fadeIn(tween(220)) + slideInHorizontally(tween(260)) { -it / 12 }
                },
                popExitTransition = { fadeOut(tween(160)) }
            ) {
                composable(Routes.HOME) { HomeScreen(vm, nav) }
                composable(Routes.FILES) { FilesScreen(vm) }
                composable(Routes.STORAGE) { StorageScreen(vm, nav) }
                composable(Routes.OPTIONS) { OptionsScreen(vm, nav) }
                composable(Routes.FREE_UP) { ReclaimScreen(vm, reclaimVm, nav) }
                composable(Routes.RECLAIM_HISTORY) { ReclaimHistoryScreen(reclaimVm, nav) }
                composable(Routes.DUPLICATES) { DuplicatesScreen(vm, reclaimVm, nav) }
                composable(Routes.BIGGEST) { BiggestFilesScreen(vm, reclaimVm, nav) }
                composable(Routes.KEPT) { KeptCopiesScreen(vm, nav) }
                composable(Routes.CALCULATOR) { CalculatorScreen(vm, nav) }
                composable(Routes.ACTIVITY) { ActivityScreen(vm, nav) }
                composable(Routes.HELP) { HelpScreen(vm, nav) }
                composable(Routes.HELP_FAQ) { HelpFaqScreen(nav) }
                composable(Routes.HELP_QUALITY) { HelpQualityScreen(nav, vm) }
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
    labelRes: Int,
    badge: Boolean = false
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
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        icon = {
            if (badge) {
                BadgedBox(badge = { Badge() }) {
                    Icon(
                        painterResource(iconRes),
                        contentDescription = stringResource(labelRes)
                    )
                }
            } else {
                Icon(
                    painterResource(iconRes),
                    contentDescription = stringResource(labelRes)
                )
            }
        },
        label = { Text(stringResource(labelRes)) }
    )
}
