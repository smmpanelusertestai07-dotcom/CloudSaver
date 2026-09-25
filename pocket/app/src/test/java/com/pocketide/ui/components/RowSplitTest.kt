package com.pocketide.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Widths in dp on a 360 dp phone: a card row is 296 dp, less the 12 dp gap. */
class RowSplitTest {
    private val available = 284

    @Test
    fun whenBothFitTheValueKeepsItsWidthAndTheLabelTheRest() {
        assertEquals(60, RowSplit.valueWidth(available, labelWants = 90, valueWants = 60))
    }

    @Test
    fun aLongValueLeavesAShortLabelItsWholeWidth() {
        // "Unused computer" beside "Removed after 30 days without agent work".
        val value = RowSplit.valueWidth(available, labelWants = 110, valueWants = 260)
        assertEquals(174, value)
        assertEquals("the label is not squeezed below what it needs", 110, available - value)
    }

    @Test
    fun aLongLabelLeavesAShortValueItsWholeWidth() {
        assertEquals(70, RowSplit.valueWidth(available, labelWants = 400, valueWants = 70))
    }

    @Test
    fun whenBothAreLongEachGetsHalf() {
        assertEquals(142, RowSplit.valueWidth(available, labelWants = 300, valueWants = 300))
    }

    @Test
    fun aRowWithNoRoomGivesNothingAway() {
        assertEquals(0, RowSplit.valueWidth(0, labelWants = 50, valueWants = 50))
    }
}
