package com.pocketide.agents

import com.pocketide.core.await
import com.pocketide.model.Decision
import com.pocketide.sync.NeedsMobileData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A package that did not match what its publisher published. The bad copy is already deleted. */
internal class PackageRejected(message: String) : IOException(message)

/** A download the data rules do not allow now (Wi-Fi only, daily limit). Tried again later. */
internal class DownloadWaits(reason: String, question: NeedsMobileData? = null) : IOException(reason, question)

/**
 * The question behind [error] when a download the owner started waits for Wi-Fi: the screen asks
 * "Download <size> on mobile data?", calls DataBudget.allowOnce on yes, then tries again.
 */
fun mobileDataQuestion(error: Throwable): NeedsMobileData? =
    generateSequence(error) { it.cause }.filterIsInstance<NeedsMobileData>().firstOrNull()

/** What a download must match. At least one digest is required. */
internal data class Expected(
    val sha256: String? = null,
    val sha512: String? = null,
    /** Open VSX's Ed25519 key and its signature over the whole file. */
    val signature: Pair<ByteArray, ByteArray>? = null,
    /** Exact size when the publisher states it. */
    val bytes: Long? = null,
    val maxBytes: Long = MAX_PACKAGE_BYTES,
) {
    init {
        require(sha256 != null || sha512 != null) { "A download needs a published checksum" }
    }

    companion object {
        /** Well above the largest agent package (Codex, about 240 MB). */
        const val MAX_PACKAGE_BYTES = 1024L * 1024 * 1024
    }
}

/**
 * Downloads a file Android-side and keeps it only when it matches: size, SHA-256 and/or SHA-512,
 * and the registry's signature, all computed while the bytes stream to disk. The file appears
 * at [target] only after every check passed; a file that fails is deleted.
 *
 * A download that is cut off (the connection drops, Android stops the job) keeps what arrived,
 * and the next fetch continues it with a Range request after hashing the kept bytes again, so a
 * 240 MB package is not started over. A [target] already in place that passes every check is
 * used as it is (a run cut off during the install fetched it already).
 *
 * [allow] applies the data rules (big downloads wait for Wi-Fi by default) once the size is
 * known; [record] counts what arrived.
 */
internal class VerifiedDownload(
    private val client: OkHttpClient,
    private val allow: (bytes: Long) -> Decision,
    private val record: (bytes: Long) -> Unit,
    /** The data budget's name for these downloads, for the mobile-data question. */
    private val dataKind: String = DATA_KIND,
) {

    suspend fun fetch(url: HttpUrl, target: File, expected: Expected, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            target.parentFile?.let { Files.createDirectories(it.toPath()) }
            if (target.isFile && matches(target, expected)) return@withContext target
            val part = File(target.parentFile, target.name + PART)
            var keep = false
            try {
                receive(url, part, expected, onProgress)
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                target
            } catch (rejected: PackageRejected) {
                throw rejected
            } catch (cut: IOException) {
                keep = true
                throw cut
            } catch (cancelled: CancellationException) {
                keep = true
                throw cancelled
            } finally {
                if (!keep) Files.deleteIfExists(part.toPath())
            }
        }

    private suspend fun receive(url: HttpUrl, part: File, expected: Expected, onProgress: (Long, Long) -> Unit) {
        var have = if (part.isFile) part.length() else 0L
        if (have > (expected.bytes ?: expected.maxBytes)) {
            Files.delete(part.toPath())
            have = 0
        }
        var checks = Checks(expected)
        if (have > 0) checks.feed(part)
        val request = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        client.newCall(request).await().use { response ->
            if (response.code == HTTP_RANGE_NOT_SATISFIABLE) {
                Files.delete(part.toPath())
                throw IOException("The download server could not continue the download")
            }
            if (!response.isSuccessful) throw IOException("The download server answered ${response.code}")
            val append = have > 0 && continues(response, have, part)
            if (!append) {
                have = 0
                checks = Checks(expected)
            }
            val body = response.body
            val total = (if (append) contentRangeTotal(response) else body.contentLength().takeIf { it >= 0 }) ?: expected.bytes ?: -1
            if (total > expected.maxBytes) throw PackageRejected("The download is larger than any agent package should be")
            if (expected.bytes != null && total >= 0 && total != expected.bytes) throw PackageRejected("The download has the wrong size")
            val decision = allow((total - have).coerceAtLeast(0))
            if (!decision.allowed) {
                throw DownloadWaits(decision.reason ?: "Waiting for Wi-Fi", NeedsMobileData.of(decision, dataKind, (total - have).coerceAtLeast(0)))
            }

            var done = have
            try {
                FileOutputStream(part, append).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            done += read
                            if (done > expected.maxBytes) throw PackageRejected("The download is larger than any agent package should be")
                            output.write(buffer, 0, read)
                            checks.update(buffer, read)
                            onProgress(done, total)
                        }
                    }
                    output.fd.sync()
                }
            } finally {
                record(done - have)
            }
            if (total >= 0 && done != total) throw IOException("The download stopped early")
            expected.bytes?.let { if (done != it) throw PackageRejected("The download has the wrong size") }
            checks.problem()?.let { throw PackageRejected(it) }
        }
    }

    /** True when the server continues at [have]; false when it sends the whole file again. */
    private fun continues(response: Response, have: Long, part: File): Boolean {
        if (response.code != HTTP_PARTIAL) return false
        val start = response.header("Content-Range")?.removePrefix("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()
        if (start != have) {
            Files.delete(part.toPath())
            throw IOException("The download server continued the download at the wrong place")
        }
        return true
    }

    private fun contentRangeTotal(response: Response): Long? =
        response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()

    private fun matches(file: File, expected: Expected): Boolean {
        if (expected.bytes != null && file.length() != expected.bytes) return false
        if (file.length() > expected.maxBytes) return false
        return Checks(expected).apply { feed(file) }.problem() == null
    }

    /** The digests and the signature over the bytes, as they arrive. */
    private class Checks(private val expected: Expected) {
        private val sha256 = MessageDigest.getInstance("SHA-256")
        private val sha512 = MessageDigest.getInstance("SHA-512")
        private val signature = expected.signature?.let { (key, sig) -> Ed25519Stream(key, sig) }

        fun update(bytes: ByteArray, length: Int) {
            sha256.update(bytes, 0, length)
            if (expected.sha512 != null) sha512.update(bytes, 0, length)
            signature?.update(bytes, 0, length)
        }

        fun feed(file: File) {
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    update(buffer, read)
                }
            }
        }

        /** Why the bytes are not the published package, or null when they are. */
        fun problem(): String? = when {
            expected.sha256 != null && !hex(sha256.digest()).equals(expected.sha256, ignoreCase = true) -> "The download did not match its published SHA-256"
            expected.sha512 != null && !hex(sha512.digest()).equals(expected.sha512, ignoreCase = true) -> "The download did not match its published SHA-512"
            signature != null && !signature.verify() -> "The download's signature is not Open VSX's"
            else -> null
        }
    }

    companion object {
        /** Agent packages in the data budget's monthly usage. */
        const val DATA_KIND = "agents"
        private const val BUFFER = 256 * 1024
        private const val PART = ".part"
        private const val HTTP_PARTIAL = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        /** SHA-256 of a file on disk, streamed. */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return hex(digest.digest())
        }
    }
}
