package app.cloudsaver.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.cloudsaver.core.logic.QualityKept
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cloudsaver.R
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.ui.components.KeyValueRow
import app.cloudsaver.util.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Original against optimised copy, before anything is deleted.
 *
 * Nobody should have to take "about 90-95% quality" on faith when the next
 * button removes their photograph. Dragging the slider shows the two versions
 * of the same picture in the same frame, at the same size, which is the only
 * comparison that means anything.
 *
 * When the copy is already in the cloud and no longer on the phone there is
 * nothing to draw, so the sheet says so and shows the recorded sizes instead
 * of rendering the original twice and implying they matched.
 */
@Composable
fun CompareSheet(
    row: ItemRow,
    onDismiss: () -> Unit,
    onKeepThisOne: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var split by remember { mutableFloatStateOf(0.5f) }

    val original by produceState<Bitmap?>(null, row.contentUri) {
        value = loadThumb(context, row.contentUri)
    }
    val optimised by produceState<Bitmap?>(null, row.outputUri, row.keptUri) {
        value = loadThumb(context, row.outputUri ?: row.keptUri)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = onKeepThisOne?.let {
            {
                TextButton(onClick = it) { Text(stringResource(R.string.compare_keep_this)) }
            }
        },
        title = {
            Text(
                row.displayName,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis
            )
        },
        text = {
            Column {
                val both = original != null && optimised != null
                if (both) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clipToBounds()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        // Optimised underneath, original clipped over it: the
                        // slider is a wipe across one image, not two pictures
                        // side by side at different scales.
                        Image(
                            bitmap = optimised!!.asImageBitmap(),
                            contentDescription = stringResource(R.string.compare_optimised),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            Modifier
                                .fillMaxWidth(split)
                                .fillMaxSize()
                                .clipToBounds()
                        ) {
                            Image(
                                bitmap = original!!.asImageBitmap(),
                                contentDescription = stringResource(R.string.compare_original),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.compare_original),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.compare_optimised),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Slider(value = split, onValueChange = { split = it })
                } else {
                    Text(
                        stringResource(R.string.compare_in_cloud),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                KeyValueRow(
                    stringResource(R.string.detail_original),
                    Formats.bytes(row.sizeBytes)
                )
                row.outputBytes?.let {
                    KeyValueRow(stringResource(R.string.detail_copy), Formats.bytes(it))
                    val saved = (row.sizeBytes - it).coerceAtLeast(0)
                    if (saved > 0) {
                        KeyValueRow(
                            stringResource(R.string.detail_saved),
                            stringResource(
                                R.string.detail_saved_value,
                                Formats.bytes(saved),
                                Formats.percentOf(saved, row.sizeBytes)
                            )
                        )
                    }
                }
                // What the encoder really did to this file, not what the preset
                // allows. Absent when the pixels were never recorded, because a
                // blank is honest and an invented percentage is not.
                QualityKept.measuredDetailKeptPercent(row.srcPixels, row.outPixels)?.let { kept ->
                    KeyValueRow(
                        stringResource(R.string.detail_kept),
                        if (kept >= 100) {
                            stringResource(R.string.detail_kept_all)
                        } else {
                            stringResource(R.string.detail_kept_value, kept)
                        }
                    )
                }
                Text(
                    stringResource(R.string.compare_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    )
}

/**
 * MediaStore's own cached thumbnail, not a full decode: this runs while the
 * user is scrolling a list of things they are about to delete, and a 60 MP
 * decode there would stutter or run out of heap.
 */
private suspend fun loadThumb(
    context: android.content.Context,
    uriString: String?
): Bitmap? {
    val uri = uriString?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.loadThumbnail(uri, Size(1024, 1024), null)
        }.getOrNull()
    }
}
