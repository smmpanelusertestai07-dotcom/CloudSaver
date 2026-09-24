package com.pocketide.sync

import com.pocketide.model.ObjectKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiecesTest {
    private val clock = FakeClock()
    private val accounts = FakeAccounts(clock)
    private val path = claudeTranscript("owner/app", "s1", "c0ffee00-1111")

    private fun phone() = TestPhone(accounts, clock).apply { sessions += session("s1", at = clock.now, ref = "c0ffee00-1111") }

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
}
