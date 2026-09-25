package com.pocketide.git

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketide.graph
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.URIish
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * JGit 5.13 as the app ships it (dexed by D8, on Android's own java.nio and TLS-free file
 * transport): clone, commit and push through the gate against a bare repository on the phone.
 * The unit tests cover the gate's rules on a desktop JVM; this proves the library itself runs here.
 */
@RunWith(AndroidJUnit4::class)
class JGitOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(context.cacheDir, "jgit-on-device").apply {
            deleteRecursively()
            File(this, "repos").mkdirs()
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun cloneCommitAndPushThroughTheGate() = runBlocking {
        val remote = File(root, "github/owner__proj.git")
        Git.init().setBare(true).setDirectory(remote).setInitialBranch("main").call().close()
        open(remote).use { commit(it, "main", from = null, files = mapOf("README.md" to "hello\n")) }

        val gate = JGitGate(File(root, "repos"), File(root, "gate-state"), remotes = LocalRemotes)
        val bare = File(root, "repos/owner__proj.git")
        gate.clone(url(remote), bare, TOKEN)

        val branch = "pocket/claude/s1"
        val work = open(bare).use { commit(it, branch, from = "main", files = mapOf("README.md" to "hello\n", "a.txt" to "a\n")) }

        assertEquals(PushResult.Pushed, gate.push(bare, branch, TOKEN))
        assertEquals(work, open(remote).use { it.resolve("refs/heads/$branch") })
    }

    @Test
    fun theAppsOwnGateTakesOnlyGitHub() {
        val remote = File(root, "elsewhere.git")
        Git.init().setBare(true).setDirectory(remote).call().close()
        val gate = createGitGate(context.graph)

        assertThrows(GitGateException::class.java) {
            runBlocking { gate.clone(url(remote), File(root, "repos/elsewhere.git"), TOKEN) }
        }
        assertTrue(File(root, "repos/elsewhere.git").list().isNullOrEmpty())
    }

    private fun url(dir: File) = "file://" + dir.absolutePath

    private fun open(dir: File): Repository = FileRepositoryBuilder().setGitDir(dir).setBare().build()

    /** A commit on [branch] whose tree is exactly [files], made with JGit alone (no git on the phone). */
    private fun commit(repo: Repository, branch: String, from: String?, files: Map<String, String>): ObjectId =
        repo.newObjectInserter().use { inserter ->
            val index = DirCache.newInCore()
            val builder = index.builder()
            files.toSortedMap().forEach { (path, text) ->
                builder.add(
                    DirCacheEntry(path).apply {
                        fileMode = FileMode.REGULAR_FILE
                        setObjectId(inserter.insert(Constants.OBJ_BLOB, text.toByteArray()))
                    },
                )
            }
            builder.finish()
            val parent = from?.let { repo.resolve("refs/heads/$it") }
            val ident = PersonIdent("Test", "test@example.com")
            val id = inserter.insert(
                CommitBuilder().apply {
                    setTreeId(index.writeTree(inserter))
                    parent?.let { setParentId(it) }
                    author = ident
                    committer = ident
                    message = "Change"
                },
            )
            inserter.flush()
            val update = repo.updateRef("refs/heads/$branch").apply { setNewObjectId(id) }
            assertEquals(RefUpdate.Result.NEW, update.update())
            id
        }

    /** file:// remotes on this phone stand in for GitHub; they never get a token. */
    private object LocalRemotes : RemotePolicy {
        override fun allowed(url: String): URIish {
            if (!url.startsWith("file:///")) throw GitGateException("Only local test remotes.")
            return URIish(url)
        }

        override fun remoteFor(gitDir: File): String? = null

        override fun mayAuthenticate(uri: URIish): Boolean = false
    }

    private companion object {
        const val TOKEN = "unused-on-file-remotes"
    }
}
