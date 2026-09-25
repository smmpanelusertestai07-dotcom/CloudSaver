package com.pocketide.ui.screens.help

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeTextTest {
    @Test
    fun `splits on blank lines and drops empty paragraphs`() {
        val text = "okhttp\r\nApache 2.0\r\n\r\n\n  \njgit\nEDL 1.0  \n\n"
        assertEquals(listOf("okhttp\nApache 2.0", "jgit\nEDL 1.0"), NoticeText.paragraphs(text))
    }

    @Test
    fun `keeps a text without blank lines whole`() {
        assertEquals(listOf("one\ntwo"), NoticeText.paragraphs("one\ntwo"))
        assertEquals(emptyList<String>(), NoticeText.paragraphs("\n\n"))
    }
}
