package com.pocketide.google

import kotlinx.serialization.Serializable
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.ByteString.Companion.toByteString
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.source
import java.io.File

internal val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()
internal val JSON_UTF8: MediaType = "application/json; charset=UTF-8".toMediaType()

/** What an upload sends: a file on disk or bytes in memory, readable in slices for resumable chunks. */
internal sealed interface Payload {
    val length: Long

    /** A repeatable body for bytes [offset] until [offset] + [count], so a retry can send it again. */
    fun slice(offset: Long, count: Long): RequestBody

    /** Lowercase hex MD5, compared with Drive's `md5Checksum` after the upload. */
    fun md5(): String

    fun whole(): RequestBody = slice(0, length)
}

internal class FilePayload(private val file: File) : Payload {
    override val length: Long = file.length()

    override fun slice(offset: Long, count: Long): RequestBody = object : RequestBody() {
        override fun contentType(): MediaType = OCTET_STREAM
        override fun contentLength(): Long = count
        override fun writeTo(sink: BufferedSink) {
            file.source().buffer().use { source ->
                source.skip(offset)
                sink.write(source, count)
            }
        }
    }

    override fun md5(): String = HashingSink.md5(blackholeSink()).use { hashing ->
        file.source().buffer().use { it.readAll(hashing) }
        hashing.hash.hex()
    }
}

internal class BytesPayload(private val bytes: ByteArray) : Payload {
    override val length: Long = bytes.size.toLong()

    override fun slice(offset: Long, count: Long): RequestBody =
        bytes.toRequestBody(OCTET_STREAM, offset.toInt(), count.toInt())

    override fun md5(): String = bytes.toByteString().md5().hex()
}

/** A file as Drive describes it; int64 values arrive as strings. */
@Serializable
internal data class FileJson(
    val id: String = "",
    val name: String = "",
    val size: String? = null,
    val modifiedTime: String? = null,
    val md5Checksum: String? = null,
    val quotaBytesUsed: String? = null,
) {
    fun toDriveFile() = DriveFile(id, name, size?.toLongOrNull() ?: 0L, modifiedTime, md5Checksum)

    /** What the file costs in the owner's storage (falls back to its size). */
    fun storedBytes(): Long = quotaBytesUsed?.toLongOrNull() ?: size?.toLongOrNull() ?: 0L
}

@Serializable
internal class FileListJson(val files: List<FileJson> = emptyList(), val nextPageToken: String? = null)

@Serializable
internal class AboutJson(val storageQuota: QuotaJson? = null, val user: UserJson? = null)

@Serializable
internal class QuotaJson(
    val limit: String? = null,
    val usage: String? = null,
    val usageInDrive: String? = null,
)

@Serializable
internal class UserJson(val emailAddress: String? = null)

@Serializable
internal class HeadRevisionJson(val headRevisionId: String? = null)

@Serializable
internal class RevisionJson(val id: String = "", val md5Checksum: String? = null)

@Serializable
internal class RevisionListJson(val revisions: List<RevisionJson> = emptyList(), val nextPageToken: String? = null)
