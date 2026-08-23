package app.cloudsaver.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.cloudsaver.R
import app.cloudsaver.core.logic.Evidence
import app.cloudsaver.core.logic.ItemState
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.BadgeTone
import app.cloudsaver.ui.components.EmptyState
import app.cloudsaver.ui.components.KeyValueRow
import app.cloudsaver.ui.components.StateBadge
import app.cloudsaver.util.Formats

@Composable
fun FilesScreen(vm: AppViewModel) {
    val items by vm.items.collectAsStateWithLifecycle()
    val query by vm.search.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<ItemRow?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.nav_files),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        OutlinedTextField(
            value = query,
            onValueChange = { vm.search.value = it },
            label = { Text(stringResource(R.string.files_search)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )
        if (items.isEmpty()) {
            EmptyState(
                title = if (query.isEmpty()) {
                    stringResource(R.string.files_empty_title)
                } else {
                    stringResource(R.string.files_no_match_title)
                },
                body = if (query.isEmpty()) {
                    stringResource(R.string.files_empty)
                } else {
                    stringResource(R.string.files_no_match_body)
                }
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { row ->
                    AppCard(
                        modifier = Modifier
                            .padding(vertical = 5.dp)
                            .animateItem(),
                        onClick = { detail = row }
                    ) {
                        Text(
                            row.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Row(
                            Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StateBadge(stateLabel(row), badgeTone(row))
                            Text(
                                // Once a copy exists, the saving is the point.
                                row.outputBytes?.let { copy ->
                                    stringResource(
                                        R.string.files_size_pair,
                                        Formats.bytes(row.sizeBytes),
                                        Formats.bytes(copy)
                                    )
                                } ?: Formats.bytes(row.sizeBytes),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    openError?.let { message ->
        AlertDialog(
            onDismissRequest = { openError = null },
            confirmButton = {
                TextButton(onClick = { openError = null }) { Text(stringResource(R.string.ok)) }
            },
            text = { Text(message) }
        )
    }

    detail?.let { row ->
        AlertDialog(
            onDismissRequest = { detail = null },
            confirmButton = {
                TextButton(onClick = { detail = null }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                // Opening the copy, not just describing it, is what tells the
                // user the optimised file is really fine to keep.
                val cannotOpen = stringResource(R.string.detail_open_failed)
                TextButton(onClick = {
                    if (!vm.openInViewer(row)) {
                        openError = cannotOpen
                    } else {
                        detail = null
                    }
                }) { Text(stringResource(R.string.detail_open)) }
            },
            title = { Text(row.displayName, maxLines = 2) },
            text = {
                Column {
                    KeyValueRow(stringResource(R.string.detail_state), stateLabel(row))
                    KeyValueRow(stringResource(R.string.detail_evidence), evidenceLabel(row))
                    KeyValueRow(stringResource(R.string.detail_original), Formats.bytes(row.sizeBytes))
                    row.outputBytes?.let {
                        KeyValueRow(stringResource(R.string.detail_copy), Formats.bytes(it))
                    }
                    KeyValueRow(stringResource(R.string.detail_captured), Formats.dateTime(row.captureAt))
                    row.releasedAt?.let {
                        KeyValueRow(stringResource(R.string.detail_released), Formats.dateTime(it))
                    }
                    row.outputName?.let {
                        KeyValueRow(stringResource(R.string.detail_copy_name), it)
                    }
                    row.skipReason?.let {
                        KeyValueRow(stringResource(R.string.detail_reason), it)
                    }
                    // Items copied byte-for-byte explain themselves here.
                    if (row.outputBytes != null && row.lastError != null) {
                        KeyValueRow(
                            stringResource(R.string.detail_as_is),
                            asIsReasonLabel(row.lastError!!)
                        )
                    }
                    if (row.originalMissing) {
                        Text(
                            stringResource(R.string.detail_original_missing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        )
    }
}

private fun badgeTone(row: ItemRow): BadgeTone {
    val state = runCatching { ItemState.valueOf(row.state) }.getOrDefault(ItemState.UNKNOWN)
    return when (state) {
        ItemState.NEW -> BadgeTone.NEUTRAL
        ItemState.STAGED, ItemState.RELEASED -> BadgeTone.PROGRESS
        ItemState.GONE, ItemState.DONE, ItemState.FREED -> BadgeTone.SUCCESS
        ItemState.SKIP, ItemState.UNKNOWN -> BadgeTone.MUTED
    }
}

@Composable
fun stateLabel(row: ItemRow): String {
    val state = runCatching { ItemState.valueOf(row.state) }.getOrDefault(ItemState.UNKNOWN)
    return when (state) {
        ItemState.NEW -> stringResource(R.string.state_new)
        ItemState.STAGED -> stringResource(R.string.state_staged)
        ItemState.RELEASED -> stringResource(R.string.state_released)
        ItemState.GONE, ItemState.DONE -> stringResource(R.string.state_done)
        ItemState.SKIP -> stringResource(R.string.state_skip)
        ItemState.FREED -> stringResource(R.string.state_freed)
        ItemState.UNKNOWN -> stringResource(R.string.state_unknown)
    }
}

/** Plain-English explanation for a file that was copied instead of compressed. */
@Composable
fun asIsReasonLabel(reason: String): String = when (reason) {
    "motion_photo" -> stringResource(R.string.asis_motion_photo)
    "depth_photo" -> stringResource(R.string.asis_depth_photo)
    "multi_picture" -> stringResource(R.string.asis_multi_picture)
    "format_as_is" -> stringResource(R.string.asis_format)
    "already_efficient" -> stringResource(R.string.asis_already_small)
    "not_smaller" -> stringResource(R.string.asis_not_smaller)
    "hdr_not_supported" -> stringResource(R.string.asis_hdr)
    else -> stringResource(R.string.asis_other)
}

@Composable
fun evidenceLabel(row: ItemRow): String {
    val ev = runCatching { Evidence.valueOf(row.evidence) }.getOrDefault(Evidence.NONE)
    return when (ev) {
        Evidence.CONFIRMED -> stringResource(R.string.evidence_confirmed)
        Evidence.VERIFIED -> stringResource(R.string.evidence_verified)
        Evidence.AGED -> stringResource(R.string.evidence_aged)
        Evidence.NONE -> stringResource(R.string.evidence_none)
    }
}
