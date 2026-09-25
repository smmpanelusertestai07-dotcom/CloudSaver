package com.pocketide.sync

import com.pocketide.model.ObjectKind
import com.pocketide.model.SessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RewriteAndCrashTest {
    private val clock = FakeClock()
    private val accounts = FakeAccounts(clock)
    private val path = claudeTranscript("owner/app", "s1", "c0ffee00-1111")

    private fun phone() = TestPhone(accounts, clock).apply { sessions += session("s1", at = clock.now, ref = "c0ffee00-1111") }

    private fun TestPhone.pieces() = remoteIndex()!!.objects.filter { it.kind == ObjectKind.CHAT_PIECE && it.path == path }.sortedBy { it.offset }

    /** Reads the file back from Drive alone, as a new phone would. */
    private fun rebuilt(): String = runBlocking {
        val other = TestPhone(accounts, clock, deviceId = "reader", deviceName = "Reader")
        other.engine.fetchSession("s1")
        other.homeFile("claude", path).readText()
    }

    private fun File.rewrite(text: String) {
        writeText(text)
        setLastModified(lastModified() + 5_000)
    }

    @Test
    fun aFileThatShrankIsSentWholeAndItsOldPiecesAreErased() = runBlocking {
        val phone = phone()
        val file = phone.homeFile("claude", path)
        file.writeText("one\ntwo\n")
        phone.engine.syncNow()
        file.appendText("three\n")
        clock.advance(60_000)
        phone.engine.syncNow()
        val before = phone.pieces().map { it.name }
        assertEquals(2, before.size)

        file.rewrite("new\n")
        clock.advance(60_000)
        phone.engine.syncNow()

        val after = phone.pieces()
        assertEquals(1, after.size)
        assertEquals(0L, after.single().offset)
        assertEquals(4L, after.single().length)
        assertTrue(before.none { it in phone.drive.objectNames() })
        assertEquals("new\n", rebuilt())
    }

    @Test
    fun aChangedPrefixOfTheSameLengthMakesANewBase() = runBlocking {
        val phone = phone()
        val file = phone.homeFile("claude", path)
        file.writeText("aaaa\nbbbb\n")
        phone.engine.syncNow()
        file.rewrite("AAAA\nbbbb\ncccc\n")
        clock.advance(60_000)
        phone.engine.syncNow()

        val pieces = phone.pieces()
        assertEquals(listOf(0L), pieces.map { it.offset })
        assertEquals(Codec.sha256(file.readBytes()), pieces.single().sha256)
        assertEquals(file.readText(), rebuilt())
    }

    @Test
    fun piecesQueuedOfflineSurviveARestartAndAreSentOnce() = runBlocking {
        val phone = phone()
        val file = phone.homeFile("claude", path)
        file.writeText("hello\n")
        phone.engine.syncNow()
        phone.drive.offline = true
        file.appendText("while offline\n")
        clock.advance(60_000)
        phone.engine.syncNow()
        assertEquals(1, phone.queued().size)
        assertTrue(phone.engine.status.value is SyncStatus.Error)

        // The app is killed and started again; the queue is read back from disk.
        phone.drive.offline = false
        val restarted = DriveSyncEngine(phone)
        restarted.syncNow()

        assertTrue(phone.queued().isEmpty())
        val pieces = phone.pieces()
        assertEquals(listOf(0L, 6L), pieces.map { it.offset })
        assertEquals(file.length(), pieces.sumOf { it.length })
        assertEquals(file.length(), phone.state().tracks.values.single { it.path == path }.syncedLength)
        assertEquals(file.readText(), rebuilt())
    }

    @Test
    fun anUploadWhoseAnswerWasLostIsNotSentTwice() = runBlocking {
        val phone = phone()
        val file = phone.homeFile("claude", path)
        file.writeText("x".repeat(500) + "\n")
        phone.drive.loseUploadAnswers = 1
        phone.engine.syncNow()
        assertEquals(1, phone.drive.objectNames().size)
        assertEquals("nothing is recorded before Drive confirmed it", null, phone.remoteIndex())
        assertEquals(0L, phone.state().tracks.values.singleOrNull { it.path == path }?.syncedLength ?: 0L)

        val uploads = phone.drive.uploads
        phone.engine.syncNow()

        assertEquals("only the index is written", uploads + 1, phone.drive.uploads)
        assertEquals(1, phone.drive.objectNames().size)
        assertEquals(1, phone.pieces().size)
        assertEquals(file.length(), phone.state().tracks.values.single { it.path == path }.syncedLength)
    }

    @Test
    fun aCrashBetweenUploadAndIndexRecordsThePiecesOnceLater() = runBlocking {
        val phone = phone()
        val file = phone.homeFile("claude", path)
        file.writeText("first\n")
        phone.engine.syncNow()
        file.appendText("second\n")
        clock.advance(60_000)
        phone.drive.failIndexWrites = 1
        phone.engine.syncNow()
        assertEquals("the piece waits in the queue, already in Drive", 1, phone.queued().count { it.driveId != null })
        assertEquals(6L, phone.state().tracks.values.single { it.path == path }.syncedLength)

        val restarted = DriveSyncEngine(phone)
        val uploads = phone.drive.uploads
        restarted.syncNow()

        assertEquals(uploads + 1, phone.drive.uploads)
        assertEquals(listOf(0L, 6L), phone.pieces().map { it.offset })
        assertEquals(file.length(), phone.state().tracks.values.single { it.path == path }.syncedLength)
        assertEquals(2, phone.drive.objectNames().size)
        assertEquals(file.readText(), rebuilt())
    }

    @Test
    fun anIndexWriteWhoseAnswerWasLostIsKnownAsThisPhonesOwn() = runBlocking {
        val phone = phone()
        val file = phone.homeFile("claude", path)
        file.writeText("first\n")
        phone.engine.syncNow()
        file.appendText("second\n")
        clock.advance(60_000)
        // The index reaches Drive, but its answer is lost on the way back.
        phone.drive.loseIndexAnswers = 1
        phone.engine.syncNow()
        assertEquals("Drive already names the piece", listOf(0L, 6L), phone.pieces().map { it.offset })
        assertEquals(6L, phone.state().tracks.values.single { it.path == path }.syncedLength)

        file.appendText("third\n")
        clock.advance(60_000)
        phone.engine.syncNow()

        assertEquals("the live chat keeps every line", "first\nsecond\nthird\n", file.readText())
        assertEquals(listOf(0L, 6L, 13L), phone.pieces().map { it.offset })
        assertTrue("no conflict copy of the phone's own work", phone.remoteIndex()!!.sessions.none { it.status == SessionStatus.CONFLICT_COPY })
        assertTrue(phone.queued().isEmpty())
        assertEquals(file.readText(), rebuilt())
    }

    @Test
    fun aFailureWhileSettlingLeavesTheQueueUntilTheStateIsSaved() = runBlocking {
        val phone = phone()
        val file = phone.homeFile("claude", path)
        file.writeText("first\n")
        phone.engine.syncNow()
        file.appendText("second\n")
        clock.advance(60_000)
        // The index reaches Drive, then the engine's own records cannot be saved (the app dies there).
        val records = File(phone.dirs.vault, "sync")
        val aside = File(phone.base, "sync-aside")
        phone.drive.afterIndexWrite = {
            records.renameTo(aside)
            records.writeText("in the way")
        }
        phone.engine.syncNow()
        phone.drive.afterIndexWrite = null
        records.delete()
        aside.renameTo(records)
        assertEquals("nothing left the queue before the state was saved", 1, phone.queued().size)

        file.appendText("third\n")
        clock.advance(60_000)
        DriveSyncEngine(phone).syncNow()

        assertEquals("first\nsecond\nthird\n", file.readText())
        assertEquals(listOf(0L, 6L, 13L), phone.pieces().map { it.offset })
        assertTrue(phone.remoteIndex()!!.sessions.none { it.status == SessionStatus.CONFLICT_COPY })
        assertTrue(phone.queued().isEmpty())
        assertEquals(file.readText(), rebuilt())
    }

    @Test
    fun aQueueFileThatOutlivedItsRecordIsCountedOnce() = runBlocking {
        val phone = phone()
        val file = phone.homeFile("claude", path)
        file.writeText("first\n")
        phone.engine.syncNow()
        file.appendText("second\n")
        clock.advance(60_000)
        // Killed after the state was saved, before the recorded piece left the queue.
        val copy = File(phone.base, "queue-copy")
        phone.drive.beforeUpload = { name -> if (name == RemoteIndex.NAME) phone.dirs.queue.copyRecursively(copy, overwrite = true) }
        phone.engine.syncNow()
        phone.drive.beforeUpload = null
        copy.copyRecursively(phone.dirs.queue, overwrite = true)
        assertEquals(1, phone.queued().size)

        file.appendText("third\n")
        clock.advance(60_000)
        DriveSyncEngine(phone).syncNow()

        val track = phone.state().tracks.values.single { it.path == path }
        assertEquals(phone.pieces().map { it.name }, track.objects)
        assertEquals(file.length(), track.syncedLength)
        assertEquals(listOf(0L, 6L, 13L), phone.pieces().map { it.offset })
        assertTrue(phone.queued().isEmpty())
        assertEquals(file.readText(), rebuilt())
    }

    @Test
    fun aPieceARacingWriteDroppedIsSentAgainNotCutFromThePhone() = runBlocking {
        val phone = phone()
        val file = phone.homeFile("claude", path)
        file.writeText("one\n")
        phone.engine.syncNow()
        file.appendText("two\n")
        clock.advance(60_000)
        phone.engine.syncNow()
        // Another phone's write, made on top of an older index, leaves the second piece out.
        phone.rewriteRemoteIndex { index ->
            index.copy(revision = index.revision + 1, objects = index.objects.filterNot { it.path == path && it.offset > 0 })
        }

        phone.engine.syncNow()
        assertEquals("one\ntwo\n", file.readText())
        clock.advance(60_000)
        phone.engine.syncNow()

        assertEquals("one\ntwo\n", file.readText())
        assertEquals(listOf(0L, 4L), phone.pieces().map { it.offset })
        assertTrue(phone.remoteIndex()!!.sessions.none { it.status == SessionStatus.CONFLICT_COPY })
        assertEquals(file.readText(), rebuilt())
    }

    @Test
    fun aDroppedPieceThatAnotherPhoneContinuedDifferentlyIsKeptAsAConflictCopy() = runBlocking {
        val phone = phone()
        val file = phone.homeFile("claude", path)
        file.writeText("one\n")
        phone.engine.syncNow()
        file.appendText("two\n")
        clock.advance(60_000)
        phone.engine.syncNow()
        // Another phone's write dropped the second piece, and that phone continued with its own.
        clock.advance(60_000)
        val first = phone.pieces().first()
        val theirs = phone.sendAsAnotherPhone(first, "bee\n", offset = 4)
        phone.rewriteRemoteIndex { index ->
            index.copy(revision = index.revision + 1, objects = index.objects.filterNot { it.path == path && it.offset > 0 } + theirs)
        }

        phone.engine.syncNow()

        assertEquals("one\nbee\n", file.readText())
        val index = phone.remoteIndex()!!
        val copy = index.sessions.single { it.status == SessionStatus.CONFLICT_COPY }
        assertEquals("s1", copy.conflictOf)
        val reader = TestPhone(accounts, clock, deviceId = "reader", deviceName = "Reader")
        reader.engine.fetchSession(copy.id)
        val copied = index.objects.single { it.sessionId == copy.id }
        assertEquals("one\ntwo\n", reader.homeFile("claude", copied.path).readText())
    }

    @Test
    fun halfWrittenQueueFilesFromAKillAreRemoved() = runBlocking {
        val phone = phone()
        phone.dirs.queue.mkdirs()
        val strayBlob = File(phone.dirs.queue, "o-stray.age").apply { writeText("half") }
        val strayPart = File(phone.dirs.queue, "o-other.meta.part").apply { writeText("half") }
        phone.homeFile("claude", path).writeText("ok\n")
        phone.engine.syncNow()
        assertFalse(strayBlob.exists())
        assertFalse(strayPart.exists())
        assertEquals(1, phone.pieces().size)
    }

    @Test
    fun theLocalIndexCopyIsSealedAndLetsTheAppStartOffline() = runBlocking {
        val phone = phone()
        phone.homeFile("claude", path).writeText("hello\n")
        phone.engine.syncNow()
        val sealed = File(phone.dirs.vault, "sync/index.bin").readBytes()
        assertTrue(sealed.copyOfRange(0, FakeCipher.HEADER.size).contentEquals(FakeCipher.HEADER))

        phone.drive.offline = true
        val restarted = DriveSyncEngine(phone)
        restarted.warmUp()
        assertEquals(listOf("s1"), restarted.driveSessions.value.map { it.id })
    }
}
