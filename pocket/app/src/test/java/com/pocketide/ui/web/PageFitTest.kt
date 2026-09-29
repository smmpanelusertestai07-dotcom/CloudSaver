package com.pocketide.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFitTest {
    @Test
    fun `a page tall enough is drawn at its own size`() {
        assertEquals(1f, PageFit.zoom(PageFit.HEIGHT_DP))
        assertEquals(1f, PageFit.zoom(900f))
    }

    @Test
    fun `a 720x1600 phone at 2x zooms out until Antigravity's welcome fits`() {
        // The page gets 672 dp there; at 0.9 the workbench lays out 747 CSS pixels tall, measured to fit.
        val zoom = PageFit.zoom(672f)
        assertEquals(0.9f, zoom, 0.001f)
        assertTrue(672f / zoom >= 705f)
    }

    @Test
    fun `it never zooms out past the floor, even in landscape`() {
        assertEquals(PageFit.MIN_ZOOM, PageFit.zoom(300f))
    }

    @Test
    fun `nothing measured yet leaves the page as it is`() {
        assertEquals(1f, PageFit.zoom(0f))
    }

    @Test
    fun `the zoom moves in hundredths, so a pixel of layout does not redraw the page`() {
        assertEquals(PageFit.zoom(700f), PageFit.zoom(700.4f))
    }
}
