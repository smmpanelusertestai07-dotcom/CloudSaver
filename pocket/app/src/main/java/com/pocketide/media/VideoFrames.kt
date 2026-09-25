package com.pocketide.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.pocketide.core.AppDirs
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Turns picked videos into WebP key frames for an input that takes only pictures. The frames
 * are written to the app's share folder (cleaned like every shared file) and handed to the page
 * as FileProvider URIs; the video itself never reaches the page.
 */
object VideoFrames {
    private const val MAX_SIDE = 1280
    private const val QUALITY = 80

    /** Blocking: call on an IO dispatcher. */
    fun forImageInput(context: Context, picked: List<Uri>, perVideo: Int): KeyFrames.Replaced<Uri> {
        val resolver = context.contentResolver
        val share = AppDirs.from(context).share
        return KeyFrames.replaceVideos(
            picked,
            isVideo = { uri -> resolver.getType(uri)?.startsWith("video/") == true },
            frames = { uri ->
                val folder = File(share, UUID.randomUUID().toString())
                if (!folder.mkdirs()) {
                    emptyList()
                } else {
                    write(context, uri, perVideo, folder).map { FileProvider.getUriForFile(context, filesAuthority(context), it) }
                }
            },
        )
    }

    /** The frames of [video] as WebP files in [folder]; empty when Android cannot read the video. */
    private fun write(context: Context, video: Uri, count: Int, folder: File): List<File> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, video)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            KeyFrames.times(durationMs, count).mapIndexedNotNull { index, timeUs ->
                val frame = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_SIDE, MAX_SIDE)
                    ?: return@mapIndexedNotNull null
                try {
                    File(folder, "video-frame-${index + 1}.webp").also { file ->
                        file.outputStream().use { out -> if (!frame.compress(webp(), QUALITY, out)) throw IOException("not written") }
                    }
                } finally {
                    frame.recycle()
                }
            }
        } catch (_: IOException) {
            emptyList()
        } catch (_: RuntimeException) {
            // MediaMetadataRetriever reports a video it cannot read with IllegalArgumentException or RuntimeException.
            emptyList()
        } finally {
            retriever.release()
        }
    }

    private fun webp(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
}
