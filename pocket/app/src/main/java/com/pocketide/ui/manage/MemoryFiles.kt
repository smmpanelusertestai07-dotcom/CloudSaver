package com.pocketide.ui.manage

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
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
)

/**
 * Finds and edits the agents' user-level instructions and memory files inside each room's home.
 *
 * The room's home is written by Linux programs, so nothing here follows a symbolic link: a link
 * planted there must never make the app read or overwrite a file outside that home.
 */
object MemoryFiles {
    /** Larger files are not opened in the phone editor (instructions stay far below this). */
    const val MAX_EDIT_BYTES = 512 * 1024L
    private const val MAX_FILES_PER_ROOM = 60

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
                val file = File(home, relative)
                val exists = isSafe(home, file) && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                if (exists || primary) {
                    found[relative] = MemoryFile(agentId, "~/$relative", file, exists, if (exists) file.length() else 0, primary)
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
        if (!isSafe(home, file)) throw IOException("This file is not a plain file in the room.")
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return ""
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("This is not a plain text file.")
        if (file.length() > MAX_EDIT_BYTES) throw IOException("This file is too large to edit on the phone.")
        return file.readText(Charsets.UTF_8)
    }

    /**
     * Writes [text] so the file is either the old or the new version, never half of each:
     * a temporary file in the same folder, flushed to disk, then renamed over the old one.
     * Renaming replaces a link rather than writing through it.
     */
    @Throws(IOException::class)
    fun save(home: File, file: File, text: String) {
        if (!isSafe(home, file)) throw IOException("This file is not a plain file in the room.")
        val parent = file.parentFile ?: throw IOException("This file has no folder.")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create the folder for this file.")
        if (!isSafe(home, file)) throw IOException("This file is not a plain file in the room.")
        val temp = File(parent, ".${file.name}.pocketide-save").toPath()
        try {
            // A leftover (or planted) temp name is removed as itself; CREATE_NEW then refuses
            // anything that reappears there, so the write can never go through a link.
            Files.deleteIfExists(temp)
            FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
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
