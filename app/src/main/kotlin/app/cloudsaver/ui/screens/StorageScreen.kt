package app.cloudsaver.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.R
import app.cloudsaver.core.logic.OutputPaths
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.Routes
import app.cloudsaver.ui.components.AnimatedNumber
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.MeterBar
import app.cloudsaver.ui.components.PathLine
import app.cloudsaver.ui.components.SectionHeader
import app.cloudsaver.ui.theme.TabularFigures
import app.cloudsaver.ui.components.WarningNote
import app.cloudsaver.util.Formats

/** Where the space goes: what fits in the cloud, what the app holds, what each volume has left. */
@Composable
fun StorageScreen(vm: AppViewModel, nav: NavHostController) {
    val stats by vm.storageStats.collectAsStateWithLifecycle()
    val reclaimable by vm.reclaimableBytes.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    var calcOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refreshStorage()
        vm.refreshVolumes()
        vm.refreshFindSpace()
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

        // The calculator lives here, folded away until asked for: it answers a
        // question about these very numbers, so making it a separate screen
        // meant leaving the answer to find the question.
        Spacer(Modifier.height(12.dp))
        AppCard(onClick = { calcOpen = !calcOpen }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.calc_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.calc_entry_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                val arrow by animateFloatAsState(
                    targetValue = if (calcOpen) 0f else -90f,
                    label = "calcArrow"
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.rotate(arrow)
                )
            }
            AnimatedVisibility(
                visible = calcOpen,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                CalculatorContent(vm, Modifier.padding(top = 12.dp))
            }
        }

        // Find space: three proof-based ways to free room, above Reclaim
        // because they are the cheaper answers to the same question.
        val findSpace by vm.findSpace.collectAsStateWithLifecycle()
        val keptBytes by vm.keptBytes.collectAsStateWithLifecycle()
        if (findSpace.duplicateBytes > 0 || findSpace.biggestBytes > 0) {
            SectionHeader(stringResource(R.string.find_space_title))
            if (findSpace.duplicateBytes > 0) {
                FindRow(
                    title = stringResource(R.string.find_duplicates),
                    hint = stringResource(R.string.find_duplicates_hint),
                    value = Formats.bytes(findSpace.duplicateBytes),
                    onClick = { nav.navigate(Routes.DUPLICATES) }
                )
            }
            if (findSpace.biggestBytes > 0) {
                FindRow(
                    title = stringResource(R.string.find_biggest),
                    hint = stringResource(R.string.find_biggest_hint),
                    value = Formats.bytes(findSpace.biggestBytes),
                    onClick = { nav.navigate(Routes.BIGGEST) }
                )
            }
            if (findSpace.reclaimableBytes > 0) {
                FindRow(
                    title = stringResource(R.string.find_suggestions),
                    hint = stringResource(R.string.find_suggestions_hint),
                    value = Formats.bytes(findSpace.reclaimableBytes),
                    onClick = { nav.navigate(Routes.FREE_UP) }
                )
            }
            Text(
                stringResource(R.string.find_space_honesty),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        SectionHeader(stringResource(R.string.storage_used_title))
        AppCard {
            // Two different things, each named and each with its path, rather
            // than two numbers side by side that nobody can tell apart.
            UsageRow(
                label = stringResource(R.string.storage_output),
                path = OutputPaths.joined(options.outputMode),
                bytes = stats.outputBytes,
                limitBytes = options.maxExtraBytes
            )
            Spacer(Modifier.height(18.dp))
            UsageRow(
                label = stringResource(R.string.storage_stage),
                path = stringResource(R.string.storage_stage_path),
                bytes = stats.stageBytes,
                limitBytes = options.maxExtraBytes
            )
            // Light copies are the user's files, so they are listed apart and
            // counted against nothing.
            if (keptBytes > 0) {
                Spacer(Modifier.height(18.dp))
                UsageRow(
                    label = stringResource(R.string.kept_title),
                    path = app.cloudsaver.core.logic.Defaults.KEPT_DIR,
                    bytes = keptBytes,
                    limitBytes = 0
                )
                TextButton(onClick = { nav.navigate(Routes.KEPT) }) {
                    Text(stringResource(R.string.kept_manage))
                }
            }
            Text(
                stringResource(R.string.storage_folder_lifecycle),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
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
                // The bar shows what is USED. A bar that fills as space frees
                // up is the opposite of what every other storage screen does.
                MeterBar(
                    fraction = fraction,
                    // Under 10 % free is where a backup run starts to struggle.
                    warn = fraction > 0.9f,
                    modifier = Modifier.padding(top = 8.dp)
                )
                // Free space is the number people came for, so it is the one
                // set large; used-of-total is the context underneath it.
                Text(
                    stringResource(R.string.volume_free_line, Formats.bytes(vol.freeBytes)),
                    style = MaterialTheme.typography.titleMedium.merge(TabularFigures),
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    stringResource(
                        R.string.volume_used_line,
                        Formats.bytes(used),
                        Formats.bytes(vol.totalBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall.merge(TabularFigures),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (!vol.isPrimary && active) {
                    WarningNote(stringResource(R.string.volume_sd_note))
                }
            }
        }

        // Only when there is something to reclaim: an empty card here is an
        // invitation to a screen that would say "nothing yet".
        AnimatedVisibility(
            visible = reclaimable > 0,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                SectionHeader(stringResource(R.string.storage_reclaim_title))
                AppCard(tonal = true) {
                    AnimatedNumber(
                        value = Formats.bytes(reclaimable),
                        style = MaterialTheme.typography.headlineSmall.merge(TabularFigures),
                        color = scheme.onPrimaryContainer
                    )
                    Text(
                        stringResource(R.string.storage_reclaim_caption),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        stringResource(R.string.storage_reclaim_consent),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Button(
                        onClick = { nav.navigate(Routes.FREE_UP) },
                        modifier = Modifier.padding(top = 12.dp)
                    ) { Text(stringResource(R.string.storage_freeup_open)) }
                }
            }
        }

        // Recently reclaimed sits with the thing it describes, and only once
        // there is something to describe.
        val history by vm.reclaimHistoryCount.collectAsStateWithLifecycle()
        if (history > 0) {
            SectionHeader(stringResource(R.string.reclaim_history))
            AppCard(onClick = { nav.navigate(Routes.RECLAIM_HISTORY) }) {
                Text(
                    stringResource(R.string.reclaim_history_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
            }
        }

        SectionHeader(stringResource(R.string.storage_temp_title))
        val hasTemp = stats.tempBytes > 0
        AppCard {
            Text(
                stringResource(R.string.storage_temp_text),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )
            Text(
                Formats.bytes(stats.tempBytes),
                style = MaterialTheme.typography.titleMedium,
                color = if (hasTemp) scheme.onSurface else scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            stats.lastTempFreed?.let {
                Text(
                    stringResource(R.string.storage_temp_cleared, Formats.bytes(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            OutlinedButton(
                onClick = { vm.cleanTemp() },
                enabled = hasTemp,
                modifier = Modifier.padding(top = 12.dp)
            ) { Text(stringResource(R.string.storage_clean_temp)) }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** One "Find space" row: a live size, and the list behind it. */
@Composable
private fun FindRow(title: String, hint: String, value: String, onClick: () -> Unit) {
    AppCard(modifier = Modifier.padding(vertical = 4.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                value,
                style = MaterialTheme.typography.titleSmall.merge(TabularFigures),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** One line of "what CloudSaver is holding", with the folder it is holding it in. */
@Composable
private fun UsageRow(label: String, path: String, bytes: Long, limitBytes: Long) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            Formats.bytes(bytes),
            style = MaterialTheme.typography.titleMedium.merge(TabularFigures),
            color = scheme.primary
        )
    }
    // Copyable, because this is the string that has to be found inside
    // another app's folder picker.
    PathLine(path, modifier = Modifier.padding(top = 2.dp))
    if (limitBytes > 0) {
        MeterBar(
            fraction = (bytes.toFloat() / limitBytes).coerceIn(0f, 1f),
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            stringResource(
                R.string.storage_share_of_limit,
                Formats.percentOf(bytes, limitBytes),
                Formats.bytes(limitBytes)
            ),
            style = MaterialTheme.typography.bodySmall.merge(TabularFigures),
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
