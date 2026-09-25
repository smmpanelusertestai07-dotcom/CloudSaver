package com.pocketide.ui.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManageFormatTest {
    @Test
    fun `bytes use decimal units and step up instead of printing 1000`() {
        assertEquals("0 B", ManageFormat.bytes(0))
        assertEquals("0 B", ManageFormat.bytes(-5))
        assertEquals("999 B", ManageFormat.bytes(999))
        assertEquals("1 KB", ManageFormat.bytes(1_000))
        assertEquals("1.5 MB", ManageFormat.bytes(1_500_000))
        assertEquals("1 MB", ManageFormat.bytes(999_700))
        assertEquals("250 MB", ManageFormat.bytes(250_000_000))
        assertEquals("2.5 GB", ManageFormat.bytes(2_500_000_000))
        assertEquals("10 GB", ManageFormat.bytes(10_000_000_000))
    }

    @Test
    fun `minutes round partial minutes up like GitHub bills them`() {
        assertEquals("0 min", ManageFormat.minutes(0.0))
        assertEquals("0 min", ManageFormat.minutes(-3.0))
        assertEquals("1 min", ManageFormat.minutes(0.2))
        assertEquals("12 min", ManageFormat.minutes(12.0))
        assertEquals("1,235 min", ManageFormat.minutes(1234.5))
    }

    @Test
    fun `percent is whole, clamped, and unknown without a total`() {
        assertEquals(42, ManageFormat.percent(42.0, 100.0))
        assertEquals(0, ManageFormat.percent(0.0, 10.0))
        assertEquals(999, ManageFormat.percent(1e9, 1.0))
        assertNull(ManageFormat.percent(1.0, 0.0))
        assertEquals("<1%", ManageFormat.percentText(1.0, 1000.0))
        assertEquals("0%", ManageFormat.percentText(0.0, 1000.0))
        assertEquals("50%", ManageFormat.percentText(1.0, 2.0))
        assertEquals("", ManageFormat.percentText(1.0, 0.0))
    }

    @Test
    fun `counts pluralise`() {
        assertEquals("1 session", ManageFormat.count(1, "session"))
        assertEquals("3 sessions", ManageFormat.count(3, "session"))
        assertEquals("0 sessions", ManageFormat.count(0, "session"))
        assertEquals("2 private repositories", ManageFormat.count(2, "private repository", "private repositories"))
        assertEquals("1,200 files", ManageFormat.count(1200, "file"))
    }

    @Test
    fun `downloads are short`() {
        assertEquals("950", ManageFormat.downloads(950))
        assertEquals("52K", ManageFormat.downloads(52_000))
        assertEquals("52.4K", ManageFormat.downloads(52_400))
        assertEquals("1.3M", ManageFormat.downloads(1_300_000))
    }

    @Test
    fun `schedules read as plain intervals`() {
        assertEquals("Every hour", ManageFormat.every(1))
        assertEquals("Every 6 hours", ManageFormat.every(6))
        assertEquals("Every day", ManageFormat.every(24))
        assertEquals("Every 2 days", ManageFormat.every(48))
        assertEquals("Every week", ManageFormat.every(168))
        assertEquals("Every 30 hours", ManageFormat.every(30))
    }

    @Test
    fun `future times are rounded to what matters`() {
        assertEquals("now", ManageFormat.inFuture(-5_000))
        assertEquals("now", ManageFormat.inFuture(30_000))
        assertEquals("in 5 min", ManageFormat.inFuture(5 * 60_000L))
        assertEquals("in 3 h", ManageFormat.inFuture(3 * 3_600_000L))
        assertEquals("in 3 days", ManageFormat.inFuture(3 * 24 * 3_600_000L))
    }

    @Test
    fun `money has two decimals`() {
        assertEquals("$0.00", ManageFormat.usd(0.0))
        assertEquals("$1.24", ManageFormat.usd(1.236))
    }
}
