package app.entesaver.data

import android.content.Context

/**
 * Ente Saver as it was until 12.2, under its earlier Android id. To Android
 * that is a different app, so it can stay on the phone beside this one - and
 * both would then work on the same photos. While it is there, Home says so
 * and opens its App info page, where Uninstall is.
 */
object EarlierInstall {

    const val PACKAGE = "app.cloudsaver"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }
}
