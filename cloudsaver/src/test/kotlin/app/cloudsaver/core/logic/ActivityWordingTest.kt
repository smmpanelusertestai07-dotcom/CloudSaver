package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityWordingTest {

    private data class Row(val setting: Boolean, val detail: String?, val at: Long)

    private fun coalesce(rows: List<Row>) = ActivityWording.coalesce(
        rows, { it.setting }, { it.detail }, { it.at }
    )

    @Test
    fun `a setting change round-trips through its token`() {
        val token = ActivityWording.encode(ActivityWording.Setting.QUALITY, "STORAGE_SAVER")
        val back = ActivityWording.decode(token)
        assertEquals(ActivityWording.Setting.QUALITY, back?.setting)
        assertEquals("STORAGE_SAVER", back?.value)
    }

    @Test
    fun `a value containing a colon survives`() {
        val token = ActivityWording.encode(ActivityWording.Setting.CLOUD_APP, "Drive: Personal")
        assertEquals("Drive: Personal", ActivityWording.decode(token)?.value)
    }

    @Test
    fun `nonsense decodes to nothing rather than to a wrong sentence`() {
        assertNull(ActivityWording.decode(null))
        assertNull(ActivityWording.decode(""))
        assertNull(ActivityWording.decode("STORAGE_SAVER"))
        assertNull(ActivityWording.decode("NOT_A_SETTING:x"))
        assertNull(ActivityWording.decode("QUALITY:"))
        assertNull(ActivityWording.decode(":value"))
    }

    @Test
    fun `three taps through the presets is one event`() {
        // Newest first, as the screen shows them.
        val rows = listOf(
            Row(true, "QUALITY:MAX_SAVER", 3_000L),
            Row(true, "QUALITY:BALANCED", 2_000L),
            Row(true, "QUALITY:STORAGE_SAVER", 1_000L)
        )
        val kept = coalesce(rows)
        assertEquals(1, kept.size)
        assertEquals("QUALITY:MAX_SAVER", kept.first().detail)
    }

    @Test
    fun `a change a day later is its own event`() {
        val rows = listOf(
            Row(true, "QUALITY:MAX_SAVER", ActivityWording.COALESCE_MS * 10),
            Row(true, "QUALITY:BALANCED", 0L)
        )
        assertEquals(2, coalesce(rows).size)
    }

    @Test
    fun `different settings never collapse into each other`() {
        val rows = listOf(
            Row(true, "QUALITY:BALANCED", 2_000L),
            Row(true, "SPEED:FAST", 1_000L)
        )
        assertEquals(2, coalesce(rows).size)
    }

    @Test
    fun `everything that is not a setting change is left exactly as recorded`() {
        val rows = listOf(
            Row(false, null, 3_000L),
            Row(false, null, 2_500L),
            Row(true, "QUALITY:BALANCED", 2_000L)
        )
        assertEquals(3, coalesce(rows).size)
    }

    @Test
    fun `an unrecognised detail is kept, not swallowed`() {
        // Better a row the app cannot summarise than a missing row.
        val rows = listOf(
            Row(true, "SOMETHING_NEW:x", 2_000L),
            Row(true, "SOMETHING_NEW:y", 1_000L)
        )
        assertEquals(2, coalesce(rows).size)
    }
}
