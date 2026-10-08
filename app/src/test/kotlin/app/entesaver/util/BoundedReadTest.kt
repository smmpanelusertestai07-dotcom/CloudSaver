package app.entesaver.util

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedReadTest {

    @Test
    fun `a file within the limit is read whole`() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        assertArrayEquals(bytes, BoundedRead.readAtMost(ByteArrayInputStream(bytes), 200_000))
    }

    @Test
    fun `a stream past the limit stops being read as soon as it passes`() {
        // An endless stream stands in for a provider that reports no size.
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                served += len
                return len
            }
        }
        assertNull(BoundedRead.readAtMost(endless, 1_000_000))
        assertTrue("read $served bytes", served < 1_000_000 + 2 * 64 * 1024)
    }

    @Test
    fun `the limit sits far below a small phone's heap`() {
        assertTrue(BoundedRead.MAX_BACKUP_BYTES <= 64L * 1024 * 1024)
    }
}
