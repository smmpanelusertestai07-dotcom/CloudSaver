package app.cloudsaver.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.cloudsaver.ui.theme.Dimens
import app.cloudsaver.R
import app.cloudsaver.ui.components.AppBackground
import app.cloudsaver.ui.screens.ActivityScreen
import app.cloudsaver.ui.screens.FilesScreen
import app.cloudsaver.ui.screens.FreeSpaceHubScreen
import app.cloudsaver.ui.screens.BiggestFilesScreen
import app.cloudsaver.ui.screens.DuplicatesScreen
import app.cloudsaver.ui.screens.KeptCopiesScreen
import app.cloudsaver.ui.screens.ReclaimHistoryScreen
import app.cloudsaver.ui.screens.ReclaimScreen
import app.cloudsaver.ui.screens.CalculatorScreen
import app.cloudsaver.ui.screens.HelpAboutScreen
import app.cloudsaver.ui.screens.HelpDeletedScreen
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
    const val FREE_SPACE_HUB = "free_space_hub"
    const val ACTIVITY = "activity"
    const val RECLAIM_HISTORY = "reclaim_history"
    const val DUPLICATES = "duplicates"
    const val BIGGEST = "biggest"
    const val KEPT = "kept"
    const val CALCULATOR = "calculator"
    const val HELP = "help"
    const val HELP_FAQ = "help_faq"
    const val HELP_DELETED = "help_deleted"
    const val HELP_QUALITY = "help_quality"
    const val HELP_LOGS = "help_logs"
    const val HELP_PRIVACY = "help_privacy"
    const val HELP_LICENSES = "help_licenses"
    const val HELP_ABOUT = "help_about"

    /** The four bottom-bar destinations. */
    val TABS = setOf(HOME, FILES, STORAGE, OPTIONS)
}

/**
 * Go to a screen, switching tabs properly when the target is one.
 *
 * Every route in the app goes through here, because the two cases need
 * different navigation and getting it wrong strands the user. A plain
 * navigate() to a tab pushes a *second* copy of that tab on top of the stack:
 * the bottom bar then sees itself as already on that tab and ignores taps,
 * while Home cannot be reached because the pop target is buried underneath.
 * That is exactly the trap a chip on Home used to drop people into - into
 * Files, with the Home tab dead.
 *
 * Tabs therefore pop back to the graph's start destination and reuse the
 * existing entry; everything else is an ordinary push that the back arrow
 * undoes. popUpTo targets the real start destination rather than a hard-coded
 * route, so it stays correct if the start ever changes.
 */
fun NavHostController.goTo(route: String) {
    if (route == currentDestination?.route) return
    if (route in Routes.TABS) {
        navigate(route) {
            popUpTo(graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    } else {
        navigate(route) { launchSingleTop = true }
    }
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

    // The whole app, not a list of screens. Locking only the screens that
    // hold file lists left Home, Storage, the calculator and every Help page
    // readable to anyone who tapped a different tab - and the tab bar stayed
    // live underneath the lock, so changing tabs was all it took. A lock that
    // covers part of an app is a lock someone walks around; every app that
    // offers one (messengers, banks, photo vaults) gates the whole surface.
    val needsLock = options.appLock && !unlocked

    // An alert that opens the app should land on the screen it was about.
    // Consumed once, so rotating the phone does not navigate again.
    //
    // It waits for the lock. While the app is locked there is no NavHost in
    // composition, so the controller has no graph and navigating into it
    // throws - tapping an alert on a locked phone would have crashed the app
    // instead of asking for a fingerprint. The link is simply held until the
    // gate opens, and then honoured.
    val deepLink by vm.deepLink.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(deepLink, needsLock) {
        val target = deepLink ?: return@LaunchedEffect
        if (needsLock) return@LaunchedEffect
        vm.clearDeepLink()
        nav.goTo(target)
    }

    Scaffold(
        containerColor = Color.Transparent,
        // contentColorFor(Transparent) is Unspecified, and Surface would then
        // hand that down as the content colour, undoing what AppBackground set
        // and leaving unstyled text black on the dark palette.
        contentColor = MaterialTheme.colorScheme.onBackground,
        bottomBar = {
            if (!needsLock &&
                route in setOf(Routes.HOME, Routes.FILES, Routes.STORAGE, Routes.OPTIONS)
            ) {
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
            var lockNote by remember { mutableStateOf<Lock.Outcome?>(null) }
            LockedScreen(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .wrapContentWidth()
                    .widthIn(max = Dimens.ContentMaxWidth),
                outcome = lockNote,
                onUnlock = {
                    val act = activity ?: return@LockedScreen
                    Lock.authenticate(
                        act,
                        act.getString(R.string.lock_title),
                        act.getString(R.string.lock_subtitle)
                    ) { outcome ->
                        lockNote = outcome
                        when (outcome) {
                            Lock.Outcome.Unlocked -> unlocked = true
                            // The phone's own lock was removed - Android has
                            // already wiped biometric enrolment with it, and
                            // removing it required knowing it. The app lock
                            // turns itself off visibly instead of becoming a
                            // door with no key.
                            Lock.Outcome.NoMethod -> vm.disableLockNoCredential()
                            else -> Unit
                        }
                    }
                }
            )
        } else {
            NavHost(
                navController = nav,
                startDestination = Routes.HOME,
                // One rule for every screen, applied once: the content never
                // spans more than a comfortable reading width, and is centred
                // in whatever is left. On a phone - portrait or landscape,
                // 320 dp or 480 - nothing changes at all, because nothing is
                // that wide. On a tablet, a foldable opened out, or a phone
                // turned sideways it stops a line of text running the full
                // width of the glass, which is unreadable and is the one way
                // the same app looks like a different app on a bigger screen.
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .wrapContentWidth()
                    .widthIn(max = Dimens.ContentMaxWidth),
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
                composable(Routes.FREE_SPACE_HUB) { FreeSpaceHubScreen(vm, nav) }
                composable(Routes.FREE_UP) { ReclaimScreen(vm, reclaimVm, nav) }
                composable(Routes.RECLAIM_HISTORY) { ReclaimHistoryScreen(reclaimVm, nav) }
                composable(Routes.DUPLICATES) { DuplicatesScreen(vm, reclaimVm, nav) }
                composable(Routes.BIGGEST) { BiggestFilesScreen(vm, reclaimVm, nav) }
                composable(Routes.KEPT) { KeptCopiesScreen(vm, nav) }
                composable(Routes.CALCULATOR) { CalculatorScreen(vm, nav) }
                composable(Routes.ACTIVITY) { ActivityScreen(vm, nav) }
                composable(Routes.HELP) { HelpScreen(vm, nav) }
                composable(Routes.HELP_FAQ) { HelpFaqScreen(nav) }
                composable(Routes.HELP_DELETED) { HelpDeletedScreen(nav) }
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
        onClick = { nav.goTo(route) },
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
