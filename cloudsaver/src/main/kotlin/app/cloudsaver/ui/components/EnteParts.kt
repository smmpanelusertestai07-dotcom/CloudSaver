package app.cloudsaver.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.cloudsaver.R
import app.cloudsaver.data.EnteApp
import app.cloudsaver.ui.theme.EnteGreen
import app.cloudsaver.ui.theme.OnBrand

/**
 * Ente's face wherever the app talks about it.
 *
 * Installed, it is Ente's own launcher icon read from the phone - always the
 * current one, never a logo shipped inside this APK. Not installed, it is a
 * tile in Ente's green with its initial, shaped like a launcher icon.
 */
@Composable
fun EnteIcon(installed: Boolean, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val context = LocalContext.current
    // Drawn at the screen's own density, so it is as sharp as the text beside it.
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon = remember(installed) { if (installed) EnteApp.icon(context) else null }
    if (icon != null) {
        Image(
            bitmap = remember(icon, px) { icon.toBitmap(px, px).asImageBitmap() },
            contentDescription = null,
            modifier = modifier.size(size)
        )
    } else {
        Box(
            modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.28f))
                .background(EnteGreen),
            contentAlignment = Alignment.Center
        ) {
            // Sized in dp, not sp: the tile is a picture of a fixed size, and a
            // letter that grew with the font setting would spill out of it.
            Text(
                "e",
                fontSize = with(LocalDensity.current) { (size * 0.6f).toSp() },
                fontWeight = FontWeight.Bold,
                color = OnBrand
            )
        }
    }
}

/**
 * The three places Ente is installed from. Each opens in the phone's own app
 * for it - the store, or the browser - since this app has no internet
 * permission and never downloads anything itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnteInstallButtons(onInstall: (EnteApp.Source) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (source in EnteApp.Source.entries) {
            OutlinedButton(onClick = { onInstall(source) }) {
                Text(
                    stringResource(
                        when (source) {
                            EnteApp.Source.PLAY -> R.string.ente_install_play
                            EnteApp.Source.FDROID -> R.string.ente_install_fdroid
                            EnteApp.Source.WEBSITE -> R.string.ente_install_web
                        }
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
