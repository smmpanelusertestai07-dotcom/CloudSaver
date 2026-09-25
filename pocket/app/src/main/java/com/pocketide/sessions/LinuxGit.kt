package com.pocketide.sessions

import com.pocketide.core.AppDirs
import com.pocketide.core.Redact
import com.pocketide.linux.Bind
import com.pocketide.linux.Computer
import com.pocketide.linux.ComputerState
import com.pocketide.linux.LinuxCommand
import com.pocketide.sessions.transcripts.oneLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Collections

/**
 * Runs git inside Linux on the bare clones and a room's worktrees. Git records worktree paths as
 * the rooms see them, so every command binds the guest layout the rooms use: `/repos` for the
 * bare clones and `/work` for one room's worktrees. Nothing here uses the network or a token:
 * fetch and push happen on the Android side.
 *
 * The repositories are writable by every agent, so git runs without hooks, without the shared
 * system and global configuration, without fsmonitor programs and without commit signing.
 */
internal class LinuxGit(
    private val computer: () -> Computer,
    private val dirs: AppDirs,
    private val io: CoroutineDispatcher,
) {

    /** Who a merge commit is by: the owner, with the address GitHub keeps private. */
    data class Identity(val name: String, val email: String)

    class Output(val exitCode: Int, val lines: List<String>) {
        val ok: Boolean get() = exitCode == 0

        fun says(text: String) = lines.any { it.contains(text) }

        /** git's own explanation, in one short sentence for the owner. */
        fun reason(): String {
            val line = lines.lastOrNull { it.startsWith("fatal:") || it.startsWith("error:") }
                ?: lines.lastOrNull { it.isNotBlank() }
                ?: return "git stopped with code $exitCode."
            val text = oneLine(Redact.text(line.removePrefix("fatal:").removePrefix("error:")), MAX_REASON_CHARS)
            return text.replaceFirstChar { it.uppercase() }.let { if (it.endsWith(".")) it else "$it." }
        }
    }

    /**
     * Runs `git [args]` with the bare clones bound, and [room]'s worktrees too when given. With
     * [errors] false only standard output is kept, for output that is parsed.
     */
    suspend fun run(room: String?, args: List<String>, identity: Identity? = null, errors: Boolean = true): Output {
        val linux = computer()
        when (val state = linux.state.value) {
            ComputerState.Ready, is ComputerState.Updating -> Unit
            ComputerState.NotInstalled -> throw SessionException("Set up the computer first.")
            is ComputerState.Installing -> throw SessionException("The computer is still being set up. Try again when it is ready.")
            is ComputerState.Broken -> throw SessionException("${state.why} ${state.fix}")
        }
        val binds = withContext(io) {
            buildList {
                add(Bind(ensureDir(dirs.repos), AppDirs.GUEST_REPOS))
                if (room != null) add(Bind(ensureDir(dirs.roomWork(room)), AppDirs.GUEST_WORK))
            }
        }
        val env = buildMap {
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_CONFIG_GLOBAL", "/dev/null")
            put("GIT_TERMINAL_PROMPT", "0")
            put("GIT_OPTIONAL_LOCKS", "0")
            put("LC_ALL", "C")
            if (identity != null) {
                put("GIT_AUTHOR_NAME", identity.name)
                put("GIT_AUTHOR_EMAIL", identity.email)
                put("GIT_COMMITTER_NAME", identity.name)
                put("GIT_COMMITTER_EMAIL", identity.email)
            }
        }
        val lines = Collections.synchronizedList(ArrayList<String>())
        val exitCode = try {
            // A git that hangs inside Linux must not hold a project's lock for ever.
            withTimeoutOrNull(TIMEOUT_MS) {
                linux.run(LinuxCommand(SAFE_GIT + args, binds, env, workDir = "/", mergeErrors = errors)) { line ->
                    if (lines.size < MAX_LINES) lines += line
                }
            } ?: throw SessionException("Git did not finish in time inside the computer. Try again.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (known: SessionException) {
            throw known
        } catch (failed: Exception) {
            throw SessionException("The computer could not run git: ${failed.message ?: failed.javaClass.simpleName}")
        }
        return Output(exitCode, lines.toList())
    }

    private fun ensureDir(dir: File): String {
        if (!dir.isDirectory && !dir.mkdirs()) throw SessionException("Could not create the folder ${dir.name}.")
        return dir.absolutePath
    }

    private companion object {
        const val MAX_LINES = 5_000
        const val MAX_REASON_CHARS = 200
        const val TIMEOUT_MS = 10 * 60 * 1000L
        val SAFE_GIT = listOf(
            "git",
            "-c", "core.hooksPath=/dev/null",
            "-c", "core.fsmonitor=false",
            "-c", "commit.gpgSign=false",
            "-c", "core.quotePath=false",
            "-c", "safe.directory=*",
        )
    }
}
