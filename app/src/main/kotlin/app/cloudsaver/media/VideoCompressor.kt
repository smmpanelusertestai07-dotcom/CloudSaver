package app.cloudsaver.media

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import app.cloudsaver.core.logic.BitrateCalc
import app.cloudsaver.core.logic.PresetSpec
import app.cloudsaver.core.logic.VideoCodec
import app.cloudsaver.util.AppLog
import com.google.common.collect.ImmutableList
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Video pipeline (Media3 Transformer): MP4 out, H.264/HEVC, aspect kept, fps kept,
 * rotation applied upright, AAC 128 kbps. Target bitrate from OUTPUT pixels x fps
 * (0.10 bpp H.264 / 0.065 HEVC), VBR first. Known Media3 pitfalls are handled with
 * a mandatory result check and a retry ladder: VBR -> CBR -> software encoder ->
 * copy as-is. An item is never lost.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object VideoCompressor {

    data class Probe(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val durationMs: Long,
        val bitrateBps: Long,
        val fps: Float
    )

    private data class Attempt(val cbr: Boolean, val software: Boolean, val label: String)

    private val ATTEMPTS = listOf(
        Attempt(cbr = false, software = false, label = "vbr"),
        Attempt(cbr = true, software = false, label = "cbr"),
        Attempt(cbr = false, software = true, label = "sw")
    )

    private val COPY_OK_CONTAINERS = setOf("video/mp4", "video/quicktime", "video/3gpp")

    suspend fun compress(
        context: Context,
        uri: Uri,
        displayName: String,
        mimeType: String,
        srcBytes: Long,
        spec: PresetSpec,
        codec: VideoCodec,
        tempDir: File,
        maxAttemptMs: Long = 20 * 60_000L
    ): CompressResult {
        val probe = probe(context, uri)
            ?: return PhotoCompressor.copyAsIs(context, uri, displayName, tempDir, "probe_failed")

        val upright = if (probe.rotation == 90 || probe.rotation == 270) {
            probe.height to probe.width
        } else {
            probe.width to probe.height
        }
        val (outW, outH) = BitrateCalc.outputDims(upright.first, upright.second, spec.videoLongSide)
        val targetBps = BitrateCalc.targetBps(outW, outH, probe.fps, codec)
        val containerOk = mimeType.lowercase() in COPY_OK_CONTAINERS
        val srcLongSide = maxOf(upright.first, upright.second)

        if (BitrateCalc.shouldCopyAsIs(srcLongSide, spec.videoLongSide, probe.bitrateBps, targetBps, containerOk)) {
            return PhotoCompressor.copyAsIs(context, uri, displayName, tempDir, "already_efficient")
        }

        val codecMime = if (codec == VideoCodec.H264) MimeTypes.VIDEO_H264 else MimeTypes.VIDEO_H265

        // HDR policy: H.264 cannot carry HDR, so tone-map to SDR. HEVC keeps HDR
        // only when this device can actually encode 10-bit HDR; otherwise it is
        // tone-mapped too. A device that can do neither ends at the as-is copy
        // below - colours are never silently washed out.
        val hdr = MediaTraits.hdrOf(context, uri)
        val keepHdr = hdr != MediaTraits.Hdr.NONE &&
            codec == VideoCodec.HEVC &&
            MediaTraits.deviceSupportsHdrHevcEncode()
        val hdrMode = when {
            hdr == MediaTraits.Hdr.NONE -> Composition.HDR_MODE_KEEP_HDR
            keepHdr -> Composition.HDR_MODE_KEEP_HDR
            else -> Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
        }
        val hdrTag = when {
            hdr == MediaTraits.Hdr.NONE -> ""
            keepHdr -> "_hdr"
            else -> "_tonemap"
        }

        for (attempt in ATTEMPTS) {
            val outFile = File(tempDir, "video_${System.nanoTime()}_${attempt.label}.mp4")
            val export = try {
                runTransform(context, uri, outFile, outW, outH, targetBps, codecMime, attempt, hdrMode, maxAttemptMs)
            } catch (ce: CancellationException) {
                outFile.delete()
                throw ce
            } catch (e: Exception) {
                AppLog.log(context, "video", "${attempt.label} threw: ${e.message}")
                null
            }
            if (export != null && outFile.exists()) {
                val outBytes = outFile.length()
                val outDur = if (export.durationMs > 0) export.durationMs else probeDurationMs(outFile)
                val outBps = if (outDur > 0) outBytes * 8000L / outDur else Long.MAX_VALUE
                if (BitrateCalc.resultAcceptable(srcBytes, outBytes, outBps, targetBps, probe.durationMs, outDur)) {
                    return CompressResult(
                        outFile,
                        outBytes,
                        asIs = false,
                        reason = "compressed_${attempt.label}$hdrTag",
                        ext = "mp4",
                        srcPixels = upright.first.toLong() * upright.second.toLong(),
                        outPixels = outW.toLong() * outH.toLong()
                    )
                }
                AppLog.log(
                    context, "video",
                    "${attempt.label} rejected: out=$outBytes src=$srcBytes bps=$outBps target=$targetBps"
                )
            }
            outFile.delete()
        }
        val failReason = if (hdr == MediaTraits.Hdr.NONE) "encoder_rejected" else "hdr_not_supported"
        return PhotoCompressor.copyAsIs(context, uri, displayName, tempDir, failReason)
    }

    /** Runs a single Transformer export; null on any export error or timeout. */
    private suspend fun runTransform(
        context: Context,
        uri: Uri,
        outFile: File,
        outW: Int,
        outH: Int,
        bitrateBps: Int,
        codecMime: String,
        attempt: Attempt,
        hdrMode: Int,
        maxAttemptMs: Long
    ): ExportResult? = withContext(Dispatchers.Default) {
        // Background priority: encoding must never make the phone feel slow.
        val thread = HandlerThread("cloudsaver-transform", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        val handler = Handler(thread.looper)
        val done = CompletableDeferred<ExportResult?>()
        val transformerRef = AtomicReference<Transformer?>(null)

        handler.post {
            try {
                val bitrateMode = if (attempt.cbr) {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                } else {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                }
                val videoSettings = VideoEncoderSettings.Builder()
                    .setBitrate(bitrateBps)
                    .setBitrateMode(bitrateMode)
                    .build()
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(videoSettings)
                    .setRequestedAudioEncoderSettings(
                        AudioEncoderSettings.Builder().setBitrate(128_000).build()
                    )
                    .setEnableFallback(true)
                    .apply { if (attempt.software) setVideoEncoderSelector(SOFTWARE_SELECTOR) }
                    .build()

                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done.complete(exportResult)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        done.complete(null)
                    }
                }

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(codecMime)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(listener)
                    .build()
                transformerRef.set(transformer)

                // Presentation is always applied: it keeps aspect (even dims) and
                // forces a real re-encode so the bitrate settings actually apply.
                val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setEffects(
                        Effects(
                            emptyList<androidx.media3.common.audio.AudioProcessor>(),
                            listOf<androidx.media3.common.Effect>(
                                Presentation.createForWidthAndHeight(
                                    outW, outH, Presentation.LAYOUT_SCALE_TO_FIT
                                )
                            )
                        )
                    )
                    .build()
                val composition = Composition.Builder(
                    EditedMediaItemSequence.Builder(edited).build()
                ).setHdrMode(hdrMode).build()
                transformer.start(composition, outFile.absolutePath)
            } catch (t: Throwable) {
                done.complete(null)
            }
        }

        try {
            withTimeout(maxAttemptMs) { done.await() }
        } catch (e: TimeoutCancellationException) {
            handler.post { runCatching { transformerRef.get()?.cancel() } }
            null
        } catch (ce: CancellationException) {
            handler.post { runCatching { transformerRef.get()?.cancel() } }
            throw ce
        } finally {
            handler.post { thread.quitSafely() }
        }
    }

    private val SOFTWARE_SELECTOR = EncoderSelector { mimeType ->
        val all = EncoderUtil.getSupportedEncoders(mimeType)
        val software = all.filter { info ->
            try {
                info.isSoftwareOnly ||
                    info.name.startsWith("c2.android.", ignoreCase = true) ||
                    info.name.startsWith("OMX.google.", ignoreCase = true)
            } catch (e: Exception) {
                false
            }
        }
        ImmutableList.copyOf(software)
    }

    fun probe(context: Context, uri: Uri): Probe? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (w == null || h == null || w <= 0 || h <= 0) return null
            Probe(
                width = w,
                height = h,
                rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0,
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                bitrateBps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toLongOrNull() ?: 0L,
                fps = extractFps(context, uri) ?: 30f
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun extractFps(context: Context, uri: Uri): Float? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    return try {
                        format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                    } catch (e: Exception) {
                        try {
                            format.getFloat(MediaFormat.KEY_FRAME_RATE)
                        } catch (e2: Exception) {
                            null
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun probeDurationMs(file: File): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { mmr.release() }
        }
    }
}
