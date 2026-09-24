package com.pocketide.google

import com.pocketide.core.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

internal const val APP_DATA_FOLDER = "appDataFolder"
internal const val FILE_FIELDS = "id,name,size,modifiedTime,md5Checksum"

internal fun createMetadata(name: String): String = buildJsonObject {
    put("name", name)
    putJsonArray("parents") { add(APP_DATA_FOLDER) }
}.toString()

/** The account's email as Drive names it (Google's sign-in does not say which account was chosen). */
internal suspend fun accountEmail(http: DriveHttp, tokens: TokenSource): String = withContext(Dispatchers.IO) {
    val url = http.endpoints.api.newBuilder().addPathSegment("about").addQueryParameter("fields", "user").build()
    DriveCalls(http, tokens).json(url, AboutJson.serializer()).user?.emailAddress?.takeIf { it.isNotBlank() }
        ?: throw DriveException.Other("Google did not say which account was chosen. Try again.")
}

/**
 * Drive REST v3 on the hidden app folder, for one account. Everything here is already
 * encrypted by the vault; this class only moves bytes, checks them by MD5 and maps Drive's
 * answers to [DriveException]. All IO runs on [Dispatchers.IO] and stops when the caller is
 * cancelled.
 */
internal class DriveRestStore(
    private val http: DriveHttp,
    private val tokens: TokenSource,
    private val clock: Clock,
    private val ofAccount: (String) -> DriveStore,
) : DriveStore {
    private val calls = DriveCalls(http, tokens)
    private val api: HttpUrl get() = http.endpoints.api
    @Volatile private var usage: Pair<Long, Long>? = null

    override fun withAccount(email: String): DriveStore = ofAccount(email)

    override suspend fun list(): List<DriveFile> = listAll().map { it.toDriveFile() }

    override suspend fun find(name: String): DriveFile? = withContext(Dispatchers.IO) {
        val url = files()
            .addQueryParameter("spaces", APP_DATA_FOLDER)
            .addQueryParameter("q", "name = '${quote(name)}' and '$APP_DATA_FOLDER' in parents")
            // Oldest first: if two phones ever made the same name, every phone picks the same one.
            .addQueryParameter("orderBy", "createdTime")
            .addQueryParameter("pageSize", FIND_PAGE.toString())
            .addQueryParameter("fields", "files($FILE_FIELDS)")
            .build()
        calls.json(url, FileListJson.serializer()).files.firstOrNull()?.toDriveFile()
    }

    override suspend fun upload(name: String, source: File, existingId: String?): DriveFile =
        store(name, FilePayload(source), existingId)

    override suspend fun uploadBytes(name: String, bytes: ByteArray, existingId: String?): DriveFile =
        store(name, BytesPayload(bytes), existingId)

    override suspend fun download(id: String, sink: OutputStream): Unit = withContext(Dispatchers.IO) {
        var written = 0L
        var failures = 0
        while (true) {
            val response = calls.exchange {
                url(media(id))
                if (written > 0) header("Range", "bytes=$written-")
            }
            // A server that ignores Range sends everything again: skip what the sink already has.
            val skip = if (written > 0 && response.code != HTTP_PARTIAL) written else 0L
            try {
                response.use { written += copy(it, sink, skip) }
                return@withContext
            } catch (e: DroppedDownload) {
                written += e.copied
                if (failures >= http.backoff.maxRetries) throw DriveException.Offline()
                http.backoff.pause(http.backoff.delayFor(failures++))
            }
        }
    }

    override suspend fun open(id: String): InputStream = withContext(Dispatchers.IO) {
        calls.exchange { url(media(id)) }.body.byteStream()
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        forgetUsage()
        try {
            calls.exchange {
                url(files().addPathSegment(id).build())
                delete()
            }.close()
        } catch (_: DriveException.NotFound) {
            // Already gone, which is what was asked.
        }
    }

    override suspend fun quota(): DriveQuota = withContext(Dispatchers.IO) {
        val about = calls.json(
            api.newBuilder().addPathSegment("about").addQueryParameter("fields", "storageQuota,user").build(),
            AboutJson.serializer(),
        )
        val quota = about.storageQuota
        DriveQuota(
            limitBytes = quota?.limit?.toLongOrNull(),
            usageBytes = quota?.usage?.toLongOrNull() ?: 0L,
            usageInDriveBytes = quota?.usageInDrive?.toLongOrNull() ?: 0L,
            appDataBytes = appDataBytes(),
            email = about.user?.emailAddress,
        )
    }

    private suspend fun listAll(): List<FileJson> = withContext(Dispatchers.IO) {
        val all = ArrayList<FileJson>()
        var page: String? = null
        do {
            val url = files()
                .addQueryParameter("spaces", APP_DATA_FOLDER)
                .addQueryParameter("pageSize", LIST_PAGE.toString())
                .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS,quotaBytesUsed)")
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build()
            val result = calls.json(url, FileListJson.serializer())
            all += result.files
            page = result.nextPageToken?.takeIf { it.isNotEmpty() && it != page }
        } while (page != null)
        all
    }

    private suspend fun store(name: String, payload: Payload, existingId: String?): DriveFile = withContext(Dispatchers.IO) {
        forgetUsage()
        val md5 = payload.md5()
        var target = existingId
        val file = try {
            send(name, payload, target, md5)
        } catch (e: DriveException.NotFound) {
            // The file to replace was deleted meanwhile: make a new one, the caller records its id.
            if (target == null) throw e
            target = null
            send(name, payload, null, md5)
        }
        val arrived = file.md5Checksum
        if (arrived != null && !arrived.equals(md5, ignoreCase = true)) {
            if (target == null) deleteQuietly(file.id)
            throw DriveException.Other("The file arrived damaged in Google Drive. Try again.")
        }
        file.toDriveFile()
    }

    private suspend fun send(name: String, payload: Payload, existingId: String?, md5: String): FileJson = when {
        payload.length > MULTIPART_LIMIT -> ResumableUpload(calls).send(name, payload, existingId, md5)
        existingId == null -> calls.exchange {
            url(uploadUrl(null, "multipart"))
            post(
                MultipartBody.Builder()
                    .setType(MULTIPART_RELATED)
                    .addPart(createMetadata(name).toRequestBody(JSON_UTF8))
                    .addPart(payload.whole())
                    .build(),
            )
        }.use { DriveCalls.decode(it, FileJson.serializer()) }
        else -> calls.exchange {
            url(uploadUrl(existingId, "media"))
            patch(payload.whole())
        }.use { DriveCalls.decode(it, FileJson.serializer()) }
    }

    private suspend fun deleteQuietly(id: String) {
        try {
            delete(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: DriveException) {
            // The daily sweep removes stray files.
        }
    }

    private suspend fun appDataBytes(): Long {
        val now = clock.now()
        usage?.let { (at, bytes) -> if (now - at < USAGE_CACHE_MS) return bytes }
        val bytes = listAll().sumOf { it.storedBytes() }
        usage = now to bytes
        return bytes
    }

    private fun forgetUsage() {
        usage = null
    }

    private fun files(): HttpUrl.Builder = api.newBuilder().addPathSegment("files")

    private fun media(id: String): HttpUrl = files().addPathSegment(id).addQueryParameter("alt", "media").build()

    private fun uploadUrl(existingId: String?, type: String): HttpUrl = http.endpoints.upload.newBuilder()
        .addPathSegment("files")
        .apply { if (existingId != null) addPathSegment(existingId) }
        .addQueryParameter("uploadType", type)
        .addQueryParameter("fields", FILE_FIELDS)
        .build()

    /** The body broke off after [copied] bytes reached the sink. */
    private class DroppedDownload(val copied: Long) : Exception()

    /**
     * Streams the body into [sink], dropping the first [skip] bytes. A read that fails is a
     * dropped connection (resumed with Range); a write that fails is the phone's problem and
     * is thrown as it is.
     */
    private suspend fun copy(response: Response, sink: OutputStream, skip: Long): Long {
        val buffer = ByteArray(COPY_BUFFER)
        var toSkip = skip
        var copied = 0L
        val input = response.body.byteStream()
        while (true) {
            coroutineContext.ensureActive()
            val read = try {
                input.read(buffer)
            } catch (_: IOException) {
                throw DroppedDownload(copied)
            }
            if (read < 0) return copied
            val start = minOf(toSkip, read.toLong()).toInt()
            toSkip -= start
            if (read > start) {
                sink.write(buffer, start, read - start)
                copied += read - start
            }
        }
    }

    companion object {
        /** Drive's own line between a multipart and a resumable upload. */
        const val MULTIPART_LIMIT = 5L * 1024 * 1024
        private const val LIST_PAGE = 1000
        private const val FIND_PAGE = 10
        private const val HTTP_PARTIAL = 206
        private const val COPY_BUFFER = 64 * 1024
        private const val USAGE_CACHE_MS = 60_000L
        private val MULTIPART_RELATED = "multipart/related".toMediaType()

        /** Drive query strings quote with `'`; a backslash and a quote inside are escaped. */
        fun quote(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")
    }
}
