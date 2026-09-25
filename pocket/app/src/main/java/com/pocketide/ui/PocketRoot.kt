package com.pocketide.ui

import android.app.Activity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.pocketide.AppGraph
import com.pocketide.MainActivity
import com.pocketide.core.ThemeMode
import com.pocketide.graph
import com.pocketide.lock.AppLock
import com.pocketide.model.LockReason
import com.pocketide.vault.KeyState
import com.pocketide.ui.screens.lock.AppLockScreen
import com.pocketide.ui.screens.lock.LockScreen
import com.pocketide.ui.screens.onboarding.OnboardingFlow
import com.pocketide.ui.shell.External
import com.pocketide.ui.shell.RootGate
import com.pocketide.ui.theme.PocketTheme

/**
 * The whole UI, in order: theme → app lock → phones that cannot run it → access locks (GitHub,
 * Drive, storage, another phone) → first-run set-up → the app.
 */
@Composable
fun PocketRoot(activity: FragmentActivity) {
    val graph = remember(activity) { activity.graph }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    AppLockLifecycle(graph.appLock)

    PocketTheme(settings.theme) {
        SystemBarIcons(settings.theme)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Not lifecycle-aware on purpose: the lock re-arms as the app stops, exactly when such a
            // collector pauses, and the first frame back must already show the lock.
            val unlocked by graph.appLock.unlocked.collectAsState()
            val access by graph.access.state.collectAsStateWithLifecycle()
            // Asked once: the answer depends on hardware and Android, which do not change while running.
            val unsupported = remember { runCatching { graph.limiter.unsupportedReason() }.getOrNull() }
            val keyOnPhone = if (settings.onboardingDone) true else rememberKeyOnPhone(graph)
            LaunchedEffect(keyOnPhone) { if (!keyOnPhone) graph.appLock.openForSetUp() }
            val gate = RootGate.of(
                appLockOn = settings.appLock,
                unlocked = unlocked,
                unsupportedReason = unsupported,
                lock = access.lock,
                onboardingDone = settings.onboardingDone,
                keyOnPhone = keyOnPhone,
            )
            // Each part keeps its saved state (steps, ticks, back stack, pending results from
            // Google's sheet) while the app lock replaces it; a GitHub sign-in waiting in Chrome
            // lives in DeviceSignIn. Nothing stays composed under the lock, so no dialog can
            // show through it.
            val navController = rememberNavController()
            val saved = rememberSaveableStateHolder()

            Crossfade(targetState = gate, animationSpec = tween(220), label = "root") { shown ->
                when (shown) {
                    RootGate.AppLocked -> AppLockGate(graph, activity)
                    is RootGate.Refused -> LockScreen(LockReason.Unsupported(shown.why))
                    is RootGate.Locked -> saved.SaveableStateProvider("locked:${shown.reason::class.simpleName}") { LockScreen(shown.reason) }
                    RootGate.Onboarding -> saved.SaveableStateProvider("onboarding") { OnboardingFlow() }
                    RootGate.Main -> saved.SaveableStateProvider("main") {
                        AppNav(navController = navController, banner = access.banner, launch = rememberLaunch(activity))
                    }
                }
            }
        }
    }
}

/** The session and the shared file the activity holds, taken only once the app is unlocked and set up. */
@Composable
private fun rememberLaunch(activity: FragmentActivity): Launch {
    val main = activity as? MainActivity ?: return Launch.NONE
    val session by main.sessionToOpen.collectAsStateWithLifecycle()
    val shared by main.sharedFile.collectAsStateWithLifecycle()
    return Launch(
        sessionToOpen = session,
        onSessionOpened = main::sessionOpened,
        sharedFile = shared,
        onSharedFileHandled = main::sharedFileHandled,
    )
}

@Composable
private fun AppLockGate(graph: AppGraph, activity: FragmentActivity) {
    var message by remember { mutableStateOf<String?>(null) }
    // Android's prompt, or its PIN screen, is on its way or showing.
    var prompting by remember { mutableStateOf(false) }
    // The owner may add a screen lock in Android's settings and come back.
    var secure by remember { mutableStateOf(graph.appLock.deviceSecure()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { secure = graph.appLock.deviceSecure() }
    AppLockScreen(
        onUnlock = {
            message = null
            prompting = true
            graph.appLock.authenticate(activity, "Unlock PocketIDE") { ok ->
                prompting = false
                if (!ok) message = "Not unlocked. Tap Unlock to try again."
            }
        },
        message = message,
        deviceSecure = secure,
        onSetScreenLock = { External.openSecuritySettings(activity) },
        prompting = prompting,
    )
}

/** Whether this phone holds the chats' key yet; read only during set-up, when it can still be missing. */
@Composable
private fun rememberKeyOnPhone(graph: AppGraph): Boolean {
    val key by graph.vault.state.collectAsState()
    return key == KeyState.Ready || key == KeyState.OnlyOnPhone
}

/** Tells the app lock when the whole app leaves and returns, not each activity or dialog. */
@Composable
private fun AppLockLifecycle(lock: AppLock) {
    DisposableEffect(lock) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> lock.onForeground()
                Lifecycle.Event.ON_STOP -> lock.onBackground()
                else -> Unit
            }
        }
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** Dark icons on a light theme and light icons on a dark one, even when the app's theme differs from the phone's. */
@Composable
private fun SystemBarIcons(mode: ThemeMode) {
    val view = LocalView.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
