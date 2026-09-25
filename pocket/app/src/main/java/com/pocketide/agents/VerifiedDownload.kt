package com.pocketide.agents

import com.pocketide.core.await
import com.pocketide.model.Decision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** A package that did not match what its publisher published. The bad copy is already deleted. */
internal class PackageRejected(message: String) : IOException(message)

/** A download the data rules do not allow now (Wi-Fi only, daily limit). Tried again later. */
internal class DownloadWaits(reason: String) : IOException(reason)

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
 * at [target] only after every check passed; anything else is deleted.
 *
 * [allow] applies the data rules (big downloads wait for Wi-Fi by default) once the size is
 * known; [record] counts what arrived.
 */
internal class VerifiedDownload(
    private val client: OkHttpClient,
    private val allow: (bytes: Long) -> Decision,
    private val record: (bytes: Long) -> Unit,
) {

    suspend fun fetch(url: HttpUrl, target: File, expected: Expected, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): File =
        withContext(Dispatchers.IO) {
            target.parentFile?.let { Files.createDirectories(it.toPath()) }
            val part = File(target.parentFile, target.name + ".part")
            try {
                receive(url, part, expected, onProgress)
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                target
            } finally {
                Files.deleteIfExists(part.toPath())
            }
        }

    private suspend fun receive(url: HttpUrl, part: File, expected: Expected, onProgress: (Long, Long) -> Unit) {
        client.newCall(Request.Builder().url(url).build()).await().use { response ->
            if (!response.isSuccessful) throw IOException("The download server answered ${response.code}")
            val body = response.body
            val total = body.contentLength().takeIf { it >= 0 } ?: expected.bytes ?: -1
            if (total > expected.maxBytes) throw PackageRejected("The download is larger than any agent package should be")
            if (expected.bytes != null && total >= 0 && total != expected.bytes) throw PackageRejected("The download has the wrong size")
            val decision = allow(total.coerceAtLeast(0))
            if (!decision.allowed) throw DownloadWaits(decision.reason ?: "Waiting for Wi-Fi")

            val sha256 = MessageDigest.getInstance("SHA-256")
            val sha512 = MessageDigest.getInstance("SHA-512")
            val signature = expected.signature?.let { (key, sig) -> Ed25519Stream(key, sig) }
            var done = 0L
            try {
                FileOutputStream(part).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            done += read
                            if (done > expected.maxBytes) throw PackageRejected("The download is larger than any agent package should be")
                            output.write(buffer, 0, read)
                            sha256.update(buffer, 0, read)
                            if (expected.sha512 != null) sha512.update(buffer, 0, read)
                            signature?.update(buffer, 0, read)
                            onProgress(done, total)
                        }
                    }
                    output.fd.sync()
                }
            } finally {
                record(done)
            }
            if (total >= 0 && done != total) throw IOException("The download stopped early")
            expected.bytes?.let { if (done != it) throw PackageRejected("The download has the wrong size") }
            expected.sha256?.let { if (!hex(sha256.digest()).equals(it, ignoreCase = true)) throw PackageRejected("The download did not match its published SHA-256") }
            expected.sha512?.let { if (!hex(sha512.digest()).equals(it, ignoreCase = true)) throw PackageRejected("The download did not match its published SHA-512") }
            if (signature != null && !signature.verify()) throw PackageRejected("The download's signature is not Open VSX's")
        }
    }

    companion object {
        private const val BUFFER = 256 * 1024

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
