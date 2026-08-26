package app.cloudsaver.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Sizes have to be printed in enough detail to carry the app's own claim.
 *
 * Every screen here says the same thing in numbers: this file was that big,
 * its copy is this big, so you saved the difference. A unit too coarse to
 * separate the two turns that into "5 MB to 5 MB (410 KB smaller)" - the same
 * figure twice followed by an assertion that it changed, which reads as the
 * app being wrong rather than as rounding.
 */
class FormatsTest {

    // The app counts in decimal units, the way storage is sold and the way
    // Android's own settings report it - not in 1024s.
    private val kb = Formats.KB
    private val mb = Formats.MB
    private val gb = Formats.GB

    @Test
    fun aPhotoAndItsOptimisedCopyDoNotPrintTheSame() {
        // The sizes an ordinary phone photo actually has, before and after.
        val original = (5.4 * mb).toLong()
        val copy = (4.8 * mb).toLong()
        assertNotEquals(Formats.bytes(original), Formats.bytes(copy))
    }

    @Test
    fun smallMegabytesKeepOneDecimal() {
        assertEquals("5.4 MB", Formats.bytes((5.4 * mb).toLong()))
        assertEquals("1.0 MB", Formats.bytes(mb))
    }

    @Test
    fun largeMegabytesStayWhole() {
        // Ten megabytes and up, the decimal buys nothing and the budgets the
        // user picks - "500 MB" - have to read back in the words they picked.
        assertEquals("500 MB", Formats.bytes(500 * mb))
        assertEquals("12 MB", Formats.bytes(12 * mb))
    }

    @Test
    fun aPickedGigabyteReadsBackAsThePickedWords() {
        assertEquals("5 GB", Formats.bytes(5 * gb))
        assertEquals("1.50 GB", Formats.bytes((1.5 * gb).toLong()))
    }

    @Test
    fun kilobytesAndBytesAreWhole() {
        assertEquals("589 KB", Formats.bytes(589 * kb))
        assertEquals("512 B", Formats.bytes(512))
    }

    @Test
    fun nothingIsNotANegativeNumber() {
        assertEquals("0 MB", Formats.bytes(0))
        assertEquals("0 MB", Formats.bytes(-1))
    }
}
