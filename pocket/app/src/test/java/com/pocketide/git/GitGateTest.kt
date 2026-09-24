package com.pocketide.git

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class GitGateTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var world: GitWorld
    private lateinit var gate: GitGate

    @Before
    fun setUp() {
        world = GitWorld(temp.root)
        gate = world.gate()
    }

    /** A "GitHub" repository and the phone's clone of it. */
    private fun cloned(name: String = "owner__proj", files: Map<String, String> = mapOf("README.md" to "hello\n")): Pair<File, File> {
        val remote = world.githubRepo(name, files)
        val bare = world.bare(name)
        runBlocking { gate.clone(world.url(remote), bare, TOKEN) }
        return remote to bare
    }

    private fun push(bare: File, branch: String, values: List<String> = emptyList()) =
        runBlocking { gate.push(bare, branch, TOKEN, values) }

    private fun <T> refused(block: suspend () -> T): GitGateException =
        assertThrows(GitGateException::class.java) { runBlocking { block() } }

    @Test
    fun `clone keeps GitHub's branches as remote-tracking refs and starts the default branch`() {
        val remote = world.githubRepo("owner__proj")
        val feature = world.commitToRemote(remote, "feature", mapOf("f.txt" to "f\n"), "Feature")
        val bare = world.bare("owner__proj")

        runBlocking { gate.clone(world.url(remote), bare, TOKEN) }

        val main = world.revParse(remote, "refs/heads/main")
        assertEquals(main, world.revParse(bare, "refs/remotes/origin/main"))
        assertEquals(feature, world.revParse(bare, "refs/remotes/origin/feature"))
        assertEquals(main, world.revParse(bare, "refs/heads/main"))
        assertNull(world.revParse(bare, "refs/heads/feature"))
        assertEquals("refs/heads/main", world.git(bare, "symbolic-ref", "HEAD"))
        assertEquals(canonicalConfig(world.url(remote)), File(bare, "config").readText())
        assertTrue(File(world.state, "staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `clone starts from GitHub's default branch whatever it is called`() {
        val remote = world.githubRepo("owner__proj")
        val trunk = world.commitToRemote(remote, "trunk", mapOf("t.txt" to "t\n"), "Trunk")
        world.git(remote, "symbolic-ref", "HEAD", "refs/heads/trunk")
        val bare = world.bare("owner__proj")

        runBlocking { gate.clone(world.url(remote), bare, TOKEN) }

        assertEquals("refs/heads/trunk", world.git(bare, "symbolic-ref", "HEAD"))
        assertEquals(trunk, world.revParse(bare, "refs/heads/trunk"))
        assertNull(world.revParse(bare, "refs/heads/main"))
    }

    @Test
    fun `cloning again brings the copy up to date, and a different repository is refused`() {
        val (remote, bare) = cloned()
        val newer = world.commitToRemote(remote, "main", mapOf("b.txt" to "b\n"), "Newer")

        runBlocking { gate.clone(world.url(remote), bare, TOKEN) }
        assertEquals(newer, world.revParse(bare, "refs/heads/main"))

        val other = world.githubRepo("other")
        assertEquals(GitMessages.ANOTHER_COPY, refused { gate.clone(world.url(other), bare, TOKEN) }.message)
    }

    @Test
    fun `the production gate talks only to GitHub over HTTPS`() {
        val production = JGitGate(world.repos, world.state)
        val remote = world.githubRepo("owner__proj")
        val bare = world.bare("owner__proj")

        assertEquals(GitMessages.NOT_GITHUB, refused { production.clone(world.url(remote), bare, TOKEN) }.message)
        assertFalse(bare.exists())
    }

    @Test
    fun `fetch moves the default branch forward but never over local work`() {
        val (remote, bare) = cloned()
        val newer = world.commitToRemote(remote, "main", mapOf("b.txt" to "b\n"), "Newer")
        runBlocking { gate.fetch(bare, TOKEN) }
        assertEquals(newer, world.revParse(bare, "refs/heads/main"))

        // A merge put on main here and not pushed yet.
        val local = world.git(bare, "commit-tree", "$newer^{tree}", "-p", newer, "-m", "Local merge")
        world.git(bare, "update-ref", "refs/heads/main", local)
        val newest = world.commitToRemote(remote, "main", mapOf("c.txt" to "c\n"), "Newest")
        runBlocking { gate.fetch(bare, TOKEN) }

        assertEquals(local, world.revParse(bare, "refs/heads/main"))
        assertEquals(newest, world.revParse(bare, "refs/remotes/origin/main"))
    }

    @Test
    fun `fetch leaves alone a default branch that a worktree has checked out`() {
        val (remote, bare) = cloned()
        val before = world.revParse(bare, "refs/heads/main")
        world.git(bare, "worktree", "add", "--quiet", File(world.work, "main").path, "main")
        val newer = world.commitToRemote(remote, "main", mapOf("b.txt" to "b\n"), "Newer")

        runBlocking { gate.fetch(bare, TOKEN) }

        assertEquals(before, world.revParse(bare, "refs/heads/main"))
        assertEquals(newer, world.revParse(bare, "refs/remotes/origin/main"))
    }

    @Test
    fun `fetch drops branches deleted on GitHub`() {
        val (remote, bare) = cloned()
        world.commitToRemote(remote, "old", mapOf("o.txt" to "o\n"), "Old")
        runBlocking { gate.fetch(bare, TOKEN) }
        assertNotNull(world.revParse(bare, "refs/remotes/origin/old"))

        world.git(remote, "branch", "-D", "old")
        runBlocking { gate.fetch(bare, TOKEN) }

        assertNull(world.revParse(bare, "refs/remotes/origin/old"))
    }

    @Test
    fun `push sends exactly the branch and records what GitHub has`() {
        val (remote, bare) = cloned()
        val work = world.worktree(bare, "s1", "pocket/claude/s1")
        val commit = world.commit(work, mapOf("a.txt" to "a\n"))

        assertEquals(PushResult.Pushed, push(bare, "pocket/claude/s1"))

        assertEquals(commit, world.revParse(remote, "refs/heads/pocket/claude/s1"))
        assertEquals(commit, world.revParse(bare, "refs/remotes/origin/pocket/claude/s1"))
        val again = runBlocking { gate.checkPost(bare, "pocket/claude/s1") }
        assertEquals(Verdict(ok = true, findings = emptyList(), commitsScanned = 0), again)
    }

    @Test
    fun `the check-post blocks a push and GitHub gets nothing`() {
        val (remote, bare) = cloned()
        val work = world.worktree(bare, "s1", "pocket/claude/s1")
        val commit = world.commit(work, mapOf("a.txt" to "a\n", ".env" to "API_KEY=abc\n"))

        val result = push(bare, "pocket/claude/s1")

        val expected = Verdict(false, listOf(Finding(FindingKind.SECRET, ".env", commit.take(7), PathRules.ENV_FILE)), 1)
        assertEquals(PushResult.Blocked(expected), result)
        assertNull(world.revParse(remote, "refs/heads/pocket/claude/s1"))
    }

    @Test
    fun `a project's Variables and Secrets never reach GitHub`() {
        val (remote, bare) = cloned()
        val work = world.worktree(bare, "s1", "pocket/claude/s1")
        world.commit(work, mapOf("app.js" to "const key = 'sup3r-s3cret-value'\n"))

        val result = push(bare, "pocket/claude/s1", values = listOf("sup3r-s3cret-value"))

        val finding = (result as PushResult.Blocked).verdict.findings.single()
        assertEquals(FindingKind.VARIABLE_OR_SECRET_VALUE, finding.kind)
        assertFalse(result.toString().contains("sup3r-s3cret-value"))
        assertNull(world.revParse(remote, "refs/heads/pocket/claude/s1"))
    }

    @Test
    fun `newer commits on GitHub are reported and never overwritten`() {
        val (remote, bare) = cloned()
        val work = world.worktree(bare, "s1", "pocket/claude/s1")
        world.commit(work, mapOf("a.txt" to "a\n"))
        assertEquals(PushResult.Pushed, push(bare, "pocket/claude/s1"))
        val theirs = world.commitToRemote(remote, "pocket/claude/s1", mapOf("b.txt" to "b\n"), "From elsewhere")
        world.commit(work, mapOf("c.txt" to "c\n"))

        assertEquals(PushResult.Rejected(GitMessages.NEWER_ON_GITHUB), push(bare, "pocket/claude/s1"))
        assertEquals(theirs, world.revParse(remote, "refs/heads/pocket/claude/s1"))
    }

    @Test
    fun `a workflow change waits for the owner, and goes through once approved`() {
        val (remote, bare) = cloned()
        val work = world.worktree(bare, "s1", "pocket/claude/s1")
        world.commit(work, mapOf(".github/workflows/leak.yml" to "on: push\njobs:\n  a:\n    env:\n      ALL: \${{ toJSON(secrets) }}\n"))

        val hold = (push(bare, "pocket/claude/s1") as PushResult.Blocked).verdict.holds.single()
        assertEquals(HoldKind.WORKFLOW_CHANGE, hold.kind)
        assertNull(world.revParse(remote, "refs/heads/pocket/claude/s1"))

        runBlocking { gate.approveWorkflowChange(bare, requireNotNull(hold.approvalKey)) }
        // The approval is the gate's own record: fetching, or Linux rewriting the repo, keeps it.
        runBlocking { gate.fetch(bare, TOKEN) }
        File(bare, "config").writeText("[core]\n\tbare = true\n")

        assertEquals(PushResult.Pushed, push(bare, "pocket/claude/s1"))
        assertNotNull(world.revParse(remote, "refs/heads/pocket/claude/s1"))
        assertEquals(
            GitMessages.NOT_AN_APPROVAL,
            refused { gate.approveWorkflowChange(bare, "0".repeat(40) + ":src/App.kt") }.message,
        )
    }

    @Test
    fun `a build output never reaches GitHub`() {
        val (remote, bare) = cloned()
        val work = world.worktree(bare, "s1", "pocket/claude/s1")
        world.commit(work, mapOf("app/release/app-release.aab" to "PK\u0003\u0004"))

        val hold = (push(bare, "pocket/claude/s1") as PushResult.Blocked).verdict.holds.single()

        assertEquals(HoldKind.BUILD_OUTPUT, hold.kind)
        assertNull("a build output cannot be approved", hold.approvalKey)
        assertNull(world.revParse(remote, "refs/heads/pocket/claude/s1"))
    }

    @Test
    fun `a clone made under the projects module's partial name is the same repo once renamed`() {
        val remote = world.githubRepo("owner__proj")
        world.commitToRemote(remote, "main", mapOf(".env" to "OLD=1\n"), "Long ago")
        val partial = File(world.repos, ".owner__proj.git.partial")
        runBlocking { gate.clone(world.url(remote), partial, TOKEN) }
        val bare = world.bare("owner__proj")
        Files.move(partial.toPath(), bare.toPath())

        // The gate still knows what GitHub has: history already there is not checked again.
        assertEquals(Verdict(true, emptyList(), 0), runBlocking { gate.checkPost(bare, "main") })
        val newer = world.commitToRemote(remote, "main", mapOf("b.txt" to "b\n"), "Newer")
        runBlocking { gate.fetch(bare, TOKEN) }
        assertEquals(newer, world.revParse(bare, "refs/heads/main"))
    }

    @Test
    fun `a hook planted in the repo never runs`() {
        val (_, bare) = cloned()
        val work = world.worktree(bare, "s1", "pocket/claude/s1")
        world.commit(work, mapOf("a.txt" to "a\n"))
        val marker = File(temp.root, "hook-ran")
        val hook = File(bare, "hooks/pre-push")
        hook.parentFile?.mkdirs()
        hook.writeText("#!/bin/sh\necho ran > '${marker.path}'\n")
        assertTrue(hook.setExecutable(true))

        // The hook is real: git runs it, and so does JGit with its usual file system.
        val elsewhere = world.emptyRemote("elsewhere")
        world.git(work, "push", "--quiet", world.url(elsewhere), "HEAD:refs/heads/by-git")
        assertTrue(marker.delete())
        FileRepositoryBuilder().setGitDir(bare).setBare().build().use { repo ->
            Git(repo).push().setRemote(world.url(elsewhere)).setRefSpecs(RefSpec("refs/heads/pocket/claude/s1:refs/heads/by-jgit")).call()
        }
        assertTrue(marker.delete())

        assertEquals(PushResult.Pushed, push(bare, "pocket/claude/s1"))
        runBlocking { gate.fetch(bare, TOKEN) }
        assertFalse(marker.exists())
    }

    @Test
    fun `a hostile config in the repo is replaced before every step`() {
        val (remote, bare) = cloned()
        val evil = world.githubRepo("evil")
        val work = world.worktree(bare, "s1", "pocket/claude/s1")
        val commit = world.commit(work, mapOf("a.txt" to "a\n"))
        val hooks = File(temp.root, "evil-hooks").apply { mkdirs() }
        val marker = File(temp.root, "hostile-config-ran")
        File(hooks, "pre-push").apply { writeText("#!/bin/sh\ntouch '${marker.path}'\n") }.setExecutable(true)
        val hostile = """
            |[core]
            |	bare = true
            |	hooksPath = ${hooks.path}
            |[remote "origin"]
            |	url = ${world.url(evil)}
            |	pushurl = ${world.url(evil)}
            |[remote "${world.url(remote)}"]
            |	url = ${world.url(evil)}
            |[url "${world.url(evil)}"]
            |	insteadOf = ${world.url(remote)}
            |[http]
            |	extraHeader = Authorization: Basic ZXZpbA==
            |	sslVerify = false
            |[credential]
            |	helper = !touch '${marker.path}'
            |""".trimMargin()
        File(bare, "config").writeText(hostile)

        assertEquals(PushResult.Pushed, push(bare, "pocket/claude/s1"))
        assertEquals(commit, world.revParse(remote, "refs/heads/pocket/claude/s1"))
        assertNull(world.revParse(evil, "refs/heads/pocket/claude/s1"))
        assertEquals(canonicalConfig(world.url(remote)), File(bare, "config").readText())

        File(bare, "config").writeText(hostile)
        val newer = world.commitToRemote(remote, "main", mapOf("b.txt" to "b\n"), "Newer")
        runBlocking { gate.fetch(bare, TOKEN) }
        assertEquals(newer, world.revParse(bare, "refs/remotes/origin/main"))
        assertEquals(canonicalConfig(world.url(remote)), File(bare, "config").readText())
        assertFalse(marker.exists())
    }

    @Test
    fun `JGit keeps the canonical config even if Linux rewrites the file mid-step`() {
        val (remote, bare) = cloned()

        val seen = runBlocking {
            gate.withRepository(bare) { repo ->
                File(bare, "config").writeText("[http]\n\tsslVerify = false\n\textraHeader = X-Evil: 1\n[core]\n\thooksPath = /tmp\n")
                listOf(
                    repo.config.getString("http", null, "sslVerify"),
                    repo.config.getString("http", null, "extraHeader"),
                    repo.config.getString("core", null, "hooksPath"),
                    repo.config.getString("remote", "origin", "url"),
                )
            }
        }

        assertEquals(listOf(null, null, null, world.url(remote)), seen)
    }

    @Test
    fun `objects borrowed from outside the repo are refused`() {
        val (remote, bare) = cloned()
        val alternates = File(bare, "objects/info/alternates")
        alternates.parentFile?.mkdirs()

        alternates.writeText(File(remote, "objects").path + "\n")
        assertEquals(GitMessages.FOREIGN_OBJECTS, refused { gate.fetch(bare, TOKEN) }.message)
        assertEquals(GitMessages.FOREIGN_OBJECTS, refused { gate.checkPost(bare, "main") }.message)
        assertEquals(PushResult.Failed(GitMessages.FOREIGN_OBJECTS), push(bare, "main"))

        alternates.writeText("../objects\n")
        runBlocking { gate.fetch(bare, TOKEN) }
    }

    @Test
    fun `a shallow list made in Linux hides nothing and is dropped`() {
        val (_, bare) = cloned()
        val work = world.worktree(bare, "s1", "pocket/claude/s1")
        world.commit(work, mapOf(".env" to "API_KEY=abc\n"))
        world.git(work, "rm", "--quiet", ".env")
        val cleaned = world.commit(work, mapOf("a.txt" to "a\n"))
        val shallow = File(bare, "shallow").apply { writeText(cleaned + "\n") }

        assertTrue(push(bare, "pocket/claude/s1") is PushResult.Blocked)
        assertFalse(shallow.exists())
    }

    @Test
    fun `a remote-tracking ref made in Linux hides nothing from the check-post`() {
        val (_, bare) = cloned()
        val work = world.worktree(bare, "s1", "pocket/claude/s1")
        world.commit(work, mapOf(".env" to "API_KEY=abc\n"))
        world.git(bare, "update-ref", "refs/remotes/origin/pocket/claude/s1", "refs/heads/pocket/claude/s1")

        val verdict = runBlocking { gate.checkPost(bare, "pocket/claude/s1") }

        assertEquals(listOf(".env"), verdict.findings.map(Finding::path))
        assertTrue(push(bare, "pocket/claude/s1") is PushResult.Blocked)
    }

    @Test
    fun `stats count the session's own commits and files, a rename once`() {
        val (remote, bare) = cloned(files = mapOf("README.md" to "hello\n", "src/app.txt" to "line\n".repeat(20)))
        val work = world.worktree(bare, "s1", "pocket/claude/s1")
        world.commit(work, mapOf("README.md" to "hello again\n"))
        world.git(work, "mv", "src/app.txt", "src/main.txt")
        world.git(work, "commit", "--quiet", "-m", "Rename")
        world.commit(work, mapOf("new.txt" to "new\n"))
        // Main moves on meanwhile; that is not the session's work.
        world.commitToRemote(remote, "main", mapOf("other.txt" to "o\n"), "Elsewhere")
        runBlocking { gate.fetch(bare, TOKEN) }

        assertEquals(3 to 3, runBlocking { gate.stats(bare, "pocket/claude/s1", "main") })
        assertEquals(0 to 0, runBlocking { gate.stats(bare, "pocket/claude/none", "main") })
    }

    @Test
    fun `session branches can be deleted on GitHub, other branches never`() {
        val (remote, bare) = cloned()
        val work = world.worktree(bare, "s1", "pocket/claude/s1")
        world.commit(work, mapOf("a.txt" to "a\n"))
        assertEquals(PushResult.Pushed, push(bare, "pocket/claude/s1"))

        assertEquals(PushResult.Pushed, runBlocking { gate.deleteRemoteBranch(bare, "pocket/claude/s1", TOKEN) })
        assertNull(world.revParse(remote, "refs/heads/pocket/claude/s1"))
        assertNull(world.revParse(bare, "refs/remotes/origin/pocket/claude/s1"))
        assertNotNull("the local branch stays", world.revParse(bare, "refs/heads/pocket/claude/s1"))

        assertEquals(PushResult.Pushed, runBlocking { gate.deleteRemoteBranch(bare, "pocket/claude/s1", TOKEN) })
        assertEquals(
            PushResult.Failed(GitMessages.ONLY_SESSION_BRANCHES),
            runBlocking { gate.deleteRemoteBranch(bare, "main", TOKEN) },
        )
        assertNotNull(world.revParse(remote, "refs/heads/main"))
    }

    @Test
    fun `worktrees made in Linux keep working and are never pruned`() {
        val (_, bare) = cloned()
        val work = world.worktree(bare, "s1", "pocket/claude/s1")
        world.commit(work, mapOf("a.txt" to "a\n"))

        runBlocking {
            gate.fetch(bare, TOKEN)
            gate.push(bare, "pocket/claude/s1", TOKEN)
        }

        assertEquals("", world.git(work, "status", "--porcelain"))
        assertTrue(world.git(bare, "worktree", "list").contains(work.path))
        // Another room cannot see this worktree; git's clean-up there must not remove it.
        assertTrue(work.renameTo(File(temp.root, "hidden-s1")))
        world.git(bare, "gc", "--quiet")
        assertTrue(File(bare, "worktrees/s1").isDirectory)
    }

    @Test
    fun `a repo behind a symlink or outside the repos folder is refused`() {
        val (_, bare) = cloned()
        val link = File(world.repos, "link.git")
        Files.createSymbolicLink(link.toPath(), bare.toPath())
        assertEquals(GitMessages.UNSAFE_COPY, refused { gate.fetch(link, TOKEN) }.message)

        val outside = File(temp.root, "outside.git")
        bare.copyRecursively(outside)
        assertEquals(GitMessages.UNSAFE_COPY, refused { gate.fetch(outside, TOKEN) }.message)

        // The repos folder itself swapped for a link.
        val moved = File(temp.root, "repos-moved")
        assertTrue(world.repos.renameTo(moved))
        Files.createSymbolicLink(world.repos.toPath(), moved.toPath())
        assertEquals(GitMessages.UNSAFE_COPY, refused { gate.fetch(bare, TOKEN) }.message)
    }

    @Test
    fun `a directory in the repo swapped for a link is refused and nothing is written through it`() {
        val (_, bare) = cloned()
        val appFolder = File(temp.root, "app-private").apply { mkdirs() }
        val remotes = File(bare, "refs/remotes")
        deleteTree(remotes)
        Files.createSymbolicLink(remotes.toPath(), appFolder.toPath())

        assertEquals(GitMessages.UNSAFE_COPY, refused { gate.fetch(bare, TOKEN) }.message)
        assertEquals(PushResult.Failed(GitMessages.UNSAFE_COPY), push(bare, "main"))
        assertTrue(appFolder.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `branch names git would not accept are refused`() {
        val (_, bare) = cloned()

        assertEquals(GitMessages.invalidBranch("bad..name"), refused { gate.checkPost(bare, "bad..name") }.message)
        assertEquals(PushResult.Failed(GitMessages.invalidBranch("has space")), push(bare, "has space"))
        assertEquals(GitMessages.missingBranch("pocket/claude/none"), refused { gate.checkPost(bare, "pocket/claude/none") }.message)
    }
}
