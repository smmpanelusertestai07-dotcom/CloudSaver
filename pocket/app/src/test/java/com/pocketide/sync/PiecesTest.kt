package com.pocketide.sync

import com.pocketide.model.ObjectKind
import com.pocketide.model.SessionStatus
import com.pocketide.sessions.Sessions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class PiecesTest {
    private val clock = FakeClock()
    private val accounts = FakeAccounts(clock)
    private val path = claudeTranscript("owner/app", "s1", "c0ffee00-1111")

    private fun phone() = TestPhone(accounts, clock).apply { sessions += session("s1", at = clock.now, ref = "c0ffee00-1111") }

    private fun TestPhone.pieces() = remoteIndex()!!.objects.filter { it.kind == ObjectKind.CHAT_PIECE && it.path == path }.sortedBy { it.offset }

    /** A chat of 200 KB, longer than the parts of its start that are checked without reading it all. */
    private val longChat = "x".repeat(LINE - 1).plus("\n").repeat(LINES)

    /** Changes one byte in place: same size, same file. */
    private fun File.patch(at: Long, char: Char) = RandomAccessFile(this, "rw").use {
        it.seek(at)
        it.write(char.code)
    }

    /** The chat as a new phone rebuilds it from Drive alone. */
    private suspend fun rebuilt(): String {
        val other = TestPhone(accounts, clock, deviceId = "reader", deviceName = "Reader")
        other.engine.fetchSession("s1")
        return other.homeFile("claude", path).readText()
    }

    @Test
    fun newBytesBecomeSmallPiecesAtTheirOffsets() = runBlocking {
        val phone = phone()
        val file = phone.homeFile("claude", path)
        file.writeText("line 1\n")
        phone.engine.syncNow()
        file.appendText("line 2\nline 3\n")
        clock.advance(60_000)
        phone.engine.syncNow()
        file.appendText("line 4\n")
        clock.advance(60_000)
        phone.engine.syncNow()

        val pieces = phone.remoteIndex()!!.objects.filter { it.kind == ObjectKind.CHAT_PIECE && it.path == path }.sortedBy { it.offset }
        assertEquals(listOf(0L, 7L, 21L), pieces.map { it.offset })
        assertEquals(listOf(7L, 14L, 7L), pieces.map { it.length })
        assertTrue(pieces.all { it.sessionId == "s1" && it.agentId == "claude" })
        assertEquals(listOf(Codec.sha256("line 2\nline 3\n".toByteArray())), pieces.filter { it.offset == 7L }.map { it.sha256 })

        val track = phone.state().tracks.values.single { it.path == path }
        assertEquals(file.length(), track.syncedLength)
        assertEquals(Codec.sha256(file.readBytes()), track.prefixSha256)
        assertEquals(pieces.map { it.name }, track.objects)
        assertTrue(phone.queued().isEmpty())
        assertTrue(phone.engine.status.value is SyncStatus.UpToDate)
    }

    @Test
    fun aNewBaseMadeAfterTheClockWentBackStillWins() = runBlocking {
        val phone = phone()
        val file = phone.homeFile("claude", path)
        file.writeText("the first version of the chat\n")
        phone.engine.syncNow()
        val oldBase = phone.pieces().single()

        // The phone's time goes back an hour (set by hand, or corrected by the network).
        clock.now -= Durations.HOUR
        file.writeText("rewritten\n")
        file.setLastModified(file.lastModified() + 5_000)
        phone.engine.syncNow()

        val base = phone.pieces().single()
        assertEquals(0L, base.offset)
        assertTrue("ordered after the base it replaces", base.createdAt > oldBase.createdAt)
        assertEquals("rewritten\n", rebuilt())
    }

    @Test
    fun aPieceAddedAfterTheClockWentBackStaysInTheChat() = runBlocking {
        val phone = phone()
        val file = phone.homeFile("claude", path)
        file.writeText("line 1\n")
        phone.engine.syncNow()

        clock.now -= Durations.HOUR
        file.appendText("line 2\n")
        phone.engine.syncNow()

        val pieces = phone.pieces()
        assertEquals(listOf(0L, 7L), pieces.map { it.offset })
        assertTrue(pieces.last().createdAt > pieces.first().createdAt)
        assertEquals("line 1\nline 2\n", rebuilt())
    }

    @Test
    fun aChatQuietForADayIsFoldedIntoOnePieceOnWifi() = runBlocking {
        val phone = phone()
        val file = phone.homeFile("claude", path)
        file.writeText("line 1\n")
        phone.engine.syncNow()
        repeat(2) {
            file.appendText("line ${it + 2}\n")
            clock.advance(60_000)
            phone.engine.syncNow()
        }
        val pieces = phone.pieces()
        assertEquals(3, pieces.size)

        clock.advance(Durations.DAY)
        phone.network.metered = true
        phone.engine.syncNow()
        assertEquals("never on mobile data", pieces, phone.pieces())

        phone.network.metered = false
        phone.engine.syncNow()
        val folded = phone.pieces().single()
        assertEquals(0L, folded.offset)
        assertEquals(file.length(), folded.length)
        assertTrue("the old pieces leave Drive", pieces.none { it.name in phone.drive.objectNames() })
        assertEquals(file.readText(), rebuilt())
    }

    @Test
    fun aQuietChatIsFoldedOnlyWhenThatSendsLittleAgainForTheEntriesItSaves() {
        val now = clock.now
        val quiet = Known(end = 0, sha = null, size = 0, modifiedAt = 0, pieces = 3, lastCreatedAt = now - Durations.DAY)
        assertTrue(SyncPass.foldDue(quiet, size = 1_000, now = now))
        assertFalse("still being written", SyncPass.foldDue(quiet.copy(lastCreatedAt = now - Durations.HOUR), size = 1_000, now = now))
        assertFalse("one piece already", SyncPass.foldDue(quiet.copy(pieces = 1), size = 1_000, now = now))
        assertFalse("a big file sent again to save two entries", SyncPass.foldDue(quiet, size = 100L shl 20, now = now))
        val long = quiet.copy(pieces = SyncPass.COMPACT_AFTER, lastCreatedAt = now)
        assertTrue("a long chat is folded however big", SyncPass.foldDue(long, size = 100L shl 20, now = now))
    }

    @Test
    fun theIndexASyncSendsCountsAgainstTheMobileDataLikeThePieces() = runBlocking {
        val phone = phone()
        phone.network.metered = true
        phone.homeFile("claude", path).writeText("hello\n")

        phone.engine.syncNow()

        val piece = phone.pieces().single()
        val index = phone.drive.named(RemoteIndex.NAME).single().bytes.size
        assertEquals(piece.storedBytes + index, phone.budget.usage.value.byType[MeteredDataBudget.KIND_SYNC])
    }

    @Test
    fun aLongChatsNextPieceIsReadFromWhereTheLastEndedAndARestartChecksItAll() = runBlocking {
        val phone = phone()
        val file = phone.homeFile("claude", path)
        file.writeText(longChat)
        phone.engine.syncNow()

        // A byte deep inside what Drive already has changes (no agent does this to its chat). The
        // next piece is read from where the last one ended, so it goes unseen for now...
        file.patch(LINES * LINE / 2L, 'y')
        file.appendText("next\n")
        clock.advance(60_000)
        phone.engine.syncNow()
        assertEquals(listOf(0L, longChat.length.toLong()), phone.pieces().map { it.offset })

        // ...until the app starts again: the first piece after that checks the whole start.
        file.appendText("after a restart\n")
        clock.advance(60_000)
        DriveSyncEngine(phone).syncNow()
        assertEquals(listOf(0L), phone.pieces().map { it.offset })
        assertEquals(file.readText(), rebuilt())
    }

    @Test
    fun aLongChatWhoseStartOrNewestSyncedBytesChangedIsSentWholeAtOnce() = runBlocking {
        val phone = phone()
        val file = phone.homeFile("claude", path)
        file.writeText(longChat)
        phone.engine.syncNow()

        file.patch(10, 'y')
        file.appendText("next\n")
        clock.advance(60_000)
        phone.engine.syncNow()
        assertEquals("a changed start", listOf(0L), phone.pieces().map { it.offset })

        file.patch(file.length() - 100, 'z')
        file.appendText("more\n")
        clock.advance(60_000)
        phone.engine.syncNow()
        assertEquals("a change just before the end Drive has", listOf(0L), phone.pieces().map { it.offset })
        assertEquals(file.readText(), rebuilt())
    }

    @Test
    fun anOpaqueConversationChangedDeepInsideIsSentWholeEvenInTheSameRun() = runBlocking {
        val phone = TestPhone(accounts, clock)
        val conversation = ".gemini/antigravity/conversations/9f1c2d3e.pb"
        val file = phone.homeFile("antigravity", conversation)
        file.writeText(longChat)
        phone.engine.syncNow()

        // Antigravity rewrites its conversation in place as it grows: a field deep inside changes too.
        file.patch(LINES * LINE / 2L, 'y')
        file.appendText("next\n")
        clock.advance(60_000)
        phone.engine.syncNow()

        val pieces = phone.remoteIndex()!!.objects.filter { it.path == conversation }
        assertEquals(listOf(0L), pieces.map { it.offset })
        assertEquals(Codec.sha256(file.readBytes()), pieces.single().sha256)
    }

    @Test
    fun everythingInDriveIsCompressedThenEncryptedUnderOpaqueNames() = runBlocking {
        val phone = phone()
        phone.homeFile("claude", path).writeText("{\"secret project\":true}\n".repeat(50))
        phone.homeFile("claude", ".claude/CLAUDE.md").writeText("Always write tests.")
        phone.engine.syncNow()

        val drive = phone.drive
        assertTrue(drive.objectNames().isNotEmpty())
        for (stored in drive.files.values) {
            assertTrue(stored.name == RemoteIndex.NAME || stored.name.matches(Regex("o-[a-z2-7]{26}")))
            assertTrue(stored.bytes.copyOfRange(0, FakeCipher.HEADER.size).contentEquals(FakeCipher.HEADER))
            assertFalse(String(stored.bytes, Charsets.ISO_8859_1).contains("secret project"))
            Codec.gunzip(phone.cipher.decryptBytes(stored.bytes))
        }
        val memory = phone.remoteIndex()!!.objects.single { it.kind == ObjectKind.MEMORY }
        assertEquals(".claude/CLAUDE.md", memory.path)
        assertEquals(null, memory.sessionId)
    }

    @Test
    fun anUnchangedFileIsNotSentAgain() = runBlocking {
        val phone = phone()
        phone.homeFile("claude", path).writeText("hello\n")
        phone.engine.syncNow()
        val uploads = phone.drive.uploads
        clock.advance(5 * 60_000)
        phone.engine.syncNow()
        assertEquals("only nothing, or a lease renewal, may be written", true, phone.drive.uploads - uploads <= 1)
        assertEquals(1, phone.remoteIndex()!!.objects.count { it.path == path })
    }

    @Test
    fun anotherPhoneRebuildsTheSameBytesFromThePieces() = runBlocking {
        val first = phone()
        val file = first.homeFile("claude", path)
        file.writeText("a\n")
        first.engine.syncNow()
        file.appendText("b\nc\n")
        clock.advance(60_000)
        first.engine.syncNow()

        val second = TestPhone(accounts, clock, deviceId = "phone-b", deviceName = "Phone B")
        second.engine.fetchSession("s1")
        assertEquals(file.readText(), second.homeFile("claude", path).readText())
        val track = second.state().tracks.values.single { it.path == path }
        assertEquals(file.length(), track.syncedLength)
    }

    @Test
    fun aFileQueuedAsTheSameContentAsAnotherStillReachesDriveWhenTheOtherChangesFirst() = runBlocking {
        val phone = phone()
        val rules = "Write tests first.\n"
        val claude = phone.homeFile("claude", ".claude/CLAUDE.md").apply { writeText(rules) }
        val codex = phone.homeFile("codex", ".codex/AGENTS.md").apply { writeText(rules) }
        phone.drive.offline = true
        phone.engine.syncNow()
        assertEquals("the same text waits once", 1, phone.queued().count { it.blob })

        claude.writeText("Write tests first. Keep them fast.\n")
        claude.setLastModified(claude.lastModified() + 5_000)
        phone.drive.offline = false
        repeat(3) {
            clock.advance(60_000)
            phone.engine.syncNow()
        }

        val memory = phone.remoteIndex()!!.objects.filter { it.kind == ObjectKind.MEMORY }.associateBy { it.path }
        assertEquals(Codec.sha256(claude.readBytes()), memory.getValue(".claude/CLAUDE.md").sha256)
        assertEquals(Codec.sha256(codex.readBytes()), memory.getValue(".codex/AGENTS.md").sha256)
        assertTrue(phone.queued().isEmpty())
        val newPhone = TestPhone(accounts, clock, deviceId = "phone-b", deviceName = "Phone B")
        newPhone.engine.restore(RestoreChoice.WIFI_ONLY)
        assertEquals(rules, newPhone.homeFile("codex", ".codex/AGENTS.md").readText())
    }

    @Test
    fun anImageSharedIntoTwoChatsReachesDriveWhenOneIsErasedBeforeUpload() = runBlocking {
        val phone = phone()
        phone.sessions += session("s2", at = clock.now)
        val shot = ByteArray(4096) { (it % 251).toByte() }
        phone.mediaFile("claude", "owner/app", "s1", "shot.png").writeBytes(shot)
        phone.mediaFile("claude", "owner/app", "s2", "shot.png").writeBytes(shot)
        phone.drive.offline = true
        phone.engine.syncNow()
        val erased = phone.queued().single { it.blob }.sessionId
        val kept = listOf("s1", "s2").single { it != erased }

        // "Delete forever" on the chat whose copy of the image was the one waiting to upload.
        val i = phone.sessions.indexOfFirst { it.id == erased }
        phone.sessions[i] = phone.sessions[i].copy(status = SessionStatus.DELETED, deletedAt = Sessions.ERASE_NOW)
        phone.drive.offline = false
        repeat(3) {
            clock.advance(60_000)
            phone.engine.syncNow()
        }

        val media = phone.remoteIndex()!!.objects.single { it.kind == ObjectKind.MEDIA }
        assertEquals(kept, media.sessionId)
        assertTrue(phone.queued().isEmpty())
        val reader = TestPhone(accounts, clock, deviceId = "reader", deviceName = "Reader")
        reader.engine.fetchSession(kept)
        assertArrayEquals(shot, reader.mediaFile("claude", "owner/app", kept, "shot.png").readBytes())
    }

    @Test
    fun identicalFilesAreStoredOnce() = runBlocking {
        val phone = phone()
        phone.sessions += session("s2", at = clock.now)
        val shot = ByteArray(4096) { (it % 251).toByte() }
        phone.mediaFile("claude", "owner/app", "s1", "shot.png").writeBytes(shot)
        phone.mediaFile("claude", "owner/app", "s2", "same.png").writeBytes(shot)
        phone.engine.syncNow()

        val media = phone.remoteIndex()!!.objects.filter { it.kind == ObjectKind.MEDIA }
        assertEquals(2, media.size)
        assertEquals(1, media.map { it.name }.toSet().size)
        assertEquals(1, phone.drive.objectNames().size)
    }

    private companion object {
        const val LINE = 100
        const val LINES = 2048
    }
}
