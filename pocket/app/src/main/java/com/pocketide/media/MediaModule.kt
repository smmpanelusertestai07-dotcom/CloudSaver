package com.pocketide.media

import android.net.Uri
import com.pocketide.AppGraph
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File

fun createMediaLibrary(graph: AppGraph): MediaLibrary = StubMedia().also { graph.hashCode() }

private class StubMedia : MediaLibrary {
    override fun forSession(sessionId: String): Flow<List<MediaItem>> = flowOf(emptyList())
    override suspend fun add(sessionId: String, source: File, name: String, from: String): MediaItem = throw IllegalStateException("stub")
    override fun kindOf(name: String, head: ByteArray) = MediaKind.OTHER
    override suspend fun delete(item: MediaItem) = Unit
    override fun shareUri(item: MediaItem): Uri = Uri.EMPTY
}
