package com.pocketide.ui.manage

import com.pocketide.rooms.RoomFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** One entry of a folder, as it is on disk: a link is reported as a link, never followed. */
data class DiskEntry(val name: String, val kind: Kind, val bytes: Long) {
    enum class Kind { FOLDER, FILE, LINK, OTHER }
}

/**
 * Reads folders that Linux programs write (a room's home, a session's worktree) without ever
 * following a symbolic link: a link planted there must never make the app read a file outside.
 */
object LinkFreeFiles {
    const val NOT_PLAIN = "This is not a plain file (it goes through a link), so it is not opened."
    const val NOT_TEXT = "This is not a plain text file."

    /** True when [file] and every directory between it and [root] is a real directory or file, not a link. */
    fun isSafe(root: File, file: File): Boolean {
        val base = root.toPath().toAbsolutePath().normalize()
        val target = file.toPath().toAbsolutePath().normalize()
        if (!target.startsWith(base) || target == base) return false
        if (Files.isSymbolicLink(base)) return false
        var current: Path = base
        for (part in base.relativize(target)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) return false
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return true
        }
        return true
    }

    /**
     * The text of [file], at most [limit] bytes ([tooLarge] is the reason given otherwise).
     * A file that does not exist reads as empty; a binary file is refused.
     *
     * The checks by path only give a clear reason. What is read is opened folder by folder from
     * [root], each one refused if it is a link ([RoomFiles]): opening by path would follow a folder
     * swapped for a link just then, and a check afterwards cannot tell once it is swapped back.
     */
    @Throws(IOException::class)
    fun readText(root: File, file: File, limit: Long, tooLarge: String): String =
        readText(root, file, limit, tooLarge, beforeOpen = {}, afterOpen = {})

    /** [readText], running [beforeOpen] after the checks and [afterOpen] once the file is open: tests swap folders there. */
    @Throws(IOException::class)
    internal fun readText(root: File, file: File, limit: Long, tooLarge: String, beforeOpen: () -> Unit, afterOpen: () -> Unit): String {
        if (!isSafe(root, file)) throw IOException(NOT_PLAIN)
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return ""
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IOException(NOT_TEXT)
        val relative = root.toPath().toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString()
        beforeOpen()
        val channel = RoomFiles(root, guardSecrets = false).open(relative)
        afterOpen()
        if (channel == null) {
            // Gone since the check reads as empty; anything else there now was a link or not a file.
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw IOException(NOT_PLAIN)
            return ""
        }
        val bytes = channel.use { readAtMost(it, limit, tooLarge) }
        if (bytes.any { it == 0.toByte() }) throw IOException(NOT_TEXT)
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * The entries of [folder] (which is [root] itself or a real folder inside it), at most [max].
     * Returns null when the folder is missing or reached through a link.
     */
    fun list(root: File, folder: File, max: Int): List<DiskEntry>? {
        val path = folder.toPath()
        val reachable = if (path.toAbsolutePath().normalize() == root.toPath().toAbsolutePath().normalize()) {
            !Files.isSymbolicLink(path)
        } else {
            isSafe(root, folder)
        }
        if (!reachable || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return null
        return try {
            Files.newDirectoryStream(path).use { stream ->
                stream.asSequence().take(max).map { entry(it) }.toList()
            }
        } catch (_: IOException) {
            null
        }
    }

    /** The file's folder, with every link resolved, is still inside the real [root]. */
    fun insideRoot(root: File, file: File): Boolean {
        val parent = file.toPath().toAbsolutePath().normalize().parent ?: return false
        return try {
            parent.toRealPath().startsWith(root.toPath().toRealPath())
        } catch (_: IOException) {
            false
        }
    }

    private fun entry(path: Path): DiskEntry {
        val name = path.fileName.toString()
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            return DiskEntry(name, DiskEntry.Kind.OTHER, 0)
        }
        val kind = when {
            attrs.isSymbolicLink -> DiskEntry.Kind.LINK
            attrs.isDirectory -> DiskEntry.Kind.FOLDER
            attrs.isRegularFile -> DiskEntry.Kind.FILE
            else -> DiskEntry.Kind.OTHER
        }
        return DiskEntry(name, kind, if (kind == DiskEntry.Kind.FILE) attrs.size() else 0)
    }

    private fun readAtMost(channel: SeekableByteChannel, limit: Long, tooLarge: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(8192)
        while (true) {
            buffer.clear()
            val n = channel.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw IOException(tooLarge)
            out.write(buffer.array(), 0, n)
        }
        return out.toByteArray()
    }
}
