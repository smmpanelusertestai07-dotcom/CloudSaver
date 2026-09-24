package com.pocketide.git

import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CheckPostTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var commits: TestCommits

    @Before
    fun setUp() {
        HermeticJGit.install()
        commits = TestCommits(File(temp.root, "scan.git"))
    }

    @After
    fun tearDown() = commits.close()

    private fun check(
        tip: ObjectId,
        onGitHub: List<ObjectId> = emptyList(),
        values: List<String> = emptyList(),
        limits: CheckPostLimits = CheckPostLimits(),
        approved: List<String> = emptyList(),
    ): Verdict = CheckPost(limits).check(commits.repo, tip, onGitHub, values, approved) {}

    private fun short(id: ObjectId) = id.name.take(7)

    @Test
    fun `clean commits pass`() {
        val first = commits.commit(mapOf("README.md" to "hello\n"))
        val second = commits.commit(mapOf("README.md" to "hello\n", "src/App.kt" to "fun main() {}\n"), first)
        val third = commits.commit(mapOf("README.md" to "hello again\n", "src/App.kt" to "fun main() {}\n"), second)

        assertEquals(Verdict(ok = true, findings = emptyList(), commitsScanned = 3), check(third))
    }

    @Test
    fun `each kind of problem blocks, with its path and commit`() {
        val base = commits.commit(mapOf("README.md" to "hello\n"))
        val cases = mapOf(
            ".env" to FindingKind.SECRET,
            "keys/upload.jks" to FindingKind.SECRET,
            "home/.ssh/id_rsa" to FindingKind.SECRET,
            ".claude/projects/-root/4f1c.jsonl" to FindingKind.AI_DATA,
            ".codex/auth.json" to FindingKind.AI_DATA,
            ".gemini/antigravity/brain/1/task.md" to FindingKind.AI_DATA,
        )
        cases.forEach { (path, kind) ->
            val tip = commits.commit(mapOf("README.md" to "hello\n", path to "anything\n"), base)
            val verdict = check(tip, onGitHub = listOf(base))
            assertEquals(path, listOf(Finding(kind, path, short(tip), PathRules.check(path)?.detail.orEmpty())), verdict.findings)
            assertFalse(path, verdict.ok)
        }
    }

    @Test
    fun `tokens in added lines block`() {
        val token = fake("gh" + "p_", 36)
        val tip = commits.commit(mapOf("src/config.kt" to "val token = \"$token\"\n"))

        val finding = check(tip).findings.single()
        assertEquals(FindingKind.SECRET, finding.kind)
        assertEquals("src/config.kt", finding.path)
        assertEquals("Contains a GitHub token.", finding.detail)
        assertFalse("the token is never repeated", finding.toString().contains(token))
    }

    @Test
    fun `only the lines a commit adds are searched`() {
        val token = fake("gh" + "p_", 36)
        val onGitHub = commits.commit(mapOf("config.txt" to "token = $token\nlevel = 1\n"))
        val changed = commits.commit(mapOf("config.txt" to "token = $token\nlevel = 2\n"), onGitHub)
        assertTrue(check(changed, onGitHub = listOf(onGitHub)).ok)

        val copied = commits.commit(mapOf("config.txt" to "token = $token\nlevel = 2\n", "other.txt" to "t = $token\n"), changed)
        val verdict = check(copied, onGitHub = listOf(onGitHub))
        assertEquals(listOf("other.txt"), verdict.findings.map(Finding::path))
    }

    @Test
    fun `commits GitHub already has are not checked again`() {
        val old = commits.commit(mapOf(".env" to "KEY=1\n"))
        val tip = commits.commit(mapOf(".env" to "KEY=1\n", "a.txt" to "a\n"), old)

        assertEquals(Verdict(ok = true, findings = emptyList(), commitsScanned = 1), check(tip, onGitHub = listOf(old)))
        assertFalse(check(tip).ok)
    }

    @Test
    fun `a root commit is compared with nothing`() {
        val root = commits.commit(mapOf(".env.local" to "KEY=1\n"))

        assertEquals(FindingKind.SECRET, check(root).findings.single().kind)
    }

    @Test
    fun `a merge answers only for what it adds itself`() {
        val start = commits.commit(mapOf("README.md" to "hello\n"))
        // Long on GitHub: main once received a legacy file with a token in it.
        val main = commits.commit(mapOf("README.md" to "hello\n", "legacy.txt" to fake("gh" + "p_", 36)), start)
        val session = commits.commit(mapOf("README.md" to "hello\n", "feature.txt" to "new\n"), start)
        val mergeMain = commits.commit(
            mapOf("README.md" to "hello\n", "feature.txt" to "new\n", "legacy.txt" to fake("gh" + "p_", 36)),
            session, main,
        )
        assertTrue(check(mergeMain, onGitHub = listOf(main)).ok)

        // A side branch that is not on GitHub is checked commit by commit.
        val side = commits.commit(mapOf("README.md" to "hello\n", ".env" to "KEY=1\n"), start)
        val mergeSide = commits.commit(mapOf("README.md" to "hello\n", "feature.txt" to "new\n", ".env" to "KEY=1\n"), session, side)
        val findings = check(mergeSide, onGitHub = listOf(start)).findings
        assertEquals(listOf(Finding(FindingKind.SECRET, ".env", short(side), PathRules.ENV_FILE)), findings)
    }

    @Test
    fun `a file changed in several commits is reported once, at the oldest`() {
        val first = commits.commit(mapOf(".env" to "KEY=1\n"))
        val second = commits.commit(mapOf(".env" to "KEY=2\n"), first)

        assertEquals(listOf(Finding(FindingKind.SECRET, ".env", short(first), PathRules.ENV_FILE)), check(second).findings)
    }

    @Test
    fun `commit messages are checked too`() {
        val token = fake("gh" + "p_", 36)
        val tip = commits.commit(mapOf("a.txt" to "a\n"), message = "Use $token for the API")
        val withValue = commits.commit(mapOf("a.txt" to "b\n"), tip, message = "Set the key to sup3r-s3cret-value")

        val findings = check(withValue, values = listOf("sup3r-s3cret-value")).findings
        assertEquals(
            listOf(
                Finding(FindingKind.SECRET, "commit message", short(tip), "Contains a GitHub token."),
                Finding(FindingKind.VARIABLE_OR_SECRET_VALUE, "commit message", short(withValue), KnownValues.DETAIL),
            ),
            findings,
        )
    }

    @Test
    fun `Variables and Secrets block when a commit adds them`() {
        val value = "sup3r-s3cret-value"
        val onGitHub = commits.commit(mapOf("keep.txt" to "old = $value\n"))
        val unchanged = commits.commit(mapOf("keep.txt" to "old = $value\nmore\n"), onGitHub)
        assertTrue(check(unchanged, onGitHub = listOf(onGitHub), values = listOf(value)).ok)

        val added = commits.commit(mapOf("keep.txt" to "old = $value\nmore\n", "app.js" to "const key = '$value'\n"), unchanged)
        val finding = check(added, onGitHub = listOf(onGitHub), values = listOf(value, "short")).findings.single()
        assertEquals(Finding(FindingKind.VARIABLE_OR_SECRET_VALUE, "app.js", short(added), KnownValues.DETAIL), finding)
        assertFalse(finding.toString().contains(value))
    }

    @Test
    fun `binary files are not searched for tokens but are for Variables and Secrets`() {
        val token = fake("gh" + "p_", 36)
        val binary = byteArrayOf(0, 1, 2, 3) + token.toByteArray() + "sup3r-s3cret-value".toByteArray()
        val tip = commits.commit(mapOf("image.bin" to binary))

        assertTrue(check(tip).ok)
        assertEquals(FindingKind.VARIABLE_OR_SECRET_VALUE, check(tip, values = listOf("sup3r-s3cret-value")).findings.single().kind)
    }

    @Test
    fun `a symlink carries no content`() {
        val tip = commits.commit(mapOf(".env" to "../shared/.env"), modes = mapOf(".env" to FileMode.SYMLINK))

        assertTrue(check(tip).ok)
    }

    @Test
    fun `a transcript is AI data whatever it is called`() {
        val claude = """{"parentUuid":null,"sessionId":"4f1c","type":"user","message":{"role":"user","content":"hi"}}"""
        val tip = commits.commit(mapOf("notes/chat.jsonl" to "$claude\n", "data/events.jsonl" to "{\"id\":1}\n"))

        val finding = check(tip).findings.single()
        assertEquals(Finding(FindingKind.AI_DATA, "notes/chat.jsonl", short(tip), Transcripts.DETAIL), finding)
    }

    @Test
    fun `a file over GitHub's 100 MiB limit blocks`() {
        val limit = 100L * 1024 * 1024
        val tip = commits.commit(mapOf("at-limit.bin" to commits.zeros(limit), "over-limit.bin" to commits.zeros(limit + 1)))

        val finding = check(tip).findings.single()
        assertEquals(FindingKind.TOO_LARGE, finding.kind)
        assertEquals("over-limit.bin", finding.path)
        assertEquals("100.0 MB. GitHub does not accept files over 100 MB.", finding.detail)
    }

    @Test
    fun `big files are not searched, but their paths still count`() {
        val limits = CheckPostLimits(maxSearchBytes = 1024)
        val big = "x".repeat(2048) + "\n" + fake("gh" + "p_", 36) + "\n"
        val tip = commits.commit(mapOf("big.txt" to big, "big/.env" to big))

        assertEquals(listOf("big/.env"), check(tip, limits = limits).findings.map(Finding::path))
    }

    @Test
    fun `findings stop at the cap and still block`() {
        val files = (1..5).associate { "app$it/.env" to "KEY=$it\n" }
        val verdict = check(commits.commit(files), limits = CheckPostLimits(maxFindings = 3))

        assertFalse(verdict.ok)
        assertEquals(3, verdict.findings.size)
    }

    @Test
    fun `a build output holds the push without being a finding`() {
        val base = commits.commit(mapOf("README.md" to "hello\n"))
        val tip = commits.commit(mapOf("README.md" to "hello\n", "release/app-release.apk" to byteArrayOf(0x50, 0x4b, 3, 4)), base)

        val verdict = check(tip, onGitHub = listOf(base))

        assertFalse(verdict.ok)
        assertEquals(emptyList<Finding>(), verdict.findings)
        val hold = verdict.holds.single()
        assertEquals(HoldKind.BUILD_OUTPUT, hold.kind)
        assertEquals("release/app-release.apk", hold.path)
        assertEquals(short(tip), hold.commit)
        assertEquals(null, hold.approvalKey)
    }

    @Test
    fun `a workflow change holds the push until its exact content is approved`() {
        val old = "on: workflow_dispatch\njobs: {}\n"
        val base = commits.commit(mapOf(WORKFLOW to old))
        val first = commits.commit(mapOf(WORKFLOW to "on: push\njobs: {}\n"), base)
        val tip = commits.commit(mapOf(WORKFLOW to "on: push\njobs:\n  a: ${'$'}{{ secrets.DEPLOY }}\n", "a.txt" to "a\n"), first)

        val held = check(tip, onGitHub = listOf(base))
        assertFalse(held.ok)
        assertEquals(emptyList<Finding>(), held.findings)
        val hold = held.holds.single()
        assertEquals(HoldKind.WORKFLOW_CHANGE, hold.kind)
        assertEquals(WORKFLOW, hold.path)
        assertEquals("the oldest commit that changes it", short(first), hold.commit)
        assertTrue(hold.detail, hold.detail.contains("It newly uses the Secrets DEPLOY."))
        assertTrue(hold.detail, hold.detail.contains("It runs on: push."))
        assertTrue(hold.diff, hold.diff.contains("\n-on: workflow_dispatch\n-jobs: {}\n+on: push\n"))
        val key = requireNotNull(hold.approvalKey)

        assertEquals(Verdict(true, emptyList(), 2), check(tip, onGitHub = listOf(base), approved = listOf(key)))

        // Changed again after the approval: the new content needs its own.
        val later = commits.commit(mapOf(WORKFLOW to "on: push\njobs:\n  a: ${'$'}{{ toJSON(secrets) }}\n", "a.txt" to "a\n"), tip)
        val again = check(later, onGitHub = listOf(base), approved = listOf(key)).holds.single()
        assertTrue(again.approvalKey != key)
    }

    @Test
    fun `a workflow change that is undone, or already on GitHub, holds nothing`() {
        val workflow = "on: workflow_dispatch\n"
        val base = commits.commit(mapOf(WORKFLOW to workflow))
        val changed = commits.commit(mapOf(WORKFLOW to "on: push\n"), base)
        val undone = commits.commit(mapOf(WORKFLOW to workflow, "a.txt" to "a\n"), changed)
        assertTrue(check(undone, onGitHub = listOf(base)).ok)

        val removed = commits.commit(mapOf("a.txt" to "a\n"), changed)
        assertTrue(check(removed, onGitHub = listOf(base)).ok)

        // Main changed a workflow on GitHub; the session merges main in.
        val session = commits.commit(mapOf(WORKFLOW to workflow, "s.txt" to "s\n"), base)
        val mainOnGitHub = commits.commit(mapOf(WORKFLOW to "on: [push]\n"), base)
        val merge = commits.commit(mapOf(WORKFLOW to "on: [push]\n", "s.txt" to "s\n"), session, mainOnGitHub)
        assertTrue(check(merge, onGitHub = listOf(mainOnGitHub)).ok)
    }

    @Test
    fun `a secret in a workflow is a finding, not only a hold`() {
        val token = fake("gh" + "p_", 36)
        val tip = commits.commit(mapOf(WORKFLOW to "on: push\nenv:\n  T: $token\n"))

        val verdict = check(tip)

        assertEquals(listOf(FindingKind.SECRET), verdict.findings.map(Finding::kind))
        assertEquals(listOf(HoldKind.WORKFLOW_CHANGE), verdict.holds.map(Hold::kind))
    }

    @Test
    fun `a shallow list planted in the repo hides no commit`() {
        val leak = commits.commit(mapOf(".env" to "KEY=1\n"))
        val cleaned = commits.commit(mapOf("a.txt" to "a\n"), leak)
        val tip = commits.commit(mapOf("a.txt" to "b\n"), cleaned)
        File(commits.repo.directory, "shallow").writeText(cleaned.name + "\n")

        val verdict = check(tip)

        assertEquals(listOf(Finding(FindingKind.SECRET, ".env", short(leak), PathRules.ENV_FILE)), verdict.findings)
        assertEquals(3, verdict.commitsScanned)
    }

    @Test
    fun `a cancelled check stops`() {
        val tip = commits.commit(mapOf("a.txt" to "a\n"))

        assertThrows(CancellationException::class.java) {
            CheckPost().check(commits.repo, tip, emptyList(), emptyList()) { throw CancellationException("cancelled") }
        }
    }

    private companion object {
        const val WORKFLOW = ".github/workflows/build.yml"
    }
}
