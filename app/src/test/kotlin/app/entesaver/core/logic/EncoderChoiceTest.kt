package app.entesaver.core.logic

import app.entesaver.media.ExifBlock
import app.entesaver.media.MediaTraits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which format a photo is written in and which codec a video gets, on the
 * phone in hand - and the pieces that keep a photo's metadata and HDR.
 */
class EncoderChoiceTest {

    private fun encode(format: PhotoFormat) = FormatResolver.Decision.Encode(format)

    @Test
    fun `Auto is HEIC only where the phone passed its own test`() {
        assertEquals(encode(PhotoFormat.HEIC), FormatResolver.resolve(PhotoFormat.AUTO, heicWorks = true, isUltraHdr = false, sdkInt = 30))
        assertEquals(encode(PhotoFormat.JPEG), FormatResolver.resolve(PhotoFormat.AUTO, heicWorks = false, isUltraHdr = false, sdkInt = 30))
        // Asked for HEIC on a phone that cannot: JPEG, said in the setting.
        assertEquals(encode(PhotoFormat.JPEG), FormatResolver.resolve(PhotoFormat.HEIC, heicWorks = false, isUltraHdr = false, sdkInt = 30))
        assertEquals(encode(PhotoFormat.WEBP), FormatResolver.resolve(PhotoFormat.WEBP, heicWorks = true, isUltraHdr = false, sdkInt = 29))
        assertEquals(encode(PhotoFormat.JPEG), FormatResolver.resolve(PhotoFormat.JPEG, heicWorks = true, isUltraHdr = false, sdkInt = 34))
    }

    @Test
    fun `an Ultra HDR photo keeps its HDR or is left alone`() {
        // Only JPEG carries the gain map, and only Android 14 writes it back.
        for (wanted in PhotoFormat.entries) {
            assertEquals(encode(PhotoFormat.JPEG), FormatResolver.resolve(wanted, heicWorks = true, isUltraHdr = true, sdkInt = 34))
            assertTrue(FormatResolver.resolve(wanted, heicWorks = true, isUltraHdr = true, sdkInt = 33) is FormatResolver.Decision.AsIs)
        }
    }

    /**
     * MediaProvider makes a file's extension agree with the type it is given.
     * The kept light copy used a table that knew only JPEG and MP4, so a HEIC
     * or WebP copy of a JPEG original was declared JPEG and landed as
     * "name.heic.jpg" - a name nothing that tracks the copy could recognise.
     */
    @Test
    fun `every format the app writes has its own MIME type`() {
        val sentinel = "application/x-not-mapped"
        for (format in PhotoFormat.entries) {
            val ext = FormatResolver.extensionOf(format)
            assertTrue(
                "$ext has no MIME type of its own",
                FormatResolver.mimeOf("IMG_0001__0123456789abcdef.$ext", sentinel) != sentinel
            )
        }
        assertEquals("image/heic", FormatResolver.mimeOf("a.heic", "image/jpeg"))
        assertEquals("image/webp", FormatResolver.mimeOf("a.WEBP", "image/jpeg"))
        assertEquals("video/mp4", FormatResolver.mimeOf("a.mp4", "video/quicktime"))
        // Anything unknown keeps what the original said it was.
        assertEquals("video/quicktime", FormatResolver.mimeOf("a.mov", "video/quicktime"))
    }

    @Test
    fun `HEVC needs a hardware encoder, whatever was asked`() {
        val chip = VideoCodecResolver.Hardware(hevcFits = true)
        val none = VideoCodecResolver.Hardware(hevcFits = false)
        assertEquals(VideoCodec.HEVC, VideoCodecResolver.resolve(VideoCodecChoice.AUTO, chip))
        assertEquals(VideoCodec.H264, VideoCodecResolver.resolve(VideoCodecChoice.AUTO, none))
        assertEquals("never HEVC in software", VideoCodec.H264, VideoCodecResolver.resolve(VideoCodecChoice.HEVC, none))
        assertEquals(VideoCodec.H264, VideoCodecResolver.resolve(VideoCodecChoice.H264, chip))
    }

    @Test
    fun `the EXIF block is lifted whole from a JPEG's APP1 segment`() {
        val tiff = byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08)
        val exif = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) + tiff
        val app0 = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x04, 0x4A, 0x46)
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x00, (exif.size + 2).toByte()) + exif
        val sos = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + app1 + sos
        assertArrayEquals(exif, ExifBlock.payloadOf(jpeg))
        // No EXIF segment before the picture data: nothing, never a guess.
        assertNull(ExifBlock.payloadOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + sos))
        assertNull(ExifBlock.payloadOf(byteArrayOf(1, 2, 3, 4)))
        // A segment that claims to run past the end of the file is refused.
        assertNull(ExifBlock.payloadOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x00)))
    }

    @Test
    fun `an Ultra HDR photo is told apart from a depth photo`() {
        val ultra = ("xxxx<x:xmpmeta hdrgm:Version=\"1.0\"><Container:Directory>" +
            "http://ns.adobe.com/hdr-gain-map/1.0/").toByteArray(Charsets.ISO_8859_1)
        val traits = MediaTraits.traitsOf(ultra)
        assertTrue(traits.ultraHdr)
        assertNull(traits.asIsReason)

        val portrait = "xxxx<Container:Directory> GDepth:Data hdrgm:Version".toByteArray(Charsets.ISO_8859_1)
        val depth = MediaTraits.traitsOf(portrait)
        assertFalse("depth data is never re-encoded", depth.ultraHdr)
        assertEquals("depth_photo", depth.asIsReason)

        val motion = "xxxx MotionPhoto hdrgm:Version".toByteArray(Charsets.ISO_8859_1)
        assertEquals("motion_photo", MediaTraits.traitsOf(motion).asIsReason)

        val plain = "an ordinary photo".toByteArray(Charsets.ISO_8859_1)
        assertEquals(MediaTraits.PhotoTraits(null, false), MediaTraits.traitsOf(plain))
    }
}
