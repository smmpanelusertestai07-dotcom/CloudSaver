package app.cloudsaver.util

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.cloudsaver.OpenEnteActivity
import app.cloudsaver.R

/**
 * A home-screen shortcut that opens Ente Photos under a gallery's icon and
 * name - "Photos", "Gallery", "Cloud Photos" or the person's own - so Ente
 * can sit where the phone's gallery used to, with nothing to confuse. The
 * icon is the same one the icon pack gives Ente.
 *
 * The phone asks the person to confirm before it is placed. Some launchers
 * draw a small Ente Saver badge on a shortcut; that is the launcher's, not
 * something an app can turn off.
 */
object PhotosShortcut {

    const val MAX_LABEL = 12

    fun supported(context: Context): Boolean =
        runCatching { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }.getOrDefault(false)

    /** The label as it would be placed: trimmed, at most [MAX_LABEL] characters, never empty. */
    fun cleanLabel(label: String, fallback: String): String =
        label.trim().replace(Regex("""\s+"""), " ").take(MAX_LABEL).ifEmpty { fallback }

    /** Asks the launcher to place the shortcut; false when it would not. */
    fun request(context: Context, label: String): Boolean {
        val intent = Intent(context, OpenEnteActivity::class.java).setAction(Intent.ACTION_VIEW)
        val info = ShortcutInfoCompat.Builder(context, "photos_${label.hashCode()}")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_shortcut_photos))
            .setIntent(intent)
            .build()
        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) }.getOrDefault(false)
    }
}
