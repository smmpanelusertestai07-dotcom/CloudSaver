package com.pocketide.ui.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * When Android reclaims the WebView renderer, every WebViewClient is asked, and one answering
 * false (the default) kills the app. Lint's own check misreads Kotlin clients, so this holds
 * each one to it.
 */
class WebViewClientsTest {
    private val declaration = Regex("""class (\w+)\b[^{]*:\s*WebViewClient\(\)\s*\{""")

    @Test
    fun `every WebViewClient survives the renderer going away`() {
        val clients = File("src/main/java").walk().filter { it.extension == "kt" }.flatMap { file ->
            val text = file.readText()
            declaration.findAll(text).map { match -> Triple(file.name, match.groupValues[1], body(text, match.range.last)) }
        }.toList()

        assertTrue("no WebViewClient found; the pattern is stale", clients.size >= 2)
        val missing = clients.filterNot { (_, _, body) -> body.contains("override fun onRenderProcessGone(") }
        assertTrue("without onRenderProcessGone: ${missing.map { "${it.first}:${it.second}" }}", missing.isEmpty())
    }

    /** The class body from its opening brace at [open] to the brace that closes it. */
    private fun body(text: String, open: Int): String {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, index)
            }
        }
        return text.substring(open)
    }
}
