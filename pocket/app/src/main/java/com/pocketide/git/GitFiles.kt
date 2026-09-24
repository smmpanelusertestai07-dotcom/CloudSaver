package com.pocketide.git

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

// File helpers for directories Linux can write: nothing here follows a symlink Linux may have
// planted.

/** Replaces [target] in one step. A symlink at [target] is replaced, never followed. */
internal fun writeAtomically(target: File, text: String) {
    val directory = target.absoluteFile.parentOrRoot()
    directory.mkdirs()
    val temporary = Files.createTempFile(directory.toPath(), ".${target.name}.", ".tmp")
    try {
        Files.write(temporary, text.toByteArray(Charsets.UTF_8))
        Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

/** Deletes [root] and everything under it; symlinks are removed, never followed. */
internal fun deleteTree(root: File) {
    val start = root.toPath()
    if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

/** Whether this path, with every symlink resolved, lies inside [dir] (itself fully resolved). */
internal fun File.isInside(dir: File): Boolean = try {
    canonicalFile.toPath().startsWith(dir.toPath())
} catch (e: IOException) {
    false
}

/** The directory holding this absolute path ("/" for "/" itself). */
internal fun File.parentOrRoot(): File = parentFile ?: this

internal fun File.isLink(): Boolean = Files.isSymbolicLink(toPath())

/** Whether anything under this directory, walked without following links, is a symlink. */
internal fun File.containsLink(): Boolean {
    val start = toPath()
    if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) return false
    var found = false
    Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            found = attrs.isSymbolicLink
            return if (found) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
        }
    })
    return found
}

internal fun File.isRealDirectory(): Boolean = Files.isDirectory(toPath(), LinkOption.NOFOLLOW_LINKS)

internal fun File.isEmptyDirectory(): Boolean =
    isRealDirectory() && Files.newDirectoryStream(toPath()).use { !it.iterator().hasNext() }

/** The text of a small regular file, or null when it is missing, a link or larger than [limit]. */
internal fun File.readSmallText(limit: Long): String? {
    val path = toPath()
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit) return null
    return String(Files.readAllBytes(path), Charsets.UTF_8)
}
