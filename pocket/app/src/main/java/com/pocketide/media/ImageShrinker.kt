package com.pocketide.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File

/** Re-encodes a still image as WebP when that makes it smaller. */
fun interface ImageShrinker {
    /** Writes [target] and returns true only when it is smaller than [source]. */
    fun toWebp(source: File, target: File): Boolean
}

/**
 * Android's own encoder. Images far larger than a screenshot are left as they are: decoding one
 * whole would need hundreds of megabytes on a 4 GB phone.
 */
internal class AndroidWebpShrinker : ImageShrinker {
    override fun toWebp(source: File, target: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) return false
        val bitmap = BitmapFactory.decodeFile(source.path) ?: return false
        try {
            val written = target.outputStream().use { bitmap.compress(format(), QUALITY, it) }
            return written && target.length() in 1 until source.length()
        } finally {
            bitmap.recycle()
        }
    }

    private fun format(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

    private companion object {
        const val QUALITY = 90
        const val MAX_PIXELS = 24_000_000L
    }
}
