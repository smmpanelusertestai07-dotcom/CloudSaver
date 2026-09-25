package com.pocketide.media

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.pocketide.AppGraph
import com.pocketide.sync.SyncStatus
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.InputStream

fun createMediaLibrary(graph: AppGraph): MediaLibrary = SessionMediaLibrary(
    dirs = graph.dirs,
    sessions = { graph.sessions.all.value },
    backup = {
        val sync = graph.sync
        BackupFacts(
            waitingSessions = sync.waiting.value.map { it.sessionId }.toSet(),
            upToDateAt = (sync.status.value as? SyncStatus.UpToDate)?.at,
        )
    },
    shrinker = AndroidWebpShrinker(),
    clock = graph.clock,
    io = Dispatchers.IO,
    metaDir = File(graph.dirs.base, "media-meta"),
    stagingDir = File(graph.dirs.downloads, "media-staging"),
    uriFor = { file -> FileProvider.getUriForFile(graph.context, "${graph.context.packageName}.files", file) },
    phoneFiles = ContentResolverFiles(graph.context.contentResolver),
)

/** Reads what the owner picked through Android's own pickers; no storage permission is needed. */
private class ContentResolverFiles(private val resolver: ContentResolver) : PhoneFiles {
    override fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    override fun open(uri: Uri): InputStream? = resolver.openInputStream(uri)
}
