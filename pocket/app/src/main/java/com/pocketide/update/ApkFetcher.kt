package com.pocketide.update

import com.pocketide.core.await
import com.pocketide.model.Decision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** The data rules say not now (Wi-Fi only, or today's mobile data is used up). */
internal class UpdateWaits(reason: String) : IOException(reason)

/**
 * Streams a release's APK to the phone with progress. The file appears under its final name
 * only when it has the size GitHub listed and, when GitHub lists one, the same SHA-256; the
 * signer check comes after, from the file itself.
 */
internal class ApkFetcher(
    private val client: OkHttpClient,
    private val allow: (bytes: Long) -> Decision,
    private val record: (bytes: Long) -> Unit,
) {

    suspend fun fetch(release: AppRelease, target: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val url = release.apkUrl.toHttpUrlOrNull() ?: throw IOException("The update's download link is not valid.")
        val decision = allow(release.apkBytes)
        if (!decision.allowed) throw UpdateWaits(decision.reason ?: "The update waits for Wi-Fi.")
        target.parentFile?.let { Files.createDirectories(it.toPath()) }
        val part = File(target.parentFile, target.name + ".part")
        try {
            receive(url.toString(), part, release, onProgress)
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            target
        } finally {
            Files.deleteIfExists(part.toPath())
        }
    }

    private suspend fun receive(url: String, part: File, release: AppRelease, onProgress: (Float) -> Unit) {
        client.newCall(Request.Builder().url(url).build()).await().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code} for the update. Try again later.")
            val expected = release.apkBytes.takeIf { it > 0 }
            val total = response.body.contentLength().takeIf { it >= 0 } ?: expected ?: -1
            if (total > MAX_APK_BYTES || (expected != null && total >= 0 && total != expected)) {
                throw IOException("The update's file is not the size GitHub lists.")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            try {
                FileOutputStream(part).use { output ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            done += read
                            if (done > MAX_APK_BYTES) throw IOException("The update's file is larger than any PocketIDE app.")
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                    output.fd.sync()
                }
            } finally {
                record(done)
            }
            if ((total >= 0 && done != total) || (expected != null && done != expected)) throw IOException("The update's download stopped early. Try again.")
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            release.sha256?.let { if (!it.equals(sha256, ignoreCase = true)) throw IOException("The update's file does not match the checksum GitHub lists.") }
        }
    }

    private companion object {
        const val BUFFER = 128 * 1024
        const val MAX_APK_BYTES = 512L * 1024 * 1024
    }
}
