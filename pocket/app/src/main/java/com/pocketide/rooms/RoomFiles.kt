package com.pocketide.rooms

import com.pocketide.core.AgentFiles
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

/**
 * Reads and writes under a folder that programs inside Linux can change (a room's home, or the
 * computer's /opt). No symbolic link is ever followed: a link planted there must not lead the
 * app to read or overwrite anything else, such as its own tokens or another room's files. A
 * write goes to a temporary file that is renamed over the target, so a link at the target is
 * replaced, never written through.
 *
 * With [guardSecrets] (a room's home) the app refuses to touch credential files at all: those
 * are for the agent alone (AgentFiles.SECRET).
 */
internal class RoomFiles(val base: File, private val guardSecrets: Boolean) {

    /** The text of a regular file of at most [limit] bytes, or null. */
    fun read(relative: String, limit: Long = MAX_READ): String? {
        checkAllowed(relative)
        val path = existing(relative) ?: return null
        val attributes = attributes(path) ?: return null
        if (!attributes.isRegularFile || attributes.size() > limit) return null
        return try {
            String(Files.readAllBytes(path), Charsets.UTF_8)
        } catch (unreadable: IOException) {
            null
        }
    }

    /** True when [relative] is a regular file (not a link to one). */
    fun isFile(relative: String): Boolean = existing(relative)?.let { attributes(it)?.isRegularFile } == true

    fun isDirectory(relative: String): Boolean = existing(relative)?.let { attributes(it)?.isDirectory } == true

    /** Last change of a regular file, without reading it; null when it is not one. */
    fun lastModified(relative: String): Long? =
        existing(relative)?.let { attributes(it) }?.takeIf { it.isRegularFile }?.lastModifiedTime()?.toMillis()

    /** Writes [bytes] unless the file already holds exactly them. Returns true when it wrote. */
    fun write(relative: String, bytes: ByteArray, executable: Boolean = false): Boolean {
        checkAllowed(relative)
        val parent = directory(parentOf(relative), create = true)
            ?: throw IOException("A folder on the way to $relative is a link or a file.")
        val target = parent.resolve(nameOf(relative))
        if (sameContent(target, bytes)) return false
        val temporary = Files.createTempFile(parent, ".pocketide-", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.setPosixFilePermissions(temporary, if (executable) EXECUTABLE else PRIVATE)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return true
    }

    fun write(relative: String, text: String, executable: Boolean = false): Boolean =
        write(relative, text.toByteArray(Charsets.UTF_8), executable)

    /** Deletes one file (a link is removed itself, never what it points to). */
    fun delete(relative: String) {
        checkAllowed(relative)
        val parent = directory(parentOf(relative), create = false) ?: return
        Files.deleteIfExists(parent.resolve(nameOf(relative)))
    }

    /** Moves a file aside (to `<name>.pocketide-broken`) so a fresh one can be written. */
    fun setAside(relative: String) {
        checkAllowed(relative)
        val parent = directory(parentOf(relative), create = false) ?: return
        val target = parent.resolve(nameOf(relative))
        if (attributes(target)?.isRegularFile != true) return
        Files.move(target, parent.resolve(nameOf(relative) + ".pocketide-broken"), StandardCopyOption.REPLACE_EXISTING)
    }

    /** The real folder at [relative], created (owner-only) when missing and [create]; null if a link is in the way. */
    fun directory(relative: String, create: Boolean): Path? {
        var current = base.toPath()
        if (attributes(current)?.isDirectory != true) {
            if (!create) return null
            Files.createDirectories(current)
        }
        for (name in components(relative)) {
            val next = current.resolve(name)
            val attributes = attributes(next)
            when {
                attributes == null -> {
                    if (!create) return null
                    try {
                        Files.createDirectory(next)
                        Files.setPosixFilePermissions(next, PRIVATE_DIR)
                    } catch (raced: FileAlreadyExistsException) {
                        if (attributes(next)?.isDirectory != true) return null
                    }
                }
                !attributes.isDirectory -> return null
            }
            current = next
        }
        return current
    }

    /** Sets owner-only modes: 0700 for folders, 0600 for files. Links are left alone. */
    fun makePrivate(relative: String) {
        val path = existing(relative) ?: return
        val attributes = attributes(path) ?: return
        when {
            attributes.isDirectory -> Files.setPosixFilePermissions(path, PRIVATE_DIR)
            attributes.isRegularFile -> Files.setPosixFilePermissions(path, PRIVATE)
        }
    }

    /**
     * Relative paths of regular files under [relative], at most [depth] folders down, skipping
     * folders named in [skip] (relative to the base). Links are listed as nothing.
     */
    fun files(relative: String, depth: Int, skip: Set<String> = emptySet()): List<String> {
        val start = directory(relative, create = false) ?: return emptyList()
        val found = mutableListOf<String>()
        fun walk(dir: Path, prefix: String, level: Int) {
            val children = try {
                Files.newDirectoryStream(dir).use { stream -> stream.map { it }.sortedBy { it.fileName.toString() } }
            } catch (unreadable: IOException) {
                return
            }
            for (child in children) {
                val name = prefix + child.fileName.toString()
                val attributes = attributes(child) ?: continue
                when {
                    attributes.isRegularFile -> found += name
                    attributes.isDirectory && level < depth && name !in skip -> walk(child, "$name/", level + 1)
                }
            }
        }
        walk(start, if (relative.isEmpty()) "" else "${components(relative).joinToString("/")}/", 0)
        return found
    }

    /** Names of the real folders (not links) directly under [relative]. */
    fun folders(relative: String): List<String> {
        val dir = directory(relative, create = false) ?: return emptyList()
        return try {
            Files.newDirectoryStream(dir).use { stream ->
                stream.filter { attributes(it)?.isDirectory == true }.map { it.fileName.toString() }.sorted()
            }
        } catch (unreadable: IOException) {
            emptyList()
        }
    }

    private fun existing(relative: String): Path? {
        val names = components(relative)
        if (names.isEmpty()) return base.toPath()
        val parent = directory(names.dropLast(1).joinToString("/"), create = false) ?: return null
        val path = parent.resolve(names.last())
        return path.takeIf { attributes(it) != null }
    }

    private fun checkAllowed(relative: String) {
        require(components(relative).isNotEmpty()) { "No file named." }
        require(!guardSecrets || !AgentFiles.isSecret(relative)) { "$relative is a sign-in file; PocketIDE never touches those." }
    }

    private fun sameContent(target: Path, bytes: ByteArray): Boolean {
        val attributes = attributes(target) ?: return false
        if (!attributes.isRegularFile || attributes.size() != bytes.size.toLong()) return false
        return try {
            Files.readAllBytes(target).contentEquals(bytes)
        } catch (unreadable: IOException) {
            false
        }
    }

    companion object {
        const val MAX_READ = 4L * 1024 * 1024
        private val PRIVATE = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        private val PRIVATE_DIR = PRIVATE + PosixFilePermission.OWNER_EXECUTE
        private val EXECUTABLE = PRIVATE_DIR + setOf(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE,
        )

        fun components(relative: String): List<String> {
            val names = relative.split('/').filter { it.isNotEmpty() && it != "." }
            require(names.none { it == ".." || it.contains('\u0000') }) { "\"$relative\" leaves its folder." }
            return names
        }

        private fun parentOf(relative: String) = components(relative).dropLast(1).joinToString("/")

        private fun nameOf(relative: String) = components(relative).last()

        /**
         * Deletes [root] and everything under it. Links are removed, never followed; folders are
         * made writable first, because tools inside Linux leave read-only folders behind.
         */
        fun deleteTree(root: File) {
            val start = root.toPath()
            if (attributes(start) == null) return
            Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    dir.toFile().setWritable(true, true)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }

        fun attributes(path: Path): BasicFileAttributes? = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (missing: IOException) {
            null
        }
    }
}
