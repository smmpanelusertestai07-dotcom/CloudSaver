package app.cloudsaver.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.cloudsaver.R
import app.cloudsaver.core.logic.ThemeMode
import app.cloudsaver.ui.theme.CloudSaverTheme
import app.cloudsaver.util.AppLog
import app.cloudsaver.util.OemPages

/**
 * The page shown instead of the app when the app cannot start.
 *
 * Deliberately built from almost nothing: no view model, no database, no
 * settings read, no navigation - every one of those is a thing that could be
 * the reason the launch keeps dying. Three buttons, each of which works with
 * the whole rest of the app broken: try again, share the log, open app info.
 * Nothing of the user's is touched by any of them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecoveryScreen(onTryAgain: () -> Unit) {
    val context = LocalContext.current
    // The system theme and no dynamic colour: both are read from settings
    // normally, and settings are one of the things that may be broken.
    CloudSaverTheme(mode = ThemeMode.SYSTEM, dynamicColor = false) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stringResource(R.string.recovery_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.recovery_body),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp, bottom = 18.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onTryAgain) {
                    Text(stringResource(R.string.recovery_try), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val shareTitle = stringResource(R.string.recovery_share)
                OutlinedButton(onClick = {
                    runCatching {
                        val file = AppLog.file(context)
                        if (file.exists()) {
                            val uri = FileProvider.getUriForFile(
                                context, "app.cloudsaver.fileprovider", file
                            )
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(share, shareTitle)
                            )
                        }
                    }
                }) {
                    Text(stringResource(R.string.recovery_share), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { OemPages.openAppInfo(context) }) {
                    Text(stringResource(R.string.recovery_app_info), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
