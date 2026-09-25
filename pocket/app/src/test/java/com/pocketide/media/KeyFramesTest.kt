package com.pocketide.media

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyFramesTest {
    @Test
    fun `frames sit in the middle of equal slices of the video`() {
        assertEquals(listOf(1_000_000L, 3_000_000L, 5_000_000L, 7_000_000L), KeyFrames.times(8_000, 4))
        assertEquals(listOf(4_000_000L), KeyFrames.times(8_000, 1))
        assertEquals(listOf(0L), KeyFrames.times(0, 4))
        assertEquals(emptyList<Long>(), KeyFrames.times(8_000, 0))
    }

    @Test
    fun `one frame for a single-file input, a few for several`() {
        assertEquals(1, KeyFrames.perVideo(multiple = false))
        assertEquals(KeyFrames.PER_VIDEO, KeyFrames.perVideo(multiple = true))
    }

    @Test
    fun `each video is replaced by its frames in place, pictures are kept`() {
        val picked = listOf("a.png", "clip.mp4", "b.jpg", "broken.mp4")
        val replaced = KeyFrames.replaceVideos(
            picked,
            isVideo = { it.endsWith(".mp4") },
            frames = { video -> if (video == "clip.mp4") listOf("clip-1.webp", "clip-2.webp") else emptyList() },
        )
        assertEquals(listOf("a.png", "clip-1.webp", "clip-2.webp", "b.jpg"), replaced.items)
        assertEquals(1, replaced.unreadableVideos)
    }
}
