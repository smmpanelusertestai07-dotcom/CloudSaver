package com.pocketide.git

import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.errors.IncorrectObjectTypeException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.TreeFilter
import java.io.InputStream
import java.util.Locale

internal data class CheckPostLimits(
    /** GitHub refuses any file over 100 MiB. */
    val maxFileBytes: Long = 100L * 1024 * 1024,
    /** Bigger files are not searched for tokens or values; the path rules still apply. */
    val maxSearchBytes: Int = 10 * 1024 * 1024,
    /** Plenty to show what needs fixing; the verdict is already "no" by then. */
    val maxFindings: Int = 200,
)

/**
 * The check-post. Every commit on the branch that GitHub does not have yet is compared with its
 * first parent (the empty tree for a root commit), oldest first, and every file it adds or
 * changes is checked: its path, its size, and, for text, the lines it adds. Commit messages are
 * checked too. One finding blocks the push.
 *
 * A merge only answers for the files that differ from every parent, as `git show --cc` does: the
 * rest came from a parent, which is either on GitHub already or checked as a commit of its own.
 */
internal class CheckPost(private val limits: CheckPostLimits = CheckPostLimits()) {

    /**
     * Checks the commits reachable from [tip] and not from [onGitHub]. [checkActive] runs between
     * commits and files and throws to stop (on cancellation).
     */
    fun check(
        repo: Repository,
        tip: ObjectId,
        onGitHub: Collection<ObjectId>,
        knownValues: List<String>,
        checkActive: () -> Unit,
    ): Verdict {
        val scan = Scan(KnownValues(knownValues), Findings(limits.maxFindings), checkActive)
        repo.newObjectReader().use { reader ->
            RevWalk(reader).use { walk ->
                walk.sort(RevSort.TOPO)
                walk.sort(RevSort.REVERSE, true)
                walk.markStart(walk.parseCommit(tip))
                onGitHub.forEach { markOnGitHub(walk, reader, it) }
                for (commit in walk) {
                    checkActive()
                    scan.commits++
                    scan.commit(reader, walk, commit)
                    if (scan.findings.full) break
                }
            }
        }
        return Verdict(scan.findings.isEmpty, scan.findings.all, scan.commits)
    }

    private fun markOnGitHub(walk: RevWalk, reader: ObjectReader, id: ObjectId) {
        if (!reader.has(id)) return
        val commit = try {
            walk.peel(walk.parseAny(id)) as? RevCommit
        } catch (e: MissingObjectException) {
            null
        } catch (e: IncorrectObjectTypeException) {
            null
        }
        commit?.let(walk::markUninteresting)
    }

    private inner class Scan(val values: KnownValues, val findings: Findings, val checkActive: () -> Unit) {
        var commits = 0

        fun commit(reader: ObjectReader, walk: RevWalk, commit: RevCommit) {
            val sha = commit.name().take(SHORT_SHA)
            message(commit, sha)
            for (change in changes(reader, walk, commit)) {
                checkActive()
                file(reader, sha, change)
                if (findings.full) return
            }
        }

        private fun message(commit: RevCommit, sha: String) {
            val text = commit.fullMessage
            SecretPatterns.find("", text).forEach { inMessage(FindingKind.SECRET, sha, it) }
            if (values.foundIn(text)) inMessage(FindingKind.VARIABLE_OR_SECRET_VALUE, sha, KnownValues.DETAIL)
        }

        // Each commit's message is its own, so the same problem is reported for every commit.
        private fun inMessage(kind: FindingKind, sha: String, detail: String) =
            findings.add(kind, COMMIT_MESSAGE, sha, detail, perCommit = true)

        private fun file(reader: ObjectReader, sha: String, change: Change) {
            val path = change.path
            PathRules.check(path)?.let { findings.add(it.kind, path, sha, it.detail); return }
            if (Transcripts.applies(path) && Transcripts.found(head(reader, change.newId))) {
                findings.add(FindingKind.AI_DATA, path, sha, Transcripts.DETAIL)
                return
            }
            val size = reader.getObjectSize(change.newId, Constants.OBJ_BLOB)
            if (size > limits.maxFileBytes) {
                findings.add(FindingKind.TOO_LARGE, path, sha, tooLarge(size))
                return
            }
            val content = small(reader, change.newId) ?: return
            val previous by lazy { change.oldId?.let { small(reader, it) } }
            if (isBinary(content)) {
                if (values.newIn(content, previous)) {
                    findings.add(FindingKind.VARIABLE_OR_SECRET_VALUE, path, sha, KnownValues.DETAIL)
                }
                return
            }
            val added = addedLines(content, previous)
            for (detail in SecretPatterns.find(path.substringAfterLast('/'), added)) {
                findings.add(FindingKind.SECRET, path, sha, detail)
            }
            if (values.foundIn(added)) findings.add(FindingKind.VARIABLE_OR_SECRET_VALUE, path, sha, KnownValues.DETAIL)
        }

        private fun small(reader: ObjectReader, id: ObjectId): ByteArray? {
            if (reader.getObjectSize(id, Constants.OBJ_BLOB) > limits.maxSearchBytes) return null
            return reader.open(id, Constants.OBJ_BLOB).getCachedBytes(limits.maxSearchBytes)
        }

        private fun head(reader: ObjectReader, id: ObjectId): String {
            val bytes = reader.open(id, Constants.OBJ_BLOB).openStream().use { it.readUpTo(Transcripts.HEAD_BYTES) }
            return String(bytes, Charsets.UTF_8)
        }
    }

    /** A file a commit adds or changes; [oldId] is the first parent's version, when it had one. */
    private class Change(val path: String, val newId: ObjectId, val oldId: ObjectId?)

    private fun changes(reader: ObjectReader, walk: RevWalk, commit: RevCommit): List<Change> {
        val parents = commit.parents.map { walk.parseCommit(it) }
        TreeWalk(reader).use { tree ->
            tree.isRecursive = true
            tree.filter = TreeFilter.ANY_DIFF
            if (parents.isEmpty()) tree.addTree(EmptyTreeIterator()) else tree.addTree(parents.first().tree)
            tree.addTree(commit.tree)
            parents.drop(1).forEach { tree.addTree(it.tree) }
            val changes = mutableListOf<Change>()
            while (tree.next()) {
                // Symlinks carry only a path and submodules only a commit ID: no content to push.
                if (!tree.getFileMode(COMMIT).isFile()) continue
                val newId = tree.getObjectId(COMMIT)
                if (newId == tree.getObjectId(FIRST_PARENT)) continue
                if ((OTHER_PARENTS until tree.treeCount).any { tree.getObjectId(it) == newId }) continue
                val oldId = tree.getObjectId(FIRST_PARENT).takeIf { tree.getFileMode(FIRST_PARENT).isFile() }
                changes += Change(tree.pathString, newId, oldId)
            }
            return changes
        }
    }

    /** One finding per path and problem, at the oldest commit that has it; the list is capped. */
    private class Findings(private val max: Int) {
        val all = mutableListOf<Finding>()
        private val seen = HashSet<List<Any>>()

        val isEmpty get() = all.isEmpty()
        val full get() = all.size >= max

        fun add(kind: FindingKind, path: String, commit: String, detail: String, perCommit: Boolean = false) {
            if (full) return
            val key = if (perCommit) listOf(kind, path, detail, commit) else listOf(kind, path, detail)
            if (seen.add(key)) all += Finding(kind, path, commit, detail)
        }
    }

    private companion object {
        const val SHORT_SHA = 7
        const val COMMIT_MESSAGE = "commit message"

        // Tree positions in the diff walk.
        const val FIRST_PARENT = 0
        const val COMMIT = 1
        const val OTHER_PARENTS = 2

        fun FileMode.isFile() = this === FileMode.REGULAR_FILE || this === FileMode.EXECUTABLE_FILE

        /** git's own test: a NUL byte in the first 8000 bytes. */
        fun isBinary(content: ByteArray) = RawText.isBinary(content, content.size)

        /** The lines of [content] that [previous] did not have, joined with newlines. */
        fun addedLines(content: ByteArray, previous: ByteArray?): String {
            val text = String(content, Charsets.UTF_8)
            if (previous == null || isBinary(previous)) return text
            val before = String(previous, Charsets.UTF_8).lineSequence().toHashSet()
            return text.lineSequence().filterNot(before::contains).joinToString("\n")
        }

        fun tooLarge(bytes: Long): String {
            val megabytes = String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0))
            return "$megabytes MB. GitHub does not accept files over 100 MB."
        }

        fun InputStream.readUpTo(limit: Int): ByteArray {
            val buffer = ByteArray(limit)
            var filled = 0
            while (filled < limit) {
                val read = read(buffer, filled, limit - filled)
                if (read < 0) break
                filled += read
            }
            return buffer.copyOf(filled)
        }
    }
}
