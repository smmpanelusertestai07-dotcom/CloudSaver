package app.cloudsaver.ui.screens

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.R
import app.cloudsaver.core.logic.OutputPaths
import app.cloudsaver.ui.goTo
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.Routes
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.MeterBar
import app.cloudsaver.ui.components.PathLine
import app.cloudsaver.ui.components.SectionHeader
import app.cloudsaver.ui.theme.Dimens
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
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoLibrary
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
    val mediaAccess by vm.mediaAccess.collectAsStateWithLifecycle()

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
            .padding(horizontal = Dimens.Screen)
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.nav_storage),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // 1. Your phone.
        // BB1.3: totals below come from the last full scan; nothing new
        // arrives until access is full again, and the screen says so.
        if (mediaAccess == app.cloudsaver.util.Permissions.MediaAccess.PARTIAL) {
            WarningNote(stringResource(R.string.partial_waiting))
        }
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
                        // A gap of its own and a limit on its height, so a
                        // translation longer than "(in use)" wraps beside the
                        // volume's name instead of running into it. No weight:
                        // the mark belongs against the end of the row, and a
                        // share of the width would leave it floating in the
                        // middle whenever it is as short as it is in English.
                        Text(
                            stringResource(R.string.volume_active_mark),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary,
                            textAlign = TextAlign.End,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp)
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
                        onManage = { nav.goTo(Routes.KEPT) }
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
                Text(
                    stringResource(R.string.folder_gallery_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // 3. One entry for everything that can make room (Z1.1). The four
        // sections and their sizes live on the hub; listing them here as well
        // was the same information twice, one screen apart.
        SectionHeader(stringResource(R.string.find_space_title))
        val hubBytes = findSpace.reclaimableBytes + findSpace.duplicateBytes + stats.tempBytes
        FindRow(
            icon = Icons.Outlined.DeleteSweep,
            title = stringResource(R.string.hub_title),
            hint = stringResource(R.string.hub_entry_hint),
            value = if (hubBytes > 0) Formats.bytes(hubBytes) else null,
            onClick = { nav.goTo(Routes.FREE_SPACE_HUB) }
        )
        FindRow(
            icon = Icons.Outlined.Calculate,
            title = stringResource(R.string.calc_title),
            hint = stringResource(R.string.calc_entry_hint),
            value = null,
            onClick = { nav.goTo(Routes.CALCULATOR) }
        )

        // Recently reclaimed sits with the thing it describes.
        val historyCount by vm.reclaimHistoryCount.collectAsStateWithLifecycle()
        if (historyCount > 0) {
            FindRow(
                icon = Icons.Outlined.History,
                title = stringResource(R.string.reclaim_history),
                hint = stringResource(R.string.reclaim_history_hint),
                value = null,
                onClick = { nav.goTo(Routes.RECLAIM_HISTORY) }
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
                    // Only when there is something to name. This card also
                    // appears after a clear, to say what the clear freed, and
                    // it printed "0 B" beside the title while doing so - which
                    // is the "a row reading 0 MB is a question with no answer"
                    // this screen is built to avoid, on the screen itself.
                    //
                    // Bounded for the same reason as every other size in the
                    // app: "1,023.45 MB" at the largest font is most of a
                    // small phone's width, and unbounded it left the title
                    // beside it with nothing.
                    if (stats.tempBytes > 0) {
                        Text(
                            Formats.bytes(stats.tempBytes),
                            style = MaterialTheme.typography.titleMedium.merge(TabularFigures),
                            color = scheme.primary,
                            textAlign = TextAlign.End,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(0.45f, fill = false)
                                .padding(start = 8.dp)
                        )
                    }
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
                ) {
                    Text(
                        stringResource(R.string.storage_clean_temp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
                // The size takes a share of the row, not whatever it wants.
                // A size and a chevron with no bound between them take the
                // whole width at the largest font, and the row's name and the
                // line under it were what was left with nothing.
                Text(
                    value,
                    style = MaterialTheme.typography.titleMedium.merge(TabularFigures),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(0.45f, fill = false)
                        .padding(start = 8.dp)
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
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
        // Bounded like every other size in the app, so the name of the thing
        // being measured keeps at least half the row at any font size.
        Text(
            Formats.bytes(bytes),
            style = MaterialTheme.typography.titleMedium.merge(TabularFigures),
            color = scheme.primary,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(0.45f, fill = false)
                .padding(start = 8.dp)
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
            Text(
                stringResource(R.string.kept_manage),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
