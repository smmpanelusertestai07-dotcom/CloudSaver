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
import org.eclipse.jgit.util.RawParseUtils
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
 * first parent (the empty tree for a root commit), oldest first, and everything it adds or
 * changes is checked: each name; for a file its path, its size and, for text, the lines it adds;
 * for a symlink its target, which is text that goes to GitHub too. Commit messages, authors and
 * committers are checked as well. One finding blocks the push.
 *
 * Build outputs and changed GitHub Actions code hold the push instead ([Hold]); a workflow
 * change stops holding it once the owner approved the content the branch ends with, or when
 * that content is one of PocketIDE's templates, unchanged, where "Add template" puts it.
 *
 * A merge only answers for what differs from every parent, as `git show --cc` does: the rest
 * came from a parent, which is either on GitHub already or checked as a commit of its own.
 */
internal class CheckPost(
    private val limits: CheckPostLimits = CheckPostLimits(),
    /**
     * PocketIDE's own workflow templates by repository path, as blob ids. A workflow that is
     * exactly one of them at its own path was added on the owner's tap and holds nothing.
     */
    private val templates: () -> Map<String, ObjectId> = ::emptyMap,
) {

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
        repo.newObjectReader().use { repoReader ->
            val reader = FullHistory(repoReader)
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

    /**
     * Reads objects without the repo's `shallow` list. Linux can write that file, and a commit
     * listed there would look like a root, hiding every commit before it from the check.
     */
    private class FullHistory(private val reader: ObjectReader) : ObjectReader.Filter() {
        override fun delegate(): ObjectReader = reader

        override fun getShallowCommits(): Set<ObjectId> = emptySet()
    }

    /** What a tree entry pushes: a file's content, a symlink's target, or only a submodule's commit ID. */
    private enum class Kind { FILE, LINK, SUBMODULE }

    private data class Entry(val id: ObjectId, val kind: Kind)

    /** An entry a commit adds or changes; [old] is the first parent's, when it had one. */
    private class Change(val path: String, val new: Entry, val old: Entry?)

    /** A workflow path's first change on the branch: where, and the entry before it. */
    private class Touch(val commit: String, val before: Entry?)

    private inner class Scan(val values: KnownValues, val report: Report, val checkActive: () -> Unit) {
        var commits = 0
        private val workflows = LinkedHashMap<String, Touch>()

        fun commit(reader: ObjectReader, walk: RevWalk, commit: RevCommit) {
            val sha = commit.name().take(SHORT_SHA)
            details(commit, sha)
            for (change in changes(reader, walk, commit)) {
                checkActive()
                entry(reader, sha, change)
                if (report.full) return
            }
        }

        /** The author and committer lines (and any other header but tree and parents), then the message. */
        private fun details(commit: RevCommit, sha: String) {
            val raw = commit.rawBuffer
            val messageStart = RawParseUtils.commitMessage(raw, 0)
            val headers = String(raw, 0, if (messageStart < 0) raw.size else messageStart, Charsets.UTF_8)
                .lineSequence()
                .filterNot { it.startsWith("tree ") || it.startsWith("parent ") }
                .joinToString("\n")
            text(COMMIT_AUTHOR, sha, headers, perCommit = false)
            // Each commit's message is its own, so the same problem is reported for every commit.
            text(COMMIT_MESSAGE, sha, commit.fullMessage, perCommit = true)
        }

        private fun text(where: String, sha: String, text: String, perCommit: Boolean) {
            SecretPatterns.find("", text).forEach { report.add(FindingKind.SECRET, where, sha, it, perCommit) }
            if (values.foundIn(text)) {
                report.add(FindingKind.VARIABLE_OR_SECRET_VALUE, where, sha, KnownValues.DETAIL, perCommit)
            }
        }

        private fun entry(reader: ObjectReader, sha: String, change: Change) {
            if (secretInName(change.path, sha)) return
            if (WorkflowChanges.applies(change.path)) workflows.getOrPut(change.path) { Touch(sha, change.old) }
            when (change.new.kind) {
                Kind.FILE -> file(reader, sha, change)
                // No path rule applies: a link named .env pushes only the path it points to.
                Kind.LINK -> content(reader, sha, change, fileName = "")
                Kind.SUBMODULE -> Unit
            }
        }

        /** A name can carry a secret as well as a file can. The finding shows the name with it hidden. */
        private fun secretInName(path: String, sha: String): Boolean {
            val tokens = SecretPatterns.find("", path)
            val value = values.foundIn(path)
            if (tokens.isEmpty() && !value) return false
            val shown = hidden(path)
            tokens.forEach { report.add(FindingKind.SECRET, shown, sha, it) }
            if (value) report.add(FindingKind.VARIABLE_OR_SECRET_VALUE, shown, sha, KnownValues.DETAIL)
            return true
        }

        private fun hidden(path: String): String {
            val parts = path.split('/').joinToString("/") { part -> if (hasSecret(part)) HIDDEN else part }
            // A secret with a "/" in it spans parts.
            return if (hasSecret(parts)) HIDDEN else parts
        }

        private fun hasSecret(text: String) = values.foundIn(text) || SecretPatterns.find("", text).isNotEmpty()

        private fun file(reader: ObjectReader, sha: String, change: Change) {
            val path = change.path
            PathRules.check(path)?.let { report.add(it.kind, path, sha, it.detail); return }
            if (Transcripts.applies(path) && Transcripts.found(head(reader, change.new.id))) {
                report.add(FindingKind.AI_DATA, path, sha, Transcripts.DETAIL)
                return
            }
            BuildOutputs.check(path)?.let { report.hold(Hold(HoldKind.BUILD_OUTPUT, path, sha, it)); return }
            content(reader, sha, change, path.substringAfterLast('/'))
        }

        /** The bytes that go to GitHub: their size, and the tokens and values they add. */
        private fun content(reader: ObjectReader, sha: String, change: Change, fileName: String) {
            val path = change.path
            val size = reader.getObjectSize(change.new.id, Constants.OBJ_BLOB)
            if (size > limits.maxFileBytes) {
                report.add(FindingKind.TOO_LARGE, path, sha, tooLarge(size))
                return
            }
            val content = small(reader, change.new.id) ?: return
            val previous by lazy { change.old?.takeIf { it.kind != Kind.SUBMODULE }?.let { small(reader, it.id) } }
            if (isBinary(content)) {
                if (values.newIn(content, previous)) {
                    report.add(FindingKind.VARIABLE_OR_SECRET_VALUE, path, sha, KnownValues.DETAIL)
                }
                return
            }
            val added = addedLines(content, previous)
            for (detail in SecretPatterns.find(fileName, added)) {
                report.add(FindingKind.SECRET, path, sha, detail)
            }
            if (values.foundIn(added)) report.add(FindingKind.VARIABLE_OR_SECRET_VALUE, path, sha, KnownValues.DETAIL)
        }

        /**
         * One hold per workflow path the branch changes, unless the owner approved what it ends
         * with. Only that can run: GitHub runs the workflows of the commit a branch points at, and
         * the gate pushes nothing else. A removed workflow runs nothing and holds nothing.
         */
        fun workflowHolds(reader: ObjectReader, tip: RevCommit, approved: Set<String>) {
            for ((path, touch) in workflows) {
                checkActive()
                val now = entryAt(reader, tip, path) ?: continue
                if (now == touch.before) continue
                val key = WorkflowChanges.approvalKey(path, now.id)
                if (key in approved || isTemplate(path, now)) continue
                val after = shown(reader, now)
                val (detail, diff) = if (after == null) {
                    "Changes GitHub Actions code in $path. It is too big to show here; " +
                        "read it in the session's files before approving." to ""
                } else {
                    val before = touch.before?.let { shown(reader, it) }
                    WorkflowChanges.describe(path, before?.toString(Charsets.UTF_8), after.toString(Charsets.UTF_8)) to
                        WorkflowChanges.diff(path, before, after)
                }
                report.hold(Hold(HoldKind.WORKFLOW_CHANGE, path, touch.commit, detail, key, diff))
            }
        }

        private fun isTemplate(path: String, entry: Entry) = entry.kind == Kind.FILE && templates()[path] == entry.id

        /** What the owner reads for an entry: a file's text, a link's target, a submodule's commit, as git shows them. */
        private fun shown(reader: ObjectReader, entry: Entry): ByteArray? = when (entry.kind) {
            Kind.SUBMODULE -> "Subproject commit ${entry.id.name}\n".toByteArray()
            else -> small(reader, entry.id)
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
                val new = entryOf(tree, COMMIT) ?: continue
                val old = entryOf(tree, FIRST_PARENT)
                if (new == old || (OTHER_PARENTS until tree.treeCount).any { entryOf(tree, it) == new }) continue
                changes += Change(tree.pathString, new, old)
            }
            return changes
        }
    }

    /** The entry at [path] in [commit], or null. */
    private fun entryAt(reader: ObjectReader, commit: RevCommit, path: String): Entry? =
        TreeWalk.forPath(reader, path, commit.tree)?.use { entryOf(it, 0) }

    /** Tree [index]'s entry where [tree] stands, or null where it has none (or a folder). */
    private fun entryOf(tree: TreeWalk, index: Int): Entry? {
        val kind = when (tree.getFileMode(index)) {
            FileMode.REGULAR_FILE, FileMode.EXECUTABLE_FILE -> Kind.FILE
            FileMode.SYMLINK -> Kind.LINK
            FileMode.GITLINK -> Kind.SUBMODULE
            // Git cannot push any other kind of entry.
            else -> return null
        }
        return Entry(tree.getObjectId(index), kind)
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
        const val COMMIT_AUTHOR = "commit author"

        /** Stands for the part of a name that holds a secret, so a finding never repeats it. */
        const val HIDDEN = "[hidden]"

        // Tree positions in the diff walk.
        const val FIRST_PARENT = 0
        const val COMMIT = 1
        const val OTHER_PARENTS = 2

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
