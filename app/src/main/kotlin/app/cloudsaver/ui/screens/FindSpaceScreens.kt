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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    val rows by rvm.largest.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        rvm.loadLargest()
        vm.refreshProfile()
    }

    Page(nav, stringResource(R.string.find_biggest)) {
        if (rows.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.biggest_empty_title),
                body = stringResource(R.string.biggest_empty_body)
            )
            return@Page
        }
        val totalBytes = rows.sumOf { it.sizeBytes }
        val couldSave = rows.sumOf { row ->
            val ratio = if (row.isVideo) profile.videos.ratio else profile.photos.ratio
            if (row.outputBytes != null) {
                (row.sizeBytes - row.outputBytes!!).coerceAtLeast(0L)
            } else if (ratio > 0) {
                (row.sizeBytes * (1 - ratio)).toLong()
            } else {
                0L
            }
        }
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
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
            items(rows, key = { it.id }) { row ->
                AppCard(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        row.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis
                    )
                    Text(
                        stringResource(
                            R.string.biggest_row,
                            Formats.bytes(row.sizeBytes),
                            row.bucket ?: "-"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (row.outputBytes == null) {
                            TextButton(onClick = { rvm.optimiseFirst(listOf(row.id)) }) {
                                Text(stringResource(R.string.biggest_optimise_first))
                            }
                        }
                        TextButton(onClick = { rvm.setNeverOptimise(row.id, true) }) {
                            Text(stringResource(R.string.never_optimise))
                        }
                    }
                }
            }
            item("tail") { Spacer(Modifier.height(24.dp)) }
        }
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
