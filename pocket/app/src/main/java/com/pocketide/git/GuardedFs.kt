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
 * - An alternate object directory outside the repo resolves to nothing, even if Linux rewrites
 *   `objects/info/alternates` after the gate checked it.
 */
internal class GuardedFs : FS_POSIX {
    private val gitDir: File
    private val objectsDir: File
    private val privateConfig: File

    constructor(gitDir: File, privateConfig: File) : super() {
        this.gitDir = gitDir
        this.objectsDir = File(gitDir, Constants.OBJECTS)
        this.privateConfig = privateConfig
    }

    private constructor(source: GuardedFs) : super(source) {
        gitDir = source.gitDir
        objectsDir = source.objectsDir
        privateConfig = source.privateConfig
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
        val resolved = super.resolve(dir, name)
        val parent = dir?.absoluteFile
        return when {
            parent == gitDir && name == Constants.CONFIG -> privateConfig
            parent == objectsDir && !resolved.isInside(gitDir) -> File(objectsDir, NO_ALTERNATE)
            else -> resolved
        }
    }

    private companion object {
        val NOT_RUN = ProcessResult(ProcessResult.Status.NOT_PRESENT)

        /** Never created, so JGit finds no objects there. */
        const val NO_ALTERNATE = "info/no-alternate"
    }
}
