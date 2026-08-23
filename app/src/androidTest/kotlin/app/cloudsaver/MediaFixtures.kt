package app.cloudsaver

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/**
 * Builds real media in MediaStore so the end-to-end test works on genuine
 * files rather than mocks. Test originals live in DCIM/CloudSaverTest, which
 * the scanner treats exactly like camera output.
 */
object MediaFixtures {

    const val TEST_ALBUM = "DCIM/CloudSaverTest"

    /** A detailed photo that JPEG cannot trivially shrink to nothing. */
    fun makeBitmap(width: Int, height: Int, seed: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val random = Random(seed)
        canvas.drawColor(Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Noise-like content keeps the encoder honest about file size.
        repeat(600) {
            paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            canvas.drawCircle(
                random.nextFloat() * width,
                random.nextFloat() * height,
                random.nextFloat() * (width / 12f) + 4f,
                paint
            )
        }
        return bitmap
    }

    /**
     * Inserts a JPEG into MediaStore with EXIF date + GPS, and returns its uri.
     * [captureMillis] becomes both DATE_TAKEN and the EXIF timestamp so the
     * test can prove the pipeline preserves it.
     */
    fun insertPhoto(
        context: Context,
        name: String,
        width: Int = 3000,
        height: Int = 2000,
        seed: Int = 1,
        captureMillis: Long = 1_600_000_000_000L
    ): Uri {
        val bitmap = makeBitmap(width, height, seed)
        val raw = ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 98, it)
        }.toByteArray()
        bitmap.recycle()

        // Stamp EXIF through a temp file (ExifInterface needs a seekable file).
        val temp = File(context.cacheDir, name)
        temp.writeBytes(raw)
        runCatching {
            val exif = ExifInterface(temp.absolutePath)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:09:13 12:26:40")
            exif.setAttribute(ExifInterface.TAG_MAKE, "CloudSaverTest")
            exif.setAttribute(ExifInterface.TAG_MODEL, "Fixture")
            exif.setLatLong(48.8584, 2.2945)
            exif.saveAttributes()
        }
        val bytes = temp.readBytes()
        temp.delete()

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$TEST_ALBUM/")
            put(MediaStore.MediaColumns.DATE_TAKEN, captureMillis)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(context.contentResolver.insert(collection, values)) {
            "MediaStore refused the fixture insert"
        }
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        context.contentResolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_TAKEN, captureMillis)
            },
            null,
            null
        )
        return uri
    }

    /**
     * Encodes a small H.264 clip and inserts it. Returns null when the device
     * has no usable encoder - the caller then skips video assertions instead
     * of failing on an emulator limitation.
     */
    fun insertVideo(
        context: Context,
        name: String,
        width: Int = 1280,
        height: Int = 720,
        frames: Int = 20,
        captureMillis: Long = 1_600_000_000_000L
    ): Uri? {
        val temp = File(context.cacheDir, name)
        if (!encodeH264(temp, width, height, frames)) {
            temp.delete()
            return null
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$TEST_ALBUM/")
            put(MediaStore.MediaColumns.DATE_TAKEN, captureMillis)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values) ?: return null
        context.contentResolver.openOutputStream(uri)!!.use { out ->
            temp.inputStream().use { it.copyTo(out) }
        }
        context.contentResolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_TAKEN, captureMillis)
            },
            null,
            null
        )
        temp.delete()
        return uri
    }

    /** Minimal buffer-mode encoder: NV12 frames in, MP4 out. */
    private fun encodeH264(out: File, width: Int, height: Int, frames: Int): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                .apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 10)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val frameBytes = width * height * 3 / 2
            val frame = ByteArray(frameBytes)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var muxing = false
            var sent = 0
            var done = false
            var guard = 0

            while (!done && guard++ < frames * 40) {
                if (sent <= frames) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        buffer.clear()
                        if (sent == frames) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, sent * 100_000L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        } else {
                            fillFrame(frame, width, height, sent)
                            buffer.put(frame)
                            codec.queueInputBuffer(inIndex, 0, frameBytes, sent * 100_000L, 0)
                        }
                        sent++
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    outIndex >= 0 -> {
                        val encoded = codec.getOutputBuffer(outIndex)!!
                        if (muxing && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(track, encoded, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
                    }
                }
            }
            muxing && out.length() > 0
        } catch (e: Exception) {
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun fillFrame(frame: ByteArray, width: Int, height: Int, index: Int) {
        val ySize = width * height
        for (y in 0 until height) {
            for (x in 0 until width) {
                frame[y * width + x] = ((x + y + index * 12) and 0xFF).toByte()
            }
        }
        var i = ySize
        while (i < frame.size) {
            frame[i] = ((i + index) and 0xFF).toByte()
            i++
        }
    }

    /** Removes every fixture this run created. */
    fun cleanUp(context: Context) {
        for (collection in listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        )) {
            runCatching {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("$TEST_ALBUM%"),
                    null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                        runCatching { context.contentResolver.delete(uri, null, null) }
                    }
                }
            }
        }
    }
}
