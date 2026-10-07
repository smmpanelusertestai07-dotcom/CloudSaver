package app.entesaver.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlChunksTest {

    @Test
    fun `no query is given more ids than old Android's SQLite accepts`() = runBlocking {
        val ids = (1L..2_345L).toList()
        val sizes = mutableListOf<Int>()
        val rows = SqlChunks.read(ids) { slice ->
            sizes += slice.size
            slice.map { it * 10 }
        }
        assertTrue("slices $sizes", sizes.all { it < 999 })
        assertEquals(ids.map { it * 10 }, rows)
    }

    @Test
    fun `an empty list asks nothing`() = runBlocking {
        var asked = 0
        val rows = SqlChunks.read(emptyList<Long>()) { asked++; emptyList<Long>() }
        assertEquals(0, asked)
        assertTrue(rows.isEmpty())
    }
}
