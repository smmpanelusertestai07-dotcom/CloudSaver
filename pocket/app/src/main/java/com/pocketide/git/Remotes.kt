package com.pocketide.git

import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/** Which remotes the gate may talk to, and which of them may see the token. */
internal interface RemotePolicy {
    /** The remote exactly as the gate will use it; a [GitGateException] when it is not allowed. */
    fun allowed(url: String): URIish

    /** The remote of a bare repo the app made, from its directory name, when there is no record of it. */
    fun remoteFor(gitDir: File): String?

    fun mayAuthenticate(uri: URIish): Boolean
}

/** Production: `https://github.com/<owner>/<repo>.git` and nothing else. */
internal object GitHubRemotes : RemotePolicy {
    private const val HOST = "github.com"
    private val shape = Regex("""https://github\.com/([A-Za-z0-9][A-Za-z0-9_-]{0,38})/([A-Za-z0-9._-]{1,100})\.git""")

    override fun allowed(url: String): URIish {
        val match = shape.matchEntire(url) ?: throw GitGateException(GitMessages.NOT_GITHUB)
        val (owner, repo) = match.destructured
        if (repo.trim('.').isEmpty()) throw GitGateException(GitMessages.NOT_GITHUB)
        return URIish(urlOf(owner, repo))
    }

    /** Bare repos are named `<owner>__<repo>.git` (see AppDirs); owners never contain "__". */
    override fun remoteFor(gitDir: File): String? {
        val project = repoName(gitDir.name)?.removeSuffix(".git") ?: return null
        val owner = project.substringBefore("__", missingDelimiterValue = "")
        val repo = project.substringAfter("__", missingDelimiterValue = "")
        return urlOf(owner, repo).takeIf { shape.matches(it) }
    }

    override fun mayAuthenticate(uri: URIish): Boolean =
        uri.scheme == "https" && uri.host.equals(HOST, ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) && uri.user == null

    private fun urlOf(owner: String, repo: String) = "https://$HOST/$owner/$repo.git"
}

/**
 * The token as HTTPS basic auth ("x-access-token", token), in memory only, and only for a remote
 * the policy trusts: a redirect to any other host gets nothing.
 */
internal class TokenCredentials(token: String, private val policy: RemotePolicy) :
    UsernamePasswordCredentialsProvider("x-access-token", token) {

    override fun get(uri: URIish?, vararg items: CredentialItem?): Boolean =
        uri != null && policy.mayAuthenticate(uri) && super.get(uri, *items)
}
