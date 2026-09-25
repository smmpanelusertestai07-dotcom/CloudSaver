package com.pocketide.github

import com.pocketide.core.Clock
import com.pocketide.core.await
import com.pocketide.model.LinkHealth
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * GitHub App sign-in with the device flow, and the user token's life after it.
 *
 * The device flow needs no client secret, and GitHub's docs say a token made by the device flow
 * is refreshed without one too ("client_secret: Required unless the user access token was
 * generated using the device flow"), so the app carries only the public client ID. Every refresh
 * rotates both tokens, which is why refreshes are single-flight behind [refreshLock]: two at once
 * would leave the loser with a dead refresh token.
 */
internal class DeviceFlowAuth(
    /** Asked at every use: the owner may enter or change the App while the app runs. */
    private val app: () -> GitHubApp,
    private val tokenStore: TokenStore,
    private val http: OkHttpClient,
    private val oauthBase: HttpUrl,
    private val apiBase: HttpUrl,
    private val clock: Clock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : GitHubAuth, UserTokens {

    @Volatile
    private var current: StoredTokens? = tokenStore.load()
    private val refreshLock = Mutex()
    private val signedIn = MutableStateFlow(current?.account())
    private val profileClient = RestClient(http, apiBase, this, clock, io = io, pause = pause)

    /** The interval GitHub last asked for, per device code, so repeated slow_downs add up. */
    @Volatile
    private var slowInterval: Pair<String, Int>? = null

    override val account: StateFlow<GitHubAccount?> = signedIn.asStateFlow()

    override val configured: Boolean get() = app().configured

    override suspend fun startDeviceFlow(): DeviceCode {
        val reply = postForm(DEVICE_CODE_PATH, mapOf("client_id" to clientId()))
        when (reply.error) {
            null -> Unit
            "device_flow_disabled" -> throw GitHubException(GitHubText.DEVICE_FLOW_OFF, 200)
            "incorrect_client_credentials" -> throw GitHubException(GitHubText.BAD_CLIENT_ID, 200)
            else -> throw GitHubException(GitHubText.SIGN_IN_FAILED, 200)
        }
        val deviceCode = reply.deviceCode
        val userCode = reply.userCode
        val verificationUri = reply.verificationUri
        if (deviceCode == null || userCode == null || verificationUri == null) throw GitHubException(GitHubText.UNEXPECTED, 200)
        return DeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri.takeIf { it.startsWith(VERIFICATION_PREFIX) } ?: DEFAULT_VERIFICATION_URI,
            expiresAtMs = clock.now() + (reply.expiresIn ?: DEFAULT_CODE_LIFETIME_S) * 1000,
            intervalSeconds = reply.interval ?: DEFAULT_INTERVAL_S,
        )
    }

    override suspend fun poll(code: DeviceCode): DevicePoll {
        val reply = postForm(
            ACCESS_TOKEN_PATH,
            mapOf("client_id" to clientId(), "device_code" to code.deviceCode, "grant_type" to DEVICE_GRANT),
        )
        return when (reply.error) {
            null -> reply.accessToken?.let { connect(reply, it) } ?: DevicePoll.Failed(GitHubText.UNEXPECTED)
            "authorization_pending" -> DevicePoll.Pending
            "slow_down" -> DevicePoll.SlowDown(slowerInterval(code, reply.interval))
            // The docs spell the expiry error both ways; a code GitHub no longer knows is expired too.
            "expired_token", "token_expired", "incorrect_device_code", "bad_verification_code" -> DevicePoll.Expired
            "access_denied" -> DevicePoll.Denied
            "device_flow_disabled" -> DevicePoll.Failed(GitHubText.DEVICE_FLOW_OFF)
            "incorrect_client_credentials" -> DevicePoll.Failed(GitHubText.BAD_CLIENT_ID)
            "unverified_user_email" -> DevicePoll.Failed(GitHubText.VERIFY_EMAIL)
            else -> DevicePoll.Failed(GitHubText.SIGN_IN_FAILED)
        }
    }

    override suspend fun token(): String {
        requireConfigured()
        val tokens = current ?: throw NotConnectedException(GitHubText.NOT_CONNECTED)
        if (!tokens.needsRefresh(clock.now())) return tokens.access
        return refreshLock.withLock {
            val latest = current ?: throw NotConnectedException(GitHubText.NOT_CONNECTED)
            if (latest.needsRefresh(clock.now())) refresh(latest, keepValidOnFailure = true) else latest.access
        }
    }

    override suspend fun renew(rejected: String): String = refreshLock.withLock {
        val latest = current ?: throw NotConnectedException(GitHubText.NOT_CONNECTED)
        when {
            latest.access != rejected -> latest.access
            latest.refresh == null -> {
                forget(revoked = true)
                throw NotConnectedException(GitHubText.ACCESS_REMOVED)
            }
            else -> refresh(latest, keepValidOnFailure = false)
        }
    }

    override suspend fun revoke(rejected: String) = refreshLock.withLock {
        if (current?.access == rejected) forget(revoked = true)
    }

    override suspend fun health(): LinkHealth {
        if (!configured) return LinkHealth.NOT_CONNECTED
        if (current == null) return if (withContext(io) { tokenStore.wasRevoked() }) LinkHealth.REVOKED else LinkHealth.NOT_CONNECTED
        return try {
            updateProfile(fetchProfile(profileClient))
            LinkHealth.OK
        } catch (e: NotConnectedException) {
            if (withContext(io) { tokenStore.wasRevoked() }) LinkHealth.REVOKED else LinkHealth.NOT_CONNECTED
        } catch (e: IOException) {
            LinkHealth.OFFLINE
        } catch (e: GitHubRateLimitException) {
            // The token was accepted; GitHub only wants fewer calls.
            LinkHealth.OK
        } catch (e: GitHubException) {
            // A server error, or a sign-in answer GitHub sent with status 200, says nothing about
            // the access itself: not a lock. Any other status means the token was accepted.
            if (e.status >= 500 || e.status == 200) LinkHealth.OFFLINE else LinkHealth.OK
        }
    }

    override fun installUrl(): String {
        val slug = app().slug
        return if (GitHubAppChoice.slug(slug) == slug) "https://github.com/apps/$slug/installations/new" else INSTALLATIONS_PAGE
    }

    /**
     * Forgets the tokens. GitHub's endpoint for revoking a user token needs the client secret,
     * which this app does not have, so the owner revokes the App on GitHub if they want to.
     */
    override suspend fun signOut() = refreshLock.withLock { forget(revoked = false) }

    private suspend fun connect(reply: OAuthReply, access: String): DevicePoll {
        val account = try {
            profileWithRetry(access)
        } catch (e: IOException) {
            return DevicePoll.Failed(GitHubText.PROFILE_FAILED)
        } catch (e: GitHubException) {
            return DevicePoll.Failed(GitHubText.PROFILE_FAILED)
        }
        val tokens = tokensFrom(reply, access, previous = null).withAccount(account)
        refreshLock.withLock { remember(tokens) }
        slowInterval = null
        return DevicePoll.Connected(account)
    }

    /** Right after approval GitHub can be briefly slow; a few short retries keep the approval from being wasted. */
    private suspend fun profileWithRetry(access: String): GitHubAccount {
        val oneShot = RestClient(http, apiBase, FixedToken(access), clock, io = io, pause = pause)
        var attempt = 0
        while (true) {
            try {
                return fetchProfile(oneShot)
            } catch (e: IOException) {
                if (++attempt >= PROFILE_ATTEMPTS) throw e
            } catch (e: GitHubException) {
                if (e.status < 500 || ++attempt >= PROFILE_ATTEMPTS) throw e
            }
            pause(PROFILE_RETRY_MS * attempt)
        }
    }

    private suspend fun fetchProfile(client: RestClient): GitHubAccount {
        val reply = client.get(client.url("user"))
        return decode(UserJson.serializer(), reply.text).account()
    }

    private suspend fun updateProfile(account: GitHubAccount) = refreshLock.withLock {
        val latest = current ?: return@withLock
        if (latest.account() != account) remember(latest.withAccount(account))
    }

    /**
     * One refresh, run to the end even if the caller is cancelled: once GitHub has rotated the
     * tokens, the new pair must be saved or the owner is signed out.
     */
    private suspend fun refresh(latest: StoredTokens, keepValidOnFailure: Boolean): String {
        val refreshToken = latest.refresh
        val now = clock.now()
        if (refreshToken == null || latest.refreshExpired(now)) {
            if (keepValidOnFailure && !latest.accessExpired(now)) return latest.access
            forget(revoked = true)
            throw NotConnectedException(GitHubText.ACCESS_REMOVED)
        }
        val reply = try {
            withContext(NonCancellable) {
                postForm(
                    ACCESS_TOKEN_PATH,
                    mapOf("client_id" to clientId(), "grant_type" to "refresh_token", "refresh_token" to refreshToken),
                )
            }
        } catch (e: IOException) {
            if (keepValidOnFailure && !latest.accessExpired(clock.now())) return latest.access
            throw e
        }
        val access = reply.accessToken
        return when {
            reply.error == "bad_refresh_token" -> {
                forget(revoked = true)
                throw NotConnectedException(GitHubText.ACCESS_REMOVED)
            }
            reply.error != null || access == null -> {
                if (keepValidOnFailure && !latest.accessExpired(clock.now())) return latest.access
                throw GitHubException(GitHubText.RENEW_FAILED, 200)
            }
            else -> {
                withContext(NonCancellable) { remember(tokensFrom(reply, access, previous = latest)) }
                access
            }
        }
    }

    private fun tokensFrom(reply: OAuthReply, access: String, previous: StoredTokens?): StoredTokens {
        val now = clock.now()
        return StoredTokens(
            access = access,
            accessExpiresAt = reply.expiresIn?.let { now + it * 1000 },
            refresh = reply.refreshToken,
            refreshExpiresAt = reply.refreshTokenExpiresIn?.let { now + it * 1000 },
            login = previous?.login.orEmpty(),
            id = previous?.id ?: 0,
            name = previous?.name,
            avatar = previous?.avatar,
        )
    }

    /** Callers hold [refreshLock]. */
    private suspend fun remember(tokens: StoredTokens) {
        withContext(io) { tokenStore.save(tokens) }
        current = tokens
        signedIn.value = tokens.account()
    }

    /** Callers hold [refreshLock]. */
    private suspend fun forget(revoked: Boolean) {
        withContext(NonCancellable + io) { tokenStore.forget(revoked) }
        current = null
        signedIn.value = null
    }

    private fun slowerInterval(code: DeviceCode, fromGitHub: Int?): Int {
        val previous = slowInterval?.takeIf { it.first == code.deviceCode }?.second ?: code.intervalSeconds
        val next = fromGitHub ?: (previous + SLOW_DOWN_STEP_S)
        slowInterval = code.deviceCode to next
        return next
    }

    /** github.com/login answers errors with HTTP 200 and an `error` field; other statuses are failures. */
    private suspend fun postForm(path: String, fields: Map<String, String>): OAuthReply {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder()
            .url(oauthBase.newBuilder().addPathSegments(path).build())
            .header("Accept", "application/json")
            .post(form)
            .build()
        return withContext(io) {
            http.newCall(request).await().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) throw GitHubErrors.of(response.code, body)
                decode(OAuthReply.serializer(), body)
            }
        }
    }

    private fun clientId(): String = app().clientId.ifBlank { throw NotConnectedException(GitHubText.NOT_CONFIGURED) }

    private fun requireConfigured() {
        clientId()
    }

    /** A token that is not stored yet: used once, to learn whose it is. */
    private class FixedToken(private val access: String) : UserTokens {
        override suspend fun token() = access
        override suspend fun renew(rejected: String): String = throw NotConnectedException(GitHubText.ACCESS_REMOVED)
        override suspend fun revoke(rejected: String) = Unit
    }

    companion object {
        const val DEVICE_CODE_PATH = "login/device/code"
        const val ACCESS_TOKEN_PATH = "login/oauth/access_token"
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val INSTALLATIONS_PAGE = "https://github.com/settings/installations"
        /** The code page is opened in the browser, so it must be GitHub's own. */
        private const val VERIFICATION_PREFIX = "https://github.com/"
        private const val DEFAULT_VERIFICATION_URI = "https://github.com/login/device"
        private const val DEFAULT_CODE_LIFETIME_S = 900L
        private const val DEFAULT_INTERVAL_S = 5
        private const val SLOW_DOWN_STEP_S = 5
        private const val PROFILE_ATTEMPTS = 3
        private const val PROFILE_RETRY_MS = 1_000L
    }
}
