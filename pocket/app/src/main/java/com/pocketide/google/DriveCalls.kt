package com.pocketide.google

import com.pocketide.core.AppJson
import com.pocketide.core.await
import kotlinx.coroutines.delay
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Where Drive lives. Tests point both at a local server. */
internal data class DriveEndpoints(
    val api: HttpUrl = "https://www.googleapis.com/drive/v3/".toHttpUrl(),
    val upload: HttpUrl = "https://www.googleapis.com/upload/drive/v3/".toHttpUrl(),
)

/** Access tokens for one Google account. Tokens stay in memory; they are never logged. */
internal interface TokenSource {
    /** The account these tokens belong to, when known (keeps upload sessions apart per account). */
    fun account(): String?

    suspend fun token(): String

    /** Drive answered 401 to [token]: forget it, so the next [token] is a fresh one. */
    suspend fun rejected(token: String)
}

/** Bounded exponential backoff with jitter, for Drive's "slow down" answers and dropped uploads. */
internal class Backoff(
    val maxRetries: Int = 5,
    private val baseMs: Long = 1_000,
    private val capMs: Long = 32_000,
    private val random: Random = Random.Default,
    val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /** The wait before retry number [retry] (0-based): half fixed, half random, never below Drive's Retry-After. */
    fun delayFor(retry: Int, retryAfterMs: Long? = null): Long {
        val ceiling = min(capMs, baseMs shl min(retry, 16))
        val half = ceiling / 2
        return max(half + random.nextLong(half + 1), retryAfterMs ?: 0L)
    }
}

/** The HTTP clients and addresses every Drive store and the sign-in share. */
internal class DriveHttp(
    val client: OkHttpClient,
    val endpoints: DriveEndpoints = DriveEndpoints(),
    val backoff: Backoff = Backoff(),
    val sessions: UploadSessions = UploadSessions(),
) {
    /** Upload sessions answer 308 without a Location; nothing there may be followed as a redirect. */
    val uploadClient: OkHttpClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
}

/** What an answer that is not a success means for the caller. */
internal sealed interface Verdict {
    data object Unauthorized : Verdict

    /** Rate limits and server errors: wait and try again. */
    data class SlowDown(val retryAfterMs: Long?) : Verdict

    data class Fatal(val error: DriveException) : Verdict
}

/**
 * Drive requests for one account: a bearer token on each, one fresh token after a 401, bounded
 * backoff on rate limits and server errors, and Drive's error reasons mapped to [DriveException].
 */
internal class DriveCalls(val http: DriveHttp, val tokens: TokenSource) {
    private val backoff get() = http.backoff

    /**
     * Sends a request with a token. A 401 gets one fresh token and one more try; a second 401
     * means access is gone. Returns the response whatever its status (the caller closes it).
     */
    suspend fun authorized(client: OkHttpClient = http.client, build: Request.Builder.() -> Unit): Response {
        var token = tokens.token()
        var response = execute(client, request(token, build))
        if (response.code != HTTP_UNAUTHORIZED) return response
        response.close()
        tokens.rejected(token)
        token = tokens.token()
        response = execute(client, request(token, build))
        if (response.code == HTTP_UNAUTHORIZED) {
            response.close()
            throw DriveException.Revoked()
        }
        return response
    }

    /** Like [authorized], but only a success comes back; slow-downs are retried within bounds. */
    suspend fun exchange(build: Request.Builder.() -> Unit): Response {
        var retry = 0
        while (true) {
            val response = authorized(build = build)
            if (response.isSuccessful) return response
            val verdict = response.use { judge(it) }
            when (verdict) {
                is Verdict.Fatal -> throw verdict.error
                Verdict.Unauthorized -> throw DriveException.Revoked()
                is Verdict.SlowDown -> {
                    val wait = backoff.delayFor(retry, verdict.retryAfterMs)
                    if (retry >= backoff.maxRetries) throw DriveException.RateLimited(wait)
                    retry++
                    backoff.pause(wait)
                }
            }
        }
    }

    suspend fun <T> json(url: HttpUrl, strategy: DeserializationStrategy<T>): T =
        exchange { url(url) }.use { decode(it, strategy) }

    private fun request(token: String, build: Request.Builder.() -> Unit): Request =
        Request.Builder().apply(build).header("Authorization", "Bearer $token").build()

    private suspend fun execute(client: OkHttpClient, request: Request): Response = try {
        client.newCall(request).await()
    } catch (_: IOException) {
        throw DriveException.Offline()
    }

    companion object {
        const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY = 429
        private const val HTTP_SERVER_ERROR = 500
        private const val ERROR_PEEK_BYTES = 64L * 1024
        private const val DAILY_LIMIT_WAIT_MS = 60L * 60 * 1000

        private val SLOW_DOWN = setOf("rateLimitExceeded", "userRateLimitExceeded")
        private val SCOPE_MISSING = setOf("insufficientPermissions", "insufficientScopes", "ACCESS_TOKEN_SCOPE_INSUFFICIENT")

        /** Maps a failed answer by its status and `error.errors[].reason` (403 means different things). */
        fun judge(response: Response): Verdict {
            val code = response.code
            val reasons = reasons(response)
            val retryAfter = retryAfterMs(response)
            return when {
                code == HTTP_UNAUTHORIZED -> Verdict.Unauthorized
                "storageQuotaExceeded" in reasons -> Verdict.Fatal(DriveException.StorageFull())
                code == HTTP_TOO_MANY || code >= HTTP_SERVER_ERROR || reasons.any { it in SLOW_DOWN } -> Verdict.SlowDown(retryAfter)
                "dailyLimitExceeded" in reasons -> Verdict.Fatal(DriveException.RateLimited(DAILY_LIMIT_WAIT_MS))
                "domainPolicy" in reasons -> Verdict.Fatal(DriveException.Other(DOMAIN_POLICY))
                code == HTTP_FORBIDDEN && reasons.any { it in SCOPE_MISSING } -> Verdict.Fatal(DriveException.Revoked())
                code == HTTP_NOT_FOUND -> Verdict.Fatal(DriveException.NotFound())
                else -> Verdict.Fatal(DriveException.Other("Google Drive refused the request (HTTP $code). Try again later."))
            }
        }

        fun <T> decode(response: Response, strategy: DeserializationStrategy<T>): T = try {
            AppJson.decodeFromString(strategy, response.body.string())
        } catch (_: SerializationException) {
            throw DriveException.Other(UNREADABLE)
        } catch (_: IllegalArgumentException) {
            throw DriveException.Other(UNREADABLE)
        } catch (_: IOException) {
            throw DriveException.Offline()
        }

        private fun reasons(response: Response): List<String> = try {
            val text = response.peekBody(ERROR_PEEK_BYTES).string()
            AppJson.decodeFromString(ErrorEnvelope.serializer(), text).error?.errors.orEmpty().mapNotNull { it.reason }
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        } catch (_: IOException) {
            emptyList()
        }

        /** Retry-After in seconds (the only form Google sends); dates are ignored. */
        private fun retryAfterMs(response: Response): Long? =
            response.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1_000)

        const val UNREADABLE = "Google Drive sent an answer PocketIDE could not read. Try again later."
        const val DOMAIN_POLICY = "The administrator of this Google account does not allow PocketIDE to use Drive. Use a personal Google account."
    }
}

@Serializable
internal class ErrorEnvelope(val error: ErrorBody? = null)

@Serializable
internal class ErrorBody(val code: Int = 0, val errors: List<ErrorItem> = emptyList())

@Serializable
internal class ErrorItem(val reason: String? = null)
