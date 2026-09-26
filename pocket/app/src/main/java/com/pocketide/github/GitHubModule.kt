package com.pocketide.github

import com.pocketide.AppGraph
import com.pocketide.BuildConfig
import com.pocketide.core.Http
import okhttp3.HttpUrl.Companion.toHttpUrl

private val OAUTH_BASE = "https://github.com/".toHttpUrl()
internal val API_BASE = "https://api.github.com/".toHttpUrl()

/** The owner's GitHub App: the one entered in the app first, the build's otherwise. Cheap; make one per use. */
fun gitHubAppChoice(graph: AppGraph): GitHubAppChoice =
    GitHubAppChoice(graph.settings, GitHubApp(BuildConfig.GITHUB_APP_CLIENT_ID, BuildConfig.GITHUB_APP_SLUG))

fun createGitHubAuth(graph: AppGraph): GitHubAuth = DeviceFlowAuth(
    app = gitHubAppChoice(graph)::current,
    tokenStore = TokenStore(graph.secureStore),
    http = Http.client,
    oauthBase = OAUTH_BASE,
    apiBase = API_BASE,
    clock = graph.clock,
)

fun createGitHubApi(graph: AppGraph): GitHubApi = GitHubRestApi(rest = restClient(graph), clock = graph.clock)

/** The REST client other modules (cloud computers) share, with the signed-in user's token. */
internal fun restClient(graph: AppGraph): RestClient = RestClient(
    client = Http.client,
    base = API_BASE,
    tokens = graph.gitHubAuth.asUserTokens(),
    clock = graph.clock,
)

/** The real auth renews and revokes on a 401; any other [GitHubAuth] (a test fake) just supplies tokens. */
private fun GitHubAuth.asUserTokens(): UserTokens = this as? UserTokens ?: object : UserTokens {
    override suspend fun token() = this@asUserTokens.token()
    override suspend fun renew(rejected: String): String = throw NotConnectedException(GitHubText.ACCESS_REMOVED)
    override suspend fun revoke(rejected: String) = Unit
}
