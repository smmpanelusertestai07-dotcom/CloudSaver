package app.cloudsaver

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cloudsaver.core.logic.Preset
import app.cloudsaver.core.logic.Presets
import app.cloudsaver.media.PhotoCompressor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

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
            exif.setAttribute(ExifInterface.TAG_MAKE, "CloudSaverTest")
            exif.setAttribute(ExifInterface.TAG_MODEL, "Fixture")
            exif.saveAttributes()
        }
        return file
    }

    private fun compress(file: File) = PhotoCompressor.compress(
        context = context,
        uri = Uri.fromFile(file),
        displayName = file.name,
        srcBytes = file.length(),
        spec = Presets.spec(Preset.STORAGE_SAVER),
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
}
