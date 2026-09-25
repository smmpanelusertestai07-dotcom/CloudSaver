package com.pocketide.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Every case runs with folders held open (the normal way) and with the path-checked way. */
@RunWith(Parameterized::class)
class RoomFilesTest(private val holdFolders: Boolean) {
    @get:Rule val temp = TemporaryFolder()

    private fun home(): Pair<File, RoomFiles> {
        val home = temp.newFolder("home")
        return home to RoomFiles(home, guardSecrets = true, holdFolders = holdFolders)
    }

    @Test fun `writes create private folders and files, and skip identical content`() {
        val (home, files) = home()
        assertTrue(files.write(".claude/CLAUDE.md", "rules"))
        assertFalse(files.write(".claude/CLAUDE.md", "rules"))
        assertEquals("rules", File(home, ".claude/CLAUDE.md").readText())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(File(home, ".claude/CLAUDE.md").toPath()))
        assertEquals(3, Files.getPosixFilePermissions(File(home, ".claude").toPath()).size)
        assertEquals("rules", files.read(".claude/CLAUDE.md"))
        assertTrue(files.write(".claude/CLAUDE.md", "new rules"))
        assertEquals("new rules", files.read(".claude/CLAUDE.md"))
        assertEquals(listOf("CLAUDE.md"), File(home, ".claude").list()!!.toList())
    }

    @Test fun `a link on the way is never followed`() {
        val (home, files) = home()
        val outside = temp.newFolder("vault")
        File(outside, "key").writeText("the vault key")
        Files.createSymbolicLink(File(home, ".codex").toPath(), outside.toPath())
        assertNull(files.read(".codex/key"))
        assertNull(files.open(".codex/key"))
        try {
            files.write(".codex/AGENTS.md", "x")
            throw AssertionError("wrote through a link")
        } catch (refused: IOException) {
            assertFalse(File(outside, "AGENTS.md").exists())
        }
        assertFalse(files.isFile(".codex/key"))
        assertFalse(files.isDirectory(".codex"))
    }

    @Test fun `a home that is itself a link is refused`() {
        val outside = temp.newFolder("other-room")
        File(outside, "CLAUDE.md").writeText("the other room's rules")
        val home = File(temp.root, "home")
        Files.createSymbolicLink(home.toPath(), outside.toPath())
        val files = RoomFiles(home, guardSecrets = true, holdFolders = holdFolders)
        assertNull(files.read("CLAUDE.md"))
        assertFalse(files.makeFolder(""))
        try {
            files.write("CLAUDE.md", "x")
            throw AssertionError("wrote through a linked home")
        } catch (refused: IOException) {
            assertEquals("the other room's rules", File(outside, "CLAUDE.md").readText())
        }
    }

    @Test fun `a file swapped for a link while it is read is never followed`() {
        val (home, files) = home()
        val secret = temp.newFile("auth.json").apply { writeText("the other room's token") }
        val folder = File(home, ".codex").apply { mkdirs() }
        File(folder, "AGENTS.md").writeText("rules")
        val swapping = AtomicBoolean(true)
        // A program in the room keeps putting a link to the token where a plain file was.
        val swapper = thread {
            var n = 0
            while (swapping.get()) {
                val next = File(folder, ".next-${n++}").toPath()
                if (n % 2 == 0) Files.write(next, "rules".toByteArray()) else Files.createSymbolicLink(next, secret.toPath())
                Files.move(next, File(folder, "AGENTS.md").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        try {
            val deadline = System.nanoTime() + SWAP_NANOS
            var plain = 0
            while (System.nanoTime() < deadline) {
                val read = files.read(".codex/AGENTS.md")
                assertFalse("the token was read through a swapped link", read?.contains("token") == true)
                if (read == "rules") plain++
            }
            assertTrue("the plain file was still read between swaps", plain > 0)
        } finally {
            swapping.set(false)
            swapper.join()
        }
    }

    @Test fun `a folder swapped for a link and back while a file is read never leads outside`() {
        assumeTrue("only held folders close this race", holdFolders)
        val (home, files) = home()
        val otherRoom = temp.newFolder("other-room")
        File(otherRoom, "config.toml").writeText("the other room's token")
        val folder = File(home, ".codex").apply { mkdirs() }
        File(folder, "config.toml").writeText("rules")
        var plain = 0
        whileSwapping(folder, otherRoom) {
            val deadline = System.nanoTime() + SWAP_NANOS
            while (System.nanoTime() < deadline) {
                val read = files.read(".codex/config.toml")
                assertFalse("the other room's file was read through a swapped folder", read?.contains("token") == true)
                files.open(".codex/config.toml")?.use { channel ->
                    val bytes = ByteBuffer.allocate(64)
                    channel.read(bytes)
                    assertFalse("the other room's file was opened", String(bytes.array(), 0, bytes.position()).contains("token"))
                }
                if (read == "rules") plain++
            }
        }
        assertTrue("the real folder was still read between swaps", plain > 0)
    }

    @Test fun `a folder swapped for a link and back while a file is written never gets anything outside`() {
        assumeTrue("only held folders close this race", holdFolders)
        val (home, files) = home()
        val otherRoom = temp.newFolder("other-room")
        val folder = File(home, ".codex").apply { mkdirs() }
        var written = 0
        whileSwapping(folder, otherRoom) {
            val deadline = System.nanoTime() + SWAP_NANOS
            var n = 0
            while (System.nanoTime() < deadline) {
                try {
                    files.write(".codex/AGENTS.md", "rules ${n++}")
                    written++
                } catch (refused: IOException) {
                    // The folder was a link at that moment: refused, as it must be.
                }
                assertEquals("nothing was created in the other room", emptyList<String>(), otherRoom.list()!!.toList())
            }
        }
        assertTrue("the real folder was still written between swaps", written > 0)
    }

    @Test fun `a folder swapped for a link while the room is deleted never gets anything outside deleted`() {
        assumeTrue("only held folders close this race", holdFolders)
        val otherRoom = temp.newFolder("other-room")
        val names = (1..FILES).map { "config-$it.toml" }
        names.forEach { File(otherRoom, it).writeText("the other room's") }
        val deadline = System.nanoTime() + SWAP_NANOS
        var rounds = 0
        while (System.nanoTime() < deadline) {
            val home = temp.newFolder("home-${rounds++}")
            val folder = File(home, ".codex").apply { mkdirs() }
            names.forEach { File(folder, it).writeText("this room's") }
            whileSwapping(folder, otherRoom) {
                try {
                    RoomFiles.deleteTree(home)
                } catch (raced: IOException) {
                    // The folder was a link at the moment it was to go: refused, as it must be.
                }
            }
            assertEquals("nothing in the other room was deleted", names.sorted(), otherRoom.list()!!.sorted())
        }
        assertTrue(rounds > 0)
    }

    @Test fun `a read-only folder is still deleted`() {
        val (home, _) = home()
        val folder = File(home, "node_modules/pkg").apply { mkdirs() }
        File(folder, "index.js").writeText("x")
        folder.setWritable(false, false)
        home.setWritable(false, false)
        RoomFiles.deleteTree(home)
        assertFalse(home.exists())
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

    @Test fun `executable files are written with their mode`() {
        val (home, files) = home()
        files.write("bin/tool", "#!/bin/sh\n", executable = true)
        assertTrue(File(home, "bin/tool").canExecute())
        assertTrue(Files.getPosixFilePermissions(File(home, "bin/tool").toPath()).contains(PosixFilePermission.OTHERS_EXECUTE))
    }

    @Test fun `files larger than the limit are not read`() {
        val (home, files) = home()
        File(home, "big.json").writeText("x".repeat(100))
        assertNull(files.read("big.json", limit = 99))
        assertEquals(100, files.read("big.json", limit = 100)?.length)
        File(home, "folder").mkdirs()
        assertNull(files.read("folder"))
        assertNull(files.open("folder"))
    }

    @Test fun `sign-in files are never read or written in a home`() {
        val (home, files) = home()
        File(home, ".codex").mkdirs()
        File(home, ".codex/auth.json").writeText("tokens")
        for (secret in listOf(".codex/auth.json", ".claude.json", ".claude/.credentials.json", ".ssh/id_ed25519")) {
            for (touch in listOf<(String) -> Any?>({ files.read(it) }, { files.open(it) }, { files.write(it, "x") }, { files.delete(it) })) {
                try {
                    touch(secret)
                    throw AssertionError("touched $secret")
                } catch (refused: IllegalArgumentException) {
                    // expected
                }
            }
        }
        assertEquals("tokens", File(home, ".codex/auth.json").readText())
        assertTrue("whether it is there may still be asked", files.isFile(".codex/auth.json"))
        // Outside a home (the computer's /opt) the guard does not apply.
        assertTrue(RoomFiles(temp.newFolder("rootfs"), guardSecrets = false, holdFolders = holdFolders).write("opt/x/token.json", "{}"))
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
        files.makePrivate("link")
        assertTrue("a link's target keeps its mode", File(outside, "precious").canRead())
        files.delete("link")
        assertFalse(Files.exists(File(home, "link").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(File(outside, "precious").exists())
        Files.createSymbolicLink(File(home, "link").toPath(), outside.toPath())
        RoomFiles.deleteTree(home)
        assertFalse(home.exists())
        assertTrue(File(outside, "precious").exists())
    }

    @Test fun `files skips the folders named and stops at the depth`() {
        val (home, files) = home()
        for (path in listOf("a/1", "a/b/2", "a/b/c/3", "skip/4")) File(home, path).apply { parentFile?.mkdirs(); writeText("x") }
        assertEquals(listOf("a/1", "a/b/2"), files.files("", depth = 2, skip = setOf("skip")))
        assertEquals(listOf("a/1", "a/b/2", "a/b/c/3"), files.files("a", depth = 5))
        assertEquals(emptyList<String>(), files.files("missing", depth = 5))
    }

    @Test fun `a broken file is set aside, a link is not`() {
        val (home, files) = home()
        File(home, ".claude").mkdirs()
        File(home, ".claude/settings.json").writeText("{ broken")
        files.setAside(".claude/settings.json")
        assertEquals("{ broken", File(home, ".claude/settings.json.pocketide-broken").readText())
        assertFalse(File(home, ".claude/settings.json").exists())
        Files.createSymbolicLink(File(home, ".claude/settings.json").toPath(), temp.newFile("elsewhere").toPath())
        files.setAside(".claude/settings.json")
        assertTrue(Files.isSymbolicLink(File(home, ".claude/settings.json").toPath()))
    }

    @Test fun `metadata is read without following links`() {
        val (home, files) = home()
        File(home, "plain").writeText("x")
        assertTrue(files.isFile("plain"))
        assertTrue(files.lastModified("plain")!! > 0)
        assertTrue(files.isDirectory(""))
        Files.createSymbolicLink(File(home, "linked").toPath(), File(home, "plain").toPath())
        assertFalse(files.isFile("linked"))
        assertNull(files.lastModified("linked"))
    }

    @Test fun `folders lists real folders only`() {
        val (home, files) = home()
        File(home, "ext/a-1").mkdirs()
        File(home, "ext/file").apply { parentFile?.mkdirs(); writeText("x") }
        Files.createSymbolicLink(File(home, "ext/b-1").toPath(), temp.newFolder("b").toPath())
        assertEquals(listOf("a-1"), files.folders("ext"))
        assertEquals(emptyList<String>(), files.folders("missing"))
    }

    /**
     * Runs [body] while a program in the room keeps swapping [folder] for a link to [elsewhere]
     * and back, as fast as it can.
     */
    private fun whileSwapping(folder: File, elsewhere: File, body: () -> Unit) {
        val real = folder.toPath()
        val parked = real.resolveSibling(folder.name + ".parked")
        val link = real.resolveSibling(folder.name + ".link")
        Files.createSymbolicLink(link, elsewhere.toPath())
        val running = AtomicBoolean(true)
        val swapper = thread {
            while (running.get()) {
                try {
                    Files.move(real, parked, StandardCopyOption.ATOMIC_MOVE)
                    Files.move(link, real, StandardCopyOption.ATOMIC_MOVE)
                    Files.move(real, link, StandardCopyOption.ATOMIC_MOVE)
                    Files.move(parked, real, StandardCopyOption.ATOMIC_MOVE)
                } catch (raced: IOException) {
                    // The app made the folder again while it was away: put things back and go on.
                    restore(real, parked, link)
                }
            }
            restore(real, parked, link)
        }
        try {
            body()
        } finally {
            running.set(false)
            swapper.join()
        }
    }

    private fun restore(real: Path, parked: Path, link: Path) {
        try {
            if (Files.isSymbolicLink(real)) Files.move(real, link, StandardCopyOption.ATOMIC_MOVE)
            if (Files.exists(parked, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isDirectory(real, java.nio.file.LinkOption.NOFOLLOW_LINKS)) real.toFile().deleteRecursively()
                Files.move(parked, real, StandardCopyOption.ATOMIC_MOVE)
            }
        } catch (raced: IOException) {
            // The next round tries again.
        }
    }

    companion object {
        private const val SWAP_NANOS = 3_000_000_000L
        private const val FILES = 40

        @JvmStatic
        @Parameterized.Parameters(name = "holdFolders={0}")
        fun modes() = listOf(true, false)
    }
}
