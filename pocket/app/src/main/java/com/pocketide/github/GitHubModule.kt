package com.pocketide.github

import com.pocketide.AppGraph
import com.pocketide.BuildConfig
import com.pocketide.core.Http
import com.pocketide.vault.SealedBox
import okhttp3.HttpUrl.Companion.toHttpUrl

private val OAUTH_BASE = "https://github.com/".toHttpUrl()
private val API_BASE = "https://api.github.com/".toHttpUrl()

fun createGitHubAuth(graph: AppGraph): GitHubAuth = DeviceFlowAuth(
    clientId = BuildConfig.GITHUB_APP_CLIENT_ID.trim(),
    appSlug = BuildConfig.GITHUB_APP_SLUG.trim(),
    tokenStore = TokenStore(graph.secureStore),
    http = Http.client,
    oauthBase = OAUTH_BASE,
    apiBase = API_BASE,
    clock = graph.clock,
)

fun createGitHubApi(graph: AppGraph): GitHubApi = GitHubRestApi(
    rest = RestClient(
        client = Http.client,
        base = API_BASE,
        tokens = graph.gitHubAuth.asUserTokens(),
        clock = graph.clock,
        downloadClient = Http.downloads,
    ),
    sealer = Sealer(SealedBox::seal),
    clock = graph.clock,
)

/** The real auth renews and revokes on a 401; any other [GitHubAuth] (a test fake) just supplies tokens. */
private fun GitHubAuth.asUserTokens(): UserTokens = this as? UserTokens ?: object : UserTokens {
    override suspend fun token() = this@asUserTokens.token()
    override suspend fun renew(rejected: String): String = throw NotConnectedException(GitHubText.ACCESS_REMOVED)
    override suspend fun revoke(rejected: String) = Unit
}
