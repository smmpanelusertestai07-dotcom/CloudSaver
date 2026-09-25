package com.pocketide.git

import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class GitSafetyTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `only https GitHub URLs of one exact shape are allowed`() {
        val uri = GitHubRemotes.allowed("https://github.com/Owner-1/my.repo_x.git")
        assertEquals("https", uri.scheme)
        assertEquals("github.com", uri.host)
        assertEquals("/Owner-1/my.repo_x.git", uri.path)

        listOf(
            "http://github.com/o/r.git",
            "https://github.com/o/r",
            "https://github.com/o/r.git/",
            "https://github.com.evil.example/o/r.git",
            "https://evil.example/github.com/o/r.git",
            "https://user:pass@github.com/o/r.git",
            "https://x-access-token@github.com/o/r.git",
            "https://github.com:8443/o/r.git",
            "https://github.com/o/r.git?x=1",
            "https://github.com/o/r.git#main",
            "https://github.com/o/../r.git",
            "https://github.com/o/r/extra.git",
            "https://github.com/o/..git",
            " https://github.com/o/r.git",
            "https://GITHUB.com/o/r.git",
            "ssh://git@github.com/o/r.git",
            "git@github.com:o/r.git",
            "file:///tmp/r.git",
        ).forEach { url ->
            assertEquals(url, GitMessages.NOT_GITHUB, assertThrows(GitGateException::class.java) { GitHubRemotes.allowed(url) }.message)
        }
    }

    @Test
    fun `a bare repo's name gives back its GitHub URL`() {
        assertEquals("https://github.com/owner/repo.git", GitHubRemotes.remoteFor(File("/data/repos/owner__repo.git")))
        assertEquals("https://github.com/owner/my__repo.git", GitHubRemotes.remoteFor(File("/data/repos/owner__my__repo.git")))
        assertNull(GitHubRemotes.remoteFor(File("/data/repos/owner__repo")))
        assertNull(GitHubRemotes.remoteFor(File("/data/repos/noowner.git")))
        assertNull(GitHubRemotes.remoteFor(File("/data/repos/__repo.git")))
    }

    @Test
    fun `the token goes to GitHub over HTTPS and nowhere else`() {
        val credentials = TokenCredentials("t0ken-value", GitHubRemotes)
        val user = CredentialItem.Username()
        val password = CredentialItem.Password()
        assertTrue(credentials.get(URIish("https://github.com/o/r.git"), user, password))
        assertEquals("x-access-token", user.value)
        assertArrayEquals("t0ken-value".toCharArray(), password.value)

        listOf("https://evil.example/o/r.git", "http://github.com/o/r.git", "https://github.com:444/o/r.git").forEach { url ->
            val otherUser = CredentialItem.Username()
            val otherPassword = CredentialItem.Password()
            assertFalse(url, credentials.get(URIish(url), otherUser, otherPassword))
            assertNull(url, otherUser.value)
            assertNull(url, otherPassword.value)
        }
    }

    @Test
    fun `the guarded file system reads the private config and leads reflogs and alternates to the shadow`() {
        val gitDir = File(temp.root, "repo.git").canonicalFile
        val privateConfig = File(temp.root, "private/config")
        val shadow = File(temp.root, "private/shadow")
        val fs = GuardedFs(gitDir, privateConfig, shadow)
        val objects = File(gitDir, "objects")

        assertEquals(privateConfig, fs.resolve(gitDir, "config"))
        assertEquals(File(gitDir, "HEAD"), fs.resolve(gitDir, "HEAD"))
        assertEquals(File(gitDir, "refs"), fs.resolve(gitDir, "refs"))
        assertEquals(File(shadow, "logs"), fs.resolve(gitDir, "logs"))
        assertEquals(File(shadow, "logs/refs"), fs.resolve(gitDir, "logs/refs/"))
        listOf("../objects", "info/chain", File(temp.root, "other.git/objects").path).forEach { alternate ->
            assertEquals(alternate, File(shadow, "objects"), fs.resolve(objects, alternate))
        }
        assertNull(fs.findHook(null, "pre-push"))
        val copy = fs.newInstance()
        assertTrue(copy is GuardedFs)
        assertEquals(privateConfig, copy.resolve(gitDir, "config"))
        assertEquals(File(shadow, "logs"), copy.resolve(gitDir, "logs"))
    }

    @Test
    fun `failures read as plain sentences`() {
        assertEquals(GitMessages.STORAGE_FULL, plainReason(IOException("Write failed", IOException("No space left on device"))))
        assertEquals(GitMessages.OFFLINE, plainReason(TransportException("https://github.com/o/r.git", UnknownHostException("github.com"))))
        assertEquals(GitMessages.SLOW, plainReason(TransportException("Read timed out", SocketTimeoutException("Read timed out"))))
        assertEquals(GitMessages.SIGN_IN, plainReason(TransportException("https://github.com/o/r.git: not authorized")))
        assertEquals(GitMessages.FAILED, plainReason(IllegalStateException("odd")))
    }

    @Test
    fun `GitHub's refusals are explained in plain words`() {
        assertEquals(
            "This branch is protected on GitHub, so the push was refused.",
            GitMessages.refusedBecause("protected branch hook declined", "error: GH006: Protected branch update failed for refs/heads/main."),
        )
        assertEquals(
            "GitHub's repository rules or secret scanning refused the push.",
            GitMessages.refusedBecause(
                "push declined due to repository rule violations",
                "error: GH013: Repository rule violations found for refs/heads/main.",
            ),
        )
        assertEquals(
            "PocketIDE's GitHub App needs the Workflows permission to change files in .github/workflows.",
            GitMessages.refusedBecause(
                "refusing to allow a GitHub App to create or update workflow `.github/workflows/b.yml` without `workflows` permission",
                "",
            ),
        )
        assertEquals("GitHub refused the push: pre-receive hook declined", GitMessages.refusedBecause("pre-receive hook declined", ""))
        assertEquals("GitHub refused the push.", GitMessages.refusedBecause("", ""))
    }

    @Test
    fun `JGit reads no user or system config and no environment`() {
        HermeticJGit.install()
        val reader = SystemReader.getInstance()
        HermeticJGit.install()

        assertSame(reader, SystemReader.getInstance())
        val opened = listOf(
            reader.openUserConfig(null, FS.DETECTED),
            reader.openSystemConfig(null, FS.DETECTED),
            reader.openJGitConfig(null, FS.DETECTED),
        )
        opened.forEach { config ->
            config.load()
            assertNull(config.file)
            assertTrue(config.sections.isEmpty())
            assertFalse(config.isOutdated)
        }
        assertNull(reader.getenv("PATH"))
        assertEquals("localhost", reader.hostname)
    }
}
