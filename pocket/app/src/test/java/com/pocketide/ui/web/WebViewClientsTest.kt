package com.pocketide.ui.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's WebViews share one renderer. When Android reclaims it, every WebViewClient is asked
 * and a single one answering false (the default) kills the app, the agent's room with it.
 */
class WebViewClientsTest {
    private val declaration = Regex("""class (\w+)\b[^{]*:\s*WebViewClient\(\)\s*\{""")

    @Test
    fun everyWebViewClientSurvivesTheRendererGoingAway() {
        val clients = File("src/main/java").walk().filter { it.extension == "kt" }.flatMap { file ->
            val text = file.readText()
            declaration.findAll(text).map { match ->
                // A top-level class ends at the first closing brace at the start of a line.
                val end = text.indexOf("\n}", match.range.last).let { if (it < 0) text.length else it }
                Triple(file.name, match.groupValues[1], text.substring(match.range.last, end))
            }
        }.toList()

        assertTrue("no WebViewClient found; the pattern is stale", clients.size >= 3)
        val missing = clients.filterNot { (_, _, body) -> body.contains("override fun onRenderProcessGone(") }
        assertTrue("without onRenderProcessGone: ${missing.map { "${it.first}:${it.second}" }}", missing.isEmpty())
    }
}
