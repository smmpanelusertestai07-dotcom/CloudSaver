package com.pocketide.update

import com.pocketide.agents.SemVer
import com.pocketide.core.await
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder

/** One release in the repository's public releases feed. */
internal data class FeedEntry(val tag: String, val updated: String, val notes: String)

/**
 * PocketIDE's own releases, read from the repository's public pages on github.com: the
 * releases feed names them, and each release's SHA256SUMS (written by the release job) names
 * its APK and that APK's SHA-256. Nothing here uses the REST API (only the github package
 * does) and no token is sent. Only tags `pocketide-v<version>` count; whether the APK may be
 * installed is decided later, from the file itself.
 */
internal class GitHubReleases(
    private val client: OkHttpClient,
    private val repository: String,
    private val tagPrefix: String,
    private val web: HttpUrl = WEB,
) {

    /** The newest release above [current] that publishes its checksums, or null when this is the newest. */
    suspend fun newest(current: SemVer): AppRelease? {
        val candidates = feed(text(repositoryUrl("releases.atom"), MAX_FEED_BYTES))
            .mapNotNull { entry -> version(entry.tag)?.takeIf { it > current }?.let { it to entry } }
            .sortedByDescending { (version, _) -> version }
        for ((version, entry) in candidates) {
            release(version, entry)?.let { return it }
        }
        return null
    }

    private fun version(tag: String): SemVer? =
        tag.takeIf { it.startsWith(tagPrefix) }?.removePrefix(tagPrefix)?.let(SemVer::parse)?.takeIf { !it.isPreRelease }

    /** The release with its one APK and that APK's checksum, or null when it publishes no checksums. */
    private suspend fun release(version: SemVer, entry: FeedEntry): AppRelease? {
        val sums = try {
            text(downloadUrl(entry.tag, SUMS), MAX_SUMS_BYTES)
        } catch (missing: NoSuchRelease) {
            return null
        }
        val (sha256, name) = sums.lineSequence()
            .mapNotNull { line -> SUM_LINE.matchEntire(line.trim())?.destructured?.let { (hex, file) -> hex.lowercase() to file } }
            .singleOrNull { (_, file) -> file.endsWith(".apk") && SAFE_NAME.matches(file) }
            ?: return null
        val apk = downloadUrl(entry.tag, name)
        return AppRelease(
            version = version.toString(),
            tag = entry.tag,
            apkUrl = apk.toString(),
            apkBytes = size(apk),
            notes = entry.notes,
            publishedAt = entry.updated,
            sha256 = sha256,
        )
    }

    /** The APK's size from a HEAD request; 0 when the server does not say. */
    private suspend fun size(url: HttpUrl): Long =
        client.newCall(Request.Builder().url(url).head().build()).await().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code} for the update. Try again later.")
            response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 } ?: 0
        }

    private suspend fun text(url: HttpUrl, limit: Long): String =
        client.newCall(Request.Builder().url(url).build()).await().use { response ->
            when {
                response.code == 404 -> throw NoSuchRelease()
                response.code == 403 || response.code == 429 -> throw IOException("GitHub is limiting requests. Try again in an hour.")
                !response.isSuccessful -> throw IOException("GitHub answered ${response.code}. Try again later.")
            }
            val source = response.body.source()
            if (source.request(limit + 1)) throw IOException("GitHub's answer was larger than expected.")
            source.readUtf8()
        }

    private fun repositoryUrl(vararg segments: String): HttpUrl =
        web.newBuilder().addPathSegments(repository).apply { segments.forEach(::addPathSegment) }.build()

    private fun downloadUrl(tag: String, file: String) = repositoryUrl("releases", "download", tag, file)

    /** GitHub answered 404: no such page (a missing repository, or a release without its checksums). */
    private class NoSuchRelease : IOException("PocketIDE's releases were not found on GitHub.")

    companion object {
        val WEB = "https://github.com/".toHttpUrl()
        private const val SUMS = "SHA256SUMS"
        private const val MAX_FEED_BYTES = 2L * 1024 * 1024
        private const val MAX_SUMS_BYTES = 64L * 1024
        private const val MAX_NOTES = 4000
        private val SUM_LINE = Regex("([0-9a-fA-F]{64})\\s+\\*?(\\S+)")
        private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
        private val ENTRY = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
        private val TAG_LINK = Regex("<link[^>]*href=\"[^\"]*/releases/tag/([^\"]+)\"")
        private val UPDATED = Regex("<updated>([^<]+)</updated>")
        private val CONTENT = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
        private val MARKUP = Regex("<[^>]*>")
        private val SPACES = Regex("\\s+")

        /** The feed's entries. It is GitHub's fixed Atom format, read for three fields only. */
        fun feed(xml: String): List<FeedEntry> = ENTRY.findAll(xml).mapNotNull { match ->
            val entry = match.groupValues[1]
            val tag = TAG_LINK.find(entry)?.groupValues?.get(1)?.let { URLDecoder.decode(unescape(it), Charsets.UTF_8.name()) }
                ?: return@mapNotNull null
            FeedEntry(
                tag = tag,
                updated = UPDATED.find(entry)?.groupValues?.get(1)?.trim().orEmpty(),
                notes = CONTENT.find(entry)?.groupValues?.get(1)?.let(::plainText).orEmpty(),
            )
        }.toList()

        /** The release notes as plain text: the feed carries them as escaped HTML. */
        private fun plainText(escaped: String): String =
            unescape(unescape(escaped).replace(MARKUP, " ")).replace(SPACES, " ").trim().take(MAX_NOTES)

        private fun unescape(text: String): String = text
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
    }
}
