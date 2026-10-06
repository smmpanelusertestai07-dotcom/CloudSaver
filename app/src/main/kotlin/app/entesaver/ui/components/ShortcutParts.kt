package app.entesaver.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.entesaver.R
import app.entesaver.ui.theme.Dimens
import app.entesaver.util.PhotosShortcut

private val SHORTCUT_LABELS = listOf(
    R.string.shortcut_label_photos, R.string.shortcut_label_gallery, R.string.shortcut_label_cloud
)

/**
 * The gallery icon Ente is given - by the icon pack and by the shortcut
 * alike, so the two never disagree about what Ente looks like.
 */
@Composable
fun PhotosIcon(size: Dp = 52.dp) {
    Image(
        painterResource(R.drawable.iconpack_photos),
        contentDescription = null,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 24))
    )
}

/**
 * A name for the shortcut, shown as it will sit on the home screen, then the
 * phone's own confirmation. The label is at most twelve characters, which is
 * what fits under an icon.
 */
@Composable
fun ShortcutDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    var labelIndex by rememberSaveable { mutableStateOf(0) }
    var own by rememberSaveable { mutableStateOf("") }
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    PhotosIcon(56.dp)
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
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
                    stringResource(R.string.shortcut_badge_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                PhotosShortcut.request(context, label)
                onDone()
            }) { Text(stringResource(R.string.shortcut_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
        }
    )
}
