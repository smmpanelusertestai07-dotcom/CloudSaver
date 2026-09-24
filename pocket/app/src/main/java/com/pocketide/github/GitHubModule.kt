package com.pocketide.github

import com.pocketide.AppGraph
import com.pocketide.model.LinkHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

fun createGitHubAuth(graph: AppGraph): GitHubAuth = StubGitHubAuth().also { graph.hashCode() }

fun createGitHubApi(graph: AppGraph): GitHubApi = StubGitHubApi().also { graph.hashCode() }

private class StubGitHubAuth : GitHubAuth {
    override val account: StateFlow<GitHubAccount?> = MutableStateFlow(null)
    override val configured = false
    override suspend fun startDeviceFlow(): DeviceCode = throw NotConnectedException("stub")
    override suspend fun poll(code: DeviceCode): DevicePoll = DevicePoll.Failed("stub")
    override suspend fun token(): String = throw NotConnectedException("stub")
    override suspend fun health() = LinkHealth.NOT_CONNECTED
    override fun installUrl() = "https://github.com/settings/installations"
    override suspend fun signOut() = Unit
}

private class StubGitHubApi : GitHubApi {
    private fun no(): Nothing = throw NotConnectedException("stub")
    override suspend fun me(): GitHubAccount = no()
    override suspend fun repos(): List<RepoInfo> = no()
    override suspend fun repo(owner: String, name: String): RepoInfo? = no()
    override suspend fun createPrivateRepo(name: String, description: String, autoInit: Boolean): RepoInfo = no()
    override suspend fun collaborators(owner: String, name: String): List<String> = no()
    override suspend fun setActionsEnabled(owner: String, name: String, enabled: Boolean) = no()
    override suspend fun readFile(owner: String, name: String, path: String): RepoFile? = no()
    override suspend fun writeFile(owner: String, name: String, path: String, bytes: ByteArray, message: String, sha: String?) = no()
    override suspend fun openPullRequest(owner: String, name: String, head: String, base: String, title: String, body: String): PullRequest = no()
    override suspend fun pullRequest(owner: String, name: String, number: Int): PullRequest = no()
    override suspend fun mergePullRequest(owner: String, name: String, number: Int, method: String): Boolean = no()
    override suspend fun dispatchWorkflow(owner: String, name: String, workflowFile: String, ref: String, inputs: Map<String, String>) = no()
    override suspend fun runs(owner: String, name: String, branch: String?): List<WorkflowRun> = no()
    override suspend fun artifacts(owner: String, name: String, runId: Long): List<RunArtifact> = no()
    override suspend fun downloadArtifact(artifact: RunArtifact, dest: File) = no()
    override suspend fun setActionsSecret(owner: String, name: String, secretName: String, value: ByteArray) = no()
    override suspend fun accountUsage(): AccountUsage = no()
    override suspend fun repoUsage(owner: String, name: String): RepoUsage = no()
}
