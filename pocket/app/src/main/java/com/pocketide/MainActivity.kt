package com.pocketide

import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.pocketide.core.ThemeMode
import com.pocketide.ui.PocketRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The single activity. Screenshots are always allowed. With App lock on, Recents shows a cover
 * instead of the screen (see [PocketRoot]); the lock itself is part of [PocketRoot] too.
 */
class MainActivity : FragmentActivity() {
    private val computerAsked = MutableStateFlow(false)

    /** True when the ongoing notification asked for the computer screen; the UI takes it once. */
    val openComputer: StateFlow<Boolean> = computerAsked.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        systemBars(graph.settings.settings.value.theme)
        if (savedInstanceState == null) take(intent)
        lifecycleScope.launch {
            graph.settings.settings.map { it.appLock to it.theme }.distinctUntilChanged().collect { (lock, theme) ->
                hideInRecents(lock)
                followTheme(theme)
                systemBars(theme)
            }
        }
        setContent { PocketRoot(activity = this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
    }

    fun computerOpened() {
        computerAsked.value = false
    }

    private fun take(intent: Intent?) {
        val fromHistory = (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (intent?.action == ACTION_OPEN_COMPUTER && !fromHistory) computerAsked.value = true
    }

    /**
     * Android 13 and newer keep no picture of the screen for Recents while App lock is on; older
     * versions keep the picture of the cover that [PocketRoot] draws when the app leaves the front.
     */
    private fun hideInRecents(lock: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!lock)
    }

    /**
     * Status and navigation bar icons in the app's own light or dark choice, which can differ from
     * the phone's: dark icons on a dark app would be invisible.
     */
    private fun systemBars(theme: ThemeMode) {
        val dark = { resources: Resources ->
            when (theme) {
                ThemeMode.SYSTEM -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT, dark),
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM, dark),
        )
    }

    /**
     * Android 12 and newer take the app's own light or dark choice, so the system's screens and the
     * cloud computer's page (which follows the app's night mode) match the app.
     */
    private fun followTheme(theme: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mode = when (theme) {
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        }
        getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode)
    }

    companion object {
        const val ACTION_OPEN_COMPUTER = "com.pocketide.OPEN_COMPUTER"

        // The scrims enableEdgeToEdge uses by default behind three-button navigation.
        private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
