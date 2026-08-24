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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.SdCard

/**
 * Where the space goes.
 *
 * Four groups and nothing else: the phone's own volumes, what CloudSaver is
 * holding, the ways to find room, and the leftovers worth clearing. Anything
 * with a zero value is not shown - a row reading "0 MB" is a question with no
 * answer, and this screen used to be full of them.
 */
@Composable
fun StorageScreen(vm: AppViewModel, nav: NavHostController) {
    val stats by vm.storageStats.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val findSpace by vm.findSpace.collectAsStateWithLifecycle()
    val keptBytes by vm.keptBytes.collectAsStateWithLifecycle()

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
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // 1. Your phone.
        SectionHeader(stringResource(R.string.storage_group_phone))
        AppCard {
            volumes.forEachIndexed { index, vol ->
                if (index > 0) Spacer(Modifier.height(20.dp))
                val used = (vol.totalBytes - vol.freeBytes).coerceAtLeast(0)
                val fraction = if (vol.totalBytes > 0) used.toFloat() / vol.totalBytes else 0f
                val active = (options.storageVolume.isEmpty() && vol.isPrimary) ||
                    options.storageVolume == vol.mediaVolumeName
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (vol.isPrimary) Icons.Outlined.PhoneAndroid else Icons.Outlined.SdCard,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(
                            if (vol.isPrimary) R.string.volume_internal else R.string.volume_sd
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (active) {
                        Text(
                            stringResource(R.string.volume_active_mark),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary
                        )
                    }
                }
                // The bar fills with what is USED. A bar that fills as space
                // frees up is the opposite of every other storage screen.
                MeterBar(
                    fraction = fraction,
                    warn = fraction > 0.9f,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    stringResource(
                        R.string.volume_used_line,
                        Formats.bytes(used),
                        Formats.bytes(vol.totalBytes)
                    ),
                    style = MaterialTheme.typography.bodyMedium.merge(TabularFigures),
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    stringResource(R.string.volume_free_line, Formats.bytes(vol.freeBytes)),
                    style = MaterialTheme.typography.bodySmall.merge(TabularFigures),
                    color = scheme.onSurfaceVariant
                )
                if (!vol.isPrimary && active) {
                    WarningNote(stringResource(R.string.volume_sd_note))
                }
            }
        }

        // 2. CloudSaver's own space: two rows, and only when they hold
        // something.
        if (stats.outputBytes > 0 || stats.stageBytes > 0 || keptBytes > 0) {
            SectionHeader(stringResource(R.string.storage_group_own))
            AppCard {
                if (stats.outputBytes > 0) {
                    UsageRow(
                        icon = Icons.Outlined.CloudUpload,
                        label = stringResource(R.string.storage_output),
                        path = OutputPaths.joined(options.outputMode),
                        bytes = stats.outputBytes
                    )
                }
                if (stats.stageBytes > 0) {
                    if (stats.outputBytes > 0) Spacer(Modifier.height(18.dp))
                    UsageRow(
                        icon = Icons.Outlined.Cached,
                        label = stringResource(R.string.storage_stage),
                        path = null,
                        note = stringResource(R.string.storage_stage_path),
                        bytes = stats.stageBytes
                    )
                }
                // Light copies are the user's own files, counted against
                // nothing, so they are listed apart.
                if (keptBytes > 0) {
                    Spacer(Modifier.height(18.dp))
                    UsageRow(
                        icon = Icons.Outlined.PhotoLibrary,
                        label = stringResource(R.string.kept_title),
                        path = app.cloudsaver.core.logic.Defaults.KEPT_DIR,
                        bytes = keptBytes,
                        onManage = { nav.navigate(Routes.KEPT) }
                    )
                }
                Text(
                    stringResource(
                        R.string.storage_limit_line,
                        Formats.mbLabel(options.maxExtraMb),
                        Formats.bytes(stats.outputBytes + stats.stageBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall.merge(TabularFigures),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp)
                )
                Text(
                    stringResource(R.string.storage_folder_empties),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // 3. Find space.
        if (findSpace.duplicateBytes > 0 || findSpace.biggestBytes > 0 ||
            findSpace.reclaimableBytes > 0
        ) {
            SectionHeader(stringResource(R.string.find_space_title))
            if (findSpace.duplicateBytes > 0) {
                FindRow(
                    icon = Icons.Outlined.ContentCopy,
                    title = stringResource(R.string.find_duplicates),
                    hint = stringResource(R.string.find_duplicates_hint),
                    value = Formats.bytes(findSpace.duplicateBytes),
                    onClick = { nav.navigate(Routes.DUPLICATES) }
                )
            }
            if (findSpace.biggestBytes > 0) {
                FindRow(
                    icon = Icons.Outlined.PhotoSizeSelectLarge,
                    title = stringResource(R.string.find_biggest),
                    hint = stringResource(R.string.find_biggest_hint),
                    value = Formats.bytes(findSpace.biggestBytes),
                    onClick = { nav.navigate(Routes.BIGGEST) }
                )
            }
            if (findSpace.reclaimableBytes > 0) {
                FindRow(
                    icon = Icons.Outlined.DeleteSweep,
                    title = stringResource(R.string.find_suggestions),
                    hint = stringResource(R.string.find_suggestions_hint),
                    value = Formats.bytes(findSpace.reclaimableBytes),
                    onClick = { nav.navigate(Routes.FREE_UP) }
                )
            }
            FindRow(
                icon = Icons.Outlined.Calculate,
                title = stringResource(R.string.calc_title),
                hint = stringResource(R.string.calc_entry_hint),
                value = null,
                onClick = { nav.navigate(Routes.CALCULATOR) }
            )
        }

        // Recently reclaimed sits with the thing it describes.
        val historyCount by vm.reclaimHistoryCount.collectAsStateWithLifecycle()
        if (historyCount > 0) {
            FindRow(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.reclaim_history),
                hint = stringResource(R.string.reclaim_history_hint),
                value = null,
                onClick = { nav.navigate(Routes.RECLAIM_HISTORY) }
            )
        }

        // 4. Clean up.
        if (stats.tempBytes > 0 || stats.lastTempFreed != null) {
            SectionHeader(stringResource(R.string.storage_group_cleanup))
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.CleaningServices,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.storage_temp_title),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        Formats.bytes(stats.tempBytes),
                        style = MaterialTheme.typography.titleMedium.merge(TabularFigures),
                        color = scheme.primary
                    )
                }
                Text(
                    stringResource(R.string.storage_temp_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
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
                    enabled = stats.tempBytes > 0,
                    modifier = Modifier.padding(top = 12.dp)
                ) { Text(stringResource(R.string.storage_clean_temp)) }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/** One "Find space" row: an icon, what it does in a few words, and a size. */
@Composable
private fun FindRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    hint: String,
    value: String?,
    onClick: () -> Unit
) {
    AppCard(modifier = Modifier.padding(vertical = 4.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.titleMedium.merge(TabularFigures),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One line of "what CloudSaver is holding".
 *
 * A copyable path where the file lives somewhere the user can point a cloud
 * app at; a plain note where it does not, because "App storage" is not a path
 * anyone can type.
 */
@Composable
private fun UsageRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    path: String?,
    bytes: Long,
    note: String? = null,
    onManage: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
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
    path?.let { PathLine(it, modifier = Modifier.padding(top = 2.dp, start = 32.dp)) }
    note?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, start = 32.dp)
        )
    }
    onManage?.let {
        TextButton(onClick = it, modifier = Modifier.padding(start = 24.dp)) {
            Text(stringResource(R.string.kept_manage))
        }
    }
}
