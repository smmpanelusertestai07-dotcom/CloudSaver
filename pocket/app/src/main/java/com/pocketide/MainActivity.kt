package com.pocketide

import android.app.UiModeManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
 * The single activity. With "Hide from screenshots" on (the default), FLAG_SECURE keeps code and
 * chats out of screenshots and the Recents preview; the app lock itself is part of [PocketRoot].
 */
class MainActivity : FragmentActivity() {
    private val computerAsked = MutableStateFlow(false)

    /** True when the ongoing notification asked for the computer screen; the UI takes it once. */
    val openComputer: StateFlow<Boolean> = computerAsked.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) take(intent)
        lifecycleScope.launch {
            graph.settings.settings.map { it.hideScreen to it.theme }.distinctUntilChanged().collect { (hide, theme) ->
                if (hide) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                followTheme(theme)
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
    }
}
