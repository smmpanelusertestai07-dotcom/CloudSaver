package com.pocketide.ui.screens.project

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.pocketide.MainActivity
import com.pocketide.R
import com.pocketide.model.SessionRecord

/**
 * A home-screen shortcut straight to a session's agent room. The launcher hands back only the
 * session id; MainActivity reads it with [sessionIdFrom] and opens the agent screen after the
 * app lock, like any other navigation.
 */
object SessionShortcut {
    const val EXTRA_SESSION = "com.pocketide.extra.SESSION_ID"

    private val SESSION_ID = Regex("[A-Za-z0-9-]{1,64}")

    /** A session id as sessions are made (a UUID), so a crafted intent cannot carry anything else. */
    fun isSessionId(text: String): Boolean = SESSION_ID.matches(text)

    /** The session a shortcut opens, or null when [intent] is not one of ours. */
    fun sessionIdFrom(intent: Intent?): String? = intent?.getStringExtra(EXTRA_SESSION)?.takeIf(::isSessionId)

    fun shortcutId(sessionId: String): String = "session:$sessionId"

    /** Asks the launcher to pin a shortcut; false when this launcher cannot pin shortcuts. */
    fun pin(context: Context, session: SessionRecord, agentName: String): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val open = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_SESSION, session.id)
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId(session.id))
            .setShortLabel(session.title.take(SHORT_LABEL))
            .setLongLabel("$agentName · ${session.title}".take(LONG_LABEL))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(open)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    // Launchers cut longer labels themselves; these are the lengths Android recommends.
    private const val SHORT_LABEL = 10
    private const val LONG_LABEL = 25
}
