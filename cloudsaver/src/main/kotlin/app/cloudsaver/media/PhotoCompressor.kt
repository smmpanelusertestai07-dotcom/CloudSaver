package app.cloudsaver.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import app.cloudsaver.core.logic.BitrateCalc
import app.cloudsaver.core.logic.FormatResolver
import app.cloudsaver.core.logic.PhotoFormat
import app.cloudsaver.core.logic.PhotoSpec
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.sqrt

/**
 * Photo pipeline: decode upright within the setting's pixel budget, write
 * HEIC, WebP or JPEG at the setting's quality, copy all important EXIF
 * (dates, GPS, camera) with orientation normal. If the result is not smaller,
 * or would lose something the original carries, the original is copied as-is.
 */
object PhotoCompressor {

    private val RAW_EXTS = setOf(
        "dng", "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2", "orf", "raf", "rw2", "pef"
    )
    private val AS_IS_EXTS = setOf("gif", "svg", "psd") + RAW_EXTS

    private val EXIF_TAGS = arrayOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_USER_COMMENT
    )

    /**
     * Makes the light copy of one photo, or copies it as it is when a copy
     * would lose something or would not be smaller.
     *
     * [heicWorks] is this phone's answer from [HeicSupport]: HEIC is written
     * only where the phone's own hardware has proved it can.
     */
    fun compress(
        context: Context,
        uri: Uri,
        displayName: String,
        srcBytes: Long,
        spec: PhotoSpec,
        heicWorks: Boolean,
        tempDir: File
    ): CompressResult {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext in AS_IS_EXTS) {
            return copyAsIs(context, uri, displayName, tempDir, "format_as_is")
        }

        // Motion photos and multi-picture / depth JPEGs carry an embedded video
        // or depth map that re-encoding would throw away, so they are copied
        // byte-for-byte. The reason is shown in the item's details.
        val traits = MediaTraits.photoTraits(context, uri)
        traits.asIsReason?.let { reason ->
            return copyAsIs(context, uri, displayName, tempDir, reason)
        }
        val format = when (
            val decision = FormatResolver.resolve(spec.format, heicWorks, traits.ultraHdr, Build.VERSION.SDK_INT)
        ) {
            is FormatResolver.Decision.AsIs -> return copyAsIs(context, uri, displayName, tempDir, decision.reason)
            is FormatResolver.Decision.Encode -> decision.format
        }

        // Read EXIF (with original GPS if ACCESS_MEDIA_LOCATION is granted).
        val exifValues = HashMap<String, String>()
        var orientation = ExifInterface.ORIENTATION_NORMAL
        try {
            openOriginal(context, uri)?.use { input ->
                val exif = ExifInterface(input)
                orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
                for (tag in EXIF_TAGS) {
                    exif.getAttribute(tag)?.let { exifValues[tag] = it }
                }
            }
        } catch (e: Exception) {
            // EXIF is best effort; keep going.
        }

        // Bounds pass.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        } catch (e: Exception) {
            return copyAsIs(context, uri, displayName, tempDir, "decode_bounds_failed")
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return copyAsIs(context, uri, displayName, tempDir, "undecodable")
        }

        val maxPixels = spec.maxPixels
        var bitmap: Bitmap = try {
            decodeUpright(context, uri, bounds.outWidth, bounds.outHeight, orientation, maxPixels)
                ?: return copyAsIs(context, uri, displayName, tempDir, "decode_failed")
        } catch (e: OutOfMemoryError) {
            return copyAsIs(context, uri, displayName, tempDir, "oom")
        }

        try {
            var written = format
            var outFile = encode(context, bitmap, format, spec.quality, exifValues, tempDir)
            if (outFile == null && format == PhotoFormat.HEIC) {
                // This phone passed the HEIC test but failed on this photo: the
                // photo goes out as JPEG, and enough of these undo the pass.
                HeicSupport.noteFailure(context)
                written = PhotoFormat.JPEG
                outFile = encode(context, bitmap, PhotoFormat.JPEG, spec.quality, exifValues, tempDir)
            }
            if (outFile == null) {
                return copyAsIs(context, uri, displayName, tempDir, "encode_failed")
            }

            // Measured after the EXIF went in. The encoder wrote no EXIF at
            // all; the block copied off the original carries every tag, and
            // two of them - the description and the user comment - have no
            // length limit, so a copy that was smaller a moment ago can be
            // pushed back over the original. That is the whole promise of the
            // app: a copy that is not smaller is not a saving, it is a second
            // file.
            if (outFile.length() <= 0 || outFile.length() >= srcBytes) {
                outFile.delete()
                return copyAsIs(context, uri, displayName, tempDir, "not_smaller")
            }
            // An Ultra HDR photo is only re-made when its gain map survives;
            // otherwise its HDR would quietly be gone from the copy.
            if (traits.ultraHdr && !MediaTraits.hasGainMap(outFile)) {
                outFile.delete()
                return copyAsIs(context, uri, displayName, tempDir, "ultra_hdr_kept")
            }

            return CompressResult(
                outFile,
                outFile.length(),
                asIs = false,
                reason = "compressed_${written.name.lowercase()}",
                ext = FormatResolver.extensionOf(written),
                srcPixels = bounds.outWidth.toLong() * bounds.outHeight.toLong(),
                outPixels = bitmap.width.toLong() * bitmap.height.toLong()
            )
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * The photo, upright and no bigger than [maxPixels], in one decode.
     *
     * The platform decoder scales while it decodes and turns the photo the
     * way its EXIF says, so a 50 MP photo becomes a 16 MP bitmap without a
     * 50 MP one ever being held - on a 4 GB phone, the difference between a
     * photo that is optimised and one the system kills the app over. The
     * older route (sample, scale, rotate: three bitmaps) remains for any
     * photo that decoder will not open.
     */
    private fun decodeUpright(
        context: Context,
        uri: Uri,
        width: Int,
        height: Int,
        orientation: Int,
        maxPixels: Long
    ): Bitmap? {
        val swaps = orientation in SWAPPING_ORIENTATIONS
        val uprightW = if (swaps) height else width
        val uprightH = if (swaps) width else height
        decodeWithImageDecoder(context, uri, maxPixels)?.let { decoded ->
            // Trusted only when it came out the shape the EXIF says it is.
            val wantLandscape = uprightW >= uprightH
            val isLandscape = decoded.width >= decoded.height
            if (uprightW == uprightH || wantLandscape == isLandscape) return decoded
            decoded.recycle()
        }
        return decodeWithBitmapFactory(context, uri, width, height, orientation, maxPixels)
    }

    private fun decodeWithImageDecoder(context: Context, uri: Uri, maxPixels: Long): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            // Software memory: a hardware bitmap cannot be compressed.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val pixels = w.toLong() * h.toLong()
            if (pixels > maxPixels) {
                val scale = sqrt(maxPixels.toDouble() / pixels)
                decoder.setTargetSize(
                    (w * scale).toInt().coerceAtLeast(1),
                    (h * scale).toInt().coerceAtLeast(1)
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun decodeWithBitmapFactory(
        context: Context,
        uri: Uri,
        width: Int,
        height: Int,
        orientation: Int,
        maxPixels: Long
    ): Bitmap? {
        val sample = BitrateCalc.sampleSizeFor(width, height, maxPixels)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap: Bitmap = try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null
        } catch (e: Exception) {
            return null
        }
        // Exact downscale if the sampled decode is still over budget.
        val pixels = bitmap.width.toLong() * bitmap.height.toLong()
        if (pixels > maxPixels) {
            val scale = sqrt(maxPixels.toDouble() / pixels)
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            if (scaled !== bitmap) {
                bitmap.recycle()
                bitmap = scaled
            }
        }
        // Bake EXIF orientation into pixels.
        val matrix = orientationMatrix(orientation)
        if (matrix != null) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) {
                bitmap.recycle()
                bitmap = rotated
            }
        }
        return bitmap
    }

    /**
     * Writes [bitmap] as [format] with the original's EXIF, orientation
     * normal; null when the encoder would not, with nothing left behind.
     */
    private fun encode(
        context: Context,
        bitmap: Bitmap,
        format: PhotoFormat,
        quality: Int,
        exifValues: Map<String, String>,
        tempDir: File
    ): File? {
        val outFile = File(tempDir, "photo_${System.nanoTime()}.${FormatResolver.extensionOf(format)}")
        val ok = try {
            when (format) {
                PhotoFormat.HEIC -> {
                    HeicSupport.write(
                        bitmap, outFile, quality,
                        exif = ExifBlock.build(exifValues, tempDir),
                        timeoutMs = HEIC_PHOTO_LIMIT_MS
                    )
                    true
                }
                else -> FileOutputStream(outFile).use { fos ->
                    bitmap.compress(compressFormatFor(format), quality, fos)
                }
            }
        } catch (e: Exception) {
            false
        }
        if (!ok || outFile.length() <= 0) {
            outFile.delete()
            return null
        }
        if (format != PhotoFormat.HEIC) {
            // Write EXIF onto the copy; orientation is now normal.
            try {
                val outExif = ExifInterface(outFile.absolutePath)
                for ((tag, value) in exifValues) outExif.setAttribute(tag, value)
                outExif.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString()
                )
                outExif.saveAttributes()
            } catch (e: Exception) {
                // EXIF write failure is not fatal.
            }
        }
        return outFile
    }

    @Suppress("DEPRECATION")
    private fun compressFormatFor(format: PhotoFormat): Bitmap.CompressFormat = when {
        format != PhotoFormat.WEBP -> Bitmap.CompressFormat.JPEG
        // The small, sharp WebP arrived in Android 11; the older one is still
        // lossy below quality 100.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSY
        else -> Bitmap.CompressFormat.WEBP
    }

    /** How long one photo's HEIC encode may take before it goes out as JPEG. */
    private const val HEIC_PHOTO_LIMIT_MS = 30_000L

    private val SWAPPING_ORIENTATIONS = setOf(
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_TRANSVERSE
    )

    private fun orientationMatrix(orientation: Int): Matrix? {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f); m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f); m.postScale(-1f, 1f)
            }
            else -> return null
        }
        return m
    }

    /** Original stream incl. location EXIF when permitted; falls back to redacted. */
    private fun openOriginal(context: Context, uri: Uri): InputStream? = try {
        context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))
    } catch (e: Exception) {
        try {
            context.contentResolver.openInputStream(uri)
        } catch (e2: Exception) {
            null
        }
    }

    fun copyAsIs(
        context: Context,
        uri: Uri,
        displayName: String,
        tempDir: File,
        reason: String
    ): CompressResult {
        val ext = displayName.substringAfterLast('.', "bin").lowercase().ifEmpty { "bin" }
        val outFile = File(tempDir, "asis_${System.nanoTime()}.$ext")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "open failed" }
                FileOutputStream(outFile).use { output -> input.copyTo(output, 64 * 1024) }
            }
        } catch (t: Throwable) {
            // A copy that stopped halfway is not a copy. The fragment would
            // otherwise sit in the work folder counting against the staging
            // limit until some later run swept it - and the disk being full
            // is precisely how this fails in the first place.
            outFile.delete()
            throw t
        }
        return CompressResult(outFile, outFile.length(), asIs = true, reason = reason, ext = ext)
    }
}
