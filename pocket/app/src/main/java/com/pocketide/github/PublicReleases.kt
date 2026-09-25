package com.pocketide.github

import com.pocketide.core.AppJson
import com.pocketide.core.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * GitHub has no such repository: the project moved to another repository or account, so asking
 * again will not find it. Only an APK installed by hand learns the new place.
 */
class ReleasesMovedException : IOException("PocketIDE's release page moved. Install the newest PocketIDE APK once by hand.")

/**
 * A file attached to a release: its name, size and public download address, and the SHA-256
 * GitHub computed when it was uploaded (null for assets older than GitHub's digests).
 */
data class ReleaseAsset(val name: String, val bytes: Long, val downloadUrl: String, val sha256: String? = null)

/** One published release of a public repository. */
data class PublicRelease(
    val tag: String,
    val notes: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

/**
 * The published releases of a public repository, newest first, read from GitHub's REST API
 * without a token: nothing of the owner's is sent, and only GET is used. Every page is read (up
 * to [maxPages] of 100), so a repository that also publishes other apps' releases cannot push
 * the one asked for out of view, as it would out of the ten-entry releases feed.
 */
class PublicReleases internal constructor(
    private val client: OkHttpClient,
    private val base: HttpUrl,
    private val maxPages: Int = MAX_PAGES,
) {

    suspend fun list(repository: String): List<PublicRelease> {
        var next: HttpUrl? = releases(repository)
            .addQueryParameter("per_page", PER_PAGE.toString())
            .build()
        val releases = mutableListOf<PublicRelease>()
        var pages = 0
        while (next != null && pages < maxPages) {
            val (text, headers) = get(next)
            releases += parse(text)
            next = nextPage(headers)
            pages++
        }
        return releases
    }

    /** The repository's latest release: the newest that is neither a draft nor a pre-release. */
    suspend fun latest(repository: String): PublicRelease {
        val (text, _) = get(releases(repository).addPathSegment("latest").build())
        return parse("[$text]").singleOrNull() ?: throw IOException("GitHub's latest release could not be read.")
    }

    private fun releases(repository: String): HttpUrl.Builder {
        val (owner, name) = repository.split('/').takeIf { it.size == 2 && it.none(String::isBlank) }
            ?: throw IllegalArgumentException("A repository is owner/name.")
        return base.newBuilder().addPathSegment("repos").addPathSegment(owner).addPathSegment(name).addPathSegment("releases")
    }

    private suspend fun get(url: HttpUrl): Pair<String, Headers> {
        val request = Request.Builder()
            .url(url)
            // No X-GitHub-Api-Version: the fields read here are the same in every version, and
            // naming one would stop the updater, the way to a build that knows a newer one, the
            // day GitHub retires it.
            .header("Accept", RestClient.ACCEPT_JSON)
            .build()
        return client.newCall(request).await().use { response ->
            when {
                response.code == 404 -> throw ReleasesMovedException()
                response.code == 403 || response.code == 429 -> throw IOException("GitHub is limiting requests. Try again in an hour.")
                !response.isSuccessful -> throw IOException("GitHub answered ${response.code}. Try again later.")
            }
            val source = response.body.source()
            if (source.request(MAX_PAGE_BYTES + 1)) throw IOException("GitHub's answer was larger than expected.")
            source.readUtf8() to response.headers
        }
    }

    /** Only a next page on the same API host is followed. */
    private fun nextPage(headers: Headers): HttpUrl? {
        val link = headers["Link"] ?: return null
        val url = NEXT_LINK.find(link)?.groupValues?.get(1)?.toHttpUrlOrNull() ?: return null
        return url.takeIf { it.scheme == base.scheme && it.host == base.host && it.port == base.port }
    }

    internal companion object {
        const val PER_PAGE = 100
        const val MAX_PAGES = 5
        private const val MAX_PAGE_BYTES = 8L * 1024 * 1024
        private val NEXT_LINK = Regex("<([^>]+)>\\s*;\\s*rel=\"next\"")

        private val SHA256_DIGEST = Regex("sha256:([0-9a-fA-F]{64})")

        /** GitHub writes an asset's digest as "sha256:<hex>"; anything else is no digest. */
        private fun sha256(digest: String?): String? = digest?.let { SHA256_DIGEST.matchEntire(it) }?.groupValues?.get(1)?.lowercase()

        /** Published releases only: a draft is never listed as one. */
        fun parse(text: String): List<PublicRelease> {
            val wire = try {
                AppJson.decodeFromString(ListSerializer(ReleaseJson.serializer()), text)
            } catch (e: SerializationException) {
                throw IOException("GitHub's list of releases could not be read.", e)
            } catch (e: IllegalArgumentException) {
                throw IOException("GitHub's list of releases could not be read.", e)
            }
            return wire.filterNot { it.draft }.map { release ->
                PublicRelease(
                    tag = release.tagName,
                    notes = release.body.orEmpty(),
                    publishedAt = release.publishedAt.orEmpty(),
                    prerelease = release.prerelease,
                    assets = release.assets.map { ReleaseAsset(it.name, it.size, it.browserDownloadUrl, sha256(it.digest)) },
                )
            }
        }
    }
}

@Serializable
internal data class ReleaseJson(
    @SerialName("tag_name") val tagName: String,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ReleaseAssetJson> = emptyList(),
)

@Serializable
internal data class ReleaseAssetJson(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val digest: String? = null,
)
