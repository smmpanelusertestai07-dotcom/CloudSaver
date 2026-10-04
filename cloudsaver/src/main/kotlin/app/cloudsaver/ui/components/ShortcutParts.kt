package app.cloudsaver.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.cloudsaver.R
import app.cloudsaver.ui.theme.Dimens
import app.cloudsaver.util.PhotosShortcut

private val SHORTCUT_LABELS = listOf(
    R.string.shortcut_label_photos, R.string.shortcut_label_gallery, R.string.shortcut_label_cloud
)

private val STYLE_PICTURES = mapOf(
    PhotosShortcut.Style.SUNSET to R.drawable.iconpack_photos,
    PhotosShortcut.Style.FAN to R.drawable.iconpack_gallery,
    PhotosShortcut.Style.CLOUD to R.drawable.iconpack_cloud_photos
)

/** The three gallery icons; tappable when [onPick] is given, a preview otherwise. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShortcutStyleRow(selected: PhotosShortcut.Style?, onPick: ((PhotosShortcut.Style) -> Unit)?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (style in PhotosShortcut.Style.entries) {
            val shape = RoundedCornerShape(12.dp)
            val base = Modifier
                .size(52.dp)
                .clip(shape)
            Image(
                painterResource(STYLE_PICTURES.getValue(style)),
                contentDescription = null,
                modifier = if (onPick == null) {
                    base
                } else {
                    base
                        .selectable(selected = selected == style, role = Role.RadioButton) { onPick(style) }
                        .then(
                            if (selected == style) {
                                Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                            } else {
                                Modifier
                            }
                        )
                }
            )
        }
    }
}

/**
 * Name and icon for the shortcut, then the phone's own confirmation. The
 * label is at most twelve characters, which is what fits under an icon.
 */
@Composable
fun ShortcutDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    var labelIndex by rememberSaveable { mutableStateOf(0) }
    var own by rememberSaveable { mutableStateOf("") }
    var style by rememberSaveable { mutableStateOf(PhotosShortcut.Style.SUNSET) }
    val fixed = SHORTCUT_LABELS.map { stringResource(it) }
    val label = if (labelIndex < fixed.size) {
        fixed[labelIndex]
    } else {
        PhotosShortcut.cleanLabel(own, fixed[0])
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.gallery_shortcut_button)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.shortcut_label_title), style = MaterialTheme.typography.labelLarge)
                for ((i, text) in (fixed + stringResource(R.string.shortcut_label_own)).withIndex()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimens.TouchTarget)
                            .selectable(selected = labelIndex == i, role = Role.RadioButton) { labelIndex = i }
                    ) {
                        RadioButton(selected = labelIndex == i, onClick = null)
                        Spacer(Modifier.width(10.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (labelIndex == fixed.size) {
                    OutlinedTextField(
                        value = own,
                        onValueChange = { own = it.take(PhotosShortcut.MAX_LABEL) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.shortcut_label_own)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    stringResource(R.string.shortcut_style_title),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                )
                ShortcutStyleRow(selected = style, onPick = { style = it })
                Text(
                    stringResource(R.string.shortcut_badge_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                PhotosShortcut.request(context, label, style)
                onDone()
            }) { Text(stringResource(R.string.shortcut_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
        }
    )
}
