package com.pocketide.media

import kotlinx.coroutines.flow.Flow
import java.io.File

/** Only these are rendered, by Android's own decoders; anything else is a plain file. */
enum class MediaKind { IMAGE, VIDEO, HTML, PDF, APK, TEXT, OTHER }

data class MediaItem(
    val sessionId: String,
    val file: File,
    val name: String,
    val kind: MediaKind,
    val bytes: Long,
    val createdAt: Long,
    /** False while a video waits for Wi-Fi, or when the phone copy was cleaned (Drive has it). */
    val onPhone: Boolean,
    val backedUp: Boolean,
    /** Where it came from: "agent", "actions" or "you". */
    val source: String,
)

/**
 * Each session's media folder (outside the git worktree): screenshots and videos agents made
 * for the owner, Actions results, and attachments. Stored once per content hash; images saved by
 * PocketIDE's tools become WebP.
 */
interface MediaLibrary {
    fun forSession(sessionId: String): Flow<List<MediaItem>>

    /** Adds a file to a session's media (from an MCP tool, an Actions artifact, or the photo picker). */
    suspend fun add(sessionId: String, source: File, name: String, from: String): MediaItem

    /**
     * Adds a file the owner picked on the phone (the photo picker, "Open document", or a share to
     * PocketIDE), under the same size limits and format checks as any other file.
     */
    suspend fun addFromPhone(sessionId: String, uri: android.net.Uri): MediaItem =
        throw UnsupportedOperationException("Adding files from the phone is not available here.")

    fun kindOf(name: String, head: ByteArray): MediaKind

    suspend fun delete(item: MediaItem)

    /** A content:// URI for the share sheet or the installer. */
    fun shareUri(item: MediaItem): android.net.Uri
}
