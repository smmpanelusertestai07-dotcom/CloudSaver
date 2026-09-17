package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListFiltersTest {

    private fun photo(
        id: Long = 1,
        name: String = "IMG_0001.jpg",
        album: String? = "Camera",
        bytes: Long = 3_000_000
    ) = ListFilters.Candidate(id, name, album, bytes, isVideo = false)

    private fun video(
        id: Long = 2,
        name: String = "VID_0002.mp4",
        album: String? = "Camera",
        bytes: Long = 250_000_000
    ) = ListFilters.Candidate(id, name, album, bytes, isVideo = true)

    @Test
    fun `type filter splits photos from videos`() {
        assertTrue(ListFilters.matchesType(photo(), ListFilters.Type.ALL))
        assertTrue(ListFilters.matchesType(photo(), ListFilters.Type.PHOTOS))
        assertFalse(ListFilters.matchesType(photo(), ListFilters.Type.VIDEOS))
        assertTrue(ListFilters.matchesType(video(), ListFilters.Type.VIDEOS))
        assertFalse(ListFilters.matchesType(video(), ListFilters.Type.PHOTOS))
    }

    @Test
    fun `size bands are inclusive at the boundary`() {
        // Exactly 10 MB is "over 10 MB": excluding the boundary would drop a
        // file from a band without anywhere else to put it.
        val tenMb = photo(bytes = 10_000_000)
        assertTrue(ListFilters.matchesSize(tenMb, ListFilters.Size.OVER_10MB))
        assertFalse(
            ListFilters.matchesSize(photo(bytes = 9_999_999), ListFilters.Size.OVER_10MB)
        )
        assertTrue(ListFilters.matchesSize(photo(bytes = 1), ListFilters.Size.ANY))
    }

    @Test
    fun `search matches the name or the album, either case`() {
        val p = photo(name = "Beach day.jpg", album = "Holiday")
        assertTrue(ListFilters.matchesQuery(p, "beach"))
        assertTrue(ListFilters.matchesQuery(p, "HOLIDAY"))
        assertTrue(ListFilters.matchesQuery(p, "  "))
        assertFalse(ListFilters.matchesQuery(p, "mountain"))
    }

    @Test
    fun `album filter keeps only that album, and null keeps everything`() {
        val camera = photo(album = "Camera")
        val screenshots = photo(id = 3, album = "Screenshots")
        assertTrue(ListFilters.matchesAlbum(camera, null))
        assertTrue(ListFilters.matchesAlbum(camera, "Camera"))
        assertFalse(ListFilters.matchesAlbum(screenshots, "Camera"))
    }

    @Test
    fun `combined state applies every rule at once`() {
        val big = video(name = "VID_0009.mp4", album = "Camera", bytes = 900_000_000)
        val state = ListFilters.State(
            type = ListFilters.Type.VIDEOS,
            size = ListFilters.Size.OVER_100MB,
            album = "Camera",
            query = "vid"
        )
        assertTrue(ListFilters.matches(big, state))
        // One rule failing is enough to exclude it.
        assertFalse(ListFilters.matches(big, state.copy(album = "Holiday")))
        assertFalse(ListFilters.matches(big, state.copy(type = ListFilters.Type.PHOTOS)))
        assertFalse(ListFilters.matches(big, state.copy(size = ListFilters.Size.OVER_1GB)))
        assertFalse(ListFilters.matches(big, state.copy(query = "img")))
    }

    @Test
    fun `default state is the untouched one`() {
        assertTrue(ListFilters.State().isDefault)
        assertFalse(ListFilters.State(type = ListFilters.Type.VIDEOS).isDefault)
        // A search term is not a filter chip, so it does not make chips look set.
        assertTrue(ListFilters.State(query = "beach").isDefault)
    }

    @Test
    fun `album counts are biggest first, then alphabetical`() {
        val counts = ListFilters.albumCounts(
            listOf(
                photo(id = 1, album = "Camera"),
                photo(id = 2, album = "Camera"),
                photo(id = 3, album = "Screenshots"),
                photo(id = 4, album = "Downloads"),
                photo(id = 5, album = null)
            )
        )
        assertEquals(listOf("Camera" to 2, "Downloads" to 1, "Screenshots" to 1), counts)
    }
}
