package app.cloudsaver.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.R
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.ReclaimViewModel
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.EmptyState
import app.cloudsaver.ui.components.KeyValueRow
import app.cloudsaver.ui.theme.TabularFigures
import app.cloudsaver.util.Formats

/** A page header with a back arrow, shared by the Find space screens. */
@Composable
private fun Page(nav: NavHostController, title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(top = 8.dp, start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

/**
 * Files that are byte-for-byte the same file.
 *
 * An identical extra can go while a copy stays on the phone, without any
 * upload evidence at all: the content is provably still there. That is the
 * one deletion in this app that needs no proof from the cloud.
 */
@Composable
fun DuplicatesScreen(rvm: ReclaimViewModel, nav: NavHostController) {
    val groups by rvm.duplicateGroups.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { rvm.loadDuplicates() }

    Page(nav, stringResource(R.string.find_duplicates)) {
        if (groups.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.dupes_empty_title),
                body = stringResource(R.string.dupes_empty_body)
            )
            return@Page
        }
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            item("intro") {
                Text(
                    stringResource(R.string.dupes_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            items(groups, key = { it.sha256 }) { group ->
                AppCard(modifier = Modifier.padding(vertical = 5.dp)) {
                    Text(
                        group.keeper.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis
                    )
                    Text(
                        pluralStringResource(
                            R.plurals.dupes_group_line,
                            group.extras.size,
                            group.extras.size,
                            Formats.bytes(group.reclaimableBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.dupes_keeper, group.keeper.album ?: "-"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    for (extra in group.extras) {
                        Text(
                            stringResource(
                                R.string.dupes_extra,
                                extra.album ?: "-",
                                Formats.bytes(extra.sizeBytes)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
            item("tail") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * The largest originals, and what optimising them would save.
 *
 * Read-only by design: this view exists to answer "what is actually using my
 * space", and answering that should not require a screen that can delete.
 */
@Composable
fun BiggestFilesScreen(vm: AppViewModel, rvm: ReclaimViewModel, nav: NavHostController) {
    val all by rvm.largest.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        rvm.loadLargest()
        vm.refreshProfile()
    }

    Page(nav, stringResource(R.string.find_biggest)) {
        if (all.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.biggest_empty_title),
                body = stringResource(R.string.biggest_empty_body)
            )
            return@Page
        }
        // Matched against name and album, because "which of my WhatsApp
        // videos is the huge one" is the actual question people arrive with.
        val rows = remember(all, query) {
            val q = query.trim()
            if (q.isEmpty()) {
                all
            } else {
                all.filter {
                    it.displayName.contains(q, ignoreCase = true) ||
                        (it.bucket?.contains(q, ignoreCase = true) == true)
                }
            }
        }
        val totalBytes = rows.sumOf { it.sizeBytes }
        val couldSave = rows.sumOf { row -> estimatedSaving(row, profile) }

        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            item("search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.biggest_search)) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.biggest_clear)
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
            item("summary") {
                AppCard(modifier = Modifier.padding(vertical = 8.dp), tonal = true) {
                    Text(
                        pluralStringResource(
                            R.plurals.biggest_summary,
                            rows.size,
                            rows.size,
                            Formats.bytes(totalBytes),
                            Formats.bytes(couldSave)
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (rows.isEmpty()) {
                item("nomatch") {
                    Text(
                        stringResource(R.string.biggest_no_match, query),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }
            items(rows, key = { it.id }) { row ->
                BiggestRow(
                    row = row,
                    saving = estimatedSaving(row, profile),
                    onOpen = { vm.openInViewer(row) },
                    onOptimise = { rvm.optimiseFirst(listOf(row.id)) },
                    onNever = { rvm.setNeverOptimise(row.id, true) }
                )
            }
            item("tail") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * What optimising this one file would save.
 *
 * A measured result is used where there is one; otherwise the device's own
 * ratio so far. Zero means "no honest estimate yet" rather than "no saving".
 */
private fun estimatedSaving(
    row: app.cloudsaver.data.db.ItemRow,
    profile: app.cloudsaver.core.logic.MediaProfile.Profile
): Long {
    row.outputBytes?.let { return (row.sizeBytes - it).coerceAtLeast(0L) }
    val ratio = if (row.isVideo) profile.videos.ratio else profile.photos.ratio
    return if (ratio > 0) (row.sizeBytes * (1 - ratio)).toLong() else 0L
}

/**
 * One large file: its picture, its numbers, and the two things that can be
 * done about it. Tapping the row opens the file itself - a list that names a
 * file you cannot look at is a list you cannot act on.
 */
@Composable
private fun BiggestRow(
    row: app.cloudsaver.data.db.ItemRow,
    saving: Long,
    onOpen: () -> Boolean,
    onOptimise: () -> Unit,
    onNever: () -> Unit
) {
    val couldNotOpen = stringResource(R.string.biggest_cannot_open)
    val context = androidx.compose.ui.platform.LocalContext.current
    AppCard(
        modifier = Modifier.padding(vertical = 4.dp),
        onClick = {
            // The original may be gone, or the phone may have no viewer for
            // this type. Say so rather than doing nothing.
            if (!onOpen()) {
                val toast = android.widget.Toast.makeText(
                    context, couldNotOpen, android.widget.Toast.LENGTH_SHORT
                )
                toast.show()
            }
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(row)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    row.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis
                )
                Text(
                    detailLine(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    row.bucket ?: stringResource(R.string.biggest_no_album),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Formats.bytes(row.sizeBytes),
                    style = MaterialTheme.typography.titleSmall.merge(TabularFigures)
                )
                if (saving > 0) {
                    Text(
                        stringResource(R.string.biggest_saving, Formats.bytes(saving)),
                        style = MaterialTheme.typography.labelSmall.merge(TabularFigures),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        Row(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (row.outputBytes == null) {
                TextButton(onClick = onOptimise) {
                    Text(stringResource(R.string.biggest_optimise_first))
                }
            }
            TextButton(onClick = onNever) {
                Text(stringResource(R.string.never_optimise))
            }
        }
    }
}

/** Kind, when it was taken, and how long it runs if it runs at all. */
@Composable
private fun detailLine(row: app.cloudsaver.data.db.ItemRow): String {
    val kind = stringResource(
        if (row.isVideo) R.string.kind_video else R.string.kind_photo
    )
    val taken = Formats.date(if (row.captureAt > 0) row.captureAt else row.dateModified)
    return if (row.isVideo && row.durationMs > 0) {
        stringResource(
            R.string.biggest_detail_video, kind, Formats.duration(row.durationMs), taken
        )
    } else {
        stringResource(R.string.biggest_detail, kind, taken)
    }
}

/**
 * Every reclaim batch of the last 30 days, which is exactly how long the
 * system trash keeps the files it describes.
 */
@Composable
fun ReclaimHistoryScreen(rvm: ReclaimViewModel, nav: NavHostController) {
    val batches by rvm.history.collectAsStateWithLifecycle(emptyList())
    val items by rvm.historyItems.collectAsStateWithLifecycle()
    val pending by rvm.pendingIntent.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r -> rvm.onRestoreResult(r.resultCode == android.app.Activity.RESULT_OK) }
    LaunchedEffect(pending) {
        pending?.let { launcher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    Page(nav, stringResource(R.string.reclaim_history)) {
        if (batches.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.history_empty_title),
                body = stringResource(R.string.history_empty_body)
            )
            return@Page
        }
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            items(batches, key = { it.id }) { batch ->
                AppCard(
                    modifier = Modifier.padding(vertical = 5.dp),
                    onClick = { rvm.loadBatch(batch) }
                ) {
                    Text(
                        Formats.dateTime(batch.atMs),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        pluralStringResource(
                            R.plurals.history_line,
                            batch.itemCount,
                            batch.itemCount,
                            Formats.bytes(batch.freedBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (batch.trashed) {
                            stringResource(
                                R.string.history_trashed,
                                Formats.date(batch.atMs + 30L * 86_400_000L)
                            )
                        } else {
                            stringResource(R.string.history_permanent)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (batch.trashed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    val shown = items.filter { it.batchId == batch.id }
                    for (item in shown) {
                        KeyValueRow(
                            item.displayName,
                            Formats.bytes(item.originalBytes)
                        )
                    }
                    if (batch.trashed && shown.isNotEmpty()) {
                        val restorable = shown.filter { it.restoredAt == null }
                        if (restorable.isEmpty()) {
                            // Nothing left to offer; everything here came back.
                        } else if (android.os.Build.VERSION.SDK_INT >= 30) {
                            OutlinedButton(
                                onClick = { rvm.restore(restorable) },
                                modifier = Modifier.padding(top = 8.dp)
                            ) { Text(stringResource(R.string.history_restore)) }
                        } else {
                            // Android cannot report trash state for files the
                            // app no longer owns, so it says where to look
                            // rather than promising a button that may fail.
                            Text(
                                stringResource(R.string.history_unknown),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
            item("tail") { Spacer(Modifier.height(24.dp)) }
        }
    }
}
