package app.cloudsaver.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.cloudsaver.R
import app.cloudsaver.media.MediaScanner
import app.cloudsaver.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The album picker, drawn the way a gallery draws it: a grid of covers with
 * the tick over the photo, not a column of checkboxes beside bare words.
 * "Camera" is a name; the photo taken this morning is the album. Both the
 * setup step and the Settings dialog use this one grid, so the two pickers
 * cannot drift apart.
 *
 * Every tile is one toggle - the whole tile takes the tap and announces
 * itself as a checkbox with the album's name, so a screen reader hears
 * "Camera, checkbox, ticked" rather than an unnamed box.
 */
@Composable
fun AlbumGrid(
    albums: List<MediaScanner.Album>,
    excluded: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    // Full-width rows above and below the tiles, inside the grid's own
    // scroll. A dialog that put these outside the grid had a body that
    // could not scroll, and on a short screen at a large font whatever sat
    // below the tiles was simply past the edge.
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null
) {
    LazyVerticalGrid(
        // Adaptive rather than a fixed count: three tiles on a 320 dp phone,
        // more as the screen widens, without anyone writing the number down.
        columns = GridCells.Adaptive(minSize = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            // This grid lives inside things that scroll (the setup page, a
            // dialog). A lazy container measured with no ceiling there does
            // not draw - the ceiling is what lets it measure at all; past it,
            // the grid scrolls its own tiles.
            .heightIn(max = maxHeight)
            .let { if (testTag != null) it.testTag(testTag) else it }
    ) {
        header?.let {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) { it() }
        }
        if (albums.isNotEmpty()) {
            // Long-press is invisible until someone says it exists. One quiet
            // line above the tiles, in every picker, is how it exists.
            item(key = "peek-hint", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.album_peek_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
        items(albums, key = { it.name }) { album ->
            AlbumTile(
                album = album,
                checked = album.name !in excluded,
                onToggle = { include -> onToggle(album.name, include) }
            )
        }
        footer?.let {
            item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { it() }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTile(
    album: MediaScanner.Album,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    Column(
        Modifier
            .heightIn(min = Dimens.TouchTarget)
            .clip(RoundedCornerShape(14.dp))
            // A tap ticks, exactly as before. A long press opens the cover in
            // whatever this phone views photos with - the peek the gallery
            // grid promises without leaving the choice it is here to make.
            // The semantics stay a checkbox with a state: a screen reader
            // still hears "Camera, checkbox, ticked", and the tests still
            // find a toggleable carrying the album's name.
            .combinedClickable(
                role = Role.Checkbox,
                onClick = { onToggle(!checked) },
                onLongClick = album.coverUri?.let { uri -> { peekAlbum(context, uri) } }
            )
            .semantics {
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            }
            .padding(2.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surfaceVariant)
        ) {
            val cover = albumCover(album.coverUri)
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // No cover to show - the album is empty of readable media, or
                // the thumbnail could not be decoded. A neutral glyph, never
                // a broken image.
                Icon(
                    Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(32.dp)
                        .align(Alignment.Center)
                )
            }
            // The tick sits on the photo, the way every gallery draws
            // selection. Unticked shows nothing rather than an empty ring:
            // the state a tile is in is said by the tick and the veil
            // together, and a page of hollow rings is noise.
            if (checked) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(scheme.scrim.copy(alpha = 0.28f))
                )
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier
                        .padding(6.dp)
                        .size(24.dp)
                        .align(Alignment.TopEnd)
                        .background(scheme.surface, CircleShape)
                )
            }
        }
        Text(
            album.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            pluralStringResource(R.plurals.album_item_count, album.count, album.count),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant
        )
    }
}

/**
 * Opens the album's newest photo in the phone's own viewer - the default
 * gallery where one is set, a chooser where none is. Read access travels with
 * that one uri only, and a phone with no viewer at all simply does nothing
 * rather than crashing the picker.
 */
private fun peekAlbum(context: Context, coverUri: String) {
    runCatching {
        val uri = Uri.parse(coverUri)
        val mime = context.contentResolver.getType(uri) ?: "image/*"
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(view, null))
    }
}

/**
 * The album's cover, loaded through MediaStore's own thumbnailer off the main
 * thread. Null while loading and null on failure - the tile shows its
 * placeholder either way, so a slow SD card or a revoked half of the media
 * permission degrades to a plain grid rather than an error.
 */
@Composable
private fun albumCover(uri: String?): ImageBitmap? {
    if (uri == null) return null
    val resolver = LocalContext.current.contentResolver
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                resolver.loadThumbnail(Uri.parse(uri), Size(256, 256), null)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}
