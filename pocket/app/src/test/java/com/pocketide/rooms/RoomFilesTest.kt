package com.pocketide.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class RoomFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun home(): Pair<File, RoomFiles> {
        val home = temp.newFolder("home")
        return home to RoomFiles(home, guardSecrets = true)
    }

    @Test fun `writes create private folders and files, and skip identical content`() {
        val (home, files) = home()
        assertTrue(files.write(".claude/CLAUDE.md", "rules"))
        assertFalse(files.write(".claude/CLAUDE.md", "rules"))
        assertEquals("rules", File(home, ".claude/CLAUDE.md").readText())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(File(home, ".claude/CLAUDE.md").toPath()))
        assertEquals(3, Files.getPosixFilePermissions(File(home, ".claude").toPath()).size)
        assertEquals("rules", files.read(".claude/CLAUDE.md"))
    }

    @Test fun `a link on the way is never followed`() {
        val (home, files) = home()
        val outside = temp.newFolder("vault")
        File(outside, "key").writeText("the vault key")
        Files.createSymbolicLink(File(home, ".codex").toPath(), outside.toPath())
        assertNull(files.read(".codex/key"))
        try {
            files.write(".codex/AGENTS.md", "x")
            throw AssertionError("wrote through a link")
        } catch (refused: IOException) {
            assertFalse(File(outside, "AGENTS.md").exists())
        }
        assertFalse(files.isFile(".codex/key"))
    }

    @Test fun `a link at the target is replaced, not written through`() {
        val (home, files) = home()
        val victim = temp.newFile("victim")
        victim.writeText("keep me")
        File(home, ".gemini").mkdirs()
        Files.createSymbolicLink(File(home, ".gemini/GEMINI.md").toPath(), victim.toPath())
        assertNull("a link is not read either", files.read(".gemini/GEMINI.md"))
        files.write(".gemini/GEMINI.md", "rules")
        assertEquals("keep me", victim.readText())
        assertFalse(Files.isSymbolicLink(File(home, ".gemini/GEMINI.md").toPath()))
    }

    @Test fun `sign-in files are never read or written in a home`() {
        val (home, files) = home()
        File(home, ".codex").mkdirs()
        File(home, ".codex/auth.json").writeText("tokens")
        for (secret in listOf(".codex/auth.json", ".claude.json", ".claude/.credentials.json", ".ssh/id_ed25519")) {
            try {
                files.read(secret)
                throw AssertionError("read $secret")
            } catch (refused: IllegalArgumentException) {
                // expected
            }
            try {
                files.write(secret, "x")
                throw AssertionError("wrote $secret")
            } catch (refused: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals("tokens", File(home, ".codex/auth.json").readText())
        // Outside a home (the computer's /opt) the guard does not apply.
        assertTrue(RoomFiles(temp.newFolder("rootfs"), guardSecrets = false).write("opt/x/token.json", "{}"))
    }

    @Test fun `paths that climb out are refused`() {
        val (_, files) = home()
        try {
            files.write("../escape", "x")
            throw AssertionError("wrote outside")
        } catch (refused: IllegalArgumentException) {
            assertFalse(File(temp.root, "escape").exists())
        }
    }

    @Test fun `listing, private modes and deleting never follow links`() {
        val (home, files) = home()
        val outside = temp.newFolder("outside")
        File(outside, "precious").writeText("x")
        File(home, ".codex").mkdirs()
        File(home, ".codex/auth.json").writeText("t")
        File(home, ".codex/auth.json").setReadable(true, false)
        Files.createSymbolicLink(File(home, "link").toPath(), outside.toPath())
        assertEquals(listOf(".codex/auth.json"), files.files("", depth = 3))
        files.makePrivate(".codex/auth.json")
        assertEquals(2, Files.getPosixFilePermissions(File(home, ".codex/auth.json").toPath()).size)
        RoomFiles.deleteTree(home)
        assertFalse(home.exists())
        assertTrue(File(outside, "precious").exists())
    }

    @Test fun `folders lists real folders only`() {
        val (home, files) = home()
        File(home, "ext/a-1").mkdirs()
        File(home, "ext/file").apply { parentFile?.mkdirs(); writeText("x") }
        Files.createSymbolicLink(File(home, "ext/b-1").toPath(), temp.newFolder("b").toPath())
        assertEquals(listOf("a-1"), files.folders("ext"))
        assertEquals(emptyList<String>(), files.folders("missing"))
    }
}
