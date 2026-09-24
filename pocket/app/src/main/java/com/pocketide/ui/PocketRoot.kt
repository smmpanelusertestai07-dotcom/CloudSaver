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
import androidx.compose.runtime.SideEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.pocketide.AppGraph
import com.pocketide.core.ThemeMode
import com.pocketide.graph
import com.pocketide.lock.AppLock
import com.pocketide.model.LockReason
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
            val unlocked by graph.appLock.unlocked.collectAsStateWithLifecycle()
            val access by graph.access.state.collectAsStateWithLifecycle()
            // Asked once: the answer depends on hardware and Android, which do not change while running.
            val unsupported = remember { runCatching { graph.limiter.unsupportedReason() }.getOrNull() }
            val gate = RootGate.of(
                appLockOn = settings.appLock,
                unlocked = unlocked,
                unsupportedReason = unsupported,
                lock = access.lock,
                onboardingDone = settings.onboardingDone,
            )
            // The main app keeps its back stack and screen state while a lock covers it.
            val navController = rememberNavController()
            val saved = rememberSaveableStateHolder()
            Crossfade(targetState = gate, animationSpec = tween(220), label = "root") { shown ->
                when (shown) {
                    RootGate.AppLocked -> AppLockGate(graph, activity)
                    is RootGate.Refused -> LockScreen(LockReason.Unsupported(shown.why))
                    is RootGate.Locked -> LockScreen(shown.reason)
                    RootGate.Onboarding -> OnboardingFlow()
                    RootGate.Main -> saved.SaveableStateProvider("main") {
                        AppNav(navController = navController, banner = access.banner)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppLockGate(graph: AppGraph, activity: FragmentActivity) {
    var message by remember { mutableStateOf<String?>(null) }
    val secure = remember { graph.appLock.deviceSecure() }
    AppLockScreen(
        onUnlock = {
            message = null
            graph.appLock.authenticate(activity, "Unlock PocketIDE") { ok ->
                if (!ok) message = "Not unlocked. Tap Unlock to try again."
            }
        },
        message = message,
        deviceSecure = secure,
        onSetScreenLock = { External.openSecuritySettings(activity) },
    )
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
