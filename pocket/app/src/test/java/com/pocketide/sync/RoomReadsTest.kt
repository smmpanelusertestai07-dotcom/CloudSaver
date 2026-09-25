package com.pocketide.sync

import com.pocketide.model.ObjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Room files are read for upload from the room's own folder, never through a link swapped in after the scan. */
class RoomReadsTest {
    @get:Rule val temp = TemporaryFolder()

    private val clock = FakeClock()
    private val cipher = FakeCipher()

    @Test
    fun aFolderSwappedForALinkAfterTheScanCarriesNothingElseIntoTheQueue() {
        val home = temp.newFolder("home").canonicalFile
        val memory = File(home, ".claude/projects/p/memory").apply { mkdirs() }
        val notes = File(memory, "notes.md").apply { writeText("my notes") }
        val candidate = Candidate(ObjectKind.MEMORY, "claude", ".claude/projects/p/memory/notes.md", notes, factsOf(notes)!!, null, video = false)
        // Between the scan and the read, the room puts a link to another room's folder where its folder was.
        val otherRoom = temp.newFolder("other-room").apply { File(this, "notes.md").writeText("SECRET!!") }
        Files.move(memory.toPath(), File(memory.parentFile, "memory.real").toPath())
        Files.createSymbolicLink(memory.toPath(), otherRoom.toPath())

        val queue = UploadQueue(temp.newFolder("queue"))
        val maker = PieceMaker(queue, cipher, 1, clock)
        assertEquals(MakeResult.Changing, maker.whole(candidate, Known.of(null, emptyList())) { null })
        assertEquals(MakeResult.Changing, maker.transcript(candidate.copy(kind = ObjectKind.CHAT_PIECE), Known.of(null, emptyList()), compact = false))
        assertEquals(null, maker.copyOf(candidate, null, candidate.path, null))
        assertTrue(queue.entries(cipher).isEmpty())
    }

    @Test
    fun theFileItselfIsOpenedOnlyWhenItIsAPlainFileInItsRoom() {
        val home = temp.newFolder("home").canonicalFile
        val codex = File(home, ".codex/sessions").apply { mkdirs() }
        val rollout = File(codex, "rollout.jsonl").apply { writeText("{\"cwd\":\"/work/a/s1\"}\n") }
        assertEquals("{\"cwd\":\"/work/a/s1\"}\n", openInRoom(rollout, ".codex/sessions/rollout.jsonl").use { channel ->
            java.nio.channels.Channels.newInputStream(channel).readBytes().toString(Charsets.UTF_8)
        })
        val secret = temp.newFile("auth.json").apply { writeText("token") }
        Files.createSymbolicLink(File(codex, "linked.jsonl").toPath(), secret.toPath())
        for (relative in listOf(".codex/sessions/linked.jsonl", ".codex/sessions/missing.jsonl", ".codex/auth.json")) {
            try {
                openInRoom(File(home, relative), relative).close()
                throw AssertionError("opened $relative")
            } catch (refused: IOException) {
                // expected
            }
        }
    }
}
