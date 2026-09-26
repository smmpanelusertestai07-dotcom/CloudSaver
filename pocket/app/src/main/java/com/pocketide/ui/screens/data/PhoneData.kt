package com.pocketide.ui.screens.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.app.NotificationManagerCompat
import com.pocketide.AppGraph
import com.pocketide.cloud.ComputerService
import com.pocketide.core.AppFolders
import com.pocketide.core.FileTrees
import com.pocketide.core.OldVersionFiles
import com.pocketide.core.afterDeleteEverything
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything PocketIDE keeps on the phone, deleted: the GitHub sign-in, the computer page's own
 * GitHub sign-in (cookies and site storage), caches, settings and anything an older version left.
 * Nothing in the owner's GitHub account is touched: repositories and cloud computers stay. Main
 * thread (it ends the page).
 */
suspend fun deleteFromPhone(context: Context, graph: AppGraph) {
    ComputerService.disconnect(context)
    graph.computerPage.release()
    graph.gitHubAuth.signOut()
    CookieManager.getInstance().apply {
        removeAllCookies(null)
        flush()
    }
    WebStorage.getInstance().deleteAllData()
    NotificationManagerCompat.from(context).cancelAll()
    withContext(Dispatchers.IO) {
        graph.secureStore.deleteAll()
        FileTrees.deleteContents(context.cacheDir)
        OldVersionFiles.remove(AppFolders.of(context))
    }
    graph.settings.update { it.afterDeleteEverything() }
}
