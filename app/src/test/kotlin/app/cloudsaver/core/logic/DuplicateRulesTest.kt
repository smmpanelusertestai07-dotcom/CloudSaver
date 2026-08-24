package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateRulesTest {

    private fun entry(
        id: Long,
        size: Long = 1000,
        sha: String? = "aaa",
        capturedAt: Long = 1000,
        album: String? = "Camera",
        path: String = "/a/$id.jpg"
    ) = DuplicateRules.Entry(id, "fp-$id", "$id.jpg", size, sha, capturedAt, album, path)

    @Test
    fun `only files of the same length are worth reading`() {
        val entries = listOf(
            entry(1, size = 100, sha = null),
            entry(2, size = 100, sha = null),
            entry(3, size = 200, sha = null)
        )
        val toHash = DuplicateRules.needsHashing(entries)
        assertEquals(setOf(1L, 2L), toHash.map { it.id }.toSet())
    }

    @Test
    fun `an already-hashed file is not read again`() {
        val entries = listOf(entry(1, size = 100, sha = "x"), entry(2, size = 100, sha = null))
        assertEquals(listOf(2L), DuplicateRules.needsHashing(entries).map { it.id })
    }

    @Test
    fun `same size but different content is not a duplicate`() {
        val groups = DuplicateRules.group(
            listOf(entry(1, sha = "aaa"), entry(2, sha = "bbb"))
        )
        assertTrue("different hashes must never group", groups.isEmpty())
    }

    @Test
    fun `identical files group, with one keeper and the rest as extras`() {
        val groups = DuplicateRules.group(
            listOf(
                entry(1, sha = "aaa", capturedAt = 100),
                entry(2, sha = "aaa", capturedAt = 200),
                entry(3, sha = "aaa", capturedAt = 300)
            )
        )
        assertEquals(1, groups.size)
        assertEquals(1L, groups.first().keeper.id)
        assertEquals(setOf(2L, 3L), groups.first().extras.map { it.id }.toSet())
    }

    @Test
    fun `the oldest copy is the one that stays`() {
        val keeper = DuplicateRules.chooseKeeper(
            listOf(entry(1, capturedAt = 500), entry(2, capturedAt = 100)), emptyMap()
        )
        assertEquals(2L, keeper.id)
    }

    @Test
    fun `a tie goes to the fuller album, then the shorter path`() {
        val keeper = DuplicateRules.chooseKeeper(
            listOf(
                entry(1, capturedAt = 100, album = "Download", path = "/a.jpg"),
                entry(2, capturedAt = 100, album = "Camera", path = "/some/long/path/b.jpg")
            ),
            albumSizes = mapOf("Camera" to 900, "Download" to 3)
        )
        assertEquals("the album that holds more files wins", 2L, keeper.id)

        val byPath = DuplicateRules.chooseKeeper(
            listOf(
                entry(1, capturedAt = 100, album = "X", path = "/very/deep/nested/a.jpg"),
                entry(2, capturedAt = 100, album = "X", path = "/b.jpg")
            ),
            albumSizes = mapOf("X" to 5)
        )
        assertEquals(2L, byPath.id)
    }

    @Test
    fun `groups are ordered by what they would free`() {
        val groups = DuplicateRules.group(
            listOf(
                entry(1, sha = "small", size = 10), entry(2, sha = "small", size = 10),
                entry(3, sha = "big", size = 5000), entry(4, sha = "big", size = 5000)
            )
        )
        assertEquals("big", groups.first().sha256)
        assertEquals(5000L, groups.first().reclaimableBytes)
    }

    @Test
    fun `an extra may go while any copy stays, without upload evidence`() {
        val group = DuplicateRules.group(
            listOf(entry(1, sha = "aaa"), entry(2, sha = "aaa"), entry(3, sha = "aaa"))
        ).first()
        assertTrue(DuplicateRules.mayRemoveWithoutEvidence(group, setOf(2L)))
        assertTrue(DuplicateRules.mayRemoveWithoutEvidence(group, setOf(2L, 3L)))
    }

    @Test
    fun `removing every copy is an ordinary deletion again`() {
        // The last one standing is not a duplicate of anything, so it goes
        // back through the full reclaim gate rather than this shortcut.
        val group = DuplicateRules.group(
            listOf(entry(1, sha = "aaa"), entry(2, sha = "aaa"))
        ).first()
        assertFalse(DuplicateRules.mayRemoveWithoutEvidence(group, setOf(1L, 2L)))
    }

    @Test
    fun `a file with no hash yet is never grouped`() {
        assertTrue(
            DuplicateRules.group(listOf(entry(1, sha = null), entry(2, sha = null))).isEmpty()
        )
    }

    @Test
    fun `the keeper is never one of the removable extras`() {
        // The whole reason removing a duplicate is safe is that one identical
        // file stays. A keeper that could also be selected would break that.
        val entries = listOf(
            entry(1, size = 100, sha = "aa", capturedAt = 300),
            entry(2, size = 100, sha = "aa", capturedAt = 100),
            entry(3, size = 100, sha = "aa", capturedAt = 200)
        )
        for (group in DuplicateRules.group(entries)) {
            assertFalse(
                "the keeper appears among its own extras",
                group.extras.any { it.id == group.keeper.id }
            )
            assertEquals(entries.size, group.all.size)
            assertEquals(entries.size - 1, group.extras.size)
        }
    }

    @Test
    fun `reclaimable bytes never include the file that stays`() {
        val entries = listOf(
            entry(1, size = 100, sha = "aa", capturedAt = 100),
            entry(2, size = 100, sha = "aa", capturedAt = 200)
        )
        val group = DuplicateRules.group(entries).single()
        assertEquals(100L, group.reclaimableBytes)
    }
}
