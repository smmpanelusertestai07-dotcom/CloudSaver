package app.litesaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintTest {

    @Test
    fun fp16IsStableAndHex() {
        val a = Fingerprint.fp16("IMG_0001.jpg", 123456, 1700000000)
        val b = Fingerprint.fp16("IMG_0001.jpg", 123456, 1700000000)
        assertEquals(a, b)
        assertEquals(16, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
    }

    @Test
    fun fp16IsPathIndependentButContentSensitive() {
        val base = Fingerprint.fp16("IMG_0001.jpg", 123456, 1700000000)
        assertNotEquals(base, Fingerprint.fp16("IMG_0002.jpg", 123456, 1700000000))
        assertNotEquals(base, Fingerprint.fp16("IMG_0001.jpg", 123457, 1700000000))
        assertNotEquals(base, Fingerprint.fp16("IMG_0001.jpg", 123456, 1700000001))
    }

    @Test
    fun sha256OfKnownVector() {
        val hash = Fingerprint.sha256("abc".byteInputStream())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hash
        )
    }

    @Test
    fun outputNameFormat() {
        val fp = "0123456789abcdef"
        assertEquals(
            "IMG_0001__0123456789abcdef.jpg",
            Fingerprint.outputName("IMG_0001.jpg", fp, "jpg")
        )
        // Extension change (HEIC photo compressed to JPEG).
        assertEquals(
            "IMG_0001__0123456789abcdef.jpg",
            Fingerprint.outputName("IMG_0001.heic", fp, "jpg")
        )
        // Name without extension.
        assertEquals(
            "clip__0123456789abcdef.mp4",
            Fingerprint.outputName("clip", fp, "mp4")
        )
    }

    @Test
    fun fpFromOutputNameRoundTrip() {
        val fp = "0123456789abcdef"
        val name = Fingerprint.outputName("VID 2020.mp4", fp, "mp4")
        assertEquals(fp, Fingerprint.fpFromOutputName(name))
    }

    @Test
    fun fpFromOutputNameHandlesMediaStoreDedupSuffix() {
        assertEquals(
            "0123456789abcdef",
            Fingerprint.fpFromOutputName("IMG__0123456789abcdef (1).jpg")
        )
    }

    @Test
    fun fpFromOutputNameRejectsForeignNames() {
        assertNull(Fingerprint.fpFromOutputName("random.jpg"))
        assertNull(Fingerprint.fpFromOutputName("a__notahexstring16.jpg"))
        assertNull(Fingerprint.fpFromOutputName("a__0123.jpg"))
    }

    @Test
    fun stripDedupSuffix() {
        assertEquals("a__b.jpg", Fingerprint.stripDedupSuffix("a__b (1).jpg"))
        assertEquals("a__b.jpg", Fingerprint.stripDedupSuffix("a__b.jpg"))
        assertEquals("a (1) x.jpg", Fingerprint.stripDedupSuffix("a (1) x.jpg"))
    }
}
