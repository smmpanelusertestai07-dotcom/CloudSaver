package com.pocketide.sessions

import com.pocketide.core.AppDirs
import com.pocketide.projects.SafeFiles
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Files the owner adds to a session (Android's file picker or the share sheet). They are copied,
 * never linked: into a folder the room can see, under a name that cannot climb out of it or
 * replace anything already there.
 */
internal object AddedFiles {
    /** GitHub refuses files over 100 MB, and the check-post stops them; bigger ones never start. */
    const val MAX_BYTES = 100L * 1024 * 1024
    private const val MAX_NAME_CHARS = 120
    private const val MAX_EXTENSION_CHARS = 10
    private const val MAX_TRIES = 100
    private const val BUFFER_BYTES = 64 * 1024
    private val UNSAFE = Regex("[\\x00-\\x1f\\x7f/\\\\:*?\"<>|]")

    /**
     * The file's own name without any folder part, with characters no file system takes replaced
     * and leading dots dropped (no hidden files, no `..`). Null when nothing usable is left.
     */
    fun safeName(name: String): String? {
        val last = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = UNSAFE.replace(last, "_").trim().trimStart('.').trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.length <= MAX_NAME_CHARS) return cleaned
        val dot = cleaned.lastIndexOf('.')
        val extension = if (dot > 0 && cleaned.length - dot <= MAX_EXTENSION_CHARS) cleaned.substring(dot) else ""
        return cleaned.take(MAX_NAME_CHARS - extension.length) + extension
    }

    /** Where attachments wait for the media library to take them. */
    fun staging(dirs: AppDirs): File = File(dirs.share, "added-${System.nanoTime()}")

    /**
     * Copies [source] into [dir] as [name], or "name (2)"… when taken, and returns the new file.
     * The file is created exclusively (an existing file or planted link is never written through)
     * and is removed again when the copy fails or passes [maxBytes].
     */
    fun copyInto(dir: File, name: String, source: InputStream, maxBytes: Long = MAX_BYTES): File {
        if (!SafeFiles.isDirectory(dir) && (SafeFiles.exists(dir) || !dir.mkdirs())) {
            throw SessionException("Could not open the folder for this file.")
        }
        val (target, stream) = create(dir, name)
        var complete = false
        try {
            stream.use { out ->
                val buffer = ByteArray(BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw SessionException("This file is over 100 MB, which GitHub does not accept. Add a smaller file.")
                    out.write(buffer, 0, read)
                }
            }
            complete = true
            return target
        } catch (failed: IOException) {
            throw SessionException("Could not copy the file: ${failed.message ?: "the phone's storage refused it"}.")
        } finally {
            if (!complete) SafeFiles.delete(target)
        }
    }

    /** Creates and opens the first free name in one step, so nothing can be swapped in between. */
    private fun create(dir: File, name: String): Pair<File, OutputStream> {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        for (n in 1..MAX_TRIES) {
            val candidate = File(dir, if (n == 1) name else "$stem ($n)$extension")
            try {
                return candidate to Files.newOutputStream(candidate.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            } catch (taken: FileAlreadyExistsException) {
                // The next number is tried.
            } catch (failed: IOException) {
                throw SessionException("Could not create the file: ${failed.message ?: "the phone's storage refused it"}.")
            }
        }
        throw SessionException("There are already many files named $name here. Rename it and try again.")
    }
}
