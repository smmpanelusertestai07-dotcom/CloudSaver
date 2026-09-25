package com.pocketide.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixLadderTest {

    private val breaks = checkNotNull(DocsContent.section("if-something-breaks"))

    @Test
    fun `the ladder goes from cheapest to a full rebuild`() {
        assertEquals(
            listOf("Reload the agent screen", "Restart the agent", "Restart the computer", "Repair", "Reset computer"),
            FixLadder.rungs.map { it.title },
        )
        val actions = FixLadder.rungs.map { it.action }
        assertEquals(
            listOf(null, null, FixLadder.Action.RESTART_COMPUTER, FixLadder.Action.REPAIR, FixLadder.Action.RESET_COMPUTER),
            actions,
        )
    }

    @Test
    fun `Help lists every level of the Computer screen's ladder, in its order`() {
        val steps = breaks.blocks.filterIsInstance<DocBlock.Steps>().single().items
        assertEquals(FixLadder.rungs.map(FixLadder::helpStep), steps)
        val intro = breaks.blocks[breaks.blocks.indexOfFirst { it is DocBlock.Steps } - 1]
        assertEquals(DocBlock.Paragraph("Start at the top. Steps 3 to 5 are buttons on the Computer screen."), intro)
        val repair = steps.indexOfFirst { it.startsWith("Repair") }
        val reset = steps.indexOfFirst { it.startsWith("Reset computer") }
        assertTrue("Repair must come before Reset computer: $steps", repair in 0 until reset)
        assertTrue(steps.any { it.startsWith("Restart the computer") })
    }

    @Test
    fun `each level says what it fixes and what it costs`() {
        for (rung in FixLadder.rungs) {
            assertTrue(rung.title, rung.fixes.startsWith("For "))
            assertTrue(rung.title, rung.cost.isNotBlank())
            assertTrue(rung.title, FixLadder.helpStep(rung).contains(rung.fixes) && FixLadder.helpStep(rung).contains(rung.cost))
        }
        assertTrue(FixLadder.rungs.last().cost.contains("big download"))
    }

    @Test
    fun `a level without a button says how to reach it`() {
        for (rung in FixLadder.rungs) {
            assertEquals(rung.title, rung.action == null, rung.how != null)
            rung.how?.let { assertTrue(rung.title, FixLadder.text(rung).contains(it)) }
        }
        assertEquals("4. Repair", FixLadder.heading(3))
    }

    @Test
    fun `sign-in errors from a changed link have their own rows`() {
        val symptoms = breaks.blocks.filterIsInstance<DocBlock.Table>().first { it.header.first() == "You see" }
        for (error in listOf("Error 400 invalid_request", "401 invalid_client", "Invalid code verifier")) {
            val row = symptoms.rows.firstOrNull { it.first().contains(error) }
            assertTrue("no row for $error", row != null)
            assertTrue(error, checkNotNull(row).last().contains("fresh sign-in"))
        }
    }
}
