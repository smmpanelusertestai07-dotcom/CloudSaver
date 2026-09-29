package com.pocketide.ui.screens.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.app.NotificationManagerCompat
import com.pocketide.AppGraph
import com.pocketide.core.AppFolders
import com.pocketide.core.FileTrees
import com.pocketide.core.OldVersionFiles
import com.pocketide.core.afterDeleteEverything
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything PocketIDE keeps on the phone, deleted: the computer with the owner's projects, the
 * agents and their sign-ins and chats, the keys, the agent screen's page storage, caches and
 * settings. Main thread (it ends the page).
 */
suspend fun deleteEverything(context: Context, graph: AppGraph) {
    graph.stopEverything()
    graph.page.release()
    graph.computer.remove()
    CookieManager.getInstance().apply {
        removeAllCookies(null)
        flush()
    }
    WebStorage.getInstance().deleteAllData()
    NotificationManagerCompat.from(context).cancelAll()
    withContext(Dispatchers.IO) {
        graph.keys.clear()
        graph.secureStore.deleteAll()
        FileTrees.deleteContents(context.cacheDir)
        OldVersionFiles.remove(AppFolders.of(context))
    }
    graph.settings.update { it.afterDeleteEverything() }
}
