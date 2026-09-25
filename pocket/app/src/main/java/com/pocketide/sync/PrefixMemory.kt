package com.pocketide.sync

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * Where each transcript's last piece ended, with the SHA-256 state of everything before that
 * point, so the next piece starts there instead of reading and hashing the whole synced prefix
 * again: a long chat is tens of megabytes, sent a few kilobytes at a time. The prefix counts as
 * unchanged only while the file is the same file, starts with the same bytes and holds the same
 * bytes just before that point; otherwise it is read in full, as it is for every transcript's
 * first piece after a restart, since this is kept in memory only. Compacting reads it in full too.
 */
internal class PrefixMemory {
    private class Mark(val end: Long, val sha: String, val digest: MessageDigest, val identity: Identity)

    /** What must not have changed for a prefix to be trusted without reading it. */
    private data class Identity(val fileKey: String?, val head: String, val tail: String)

    private val marks = HashMap<String, Mark>()

    /** The hash state after [known]'s synced prefix, when the file still holds that prefix; null to read it all. */
    fun resume(c: Candidate, known: Known): MessageDigest? {
        val mark = marks[c.key]?.takeIf { it.end == known.end && it.sha == known.sha && identity(c, it.end) == it.identity }
        return mark?.digest?.clone() as MessageDigest?
    }

    /** Keeps [digest], the hash state after [c]'s first [end] bytes, whose SHA-256 is [sha]. */
    fun remember(c: Candidate, end: Long, sha: String, digest: MessageDigest) {
        val identity = identity(c, end)
        if (identity == null) marks.remove(c.key) else marks[c.key] = Mark(end, sha, digest, identity)
    }

    private fun identity(c: Candidate, end: Long): Identity? = try {
        val key = Files.readAttributes(c.file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
        c.openInRoom().use { channel -> Identity(key?.toString(), hashOf(channel, 0L, minOf(HEAD, end)), hashOf(channel, maxOf(0L, end - TAIL), end)) }
    } catch (_: IOException) {
        null
    }

    private fun hashOf(channel: SeekableByteChannel, from: Long, to: Long): String {
        val digest = Codec.newDigest()
        val buffer = ByteBuffer.allocate(BUFFER)
        channel.position(from)
        var left = to - from
        while (left > 0) {
            buffer.clear()
            buffer.limit(minOf(BUFFER.toLong(), left).toInt())
            val n = channel.read(buffer)
            if (n < 0) throw EOFException("The file shrank while it was read.")
            digest.update(buffer.array(), 0, n)
            left -= n
        }
        return Codec.hex(digest.digest())
    }

    private companion object {
        const val HEAD = 64L
        const val TAIL = 64L * 1024
        const val BUFFER = 64 * 1024
    }
}
