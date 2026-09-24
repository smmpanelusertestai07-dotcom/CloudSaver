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
 * Build outputs and changed GitHub Actions code hold the push instead ([Hold]); a workflow
 * change stops holding it once the owner approved the content the branch ends with.
 *
 * A merge only answers for the files that differ from every parent, as `git show --cc` does: the
 * rest came from a parent, which is either on GitHub already or checked as a commit of its own.
 */
internal class CheckPost(private val limits: CheckPostLimits = CheckPostLimits()) {

    /**
     * Checks the commits reachable from [tip] and not from [onGitHub]. [approvedWorkflows] holds
     * [WorkflowChanges.approvalKey]s. [checkActive] runs between commits and files and throws to
     * stop (on cancellation).
     */
    fun check(
        repo: Repository,
        tip: ObjectId,
        onGitHub: Collection<ObjectId>,
        knownValues: List<String>,
        approvedWorkflows: Collection<String> = emptyList(),
        checkActive: () -> Unit,
    ): Verdict {
        val scan = Scan(KnownValues(knownValues), Report(limits.maxFindings), checkActive)
        repo.newObjectReader().use { reader ->
            RevWalk(reader).use { walk ->
                walk.sort(RevSort.TOPO)
                walk.sort(RevSort.REVERSE, true)
                val tipCommit = walk.parseCommit(tip)
                walk.markStart(tipCommit)
                onGitHub.forEach { markOnGitHub(walk, reader, it) }
                for (commit in walk) {
                    checkActive()
                    scan.commits++
                    scan.commit(reader, walk, commit)
                    if (scan.report.full) break
                }
                scan.workflowHolds(reader, tipCommit, approvedWorkflows.toSet())
            }
        }
        val report = scan.report
        return Verdict(report.isEmpty, report.findings, scan.commits, report.holds)
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

    /** A workflow file's first change on the branch: where, and the version before it. */
    private class Touch(val commit: String, val before: ObjectId?)

    private inner class Scan(val values: KnownValues, val report: Report, val checkActive: () -> Unit) {
        var commits = 0
        private val workflows = LinkedHashMap<String, Touch>()

        fun commit(reader: ObjectReader, walk: RevWalk, commit: RevCommit) {
            val sha = commit.name().take(SHORT_SHA)
            message(commit, sha)
            for (change in changes(reader, walk, commit)) {
                checkActive()
                file(reader, sha, change)
                if (report.full) return
            }
        }

        private fun message(commit: RevCommit, sha: String) {
            val text = commit.fullMessage
            SecretPatterns.find("", text).forEach { inMessage(FindingKind.SECRET, sha, it) }
            if (values.foundIn(text)) inMessage(FindingKind.VARIABLE_OR_SECRET_VALUE, sha, KnownValues.DETAIL)
        }

        // Each commit's message is its own, so the same problem is reported for every commit.
        private fun inMessage(kind: FindingKind, sha: String, detail: String) =
            report.add(kind, COMMIT_MESSAGE, sha, detail, perCommit = true)

        private fun file(reader: ObjectReader, sha: String, change: Change) {
            val path = change.path
            if (WorkflowChanges.applies(path)) workflows.getOrPut(path) { Touch(sha, change.oldId) }
            PathRules.check(path)?.let { report.add(it.kind, path, sha, it.detail); return }
            if (Transcripts.applies(path) && Transcripts.found(head(reader, change.newId))) {
                report.add(FindingKind.AI_DATA, path, sha, Transcripts.DETAIL)
                return
            }
            BuildOutputs.check(path)?.let { report.hold(Hold(HoldKind.BUILD_OUTPUT, path, sha, it)); return }
            val size = reader.getObjectSize(change.newId, Constants.OBJ_BLOB)
            if (size > limits.maxFileBytes) {
                report.add(FindingKind.TOO_LARGE, path, sha, tooLarge(size))
                return
            }
            val content = small(reader, change.newId) ?: return
            val previous by lazy { change.oldId?.let { small(reader, it) } }
            if (isBinary(content)) {
                if (values.newIn(content, previous)) {
                    report.add(FindingKind.VARIABLE_OR_SECRET_VALUE, path, sha, KnownValues.DETAIL)
                }
                return
            }
            val added = addedLines(content, previous)
            for (detail in SecretPatterns.find(path.substringAfterLast('/'), added)) {
                report.add(FindingKind.SECRET, path, sha, detail)
            }
            if (values.foundIn(added)) report.add(FindingKind.VARIABLE_OR_SECRET_VALUE, path, sha, KnownValues.DETAIL)
        }

        /**
         * One hold per workflow file the branch changes, unless the owner approved the content it
         * ends with. Only that content can run: GitHub runs the workflows of the commit a branch
         * points at, and the gate pushes nothing else.
         */
        fun workflowHolds(reader: ObjectReader, tip: RevCommit, approved: Set<String>) {
            for ((path, touch) in workflows) {
                checkActive()
                val now = fileAt(reader, tip, path) ?: continue
                if (now == touch.before) continue
                val key = WorkflowChanges.approvalKey(path, now)
                if (key in approved) continue
                val after = small(reader, now)
                val before = touch.before?.let { small(reader, it) }
                val (detail, diff) = if (after == null) {
                    "Changes GitHub Actions code in $path, too big to show here. Check it on GitHub's website before approving." to ""
                } else {
                    val beforeText = before?.toString(Charsets.UTF_8)
                    WorkflowChanges.describe(path, beforeText, after.toString(Charsets.UTF_8)) to
                        WorkflowChanges.diff(path, before, after)
                }
                report.hold(Hold(HoldKind.WORKFLOW_CHANGE, path, touch.commit, detail, key, diff))
            }
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

    /** The regular file at [path] in [commit], or null. */
    private fun fileAt(reader: ObjectReader, commit: RevCommit, path: String): ObjectId? =
        TreeWalk.forPath(reader, path, commit.tree)?.use { tree ->
            tree.getObjectId(0).takeIf { tree.getFileMode(0).isFile() }
        }

    /** One finding or hold per path and problem, at the oldest commit that has it; the list is capped. */
    private class Report(private val max: Int) {
        val findings = mutableListOf<Finding>()
        val holds = mutableListOf<Hold>()
        private val seen = HashSet<List<Any>>()

        val isEmpty get() = findings.isEmpty() && holds.isEmpty()
        val full get() = findings.size + holds.size >= max

        fun add(kind: FindingKind, path: String, commit: String, detail: String, perCommit: Boolean = false) {
            if (full) return
            val key = if (perCommit) listOf(kind, path, detail, commit) else listOf(kind, path, detail)
            if (seen.add(key)) findings += Finding(kind, path, commit, detail)
        }

        fun hold(hold: Hold) {
            if (!full && seen.add(listOf(hold.kind, hold.path))) holds += hold
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
