package com.pocketide.limiter

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** This app's processes, as Android lets it see them. */
data class OwnProcesses(
    /** Processes other than the app itself: proot and everything under it (Android's "phantom" processes). */
    val children: Int,
    /** Memory held by the app and all of them together. */
    val memoryBytes: Long,
)

/**
 * Reads /proc for this app's own processes. Android mounts /proc so an app sees only its own
 * UID's entries; the UID is still checked, so the count never includes anyone else's.
 *
 * Memory is PSS from smaps_rollup where the kernel has it (shared pages split fairly between
 * the processes that map them), else VmRSS from status. Unlike getProcessMemoryInfo, neither is
 * throttled to once per five minutes.
 */
class ProcessReader(private val proc: File = File("/proc"), private val uid: Int, private val selfPid: Int) {

    fun read(): OwnProcesses {
        var children = 0
        var kilobytes = 0L
        for (pid in pids()) {
            val status = read(File(proc, "$pid/status")) ?: continue
            if (field(status, "Uid:")?.split(WHITESPACE)?.firstOrNull()?.toIntOrNull() != uid) continue
            if (pid != selfPid) children++
            kilobytes += pssKb(pid) ?: kb(field(status, "VmRSS:")) ?: 0
        }
        return OwnProcesses(children, kilobytes * 1024)
    }

    private fun pssKb(pid: Int): Long? = read(File(proc, "$pid/smaps_rollup"))?.let { kb(field(it, "Pss:")) }

    private fun pids(): List<Int> = proc.list()?.mapNotNull { name -> name.toIntOrNull()?.takeIf { it > 0 } }.orEmpty()

    private fun field(text: String, name: String): String? =
        text.lineSequence().firstOrNull { it.startsWith(name) }?.removePrefix(name)?.trim()

    /** "123456 kB" → 123456. */
    private fun kb(value: String?): Long? = value?.substringBefore(' ')?.toLongOrNull()

    /** A process that ends between the listing and the read is ordinary, not an error. */
    private fun read(file: File): String? = try {
        file.readText()
    } catch (_: IOException) {
        null
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/**
 * Bytes under [roots], counting each regular file once. Symbolic links are never followed:
 * the Linux computer is full of them, and some point back up the tree.
 */
object DirectorySize {
    fun of(roots: List<File>): Long {
        var total = 0L
        val visitor = object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) total += attrs.size()
                return FileVisitResult.CONTINUE
            }

            // Unreadable corners (a guest file with mode 000) are skipped, not fatal.
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        }
        for (root in roots) {
            if (!root.exists()) continue
            try {
                Files.walkFileTree(root.toPath(), visitor)
            } catch (_: IOException) {
                // The walk keeps what it counted so far.
            }
        }
        return total
    }
}
