package com.pocketide.github

import com.pocketide.core.AppJson
import com.pocketide.core.Clock
import com.pocketide.core.await
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource

/**
 * The HTTP verbs PocketIDE may use against GitHub. There is deliberately no DELETE and no PATCH:
 * the app never deletes a repository and never changes its visibility (the least-privilege gate
 * test checks this list).
 */
internal enum class Verb { GET, POST, PUT }

/** Where a request gets its user token, and what happens when GitHub refuses one. */
internal interface UserTokens {
    suspend fun token(): String

    /** GitHub answered 401 to [rejected]: renew once (unless another caller already did) and return the new token. */
    suspend fun renew(rejected: String): String

    /** GitHub refused a renewed token too: the access is gone. */
    suspend fun revoke(rejected: String)
}

internal class Reply(val status: Int, val headers: Headers, val bytes: ByteArray) {
    val text: String get() = bytes.toString(Charsets.UTF_8)
}

/**
 * Whether GitHub still takes the REST version this build names ([RestClient.API_VERSION]). GitHub
 * supports a version for at least 24 months after the next one ships, then refuses requests that
 * name it. Once it has, requests go without the header (GitHub's oldest supported version),
 * which every call here also understands, for the rest of the process.
 */
internal class ApiVersionChoice {
    @Volatile
    var retired: Boolean = false

    companion object {
        /** The one choice all of this process's clients share. */
        val process = ApiVersionChoice()

        /** GitHub's answer to a version it no longer supports: 400 or 410, naming the API version. */
        fun refuses(status: Int, body: String): Boolean = status in STATUSES && RETIRED.containsMatchIn(body)

        val STATUSES = setOf(400, 410)

        private val RETIRED = Regex("api[- _]?version", RegexOption.IGNORE_CASE)
    }
}

/**
 * GitHub's REST API with a user token: the documented headers on every call, one token renewal
 * on 401, bounded waits for rate limits and server errors, and Link-header pagination.
 * The token is only ever sent to [base]; page links and download links elsewhere are refused.
 */
internal class RestClient(
    private val client: OkHttpClient,
    val base: HttpUrl,
    private val tokens: UserTokens,
    private val clock: Clock,
    private val downloadClient: OkHttpClient = client,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val apiVersion: ApiVersionChoice = ApiVersionChoice.process,
) {

    /** `base` + path segments, each encoded on its own, so a name can never add a path. */
    fun url(vararg segments: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val builder = base.newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        query.forEach { (key, value) -> if (value != null) builder.addQueryParameter(key, value) }
        return builder.build()
    }

    fun isOurs(url: HttpUrl): Boolean = url.scheme == base.scheme && url.host == base.host && url.port == base.port

    /** Sends a call; 2xx returns the reply, 404 returns null when [missingIsNull], anything else throws. */
    suspend fun send(
        verb: Verb,
        url: HttpUrl,
        body: JsonElement? = null,
        accept: String = ACCEPT_JSON,
        missingIsNull: Boolean = false,
    ): Reply? = exchange(verb, url, body, accept, client) { response ->
        val bytes = response.body.bytes()
        when {
            response.isSuccessful -> Reply(response.code, response.headers, bytes)
            response.code == 404 && missingIsNull -> null
            else -> throw GitHubErrors.of(response.code, bytes.toString(Charsets.UTF_8))
        }
    }

    suspend fun get(url: HttpUrl, accept: String = ACCEPT_JSON): Reply =
        checkNotNull(send(Verb.GET, url, accept = accept))

    suspend fun getOrNull(url: HttpUrl): Reply? = send(Verb.GET, url, missingIsNull = true)

    /** Every page of a list, following `Link: rel="next"` up to [maxPages]. */
    suspend fun <T> pages(first: HttpUrl, maxPages: Int = MAX_PAGES, parse: (String) -> List<T>): List<T> {
        val items = mutableListOf<T>()
        var next: HttpUrl? = first.newBuilder().setQueryParameter("per_page", PER_PAGE.toString()).build()
        var count = 0
        while (next != null && count < maxPages) {
            val reply = get(next)
            items += parse(reply.text)
            next = nextPage(reply.headers)?.takeIf(::isOurs)
            count++
        }
        return items
    }

    /** Streams a GET whose answer may redirect to a pre-signed URL (OkHttp drops the token on a host change). */
    suspend fun <T> stream(url: HttpUrl, read: (BufferedSource) -> T): T {
        require(isOurs(url)) { GitHubText.NOT_FROM_GITHUB }
        return exchange(Verb.GET, url, null, ACCEPT_JSON, downloadClient) { response ->
            if (!response.isSuccessful) throw GitHubErrors.of(response.code, response.body.string())
            read(response.body.source())
        }
    }

    private suspend fun <T> exchange(
        verb: Verb,
        url: HttpUrl,
        body: JsonElement?,
        accept: String,
        http: OkHttpClient,
        read: (Response) -> T,
    ): T = withContext(io) { exchangeLoop(verb, url, body, accept, http, read) }

    private suspend fun <T> exchangeLoop(
        verb: Verb,
        url: HttpUrl,
        body: JsonElement?,
        accept: String,
        http: OkHttpClient,
        read: (Response) -> T,
    ): T {
        var token = tokens.token()
        var renewed = false
        var retries = 0
        while (true) {
            val versioned = !apiVersion.retired
            val response = http.newCall(request(verb, url, body, accept, token, versioned)).await()
            val limited = isRateLimited(response)
            val wait = if (retries < MAX_RETRIES) retryDelay(verb, response, limited, retries) else null
            when {
                // Refused before anything happened, so even a write is safe to send again.
                versioned && refusesVersion(response) -> {
                    response.close()
                    apiVersion.retired = true
                }
                response.code == 401 -> {
                    response.close()
                    if (renewed) {
                        tokens.revoke(token)
                        throw NotConnectedException(GitHubText.ACCESS_REMOVED)
                    }
                    token = tokens.renew(token)
                    renewed = true
                }
                wait != null -> {
                    response.close()
                    retries++
                    pause(wait)
                }
                limited -> {
                    val until = rateLimitWait(response)
                    response.close()
                    throw GitHubRateLimitException(GitHubText.slowDown(until), response.code, until?.let { clock.now() + it })
                }
                else -> return response.use(read)
            }
        }
    }

    private fun request(verb: Verb, url: HttpUrl, body: JsonElement?, accept: String, token: String, versioned: Boolean): Request {
        check(isOurs(url)) { "GitHub requests go to the API host only" }
        val payload = body?.let { AppJson.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON) }
        return Request.Builder()
            .url(url)
            .header("Accept", accept)
            .apply { if (versioned) header(API_VERSION_HEADER, API_VERSION) }
            .header("Authorization", "Bearer $token")
            .method(verb.name, payload ?: if (verb == Verb.GET) null else EMPTY_BODY)
            .build()
    }

    /** How long to wait before retrying, or null when this answer is final. */
    private fun retryDelay(verb: Verb, response: Response, limited: Boolean, retries: Int): Long? {
        if (limited) return rateLimitWait(response)?.takeIf { it <= MAX_WAIT_MS }
        // Only reads are repeated after a server error: a write may have happened.
        if (response.code >= 500 && verb == Verb.GET) return SERVER_BACKOFF_MS shl retries
        return null
    }

    private fun refusesVersion(response: Response): Boolean =
        response.code in ApiVersionChoice.STATUSES && ApiVersionChoice.refuses(response.code, response.peekBody(PEEK_BYTES).string())

    private fun isRateLimited(response: Response): Boolean = when (response.code) {
        429 -> true
        403 -> response.header("retry-after") != null ||
            response.header("x-ratelimit-remaining") == "0" ||
            response.peekBody(PEEK_BYTES).string().contains("rate limit", ignoreCase = true)
        else -> false
    }

    /** GitHub's documented order: `retry-after`, then `x-ratelimit-reset` when none is left; null when unknown. */
    private fun rateLimitWait(response: Response): Long? {
        response.header("retry-after")?.trim()?.toLongOrNull()?.let { return it.coerceAtLeast(0) * 1000 }
        if (response.header("x-ratelimit-remaining") == "0") {
            val reset = response.header("x-ratelimit-reset")?.trim()?.toLongOrNull() ?: return null
            return (reset * 1000 - clock.now()).coerceAtLeast(0) + RESET_MARGIN_MS
        }
        return null
    }

    private fun nextPage(headers: Headers): HttpUrl? {
        val link = headers["Link"] ?: return null
        return NEXT_LINK.find(link)?.groupValues?.get(1)?.toHttpUrlOrNull()
    }

    companion object {
        const val API_VERSION = "2026-03-10"
        const val API_VERSION_HEADER = "X-GitHub-Api-Version"
        const val ACCEPT_JSON = "application/vnd.github+json"
        const val ACCEPT_RAW = "application/vnd.github.raw+json"
        const val PER_PAGE = 100
        const val MAX_PAGES = 50
        const val MAX_RETRIES = 2
        /** Longest wait worth holding a screen for; longer limits become a message with a time. */
        const val MAX_WAIT_MS = 30_000L
        private const val SERVER_BACKOFF_MS = 1_000L
        private const val RESET_MARGIN_MS = 1_000L
        private const val PEEK_BYTES = 4_096L
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private val NEXT_LINK = Regex("<([^>]+)>\\s*;\\s*rel=\"next\"")
    }
}
