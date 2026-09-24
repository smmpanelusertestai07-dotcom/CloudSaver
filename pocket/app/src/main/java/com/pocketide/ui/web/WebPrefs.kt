package com.pocketide.ui.web

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Choices that fit this phone's screen, kept on this phone only (they are not AI data and are not
 * synced): each agent's text size, the terminal's font size, and notes already shown once.
 * Read and written off the main thread.
 */
class WebPrefs internal constructor(private val prefs: Lazy<SharedPreferences>) {
    suspend fun agentZoom(agentId: String): Int = read { clampZoom(it.getInt("zoom:$agentId", DEFAULT_ZOOM)) }

    suspend fun setAgentZoom(agentId: String, zoom: Int) = write { it.putInt("zoom:$agentId", clampZoom(zoom)) }

    suspend fun terminalFont(): Int = read { clampFont(it.getInt("terminal-font", DEFAULT_FONT)) }

    suspend fun setTerminalFont(px: Int) = write { it.putInt("terminal-font", clampFont(px)) }

    suspend fun noteShown(id: String): Boolean = read { it.getBoolean("note:$id", false) }

    suspend fun markNoteShown(id: String) = write { it.putBoolean("note:$id", true) }

    private suspend fun <T> read(block: (SharedPreferences) -> T): T = withContext(Dispatchers.IO) { block(prefs.value) }

    private suspend fun write(block: (SharedPreferences.Editor) -> Unit) = withContext(Dispatchers.IO) {
        prefs.value.edit().also(block).apply()
    }

    companion object {
        const val DEFAULT_ZOOM = 100
        /** The text sizes the agent menu steps through, in percent. */
        val ZOOMS = listOf(100, 115, 125, 150)
        const val DEFAULT_FONT = 14
        const val MIN_FONT = 10
        const val MAX_FONT = 24
    }
}

/** The text size after [current] in the menu's cycle. */
fun nextZoom(current: Int): Int {
    val next = WebPrefs.ZOOMS.firstOrNull { it > current }
    return next ?: WebPrefs.ZOOMS.first()
}

fun clampZoom(zoom: Int): Int = zoom.coerceIn(WebPrefs.ZOOMS.first(), WebPrefs.ZOOMS.last())

fun clampFont(px: Int): Int = px.coerceIn(WebPrefs.MIN_FONT, WebPrefs.MAX_FONT)

@Composable
fun rememberWebPrefs(): WebPrefs {
    val context = LocalContext.current.applicationContext
    return remember(context) { WebPrefs(lazy { context.getSharedPreferences("ui-web", Context.MODE_PRIVATE) }) }
}
