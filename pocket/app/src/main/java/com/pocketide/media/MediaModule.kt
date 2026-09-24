package com.pocketide.media

import androidx.core.content.FileProvider
import com.pocketide.AppGraph
import com.pocketide.sync.SyncStatus
import kotlinx.coroutines.Dispatchers
import java.io.File

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
    uriFor = { file -> FileProvider.getUriForFile(graph.context, "${graph.context.packageName}.files", file) },
)
