package com.pocketide.sync

import com.pocketide.core.AppDirs
import org.eclipse.jgit.ignore.IgnoreNode
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The phone's own clean-up (§6.6). It never counts as a delete in Drive, never follows a symbolic
 * link (pnpm and `npm link` put links to source folders inside node_modules), and removes a
 * project cache only when git ignores it, so nothing that belongs to the project is lost.
 */
internal class LocalCleaner(private val dirs: AppDirs) {

    /** Temp files and logs older than [maxAgeMs]. Room temp folders are left alone while rooms run. */
    fun oldFiles(now: Long, maxAgeMs: Long, roomsRunning: Boolean): Long {
        val roots = mutableListOf(dirs.prootTmp, dirs.logs, dirs.downloads, dirs.share, dirs.apk)
        if (!roomsRunning) roots += dirs.rooms.listFiles()?.map { File(it, "tmp") }.orEmpty()
        return roots.sumOf { removeOlderThan(it, now - maxAgeMs) }
    }

    /** Build outputs beyond the newest [keep] per project. */
    fun builds(keep: Int = KEEP_BUILDS): Long {
        val projects = dirs.builds.listFiles()?.filter { isRealDirectory(it) }.orEmpty()
        return projects.sumOf { project ->
            project.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(keep).sumOf { deleteTree(it) }
        }
    }

    /**
     * Rebuildable caches (node_modules, build, .gradle, target, .next, .dart_tool, Pods, .venv,
     * __pycache__) in the worktrees of projects without activity for [days] days. [lastActivity]
     * gives a project folder's last agent work or commit; [force] cleans every idle worktree
     * (the phone is at 90 % of its limit).
     */
    fun caches(now: Long, days: Int, lastActivity: (projectDir: String) -> Long?, active: Set<String>, force: Boolean): Long {
        var freed = 0L
        for (agent in dirs.work.listFiles()?.filter { isRealDirectory(it) }.orEmpty()) {
            for (project in agent.listFiles()?.filter { isRealDirectory(it) }.orEmpty()) {
                val activity = lastActivity(project.name) ?: project.lastModified()
                if (!force && now - activity < Durations.days(days)) continue
                for (worktree in project.listFiles()?.filter { isRealDirectory(it) && it.name != Scanner.MEDIA && it.name !in active }.orEmpty()) {
                    freed += cleanWorktree(worktree)
                }
            }
        }
        return freed
    }

    private fun cleanWorktree(worktree: File): Long {
        val found = ArrayList<Path>()
        val root = worktree.toPath()
        try {
            Files.walkFileTree(root, emptySet(), MAX_DEPTH, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.fileName?.toString().orEmpty()
                    return when {
                        dir != root && (attrs.isSymbolicLink || name == ".git") -> FileVisitResult.SKIP_SUBTREE
                        dir != root && name in CACHE_NAMES -> {
                            found.add(dir)
                            FileVisitResult.SKIP_SUBTREE
                        }
                        else -> FileVisitResult.CONTINUE
                    }
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (_: IOException) {
            return 0
        }
        return found.sumOf { dir ->
            val rel = root.relativize(dir).toString().replace(File.separatorChar, '/')
            if (GitIgnore.ignored(worktree, rel)) deleteTree(dir.toFile()) else 0L
        }
    }

    private fun removeOlderThan(root: File, cutoff: Long): Long {
        if (!isRealDirectory(root)) return 0
        var freed = 0L
        val start = root.toPath()
        try {
            Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.lastModifiedTime().toMillis() < cutoff && Files.deleteIfExists(file)) freed += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (dir != start) dir.toFile().delete()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (_: IOException) {
            // Partly cleaned; the next run continues.
        }
        return freed
    }

    companion object {
        const val KEEP_BUILDS = 3
        private const val MAX_DEPTH = 8
        val CACHE_NAMES = setOf("node_modules", "build", ".gradle", "target", ".next", ".dart_tool", "Pods", ".venv", "__pycache__")
    }
}

/** Whether git would ignore a folder of a worktree, from its .gitignore files (deepest first). */
internal object GitIgnore {
    fun ignored(worktree: File, relativeDir: String): Boolean {
        val parts = relativeDir.split('/').filter { it.isNotEmpty() }
        for (end in 1..parts.size) {
            if (decide(worktree, parts.subList(0, end)) == true) return true
        }
        return false
    }

    private fun decide(worktree: File, parts: List<String>): Boolean? {
        for (depth in parts.size - 1 downTo 0) {
            val dir = if (depth == 0) worktree else File(worktree, parts.subList(0, depth).joinToString("/"))
            val file = File(dir, ".gitignore")
            if (factsOf(file) == null) continue
            val node = IgnoreNode()
            try {
                file.inputStream().use { node.parse(it) }
            } catch (_: IOException) {
                continue
            }
            node.checkIgnored(parts.subList(depth, parts.size).joinToString("/"), true)?.let { return it }
        }
        return null
    }
}

/** Deletes a file or a folder without following symbolic links; returns the bytes freed. */
internal fun deleteTree(file: File): Long {
    val path = file.toPath()
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path)) {
        val size = factsOf(file)?.size ?: 0
        return if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) size else 0
    }
    var freed = 0L
    try {
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (runCatching { Files.deleteIfExists(f) }.getOrDefault(false)) freed += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                runCatching { Files.deleteIfExists(dir) }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(f: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
    } catch (_: IOException) {
        // Whatever could not be removed now is removed on the next clean-up.
    }
    return freed
}

/** Bytes under a folder, without following links. */
internal fun sizeOf(root: File): Long {
    if (!root.exists()) return 0
    var total = 0L
    try {
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) total += attrs.size()
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
    } catch (_: IOException) {
        // A partial total is still a useful estimate.
    }
    return total
}
