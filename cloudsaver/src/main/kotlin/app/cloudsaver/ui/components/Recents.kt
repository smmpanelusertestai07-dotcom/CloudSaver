package app.cloudsaver.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Keeps this app out of the recent-apps switcher while the app lock is on.
 *
 * The switcher is the hole an app lock leaves open. Android photographs the
 * last frame as the app goes to the background, and that picture stays
 * readable behind the lock - so a locked app whose file list was on screen
 * hands the list to whoever presses the recents key. Every app that offers a
 * lock closes that hole, which is why a payment app in the switcher shows
 * the phone's own "content hidden" card instead of its last screen.
 *
 * Which switch, depends on what the Android of the day offers:
 *
 *  - Android 13 and later have one that means exactly this and nothing more
 *    (`setRecentsScreenshotEnabled`): the thumbnail is replaced, and the
 *    person can still screenshot their own storage figures to keep or send.
 *  - Before that, FLAG_SECURE is the only way to keep the thumbnail out, and
 *    it stops screenshots too. That is the price of the older API rather
 *    than a choice, and it is paid only while the lock is on.
 *
 * Nothing is taken away when the lock is off. The earlier version also held
 * the flag over the free-up screen with the lock off, which bought no
 * privacy - the phone is already unlocked and the list is the person's own -
 * and cost them the screenshot of what they were about to delete.
 *
 * One caller owns the window, keyed on the setting. Three screens sharing
 * one window flag is how the first version lost it: each cleared the flag as
 * it left, so the first unlock - the locked screen leaving the composition -
 * put the whole app back in the switcher for the rest of the process, with
 * the setting still on and nothing on screen to say otherwise.
 */
@Composable
fun HideWhileLocked(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        // Nothing to hide behind: not "the lock is off", but a window this
        // composable cannot reach. It cannot happen inside this app, where
        // the only host is MainActivity, and it is written separately so
        // that it stays visible if one ever is added.
        val activity = context.findActivity() ?: return@DisposableEffect onDispose { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(false)
            onDispose { activity.setRecentsScreenshotEnabled(true) }
        } else {
            activity.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
