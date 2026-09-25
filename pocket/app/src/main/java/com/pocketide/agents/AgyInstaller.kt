package com.pocketide.agents

import com.pocketide.core.AppJson
import com.pocketide.core.await
import com.pocketide.linux.FileModes
import com.pocketide.linux.GuestRoot
import com.pocketide.linux.TarGzExtractor
import com.pocketide.linux.Trees
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** What Google's agy update manifest says: the newest version and its archive's digest. */
@Serializable
internal data class AgyManifest(
    val version: String,
    val url: String,
    val sha512: String? = null,
    val sha256: String? = null,
)

/**
 * Google's `agy`, the program behind the Antigravity room. Its own update manifest names the
 * newest version and the SHA-512 of its archive (there is no signature, so the digest and the
 * doctor are the checks). The archive holds one file, which becomes `.gemini/bin/agy` in the
 * Antigravity room's home. The version it replaces stays beside it as `agy.previous` until the
 * new one has started, and goes back in place when it does not.
 */
internal class AgyInstaller(
    private val env: AgentsEnv,
    private val download: VerifiedDownload,
    private val manifestUrl: HttpUrl = MANIFEST,
    private val archiveHost: String = ARCHIVE_HOST,
) {

    suspend fun latest(): AgyManifest {
        val text = env.http.newCall(Request.Builder().url(manifestUrl).build()).await().use { response ->
            if (response.code == 429) throw IOException("Google's agy server is busy. PocketIDE tries again later.")
            if (!response.isSuccessful) throw IOException("Google's agy server answered ${response.code}")
            val source = response.body.source()
            if (source.request(MAX_MANIFEST_BYTES + 1L)) throw IOException("agy's update manifest was larger than expected")
            source.readUtf8()
        }
        val manifest = try {
            AppJson.decodeFromString<AgyManifest>(text)
        } catch (unreadable: IllegalArgumentException) {
            throw IOException("agy's update manifest could not be read")
        }
        if (!SAFE_VERSION.matches(manifest.version) || SemVer.parse(manifest.version) == null) {
            throw PackageRejected("agy's update manifest names no usable version")
        }
        archiveUrl(manifest)
        val sha512 = manifest.sha512?.lowercase()?.takeIf { SHA512_HEX.matches(it) }
        val sha256 = manifest.sha256?.lowercase()?.takeIf { SHA256_HEX.matches(it) }
        if (sha512 == null && sha256 == null) throw PackageRejected("agy's update manifest gives no checksum")
        return manifest.copy(sha512 = sha512, sha256 = sha256)
    }

    /** Downloads the archive into [folder] as `agy-<version>.tar.gz`, kept only when its digest matches. */
    suspend fun fetch(manifest: AgyManifest, folder: File): File =
        download.fetch(
            archiveUrl(manifest),
            archiveFile(folder, manifest.version),
            Expected(sha256 = manifest.sha256, sha512 = manifest.sha512, maxBytes = MAX_ARCHIVE_BYTES),
        )

    /** Puts the program from [archive] in place; the one it replaces is kept as `agy.previous`. */
    suspend fun install(agentId: String, archive: File) = withContext(Dispatchers.IO) {
        val bin = binFolder(agentId)
        val staging = bin.resolve(STAGING)
        Trees.delete(staging)
        try {
            TarGzExtractor().extract(archive, staging.toFile())
            val program = staging.resolve(PROGRAM)
            if (!Files.isRegularFile(program, LinkOption.NOFOLLOW_LINKS)) throw PackageRejected("The agy archive did not hold the agy program")
            val current = bin.resolve(AGY)
            val previous = bin.resolve(PREVIOUS)
            Trees.delete(previous)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) Files.move(current, previous, StandardCopyOption.ATOMIC_MOVE)
            Files.move(program, current, StandardCopyOption.ATOMIC_MOVE)
            FileModes.set(current, FileModes.EXECUTABLE)
        } finally {
            Trees.delete(staging)
        }
    }

    /** The new program passed its test: the one it replaced is no longer needed. */
    suspend fun keep(agentId: String) = withContext(Dispatchers.IO) {
        Trees.delete(binFolder(agentId).resolve(PREVIOUS))
    }

    /** The new program failed its test: the one it replaced goes back, or none when there was none. */
    suspend fun rollBack(agentId: String) = withContext(Dispatchers.IO) {
        val bin = binFolder(agentId)
        val previous = bin.resolve(PREVIOUS)
        val current = bin.resolve(AGY)
        if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
            Files.move(previous, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            Trees.delete(current)
        }
    }

    /** The folder inside the room's home, resolved the way the room sees it: a link there cannot lead out. */
    private fun binFolder(agentId: String): Path {
        val home = env.dirs.roomHome(agentId)
        Files.createDirectories(home.toPath())
        return GuestRoot(home).directory("/${RoomPaths.AGY_BIN_IN_HOME}", create = true)
            ?: throw IOException("The Antigravity room's .gemini/bin is not a folder")
    }

    private fun archiveUrl(manifest: AgyManifest): HttpUrl {
        val url = manifest.url.toHttpUrlOrNull()
        val trusted = url != null && url.isHttps && url.host == archiveHost && url.encodedPath.startsWith(ARCHIVE_PATH)
        if (!trusted) throw PackageRejected("agy's update manifest points somewhere other than Google's download server")
        return url
    }

    companion object {
        val MANIFEST = "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json".toHttpUrl()
        const val ARCHIVE_HOST = "storage.googleapis.com"
        private const val ARCHIVE_PATH = "/antigravity-public/"
        private const val PROGRAM = "antigravity"
        private const val AGY = "agy"
        private const val PREVIOUS = "agy.previous"
        private const val STAGING = ".agy-new"
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        /** agy's archive is about 57 MB; its program about 208 MB. */
        private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        private val SAFE_VERSION = Regex("[0-9][0-9A-Za-z.+-]{0,63}")
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
        private val SHA512_HEX = Regex("[0-9a-f]{128}")

        fun archiveFile(folder: File, version: String) = File(folder, "agy-$version.tar.gz")
    }
}
