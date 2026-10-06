package app.entesaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.entesaver.core.logic.PhotoFormat
import app.entesaver.core.logic.PhotoSpec
import app.entesaver.media.PhotoCompressor
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one promise the compressor cannot break: the copy is smaller, or there
 * is no copy.
 *
 * Nothing tested [PhotoCompressor] directly - it needs the real graphics stack,
 * so the unit tests could not reach it, and the end-to-end suites only ever
 * fed it large fixtures written at quality 98, which shrink so easily that the
 * interesting case never arose.
 *
 * The interesting case is a photo that is already small and already well
 * compressed. There is nothing to gain there, and something to lose: the
 * encoder writes no EXIF at all, so the metadata has to be copied back on
 * afterwards, and the tags it carries - a description and a user comment among
 * them, neither with a length limit - can cost more than the re-encode saved.
 * The size was checked before that write and never again, so a copy could be
 * delivered, and counted as a saving, while being larger than the original it
 * replaced.
 */
@RunWith(AndroidJUnit4::class)
class PhotoCompressorTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File(context.cacheDir, "compressor_test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /**
     * A JPEG on disk, at the size and quality asked for, carrying [exifBytes]
     * of metadata the compressor is obliged to copy onto whatever it produces.
     */
    private fun sourceJpeg(
        name: String,
        width: Int,
        height: Int,
        quality: Int,
        exifBytes: Int = 0
    ): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Flat colour compresses to almost nothing, which would make even a
        // pointless re-encode look like a win. Noise does not.
        val pixels = IntArray(width * height) { i ->
            val r = (i * 37) and 0xFF
            val g = (i * 91) and 0xFF
            val b = (i * 173) and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val file = File(tempDir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        bitmap.recycle()

        if (exifBytes > 0) {
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "c".repeat(exifBytes / 2))
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "d".repeat(exifBytes / 2))
            exif.setAttribute(ExifInterface.TAG_MAKE, "EnteSaverTest")
            exif.setAttribute(ExifInterface.TAG_MODEL, "Fixture")
            exif.saveAttributes()
        }
        return file
    }

    private fun compress(
        file: File,
        spec: PhotoSpec = PhotoSpec(PhotoFormat.JPEG, 16, 82),
        heicWorks: Boolean = false
    ) = PhotoCompressor.compress(
        context = context,
        uri = Uri.fromFile(file),
        displayName = file.name,
        srcBytes = file.length(),
        spec = spec,
        heicWorks = heicWorks,
        tempDir = tempDir
    )

    /**
     * Whatever comes back, it is smaller than what went in - or it is the
     * original itself, copied byte for byte and labelled as such.
     */
    private fun assertNeverLargerThanTheOriginal(file: File) {
        val srcBytes = file.length()
        val result = compress(file)
        if (result.asIs) {
            assertEquals(
                "an as-is copy must be the original, byte for byte",
                srcBytes,
                result.bytes
            )
        } else {
            assertTrue(
                "${file.name}: the copy is ${result.bytes} bytes against an " +
                    "original of $srcBytes, so it is not a saving - it is a second file",
                result.bytes < srcBytes
            )
        }
        result.file.delete()
    }

    @Test
    fun aSmallWellCompressedPhotoWithHeavyMetadataIsNeverReturnedLarger() {
        // The case the size guard exists for, and the one it used to miss:
        // 8 KB of metadata against a re-encode that can save far less.
        assertNeverLargerThanTheOriginal(
            sourceJpeg("small_heavy_exif.jpg", 320, 240, quality = 35, exifBytes = 8_000)
        )
    }

    @Test
    fun aTinyPhotoWithHeavyMetadataIsNeverReturnedLarger() {
        assertNeverLargerThanTheOriginal(
            sourceJpeg("tiny_heavy_exif.jpg", 96, 96, quality = 30, exifBytes = 8_000)
        )
    }

    @Test
    fun anAlreadyHeavilyCompressedPhotoIsNeverReturnedLarger() {
        assertNeverLargerThanTheOriginal(
            sourceJpeg("already_squeezed.jpg", 800, 600, quality = 25)
        )
    }

    @Test
    fun aLargePhotoStillCompresses() {
        // The ordinary case, kept here so a guard that simply refused
        // everything would fail this file rather than pass it.
        val file = sourceJpeg("big.jpg", 3000, 2000, quality = 98)
        val result = compress(file)
        assertTrue(
            "a 3000x2000 photo written at quality 98 must compress, but came " +
                "back ${result.bytes} against ${file.length()} (asIs=${result.asIs})",
            !result.asIs && result.bytes < file.length()
        )
        result.file.delete()
    }

    /**
     * A photo already under the megapixel cap keeps every pixel it had.
     *
     * The budget is a ceiling, not a target: there is no path that enlarges a
     * photo to meet it, and this is the assertion that says so.
     */
    @Test
    fun aPhotoUnderTheCapIsNeverEnlarged() {
        val file = sourceJpeg("under_cap.jpg", 800, 600, quality = 90)
        val result = compress(file)
        if (!result.asIs) {
            assertEquals(
                "an 800x600 photo is far under the 16 MP cap and must not be resized",
                800L * 600L,
                result.outPixels
            )
        }
        result.file.delete()
    }

    /** A photo shot sideways, as a phone's EXIF says it was held. */
    private fun sidewaysJpeg(name: String): File {
        val file = sourceJpeg(name, 1200, 800, quality = 95, exifBytes = 200)
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:05:06 07:08:09")
            saveAttributes()
        }
        return file
    }

    private fun decodedSize(file: File): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    @Test
    fun aSidewaysPhotoComesOutUprightWithItsDate() {
        // The decoder turns the photo itself now; the copy must be upright
        // (portrait) and say so, and keep when it was taken.
        val result = compress(sidewaysJpeg("sideways.jpg"))
        assertTrue("must compress, got ${result.reason}", !result.asIs)
        val (w, h) = decodedSize(result.file)
        assertTrue("upright means portrait: ${w}x$h", h > w)
        val exif = ExifInterface(result.file.absolutePath)
        assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals("2024:05:06 07:08:09", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        result.file.delete()
    }

    @Test
    fun aWebpCopyKeepsTheShootingDate() {
        val file = sidewaysJpeg("for_webp.jpg")
        val result = compress(file, spec = PhotoSpec(PhotoFormat.WEBP, 16, 82))
        assertTrue("must compress, got ${result.reason}", !result.asIs)
        assertEquals("webp", result.ext)
        assertTrue(result.bytes < file.length())
        assertEquals(
            "2024:05:06 07:08:09",
            ExifInterface(result.file.absolutePath).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        )
        val (w, h) = decodedSize(result.file)
        assertTrue("upright means portrait: ${w}x$h", h > w)
        result.file.delete()
    }

    @Test
    fun aHeicRequestEndsInAReadableSmallerFileWhateverTheEncoder() {
        // Emulators have no HEIC chip. Told the phone passed anyway, the
        // compressor must either write a real HEIC or fall back to JPEG -
        // never fail the photo, never return something larger or unreadable.
        val file = sidewaysJpeg("for_heic.jpg")
        val result = compress(file, spec = PhotoSpec(PhotoFormat.HEIC, 16, 82), heicWorks = true)
        assertTrue("must compress, got ${result.reason}", !result.asIs)
        assertTrue(result.ext == "heic" || result.ext == "jpg")
        assertTrue(result.bytes < file.length())
        val (w, h) = decodedSize(result.file)
        assertTrue("readable and upright: ${w}x$h", w > 0 && h > w)
        result.file.delete()
    }

    @Test
    fun aPhotoOverTheCapIsDecodedStraightToTheCap() {
        // 3000x2000 is 6 MP; a 2 MP cap is a third of it, in one decode.
        val file = sourceJpeg("over_cap.jpg", 3000, 2000, quality = 95)
        val result = compress(file, spec = PhotoSpec(PhotoFormat.JPEG, 2, 82))
        assertTrue(!result.asIs)
        assertTrue("at most the cap: ${result.outPixels}", result.outPixels <= 2_000_000L)
        assertTrue("and not far under it: ${result.outPixels}", result.outPixels >= 1_800_000L)
        result.file.delete()
    }
}
