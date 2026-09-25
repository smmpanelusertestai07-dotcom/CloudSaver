package com.pocketide.ui.shell

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketide.core.Http
import com.pocketide.core.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * The GitHub account picture, or the first letter of the login while it loads (and when it
 * cannot). Only GitHub's own avatar host is fetched, and only a small image.
 */
@Composable
fun GitHubAvatar(login: String, avatarUrl: String?, size: Dp = 48.dp) {
    var image by remember(avatarUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(avatarUrl) {
        image = avatarUrl?.let { loadAvatar(it, pixels = 160) }
    }
    val current = image
    if (current != null) {
        Image(current, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    } else {
        Box(
            Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                login.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

private const val AVATAR_HOST = "avatars.githubusercontent.com"
private const val MAX_AVATAR_BYTES = 512 * 1024

private suspend fun loadAvatar(url: String, pixels: Int): ImageBitmap? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "https" || parsed.host != AVATAR_HOST) return null
    val sized = parsed.newBuilder().setQueryParameter("s", pixels.toString()).build()
    return try {
        Http.client.newCall(Request.Builder().url(sized).build()).await().use { response ->
            val body = response.body
            if (!response.isSuccessful || body.contentLength() > MAX_AVATAR_BYTES) return null
            withContext(Dispatchers.IO) {
                val bytes = body.byteStream().use { it.readNBytesCompat(MAX_AVATAR_BYTES + 1) }
                if (bytes.size > MAX_AVATAR_BYTES) null else decodeSmall(bytes, pixels)?.asImageBitmap()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/**
 * Decodes at about [pixels] wide: a small file can still claim a huge picture, and decoding that
 * at full size would take more memory than the phone has to spare.
 */
private fun decodeSmall(bytes: ByteArray, pixels: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sample = AvatarSize.sampleSize(bounds.outWidth, bounds.outHeight, pixels) ?: return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** How much to shrink an avatar while decoding it. */
object AvatarSize {
    /** Larger than any real avatar; anything bigger is refused rather than decoded. */
    private const val MAX_SIDE = 8192

    /** A power of two that brings the picture near [target] pixels, or null when it is not a usable picture. */
    fun sampleSize(width: Int, height: Int, target: Int): Int? {
        if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE) return null
        var sample = 1
        while (minOf(width, height) / (sample * 2) >= target) sample *= 2
        return sample
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
