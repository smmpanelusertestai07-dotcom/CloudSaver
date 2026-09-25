package com.pocketide

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import com.pocketide.rooms.RoomNotices
import com.pocketide.ui.PocketRoot
import com.pocketide.ui.screens.project.SessionShortcut
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single activity. FLAG_SECURE keeps chats and code out of screenshots and the recents
 * thumbnail; the app lock itself is part of [PocketRoot].
 *
 * Launches that ask for something (an agent notification or a pinned shortcut naming a session,
 * a file shared from another app) are held here until the UI takes them, which it does only
 * after the app lock and once navigation is ready.
 */
class MainActivity : FragmentActivity() {
    private val session = MutableStateFlow<String?>(null)
    private val shared = MutableStateFlow<Uri?>(null)

    /** The session whose agent screen should open next; null when none is waiting. */
    val sessionToOpen: StateFlow<String?> = session.asStateFlow()

    /** A file another app shared, waiting for the owner to pick a session for it. */
    val sharedFile: StateFlow<Uri?> = shared.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            take(intent)
        } else {
            session.value = savedInstanceState.getString(STATE_SESSION)?.takeIf(SessionShortcut::isSessionId)
        }
        setContent { PocketRoot(activity = this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        session.value?.let { outState.putString(STATE_SESSION, it) }
    }

    /** Called once the agent screen for [sessionToOpen] is shown. */
    fun sessionOpened() {
        session.value = null
    }

    /** Called once the shared file was added to a session, or the owner cancelled. */
    fun sharedFileHandled() {
        shared.value = null
    }

    private fun take(intent: Intent?) {
        if (intent == null) return
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        sessionToOpen(
            notice = intent.getStringExtra(RoomNotices.EXTRA_SESSION),
            shortcut = intent.getStringExtra(SessionShortcut.EXTRA_SESSION),
            fromHistory = fromHistory,
        )?.let { session.value = it }
        // Recents hands the original intent back; a relaunch from there must not open it again.
        intent.removeExtra(RoomNotices.EXTRA_SESSION)
        intent.removeExtra(SessionShortcut.EXTRA_SESSION)

        if (intent.action == Intent.ACTION_SEND && !fromHistory) {
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (isShareable(uri?.scheme, uri?.authority, packageName)) shared.value = uri
        }
    }

    private companion object {
        const val STATE_SESSION = "pocketide.session_to_open"
    }
}

/**
 * The session a launch names: a notification's or a shortcut's, as a session id and never
 * anything else. A relaunch from Recents carries the old extras again and opens nothing.
 */
internal fun sessionToOpen(notice: String?, shortcut: String?, fromHistory: Boolean): String? {
    if (fromHistory) return null
    return listOfNotNull(notice, shortcut).firstOrNull(SessionShortcut::isSessionId)
}

/**
 * Only content shared through a content provider of another app. A file:// path could name this
 * app's own private files, and our own provider only serves files the app already has.
 */
internal fun isShareable(scheme: String?, authority: String?, packageName: String): Boolean =
    scheme == ContentResolver.SCHEME_CONTENT && authority != null && !authority.startsWith("$packageName.")
