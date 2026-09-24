package com.pocketide.git

import com.pocketide.AppGraph
import java.io.File

/**
 * The bare repos live in [com.pocketide.core.AppDirs.repos], which every room in Linux can
 * write; the gate's own records live in no_backup, which no room can see.
 */
fun createGitGate(graph: AppGraph): GitGate = JGitGate(
    reposRoot = graph.dirs.repos,
    stateDir = File(graph.context.noBackupFilesDir, "git-gate"),
)
