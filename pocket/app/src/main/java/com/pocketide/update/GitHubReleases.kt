package com.pocketide.update

import com.pocketide.agents.SemVer
import com.pocketide.core.AppJson
import com.pocketide.core.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
internal data class GitHubAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String,
    /** "sha256:<hex>" on assets GitHub has hashed. */
    val digest: String? = null,
)

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

/**
 * PocketIDE's own releases on GitHub (public, so no token is sent). Only full releases whose tag
 * is `pocketide-v<version>` count, and only an APK served from that repository's own release
 * downloads. Whether the APK may be installed is decided later, from the file itself.
 */
internal class GitHubReleases(
    private val client: OkHttpClient,
    private val repository: String,
    private val tagPrefix: String,
    private val api: HttpUrl = API,
    private val downloads: HttpUrl = DOWNLOADS,
) {

    /** The newest release above [current], or null when this is the newest. */
    suspend fun newest(current: SemVer): AppRelease? {
        val releases = list()
        return releases.asSequence()
            .filter { !it.draft && !it.prerelease && it.tag.startsWith(tagPrefix) }
            .mapNotNull { release -> SemVer.parse(release.tag.removePrefix(tagPrefix))?.takeIf { !it.isPreRelease }?.let { it to release } }
            .filter { (version, _) -> version > current }
            .sortedByDescending { (version, _) -> version }
            .firstNotNullOfOrNull { (version, release) -> appRelease(version, release) }
    }

    private suspend fun list(): List<GitHubRelease> {
        val url = api.newBuilder()
            .addPathSegment("repos")
            .addPathSegments(repository)
            .addPathSegment("releases")
            .addQueryParameter("per_page", PAGE.toString())
            .build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .build()
        val text = client.newCall(request).await().use { response ->
            when {
                response.code == 404 -> throw IOException("PocketIDE's releases were not found on GitHub.")
                response.code == 403 || response.code == 429 -> throw IOException("GitHub is limiting requests. Try again in an hour.")
                !response.isSuccessful -> throw IOException("GitHub answered ${response.code}. Try again later.")
            }
            val source = response.body.source()
            if (source.request(MAX_ANSWER_BYTES + 1L)) throw IOException("GitHub's answer was larger than expected.")
            source.readUtf8()
        }
        return try {
            AppJson.decodeFromString<List<GitHubRelease>>(text)
        } catch (unreadable: IllegalArgumentException) {
            throw IOException("GitHub's list of releases could not be read.")
        }
    }

    /** The release with its one APK, or null when it has none from this repository's downloads. */
    private fun appRelease(version: SemVer, release: GitHubRelease): AppRelease? {
        val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) && trusted(it.downloadUrl, release.tag) }
        val apk = apks.singleOrNull() ?: apks.firstOrNull { it.name.contains("pocketide", ignoreCase = true) } ?: return null
        return AppRelease(
            version = version.toString(),
            tag = release.tag,
            apkUrl = apk.downloadUrl,
            apkBytes = apk.size,
            notes = release.body.orEmpty().take(MAX_NOTES),
            publishedAt = release.publishedAt.orEmpty(),
            sha256 = apk.digest?.takeIf { it.startsWith(SHA256_PREFIX) }?.removePrefix(SHA256_PREFIX)?.lowercase()?.takeIf { SHA256_HEX.matches(it) },
        )
    }

    private fun trusted(link: String, tag: String): Boolean {
        val url = link.toHttpUrlOrNull() ?: return false
        val prefix = "/$repository/releases/download/$tag/"
        return url.scheme == downloads.scheme && url.host == downloads.host && url.port == downloads.port &&
            url.encodedPath.startsWith(prefix, ignoreCase = true)
    }

    companion object {
        val API = "https://api.github.com/".toHttpUrl()
        val DOWNLOADS = "https://github.com/".toHttpUrl()
        private const val API_VERSION = "2022-11-28"
        private const val PAGE = 30
        private const val MAX_ANSWER_BYTES = 4 * 1024 * 1024
        private const val MAX_NOTES = 4000
        private const val SHA256_PREFIX = "sha256:"
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}
