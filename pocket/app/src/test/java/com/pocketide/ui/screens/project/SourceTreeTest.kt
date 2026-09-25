package com.pocketide.ui.screens.project

import com.pocketide.ui.manage.DiskEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** The session file viewer reads a worktree that Linux programs write: links are never followed. */
class SourceTreeTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var work: File
    private lateinit var root: File
    private lateinit var tree: SourceTree

    @Before
    fun setUp() {
        work = temp.newFolder("work")
        root = File(work, "claude/me__app/s1").apply { mkdirs() }
        tree = SourceTree(work, root)
    }

    private fun write(relative: String, text: String): File = File(root, relative).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    @Test
    fun `a changed file opens as text`() {
        write("src/Main.kt", "fun main() = Unit\n")
        assertEquals(SourceView.Text("fun main() = Unit\n"), tree.read("src/Main.kt"))
    }

    @Test
    fun `a file the session deleted says so`() {
        assertEquals(SourceView.Missing, tree.read("src/Gone.kt"))
    }

    @Test
    fun `paths that leave the worktree are refused`() {
        File(work, "secret.txt").writeText("outside")
        listOf("../secret.txt", "src/../../secret.txt", "/etc/passwd", "a//b", "./x", "").forEach { path ->
            val view = tree.read(path)
            assertTrue("$path -> $view", view is SourceView.Unreadable)
        }
    }

    @Test
    fun `a linked file or folder is never followed`() {
        val outside = temp.newFolder("outside")
        File(outside, "vault.key").writeText("secret")
        Files.createSymbolicLink(File(root, "key.txt").toPath(), File(outside, "vault.key").toPath())
        Files.createSymbolicLink(File(root, "linked").toPath(), outside.toPath())
        assertTrue(tree.read("key.txt") is SourceView.Unreadable)
        assertTrue(tree.read("linked/vault.key") is SourceView.Unreadable)
        assertNull(tree.list("linked"))
        val kinds = tree.list("").orEmpty().associate { it.name to it.kind }
        assertEquals(DiskEntry.Kind.LINK, kinds["key.txt"])
        assertEquals(DiskEntry.Kind.LINK, kinds["linked"])
    }

    @Test
    fun `a worktree reached through a link is not read`() {
        val elsewhere = temp.newFolder("elsewhere")
        File(elsewhere, "s2").mkdirs()
        File(elsewhere, "s2/a.txt").writeText("x")
        val project = File(work, "codex").apply { mkdirs() }
        Files.createSymbolicLink(File(project, "me__app").toPath(), elsewhere.toPath())
        val linked = SourceTree(work, File(project, "me__app/s2"))
        assertNull(linked.list(""))
        assertTrue(linked.read("a.txt") is SourceView.Unreadable)
    }

    @Test
    fun `large and binary files are not shown`() {
        File(root, "big.txt").writeBytes(ByteArray((SourceTree.MAX_VIEW_BYTES + 1).toInt()) { 'a'.code.toByte() })
        File(root, "app.bin").writeBytes(byteArrayOf(1, 0, 2))
        assertTrue(tree.read("big.txt") is SourceView.Unreadable)
        assertTrue(tree.read("app.bin") is SourceView.Unreadable)
    }

    @Test
    fun `folders come first, git's own file is left out`() {
        write("b.txt", "b")
        write("A.md", "a")
        write("src/x.kt", "x")
        write(".git", "gitdir: /repos/me__app.git/worktrees/s1")
        assertEquals(listOf("src", "A.md", "b.txt"), tree.list("")?.map { it.name })
        assertEquals(listOf("x.kt"), tree.list("src")?.map { it.name })
    }

    @Test
    fun `a worktree that is gone lists as missing`() {
        assertNull(SourceTree(work, File(work, "claude/me__app/none")).list(""))
    }

    @Test
    fun `paths move up and down the tree`() {
        assertEquals("", SourcePaths.parent("README"))
        assertEquals("src/main", SourcePaths.parent("src/main/App.kt"))
        assertEquals("src/App.kt", SourcePaths.child("src", "App.kt"))
        assertEquals("App.kt", SourcePaths.child("", "App.kt"))
        assertEquals("App.kt", SourcePaths.name("src/App.kt"))
    }
}
