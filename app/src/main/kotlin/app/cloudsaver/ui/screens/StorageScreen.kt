package app.cloudsaver.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.R
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.Routes
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.KeyValueRow
import app.cloudsaver.util.Formats

@Composable
fun StorageScreen(vm: AppViewModel, nav: NavHostController) {
    val stats by vm.storageStats.collectAsStateWithLifecycle()
    val reclaimable by vm.reclaimableBytes.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.refreshStorage()
        vm.refreshVolumes()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.nav_storage),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        AppCard {
            Text(
                stringResource(R.string.storage_used_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            KeyValueRow(stringResource(R.string.storage_stage), Formats.bytes(stats.stageBytes))
            KeyValueRow(stringResource(R.string.storage_output), Formats.bytes(stats.outputBytes))
        }
        Spacer(Modifier.height(10.dp))
        AppCard {
            Text(
                stringResource(R.string.storage_volumes_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            for (vol in volumes) {
                val used = (vol.totalBytes - vol.freeBytes).coerceAtLeast(0)
                val fraction = if (vol.totalBytes > 0) used.toFloat() / vol.totalBytes else 0f
                val label = if (vol.isPrimary) {
                    stringResource(R.string.volume_internal)
                } else {
                    stringResource(R.string.volume_sd)
                }
                val active = (options.storageVolume.isEmpty() && vol.isPrimary) ||
                    options.storageVolume == vol.mediaVolumeName
                Text(
                    label + (if (active) " " + stringResource(R.string.volume_active_mark) else "") +
                        ": " + stringResource(
                        R.string.volume_free_line,
                        Formats.bytes(vol.freeBytes),
                        Formats.bytes(vol.totalBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        AppCard {
            Text(
                stringResource(R.string.storage_reclaim_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.storage_reclaim_line, Formats.bytes(reclaimable)),
                style = MaterialTheme.typography.bodyMedium
            )
            if (options.showFreeUp) {
                OutlinedButton(
                    onClick = { nav.navigate(Routes.FREE_UP) },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text(stringResource(R.string.storage_freeup_open)) }
            } else {
                Text(
                    stringResource(R.string.storage_freeup_hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        AppCard {
            Text(
                stringResource(R.string.storage_temp_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.storage_temp_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { vm.cleanTemp() },
                modifier = Modifier.padding(top = 8.dp)
            ) { Text(stringResource(R.string.storage_clean_temp)) }
        }
        Spacer(Modifier.height(24.dp))
    }
}
