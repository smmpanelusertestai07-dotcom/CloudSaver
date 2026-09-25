package com.pocketide.media

/**
 * Videos for agents that accept only pictures (§8): each video is sent as a few frames taken at
 * evenly spaced times. Pure rules here; [VideoFrames] does the Android work.
 */
object KeyFrames {
    /** Frames per video when the input takes several files. */
    const val PER_VIDEO = 4

    /** What the picked list became: [items] to hand to the page, and how many videos gave no frame. */
    data class Replaced<T>(val items: List<T>, val unreadableVideos: Int)

    /**
     * [count] times in microseconds, each in the middle of an equal slice of the video, so no
     * frame is the black first one or the last one. A video of unknown length gives its start.
     */
    fun times(durationMs: Long, count: Int): List<Long> {
        if (count <= 0) return emptyList()
        if (durationMs <= 0) return listOf(0L)
        val durationUs = durationMs * 1_000
        return List(count) { i -> (durationUs * (2 * i + 1)) / (2L * count) }
    }

    /** Frames per video for an input that takes one file or several. */
    fun perVideo(multiple: Boolean): Int = if (multiple) PER_VIDEO else 1

    /** Each video in [picked] replaced by its frames, in place; anything else is kept as it is. */
    fun <T> replaceVideos(picked: List<T>, isVideo: (T) -> Boolean, frames: (T) -> List<T>): Replaced<T> {
        var unreadable = 0
        val items = picked.flatMap { item ->
            if (!isVideo(item)) return@flatMap listOf(item)
            frames(item).also { if (it.isEmpty()) unreadable++ }
        }
        return Replaced(items, unreadable)
    }
}
