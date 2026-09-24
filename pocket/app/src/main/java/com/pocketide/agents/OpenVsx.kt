package com.pocketide.agents

import com.pocketide.core.AppJson
import com.pocketide.core.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException

/** Open VSX asked for fewer requests (HTTP 429). */
internal class RegistryBusy : IOException("Open VSX is busy. PocketIDE tries again later.")

/** One row of `GET /api/-/search`. Its `files.download` may be any platform's: never install from it. */
@Serializable
internal data class SearchEntry(
    val namespace: String,
    val name: String,
    val version: String = "",
    val verified: Boolean = false,
    val downloadCount: Long = 0,
    val displayName: String? = null,
    val description: String? = null,
    val deprecated: Boolean = false,
) {
    val id: String get() = "$namespace.$name".lowercase()
}

@Serializable
internal data class SearchPage(val offset: Int = 0, val totalSize: Int = 0, val extensions: List<SearchEntry> = emptyList())

/** One version of an extension for one platform: `GET /api/{ns}/{name}[/{target}]/{version|latest}`. */
@Serializable
internal data class ExtensionVersion(
    val namespace: String,
    val name: String,
    val version: String,
    val targetPlatform: String = UNIVERSAL,
    val preRelease: Boolean = false,
    val timestamp: String? = null,
    val verified: Boolean = false,
    val downloadable: Boolean = true,
    val deprecated: Boolean = false,
    val displayName: String? = null,
    val namespaceDisplayName: String? = null,
    val description: String? = null,
    val downloadCount: Long = 0,
    val categories: List<String> = emptyList(),
    val license: String? = null,
    val repository: String? = null,
    val averageRating: Double? = null,
    val reviewCount: Long = 0,
    val reviewStatus: String? = null,
    val engines: JsonObject? = null,
    /** Target platform → .vsix URL, across every platform this version was published for. */
    val downloads: Map<String, String> = emptyMap(),
    val files: Map<String, String> = emptyMap(),
) {
    val id: String get() = "$namespace.$name".lowercase()
    val vscodeEngine: String? get() = engines?.get("vscode")?.jsonPrimitive?.contentOrNull
    val publishedAt: Long? get() = timestamp?.let(::epochMillis)

    companion object {
        const val UNIVERSAL = "universal"
    }
}

@Serializable
internal data class QueryPage(val totalSize: Int = 0, val extensions: List<ExtensionVersion> = emptyList())

@Serializable
internal data class VersionsPage(val totalSize: Int = 0, val versions: Map<String, String> = emptyMap())

internal fun epochMillis(iso: String): Long? = try {
    Instant.parse(iso).toEpochMilli()
} catch (unreadable: DateTimeParseException) {
    null
}

/**
 * The Open VSX REST API (live OpenAPI spec at open-vsx.org/v3/api-docs). Every URL the app
 * follows, including the file links inside answers, must be on the registry's own host.
 */
internal class OpenVsx(
    private val client: OkHttpClient,
    val base: HttpUrl = DEFAULT_BASE,
    private val onBytes: (Long) -> Unit = {},
) {

    suspend fun search(category: String, offset: Int, size: Int): SearchPage = json(
        api("-", "search").newBuilder()
            .addQueryParameter("category", category)
            .addQueryParameter("sortBy", "downloadCount")
            .addQueryParameter("sortOrder", "desc")
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("size", size.toString())
            .build(),
    ) ?: SearchPage()

    /** The newest version for [target], or null when the extension has no build for it. */
    suspend fun latest(namespace: String, name: String, target: String): ExtensionVersion? =
        json(api(namespace, name, target, "latest"))

    /** Every platform's newest version (its `downloads` map lists the platforms). */
    suspend fun extension(namespace: String, name: String): ExtensionVersion? = json(api(namespace, name))

    suspend fun version(namespace: String, name: String, target: String, version: String): ExtensionVersion? =
        if (target == ExtensionVersion.UNIVERSAL) json(api(namespace, name, version)) else json(api(namespace, name, target, version))

    /** Version numbers published for [target], newest first as the registry lists them. */
    suspend fun versions(namespace: String, name: String, target: String, size: Int = VERSIONS_PAGE): List<String> {
        val url = (if (target == ExtensionVersion.UNIVERSAL) api(namespace, name, "versions") else api(namespace, name, target, "versions"))
            .newBuilder().addQueryParameter("size", size.toString()).build()
        return json<VersionsPage>(url)?.versions?.keys?.toList().orEmpty()
    }

    /**
     * When the extension first appeared: the oldest version's timestamp, from the all-versions
     * query (newest first, so the last entry is the oldest).
     */
    suspend fun firstPublished(namespace: String, name: String): Long? {
        fun query(offset: Int) = api("-", "query").newBuilder()
            .addQueryParameter("namespaceName", namespace)
            .addQueryParameter("extensionName", name)
            .addQueryParameter("includeAllVersions", "true")
            .addQueryParameter("size", "1")
            .addQueryParameter("offset", offset.toString())
            .build()
        val first = json<QueryPage>(query(0)) ?: return null
        val last = if (first.totalSize > 1) json<QueryPage>(query(first.totalSize - 1)) ?: return null else first
        return (first.extensions + last.extensions).mapNotNull { it.publishedAt }.minOrNull()
    }

    /** A file link from an answer, accepted only when it points into this registry's API. */
    fun fileUrl(link: String?): HttpUrl? {
        val url = link?.toHttpUrlOrNull() ?: return null
        val sameOrigin = url.scheme == base.scheme && url.host == base.host && url.port == base.port
        return url.takeIf { sameOrigin && it.encodedPath.startsWith(base.encodedPath.trimEnd('/') + "/api/") }
    }

    suspend fun packageJson(link: String?): JsonObject {
        val text = text(fileUrl(link) ?: throw IOException("The extension's details are not on Open VSX"), MAX_JSON_BYTES)
        return runCatching { AppJson.parseToJsonElement(text) as JsonObject }.getOrNull()
            ?: throw IOException("The extension's package.json could not be read")
    }

    suspend fun text(url: HttpUrl, limit: Int): String = String(bytes(url, limit), Charsets.UTF_8)

    suspend fun bytes(url: HttpUrl, limit: Int): ByteArray =
        get(url) { body -> body ?: throw IOException("Open VSX has no file at ${url.encodedPath}") }.also {
            if (it.size > limit) throw IOException("An answer from Open VSX was larger than expected")
        }

    private suspend inline fun <reified T> json(url: HttpUrl): T? {
        val bytes = get(url) { it } ?: return null
        return try {
            AppJson.decodeFromString<T>(String(bytes, Charsets.UTF_8))
        } catch (unreadable: IllegalArgumentException) {
            throw IOException("Open VSX sent an answer PocketIDE could not read")
        }
    }

    /** The body, or null for 404; [use] sees it and decides. */
    private suspend fun <R> get(url: HttpUrl, use: (ByteArray?) -> R): R {
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).await().use { response ->
            if (response.code == 404) return use(null)
            if (response.code == 429) throw RegistryBusy()
            if (!response.isSuccessful) throw IOException("Open VSX answered ${response.code}")
            val source = response.body.source()
            if (source.request(MAX_JSON_BYTES + 1L)) throw IOException("An answer from Open VSX was larger than expected")
            val bytes = source.readByteArray()
            onBytes(bytes.size.toLong())
            return use(bytes)
        }
    }

    private fun api(vararg segments: String): HttpUrl =
        base.newBuilder().addPathSegment("api").apply { segments.forEach(::addPathSegment) }.build()

    companion object {
        val DEFAULT_BASE = "https://open-vsx.org/".toHttpUrl()
        const val MAX_JSON_BYTES = 4 * 1024 * 1024
        private const val VERSIONS_PAGE = 100
    }
}
