package com.pocketide.git

import com.pocketide.core.AppJson
import kotlinx.serialization.Serializable
import org.eclipse.jgit.lib.ObjectId
import java.io.File
import java.security.MessageDigest

/** What the gate knows about one bare repo from its own clone, fetch and push results. */
@Serializable
internal data class RemoteState(
    val url: String,
    val defaultBranch: String? = null,
    /** Every ref GitHub showed at the last clone, fetch or push: full ref name to object id. */
    val refs: Map<String, String> = emptyMap(),
    /** Workflow contents the owner approved, as [WorkflowChanges.approvalKey]s, newest last. */
    val approvedWorkflows: List<String> = emptyList(),
) {
    fun onGitHub(): List<ObjectId> = refs.values.mapNotNull { id ->
        if (ObjectId.isId(id)) ObjectId.fromString(id) else null
    }
}

/**
 * The gate's own records, one per bare repo, kept where Linux cannot write. Linux can change
 * anything inside a bare repo, its remote-tracking refs included, so "which repository on GitHub"
 * and "what GitHub already has" come from here, never from the repo. Also home to the private
 * config JGit reads, to the shadow folder of each repo ([GuardedFs]) and to clones in progress.
 */
internal class RemoteStates(private val dir: File) {

    fun read(gitDir: File): RemoteState? {
        val text = file(gitDir, "json").readSmallText(MAX_RECORD_BYTES) ?: return null
        return runCatching { AppJson.decodeFromString(RemoteState.serializer(), text) }.getOrNull()
    }

    fun write(gitDir: File, state: RemoteState) {
        writeAtomically(file(gitDir, "json"), AppJson.encodeToString(RemoteState.serializer(), state))
    }

    fun privateConfig(gitDir: File): File = file(gitDir, "config")

    /** Where JGit looks for the repo's reflogs and borrowed objects; it holds neither. */
    fun shadow(gitDir: File): File = file(gitDir, "shadow")

    /** Where a clone is built before it is moved into place, out of Linux's sight. */
    fun staging(gitDir: File): File = File(File(dir, "staging"), keyOf(gitDir))

    private fun file(gitDir: File, extension: String) = File(dir, "${keyOf(gitDir)}.$extension")

    // A clone in progress and the finished repo share one record (see [repoName]).
    private fun keyOf(gitDir: File): String {
        val path = File(gitDir.parentOrRoot(), repoName(gitDir.name) ?: gitDir.name).path
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        return digest.take(KEY_BYTES).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val KEY_BYTES = 16

        /** A record lists GitHub's refs; even thousands of pull requests stay far below this. */
        const val MAX_RECORD_BYTES = 16L * 1024 * 1024
    }
}
