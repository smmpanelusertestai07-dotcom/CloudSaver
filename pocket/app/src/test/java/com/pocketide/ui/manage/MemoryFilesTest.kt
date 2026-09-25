package com.pocketide.ui.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class MemoryFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun home(): File = temp.newFolder("home")

    private fun write(home: File, relative: String, text: String): File =
        File(home, relative).apply {
            parentFile?.mkdirs()
            writeText(text)
        }

    @Test
    fun `no room home means nothing to edit yet`() {
        assertTrue(MemoryFiles.find(File(temp.root, "missing"), "claude", "CLAUDE.md").isEmpty())
    }

    @Test
    fun `primary file is listed before it exists, others only when they exist`() {
        val home = home()
        val files = MemoryFiles.find(home, "codex", "AGENTS.md")
        assertEquals(listOf("~/.codex/AGENTS.md"), files.map { it.label })
        assertFalse(files.single().exists)
        assertTrue(files.single().primary)
    }

    @Test
    fun `claude rules and project memory are found`() {
        val home = home()
        write(home, ".claude/CLAUDE.md", "hello")
        write(home, ".claude/rules/style.md", "rules")
        write(home, ".claude/rules/notes.txt", "not markdown")
        write(home, ".claude/projects/-work-app/memory/MEMORY.md", "memory")
        val files = MemoryFiles.find(home, "claude", "CLAUDE.md")
        assertEquals(
            listOf("~/.claude/CLAUDE.md", "~/.claude/rules/style.md", "~/.claude/projects/-work-app/memory/MEMORY.md"),
            files.map { it.label },
        )
        assertTrue(files.all { it.exists })
        assertEquals(5L, files.first().bytes)
    }

    @Test
    fun `an unknown agent uses its instructions file name`() {
        val home = home()
        write(home, "KILO.md", "x")
        assertEquals(listOf("~/KILO.md"), MemoryFiles.find(home, "kilocode.kilo-code", "KILO.md").map { it.label })
        assertTrue(MemoryFiles.patternsFor("x.y", "../escape.md").isEmpty())
    }

    @Test
    fun `symbolic links are never listed, read or written through`() {
        val home = home()
        val outside = temp.newFile("secret.bin").apply { writeText("sealed") }
        Files.createDirectories(File(home, ".gemini").toPath())
        Files.createSymbolicLink(File(home, ".gemini/GEMINI.md").toPath(), outside.toPath())
        val listed = MemoryFiles.find(home, "antigravity", "GEMINI.md").single { it.label == "~/.gemini/GEMINI.md" }
        assertFalse(listed.exists)
        assertFalse(MemoryFiles.isSafe(home, File(home, ".gemini/GEMINI.md")))
        try {
            MemoryFiles.read(home, File(home, ".gemini/GEMINI.md"))
            fail("read through a link")
        } catch (_: IOException) {
        }
        try {
            MemoryFiles.save(home, File(home, ".gemini/GEMINI.md"), "overwrite")
            fail("wrote through a link")
        } catch (_: IOException) {
        }
        assertEquals("sealed", outside.readText())
    }

    @Test
    fun `a linked folder on the way is refused`() {
        val home = home()
        val elsewhere = temp.newFolder("elsewhere")
        Files.createSymbolicLink(File(home, ".claude").toPath(), elsewhere.toPath())
        assertFalse(MemoryFiles.isSafe(home, File(home, ".claude/CLAUDE.md")))
        assertTrue(MemoryFiles.find(home, "claude", "CLAUDE.md").none { it.exists })
        assertFalse(MemoryFiles.isSafe(home, File(home, "../outside.md")))
        assertFalse(MemoryFiles.isSafe(home, home))
    }

    @Test
    fun `save creates folders, replaces the file and leaves no temp file`() {
        val home = home()
        val target = File(home, ".codex/AGENTS.md")
        MemoryFiles.save(home, target, "first")
        assertEquals("first", MemoryFiles.read(home, target))
        MemoryFiles.save(home, target, "second")
        assertEquals("second", target.readText())
        assertEquals(listOf("AGENTS.md"), target.parentFile!!.list()!!.toList())
    }

    @Test
    fun `a planted temp link is removed, not written through`() {
        val home = home()
        val outside = temp.newFile("victim").apply { writeText("keep") }
        val target = write(home, ".codex/AGENTS.md", "old")
        Files.createSymbolicLink(File(target.parentFile, ".AGENTS.md.pocketide-save").toPath(), outside.toPath())
        MemoryFiles.save(home, target, "new")
        assertEquals("new", target.readText())
        assertEquals("keep", outside.readText())
    }

    @Test
    fun `missing file reads as empty, a huge one is refused`() {
        val home = home()
        assertEquals("", MemoryFiles.read(home, File(home, ".codex/AGENTS.md")))
        val big = write(home, ".codex/AGENTS.md", "x".repeat((MemoryFiles.MAX_EDIT_BYTES + 1).toInt()))
        try {
            MemoryFiles.read(home, big)
            fail("read a huge file")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    @Test
    fun `managed block is found and the file round-trips byte for byte`() {
        val text = "# Mine\nkeep this\n<!-- pocketide:begin (managed by PocketIDE) -->\nRun builds on Actions.\n<!-- pocketide:end -->\nmore of mine\n"
        val doc = MemoryDocument.parse(text)
        assertEquals("# Mine\nkeep this\n", doc.before)
        assertEquals("<!-- pocketide:begin (managed by PocketIDE) -->\nRun builds on Actions.\n<!-- pocketide:end -->\n", doc.managed)
        assertEquals("more of mine\n", doc.after)
        assertEquals(text, doc.join())
    }

    @Test
    fun `no block, or an unfinished one, leaves everything editable`() {
        val plain = MemoryDocument.parse("just text\n")
        assertNull(plain.managed)
        assertEquals("just text\n", plain.before)
        val open = MemoryDocument.parse("a\n<!-- PocketIDE start -->\nb\n")
        assertNull(open.managed)
        assertEquals("a\n<!-- PocketIDE start -->\nb\n", open.join())
    }

    @Test
    fun `block at the very end without a newline is kept`() {
        val text = "mine\n<!-- PocketIDE: begin -->\nrules\n<!-- PocketIDE: end -->"
        val doc = MemoryDocument.parse(text)
        assertEquals("mine\n", doc.before)
        assertEquals("", doc.after)
        assertEquals(text, doc.join())
    }

    @Test
    fun `rebuild keeps the block on its own lines after edits`() {
        val block = "<!-- pocketide:begin -->\nr\n<!-- pocketide:end -->"
        assertEquals("edited\n$block\nafter", MemoryDocument.rebuild("edited", block, "after"))
        assertEquals("$block", MemoryDocument.rebuild("", block, ""))
        assertEquals("ab", MemoryDocument.rebuild("a", null, "b"))
    }

    @Test
    fun `glob matches names but never hidden files`() {
        assertTrue(MemoryFiles.globMatches("*.md", "style.md"))
        assertFalse(MemoryFiles.globMatches("*.md", "style.txt"))
        assertFalse(MemoryFiles.globMatches("*.md", ".hidden.md"))
        assertTrue(MemoryFiles.globMatches("*", "-work-app"))
    }
}
