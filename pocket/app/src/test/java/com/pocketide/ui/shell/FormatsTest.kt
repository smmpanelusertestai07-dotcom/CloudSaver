package com.pocketide.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatsTest {
    @Test
    fun bytesUseDecimalUnitsLikeAndroid() {
        assertEquals("0 B", Formats.bytes(0))
        assertEquals("999 B", Formats.bytes(999))
        assertEquals("1 KB", Formats.bytes(1000))
        assertEquals("1.3 MB", Formats.bytes(1_300_000))
        assertEquals("2.5 GB", Formats.bytes(2_500_000_000))
        assertEquals("272 MB", Formats.bytes(272_000_000))
        assertEquals("1 MB", Formats.bytes(999_700))
        assertEquals("1 GB", Formats.bytes(999_960_000))
    }

    @Test
    fun megabyteLimitsReadNaturally() {
        assertEquals("Off", Formats.megabytes(0))
        assertEquals("200 MB", Formats.megabytes(200))
        assertEquals("1 GB", Formats.megabytes(1000))
        assertEquals("1500 MB", Formats.megabytes(1500))
    }

    @Test
    fun countdownRoundsUpAndStopsAtZero() {
        assertEquals("15:00", Formats.countdown(900_000))
        assertEquals("0:01", Formats.countdown(1))
        assertEquals("0:00", Formats.countdown(-5))
        assertEquals("1:05", Formats.countdown(65_000))
    }

    @Test
    fun partsShareTheUnit() {
        assertEquals("3.2 of 3.7 GB", Formats.part(3_200_000_000, 3_700_000_000))
        assertEquals("320 MB of 1.4 GB", Formats.part(320_000_000, 1_400_000_000))
    }
}
