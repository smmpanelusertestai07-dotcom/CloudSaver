package app.cloudsaver.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import app.cloudsaver.core.logic.BitrateCalc
import app.cloudsaver.core.logic.PresetSpec
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.sqrt

/**
 * Photo pipeline: decode within the preset pixel budget, JPEG q(preset) 4:2:0,
 * copy all important EXIF (dates, GPS, camera), bake orientation into pixels
 * (Orientation=1). If the result is not smaller, the original is copied as-is.
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

    fun compress(
        context: Context,
        uri: Uri,
        displayName: String,
        srcBytes: Long,
        spec: PresetSpec,
        tempDir: File
    ): CompressResult {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext in AS_IS_EXTS) {
            return copyAsIs(context, uri, displayName, tempDir, "format_as_is")
        }

        // Motion photos and multi-picture / depth JPEGs carry an embedded video
        // or depth map that re-encoding would throw away, so they are copied
        // byte-for-byte. The reason is shown in the item's details.
        MediaTraits.embeddedPayloadReason(context, uri)?.let { reason ->
            return copyAsIs(context, uri, displayName, tempDir, reason)
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

        val maxPixels = spec.photoMaxMp * 1_000_000L
        val sample = BitrateCalc.sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPixels)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap: Bitmap = try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return copyAsIs(context, uri, displayName, tempDir, "open_failed")
        } catch (e: Exception) {
            return copyAsIs(context, uri, displayName, tempDir, "decode_failed")
        } catch (e: OutOfMemoryError) {
            return copyAsIs(context, uri, displayName, tempDir, "oom")
        }

        try {
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

            val outFile = File(tempDir, "photo_${System.nanoTime()}.jpg")
            FileOutputStream(outFile).use { fos ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, spec.jpegQuality, fos)) {
                    outFile.delete()
                    return copyAsIs(context, uri, displayName, tempDir, "encode_failed")
                }
            }

            if (outFile.length() <= 0 || outFile.length() >= srcBytes) {
                outFile.delete()
                return copyAsIs(context, uri, displayName, tempDir, "not_smaller")
            }

            // Write EXIF onto the compressed copy; orientation is now normal.
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

            // Measure again, because writing the EXIF changed the file. The
            // encoder wrote no EXIF segment at all; saveAttributes inserts one
            // carrying every tag copied off the original, and two of those -
            // the description and the user comment - have no length limit. So
            // a copy that passed the check above by a few hundred bytes can be
            // pushed back over the original here, and this is the last moment
            // anything looks at it. That is the whole promise of the app: a
            // copy that is not smaller is not a saving, it is a second file.
            if (outFile.length() >= srcBytes) {
                outFile.delete()
                return copyAsIs(context, uri, displayName, tempDir, "not_smaller")
            }

            return CompressResult(
                outFile,
                outFile.length(),
                asIs = false,
                reason = "compressed",
                ext = "jpg",
                srcPixels = bounds.outWidth.toLong() * bounds.outHeight.toLong(),
                outPixels = bitmap.width.toLong() * bitmap.height.toLong()
            )
        } finally {
            bitmap.recycle()
        }
    }

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
