package com.pocketide.ui.screens.project

import com.pocketide.ui.manage.DiskEntry
import com.pocketide.ui.manage.LinkFreeFiles
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** What the file viewer shows for one path of a session's worktree. */
sealed interface SourceView {
    data class Text(val text: String) : SourceView

    /** Not in the worktree: the session deleted it, or the worktree is gone. */
    data object Missing : SourceView

    data class Unreadable(val why: String) : SourceView
}

/**
 * A session's worktree, read-only. The agent's Linux programs write it, so nothing here follows
 * a symbolic link: every folder from the app's own [base] down to the file must be real.
 */
class SourceTree(private val base: File, val root: File) {
    /** The worktree's entries under [folder] ("" for the top): folders first, `.git` left out. */
    fun list(folder: String): List<DiskEntry>? {
        val dir = resolve(folder) ?: return null
        return LinkFreeFiles.list(base, dir, MAX_ENTRIES)
            ?.filterNot { it.name == ".git" }
            ?.sortedWith(compareBy<DiskEntry> { it.kind != DiskEntry.Kind.FOLDER }.thenBy { it.name.lowercase() })
    }

    fun read(path: String): SourceView {
        val file = resolve(path)?.takeIf { path.isNotEmpty() } ?: return SourceView.Unreadable("This path is not in the session.")
        // Checked before asking whether it exists, so a link cannot reveal what lies outside.
        if (!LinkFreeFiles.isSafe(base, file)) return SourceView.Unreadable(LinkFreeFiles.NOT_PLAIN)
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return SourceView.Missing
        return try {
            SourceView.Text(LinkFreeFiles.readText(base, file, MAX_VIEW_BYTES, "This file is too large to show on the phone."))
        } catch (e: IOException) {
            SourceView.Unreadable(e.message ?: LinkFreeFiles.NOT_TEXT)
        }
    }

    /** A relative path inside the worktree, or null for anything that would leave it. */
    internal fun resolve(relative: String): File? {
        if (relative.isEmpty()) return root
        if (relative.startsWith('/') || relative.contains('\\') || relative.contains('\u0000')) return null
        val parts = relative.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return File(root, relative)
    }

    companion object {
        const val MAX_VIEW_BYTES = 512 * 1024L
        private const val MAX_ENTRIES = 2_000
    }
}

/** Path helpers for the file tree: "" is the worktree's top folder. */
object SourcePaths {
    fun parent(path: String): String = path.substringBeforeLast('/', missingDelimiterValue = "")

    fun child(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder/$name"

    fun name(path: String): String = path.substringAfterLast('/')
}
