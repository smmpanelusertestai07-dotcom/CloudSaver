package com.pocketide.ui.manage

import com.pocketide.core.AgentFiles
import com.pocketide.core.FileClass
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** One user-level instructions or memory file in an agent's room. */
data class MemoryFile(
    val agentId: String,
    /** Shown as the room sees it, e.g. "~/.claude/CLAUDE.md". */
    val label: String,
    val file: File,
    val exists: Boolean,
    val bytes: Long,
    /** The agent's main instructions file: listed even before it exists, so it can be created. */
    val primary: Boolean,
    /** Synced, encrypted, to Drive; otherwise it stays on this phone only. */
    val synced: Boolean,
)

/**
 * Finds and edits the agents' user-level instructions and memory files inside each room's home.
 *
 * The room's home is written by Linux programs, so nothing here follows a symbolic link: a link
 * planted there must never make the app read or overwrite a file outside that home. What may be
 * shown comes from [AgentFiles]: a credential is never listed, whatever its name.
 */
object MemoryFiles {
    /** Larger files are not opened in the phone editor (instructions stay far below this). */
    const val MAX_EDIT_BYTES = 512 * 1024L
    private const val MAX_FILES_PER_ROOM = 60
    private const val NOT_PLAIN = "This file is not a plain file in the room."

    /** Relative paths per agent; `*` matches one directory level or one file name. */
    private val patterns = mapOf(
        "claude" to listOf(".claude/CLAUDE.md", ".claude/rules/*.md", ".claude/projects/*/memory/*.md"),
        "codex" to listOf(".codex/AGENTS.md", ".codex/AGENTS.override.md"),
        "antigravity" to listOf(
            ".gemini/GEMINI.md",
            ".gemini/AGENTS.md",
            ".gemini/config/GEMINI.md",
            ".gemini/config/AGENTS.md",
            ".gemini/config/rules/*.md",
            ".gemini/antigravity-cli/rules/*.md",
        ),
    )

    fun patternsFor(agentId: String, instructionsFile: String): List<String> =
        patterns[agentId] ?: listOf(instructionsFile).filter { isPlainName(it) }

    /** The files of one room: the primary file always, the others only when they exist. */
    fun find(home: File, agentId: String, instructionsFile: String): List<MemoryFile> {
        // Before the room exists there is nothing to edit; the room writes its home first.
        if (!Files.isDirectory(home.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val found = LinkedHashMap<String, MemoryFile>()
        patternsFor(agentId, instructionsFile).forEachIndexed { index, pattern ->
            val primary = index == 0 && !pattern.contains('*')
            for (relative in expand(home, pattern)) {
                if (found.size >= MAX_FILES_PER_ROOM) break
                if (relative in found) continue
                val kind = AgentFiles.classify(relative)
                if (kind == FileClass.SECRET || kind == FileClass.GENERATED) continue
                val file = File(home, relative)
                val exists = isSafe(home, file) && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                if (exists || primary) {
                    val bytes = if (exists) file.length() else 0
                    found[relative] = MemoryFile(agentId, "~/$relative", file, exists, bytes, primary, kind == FileClass.SYNC)
                }
            }
        }
        return found.values.toList()
    }

    /** True when [file] and every directory between it and [home] is a real directory or file, not a link. */
    fun isSafe(home: File, file: File): Boolean {
        val root = home.toPath().toAbsolutePath().normalize()
        val target = file.toPath().toAbsolutePath().normalize()
        if (!target.startsWith(root) || target == root) return false
        if (Files.isSymbolicLink(root)) return false
        var current: Path = root
        for (part in root.relativize(target)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) return false
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return true
        }
        return true
    }

    @Throws(IOException::class)
    fun read(home: File, file: File): String {
        if (!isSafe(home, file)) throw IOException(NOT_PLAIN)
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return ""
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("This is not a plain text file.")
        // NOFOLLOW_LINKS refuses a link swapped in after the check; the folder is checked again once open.
        val bytes = Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            if (!insideHome(home, file)) throw IOException(NOT_PLAIN)
            readAtMost(channel, MAX_EDIT_BYTES)
        }
        if (bytes.any { it == 0.toByte() }) throw IOException("This is not a plain text file.")
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Writes [text] so the file is either the old or the new version, never half of each:
     * a temporary file in the same folder, flushed to disk, then renamed over the old one.
     * Renaming replaces a link rather than writing through it.
     */
    @Throws(IOException::class)
    fun save(home: File, file: File, text: String) {
        if (!isSafe(home, file)) throw IOException(NOT_PLAIN)
        val parent = file.parentFile ?: throw IOException("This file has no folder.")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create the folder for this file.")
        if (!isSafe(home, file)) throw IOException(NOT_PLAIN)
        val temp = File(parent, ".${file.name}.pocketide-save").toPath()
        try {
            // A leftover (or planted) temp name is removed as itself; CREATE_NEW then refuses
            // anything that reappears there, so the write can never go through a link.
            Files.deleteIfExists(temp)
            FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                // Checked before any byte is written, so a swapped folder never receives the text.
                if (!insideHome(home, file)) throw IOException(NOT_PLAIN)
                val buffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            // A folder on the way swapped for a link while writing would move the file elsewhere.
            if (!insideHome(home, file)) throw IOException(NOT_PLAIN)
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** The file's folder, with every link resolved, is still inside the room's real home. */
    private fun insideHome(home: File, file: File): Boolean {
        val parent = file.toPath().toAbsolutePath().normalize().parent ?: return false
        return try {
            parent.toRealPath().startsWith(home.toPath().toRealPath())
        } catch (_: IOException) {
            false
        }
    }

    private fun readAtMost(channel: SeekableByteChannel, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(8192)
        while (true) {
            buffer.clear()
            val n = channel.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw IOException("This file is too large to edit on the phone.")
            out.write(buffer.array(), 0, n)
        }
        return out.toByteArray()
    }

    private fun expand(home: File, pattern: String): List<String> {
        var paths = listOf("")
        for (segment in pattern.split('/')) {
            paths = paths.flatMap { base ->
                if (segment.contains('*')) {
                    val dir = if (base.isEmpty()) home else File(home, base)
                    if (!isSafe(home, dir) || !Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        emptyList()
                    } else {
                        dir.list().orEmpty().sorted().filter { globMatches(segment, it) }.map { join(base, it) }
                    }
                } else {
                    listOf(join(base, segment))
                }
            }
        }
        return paths
    }

    internal fun globMatches(glob: String, name: String): Boolean {
        if (name.startsWith('.')) return false
        val regex = glob.split('*').joinToString(".*") { Regex.escape(it) }
        return Regex(regex).matches(name)
    }

    private fun join(base: String, name: String) = if (base.isEmpty()) name else "$base/$name"

    private fun isPlainName(name: String) = name.isNotBlank() && !name.contains('/') && !name.startsWith('.')
}

/**
 * An instructions file split around the block the app manages (its own rules for the agent).
 * The owner edits [before] and [after]; the managed block is kept byte for byte.
 */
data class MemoryDocument(val before: String, val managed: String?, val after: String) {
    fun join(): String = before + managed.orEmpty() + after

    companion object {
        /*
         * The rooms module writes its block between HTML comment lines naming PocketIDE, e.g.
         * `<!-- pocketide:begin … -->` and `<!-- pocketide:end -->`. Matching loosely keeps the
         * block protected even if the wording of those comments changes.
         */
        private val begin = Regex("^\\s*<!--.*pocketide.*\\b(begin|start)\\b.*-->\\s*$", RegexOption.IGNORE_CASE)
        private val end = Regex("^\\s*<!--.*pocketide.*\\bend\\b.*-->\\s*$", RegexOption.IGNORE_CASE)

        fun parse(text: String): MemoryDocument {
            val lines = linesWithEnds(text)
            var offset = 0
            var blockStart = -1
            for (line in lines) {
                val content = line.trimEnd('\n', '\r')
                if (blockStart < 0 && begin.matches(content)) {
                    blockStart = offset
                } else if (blockStart >= 0 && end.matches(content) && !begin.matches(content)) {
                    val blockEnd = offset + line.length
                    return MemoryDocument(text.substring(0, blockStart), text.substring(blockStart, blockEnd), text.substring(blockEnd))
                }
                offset += line.length
            }
            return MemoryDocument(text, null, "")
        }

        /**
         * What to write when the owner saves, given the file as it is on disk [now]. The owner's
         * parts go around the app's block as it is now (the room may have rewritten it). If the
         * owner's parts changed on disk since [loaded] (an agent wrote to its memory), nothing is
         * written unless [overwrite] says to replace them.
         */
        fun plan(loaded: MemoryDocument, now: MemoryDocument, before: String, after: String, overwrite: Boolean): SavePlan = when {
            hasMarker(before) || hasMarker(after) -> SavePlan.Refused(
                "Your text has a PocketIDE marker line. Remove it: those lines belong to the app's block.",
            )
            !overwrite && (now.before != loaded.before || now.after != loaded.after) -> SavePlan.ChangedOnDisk
            else -> SavePlan.Write(rebuild(before, now.managed ?: loaded.managed, after))
        }

        fun hasMarker(text: String): Boolean = linesWithEnds(text).any { line ->
            val content = line.trimEnd('\n', '\r')
            begin.matches(content) || end.matches(content)
        }

        /** Joins edited parts so the managed block still starts and ends on its own lines. */
        fun rebuild(before: String, managed: String?, after: String): String {
            if (managed == null) return before + after
            val head = if (before.isEmpty() || before.endsWith('\n')) before else before + "\n"
            val block = if (managed.endsWith('\n') || after.isEmpty()) managed else managed + "\n"
            return head + block + after
        }

        private fun linesWithEnds(text: String): List<String> {
            val out = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                val newline = text.indexOf('\n', start)
                val stop = if (newline < 0) text.length else newline + 1
                out += text.substring(start, stop)
                start = stop
            }
            return out
        }
    }
}

sealed interface SavePlan {
    data class Write(val text: String) : SavePlan

    /** The file changed since it was opened; the owner decides whether to replace it. */
    data object ChangedOnDisk : SavePlan

    data class Refused(val why: String) : SavePlan
}

/** Sizes on disk without following links (a room could link to anything). */
object DiskUsage {
    /** Total bytes of regular files under [root], skipping any folder named [skipDirectory]. */
    fun sizeOf(root: File, skipDirectory: String? = null): Long {
        val path = root.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return 0
        var total = 0L
        try {
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                        if (skipDirectory != null && dir != path && dir.fileName?.toString() == skipDirectory) {
                            FileVisitResult.SKIP_SUBTREE
                        } else {
                            FileVisitResult.CONTINUE
                        }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        return FileVisitResult.CONTINUE
                    }

                    // An unreadable folder is skipped, not a reason to report nothing.
                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
        } catch (_: IOException) {
            return total
        }
        return total
    }
}
