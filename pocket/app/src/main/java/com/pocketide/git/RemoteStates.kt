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
) {
    fun onGitHub(): List<ObjectId> = refs.values.mapNotNull { id ->
        if (ObjectId.isId(id)) ObjectId.fromString(id) else null
    }
}

/**
 * The gate's own records, one per bare repo, kept where Linux cannot write. Linux can change
 * anything inside a bare repo, its remote-tracking refs included, so "which repository on GitHub"
 * and "what GitHub already has" come from here, never from the repo. Also home to the private
 * config JGit reads and to clones in progress.
 */
internal class RemoteStates(private val dir: File) {

    fun read(gitDir: File): RemoteState? {
        val text = file(gitDir, "json").readSmallText(MAX_RECORD_BYTES) ?: return null
        return runCatching { AppJson.decodeFromString(RemoteState.serializer(), text) }.getOrNull()
    }

    fun write(gitDir: File, state: RemoteState) {
        writeAtomically(file(gitDir, "json"), AppJson.encodeToString(RemoteState.serializer(), state))
    }

    /** Drops the record and the private config, before a fresh clone. */
    fun forget(gitDir: File) {
        file(gitDir, "json").delete()
        privateConfig(gitDir).delete()
    }

    fun privateConfig(gitDir: File): File = file(gitDir, "config")

    /** Where a clone is built before it is moved into place, out of Linux's sight. */
    fun staging(gitDir: File): File = File(File(dir, "staging"), keyOf(gitDir))

    private fun file(gitDir: File, extension: String) = File(dir, "${keyOf(gitDir)}.$extension")

    private fun keyOf(gitDir: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(gitDir.path.toByteArray(Charsets.UTF_8))
        return digest.take(KEY_BYTES).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val KEY_BYTES = 16

        /** A record lists GitHub's refs; even thousands of pull requests stay far below this. */
        const val MAX_RECORD_BYTES = 16L * 1024 * 1024
    }
}
