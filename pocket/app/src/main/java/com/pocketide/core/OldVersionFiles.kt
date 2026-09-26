package com.pocketide.core

import android.content.Context
import java.io.File
import java.io.IOException

/** The app's private folders, as plain files so what empties them can be tested off a phone. */
internal class AppFolders(val files: File, val noBackup: File, val cache: File, val data: File) {
    /** The sealed GitHub sign-in ([SecureStore]). */
    val secure: File get() = File(noBackup, SECURE)

    companion object {
        const val SECURE = "secure"

        fun of(context: Context) = AppFolders(context.filesDir, context.noBackupFilesDir, context.cacheDir, context.dataDir)
    }
}

/**
 * What earlier PocketIDE versions kept in the app's private storage, deleted once after an update.
 *
 * Up to 3.0.0 the phone was the computer: its Linux (gigabytes), copies of the projects, each
 * agent's sign-in, the chat vault, an earlier GitHub sign-in and the Google account's name lived
 * here. This version keeps only the sealed GitHub sign-in and the settings, and nothing in it can
 * open the rest, so an update in place would leave all of it until the app is uninstalled. The web
 * page's own storage stays: the page deletes that through its own APIs.
 */
internal object OldVersionFiles {
    private const val DONE = "old-versions-removed"

    /** The settings, and the backup Android keeps of them while it writes. */
    private const val SETTINGS_PREFS = "pocketide.settings."

    /** Chromium's own files for the web page, by the names it gives them. */
    private val WEB_PAGE = listOf("WebView", "org.chromium")

    /** Once per install: after an update from an older version (a new install has nothing to find). */
    fun removeOnce(folders: AppFolders) {
        val done = File(folders.noBackup, DONE)
        if (done.exists() || !remove(folders)) return
        try {
            done.createNewFile()
        } catch (_: IOException) {
            // Tried again at the next start, which then finds nothing to delete.
        }
    }

    /** Everything but this version's sign-in, its settings and the web page's storage; true when all of it went. */
    fun remove(folders: AppFolders): Boolean = listOf(
        FileTrees.deleteContents(folders.files),
        FileTrees.deleteContents(folders.noBackup) { it == AppFolders.SECURE || it == DONE },
        FileTrees.deleteContents(folders.cache, ::isWebPage),
        FileTrees.deleteContents(File(folders.data, "databases")),
        FileTrees.deleteContents(File(folders.data, "shared_prefs")) { it.startsWith(SETTINGS_PREFS) || isWebPage(it) },
    ).all { it }

    private fun isWebPage(name: String) = WEB_PAGE.any(name::startsWith)
}
