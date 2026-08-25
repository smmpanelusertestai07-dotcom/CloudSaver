package app.cloudsaver.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.R
import app.cloudsaver.ui.components.typeFilter
import app.cloudsaver.ui.components.sizeFilter
import app.cloudsaver.ui.components.albumFilter
import app.cloudsaver.ui.components.ListSearchField
import app.cloudsaver.ui.components.ListOption
import app.cloudsaver.ui.components.ListFilterRow
import app.cloudsaver.ui.components.ListFilter
import app.cloudsaver.ui.components.RemovalWarningCard
import app.cloudsaver.core.logic.ListFilters
import app.cloudsaver.data.CloudApps
import app.cloudsaver.core.logic.ReclaimRules
import app.cloudsaver.core.logic.Suggestions
import app.cloudsaver.data.prefs.OptionsRepo
import app.cloudsaver.ui.goTo
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.ReclaimViewModel
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.EmptyState
import app.cloudsaver.ui.components.KeyValueRow
import app.cloudsaver.ui.theme.TabularFigures
import app.cloudsaver.util.Formats
import app.cloudsaver.core.logic.Evidence
import app.cloudsaver.core.logic.ProofLine

/**
 * Reclaim space - the only place in CloudSaver that can remove a user's photo.
 *
 * Everything here is built around one idea: nothing happens that the person
 * did not choose, having been told exactly what it costs. The default mode
 * leaves a viewable file behind, the default action is recoverable for thirty
 * days, and the numbers for all three modes are on screen before any of them
 * is picked.
 */
@Composable
fun ReclaimScreen(vm: AppViewModel, rvm: ReclaimViewModel, nav: NavHostController) {
    val entries by rvm.entries.collectAsStateWithLifecycle()
    val selected by rvm.selected.collectAsStateWithLifecycle()
    val mode by rvm.mode.collectAsStateWithLifecycle()
    val loading by rvm.loading.collectAsStateWithLifecycle()
    val result by rvm.lastResult.collectAsStateWithLifecycle()
    val dry by rvm.dryRun.collectAsStateWithLifecycle()
    val pending by rvm.pendingIntent.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val suggestion by rvm.suggestion.collectAsStateWithLifecycle()
    val sort by rvm.sort.collectAsStateWithLifecycle()
    val grouping by rvm.grouping.collectAsStateWithLifecycle()

    var confirmBig by remember { mutableStateOf<Boolean?>(null) }
    var compare by remember { mutableStateOf<ReclaimViewModel.Entry?>(null) }
    var understood by remember { mutableStateOf(options.reclaimUnderstood) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) { rvm.load() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r -> rvm.onDialogResult(r.resultCode == android.app.Activity.RESULT_OK) }
    LaunchedEffect(pending) {
        pending?.let { launcher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    val exportOk = stringResource(R.string.reclaim_export_ok)
    val exportFail = stringResource(R.string.reclaim_export_failed)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { rvm.exportSelection(it, exportOk, exportFail) } }

    val shared by rvm.listFilter.collectAsStateWithLifecycle()
    val holdingApps by rvm.holdingApps.collectAsStateWithLifecycle()
    val visible = rvm.visible()
    val groups = rvm.groups()
    val selectedEntries = rvm.selectedEntries()
    val freed = rvm.savedBytesForMode()

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
            Text(
                stringResource(R.string.freeup_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (entries.isEmpty() && !loading) {
            EmptyState(
                title = stringResource(R.string.freeup_empty_title),
                body = stringResource(R.string.freeup_empty)
            )
            return@Column
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            item("warning") {
                RemovalWarningCard(Modifier.padding(top = 8.dp))
            }
            item("modes") {
                ModePicker(rvm, mode, selectedEntries.map { it.candidate })
            }
            item("targets") {
                // "Free 5 GB" is the thought people actually have; building
                // that selection by hand is forty taps.
                Text(
                    stringResource(R.string.reclaim_target_label),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 14.dp)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (gb in listOf(1L, 5L, 10L)) {
                        AssistChip(
                            onClick = { rvm.selectForTarget(gb * 1_000_000_000L) },
                            label = { Text(stringResource(R.string.calc_chip_gb, gb.toInt())) }
                        )
                    }
                    AssistChip(
                        onClick = { rvm.selectAllVisible() },
                        label = { Text(stringResource(R.string.reclaim_all_eligible)) }
                    )
                    AssistChip(
                        onClick = { rvm.clearSelection() },
                        label = { Text(stringResource(R.string.reclaim_clear)) }
                    )
                }
            }
            // Search and the same four chips as every other list, in place of
            // the three private rows of chips this screen used to carry. The
            // proof and grouping choices stay, because they are Reclaim's own
            // question, but they now open sheets like everything else.
            item("search") {
                ListSearchField(
                    shared.query,
                    { rvm.listFilter.value = shared.copy(query = it) },
                    Modifier.padding(top = 10.dp)
                )
            }
            item("filters") {
                val albums = remember(entries) {
                    ListFilters.albumCounts(
                        entries.map {
                            ListFilters.Candidate(
                                it.id, it.row.displayName, it.row.bucket,
                                it.row.sizeBytes, it.row.isVideo
                            )
                        }
                    )
                }
                ListFilterRow(
                    filters = listOf(
                        typeFilter(shared.type) {
                            rvm.listFilter.value = shared.copy(type = it)
                        },
                        proofFilter(suggestion) { rvm.suggestion.value = it },
                        albumFilter(shared.album, albums) {
                            rvm.listFilter.value = shared.copy(album = it)
                        },
                        sizeFilter(shared.size) {
                            rvm.listFilter.value = shared.copy(size = it)
                        },
                        groupingFilter(grouping) { rvm.setGrouping(it) }
                    ),
                    sort = ListFilter(
                        name = stringResource(R.string.filter_sort),
                        valueLabel = null,
                        options = ReclaimViewModel.Sort.entries.map { option ->
                            ListOption(sortLabel(option), sort == option) { rvm.setSort(option) }
                        }
                    ),
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    stringResource(R.string.reclaim_no_internet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            for ((key, rows) in groups) {
                if (key.isNotEmpty()) {
                    item("h-$key") {
                        Column(Modifier.padding(top = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = rows.all { it.id in selected },
                                    onCheckedChange = { rvm.selectGroup(key) }
                                )
                                Text(
                                    pluralStringResource(
                                        R.plurals.reclaim_group_header,
                                        rows.size,
                                        groupTitle(key),
                                        rows.size,
                                        Formats.bytes(rows.sumOf { it.row.sizeBytes })
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // What this group's proof actually is. The old
                            // build asked the user to enable the weaker kind
                            // in Settings, which is a question nobody can
                            // answer; saying what it means is the answer.
                            groupExplanation(key)?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                                )
                            }
                        }
                    }
                }
                items(rows.size, key = { i -> rows[i].id }) { i ->
                    val entry = rows[i]
                    ReclaimRow(
                        entry = entry,
                        checked = entry.id in selected,
                        onToggle = { rvm.toggle(entry.id) },
                        onCompare = { compare = entry }
                    )
                }
            }
            item("tail") { Spacer(Modifier.height(24.dp)) }
        }

        // The running total sits with the button, because that pair is the
        // decision: this many gigabytes, for this action.
        AppCard(modifier = Modifier.padding(12.dp)) {
            if (!understood) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = false,
                        onCheckedChange = {
                            understood = true
                            vm.setReclaimUnderstood(true)
                        }
                    )
                    Text(
                        stringResource(R.string.reclaim_understand),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Text(
                stringResource(R.string.reclaim_will_free, Formats.bytes(freed)),
                // Tabular: this total changes with every tick of a checkbox.
                style = MaterialTheme.typography.titleMedium.merge(TabularFigures),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                pluralStringResource(
                    R.plurals.reclaim_selected, selected.size, selected.size, visible.size
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { rvm.previewResult() },
                    enabled = selected.isNotEmpty()
                ) { Text(stringResource(R.string.reclaim_preview)) }
                OutlinedButton(
                    onClick = { exportLauncher.launch("cloudsaver-reclaim.csv") },
                    enabled = selected.isNotEmpty()
                ) { Text(stringResource(R.string.reclaim_export)) }
            }
            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        // Removing an original always goes through the sheet
                        // that carries the check-your-cloud warning; no batch
                        // is small enough to skip it. Only the copies-only
                        // mode, which touches nothing of the user's own,
                        // keeps the quick path for ordinary batches. On a
                        // phone with no media trash every original removal is
                        // permanent whatever the button was called, so the
                        // sheet opens in its delete-for-good wording there.
                        if (mode != ReclaimRules.Mode.COPIES_ONLY ||
                            rvm.needsSecondConfirmation(permanent = false)
                        ) {
                            confirmBig =
                                mode != ReclaimRules.Mode.COPIES_ONLY && !rvm.canUndoRemoval
                        } else {
                            rvm.start(permanent = false)
                        }
                    },
                    enabled = selected.isNotEmpty() && understood
                ) {
                    // On a phone with no trash this button deletes for good,
                    // so it says so rather than promising a recovery that
                    // does not exist.
                    Text(
                        stringResource(
                            if (rvm.canUndoRemoval || mode == ReclaimRules.Mode.COPIES_ONLY) {
                                R.string.reclaim_trash
                            } else {
                                R.string.reclaim_delete
                            }
                        )
                    )
                }
                if (mode != ReclaimRules.Mode.COPIES_ONLY && rvm.canUndoRemoval) {
                    TextButton(
                        onClick = { confirmBig = true },
                        enabled = selected.isNotEmpty() && understood
                    ) { Text(stringResource(R.string.reclaim_delete)) }
                }
            }
            if (mode != ReclaimRules.Mode.COPIES_ONLY) {
                Text(
                    stringResource(R.string.reclaim_why_android_asks),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (!rvm.canUndoRemoval) {
                    Text(
                        stringResource(R.string.reclaim_no_trash_here),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }

    // A batch this large is worth stating in words before the system dialog.
    compare?.let { entry ->
        CompareSheet(
            row = entry.row,
            onDismiss = { compare = null },
            onKeepThisOne = {
                // Taking it out of the batch is the whole point of looking.
                if (entry.id in selected) rvm.toggle(entry.id)
                compare = null
            }
        )
    }

    confirmBig?.let { permanent ->
        AlertDialog(
            onDismissRequest = { confirmBig = null },
            title = {
                Text(
                    stringResource(
                        if (permanent) R.string.reclaim_confirm_delete_title
                        else R.string.reclaim_confirm_title
                    )
                )
            },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.reclaim_confirm_body,
                            Formats.count(selected.size),
                            Formats.bytes(freed)
                        )
                    )
                    // What the proof actually is, counted. "Trust us" is not
                    // an acceptable last screen before deleting photographs.
                    val tally = ProofLine.tally(
                        entries.filter { it.row.id in selected }.map {
                            ProofLine.forItem(
                                Evidence.parse(it.row.evidence),
                                isDuplicateExtra = it.row.duplicateOf != null
                            )
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    for ((kind, count) in tally.entries.sortedBy { it.key.ordinal }) {
                        Text(
                            stringResource(
                                R.string.reclaim_confirm_proof, count, proofLabel(kind)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    // Z1.4: what will still exist afterwards, and who holds
                    // it. Proof belongs to the app the file was sent to, so
                    // the sheet names that app even if the selection changed.
                    val holders = entries
                        .filter { it.row.id in selected }
                        .mapNotNull { it.row.batchId?.let { id -> holdingApps[id] } }
                        .distinct()
                        .map { pkg -> CloudApps.ALL.firstOrNull { pkg in it.packages }?.label ?: pkg }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (holders.isNotEmpty()) {
                            stringResource(
                                R.string.reclaim_confirm_keeps,
                                holders.joinToString(", ")
                            )
                        } else {
                            stringResource(R.string.reclaim_confirm_keeps_generic)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    // The blind spot, said at the moment it matters: every
                    // proof this app holds was measured from outside the
                    // cloud app. The one direct check - opening the cloud
                    // app and looking - only the user can do, so the sheet
                    // asks for it and hands over the door.
                    val cloudApp = CloudApps.byId(options.cloudSingle)
                    val cloudPkg = CloudApps.installedPackage(context, cloudApp)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (cloudPkg != null) {
                                stringResource(R.string.reclaim_blindspot, cloudApp.label)
                            } else {
                                stringResource(R.string.reclaim_blindspot_generic)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (cloudPkg != null) {
                        TextButton(
                            onClick = { CloudApps.launch(context, options.cloudSingle) }
                        ) {
                            Text(stringResource(R.string.reclaim_open_cloud, cloudApp.label))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmBig = null
                    rvm.start(permanent = permanent)
                }) { Text(stringResource(R.string.reclaim_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBig = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    dry?.let { run ->
        AlertDialog(
            onDismissRequest = { rvm.dismissDryRun() },
            confirmButton = {
                TextButton(onClick = { rvm.dismissDryRun() }) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { Text(stringResource(R.string.reclaim_preview_title)) },
            text = {
                Column {
                    KeyValueRow(
                        stringResource(R.string.reclaim_preview_count),
                        Formats.count(run.count)
                    )
                    KeyValueRow(
                        stringResource(R.string.reclaim_preview_freed),
                        Formats.bytes(run.freedBytes)
                    )
                    if (run.dropped.isNotEmpty()) {
                        Text(
                            pluralStringResource(
                                R.plurals.reclaim_preview_dropped,
                                run.dropped.size,
                                run.dropped.size
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        for ((name, refusal) in run.dropped.take(8)) {
                            Text(
                                "$name - ${refusalLabel(refusal)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        )
    }

    result?.let { r ->
        AlertDialog(
            onDismissRequest = { rvm.dismissResult() },
            confirmButton = {
                TextButton(onClick = {
                    rvm.dismissResult()
                    rvm.clearDropped()
                }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    rvm.dismissResult()
                    nav.goTo(app.cloudsaver.ui.Routes.RECLAIM_HISTORY)
                }) { Text(stringResource(R.string.reclaim_history_open)) }
            },
            title = { Text(stringResource(R.string.reclaim_done_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.reclaim_done_body,
                            Formats.bytes(r.freedBytes),
                            Formats.count(r.done.size)
                        )
                    )
                    if (r.trashed) {
                        Text(
                            stringResource(R.string.reclaim_done_trash),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    // Anything that stopped qualifying between the tap and the
                    // action, named. A batch that quietly did less than it
                    // promised is worse than one that says what it left out.
                    val dropped = rvm.droppedAtAction
                    if (dropped.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.reclaim_done_dropped,
                                dropped.size,
                                dropped.take(3).joinToString(", ")
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (r.skipped.isNotEmpty()) {
                        Text(
                            pluralStringResource(
                                R.plurals.reclaim_done_skipped,
                                r.skipped.size,
                                r.skipped.size
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        // Named, with the reason - "3 skipped" alone reads as
                        // an apology; a name and a reason is an answer.
                        for (s in r.skipped.take(3)) {
                            Text(
                                "${s.displayName} - ${skipReasonLabel(s.reason)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        )
    }
}

/** All three modes with their own totals, so they can be compared first. */
@Composable
private fun ModePicker(
    rvm: ReclaimViewModel,
    mode: ReclaimRules.Mode,
    selected: List<ReclaimRules.Candidate>
) {
    Column(Modifier.padding(top = 8.dp)) {
        for (option in ReclaimRules.Mode.entries) {
            val saving = ReclaimRules.savedBytes(selected, option)
            AppCard(
                modifier = Modifier.padding(vertical = 4.dp),
                tonal = option == mode,
                onClick = { rvm.setMode(option) }
            ) {
                Text(
                    modeTitle(option),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    modeBody(option),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (option == ReclaimRules.Mode.REPLACE_WITH_LIGHT) {
                    // Most copies have left the phone by the time anyone is
                    // here (the cloud app collected them), so remaking is the
                    // normal case, not the exception - worth a line.
                    Text(
                        stringResource(R.string.reclaim_mode_replace_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Text(
                    stringResource(R.string.reclaim_mode_saving, Formats.bytes(saving)),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ReclaimRow(
    entry: ReclaimViewModel.Entry,
    checked: Boolean,
    onToggle: () -> Unit,
    onCompare: () -> Unit
) {
    AppCard(modifier = Modifier.padding(vertical = 4.dp), onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    entry.row.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.MiddleEllipsis
                )
                Text(
                    stringResource(
                        R.string.files_size_saving,
                        Formats.bytes(entry.row.sizeBytes),
                        Formats.bytes(entry.row.outputBytes ?: 0L),
                        Formats.percentOf(entry.saving, entry.row.sizeBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(
                        R.string.reclaim_row_meta,
                        entry.row.bucket ?: "-",
                        Formats.date(entry.row.confirmedAt ?: entry.row.releasedAt ?: 0)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // How we know, per file, in the same words every other list
                // uses. This is the sentence the whole feature rests on.
                Text(
                    proofLabel(
                        ProofLine.forItem(
                            Evidence.parse(entry.row.evidence),
                            isDuplicateExtra = entry.row.duplicateOf != null
                        )
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            // Seeing the two versions is the only quality test that counts,
            // and it belongs one tap from the checkbox.
            TextButton(onClick = onCompare) {
                Text(stringResource(R.string.reclaim_compare))
            }
        }
    }
}

@Composable
private fun modeTitle(mode: ReclaimRules.Mode): String = when (mode) {
    ReclaimRules.Mode.REPLACE_WITH_LIGHT -> stringResource(R.string.reclaim_mode_replace)
    ReclaimRules.Mode.FREE_UP_FULLY -> stringResource(R.string.reclaim_mode_full)
    ReclaimRules.Mode.COPIES_ONLY -> stringResource(R.string.reclaim_mode_copies)
}

@Composable
private fun modeBody(mode: ReclaimRules.Mode): String = when (mode) {
    ReclaimRules.Mode.REPLACE_WITH_LIGHT -> stringResource(R.string.reclaim_mode_replace_body)
    ReclaimRules.Mode.FREE_UP_FULLY -> stringResource(R.string.reclaim_mode_full_body)
    ReclaimRules.Mode.COPIES_ONLY -> stringResource(R.string.reclaim_mode_copies_body)
}

@Composable
fun suggestionLabel(kind: Suggestions.Kind): String = when (kind) {
    Suggestions.Kind.BIG_OLD_VIDEOS -> stringResource(R.string.suggest_big_videos)
    Suggestions.Kind.OLD_SCREENSHOTS -> stringResource(R.string.suggest_screenshots)
    Suggestions.Kind.OLD_MEDIA -> stringResource(R.string.suggest_old)
    Suggestions.Kind.DUPLICATES -> stringResource(R.string.suggest_duplicates)
    Suggestions.Kind.CONFIRMED_30_DAYS -> stringResource(R.string.suggest_confirmed)
}

/** A group key as a person would read it. */
@Composable
private fun groupTitle(key: String): String = when (key) {
    ReclaimViewModel.GROUP_EXACT -> stringResource(R.string.group_exact)
    ReclaimViewModel.GROUP_BY_SIZE -> stringResource(R.string.group_by_size)
    "photo" -> stringResource(R.string.scope_photos)
    "video" -> stringResource(R.string.scope_videos)
    else -> key
}

@Composable
private fun groupExplanation(key: String): String? = when (key) {
    ReclaimViewModel.GROUP_EXACT -> stringResource(R.string.group_exact_body)
    ReclaimViewModel.GROUP_BY_SIZE -> stringResource(R.string.group_by_size_body)
    else -> null
}

@Composable
private fun sortLabel(sort: ReclaimViewModel.Sort): String = when (sort) {
    ReclaimViewModel.Sort.LARGEST -> stringResource(R.string.sort_largest)
    ReclaimViewModel.Sort.OLDEST -> stringResource(R.string.sort_oldest)
    ReclaimViewModel.Sort.ALBUM -> stringResource(R.string.sort_album)
}

@Composable
private fun groupingLabel(grouping: ReclaimViewModel.Grouping): String = when (grouping) {
    ReclaimViewModel.Grouping.EVIDENCE -> stringResource(R.string.group_evidence)
    ReclaimViewModel.Grouping.ALBUM -> stringResource(R.string.group_album)
    ReclaimViewModel.Grouping.MONTH -> stringResource(R.string.group_month)
    ReclaimViewModel.Grouping.YEAR -> stringResource(R.string.group_year)
    ReclaimViewModel.Grouping.TYPE -> stringResource(R.string.group_type)
}

/** Why an item in a finished batch was left alone, in the user's words. */
@Composable
private fun skipReasonLabel(reason: String?): String = when (reason) {
    "light_copy_failed" -> stringResource(R.string.skip_light_copy_failed)
    "integrity_failed" -> stringResource(R.string.skip_integrity_failed)
    "not_confirmed" -> stringResource(R.string.skip_not_confirmed)
    else -> stringResource(R.string.skip_generic)
}

@Composable
private fun refusalLabel(refusal: ReclaimRules.Refusal): String = when (refusal) {
    ReclaimRules.Refusal.NOT_CONFIRMED -> stringResource(R.string.refuse_not_confirmed)
    ReclaimRules.Refusal.TOO_RECENT -> stringResource(R.string.refuse_too_recent)
    ReclaimRules.Refusal.NO_LEDGER, ReclaimRules.Refusal.HASH_CHANGED ->
        stringResource(R.string.refuse_no_proof)
    ReclaimRules.Refusal.CLOUD_UNHEALTHY -> stringResource(R.string.refuse_cloud)
    ReclaimRules.Refusal.WRONG_STATE -> stringResource(R.string.refuse_state)
    ReclaimRules.Refusal.ORIGINAL_GONE -> stringResource(R.string.refuse_gone)
    ReclaimRules.Refusal.EXCLUDED_ALBUM -> stringResource(R.string.refuse_album)
    ReclaimRules.Refusal.FAVOURITE -> stringResource(R.string.refuse_favourite)
    ReclaimRules.Refusal.TOO_SMALL -> stringResource(R.string.refuse_small)
}

/**
 * Reclaim's own filter: which kind of proof a file has.
 *
 * It is the question this screen exists to answer, so it keeps its place in
 * the row - but as a sheet, with the others, rather than as a private strip of
 * chips above them.
 */
@Composable
private fun proofFilter(
    selected: Suggestions.Kind?,
    onSelect: (Suggestions.Kind?) -> Unit
): ListFilter {
    val kinds = Suggestions.ALL.filter { it.kind != Suggestions.Kind.DUPLICATES }
    val allLabel = stringResource(R.string.filter_all)
    return ListFilter(
        name = stringResource(R.string.filter_proof),
        valueLabel = selected?.let { suggestionLabel(it) },
        options = buildList {
            add(ListOption(allLabel, selected == null) { onSelect(null) })
            for (filter in kinds) {
                add(
                    ListOption(suggestionLabel(filter.kind), selected == filter.kind) {
                        onSelect(filter.kind)
                    }
                )
            }
        }
    )
}

/** How the list is grouped, as a sheet rather than a second row of chips. */
@Composable
private fun groupingFilter(
    selected: ReclaimViewModel.Grouping,
    onSelect: (ReclaimViewModel.Grouping) -> Unit
): ListFilter = ListFilter(
    name = stringResource(R.string.filter_grouping),
    valueLabel = if (selected == ReclaimViewModel.Grouping.entries.first()) {
        null
    } else {
        groupingLabel(selected)
    },
    options = ReclaimViewModel.Grouping.entries.map { option ->
        ListOption(groupingLabel(option), option == selected) { onSelect(option) }
    }
)
