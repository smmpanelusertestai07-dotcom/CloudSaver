package com.pocketide.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MaterializerTest {
    private val clock = FakeClock()
    private val phone = TestPhone(FakeAccounts(clock), clock)
    private val materializer = SyncKit(phone).materializer
    private val file = phone.homeFile("claude", "notes.jsonl").apply { writeText("the phone's own bytes\n") }
    private val target = RoomFile(phone.dirs.roomHome("claude"), "notes.jsonl", file)

    private fun refused(keep: Long, keepSha: String) = runBlocking {
        try {
            materializer.assemble(phone.drive, phone.cipher, target, emptyList(), keep = keep, keepSha = keepSha)
            fail("the kept bytes are not the expected ones")
        } catch (_: PrefixChangedException) {
            // Expected.
        }
        assertEquals("the file is left as it was", "the phone's own bytes\n", file.readText())
        assertTrue(phone.dirs.queue.list().orEmpty().none { it.startsWith("tmp-") })
    }

    @Test
    fun keptBytesThatHashToSomethingElseAreRefusedAndTheFileIsUntouched() {
        refused(keep = file.length(), keepSha = Codec.sha256("other bytes, same length!\n".toByteArray()))
    }

    @Test
    fun aFileShorterThanWhatWasKeptIsRefusedToo() {
        refused(keep = file.length() + 10, keepSha = Codec.sha256(file.readBytes()))
    }

    @Test
    fun keptBytesThatMatchAreWrittenBackUnchanged() = runBlocking {
        val assembled = materializer.assemble(phone.drive, phone.cipher, target, emptyList(), keep = file.length(), keepSha = Codec.sha256(file.readBytes()))

        assertEquals(Codec.sha256("the phone's own bytes\n".toByteArray()), assembled!!.sha256)
        assertEquals("the phone's own bytes\n", file.readText())
    }
}
