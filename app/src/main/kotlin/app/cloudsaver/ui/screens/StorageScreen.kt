package app.cloudsaver.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.R
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.Routes
import app.cloudsaver.ui.components.AnimatedNumber
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.MeterBar
import app.cloudsaver.ui.components.MetricTile
import app.cloudsaver.ui.components.SectionHeader
import app.cloudsaver.ui.components.WarningNote
import app.cloudsaver.util.Formats

/** Where the space goes: what the app holds, what each volume has left. */
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

    val scheme = MaterialTheme.colorScheme

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

        SectionHeader(stringResource(R.string.storage_used_title))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                value = Formats.bytes(stats.stageBytes),
                label = stringResource(R.string.storage_stage),
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                value = Formats.bytes(stats.outputBytes),
                label = stringResource(R.string.storage_output),
                modifier = Modifier.weight(1f)
            )
        }

        SectionHeader(stringResource(R.string.storage_volumes_title))
        AppCard {
            volumes.forEachIndexed { index, vol ->
                val used = (vol.totalBytes - vol.freeBytes).coerceAtLeast(0)
                val fraction = if (vol.totalBytes > 0) {
                    used.toFloat() / vol.totalBytes
                } else {
                    0f
                }
                val label = if (vol.isPrimary) {
                    stringResource(R.string.volume_internal)
                } else {
                    stringResource(R.string.volume_sd)
                }
                val active = (options.storageVolume.isEmpty() && vol.isPrimary) ||
                    options.storageVolume == vol.mediaVolumeName
                if (index > 0) Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (active) {
                        Text(
                            stringResource(R.string.volume_active_mark),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary
                        )
                    }
                }
                MeterBar(
                    fraction = fraction,
                    // Under 10 % free is where a backup run starts to struggle.
                    warn = fraction > 0.9f,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    stringResource(
                        R.string.volume_free_line,
                        Formats.bytes(vol.freeBytes),
                        Formats.bytes(vol.totalBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (!vol.isPrimary && active) {
                    WarningNote(stringResource(R.string.volume_sd_note))
                }
            }
        }

        SectionHeader(stringResource(R.string.storage_reclaim_title))
        AppCard(tonal = reclaimable > 0) {
            AnimatedNumber(
                value = Formats.bytes(reclaimable),
                style = MaterialTheme.typography.headlineSmall,
                color = if (reclaimable > 0) scheme.onPrimaryContainer else scheme.onSurface
            )
            Text(
                stringResource(R.string.storage_reclaim_caption),
                style = MaterialTheme.typography.bodyMedium,
                color = if (reclaimable > 0) {
                    scheme.onPrimaryContainer.copy(alpha = 0.85f)
                } else {
                    scheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 2.dp)
            )
            // The button only makes sense once something is actually reclaimable.
            AnimatedVisibility(
                visible = options.showFreeUp && reclaimable > 0,
                enter = fadeIn() + expandVertically()
            ) {
                Button(
                    onClick = { nav.navigate(Routes.FREE_UP) },
                    modifier = Modifier.padding(top = 12.dp)
                ) { Text(stringResource(R.string.storage_freeup_open)) }
            }
            if (!options.showFreeUp) {
                Text(
                    stringResource(R.string.storage_freeup_hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        SectionHeader(stringResource(R.string.storage_temp_title))
        AppCard {
            Text(
                stringResource(R.string.storage_temp_text),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { vm.cleanTemp() },
                modifier = Modifier.padding(top = 12.dp)
            ) { Text(stringResource(R.string.storage_clean_temp)) }
        }
        Spacer(Modifier.height(28.dp))
    }
}
