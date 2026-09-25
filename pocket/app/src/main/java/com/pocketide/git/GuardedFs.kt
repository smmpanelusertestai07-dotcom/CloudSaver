package com.pocketide.git

import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.FS_POSIX
import org.eclipse.jgit.util.ProcessResult
import java.io.File
import java.io.OutputStream

/**
 * JGit's file system for one bare repo that Linux can write.
 *
 * - Hooks never run, whatever `hooks/` or `core.hooksPath` hold: on this side a hook would run as
 *   the app itself, outside Linux.
 * - The repo's config is read from [privateConfig], a copy Linux cannot see. JGit re-reads the
 *   config file whenever it changes, so reading the shared file would let Linux swap in
 *   `http.sslVerify`, `http.extraHeader` or a cookie file in the middle of a push.
 * - Reflogs are looked for in [shadow], where there are none, so JGit writes none. JGit appends
 *   to any log file that exists, and a log file Linux swapped for a link while a step runs would
 *   send the lines into whatever app file the link names.
 * - Alternates lead into [shadow] as well, so JGit never reads another object store: not one
 *   outside the repo, not one chained behind an entry inside it, and not a repository nested in
 *   it, which JGit would open without this file system. The gate's repos have no alternates.
 */
internal class GuardedFs : FS_POSIX {
    private val gitDir: File
    private val objectsDir: File
    private val privateConfig: File
    private val shadow: File

    /** [shadow] is app-private; only JGit's empty reflog folders are ever made in it. */
    constructor(gitDir: File, privateConfig: File, shadow: File) : super() {
        this.gitDir = gitDir
        this.objectsDir = File(gitDir, Constants.OBJECTS)
        this.privateConfig = privateConfig
        this.shadow = shadow
    }

    private constructor(source: GuardedFs) : super(source) {
        gitDir = source.gitDir
        objectsDir = source.objectsDir
        privateConfig = source.privateConfig
        shadow = source.shadow
    }

    override fun newInstance(): FS = GuardedFs(this)

    override fun findHook(repository: Repository?, hookName: String?): File? = null

    override fun runHookIfPresent(repository: Repository?, hookName: String?, args: Array<String>?): ProcessResult =
        NOT_RUN

    override fun runHookIfPresent(
        repository: Repository?,
        hookName: String?,
        args: Array<String>?,
        outRedirect: OutputStream?,
        errRedirect: OutputStream?,
        stdinArgs: String?,
    ): ProcessResult = NOT_RUN

    override fun resolve(dir: File?, name: String?): File {
        val parent = dir?.absoluteFile
        return when {
            parent == gitDir && name == Constants.CONFIG -> privateConfig
            parent == gitDir && (name == Constants.LOGS || name == LOG_REFS) -> File(shadow, name)
            // Only alternates are resolved against the objects folder.
            parent == objectsDir -> File(shadow, Constants.OBJECTS)
            else -> super.resolve(dir, name)
        }
    }

    private companion object {
        val NOT_RUN = ProcessResult(ProcessResult.Status.NOT_PRESENT)

        /** How JGit names the folder of branch and tag reflogs. */
        const val LOG_REFS = "${Constants.LOGS}/${Constants.R_REFS}"
    }
}
