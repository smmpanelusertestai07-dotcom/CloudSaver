package com.pocketide.ui.screens.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SyntaxColorsTest {
    private fun colored(text: String, path: String): List<Pair<String, TokenKind>> {
        val syntax = requireNotNull(SyntaxColors.forPath(path)) { "no syntax for $path" }
        return SyntaxColors.tokens(text, syntax).map { text.substring(it.start, it.end) to it.kind }
    }

    @Test
    fun `the language follows the file name`() {
        assertNotNull(SyntaxColors.forPath("app/src/Main.kt"))
        assertNotNull(SyntaxColors.forPath("web/index.TSX"))
        assertNotNull(SyntaxColors.forPath("Dockerfile"))
        assertNull(SyntaxColors.forPath("README.md"))
        assertNull(SyntaxColors.forPath("notes"))
    }

    @Test
    fun `keywords, strings, numbers and comments in Kotlin`() {
        val text = "val name = \"x // not a comment\" // real\nif (n > 42) return"
        assertEquals(
            listOf(
                "val" to TokenKind.KEYWORD,
                "\"x // not a comment\"" to TokenKind.STRING,
                "// real" to TokenKind.COMMENT,
                "if" to TokenKind.KEYWORD,
                "42" to TokenKind.NUMBER,
                "return" to TokenKind.KEYWORD,
            ),
            colored(text, "A.kt"),
        )
    }

    @Test
    fun `block comments and triple quotes span lines`() {
        assertEquals(listOf("/* a\nb */" to TokenKind.COMMENT), colored("/* a\nb */x", "A.java"))
        assertEquals(listOf("\"\"\"doc\nmore\"\"\"" to TokenKind.STRING), colored("\"\"\"doc\nmore\"\"\"", "a.py"))
    }

    @Test
    fun `a hash inside a word and an apostrophe in text are not comments or strings`() {
        assertEquals(listOf("# note" to TokenKind.COMMENT), colored("echo a#b # note", "run.sh"))
        assertEquals(emptyList<Pair<String, TokenKind>>(), colored("<p>don't</p>", "index.html"))
        // An unclosed string stops at the end of its line.
        assertEquals(listOf("\"open" to TokenKind.STRING, "if" to TokenKind.KEYWORD), colored("\"open\nif", "a.js"))
    }

    @Test
    fun `identifiers containing keywords or digits are not coloured`() {
        assertEquals(emptyList<Pair<String, TokenKind>>(), colored("iffy value2 x_if", "A.kt"))
    }

    @Test
    fun `lines drop their breaks, including a CR`() {
        val text = "a\r\nbc\n\nd"
        val lines = SyntaxColors.lines(text).map { text.substring(it.first, it.last + 1) }
        assertEquals(listOf("a", "bc", "", "d"), lines)
        assertEquals(listOf(0 until 0), SyntaxColors.lines(""))
    }

    @Test
    fun `a comment across lines is coloured on each line`() {
        val text = "x /* one\ntwo */ y"
        val tokens = SyntaxColors.tokens(text, SyntaxColors.forPath("a.c")!!)
        val lines = SyntaxColors.lines(text)
        val first = SyntaxColors.inLine(tokens, lines[0].first, lines[0].last + 1)
        val second = SyntaxColors.inLine(tokens, lines[1].first, lines[1].last + 1)
        assertEquals(listOf(Token(2, 8, TokenKind.COMMENT)), first)
        assertEquals(listOf(Token(0, 6, TokenKind.COMMENT)), second)
    }
}
