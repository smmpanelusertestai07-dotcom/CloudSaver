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

    /** A content:// URI for the share sheet, to a copy made now of a plain file in the session's media. */
    fun shareUri(item: MediaItem): android.net.Uri

    /**
     * Copies an APK once into the app's private storage, out of reach of Linux. The package and
     * signer shown to the owner are read from [PrivateCopy.file], and Install hands over that same
     * copy, so the file cannot be swapped between the owner's check and the tap.
     */
    suspend fun stageApk(item: MediaItem): PrivateCopy =
        throw UnsupportedOperationException("Installing apps is not available here.")
}

/** A copy in the app's private storage, out of reach of Linux. */
class PrivateCopy(val file: File, private val handOut: (File) -> android.net.Uri) {
    /** The content:// URI that hands exactly this copy to another app. */
    val uri: android.net.Uri get() = handOut(file)
}
