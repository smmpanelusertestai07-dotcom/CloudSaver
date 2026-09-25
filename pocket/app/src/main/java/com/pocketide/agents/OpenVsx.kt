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

    /**
     * Versions published for [target], with their details (pre-release flag, engine range,
     * files), from the all-versions query, highest version number first. Reads page after page
     * until a page holds a version that is [enough], or the list ends, or [pages] were read.
     * Open VSX orders by number, not date, so a publisher whose pre-releases carry higher
     * numbers (Codex's 26.5MDD against 26.MDD) puts a whole year of them before its releases.
     */
    suspend fun allVersions(
        namespace: String,
        name: String,
        target: String,
        pages: Int = VERSION_PAGES,
        enough: (ExtensionVersion) -> Boolean = { false },
    ): List<ExtensionVersion> {
        val found = mutableListOf<ExtensionVersion>()
        var offset = 0
        repeat(pages) {
            val url = api("-", "query").newBuilder()
                .addQueryParameter("namespaceName", namespace)
                .addQueryParameter("extensionName", name)
                .addQueryParameter("targetPlatform", target)
                .addQueryParameter("includeAllVersions", "true")
                .addQueryParameter("size", QUERY_PAGE.toString())
                .addQueryParameter("offset", offset.toString())
                .build()
            val page = json<QueryPage>(url) ?: return found
            found += page.extensions
            offset += page.extensions.size
            if (page.extensions.isEmpty() || offset >= page.totalSize || page.extensions.any(enough)) return found
        }
        return found
    }

    /** The published SHA-256 of a file (the `.sha256` link: 64 hex characters, perhaps followed by a name). */
    suspend fun sha256(link: String?): String {
        val url = fileUrl(link) ?: throw PackageRejected("Open VSX publishes no checksum for this package")
        val digest = text(url, MAX_CHECKSUM_BYTES).trim().split(WHITESPACE).first().lowercase()
        if (!SHA256_HEX.matches(digest)) throw PackageRejected("Open VSX's checksum for this package could not be read")
        return digest
    }

    /** Open VSX's key and its signature over the whole package, or null when the version is not signed. */
    suspend fun signature(files: Map<String, String>): Pair<ByteArray, ByteArray>? {
        val signatureLink = files["signature"]
        val keyLink = files["publicKey"]
        if (signatureLink == null && keyLink == null) return null
        val signatureUrl = fileUrl(signatureLink) ?: throw PackageRejected("The package's signature is not on Open VSX")
        val keyUrl = fileUrl(keyLink) ?: throw PackageRejected("Open VSX's signing key is not on Open VSX")
        val key = OpenVsxSignature.publicKeyFrom(text(keyUrl, MAX_KEY_BYTES))
        val signature = OpenVsxSignature.signatureFrom(bytes(signatureUrl, MAX_SIGZIP_BYTES))
        return key to signature
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
        private const val QUERY_PAGE = 50
        /** 1,000 versions: years of Codex's pre-releases. */
        private const val VERSION_PAGES = 20
        private const val MAX_CHECKSUM_BYTES = 1024
        private const val MAX_KEY_BYTES = 16 * 1024
        private const val MAX_SIGZIP_BYTES = 64 * 1024
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
        private val WHITESPACE = Regex("\\s+")
    }
}
