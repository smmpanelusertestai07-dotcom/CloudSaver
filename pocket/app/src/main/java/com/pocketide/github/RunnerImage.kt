package com.pocketide.github

import okio.Buffer
import okio.BufferedSource

/** Reads the runner image and version from the "Runner Image" group near the top of a job log. */
object RunnerImage {
    private val IMAGE = Regex("""\bImage:\s*(\S+)""")
    private val VERSION = Regex("""\bVersion:\s*(\S+)""")

    fun fromLog(log: String): String? {
        val image = IMAGE.find(log) ?: return null
        val version = VERSION.find(log, image.range.last)?.groupValues?.get(1)
        return listOfNotNull(image.groupValues[1], version).joinToString(" ")
    }
}

/** The start and the end of a job log, without holding all of it in memory. */
internal object LogReader {
    private const val CHUNK = 8_192L

    fun read(source: BufferedSource, headBytes: Long, tailBytes: Long): JobLog {
        val head = Buffer()
        val tail = Buffer()
        val chunk = Buffer()
        var truncated = false
        while (source.read(chunk, CHUNK) != -1L) {
            if (head.size < headBytes) chunk.copyTo(head, 0, minOf(chunk.size, headBytes - head.size))
            tail.writeAll(chunk)
            if (tail.size > tailBytes) {
                tail.skip(tail.size - tailBytes)
                truncated = true
            }
        }
        val text = tail.readUtf8()
        // A cut tail starts mid-line; drop that partial line.
        return JobLog(RunnerImage.fromLog(head.readUtf8()), if (truncated) text.substringAfter('\n', text) else text)
    }
}
