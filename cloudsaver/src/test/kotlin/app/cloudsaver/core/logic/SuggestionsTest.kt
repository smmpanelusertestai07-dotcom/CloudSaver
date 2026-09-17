package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionsTest {

    private fun candidate(id: Long, mb: Long, isVideo: Boolean, album: String?) =
        ReclaimRules.Candidate(
            id = id,
            fingerprint = "fp-$id",
            sizeBytes = mb * 1_000_000,
            optimisedBytes = 0,
            evidence = Evidence.CONFIRMED_EXACT,
            confirmedAgeDays = 60,
            state = ItemState.DONE,
            hasLedgerEntry = true,
            ledgerHashMatches = true,
            originalPresent = true,
            inExcludedAlbum = false,
            isFavourite = false,
            addedDaysAgo = 400,
            isVideo = isVideo,
            album = album
        )

    private fun apply(
        items: List<ReclaimRules.Candidate>,
        kind: Suggestions.Kind,
        ages: Map<Long, Int>,
        names: Map<Long, String> = emptyMap()
    ) = Suggestions.apply(
        items,
        Suggestions.ALL.first { it.kind == kind },
        ageDaysOf = { ages[it.id] ?: 0 },
        nameOf = { names[it.id] ?: "IMG_${it.id}.jpg" }
    )

    @Test
    fun `big old videos means all three conditions, not any of them`() {
        val items = listOf(
            candidate(1, 200, isVideo = true, album = "Camera"),   // big, old
            candidate(2, 200, isVideo = true, album = "Camera"),   // big, recent
            candidate(3, 10, isVideo = true, album = "Camera"),    // small, old
            candidate(4, 200, isVideo = false, album = "Camera")   // photo, old
        )
        val ages = mapOf(1L to 400, 2L to 30, 3L to 400, 4L to 400)
        assertEquals(listOf(1L), apply(items, Suggestions.Kind.BIG_OLD_VIDEOS, ages).map { it.id })
    }

    @Test
    fun `screenshots are recognised by album or by name`() {
        assertTrue(Suggestions.isScreenshot("Screenshots", "whatever.png"))
        assertTrue(Suggestions.isScreenshot("screenshots", "whatever.png"))
        assertTrue(Suggestions.isScreenshot("Camera", "Screenshot_2024.png"))
        assertFalse(Suggestions.isScreenshot("Camera", "IMG_1234.jpg"))
    }

    @Test
    fun `old screenshots takes only screenshots, only old ones`() {
        val items = listOf(
            candidate(1, 5, isVideo = false, album = "Screenshots"),
            candidate(2, 5, isVideo = false, album = "Screenshots"),
            candidate(3, 5, isVideo = false, album = "Camera")
        )
        val ages = mapOf(1L to 400, 2L to 10, 3L to 400)
        assertEquals(
            listOf(1L),
            apply(items, Suggestions.Kind.OLD_SCREENSHOTS, ages).map { it.id }
        )
    }

    @Test
    fun `a suggestion can only narrow the eligible set, never widen it`() {
        // Every suggestion is applied to an already-eligible list, so the
        // result can never contain something the gate refused.
        val items = listOf(candidate(1, 500, isVideo = true, album = "Camera"))
        val ages = mapOf(1L to 4000)
        for (filter in Suggestions.ALL) {
            val out = Suggestions.apply(
                items, filter, ageDaysOf = { ages[it.id] ?: 0 }, nameOf = { "IMG.jpg" }
            )
            assertTrue("${filter.kind} returned something not in the input", items.containsAll(out))
        }
    }

    @Test
    fun `the plain default is just the age rule`() {
        val items = listOf(candidate(1, 5, isVideo = false, album = "Camera"))
        assertEquals(
            1,
            apply(items, Suggestions.Kind.CONFIRMED_30_DAYS, mapOf(1L to 40)).size
        )
        assertEquals(
            0,
            apply(items, Suggestions.Kind.CONFIRMED_30_DAYS, mapOf(1L to 5)).size
        )
    }

    @Test
    fun `every suggestion kind has exactly one filter`() {
        for (kind in Suggestions.Kind.entries) {
            assertEquals(1, Suggestions.ALL.count { it.kind == kind })
        }
    }
}
