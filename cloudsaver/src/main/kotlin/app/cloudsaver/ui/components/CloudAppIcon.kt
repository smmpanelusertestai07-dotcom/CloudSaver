package app.cloudsaver.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.cloudsaver.data.CloudApp
import app.cloudsaver.data.CloudApps
import app.cloudsaver.ui.theme.OnBrand
import app.cloudsaver.ui.theme.OnOtherAppPlainTile
import app.cloudsaver.ui.theme.OtherAppPlainTile

/**
 * The face a cloud app wears in every picker.
 *
 * Installed, it is the app's own icon read from the package manager - always
 * current, never a third-party logo shipped inside this APK. Not installed,
 * it is the app's initial on a tile in the app's own colour, shaped like a
 * launcher icon: MEGA's red, Proton's purple, OneDrive's blue. That used to
 * be one grey circle for every app, which in the light theme sat on a dialog
 * of almost the same grey - the circle vanished and each row read as a stray
 * letter where an image had failed to load. Apps whose icon is mostly white
 * get a white tile with a border, so it shows on light and dark alike.
 * "Other app" is not an app and keeps the cloud.
 */
@Composable
fun CloudAppIcon(app: CloudApp, installed: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val size = 28.dp
    // Drawn at the screen's own density: a fixed 96 px bitmap was stretched
    // to 112 px on the sharpest phones and looked soft beside the text.
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon = remember(app.id, installed) {
        if (installed) CloudApps.iconFor(context, app) else null
    }
    when {
        icon != null -> Image(
            bitmap = remember(icon, px) { icon.toBitmap(px, px).asImageBitmap() },
            contentDescription = null,
            modifier = modifier.size(size)
        )
        app.packages.isEmpty() -> Icon(
            Icons.Outlined.CloudQueue,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(size)
        )
        else -> {
            val shape = RoundedCornerShape(8.dp)
            val brand = app.brandColor?.let { Color(it) }
            Box(
                modifier
                    .size(size)
                    .clip(shape)
                    .background(brand ?: OtherAppPlainTile)
                    .let {
                        if (brand == null) {
                            it.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                        } else {
                            it
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // Sized in dp, not sp: the tile is a picture of a fixed size,
                // and a letter that grew with the font setting spilled out of
                // it at the largest sizes. The app's name beside it does grow.
                Text(
                    app.label.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    fontSize = with(LocalDensity.current) { 15.dp.toSp() },
                    fontWeight = FontWeight.Bold,
                    color = if (brand == null) OnOtherAppPlainTile else OnBrand
                )
            }
        }
    }
}
