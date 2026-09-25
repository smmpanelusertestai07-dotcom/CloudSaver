package com.pocketide.git

import com.pocketide.AppGraph
import com.pocketide.builds.TemplateCatalog
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import java.io.File
import java.io.IOException

/**
 * The bare repos live in [com.pocketide.core.AppDirs.repos], which every room in Linux can
 * write; the gate's own records live in no_backup, which no room can see.
 */
fun createGitGate(graph: AppGraph): GitGate {
    val templates by lazy { templateBlobs(graph) }
    return JGitGate(
        reposRoot = graph.dirs.repos,
        stateDir = File(graph.context.noBackupFilesDir, "git-gate"),
        scanner = CheckPost(templates = { templates }),
    )
}

/**
 * The build templates as they sit in the APK, keyed by the path "Add template" writes them to.
 * The APK is out of Linux's reach, so these are the only workflow contents trusted without a
 * diff card. A template that cannot be read is simply not trusted.
 */
private fun templateBlobs(graph: AppGraph): Map<String, ObjectId> {
    val ids = ObjectInserter.Formatter()
    return TemplateCatalog.all.mapNotNull { template ->
        val bytes = try {
            graph.context.assets.open(TemplateCatalog.assetPath(template)).use { it.readBytes() }
        } catch (_: IOException) {
            return@mapNotNull null
        }
        TemplateCatalog.repoPath(template) to ids.idFor(Constants.OBJ_BLOB, bytes)
    }.toMap()
}
