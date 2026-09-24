package com.pocketide.git

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.URIish
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What a room in Linux can do to a bare repo, and what the gate makes of it. */
class HostileRepoTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var world: GitWorld
    private lateinit var gate: GitGate

    @Before
    fun setUp() {
        world = GitWorld(temp.root)
        gate = world.gate()
    }

    private fun cloned(name: String = "owner__proj"): Pair<File, File> {
        val remote = world.githubRepo(name)
        val bare = world.bare(name)
        runBlocking { gate.clone(world.url(remote), bare, TOKEN) }
        return remote to bare
    }

    private fun <T> refused(block: suspend () -> T): GitGateException =
        assertThrows(GitGateException::class.java) { runBlocking { block() } }

    /** A blob that only a store outside the repos folder holds. */
    private fun outsideBlob(): Pair<File, ObjectId> {
        val outside = File(temp.root, "app-private/store.git")
        world.git(temp.root, "init", "--quiet", "--bare", outside.path)
        val input = File(temp.root, "outside.txt").apply { writeText("only outside\n") }
        val id = world.git(outside, "hash-object", "-w", input.path)
        return File(outside, "objects") to ObjectId.fromString(id)
    }

    private fun seen(bare: File, id: ObjectId): Boolean = runBlocking { gate.withRepository(bare) { it.objectDatabase.has(id) } }

    @Test
    fun `objects are never borrowed through a chain of alternates`() {
        val (_, bare) = cloned()
        val (outsideObjects, id) = outsideBlob()
        // The first entry stays inside the repo; the store it names borrows from outside.
        File(bare, "objects/info/alternates").writeText("info/chain\n")
        File(bare, "objects/info/chain/info").mkdirs()
        File(bare, "objects/info/chain/info/alternates").writeText(outsideObjects.path + "\n")

        assertFalse(seen(bare, id))
    }

    @Test
    fun `objects are never borrowed from a repo nested in the objects folder`() {
        val (_, bare) = cloned()
        val (outsideObjects, id) = outsideBlob()
        val nested = File(bare, "objects/info/nested")
        listOf("objects/info", "refs").forEach { File(nested, it).mkdirs() }
        File(nested, "HEAD").writeText("ref: refs/heads/main\n")
        File(nested, "objects/info/alternates").writeText(outsideObjects.path + "\n")
        File(bare, "objects/info/alternates").writeText("info/nested/objects\n")

        assertFalse(seen(bare, id))
    }

    @Test
    fun `a link planted while a step runs gets nothing written through it`() {
        val (remote, bare) = cloned()
        val appFile = File(temp.root, "app-private/projects.json").apply {
            parentFile?.mkdirs()
            writeText("{}")
        }
        world.commitToRemote(remote, "main", mapOf("b.txt" to "b\n"), "Newer")
        // Linux wins the race: the link appears after the gate checked the repo.
        val racing = object : RemotePolicy by LocalRemotes {
            override fun allowed(url: String): URIish {
                val log = File(bare, "logs/refs/remotes/origin/main")
                log.parentFile?.mkdirs()
                if (!Files.isSymbolicLink(log.toPath())) Files.createSymbolicLink(log.toPath(), appFile.toPath())
                return LocalRemotes.allowed(url)
            }
        }

        runBlocking { world.gate(remotes = racing).fetch(bare, TOKEN) }

        assertEquals("{}", appFile.readText())
    }

    @Test
    fun `the gate writes no reflog into the repo`() {
        val (remote, bare) = cloned()
        val log = File(bare, "logs/refs/remotes/origin/main").apply {
            parentFile?.mkdirs()
            writeText("")
        }
        world.commitToRemote(remote, "main", mapOf("b.txt" to "b\n"), "Newer")

        runBlocking { gate.fetch(bare, TOKEN) }

        assertEquals("", log.readText())
    }

    @Test
    fun `a clone under the temporary name that fails keeps the finished repo's record`() {
        val (remote, bare) = cloned()
        world.commitToRemote(remote, "main", mapOf(".env" to "OLD=1\n"), "Long ago")
        runBlocking { gate.fetch(bare, TOKEN) }
        val partial = File(world.repos, ".owner__proj.git.partial")

        refused { gate.clone(world.url(File(world.github, "missing.git")), partial, TOKEN) }

        assertFalse(partial.exists())
        // The gate still knows what GitHub has, so history already there is not checked again.
        assertEquals(Verdict(true, emptyList(), 0), runBlocking { gate.checkPost(bare, "main") })
    }

    @Test
    fun `a clone that fails leaves nothing behind`() {
        val bare = world.bare("owner__proj")

        val error = refused { gate.clone(world.url(File(world.github, "missing.git")), bare, TOKEN) }

        assertEquals(GitMessages.NO_REPO, error.message)
        assertFalse(bare.exists())
        assertTrue(File(world.state, "staging").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a corrupt object stops the push, and GitHub gets nothing`() {
        val (remote, bare) = cloned()
        val work = world.worktree(bare, "s1", "pocket/claude/s1")
        val blob = world.git(work, "hash-object", "-w", File(work, "README.md").apply { writeText("changed\n") }.path)
        world.commit(work, mapOf("README.md" to "changed\n"))
        val loose = File(bare, "objects/${blob.take(2)}/${blob.drop(2)}")
        assertTrue(loose.setWritable(true))
        loose.writeBytes(byteArrayOf(0x78, 0x01, 0x00))

        val result = runBlocking { gate.push(bare, "pocket/claude/s1", TOKEN) }

        assertTrue(result.toString(), result is PushResult.Failed)
        assertNull(world.revParse(remote, "refs/heads/pocket/claude/s1"))
    }

    @Test
    fun `a branch pointing at something that is not a commit is refused`() {
        val (remote, bare) = cloned()
        val blob = world.git(bare, "hash-object", "-w", File(temp.root, "x.txt").apply { writeText("x\n") }.path)
        // git itself refuses this; a room can still write the file.
        File(bare, "refs/heads/pocket/claude/odd").apply { parentFile?.mkdirs() }.writeText("$blob\n")

        assertEquals(GitMessages.FAILED, refused { gate.checkPost(bare, "pocket/claude/odd") }.message)
        assertEquals(PushResult.Failed(GitMessages.FAILED), runBlocking { gate.push(bare, "pocket/claude/odd", TOKEN) })
        assertNull(world.revParse(remote, "refs/heads/pocket/claude/odd"))
    }

    @Test
    fun `steps on one repo wait for each other and all finish`() {
        val (remote, bare) = cloned()
        val branches = (1..4).map { "pocket/claude/s$it" }
        branches.forEachIndexed { i, branch ->
            world.commit(world.worktree(bare, "s$i", branch), mapOf("f$i.txt" to "$i\n"))
        }
        world.commitToRemote(remote, "main", mapOf("m.txt" to "m\n"), "Main moved")

        val results = runBlocking(Dispatchers.Default) {
            val pushes = branches.map { async { gate.push(bare, it, TOKEN) } }
            val fetches = (1..4).map { async { gate.fetch(bare, TOKEN); PushResult.Pushed } }
            (pushes + fetches).awaitAll()
        }

        assertTrue(results.toString(), results.all { it == PushResult.Pushed })
        branches.forEach { assertNotNull(it, world.revParse(remote, "refs/heads/$it")) }
    }

    @Test
    fun `a step cancelled while it waits for the repo gives up, and the repo stays usable`() {
        val (_, bare) = cloned()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holding = object : RemotePolicy by LocalRemotes {
            override fun allowed(url: String): URIish {
                inside.countDown()
                release.await(10, TimeUnit.SECONDS)
                return LocalRemotes.allowed(url)
            }
        }
        val slow = world.gate(remotes = holding)

        runBlocking(Dispatchers.Default) {
            val first = async { slow.fetch(bare, TOKEN) }
            assertTrue(inside.await(10, TimeUnit.SECONDS))
            val waiting = CompletableDeferred<Unit>()
            val second = async {
                waiting.complete(Unit)
                slow.checkPost(bare, "main")
            }
            waiting.await()
            second.cancel()
            withTimeout(5_000) { second.join() }
            assertTrue(second.isCancelled)
            release.countDown()
            first.await()
        }
        assertEquals(Verdict(true, emptyList(), 0), runBlocking { slow.checkPost(bare, "main") })
    }
}
