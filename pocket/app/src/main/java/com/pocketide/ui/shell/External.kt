package com.pocketide.ui.shell

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/** Leaving the app: web pages go to Chrome (or the default browser), settings to Android's own. */
object External {
    private const val CHROME = "com.android.chrome"

    /**
     * Opens [url] outside the app. Chrome first because sign-in pages and the owner's accounts
     * live there; any browser otherwise. Nothing but HTTPS pages is ever opened.
     */
    fun openUrl(context: Context, url: String) {
        if (!Links.isOpenable(url)) {
            toast(context, "That link can't be opened.")
            return
        }
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = start(context, Intent(view).setPackage(CHROME)) || start(context, view)
        if (!started) toast(context, "No browser found. Install Chrome to open this page.")
    }

    /** Android's screen-lock settings, to set a PIN, pattern or password. */
    fun openSecuritySettings(context: Context) {
        val flags = if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
        val opened = start(context, Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(flags)) ||
            start(context, Intent(Settings.ACTION_SETTINGS).addFlags(flags))
        if (!opened) toast(context, "Open Settings → Security to set a screen lock.")
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
