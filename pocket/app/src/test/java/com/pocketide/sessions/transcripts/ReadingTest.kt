package com.pocketide.sessions.transcripts

import com.pocketide.sessions.transcripts.TranscriptFormat.Companion.NOTE
import com.pocketide.sessions.transcripts.TranscriptFormat.Companion.USER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadingTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun lines(text: String, maxString: Int = 16, partialLast: Boolean = false): List<String> =
        JsonLineReader(text.byteInputStream(), maxString = maxString).use { reader ->
            generateSequence { reader.next(partialLast) }.toList()
        }

    @Test
    fun `long strings are cut while reading and the line stays valid json`() {
        val line = """{"type":"image","data":"${"A".repeat(10_000)}","after":"kept"}"""
        val read = lines(line + "\n").single()
        val json = parseObject(read)
        assertNotNull(json)
        assertEquals("AAAAAAAAAAAAAAAA…", json?.text("data"))
        assertEquals("kept", json?.text("after"))
        assertTrue(read.length < 80)
    }

    @Test
    fun `a cut never splits an escape`() {
        val escape = BACKSLASH + "u00e9"
        val unicode = """{"t":"${"a".repeat(15)}$escape${"b".repeat(40)}","n":1}"""
        assertEquals("a".repeat(15) + "é…", parseObject(lines(unicode + "\n").single())?.text("t"))
        val quote = """{"t":"${"a".repeat(16)}\"${"b".repeat(40)}\"","n":2}"""
        val json = parseObject(lines(quote + "\n").single())
        assertEquals("a".repeat(16) + "…", json?.text("t"))
        assertEquals(2L, json?.get("n").asLong())
    }

    @Test
    fun `a cut never splits a character`() {
        val accented = """{"t":"${"a".repeat(15)}é${"b".repeat(40)}"}"""
        assertEquals("a".repeat(15) + "…", parseObject(lines(accented + "\n").single())?.text("t"))
        val fits = """{"t":"${"a".repeat(14)}é${"b".repeat(40)}"}"""
        assertEquals("a".repeat(14) + "é…", parseObject(lines(fits + "\n").single())?.text("t"))
    }

    @Test
    fun `short strings and escapes pass through untouched`() {
        val line = """{"t":"say \"hi\"\n\\ done","u":"é"}"""
        val json = parseObject(lines(line + "\n", maxString = 64).single())
        assertEquals("say \"hi\"\n\\ done", json?.text("t"))
        assertEquals("é", json?.text("u"))
    }

    @Test
    fun `a last line without a newline waits unless asked for`() {
        assertEquals(listOf("""{"a":1}"""), lines("""{"a":1}""" + "\n" + """{"b":2}"""))
        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), lines("""{"a":1}""" + "\n" + """{"b":2}""", partialLast = true))
    }

    @Test
    fun `consumed counts whole lines only, and blank lines are skipped`() {
        val text = "{\"a\":1}\r\n\n{\"b\":2}\n{\"c\":"
        JsonLineReader(text.byteInputStream()).use { reader ->
            assertEquals("{\"a\":1}\r", reader.next())
            assertEquals("{\"b\":2}", reader.next())
            assertNull(reader.next())
            assertEquals(text.length.toLong() - "{\"c\":".length, reader.consumed)
        }
    }

    @Test
    fun `a line too long even after cutting is skipped, the next one is read`() {
        val many = (1..200).joinToString(",", "{", "}") { "\"k$it\":\"${"v".repeat(16)}\"" }
        val read = JsonLineReader((many + "\n{\"ok\":true}\n").byteInputStream(), maxString = 16, maxLine = 1_000).use { reader ->
            generateSequence { reader.next() }.toList()
        }
        assertEquals(listOf("{\"ok\":true}"), read)
    }

    @Test
    fun `malformed lines parse to null`() {
        assertNull(parseObject("{\"a\":"))
        assertNull(parseObject("[1,2]"))
        assertNull(parseObject("plain text"))
    }

    @Test
    fun `the view keeps the newest 5000 entries`() {
        val file = temp.newFile("big.jsonl")
        file.bufferedWriter().use { out ->
            for (i in 1..6_000) {
                out.write("""{"type":"USER_INPUT","created_at":"2026-09-24T08:00:00Z","content":"message $i"}""")
                out.write("\n")
            }
        }
        val entries = TranscriptView.read(AntigravityFormat, listOf(file))
        assertEquals(TranscriptView.MAX_ENTRIES, entries.size)
        assertEquals("message 1001", entries.first().text)
        assertEquals("message 6000", entries.last().text)
    }

    @Test
    fun `several files read as one chat in time order, compressed parts as a note`() {
        val later = temp.newFile("b.jsonl").apply {
            writeText("""{"type":"USER_INPUT","created_at":"2026-09-24T09:00:00Z","content":"second"}""" + "\n")
        }
        val earlier = temp.newFile("a.jsonl").apply {
            writeText("""{"type":"USER_INPUT","created_at":"2026-09-24T08:00:00Z","content":"first"}""" + "\n")
        }
        val entries = TranscriptView.read(AntigravityFormat, listOf(later, earlier))
        assertEquals(listOf("first", "second"), entries.map { it.text })
        assertTrue(entries.all { it.role == USER })

        val compressed = temp.newFile("rollout-2026-09-01T10-00-00-x.jsonl.zst").apply { setLastModified(0) }
        val withNote = TranscriptView.read(CodexFormat, listOf(compressed))
        assertEquals(NOTE, withNote.single().role)
        assertEquals(TranscriptView.COMPRESSED, withNote.single().text)
    }

    private companion object {
        const val BACKSLASH = "\\"
    }

    @Test
    fun `iso times with an offset are read too`() {
        assertEquals(isoMillis("2026-09-24T02:30:00Z"), isoMillis("2026-09-24T08:00:00+05:30"))
        assertNull(isoMillis("yesterday"))
        assertNull(isoMillis(null))
    }
}
