package com.pocketide.projects

import com.pocketide.git.GitGate
import com.pocketide.git.PushResult
import com.pocketide.git.Verdict
import com.pocketide.github.AccountUsage
import com.pocketide.github.GitHubAccount
import com.pocketide.github.GitHubApi
import com.pocketide.github.GitHubAuth
import com.pocketide.github.NotConnectedException
import com.pocketide.github.PullRequest
import com.pocketide.github.RepoFile
import com.pocketide.github.RepoInfo
import com.pocketide.github.RepoUsage
import com.pocketide.github.RunArtifact
import com.pocketide.github.WorkflowRun
import com.pocketide.model.Decision
import com.pocketide.model.LinkHealth
import com.pocketide.sync.DataBudget
import com.pocketide.sync.DataUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException

fun repoInfo(owner: String, name: String, sizeKb: Long = 100, isPrivate: Boolean = true, defaultBranch: String = "main") = RepoInfo(
    owner = owner,
    name = name,
    isPrivate = isPrivate,
    defaultBranch = defaultBranch,
    sizeKb = sizeKb,
    cloneUrl = "https://github.com/$owner/$name.git",
    htmlUrl = "https://github.com/$owner/$name",
    pushedAt = null,
)

/** GitHub with the repositories the app's installation can reach. */
class FakeGitHubApi : GitHubApi {
    val reachable = mutableMapOf<String, RepoInfo>()
    val created = mutableListOf<Triple<String, String, Boolean>>()
    var offline = false

    override suspend fun repo(owner: String, name: String): RepoInfo? {
        if (offline) throw IOException("offline")
        return reachable["$owner/$name".lowercase()]
    }

    override suspend fun createPrivateRepo(name: String, description: String, autoInit: Boolean): RepoInfo {
        created += Triple(name, description, autoInit)
        return repoInfo("alice", name).also { reachable["alice/${name.lowercase()}"] = it }
    }

    private fun no(): Nothing = throw UnsupportedOperationException("Not used by projects")
    override suspend fun me(): GitHubAccount = no()
    override suspend fun repos(): List<RepoInfo> = reachable.values.toList()
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

class FakeAuth(login: String? = "alice") : GitHubAuth {
    override val account = MutableStateFlow(login?.let { GitHubAccount(it, 42, null, null) })
    override val configured = true
    override suspend fun startDeviceFlow() = throw UnsupportedOperationException()
    override suspend fun poll(code: com.pocketide.github.DeviceCode) = throw UnsupportedOperationException()
    override suspend fun token(): String = account.value?.let { "token" } ?: throw NotConnectedException("signed out")
    override suspend fun health() = LinkHealth.OK
    override fun installUrl() = "https://github.com/settings/installations/7"
    override suspend fun signOut() = Unit
}

/** Clones by making the folders a finished bare clone has. */
class FakeCloneGate : GitGate {
    val cloned = mutableListOf<String>()
    val fetched = mutableListOf<File>()
    var failClone = false

    override suspend fun clone(cloneUrl: String, bareRepo: File, token: String) {
        if (failClone) {
            File(bareRepo, "objects").mkdirs()
            throw IOException("connection reset")
        }
        cloned += cloneUrl
        File(bareRepo, "objects").mkdirs()
        File(bareRepo, "HEAD").writeText("ref: refs/heads/main\n")
    }

    override suspend fun fetch(bareRepo: File, token: String) {
        fetched += bareRepo
    }

    override suspend fun checkPost(bareRepo: File, branch: String, knownValues: List<String>) = Verdict(true, emptyList(), 0)
    override suspend fun push(bareRepo: File, branch: String, token: String, knownValues: List<String>) = PushResult.Pushed
    override suspend fun stats(bareRepo: File, branch: String, base: String) = 0 to 0
    override suspend fun deleteRemoteBranch(bareRepo: File, branch: String, token: String) = PushResult.Pushed
    override suspend fun <T> withRepository(bareRepo: File, block: (org.eclipse.jgit.lib.Repository) -> T): T = throw UnsupportedOperationException()
}

class FakeBudget(var decision: Decision = Decision.YES) : DataBudget {
    val asked = mutableListOf<Triple<Long, String, Boolean>>()
    val recorded = mutableListOf<Long>()
    /** Kinds the owner allowed once on mobile data. */
    val grants = mutableMapOf<String, Long>()
    override val usage: StateFlow<DataUsage> = MutableStateFlow(DataUsage(0, 0, emptyMap()))
    override fun allow(bytes: Long, kind: String, big: Boolean): Decision {
        asked += Triple(bytes, kind, big)
        return if (kind in grants) Decision.YES else decision
    }
    override fun allowOnce(kind: String, bytes: Long) {
        grants[kind] = bytes
    }
    override fun record(bytes: Long, kind: String) {
        recorded += bytes
    }
}

class FakeWork : ProjectWork {
    var unsavedReason: String? = null
    val released = mutableListOf<String>()
    override suspend fun unsaved(projectId: String): String? = unsavedReason
    override suspend fun release(projectId: String) {
        released += projectId
    }
}

internal class FakeProjectEnv(
    override val gitHub: FakeGitHubApi = FakeGitHubApi(),
    override val gitHubAuth: FakeAuth = FakeAuth(),
    override val git: FakeCloneGate = FakeCloneGate(),
    override val dataBudget: FakeBudget = FakeBudget(),
    override val work: FakeWork = FakeWork(),
) : ProjectEnv {
    /** How many times the vault was asked for a new key; [rekeyFails] makes the next ask fail. */
    var rekeys = 0
    var rekeyFails = false

    override suspend fun keyringCloned() {
        if (rekeyFails) throw IOException("offline")
        rekeys++
    }
}
