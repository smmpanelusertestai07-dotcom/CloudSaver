package com.pocketide.ui.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/** What a Linux program in the room could plant, and what the editor must do about it. */
class MemoryFilesHostileTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun write(home: File, relative: String, text: String): File =
        File(home, relative).apply {
            parentFile?.mkdirs()
            writeText(text)
        }

    private fun assertRefused(block: () -> Unit) {
        try {
            block()
            fail("expected a refusal")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `credential-looking files in memory folders are never listed`() {
        val home = temp.newFolder("home")
        write(home, ".claude/CLAUDE.md", "mine")
        write(home, ".claude/projects/-work/memory/MEMORY.md", "memory")
        write(home, ".claude/projects/-work/memory/oauth_token.md", "ok: markdown is never a credential by name")
        write(home, ".claude/projects/-work/memory/.credentials.json", "{\"token\":\"x\"}")
        val labels = MemoryFiles.find(home, "claude", "CLAUDE.md").map { it.label }
        assertTrue(labels.none { it.contains("credentials") })
        assertTrue(labels.all { it.endsWith(".md") })
    }

    @Test
    fun `built-in files are synced, an added agent's file stays on the phone`() {
        val home = temp.newFolder("home")
        write(home, ".codex/AGENTS.md", "x")
        assertTrue(MemoryFiles.find(home, "codex", "AGENTS.md").single().synced)
        write(home, "KILO.md", "x")
        assertFalse(MemoryFiles.find(home, "kilocode.kilo-code", "KILO.md").single().synced)
    }

    @Test
    fun `an agent's credential file as its instructions name is never offered`() {
        val home = temp.newFolder("home")
        write(home, "auth.json", "{}")
        assertTrue(MemoryFiles.find(home, "evil.agent", "auth.json").isEmpty())
    }

    @Test
    fun `a binary file is not opened as text`() {
        val home = temp.newFolder("home")
        val file = File(home, ".codex/AGENTS.md").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00, 0x00))
        }
        assertRefused { MemoryFiles.read(home, file) }
    }

    @Test
    fun `exactly the size limit opens, one byte more does not`() {
        val home = temp.newFolder("home")
        val file = write(home, ".codex/AGENTS.md", "y".repeat(MemoryFiles.MAX_EDIT_BYTES.toInt()))
        assertEquals(MemoryFiles.MAX_EDIT_BYTES.toInt(), MemoryFiles.read(home, file).length)
        file.appendText("y")
        assertRefused { MemoryFiles.read(home, file) }
    }

    @Test
    fun `a folder on the way that is a link to outside is refused for save and read`() {
        val home = temp.newFolder("home")
        val outside = temp.newFolder("outside")
        write(outside, "AGENTS.md", "outside")
        Files.createSymbolicLink(File(home, ".codex").toPath(), outside.toPath())
        val target = File(home, ".codex/AGENTS.md")
        assertRefused { MemoryFiles.read(home, target) }
        assertRefused { MemoryFiles.save(home, target, "planted") }
        assertEquals("outside", File(outside, "AGENTS.md").readText())
    }

    @Test
    fun `a home reached through a link of the phone's own still works`() {
        val real = temp.newFolder("real-home")
        val alias = File(temp.root, "alias").toPath()
        Files.createSymbolicLink(alias, real.toPath())
        // The app's own folders can sit behind a link (/data/user/0 → /data/data); only links
        // inside the home are refused.
        val inside = File(real, ".codex/AGENTS.md")
        MemoryFiles.save(real, inside, "ok")
        assertEquals("ok", MemoryFiles.read(real, inside))
    }

    @Test
    fun `a folder swapped for a link while saving never changes a file outside the home`() {
        val home = temp.newFolder("home")
        val outside = temp.newFolder("outside")
        val victim = write(outside, "AGENTS.md", "outside")
        val real = File(home, ".codex").apply { mkdirs() }
        val parked = File(home, "parked")
        val stop = AtomicBoolean(false)
        val swapper = Thread {
            while (!stop.get()) {
                runCatching {
                    Files.move(real.toPath(), parked.toPath())
                    Files.createSymbolicLink(real.toPath(), outside.toPath())
                    Thread.yield()
                    Files.delete(real.toPath())
                    Files.move(parked.toPath(), real.toPath())
                }
            }
        }
        swapper.start()
        try {
            repeat(300) { i ->
                try {
                    MemoryFiles.save(home, File(home, ".codex/AGENTS.md"), "mine $i")
                } catch (_: IOException) {
                }
                try {
                    val text = MemoryFiles.read(home, File(home, ".codex/AGENTS.md"))
                    assertFalse("read through the link", text == "outside")
                } catch (_: IOException) {
                }
            }
        } finally {
            stop.set(true)
            swapper.join()
        }
        assertEquals("outside", victim.readText())
        // Only a new temp name could ever appear there (CREATE_NEW); no file outside is replaced.
        assertTrue(outside.list()!!.all { it == "AGENTS.md" || it.endsWith(".pocketide-save") })
    }

    @Test
    fun `a folder swapped for a link just while the file opens is never read, even once swapped back`() {
        val home = temp.newFolder("home")
        val outside = temp.newFolder("outside")
        write(outside, "AGENTS.md", "outside")
        val target = write(home, ".codex/AGENTS.md", "mine")
        val real = File(home, ".codex").toPath()
        val parked = File(home, "parked").toPath()
        assertRefused {
            LinkFreeFiles.readText(
                home,
                target,
                MemoryFiles.MAX_EDIT_BYTES,
                "too large",
                beforeOpen = {
                    Files.move(real, parked)
                    Files.createSymbolicLink(real, outside.toPath())
                },
                afterOpen = {
                    Files.delete(real)
                    Files.move(parked, real)
                },
            )
        }
        assertEquals("mine", MemoryFiles.read(home, target))
    }

    @Test
    fun `invalid UTF-8 is shown with replacement characters, not a crash`() {
        val home = temp.newFolder("home")
        val file = File(home, ".codex/AGENTS.md").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x61, 0xC3.toByte(), 0x28, 0x62))
        }
        val text = MemoryFiles.read(home, file)
        assertTrue(text.startsWith("a") && text.endsWith("b"))
    }

    @Test
    fun `hundreds of planted memory files are capped`() {
        val home = temp.newFolder("home")
        repeat(200) { write(home, ".claude/rules/r$it.md", "x") }
        val files = MemoryFiles.find(home, "claude", "CLAUDE.md")
        assertTrue(files.size <= 60)
    }

    @Test
    fun `save plan keeps the app block as it is now and never loses the agent's edits silently`() {
        val block = "<!-- pocketide:begin -->\nrules v1\n<!-- pocketide:end -->\n"
        val loaded = MemoryDocument.parse("mine\n$block")
        val newerBlock = block.replace("v1", "v2")
        val roomRewrote = MemoryDocument.parse("mine\n$newerBlock")
        val plan = MemoryDocument.plan(loaded, roomRewrote, "mine, edited\n", "", overwrite = false)
        assertEquals(SavePlan.Write("mine, edited\n$newerBlock"), plan)

        val agentWrote = MemoryDocument.parse("mine\n${block}the agent remembered this\n")
        assertEquals(SavePlan.ChangedOnDisk, MemoryDocument.plan(loaded, agentWrote, "mine, edited\n", "", overwrite = false))
        assertEquals(
            SavePlan.Write("mine, edited\n$block"),
            MemoryDocument.plan(loaded, agentWrote, "mine, edited\n", "", overwrite = true),
        )
    }

    @Test
    fun `a typed marker line is refused so the block can never swallow the owner's text`() {
        val loaded = MemoryDocument.parse("mine\n")
        val plan = MemoryDocument.plan(loaded, loaded, "mine\n<!-- pocketide:begin -->\n", "", overwrite = false)
        assertTrue(plan is SavePlan.Refused)
        assertFalse(MemoryDocument.hasMarker("I mention pocketide begin in prose\n"))
        assertTrue(MemoryDocument.hasMarker("x\r\n<!-- PocketIDE end -->\r\n"))
    }

    @Test
    fun `a file deleted while the editor was open is written again`() {
        val loaded = MemoryDocument.parse("mine\n")
        val gone = MemoryDocument.parse("")
        assertEquals(SavePlan.ChangedOnDisk, MemoryDocument.plan(loaded, gone, "mine\n", "", overwrite = false))
    }
}
