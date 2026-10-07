package app.entesaver.util

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A screen flow whose database cannot open on full or damaged storage
 * shows its fallback and tries again, rather than ending the app.
 */
class FailSoftTest {

    @Test
    fun `a source that keeps throwing gives the fallback, never the throw`() = runBlocking {
        val broken = flow<Boolean> { throw IllegalStateException("database or disk is full") }
        assertEquals(listOf(false, false, false), broken.failSoft(false) { 0L }.take(3).toList())
    }

    @Test
    fun `the source is started again and catches up once storage is back`() = runBlocking {
        var opens = 0
        val recovering = flow {
            opens++
            if (opens < 3) throw RuntimeException("disk I/O error")
            emit(true)
        }
        assertEquals(listOf(false, false, true), recovering.failSoft(false) { 0L }.take(3).toList())
    }

    @Test
    fun `retries back off to a minute and no further`() {
        assertEquals(2_000L, failSoftDelayMs(0))
        assertEquals(4_000L, failSoftDelayMs(1))
        assertEquals(60_000L, failSoftDelayMs(5))
        assertEquals(60_000L, failSoftDelayMs(Long.MAX_VALUE))
    }
}
