package com.pocketide.git

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Opens the bare repos under [reposRoot], which every room in Linux can write. Nothing inside
 * them is trusted: a repo must sit directly in [reposRoot] with no symlink on the way, its
 * objects must be its own, and before every use its config is replaced with [canonicalConfig]
 * (Linux git reads that file) while JGit reads a private copy of the same text.
 */
internal class BareRepos(reposRoot: File, private val states: RemoteStates) {
    // Resolved once. The root's own name is left unresolved, so a symlink put in its place later
    // no longer matches.
    private val root = reposRoot.absoluteFile.let { File(it.parentOrRoot().canonicalFile, it.name) }

    /** Where [bareRepo] lives, fully resolved; it need not exist yet. */
    fun placed(bareRepo: File): File {
        val absolute = bareRepo.absoluteFile
        val dir = File(absolute.parentOrRoot().canonicalFile, absolute.name)
        if (dir.parentFile != root || repoName(dir.name) == null) {
            throw GitGateException(GitMessages.UNSAFE_COPY)
        }
        if (Files.isSymbolicLink(dir.toPath())) throw GitGateException(GitMessages.UNSAFE_COPY)
        return dir
    }

    /** One file per repo, whichever of its names ([repoName]) [placedDir] has; for locking. */
    fun identity(placedDir: File): File = File(root, repoName(placedDir.name) ?: placedDir.name)

    /** The resolved directory of an existing bare repo that passes every check. */
    fun existing(bareRepo: File): File {
        val dir = placed(bareRepo)
        if (!dir.isRealDirectory()) throw GitGateException(GitMessages.NOT_ON_PHONE)
        requireNoLinkedDirectories(dir)
        requireOwnObjects(dir)
        return dir
    }

    // JGit creates files in these directories; one swapped for a link would send ref, log or
    // pack files into whatever app folder the link points at. A real repo has no links there.
    private fun requireNoLinkedDirectories(gitDir: File) {
        val objects = File(gitDir, Constants.OBJECTS)
        val refsAndLogs = listOf(File(gitDir, "refs"), File(gitDir, "logs"))
        val linked = objects.isLink() || objects.listFiles().orEmpty().any(File::isLink) ||
            refsAndLogs.any { it.isLink() || it.containsLink() }
        if (linked) throw GitGateException(GitMessages.UNSAFE_COPY)
    }

    /**
     * Writes the canonical config for Linux git and the private copy JGit reads, and drops a
     * `shallow` list: the gate's clones are never shallow, and one planted there would cut
     * history short for git on both sides.
     */
    fun resetConfig(gitDir: File, url: String) {
        val text = canonicalConfig(url)
        writeAtomically(states.privateConfig(gitDir), text)
        writeAtomically(File(gitDir, Constants.CONFIG), text)
        Files.deleteIfExists(File(gitDir, Constants.SHALLOW).toPath())
    }

    fun open(gitDir: File): Repository = FileRepositoryBuilder()
        .setGitDir(gitDir)
        .setBare()
        .setMustExist(true)
        .setFS(GuardedFs(gitDir, states.privateConfig(gitDir)))
        .build()

    /**
     * A new empty bare repo in [staging] whose config is the canonical one for [url]. It reads the
     * private config of [target], where it will be moved once complete.
     */
    fun create(staging: File, target: File, url: String): Repository {
        val privateConfig = states.privateConfig(target)
        val repo = FileRepositoryBuilder()
            .setGitDir(staging)
            .setBare()
            .setFS(GuardedFs(staging, privateConfig))
            .build()
        try {
            repo.create(true)
            writeAtomically(privateConfig, canonicalConfig(url))
            writeAtomically(File(staging, Constants.CONFIG), canonicalConfig(url))
        } catch (e: Exception) {
            repo.close()
            throw e
        }
        return repo
    }

    private fun requireOwnObjects(gitDir: File) {
        val alternates = File(gitDir, "${Constants.OBJECTS}/${Constants.INFO_ALTERNATES}")
        if (!Files.exists(alternates.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        val listed = alternates.readSmallText(MAX_ALTERNATES_BYTES)
            ?: throw GitGateException(GitMessages.FOREIGN_OBJECTS)
        val objects = File(gitDir, Constants.OBJECTS)
        listed.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { entry ->
                val target = File(entry).takeIf(File::isAbsolute) ?: File(objects, entry)
                if (!target.isInside(gitDir)) throw GitGateException(GitMessages.FOREIGN_OBJECTS)
            }
    }

    private companion object {
        const val MAX_ALTERNATES_BYTES = 64L * 1024
    }
}

/**
 * The only config a bare repo gets: no URL rewriting, extra headers, credential helpers, proxies,
 * TLS switches or hooks path survive a rewrite. Worktrees keep their data in `worktrees/`, not
 * here. git's clean-up never prunes them: each room sees only its own worktrees, so gc in one
 * room would otherwise prune the others'.
 */
internal fun canonicalConfig(url: String): String = Config().apply {
    setInt("core", null, "repositoryformatversion", 0)
    setBoolean("core", null, "filemode", true)
    setBoolean("core", null, "bare", true)
    setBoolean("core", null, "logallrefupdates", false)
    setString("remote", "origin", "url", url)
    setString("remote", "origin", "fetch", TRACKING_SPEC)
    setString("gc", null, "worktreePruneExpire", "never")
}.toText()

/**
 * The name a bare repo in the repos folder is known by, or null when [dirName] is not one. The
 * projects module clones into `.<name>.partial` and renames it when complete, so both names are
 * the same repo to the gate: one record, one lock.
 */
internal fun repoName(dirName: String): String? {
    val name = if (dirName.startsWith(".") && dirName.endsWith(PARTIAL_SUFFIX)) {
        dirName.substring(1, dirName.length - PARTIAL_SUFFIX.length)
    } else {
        dirName
    }
    val named = name.length > Constants.DOT_GIT_EXT.length && name.endsWith(Constants.DOT_GIT_EXT)
    return name.takeIf { named && !it.startsWith(".") }
}

private const val PARTIAL_SUFFIX = ".partial"

internal const val TRACKING_PREFIX = "refs/remotes/origin/"
internal const val TRACKING_SPEC = "+refs/heads/*:$TRACKING_PREFIX*"
