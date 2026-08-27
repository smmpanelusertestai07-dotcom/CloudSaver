package app.cloudsaver.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.R
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.Routes
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.goTo
import app.cloudsaver.ui.theme.Dimens
import app.cloudsaver.ui.theme.TabularFigures
import app.cloudsaver.util.Formats

/**
 * Every way to make room, on one screen (Z1).
 *
 * Four sections, four screens behind them, and one honest header: which
 * volume this cleans, how much is free now, and roughly how much would be
 * free afterwards. A section whose size is zero is absent rather than shown
 * with a dash - a row reading "0 MB" is a question with no answer.
 *
 * The hub adds nothing of its own on purpose. The lists behind it carry the
 * warnings, the proof checks and Android's own confirmation; putting a
 * summary here and the safety there means the safety cannot be bypassed by
 * entering through a different door.
 */
@Composable
fun FreeSpaceHubScreen(vm: AppViewModel, nav: NavHostController) {
    val findSpace by vm.findSpace.collectAsStateWithLifecycle()
    val stats by vm.storageStats.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.refreshFindSpace()
        vm.refreshStorage()
        vm.refreshVolumes()
    }

    val total = findSpace.reclaimableBytes + findSpace.duplicateBytes + stats.tempBytes
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.padding(top = 8.dp, start = 4.dp, end = Dimens.Screen),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
            // A share of the row rather than whatever the title wants: beside
            // the arrow at the largest font it is wider than a small phone,
            // and with nothing to hold it the end of the title was drawn past
            // the edge of the screen. A weight lets it wrap instead.
            Text(
                stringResource(R.string.hub_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        Column(Modifier.padding(horizontal = Dimens.Screen)) {
            // Z1.5: which volume, free now, and free after - so "free up
            // space" is a promise with a number, not a mood.
            val volume = volumes.firstOrNull { vol ->
                if (options.storageVolume.isEmpty()) vol.isPrimary
                else vol.mediaVolumeName == options.storageVolume
            } ?: volumes.firstOrNull { it.isPrimary }
            if (volume != null) {
                val where = stringResource(
                    if (volume.isPrimary) R.string.volume_internal else R.string.volume_sd
                )
                val freeNow = Formats.bytes(volume.freeBytes)
                val freeAfter = Formats.bytes(volume.freeBytes + total)
                Text(
                    // "5.85 GB free now, about 5.85 GB after" is what this
                    // printed whenever the saving was too small to move the
                    // figure - a sentence that names the same number twice and
                    // promises nothing. When the second half would not differ
                    // from the first, it is left off rather than printed.
                    if (freeAfter == freeNow) {
                        stringResource(R.string.hub_volume_line_only, where, freeNow)
                    } else {
                        stringResource(R.string.hub_volume_line, where, freeNow, freeAfter)
                    },
                    style = MaterialTheme.typography.bodyMedium.merge(TabularFigures),
                    color = scheme.onSurfaceVariant
                )
            }
            Text(
                if (total > 0) {
                    stringResource(R.string.hub_could_free, Formats.bytes(total))
                } else {
                    stringResource(R.string.hub_nothing)
                },
                style = MaterialTheme.typography.titleMedium.merge(TabularFigures),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
            )

            if (findSpace.reclaimableBytes > 0) {
                HubCard(
                    icon = Icons.Outlined.CloudDone,
                    title = stringResource(R.string.hub_backed_up),
                    hint = stringResource(R.string.hub_backed_up_hint),
                    value = Formats.bytes(findSpace.reclaimableBytes),
                    onClick = { nav.goTo(Routes.FREE_UP) }
                )
            }
            if (findSpace.duplicateBytes > 0) {
                HubCard(
                    icon = Icons.Outlined.ContentCopy,
                    title = stringResource(R.string.find_duplicates),
                    hint = stringResource(R.string.hub_duplicates_hint),
                    value = Formats.bytes(findSpace.duplicateBytes),
                    onClick = { nav.goTo(Routes.DUPLICATES) }
                )
            }
            if (findSpace.biggestBytes > 0) {
                HubCard(
                    icon = Icons.Outlined.PhotoSizeSelectLarge,
                    title = stringResource(R.string.find_biggest),
                    hint = stringResource(R.string.hub_biggest_hint),
                    value = Formats.bytes(findSpace.biggestBytes),
                    onClick = { nav.goTo(Routes.BIGGEST) }
                )
            }
            // Leftover work files are the app's own half-finished output, so
            // clearing them needs no proof and no system dialog - the one
            // section that acts in place rather than opening a list.
            if (stats.tempBytes > 0) {
                AppCard(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.CleaningServices,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.hub_leftovers),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                stringResource(R.string.hub_leftovers_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                        // The size takes a share of the row, not whatever it
                        // wants. "1,023.45 MB" at the largest font is most of
                        // a small phone's width, and with no bound on it the
                        // name and the line under it were squeezed to nothing.
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
                    OutlinedButton(
                        onClick = { vm.cleanTemp() },
                        modifier = Modifier.padding(top = 10.dp)
                    ) {
                        Text(
                            stringResource(R.string.hub_clean_now),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** One section: icon, name, one line, its size, a chevron. */
@Composable
private fun HubCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    hint: String,
    value: String,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    AppCard(modifier = Modifier.padding(vertical = 4.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            // Bounded for the same reason as the name beside it: a size and a
            // chevron with no limit between them take the whole row at a large
            // font, and the section's name is what is left with nothing.
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.merge(TabularFigures),
                color = scheme.primary,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(0.45f, fill = false)
                    .padding(start = 8.dp)
            )
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
