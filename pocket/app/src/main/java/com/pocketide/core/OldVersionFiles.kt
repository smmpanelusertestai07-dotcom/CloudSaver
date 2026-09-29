package com.pocketide.core

import android.content.Context
import java.io.File
import java.io.IOException

/** The app's private folders, as plain files so what empties them can be tested off a phone. */
internal class AppFolders(val files: File, val noBackup: File, val cache: File, val data: File) {
    /** The sealed keys ([SecureStore]). */
    val secure: File get() = File(noBackup, SECURE)

    companion object {
        const val SECURE = "secure"

        fun of(context: Context) = AppFolders(context.filesDir, context.noBackupFilesDir, context.cacheDir, context.dataDir)
    }
}

/**
 * What earlier PocketIDE versions kept in the app's private storage, deleted once after an update.
 *
 * Up to 3.0.0 the computer had another layout (rooms, a chat vault, copies of the projects, a
 * Google account's name); nothing in this version can open it, so an update in place would leave
 * gigabytes until the app is uninstalled. 4.x kept a sealed GitHub sign-in for its cloud
 * computers, which this version no longer uses. This version's own computer
 * ([LinuxDirs.FOLDER]) and keys stay.
 */
internal object OldVersionFiles {
    private const val DONE = "old-versions-removed"

    /** 4.x's GitHub sign-in was deleted. */
    private const val DONE_V5 = "v5-removed"

    /** The only sealed file this version writes ([com.pocketide.core.KeyStore]), and its partial write. */
    private val KEPT_SECURE = setOf("keys", "keys.tmp")

    /** The settings, and the backup Android keeps of them while it writes. */
    private const val SETTINGS_PREFS = "pocketide.settings."

    /** Chromium's own files for the web page, by the names it gives them. */
    private val WEB_PAGE = listOf("WebView", "org.chromium")

    /**
     * Once per install: after an update from an older version (a new install has nothing to
     * find). True when something was deleted now, so the caller can also clear the web page's
     * storage, which only the WebView's own APIs may do.
     */
    fun removeOnce(folders: AppFolders): Boolean {
        var removed = false
        val done = File(folders.noBackup, DONE)
        if (!done.exists() && remove(folders)) {
            removed = true
            mark(done)
        }
        val doneV5 = File(folders.noBackup, DONE_V5)
        if (!doneV5.exists() && FileTrees.deleteContents(folders.secure) { it in KEPT_SECURE }) {
            removed = true
            mark(doneV5)
        }
        return removed
    }

    private fun mark(file: File) {
        try {
            file.createNewFile()
        } catch (_: IOException) {
            // Tried again at the next start, which then finds nothing to delete.
        }
    }

    /** Everything but this version's computer, keys and settings and the web page's storage; true when all of it went. */
    fun remove(folders: AppFolders): Boolean = listOf(
        FileTrees.deleteContents(folders.files) { it == COMPUTER },
        FileTrees.deleteContents(folders.noBackup) { it == AppFolders.SECURE || it == DONE || it == DONE_V5 },
        FileTrees.deleteContents(folders.cache, ::isWebPage),
        FileTrees.deleteContents(File(folders.data, "databases")),
        FileTrees.deleteContents(File(folders.data, "shared_prefs")) { it.startsWith(SETTINGS_PREFS) || isWebPage(it) },
    ).all { it }

    /** This version's computer ([com.pocketide.linux.LinuxDirs.FOLDER]); no earlier version used the name. */
    private const val COMPUTER = "computer"

    private fun isWebPage(name: String) = WEB_PAGE.any(name::startsWith)
}
