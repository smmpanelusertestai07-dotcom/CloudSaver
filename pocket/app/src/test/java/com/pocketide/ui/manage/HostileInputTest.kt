package com.pocketide.ui.manage

import com.pocketide.docs.DocBlock
import com.pocketide.docs.DocSection
import com.pocketide.github.AccountUsage
import com.pocketide.github.UsageLine
import com.pocketide.secrets.SecretKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Odd numbers from GitHub and odd text from anywhere must never crash a screen. */
class HostileInputTest {
    private val random = Random(20260924)

    private fun randomText(max: Int): String {
        val alphabet = "ab cd\n\t…é漢😀\u0000​.*+?[](){}\\|^$"
        return buildString { repeat(random.nextInt(max)) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    @Test
    fun `search and snippets survive any text and any query`() {
        repeat(500) {
            val body = randomText(400)
            val section = DocSection("s$it", randomText(30), randomText(60), listOf(DocBlock.Paragraph(body)))
            val query = randomText(20)
            HelpSearch.search(query, listOf(section), emptyList(), emptyList())
            val snippet = HelpSearch.snippet(body, HelpSearch.words(query), radius = random.nextInt(0, 100))
            assertTrue(snippet.length <= body.length + 2)
        }
    }

    @Test
    fun `a tiny radius and letters that grow when lower-cased never cut out of range`() {
        assertEquals("…word…", HelpSearch.snippet("aaaa bbbb word cccc dddd", listOf("word"), radius = 0))
        val turkish = "İİİİİİİİİİ " + "x ".repeat(80) + "needle end"
        assertTrue(HelpSearch.snippet(turkish, listOf("needle")).contains("needle"))
        assertTrue(HelpSearch.snippet("İstanbul needle", listOf("needle"), radius = 1).contains("needle"))
    }

    @Test
    fun `numbers from GitHub that make no sense still format`() {
        val odd = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -5.0, 0.0, 1e18)
        for (q in odd) {
            val usage = AccountUsage(
                plan = "free",
                lines = listOf(
                    UsageLine("actions", "actions_linux", q, "Minutes", q),
                    UsageLine("actions", "actions_macos", q, "minutes", q),
                    UsageLine("actions", "actions_storage", q, "GigabyteHours", q),
                ),
                periodStart = null,
                periodEnd = null,
            )
            val summary = ActionsUsage.summarize(usage)
            assertTrue(summary.chargedUsd.isNaN() || summary.chargedUsd >= 0)
            ManageFormat.minutes(summary.countedMinutes)
            ManageFormat.percentText(summary.countedMinutes, 2000.0)
            ActionsUsage.storageShare(summary.storageGbHours, summary.allowance, 1_790_000_000_000L)
        }
        assertEquals("0 min", ManageFormat.minutes(Double.NaN))
        assertEquals("", ManageFormat.percentText(1.0, Double.NaN))
    }

    @Test
    fun `byte counts at the edges`() {
        assertEquals("0 B", ManageFormat.bytes(Long.MIN_VALUE))
        assertTrue(ManageFormat.bytes(Long.MAX_VALUE).endsWith(" TB"))
        assertEquals("0", ManageFormat.downloads(-1))
        assertEquals("Every hour", ManageFormat.every(0))
        assertEquals("now", ManageFormat.inFuture(Long.MIN_VALUE))
        assertTrue(ManageFormat.inFuture(Long.MAX_VALUE).endsWith("days"))
    }

    @Test
    fun `value names from a paste are checked, not trusted`() {
        val hostile = listOf("", " ", "A B", "1ABC", "ÄPI_KEY", "API-KEY", "API_KEY\n", "\u0000", "a".repeat(101), "API​KEY")
        for (name in hostile) {
            assertTrue("'$name'", ValueNames.problem(name, SecretKind.VARIABLE) != null || name.trim() == "API_KEY")
        }
    }
}
