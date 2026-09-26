package com.pocketide.cloud

import com.pocketide.github.GitHubException
import com.pocketide.github.RestClient
import com.pocketide.github.Verb
import com.pocketide.github.decode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What GitHub needs to make a computer: the repository, its set-up file and the owner's choices. */
internal data class NewCodespace(
    val repositoryId: Long,
    val branch: String,
    val displayName: String,
    val devcontainerPath: String,
    val machine: String?,
    val idleMinutes: Int,
    val keepMinutes: Int,
)

/**
 * GitHub's Codespaces REST API for the signed-in user. Every call names a codespace GitHub gave
 * us, checked against its shape, so a name can never add a path.
 */
internal class CodespacesRest(private val rest: RestClient) {

    suspend fun list(): List<Computer> =
        rest.pages(rest.url("user", "codespaces")) { decode(CodespacesPage.serializer(), it).codespaces.map { c -> c.computer() } }

    suspend fun get(name: String): Computer? =
        rest.getOrNull(rest.url("user", "codespaces", checked(name)))?.let { decode(CodespaceJson.serializer(), it.text).computer() }

    /**
     * GitHub answers 201 with the new codespace, or 202 when making it hit a snag it retries by
     * itself; both carry the codespace, which is then followed by its state.
     */
    suspend fun create(request: NewCodespace): Computer {
        val body = buildJsonObject {
            put("repository_id", request.repositoryId)
            put("ref", request.branch)
            put("display_name", request.displayName)
            put("devcontainer_path", request.devcontainerPath)
            request.machine?.let { put("machine", it) }
            put("idle_timeout_minutes", request.idleMinutes)
            put("retention_period_minutes", request.keepMinutes)
            // The computer's own GitHub token then reaches this repository only.
            put("multi_repo_permissions_opt_out", true)
        }
        val reply = rest.send(Verb.POST, rest.url("user", "codespaces"), body) ?: throw GitHubException(CloudText.NOT_MADE, HttpStatus.NONE)
        return decode(CodespaceJson.serializer(), reply.text).computer()
    }

    suspend fun start(name: String): Computer = lifecycle(name, "start")

    suspend fun stop(name: String): Computer = lifecycle(name, "stop")

    suspend fun delete(name: String) {
        rest.send(Verb.DELETE, rest.url("user", "codespaces", checked(name)))
    }

    suspend fun machines(owner: String, repo: String): List<Machine> {
        val url = rest.url("repos", owner, repo, "codespaces", "machines")
        return decode(MachinesPage.serializer(), rest.get(url).text).machines.map { it.machine() }
    }

    private suspend fun lifecycle(name: String, action: String): Computer {
        val reply = rest.send(Verb.POST, rest.url("user", "codespaces", checked(name), action))
            ?: throw GitHubException(CloudText.NOT_FOUND, HttpStatus.NOT_FOUND)
        return decode(CodespaceJson.serializer(), reply.text).computer()
    }

    private fun checked(name: String): String {
        require(NAME.matches(name)) { CloudText.NOT_FOUND }
        return name
    }

    private companion object {
        /** GitHub's codespace names: words and numbers joined by hyphens. */
        val NAME = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,99}")
    }
}
