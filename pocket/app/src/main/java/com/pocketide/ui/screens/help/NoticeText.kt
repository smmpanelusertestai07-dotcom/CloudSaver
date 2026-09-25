package com.pocketide.ui.screens.help

/** Splits the open-source notices into paragraphs, one list item each. */
internal object NoticeText {
    private val BLANK_LINES = Regex("\n[ \t]*\n+")

    fun paragraphs(text: String): List<String> =
        text.replace("\r\n", "\n").split(BLANK_LINES).map { it.trimEnd() }.filter { it.isNotBlank() }
}
