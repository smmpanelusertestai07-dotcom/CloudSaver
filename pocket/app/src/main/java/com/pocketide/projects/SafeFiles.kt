package com.pocketide.projects

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * File operations on folders that programs inside Linux can write to (worktrees, agents' homes).
 * Symbolic links there are never followed: a link planted inside a worktree must not make the
 * app read, count or delete anything outside it.
 */
internal object SafeFiles {

    fun isDirectory(file: File): Boolean = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    fun isFile(file: File): Boolean = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** Entries of a real folder (not a link to one), or none. */
    fun children(dir: File): List<File> =
        if (isDirectory(dir)) dir.listFiles()?.sortedBy { it.name }.orEmpty() else emptyList()

    /** Regular files under [dir], at most [depth] folders down, never through links. */
    fun files(dir: File, depth: Int = Int.MAX_VALUE): List<File> {
        if (!isDirectory(dir)) return emptyList()
        val found = ArrayList<File>()
        walk(dir.toPath(), depth) { path, attrs -> if (attrs.isRegularFile) found += path.toFile() }
        return found
    }

    /** Bytes of the regular files under [target] (or of [target] itself). */
    fun size(target: File): Long {
        if (!exists(target)) return 0
        var total = 0L
        walk(target.toPath(), Int.MAX_VALUE) { _, attrs -> if (attrs.isRegularFile) total += attrs.size() }
        return total
    }

    /**
     * Deletes [target] and everything under it; links are removed, never followed. Folders are made
     * writable first, because tools inside Linux leave read-only folders behind (module caches).
     * Returns true when nothing is left.
     */
    fun delete(target: File): Boolean {
        val root = target.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
        try {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    dir.toFile().setWritable(true, true)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    quietDelete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    quietDelete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    quietDelete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (unreadable: IOException) {
            return !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
        }
        return !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
    }

    private fun quietDelete(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (kept: IOException) {
            // Reported by the caller: the folder still exists afterwards.
        }
    }

    private fun walk(root: Path, depth: Int, visit: (Path, BasicFileAttributes) -> Unit) {
        try {
            Files.walkFileTree(root, emptySet(), depth, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    visit(dir, attrs)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    visit(file, attrs)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (unreadable: IOException) {
            // What was visited is what there is.
        }
    }
}
