package com.pocketide.google

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.math.min

/**
 * Drive's resumable upload for files over the multipart limit: open a session, send 8 MiB
 * chunks, and after a dropped connection or a server error ask Drive how far it got (an empty
 * PUT whose Content-Range names only the total) and continue from there. Any other 4xx means
 * the session is dead, so a new one starts (once). The session is remembered in memory, so a
 * later call for the same content resumes it too.
 */
internal class ResumableUpload(private val calls: DriveCalls) {
    private val http get() = calls.http
    private val backoff get() = http.backoff

    private sealed interface Step {
        data class Done(val file: FileJson) : Step

        /** Drive holds bytes up to (not including) [next]. */
        data class Received(val next: Long) : Step

        /** Connection dropped, rate limit or server error: wait, ask where it stopped, go on. */
        data class Broken(val error: DriveException, val retryAfterMs: Long?) : Step

        /** Drive no longer knows the session: start a new one. */
        data class Gone(val error: DriveException) : Step
    }

    suspend fun send(name: String, payload: Payload, existingId: String?, md5: String): FileJson {
        val total = payload.length
        val key = UploadSessions.key(calls.tokens.account(), name, existingId, md5, total)
        var session = http.sessions.get(key)
        // Null means "unknown": a remembered session, or one whose last chunk may have half arrived.
        var offset: Long? = if (session == null) 0L else null
        var confirmed = 0L
        var failures = 0
        var restarts = 0
        while (true) {
            val uri = session ?: open(name, total, existingId).also {
                session = it
                http.sessions.put(key, it)
                offset = 0L
                confirmed = 0L
            }
            val at = offset
            val step = if (at == null || at >= total) status(uri, total) else chunk(uri, payload, at)
            when (step) {
                is Step.Done -> {
                    http.sessions.remove(key)
                    return step.file
                }
                is Step.Received -> {
                    if (step.next > confirmed) {
                        confirmed = step.next
                        failures = 0
                    }
                    offset = step.next
                }
                is Step.Broken -> {
                    if (failures >= backoff.maxRetries) throw step.error
                    backoff.pause(backoff.delayFor(failures++, step.retryAfterMs))
                    offset = null
                }
                is Step.Gone -> {
                    http.sessions.remove(key)
                    if (restarts >= MAX_RESTARTS) throw step.error
                    restarts++
                    session = null
                }
            }
        }
    }

    private suspend fun open(name: String, total: Long, existingId: String?): String {
        val url = http.endpoints.upload.newBuilder().addPathSegment("files")
            .apply { if (existingId != null) addPathSegment(existingId) }
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        val metadata = (if (existingId == null) createMetadata(name) else "{}").toRequestBody(JSON_UTF8)
        val location = calls.exchange {
            url(url)
            header("X-Upload-Content-Type", OCTET_STREAM.toString())
            header("X-Upload-Content-Length", total.toString())
            if (existingId == null) post(metadata) else patch(metadata)
        }.use { it.header("Location") }
        return checkedSession(location)
    }

    /** The token goes with every chunk, so the session must be on Drive's own upload host. */
    private fun checkedSession(location: String?): String {
        val url = location?.toHttpUrlOrNull()
        val upload = http.endpoints.upload
        if (url == null || url.scheme != upload.scheme || url.host != upload.host || url.port != upload.port) {
            throw DriveException.Other("Google Drive did not start the upload. Try again later.")
        }
        return url.toString()
    }

    private suspend fun chunk(uri: String, payload: Payload, offset: Long): Step {
        val end = min(offset + CHUNK_BYTES, payload.length)
        return put(uri) {
            header("Content-Range", "bytes $offset-${end - 1}/${payload.length}")
            put(payload.slice(offset, end - offset))
        }
    }

    private suspend fun status(uri: String, total: Long): Step = put(uri) {
        header("Content-Range", "bytes */$total")
        put(ByteArray(0).toRequestBody())
    }

    private suspend fun put(uri: String, build: Request.Builder.() -> Unit): Step = try {
        calls.authorized(http.uploadClient) {
            url(uri)
            build()
        }.use(::read)
    } catch (e: DriveException.Offline) {
        Step.Broken(e, null)
    }

    private fun read(response: Response): Step = when {
        response.isSuccessful -> Step.Done(DriveCalls.decode(response, FileJson.serializer()))
        response.code == HTTP_RESUME_INCOMPLETE -> Step.Received(receivedUpTo(response.header("Range")))
        else -> when (val verdict = DriveCalls.judge(response)) {
            is Verdict.SlowDown -> Step.Broken(DriveException.RateLimited(verdict.retryAfterMs ?: 0L), verdict.retryAfterMs)
            is Verdict.Fatal -> when (verdict.error) {
                is DriveException.StorageFull, is DriveException.Revoked -> throw verdict.error
                else -> Step.Gone(verdict.error)
            }
            Verdict.Unauthorized -> throw DriveException.Revoked()
        }
    }

    companion object {
        /** A multiple of 256 KiB, as Drive requires for every chunk but the last. */
        const val CHUNK_BYTES = 8L * 1024 * 1024
        private const val HTTP_RESUME_INCOMPLETE = 308
        private const val MAX_RESTARTS = 1

        /** `Range: bytes=0-N` means N + 1 bytes arrived; no header means none did. */
        fun receivedUpTo(range: String?): Long {
            val last = range?.substringAfter("bytes=", "")?.substringAfter('-', "")?.trim()?.toLongOrNull()
            return if (last == null) 0L else last + 1
        }
    }
}
