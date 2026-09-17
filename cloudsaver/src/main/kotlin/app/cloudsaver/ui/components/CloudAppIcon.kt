package app.cloudsaver.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.cloudsaver.data.CloudApp
import app.cloudsaver.data.CloudApps

/**
 * The face a cloud app wears in every picker.
 *
 * Installed, it is the app's own icon read from the package manager - always
 * current, never a third-party logo shipped inside this APK. Not installed,
 * it used to be the same grey cloud for every row, so a list of five choices
 * had four identical blanks and one picture, and the blanks read as broken
 * images rather than as apps not on this phone. Now an absent app shows its
 * initial in a circle: every row has a face, and "installed" is still legible
 * before the words are read. "Other app" is not an app and keeps the cloud.
 */
@Composable
fun CloudAppIcon(app: CloudApp, installed: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon = remember(app.id, installed) {
        if (installed) CloudApps.iconFor(context, app) else null
    }
    val size = 28.dp
    when {
        icon != null -> Image(
            bitmap = remember(icon) { icon.toBitmap(96, 96).asImageBitmap() },
            contentDescription = null,
            modifier = modifier.size(size)
        )
        app.packages.isEmpty() -> Icon(
            Icons.Outlined.CloudQueue,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(size)
        )
        else -> Box(
            modifier
                .size(size)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                app.label.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
