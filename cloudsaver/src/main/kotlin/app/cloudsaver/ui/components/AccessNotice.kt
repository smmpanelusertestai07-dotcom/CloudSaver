package app.cloudsaver.ui.components

import androidx.annotation.StringRes
import app.cloudsaver.R
import app.cloudsaver.util.Permissions.MediaAccess

/**
 * What each screen says when it cannot see the whole gallery.
 *
 * There are two ways to lose sight of it and only one of them was handled.
 * Partial access - the "Select photos" answer on Android 14 and later - had a
 * card, a chip and a waiting line. No access at all had none of them: someone
 * who finished setup and later switched the permission off in system settings
 * came back to a Home screen with nothing on it, a Files list that said the
 * gallery was empty, and a calculator that printed a total from a database
 * nothing was refreshing. Every one of those is a false statement about
 * somebody's photographs.
 *
 * So the question every screen asks is "is access full?", never "is it
 * partial?", and the wording for the answer is chosen here rather than
 * repeated - the two cases differ only in their words, never in what the
 * screen is allowed to claim.
 */
object AccessNotice {

    /** True when the screen must not present totals as the whole gallery. */
    fun isLimited(access: MediaAccess): Boolean = access != MediaAccess.FULL

    @StringRes
    fun title(access: MediaAccess): Int =
        if (access == MediaAccess.NONE) R.string.no_access_title else R.string.partial_title

    @StringRes
    fun body(access: MediaAccess): Int =
        if (access == MediaAccess.NONE) R.string.no_access_body else R.string.partial_body

    @StringRes
    fun action(access: MediaAccess): Int =
        if (access == MediaAccess.NONE) R.string.no_access_action else R.string.partial_action

    @StringRes
    fun chip(access: MediaAccess): Int =
        if (access == MediaAccess.NONE) R.string.no_access_chip else R.string.partial_chip

    @StringRes
    fun waiting(access: MediaAccess): Int =
        if (access == MediaAccess.NONE) R.string.no_access_waiting else R.string.partial_waiting
}
