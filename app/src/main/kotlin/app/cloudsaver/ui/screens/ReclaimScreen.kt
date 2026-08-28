package app.cloudsaver.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.R
import app.cloudsaver.ui.components.ListTags
import app.cloudsaver.ui.components.typeFilter
import app.cloudsaver.ui.components.sizeFilter
import app.cloudsaver.ui.components.albumFilter
import app.cloudsaver.ui.components.ListSearchField
import app.cloudsaver.ui.components.ListOption
import app.cloudsaver.ui.components.ListFilterRow
import app.cloudsaver.ui.components.ListFilter
import app.cloudsaver.ui.components.RemovalWarningCard
import app.cloudsaver.ui.components.WarningNote
import androidx.compose.material3.Switch
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
@OptIn(ExperimentalLayoutApi::class)
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

    // The only screen that lists someone's photographs by name next to a
    // button that removes them. It stays out of the recents thumbnail and out
    // of screenshots, for the same reason the lock screen does.
    app.cloudsaver.ui.components.SecureScreen()

    var confirmBig by remember { mutableStateOf<Boolean?>(null) }
    var compare by remember { mutableStateOf<ReclaimViewModel.Entry?>(null) }
    // The saved answer arrives a moment after the screen is first drawn, and
    // a plain remember of its value kept the very first one - "not yet" - for
    // the life of the screen, so someone who had already ticked this box was
    // asked to tick it again every time they came back. The tick is held
    // separately and either source counts.
    var justUnderstood by rememberSaveable { mutableStateOf(false) }
    val understood = justUnderstood || options.reclaimUnderstood
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
    // Filtering and sorting the batch is not free, and this ran four times
    // over on every recomposition - once per call, plus the three that call
    // visible() again inside themselves. On a four-hundred-file selection
    // that is four passes for every checkbox tap, on the one screen that has
    // to feel dependable. Each is now recomputed only when its inputs change.
    // The proof choice is one of the inputs to both, and leaving it out of the
    // keys meant picking a proof filter changed the chip and nothing else: the
    // list kept the rows it already had until something else happened to
    // change. Every input the two functions read is listed here.
    val visible = remember(entries, shared, suggestion, sort, grouping) { rvm.visible() }
    val groups = remember(entries, shared, suggestion, sort, grouping) { rvm.groups() }
    val selectedEntries = remember(visible, selected) {
        visible.filter { it.id in selected }
    }
    val freed = remember(selectedEntries, mode) {
        ReclaimRules.savedBytes(selectedEntries.map { it.candidate }, mode)
    }

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
            // Nothing to remove is still a screen, and it is the one state
            // here that draws no list at all. Sideways on a phone at a large
            // font the mark, the heading and the sentence under it are taller
            // than the display, so this state scrolls like every other.
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                EmptyState(
                    title = stringResource(R.string.freeup_empty_title),
                    body = stringResource(R.string.freeup_empty)
                )
            }
            return@Column
        }

        // Tagged so a test can scroll it the way lazy lists have to be
        // scrolled. performScrollTo does not support them - it reaches
        // whatever happens to be composed and gives up - which is why an
        // assertion here read "Free a set amount is not displayed" on a
        // screen that was drawing it perfectly well, just further down.
        LazyColumn(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
                .testTag(ListTags.ROWS)
        ) {
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
                // These five chips used to sit in a row that scrolled
                // sideways, and a sideways scroll inside a vertical list is a
                // gesture nobody goes looking for: there is no edge, no arrow
                // and no half-cut chip to say anything more exists. At a large
                // font on a narrow phone that put "Everything eligible" and
                // "Clear" off the right-hand side with nothing on screen
                // hinting at them, so the two chips that undo a mistaken
                // selection were the two that could not be found. Wrapping
                // puts the overflow on the next line instead, at every width
                // and font size, exactly as the health chips on Home do.
                FlowRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.toggleable(
                                    value = rows.all { it.id in selected },
                                    onValueChange = { rvm.selectGroup(key) },
                                    role = Role.Checkbox
                                )
                            ) {
                                Checkbox(
                                    checked = rows.all { it.id in selected },
                                    onCheckedChange = null
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
        //
        // The pair is also measured before the list is given anything, so
        // on a short screen - a phone turned sideways, or a small one at
        // the largest font - the tick, the total, the four buttons and the
        // two explanations took the whole display, left the list with none
        // of it, and pushed the last button off the bottom edge. Measuring
        // the space first caps this at half of what is left and lets it
        // scroll inside that: the list keeps the rest, and every button
        // stays reachable however short the screen is.
        BoxWithConstraints {
            Column(
                Modifier
                    .heightIn(max = maxHeight / 2)
                    .verticalScroll(rememberScrollState())
            ) {
                AppCard(modifier = Modifier.padding(12.dp)) {
                    if (!understood) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.toggleable(
                                value = false,
                                onValueChange = {
                                    justUnderstood = true
                                    vm.setReclaimUnderstood(true)
                                },
                                role = Role.Checkbox
                            )
                        ) {
                            Checkbox(checked = false, onCheckedChange = null)
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
                        // Counted the same way as the total above it and the batch
                        // itself: only rows the current filters still show can be
                        // acted on. Counting every tick ever made printed "40 of 5
                        // selected" the moment a filter narrowed the list, and
                        // promised thirty-five files that were never going anywhere.
                        pluralStringResource(
                            R.plurals.reclaim_selected,
                            selectedEntries.size,
                            selectedEntries.size,
                            visible.size
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Two buttons side by side is two buttons wide, and "Preview
                    // result" beside "Export list" at the largest accessibility font
                    // is wider than a 320 dp phone: the second one simply left the
                    // screen. Wrapping puts the overflow on the next line instead,
                    // at every width and font size, and keeps the order.
                    FlowRow(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { rvm.previewResult() },
                            enabled = selected.isNotEmpty()
                        ) {
                            Text(
                                stringResource(R.string.reclaim_preview),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = { exportLauncher.launch("cloudsaver-reclaim.csv") },
                            enabled = selected.isNotEmpty()
                        ) {
                            Text(
                                stringResource(R.string.reclaim_export),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    FlowRow(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                    if (rvm.canUndoRemoval ||
                                        mode == ReclaimRules.Mode.COPIES_ONLY
                                    ) {
                                        R.string.reclaim_trash
                                    } else {
                                        R.string.reclaim_delete
                                    }
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (mode != ReclaimRules.Mode.COPIES_ONLY && rvm.canUndoRemoval) {
                            TextButton(
                                onClick = { confirmBig = true },
                                enabled = selected.isNotEmpty() && understood
                            ) {
                                Text(
                                    stringResource(R.string.reclaim_delete),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
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
                // Scrollable, because a dialog's text slot is not. This is the
                // last thing shown before originals are removed for good, and
                // on a small screen at a large font its lower half - the
                // warning and what will happen - was simply off the display.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // Every number in this sheet is counted from the rows the
                    // batch will actually touch, and only those.
                    //
                    // The batch acts on the selection narrowed by whatever
                    // filters the list is showing - that is what start() takes
                    // - but this sheet counted every tick ever made, filtered
                    // or not. So someone who ticked twelve files and then
                    // narrowed to a single album was shown "12 files, 3.4 GB",
                    // agreed to it, and had five removed. The gap ran through
                    // the whole sheet: the count, the proof tally under it and
                    // the list of cloud apps that keep the copies were all
                    // drawn from files that were never going anywhere. A
                    // consent screen that overstates what it is about to do is
                    // worse than no consent screen, because the person has now
                    // been told a number and believes it. The space figure was
                    // already counted the right way; the rest now matches it.
                    Text(
                        stringResource(
                            R.string.reclaim_confirm_body,
                            Formats.count(selectedEntries.size),
                            Formats.bytes(freed)
                        )
                    )
                    // What the proof actually is, counted. "Trust us" is not
                    // an acceptable last screen before deleting photographs.
                    val tally = ProofLine.tally(
                        selectedEntries.map {
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
                    val holders = selectedEntries
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
                            // The label carries an app name someone else
                            // chose, so it is as long as it is - two lines
                            // rather than a cut through the middle of it.
                            Text(
                                stringResource(R.string.reclaim_open_cloud, cloudApp.label),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
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
                // Up to eight refused files are named here, so this slot is
                // the one most able to outgrow the screen it is drawn on.
                Column(Modifier.verticalScroll(rememberScrollState())) {
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
                // What was freed, what was left behind and why - all of it
                // has to stay reachable, at any font size.
                Column(Modifier.verticalScroll(rememberScrollState())) {
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
                    // Where the replacement lands is a property of this mode,
                    // so it is asked here rather than buried in Settings -
                    // the question only means anything next to the choice it
                    // changes, and only while that choice is the live one.
                    if (option == mode) InPlaceChoice(rvm)
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

/**
 * Where the replacement copy goes: its own album, or the original's.
 *
 * In place is the answer to "I want my album exactly as it was, only
 * smaller" - the copy carries the original's name and its date, so the
 * gallery timeline does not move. It is also the one choice here that
 * changes a file a person will meet outside this app, so the trade is
 * spelled out under the switch rather than left to be discovered: the
 * extension can change, and the file in the album is no longer the byte
 * for byte twin of what the cloud holds.
 */
@Composable
private fun InPlaceChoice(rvm: ReclaimViewModel) {
    val inPlace by rvm.keptInPlace.collectAsStateWithLifecycle()
    // The switch is a fixed 52 dp whatever the text does, so past the shared
    // stacking point it goes under the words rather than taking a third of
    // the row from a sentence that by then needs all of it.
    val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale >=
        app.cloudsaver.ui.components.StackedTextScale
    Column(
        Modifier
            .padding(top = 8.dp)
            .toggleable(
                value = inPlace,
                role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = { rvm.setKeptInPlace(it) }
            )
    ) {
        val label: @Composable () -> Unit = {
            Column {
                Text(
                    stringResource(R.string.kept_in_place_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    stringResource(R.string.kept_in_place_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (stacked) {
            label()
            Switch(checked = inPlace, onCheckedChange = null, modifier = Modifier.padding(top = 6.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { label() }
                Switch(
                    checked = inPlace,
                    onCheckedChange = null,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
        if (inPlace) {
            WarningNote(stringResource(R.string.kept_in_place_warning))
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
            // and it belongs one tap from the checkbox. It takes a share of
            // the row rather than whatever its label happens to want: at the
            // largest font on a narrow phone an unbounded button swallowed
            // the width and left the file name with nothing to be drawn in.
            TextButton(
                onClick = onCompare,
                modifier = Modifier.weight(0.45f, fill = false)
            ) {
                Text(
                    stringResource(R.string.reclaim_compare),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
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
