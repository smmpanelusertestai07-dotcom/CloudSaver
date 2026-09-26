package com.pocketide.git

import kotlinx.coroutines.Dispatchers
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.URIish
import java.io.File
import java.io.InputStream
import java.util.Date

const val TOKEN = "test-token"

/** Remotes on this machine over file:// URLs, standing in for GitHub. They never get a token. */
internal object LocalRemotes : RemotePolicy {
    override fun allowed(url: String): URIish {
        if (!url.startsWith("file:///")) throw GitGateException("Only local test remotes.")
        return URIish(url)
    }

    override fun remoteFor(gitDir: File): String? = null

    override fun mayAuthenticate(uri: URIish): Boolean = false
}

/**
 * One test's world: "GitHub" repositories on disk, the phone's repos folder, worktrees as rooms
 * make them, and the system git with no user or system config.
 */
internal class GitWorld(val root: File) {
    val github = File(root, "github")
    val repos = File(root, "repos")
    val work = File(root, "work")
    val state = File(root, "gate-state")
    private val home = File(root, "home")
    private var elsewhere = 0

    init {
        listOf(github, repos, work, home).forEach(File::mkdirs)
    }

    fun gate(scanner: CheckPost = CheckPost(), remotes: RemotePolicy = LocalRemotes) =
        JGitGate(repos, state, remotes, scanner, Dispatchers.IO)

    fun url(remote: File) = "file://" + remote.absolutePath

    fun bare(name: String) = File(repos, "$name.git")

    fun git(dir: File, vararg args: String): String {
        val process = command(dir, *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$output" }
        return output.trim()
    }

    /** The system git in [dir], with no user or system config. */
    fun command(dir: File, vararg args: String): ProcessBuilder =
        ProcessBuilder(listOf("git") + args).directory(dir).apply {
            val env = environment()
            env.keys.filter { it.startsWith("GIT_") }.forEach { env.remove(it) }
            env["HOME"] = home.path
            env["XDG_CONFIG_HOME"] = File(home, ".config").path
            env["GIT_CONFIG_NOSYSTEM"] = "1"
            env["GIT_CONFIG_GLOBAL"] = "/dev/null"
            env["GIT_TERMINAL_PROMPT"] = "0"
            env["GIT_AUTHOR_NAME"] = "Test"
            env["GIT_AUTHOR_EMAIL"] = "test@example.com"
            env["GIT_COMMITTER_NAME"] = "Test"
            env["GIT_COMMITTER_EMAIL"] = "test@example.com"
        }

    /** The object a revision names, or null when there is none. */
    fun revParse(dir: File, revision: String): String? =
        runCatching { git(dir, "rev-parse", "--verify", "--quiet", "$revision^{object}") }.getOrNull()

    fun emptyRemote(name: String): File {
        val remote = File(github, "$name.git")
        git(github, "init", "--quiet", "--bare", "--initial-branch=main", remote.path)
        return remote
    }

    /** A "GitHub" repository with one commit on main. */
    fun githubRepo(name: String, files: Map<String, String> = mapOf("README.md" to "hello\n")): File {
        val remote = emptyRemote(name)
        commitToRemote(remote, "main", files, "Initial commit")
        return remote
    }

    /** A commit made somewhere else and pushed to [branch] on [remote]; returns its ID. */
    fun commitToRemote(remote: File, branch: String, files: Map<String, String>, message: String): String {
        val clone = File(root, "elsewhere-${elsewhere++}")
        git(root, "clone", "--quiet", url(remote), clone.path)
        if (revParse(clone, "refs/remotes/origin/$branch") != null) {
            git(clone, "checkout", "--quiet", branch)
        } else {
            git(clone, "checkout", "--quiet", "-b", branch)
        }
        return commit(clone, files, message).also { git(clone, "push", "--quiet", "origin", "HEAD:refs/heads/$branch") }
    }

    /** A worktree of [bare] on a new [branch], as a room makes one for a session. */
    fun worktree(bare: File, name: String, branch: String, from: String = "main"): File {
        val dir = File(work, name)
        git(bare, "worktree", "add", "--quiet", "-b", branch, dir.path, from)
        return dir
    }

    fun commit(dir: File, files: Map<String, String>, message: String = "Change"): String {
        files.forEach { (path, text) -> File(dir, path).apply { parentFile?.mkdirs() }.writeText(text) }
        git(dir, "add", "--all")
        git(dir, "commit", "--quiet", "-m", message)
        return git(dir, "rev-parse", "HEAD")
    }
}

/** Builds commits straight into a new bare repo with JGit: each commit's tree is exactly the files given. */
internal class TestCommits(dir: File) : AutoCloseable {
    val repo: Repository = FileRepositoryBuilder().setGitDir(dir).setBare().build().apply { create(true) }
    private val inserter = repo.newObjectInserter()
    private var tick = 0L

    fun blob(bytes: ByteArray): ObjectId = inserter.insert(Constants.OBJ_BLOB, bytes).also { inserter.flush() }

    /** A blob of [size] zero bytes, streamed rather than held in memory. */
    fun zeros(size: Long): ObjectId = inserter.insert(Constants.OBJ_BLOB, size, Zeros(size)).also { inserter.flush() }

    /**
     * [files] maps a path to its text, its bytes or a blob ID; [modes] overrides a file's mode (a
     * gitlink's ID is taken as is). [author] replaces the test identity.
     */
    fun commit(
        files: Map<String, Any>,
        vararg parents: ObjectId,
        message: String = "Change",
        modes: Map<String, FileMode> = emptyMap(),
        author: PersonIdent? = null,
    ): ObjectId {
        val index = DirCache.newInCore()
        val builder = index.builder()
        files.toSortedMap().forEach { (path, content) ->
            val entry = DirCacheEntry(path)
            entry.fileMode = modes[path] ?: FileMode.REGULAR_FILE
            entry.setObjectId(
                when (content) {
                    is String -> blob(content.toByteArray())
                    is ByteArray -> blob(content)
                    is ObjectId -> content
                    else -> error("Unsupported content for $path")
                },
            )
            builder.add(entry)
        }
        builder.finish()
        // Distinct times keep the order of commits obvious.
        val ident = PersonIdent(author ?: PersonIdent("Test", "test@example.com"), Date(1_700_000_000_000L + 1000L * tick++))
        val commit = CommitBuilder().apply {
            setTreeId(index.writeTree(inserter))
            setParentIds(*parents)
            this.author = ident
            committer = ident
            this.message = message
        }
        return inserter.insert(commit).also { inserter.flush() }
    }

    /** A commit object written byte for byte, as an agent could write one with any header in it. */
    fun rawCommit(text: String): ObjectId = inserter.insert(Constants.OBJ_COMMIT, text.toByteArray()).also { inserter.flush() }

    override fun close() {
        inserter.close()
        repo.close()
    }

    private class Zeros(private var left: Long) : InputStream() {
        override fun read(): Int = if (left-- > 0) 0 else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left <= 0) return -1
            val count = minOf(length.toLong(), left).toInt()
            buffer.fill(0, offset, offset + count)
            left -= count
            return count
        }
    }
}

/** A fake credential of the given shape, assembled at run time so no token-shaped text sits in the source. */
internal fun fake(prefix: String, length: Int, alphabet: String = "aB3dE5gH7jK9mN1pQrS2tU4"): String =
    prefix + (0 until length).map { alphabet[it % alphabet.length] }.joinToString("")
