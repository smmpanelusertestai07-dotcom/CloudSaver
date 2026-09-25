package com.pocketide.update

import com.pocketide.agents.SemVer
import com.pocketide.core.await
import com.pocketide.github.PublicRelease
import com.pocketide.github.ReleaseAsset
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * PocketIDE's own releases in a repository it shares with other apps: every published release is
 * listed (through the github package's token-free client), only tags `pocketide-v<version>`
 * count, and each release's SHA256SUMS (written by the release job) names its one APK and that
 * APK's SHA-256. Whether the APK may be installed is decided later, from the file itself.
 */
internal class GitHubReleases(
    private val client: OkHttpClient,
    private val list: suspend () -> List<PublicRelease>,
    private val tagPrefix: String,
) {

    /** The newest release above [current] that publishes its checksums, or null when this is the newest. */
    suspend fun newest(current: SemVer): AppRelease? {
        val candidates = list()
            .filterNot { it.prerelease }
            .mapNotNull { release -> version(release.tag)?.takeIf { it > current }?.let { it to release } }
            .sortedByDescending { (version, _) -> version }
        for ((version, release) in candidates) {
            appRelease(version, release)?.let { return it }
        }
        return null
    }

    private fun version(tag: String): SemVer? =
        tag.takeIf { it.startsWith(tagPrefix) }?.removePrefix(tagPrefix)?.let(SemVer::parse)?.takeIf { !it.isPreRelease }

    /** The release with its one APK and that APK's checksum, or null when it publishes no usable checksums. */
    private suspend fun appRelease(version: SemVer, release: PublicRelease): AppRelease? {
        val sums = release.assets.firstOrNull { it.name == SUMS } ?: return null
        val (sha256, name) = text(sums).lineSequence()
            .mapNotNull { line -> SUM_LINE.matchEntire(line.trim())?.destructured?.let { (hex, file) -> hex.lowercase() to file } }
            .singleOrNull { (_, file) -> file.endsWith(".apk") && SAFE_NAME.matches(file) }
            ?: return null
        val apk = release.assets.firstOrNull { it.name == name } ?: return null
        return AppRelease(
            version = version.toString(),
            tag = release.tag,
            apkUrl = apk.downloadUrl,
            apkBytes = apk.bytes,
            notes = plainText(release.notes),
            publishedAt = release.publishedAt,
            sha256 = sha256,
        )
    }

    private suspend fun text(asset: ReleaseAsset): String {
        // What the checksums say is only trusted as far as the APK then matches them and is signed
        // with PocketIDE's own key.
        val url = asset.downloadUrl.toHttpUrlOrNull() ?: throw IOException("The release's checksums link is not valid.")
        return client.newCall(Request.Builder().url(url).build()).await().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code} for the checksums. Try again later.")
            val source = response.body.source()
            if (source.request(MAX_SUMS_BYTES + 1)) throw IOException("The release's checksums are larger than expected.")
            source.readUtf8()
        }
    }

    companion object {
        private const val SUMS = "SHA256SUMS"
        private const val MAX_SUMS_BYTES = 64L * 1024
        private const val MAX_NOTES = 4000

        private val SUM_LINE = Regex("([0-9a-fA-F]{64})\\s+\\*?(\\S+)")
        private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
        private val MARKDOWN = Regex("[*_`#>]+")
        private val SPACES = Regex("\\s+")

        /** The release notes as one plain paragraph: they are written in Markdown. */
        fun plainText(markdown: String): String = markdown.replace(MARKDOWN, " ").replace(SPACES, " ").trim().take(MAX_NOTES)
    }
}
