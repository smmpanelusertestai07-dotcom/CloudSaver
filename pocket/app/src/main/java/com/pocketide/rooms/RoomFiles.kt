package com.pocketide.rooms

import com.pocketide.core.AgentFiles
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/**
 * Reads and writes under a folder that programs inside Linux can change (a room's home, or the
 * computer's /opt). No symbolic link is ever followed, not even one a program swaps in, or swaps
 * back and forth, while the app is at work: each folder on the way is opened from the one before
 * it and refused if it is a link, and every file is then opened, written, renamed or removed
 * relative to its folder's open handle (openat and its kin, through SecureDirectoryStream). So a
 * link planted anywhere under [base] can never lead the app to read or overwrite anything else,
 * such as its own tokens or another room's files. A write goes to a new temporary file that is
 * renamed over the target, so a link at the target is replaced, never written through.
 *
 * Where the platform cannot hold folders open like that, every step is checked by path instead:
 * no link at the file, and its folder still inside [base] once it is open. That refuses a link
 * that stays in place, though not one swapped in and out at just the wrong moment.
 *
 * With [guardSecrets] (a room's home) the app refuses to touch credential files at all: those
 * are for the agent alone (AgentFiles.SECRET). Whether one is there may still be asked.
 */
internal class RoomFiles(
    val base: File,
    private val guardSecrets: Boolean,
    /** Tests turn this off to check the path-checked way; the app always holds folders when it can. */
    private val holdFolders: Boolean = true,
) {

    /** The text of a regular file of at most [limit] bytes, or null. */
    fun read(relative: String, limit: Long = MAX_READ): String? {
        checkAllowed(relative)
        return inFolderOf(relative, create = false) { folder, name -> folder.readBytes(name, limit) }?.toString(Charsets.UTF_8)
    }

    /**
     * The regular file at [relative], open for reading (the caller closes it), or null. What is
     * read is that file, whatever is swapped in at its name or on the way afterwards.
     */
    fun open(relative: String): SeekableByteChannel? {
        checkAllowed(relative)
        return inFolderOf(relative, create = false) { folder, name -> folder.open(name) }
    }

    /** True when [relative] is a regular file (not a link to one). */
    fun isFile(relative: String): Boolean = attributesOf(relative)?.isRegularFile == true

    fun isDirectory(relative: String): Boolean = attributesOf(relative)?.isDirectory == true

    /** Last change of a regular file, without reading it; null when it is not one. */
    fun lastModified(relative: String): Long? =
        attributesOf(relative)?.takeIf { it.isRegularFile }?.lastModifiedTime()?.toMillis()

    /** Writes [bytes] unless the file already holds exactly them. Returns true when it wrote. */
    fun write(relative: String, bytes: ByteArray, executable: Boolean = false): Boolean {
        checkAllowed(relative)
        return inFolderOf(relative, create = true) { folder, name ->
            if (folder.readBytes(name, bytes.size.toLong())?.contentEquals(bytes) == true) {
                false
            } else {
                folder.write(name, bytes, if (executable) EXECUTABLE else PRIVATE)
                true
            }
        } ?: throw IOException("A folder on the way to $relative is a link or a file.")
    }

    fun write(relative: String, text: String, executable: Boolean = false): Boolean =
        write(relative, text.toByteArray(Charsets.UTF_8), executable)

    /** Deletes one file (a link is removed itself, never what it points to). */
    fun delete(relative: String) {
        checkAllowed(relative)
        inFolderOf(relative, create = false) { folder, name -> folder.delete(name) }
    }

    /** Moves a file aside (to `<name>.pocketide-broken`) so a fresh one can be written. */
    fun setAside(relative: String) {
        checkAllowed(relative)
        inFolderOf(relative, create = false) { folder, name ->
            if (folder.attributes(name)?.isRegularFile == true) folder.rename(name, name + BROKEN)
        }
    }

    /** Makes the folder at [relative] and those on the way (owner-only); false when a link or a file is in the way. */
    fun makeFolder(relative: String): Boolean = within(components(relative), create = true) { true } ?: false

    /** Sets owner-only modes: 0700 for folders, 0600 for files. Links are left alone. */
    fun makePrivate(relative: String) {
        val names = components(relative)
        if (names.isEmpty()) {
            within(names, create = false) { it.setPermissions(null, PRIVATE_DIR) }
            return
        }
        inFolderOf(relative, create = false) { folder, name ->
            val attributes = folder.attributes(name)
            when {
                attributes?.isDirectory == true -> folder.setPermissions(name, PRIVATE_DIR)
                attributes?.isRegularFile == true -> folder.setPermissions(name, PRIVATE)
            }
        }
    }

    /**
     * Relative paths of regular files under [relative], at most [depth] folders down, skipping
     * folders named in [skip] (relative to the base). Links are listed as nothing.
     */
    fun files(relative: String, depth: Int, skip: Set<String> = emptySet()): List<String> {
        val start = components(relative)
        val found = mutableListOf<String>()
        within(start, create = false) { collect(it, start.joinToString("/"), 0, depth, skip, found) }
        return found
    }

    /** Names of the real folders (not links) directly under [relative]. */
    fun folders(relative: String): List<String> =
        within(components(relative), create = false) { folder ->
            folder.namesOrEmpty().filter { folder.attributes(it)?.isDirectory == true }
        }.orEmpty()

    private fun collect(folder: Folder, prefix: String, level: Int, depth: Int, skip: Set<String>, found: MutableList<String>) {
        for (name in folder.namesOrEmpty()) {
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            val attributes = folder.attributes(name) ?: continue
            when {
                attributes.isRegularFile -> found += path
                attributes.isDirectory && level < depth && path !in skip ->
                    folder.folder(name)?.use { collect(it, path, level + 1, depth, skip, found) }
            }
        }
    }

    private fun attributesOf(relative: String): BasicFileAttributes? {
        val names = components(relative)
        if (names.isEmpty()) return within(names, create = false) { it.ownAttributes() }
        return inFolderOf(relative, create = false) { folder, name -> folder.attributes(name) }
    }

    /** Runs [use] in the folder that holds [relative]'s last name; null when that folder is not there (or not real). */
    private fun <T> inFolderOf(relative: String, create: Boolean, use: (Folder, String) -> T?): T? {
        val names = components(relative)
        return within(names.dropLast(1), create) { use(it, names.last()) }
    }

    /** Runs [use] in the folder at [names] under the base, each one opened from the one before; null when one is missing or not real. */
    private fun <T> within(names: List<String>, create: Boolean, use: (Folder) -> T?): T? {
        var folder = baseFolder(create) ?: return null
        try {
            for (name in names) {
                val next = (if (create) folder.makeFolder(name) else folder.folder(name)) ?: return null
                val previous = folder
                folder = next
                previous.close()
            }
            return use(folder)
        } finally {
            folder.close()
        }
    }

    /**
     * The base, opened from its parent (the app's own folder) and refused if it is a link: the
     * room could otherwise replace its whole home with one.
     */
    private fun baseFolder(create: Boolean): Folder? {
        val root = base.toPath().toAbsolutePath()
        if (create && attributes(root) == null) Files.createDirectories(root)
        val above = root.parent ?: return null
        val parent = try {
            Files.newDirectoryStream(above)
        } catch (missing: IOException) {
            return null
        }
        parent.use {
            if (holdFolders && it is SecureDirectoryStream<Path>) {
                return try {
                    HeldFolder(root, it.newDirectoryStream(root.fileName, LinkOption.NOFOLLOW_LINKS))
                } catch (refused: IOException) {
                    null
                }
            }
        }
        return if (attributes(root)?.isDirectory == true) CheckedFolder(root, root) else null
    }

    private fun checkAllowed(relative: String) {
        require(components(relative).isNotEmpty()) { "No file named." }
        require(!guardSecrets || !AgentFiles.isSecret(relative)) { "$relative is a sign-in file; PocketIDE never touches those." }
    }

    /** A folder under the base, reached without following a link. Every name is one path component. */
    private interface Folder : Closeable {
        fun ownAttributes(): BasicFileAttributes?

        /** What [name] is, itself (a link is reported as a link); null when nothing is there. */
        fun attributes(name: String): BasicFileAttributes?

        /** The real folder [name]; null when it is missing, a link or not a folder. */
        fun folder(name: String): Folder?

        /** [folder], made (owner-only) when missing. */
        fun makeFolder(name: String): Folder?

        /** The regular file [name], open for reading; null when it is not one. */
        fun open(name: String): SeekableByteChannel?

        /** Writes a new temporary file and renames it over [name]. */
        fun write(name: String, bytes: ByteArray, permissions: Set<PosixFilePermission>)

        fun delete(name: String)

        /** Removes the empty folder [name]. */
        fun deleteFolder(name: String)

        fun rename(from: String, to: String)

        /** Of [name], or of this folder itself when [name] is null. */
        fun setPermissions(name: String?, permissions: Set<PosixFilePermission>)

        fun names(): List<String>
    }

    /** A folder held open: every step is relative to its handle, so nothing swapped on the way above it matters. */
    private class HeldFolder(private val path: Path, private val handle: SecureDirectoryStream<Path>) : Folder {
        override fun ownAttributes(): BasicFileAttributes? = try {
            handle.getFileAttributeView(BasicFileAttributeView::class.java)?.readAttributes()
        } catch (unreadable: IOException) {
            null
        }

        override fun attributes(name: String): BasicFileAttributes? = try {
            handle.getFileAttributeView(Paths.get(name), BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)?.readAttributes()
        } catch (missing: IOException) {
            null
        }

        override fun folder(name: String): Folder? {
            if (attributes(name)?.isDirectory != true) return null
            return try {
                HeldFolder(path.resolve(name), handle.newDirectoryStream(Paths.get(name), LinkOption.NOFOLLOW_LINKS))
            } catch (swapped: IOException) {
                null
            }
        }

        override fun makeFolder(name: String): Folder? {
            folder(name)?.let { return it }
            if (attributes(name) != null) return null
            // Java has no mkdirat: the folder is made by path, then opened from this handle, so
            // it is used only if it really is here.
            val made = try {
                Files.createDirectory(path.resolve(name))
                true
            } catch (raced: FileAlreadyExistsException) {
                false
            }
            val folder = folder(name) ?: return null
            if (made) folder.setPermissions(null, PRIVATE_DIR)
            return folder
        }

        override fun open(name: String): SeekableByteChannel? {
            if (attributes(name)?.isRegularFile != true) return null
            return try {
                handle.newByteChannel(Paths.get(name), READ_NO_LINK)
            } catch (swapped: IOException) {
                null
            }
        }

        override fun write(name: String, bytes: ByteArray, permissions: Set<PosixFilePermission>) {
            val temporary = temporaryName()
            var placed = false
            try {
                handle.newByteChannel(Paths.get(temporary), CREATE_NO_LINK, PosixFilePermissions.asFileAttribute(PRIVATE)).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
                if (permissions != PRIVATE) setPermissions(temporary, permissions)
                handle.move(Paths.get(temporary), handle, Paths.get(name))
                placed = true
            } finally {
                if (!placed) deleteQuietly(temporary)
            }
        }

        override fun delete(name: String) {
            try {
                handle.deleteFile(Paths.get(name))
            } catch (missing: NoSuchFileException) {
                // Already gone.
            }
        }

        override fun deleteFolder(name: String) {
            try {
                handle.deleteDirectory(Paths.get(name))
            } catch (missing: NoSuchFileException) {
                // Already gone.
            }
        }

        override fun rename(from: String, to: String) = handle.move(Paths.get(from), handle, Paths.get(to))

        override fun setPermissions(name: String?, permissions: Set<PosixFilePermission>) {
            val view = if (name == null) {
                handle.getFileAttributeView(PosixFileAttributeView::class.java)
            } else {
                handle.getFileAttributeView(Paths.get(name), PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            }
            (view ?: throw IOException("File modes cannot be set here.")).setPermissions(permissions)
        }

        override fun names(): List<String> = handle.map { it.fileName.toString() }.sorted()

        override fun close() = handle.close()

        private fun deleteQuietly(name: String) {
            try {
                handle.deleteFile(Paths.get(name))
            } catch (gone: IOException) {
                // Never made, or already removed.
            }
        }
    }

    /**
     * A folder reached by path, each step checked: used only where folders cannot be held open.
     * [root] is the base; the folder must still be inside it (links resolved) when a file is used.
     */
    private class CheckedFolder(private val path: Path, private val root: Path) : Folder {
        override fun ownAttributes() = RoomFiles.attributes(path)

        override fun attributes(name: String) = RoomFiles.attributes(path.resolve(name))

        override fun folder(name: String): Folder? =
            path.resolve(name).takeIf { RoomFiles.attributes(it)?.isDirectory == true }?.let { CheckedFolder(it, root) }

        override fun makeFolder(name: String): Folder? {
            folder(name)?.let { return it }
            if (attributes(name) != null) return null
            val next = path.resolve(name)
            try {
                Files.createDirectory(next)
                Files.setPosixFilePermissions(next, PRIVATE_DIR)
            } catch (raced: FileAlreadyExistsException) {
                // Made at the same time; used below only if it is a real folder.
            }
            return folder(name)
        }

        override fun open(name: String): SeekableByteChannel? {
            if (attributes(name)?.isRegularFile != true) return null
            val channel = try {
                Files.newByteChannel(path.resolve(name), READ_NO_LINK)
            } catch (swapped: IOException) {
                return null
            }
            if (inside()) return channel
            channel.close()
            return null
        }

        override fun write(name: String, bytes: ByteArray, permissions: Set<PosixFilePermission>) {
            if (!inside()) throw IOException(OUTSIDE)
            val temporary = path.resolve(temporaryName())
            try {
                Files.newByteChannel(temporary, CREATE_NO_LINK, PosixFilePermissions.asFileAttribute(PRIVATE)).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
                if (permissions != PRIVATE) Files.setPosixFilePermissions(temporary, permissions)
                if (!inside()) throw IOException(OUTSIDE)
                Files.move(temporary, path.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        override fun delete(name: String) {
            if (inside()) Files.deleteIfExists(path.resolve(name))
        }

        override fun deleteFolder(name: String) = delete(name)

        override fun rename(from: String, to: String) {
            if (inside()) Files.move(path.resolve(from), path.resolve(to), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        override fun setPermissions(name: String?, permissions: Set<PosixFilePermission>) {
            val target = if (name == null) path else path.resolve(name)
            val attributes = RoomFiles.attributes(target) ?: return
            if (inside() && !attributes.isSymbolicLink) Files.setPosixFilePermissions(target, permissions)
        }

        override fun names(): List<String> = Files.newDirectoryStream(path).use { stream -> stream.map { it.fileName.toString() }.sorted() }

        override fun close() = Unit

        /** This folder, with every link resolved, is still inside the real base. */
        private fun inside(): Boolean = try {
            path.toRealPath().startsWith(root.toRealPath())
        } catch (unreadable: IOException) {
            false
        }
    }

    companion object {
        const val MAX_READ = 4L * 1024 * 1024
        private const val READ_CHUNK = 8192
        private const val TEMPORARY_BYTES = 12
        private const val BROKEN = ".pocketide-broken"
        private const val OUTSIDE = "A folder on the way was swapped for a link."
        private val READ_NO_LINK: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        private val CREATE_NO_LINK: Set<OpenOption> = setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)
        private val PRIVATE = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        private val PRIVATE_DIR = PRIVATE + PosixFilePermission.OWNER_EXECUTE
        private val EXECUTABLE = PRIVATE_DIR + setOf(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE,
        )
        private val random = SecureRandom()

        fun components(relative: String): List<String> {
            val names = relative.split('/').filter { it.isNotEmpty() && it != "." }
            require(names.none { it == ".." || it.contains('\u0000') }) { "\"$relative\" leaves its folder." }
            return names
        }

        /** A fresh name nothing else uses, so creating it can never reach an existing file or link. */
        private fun temporaryName(): String =
            ".pocketide-" + ByteArray(TEMPORARY_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) } + ".tmp"

        /** The bytes of the regular file [name], at most [limit] of them (more gives null), or null. */
        private fun Folder.readBytes(name: String, limit: Long): ByteArray? {
            val channel = open(name) ?: return null
            return try {
                channel.use { if (it.size() > limit) null else readAtMost(it, limit) }
            } catch (unreadable: IOException) {
                null
            }
        }

        /** At most [limit] bytes; null when there are more (the file grew while it was read). */
        private fun readAtMost(channel: SeekableByteChannel, limit: Long): ByteArray? {
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(READ_CHUNK)
            while (true) {
                val n = channel.read(ByteBuffer.wrap(chunk))
                if (n < 0) return out.toByteArray()
                if (out.size() + n > limit) return null
                out.write(chunk, 0, n)
            }
        }

        private fun Folder.namesOrEmpty(): List<String> = try {
            names()
        } catch (unreadable: IOException) {
            emptyList()
        } catch (unreadable: DirectoryIteratorException) {
            emptyList()
        }

        /**
         * Deletes [root] and everything under it. Links are removed, never followed, not even one
         * swapped in while the delete runs: each folder is opened from the one above it, as for
         * reads and writes. Folders are made writable first, because tools inside Linux leave
         * read-only folders behind.
         */
        fun deleteTree(root: File) {
            val start = root.toPath()
            val attributes = attributes(start) ?: return
            if (attributes.isDirectory) {
                RoomFiles(root, guardSecrets = false).within(emptyList(), create = false) { folder ->
                    folder.unlock(null)
                    folder.empty()
                }
            }
            Files.deleteIfExists(start)
        }

        /** Deletes everything in this folder: a link itself, a file, a folder once it is empty. */
        private fun Folder.empty() {
            for (name in namesOrEmpty()) {
                val attributes = attributes(name) ?: continue
                if (!attributes.isDirectory) {
                    delete(name)
                    continue
                }
                unlock(name)
                folder(name)?.use { it.empty() }
                deleteFolder(name)
            }
        }

        /** Makes the folder [name] (or this one) the owner's to empty; one that stays locked fails to go, and says so. */
        private fun Folder.unlock(name: String?) {
            try {
                setPermissions(name, PRIVATE_DIR)
            } catch (locked: IOException) {
                // Removing it reports why it cannot go.
            }
        }

        fun attributes(path: Path): BasicFileAttributes? = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (missing: IOException) {
            null
        }
    }
}
