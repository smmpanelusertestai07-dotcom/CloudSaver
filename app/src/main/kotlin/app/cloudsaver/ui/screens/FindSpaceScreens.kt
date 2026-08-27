package app.cloudsaver.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.cloudsaver.ui.components.ListTags
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.R
import app.cloudsaver.core.logic.RowActions
import app.cloudsaver.ui.components.typeFilter
import app.cloudsaver.ui.components.sizeFilter
import app.cloudsaver.ui.components.selectionSummary
import app.cloudsaver.ui.components.rememberListSelection
import app.cloudsaver.ui.components.albumFilter
import app.cloudsaver.ui.components.RemovalWarningCard
import app.cloudsaver.ui.components.SearchEmptyState
import app.cloudsaver.ui.components.ListScreenScaffold
import app.cloudsaver.ui.components.ListOption
import app.cloudsaver.ui.components.ListFilter
import app.cloudsaver.ui.components.ListActionBar
import app.cloudsaver.ui.components.FilteredEmptyState
import app.cloudsaver.core.logic.ListFilters
import app.cloudsaver.ui.goTo
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.ReclaimViewModel
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.EmptyState
import app.cloudsaver.ui.components.KeyValueRow
import app.cloudsaver.ui.theme.TabularFigures
import app.cloudsaver.util.Formats
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.cloudsaver.ui.components.FileRow
import app.cloudsaver.core.logic.ProofLine
import app.cloudsaver.core.logic.Projection
import app.cloudsaver.ui.Routes
import kotlinx.coroutines.delay

/** A page header with a back arrow, shared by the Find space screens. */
@Composable
private fun Page(
    nav: NavHostController,
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
            // The title takes whatever the arrow leaves rather than its own
            // natural width. A heading this size at a 200% font scale is
            // wider than a 320 dp phone on its own, and with no share of the
            // row it simply ran off the end of the screen.
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        content()
    }
}

/**
 * Files that are byte-for-byte identical, and the one extra thing this screen
 * can do that no other can: remove a copy with no upload evidence at all.
 *
 * That is safe because the proof is local. An identical file stays on the
 * phone, so the content is provably still there whatever any cloud app did or
 * did not do. The screen says exactly that above the list, because a person
 * being asked to delete photographs deserves the actual reason before they
 * are asked, not after.
 */
@Composable
fun DuplicatesScreen(vm: AppViewModel, rvm: ReclaimViewModel, nav: NavHostController) {
    val groups by rvm.duplicateGroups.collectAsStateWithLifecycle()
    val removed by rvm.duplicatesRemoved.collectAsStateWithLifecycle()
    val pending by rvm.pendingIntent.collectAsStateWithLifecycle()
    val loading by rvm.duplicatesLoading.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(ListFilters.Type.ALL) }
    var size by rememberSaveable { mutableStateOf(ListFilters.Size.ANY) }
    var album by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(DupeSort.SPACE) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val selection = rememberListSelection()

    LaunchedEffect(Unit) { rvm.loadDuplicates() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> rvm.onDialogResult(result.resultCode == android.app.Activity.RESULT_OK) }
    LaunchedEffect(pending) {
        pending?.let { launcher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    val state = ListFilters.State(type, size, album, query)
    // Filtering applies to the extras, since those are what a filter is for:
    // a group survives when any of its extras match, and shows only those.
    val shown = remember(groups, state, sort) {
        groups.mapNotNull { g ->
            val kept = g.extras.filter { ListFilters.matches(it.toCandidate(), state) }
            if (kept.isEmpty()) null else g to kept
        }.sortedWith(
            when (sort) {
                DupeSort.SPACE -> compareByDescending { (_, extras) ->
                    extras.sumOf { it.sizeBytes }
                }
                DupeSort.COPIES -> compareByDescending { (_, extras) -> extras.size }
                DupeSort.OLDEST -> compareBy { (g, _) -> g.keeper.capturedAtMs }
            }
        )
    }
    // Flattened once per change of the grouped list, not once per frame: a
    // gallery with a few thousand duplicates walked every group again on
    // every recomposition, including on each tick of a selection.
    val allExtras = remember(shown) { shown.flatMap { it.second } }
    val selectedBytes = remember(allExtras, selection.ids) {
        allExtras.filter { it.id in selection }.sumOf { it.sizeBytes }
    }
    val albums = remember(groups) {
        ListFilters.albumCounts(groups.flatMap { it.extras }.map { it.toCandidate() })
    }
    val legacy = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R

    ListScreenScaffold(
        title = stringResource(R.string.find_duplicates),
        onBack = { nav.popBackStack() },
        query = query,
        onQuery = { query = it },
        filters = listOf(
            typeFilter(type) { type = it },
            albumFilter(album, albums) { album = it },
            sizeFilter(size) { size = it }
        ),
        sort = ListFilter(
            name = stringResource(R.string.filter_sort),
            valueLabel = null,
            options = listOf(
                ListOption(stringResource(R.string.dupes_sort_space), sort == DupeSort.SPACE) {
                    sort = DupeSort.SPACE
                },
                ListOption(stringResource(R.string.dupes_sort_copies), sort == DupeSort.COPIES) {
                    sort = DupeSort.COPIES
                },
                ListOption(stringResource(R.string.dupes_sort_oldest), sort == DupeSort.OLDEST) {
                    sort = DupeSort.OLDEST
                }
            )
        ),
        selection = selection,
        matchingCount = allExtras.size,
        onSelectAll = { selection.selectAll(allExtras.map { it.id }) },
        onResetFilters = {
            type = ListFilters.Type.ALL
            size = ListFilters.Size.ANY
            album = null
        },
        loading = loading && groups.isEmpty(),
        isEmpty = shown.isEmpty(),
        emptyContent = {
            // The empty state is drawn in whatever height the header, the
            // search box and the filter row leave behind. On a phone held
            // sideways at a large font that is a couple of hundred dp, which
            // is less than this state needs, so it scrolls rather than being
            // cut off at the bottom of the glass.
            // The scroll belongs to the scaffold's empty branch, which is the
            // only place that knows how much height the title, the search box
            // and the chips above have already taken. A second one here is
            // measured by the first with no ceiling at all, and a scrolling
            // container asked how tall it would like to be throws rather than
            // answers - which is what crashed Files and Free up space the
            // moment a filter or a search left nothing to show.
            when {
                groups.isNotEmpty() && query.isNotBlank() ->
                    SearchEmptyState(term = query, onClear = { query = "" })
                groups.isNotEmpty() -> FilteredEmptyState(
                    onReset = {
                        type = ListFilters.Type.ALL
                        size = ListFilters.Size.ANY
                        album = null
                    }
                )
                else -> EmptyState(
                    title = stringResource(R.string.dupes_empty_title),
                    body = stringResource(R.string.dupes_empty_body)
                )
            }
        
        },
        actionBar = {
            ListActionBar(
                summary = selectionSummary(selection.size, Formats.bytes(selectedBytes)),
                actionLabel = stringResource(R.string.dupes_remove_extras),
                onAction = { confirming = true }
            )
        }
    ) {
        item("intro") {
            // The first thing in the list rather than a band pinned above it.
            // Two paragraphs and the three-line removal warning are most of a
            // landscape phone at a large font, and above the list they left
            // the rows no height to be drawn in at all. Inside it they are
            // still the first thing read, and they can be scrolled past.
            Column(Modifier.padding(top = 4.dp)) {
                Text(
                    stringResource(R.string.dupes_intro),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(R.string.dupes_intro_second),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                // Never behind the action. Someone about to delete
                // photographs should not have to tap to find out that their
                // cloud is untouched and the files are recoverable.
                RemovalWarningCard(Modifier.padding(top = 10.dp))
            }
        }
        for ((group, extras) in shown) {
            item("h-${group.sha256}") {
                Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
                    Text(
                        pluralStringResource(
                            R.plurals.dupes_group_header,
                            extras.size + 1,
                            extras.size + 1,
                            Formats.bytes(extras.sumOf { it.sizeBytes })
                        ),
                        style = MaterialTheme.typography.labelLarge.merge(TabularFigures),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.dupes_group_proof),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item("k-${group.sha256}") {
                DuplicateEntryRow(
                    vm = vm,
                    entry = group.keeper,
                    isKeeper = true,
                    selected = null,
                    onToggle = {},
                    onLongPress = null,
                    onKeepInstead = null
                )
            }
            items(extras, key = { "e-${it.id}" }) { extra ->
                DuplicateEntryRow(
                    vm = vm,
                    entry = extra,
                    isKeeper = false,
                    selected = if (selection.active) extra.id in selection else null,
                    onToggle = { selection.toggle(extra.id) },
                    onLongPress = { selection.toggle(extra.id) },
                    onKeepInstead = { rvm.keepInstead(group.sha256, extra.id) },
                    onRemoveExtra = { rvm.removeDuplicateExtras(setOf(extra.id)) }
                )
            }
        }
    }

    // What is about to happen, before Android's own dialog rather than
    // instead of it: how many, how much, where they go, and the one sentence
    // that matters - a copy of every file stays.
    if (confirming) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.dupes_confirm_title)) },
            text = {
                // A dialog's text slot does not scroll. On a small screen at
                // a large font its lower half simply sits past the edge, and
                // the buttons are pushed off with it.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        pluralStringResource(
                            R.plurals.dupes_confirm_body,
                            selection.size,
                            selection.size,
                            Formats.bytes(selectedBytes)
                        )
                    )
                    Text(
                        stringResource(
                            if (legacy) R.string.dupes_confirm_where_legacy
                            else R.string.dupes_confirm_where
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        stringResource(R.string.dupes_confirm_safe),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    rvm.removeDuplicateExtras(selection.ids)
                    selection.clear()
                }) { Text(stringResource(R.string.dupes_remove_extras)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    removed?.let { count ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { rvm.dismissDuplicatesResult() },
            confirmButton = {
                TextButton(onClick = { rvm.dismissDuplicatesResult() }) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { Text(stringResource(R.string.dupes_removed_title)) },
            text = {
                // A dialog's text slot does not scroll. On a small screen at a
                // large font its lower half simply sits past the edge, and the
                // buttons are pushed off with it.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(pluralStringResource(R.plurals.dupes_removed_body, count, count))
                }
            }
        )
    }
}

/** One duplicate entry, as the shared filters need to see it. */
private fun app.cloudsaver.core.logic.DuplicateRules.Entry.toCandidate() =
    ListFilters.Candidate(
        id = id,
        name = displayName,
        album = album,
        sizeBytes = sizeBytes,
        // Duplicate grouping is byte-identity, which says nothing about kind;
        // the name extension is what the list has to go on.
        isVideo = displayName.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
    )

private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "3gp", "mkv", "webm", "avi", "m4v")

/**
 * One file inside a duplicate group.
 *
 * The full folder is always shown, for the keeper and every extra alike. Two
 * entries showing only "Camera" and "Camera" are indistinguishable, and being
 * unable to tell which copy you are about to delete is exactly the moment
 * someone stops trusting the screen.
 */
@Composable
private fun DuplicateEntryRow(
    vm: AppViewModel,
    entry: app.cloudsaver.core.logic.DuplicateRules.Entry,
    isKeeper: Boolean,
    selected: Boolean?,
    onToggle: (Boolean) -> Unit,
    onLongPress: (() -> Unit)?,
    onKeepInstead: (() -> Unit)?,
    onRemoveExtra: (() -> Unit)? = null
) {
    val keepInsteadLabel = stringResource(R.string.dupes_keep_instead)
    val removeExtraLabel = stringResource(R.string.dupes_remove_extra_one)
    val openLabel = stringResource(R.string.list_open)
    val cannotOpen = stringResource(R.string.detail_open_failed)
    val context = androidx.compose.ui.platform.LocalContext.current
    // The stored row, resolved off the main thread once and handed to the
    // thumbnail as well. It used to be looked up twice per row - here for the
    // Open action and again inside the thumbnail - which on a screen full of
    // duplicates is two database reads for every row on screen, exactly what
    // the comment claimed it was avoiding.
    val stored by androidx.compose.runtime.produceState<app.cloudsaver.data.db.ItemRow?>(
        null, entry.id
    ) { value = vm.itemById(entry.id) }
    // CC5.1: Open first, on the keeper as well as the extras - deciding which
    // of two identical-looking rows to remove is exactly when someone needs
    // to look at the file. It goes through the shared chooser, so Android
    // offers its own app list with "Just once" and "Always"; the app never
    // picks or remembers a viewer itself.
    val actions = buildList {
        add(
            openLabel to {
                val ok = stored?.let { vm.openInViewer(it) } ?: false
                if (!ok) {
                    android.widget.Toast
                        .makeText(context, cannotOpen, android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
                Unit
            }
        )
        onRemoveExtra?.let { add(removeExtraLabel to it) }
        onKeepInstead?.let { add(keepInsteadLabel to it) }
    }
    FileRow(
        name = entry.displayName,
        context = entry.path.ifEmpty { entry.album.orEmpty() },
        size = Formats.bytes(entry.sizeBytes),
        proof = if (isKeeper) {
            stringResource(R.string.dupes_this_one_stays)
        } else {
            stringResource(R.string.proof_identical)
        },
        thumbnail = {
            stored?.let { Thumbnail(it) }
                ?: androidx.compose.foundation.layout.Box(Modifier.size(52.dp))
        },
        actions = actions,
        selected = selected,
        onSelectedChange = if (selected != null) onToggle else null,
        onLongPress = onLongPress,
        onClick = if (selected != null) {
            { onToggle(!selected) }
        } else {
            null
        }
    )
}

/**
 * The largest originals, what optimising them would save, and the two things
 * worth doing about them.
 *
 * "Remove from phone" is offered only for files already confirmed backed up,
 * and it is absent rather than greyed out for the rest: a disabled control
 * invites the question "why not?", and the answer - "because we cannot prove
 * your cloud has it" - belongs in the proof line, not in a tooltip nobody
 * opens. Which actions a row gets is decided by the shared rule, so this
 * screen cannot drift from Files.
 */
@Composable
fun BiggestFilesScreen(vm: AppViewModel, rvm: ReclaimViewModel, nav: NavHostController) {
    val all by rvm.largest.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(ListFilters.Type.ALL) }
    var sizeBand by rememberSaveable { mutableStateOf(ListFilters.Size.ANY) }
    var album by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(BiggestSort.LARGEST) }
    val selection = rememberListSelection()

    // This screen used to hand the scaffold loading = false no matter what,
    // which meant that for the whole of the database read it drew the "No big
    // files" state - the app telling someone whose gallery is full of 4K video
    // that they have nothing large, and then quietly replacing that with a
    // list of their largest files a moment later. Contradicting the person's
    // own phone is about the worst first impression a screen can make.
    //
    // The view model has no signal to borrow: loadLargest() starts a coroutine
    // of its own, replaces rvm.largest whenever the read comes back, and
    // returns immediately with nothing to wait on. So the wait is bounded
    // here instead. While nothing has arrived and the settle window has not
    // passed, the scaffold shows its skeleton rows; the instant rows arrive
    // the list is no longer empty and the flag stops mattering, and a gallery
    // that really has no files reaches its empty state a moment later. On a
    // second visit rvm.largest is already populated, so there are no skeletons
    // at all.
    var settled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        rvm.loadLargest()
        vm.refreshProfile()
        delay(LARGEST_SETTLE_MS)
        settled = true
    }

    val state = ListFilters.State(type, sizeBand, album, query)
    val rows = remember(all, state, sort, profile) {
        all.filter { ListFilters.matches(it.toCandidate(), state) }
            .let { list ->
                when (sort) {
                    BiggestSort.LARGEST -> list.sortedByDescending { it.sizeBytes }
                    // dateModified comes from MediaStore in seconds while
                    // captureAt is milliseconds, so the fallback has to be
                    // converted: compared as-is it is a thousand times
                    // smaller and sorts every such file to the very top of
                    // "oldest first" regardless of its date.
                    BiggestSort.OLDEST -> list.sortedBy {
                        if (it.captureAt > 0) it.captureAt else it.dateModified * 1000L
                    }
                    BiggestSort.SAVED -> list.sortedByDescending { savingFor(it, profile) }
                }
            }
    }
    val albums = remember(all) { ListFilters.albumCounts(all.map { it.toCandidate() }) }
    // Both sums walk every row on this screen; they change only when the rows
    // or the measured profile do, not on every recomposition.
    val totalBytes = remember(rows) { rows.sumOf { it.sizeBytes } }
    val couldSave = remember(rows, profile) { rows.sumOf { savingFor(it, profile) } }
    val rough = profile.photos.ratio <= 0.0 || profile.videos.ratio <= 0.0

    // All three walk every row on the screen, and all three used to do it on
    // every recomposition - which on a selection of fifty files meant three
    // full passes per tick of a checkbox. They change only when the rows or
    // the selection do.
    val chosen = remember(rows, selection.ids) { rows.filter { it.id in selection } }
    val selectedBytes = remember(chosen) { chosen.sumOf { it.sizeBytes } }
    val removable = remember(chosen) {
        chosen.filter {
            RowActions.Action.REMOVE_FROM_PHONE in RowActions.forItem(it.toActionRow())
        }
    }

    ListScreenScaffold(
        title = stringResource(R.string.find_biggest),
        onBack = { nav.popBackStack() },
        query = query,
        onQuery = { query = it },
        filters = listOf(
            typeFilter(type) { type = it },
            albumFilter(album, albums) { album = it },
            sizeFilter(sizeBand) { sizeBand = it }
        ),
        sort = ListFilter(
            name = stringResource(R.string.filter_sort),
            valueLabel = null,
            options = listOf(
                ListOption(
                    stringResource(R.string.list_sort_largest),
                    sort == BiggestSort.LARGEST
                ) { sort = BiggestSort.LARGEST },
                ListOption(
                    stringResource(R.string.list_sort_oldest),
                    sort == BiggestSort.OLDEST
                ) { sort = BiggestSort.OLDEST },
                ListOption(
                    stringResource(R.string.list_sort_saving),
                    sort == BiggestSort.SAVED
                ) { sort = BiggestSort.SAVED }
            )
        ),
        selection = selection,
        matchingCount = rows.size,
        onSelectAll = { selection.selectAll(rows.map { it.id }) },
        onResetFilters = {
            type = ListFilters.Type.ALL
            sizeBand = ListFilters.Size.ANY
            album = null
        },
        loading = !settled && all.isEmpty(),
        isEmpty = rows.isEmpty(),
        emptyContent = {
            // Scrollable for the same reason the list is: sideways on a phone
            // at a large font there is not enough height left below the
            // filters to draw this state, and without a scroll its lower half
            // is simply past the bottom edge.
            // The scroll belongs to the scaffold's empty branch, which is the
            // only place that knows how much height the title, the search box
            // and the chips above have already taken. A second one here is
            // measured by the first with no ceiling at all, and a scrolling
            // container asked how tall it would like to be throws rather than
            // answers - which is what crashed Files and Free up space the
            // moment a filter or a search left nothing to show.
            when {
                all.isNotEmpty() && query.isNotBlank() ->
                    SearchEmptyState(term = query, onClear = { query = "" })
                all.isNotEmpty() -> FilteredEmptyState(
                    onReset = {
                        type = ListFilters.Type.ALL
                        sizeBand = ListFilters.Size.ANY
                        album = null
                    }
                )
                else -> EmptyState(
                    title = stringResource(R.string.biggest_empty_title),
                    body = stringResource(R.string.biggest_empty_body)
                )
            }
        
        },
        actionBar = {
            // Removal needs proof for every file chosen. Rather than failing
            // part way, or quietly doing some of them, the bar says how many
            // fall short and offers to narrow the selection to the rest.
            val shortfall = chosen.size - removable.size
            // CC6: only what the action can touch, with the counts said.
            val split = RowActions.splitForOptimise(
                chosen.map { it.id to it.toActionRow() }
            )
            val blocked = shortfall > 0 && removable.isNotEmpty()
            // With nothing left to optimise and nothing to narrow to, the bar
            // is absent rather than showing a button that runs an optimise
            // over an empty list - it cleared the selection and did nothing
            // else, which reads as the app quietly losing the job. Files
            // already hides its bar at zero eligible; this one did not.
            if (split.eligible > 0 || blocked) {
                ListActionBar(
                    // frees = false: the action here optimises. Removing from
                    // the phone is a separate choice, made later through
                    // Android's own dialog.
                    summary = selectionSummary(
                        selection.size, Formats.bytes(selectedBytes), frees = false
                    ),
                    actionLabel = if (split.skipped > 0 && split.eligible > 0) {
                        stringResource(R.string.bulk_optimise_of, split.eligible, chosen.size)
                    } else {
                        stringResource(R.string.list_optimise_these_first)
                    },
                    note = if (split.skipped > 0 && split.eligible > 0) {
                        pluralStringResource(
                            R.plurals.bulk_skipped_note, split.skipped, split.skipped
                        )
                    } else {
                        null
                    },
                    onAction = {
                        rvm.optimiseFirst(split.eligibleIds)
                        selection.clear()
                    },
                    blockedReason = if (blocked) {
                        pluralStringResource(
                            R.plurals.list_remove_blocked, shortfall, shortfall, chosen.size
                        )
                    } else {
                        null
                    },
                    narrowLabel = stringResource(R.string.list_select_backed_up),
                    onNarrow = { selection.replaceWith(removable.map { it.id }) }
                )
            }
        }
    ) {
        item("summary") {
            AppCard(modifier = Modifier.padding(vertical = 10.dp), tonal = true) {
                Text(
                    pluralStringResource(
                        if (rough) R.plurals.biggest_header_plain_rough
                        else R.plurals.biggest_header_plain,
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
            BiggestRow(
                row = row,
                saving = savingFor(row, profile),
                selected = if (selection.active) row.id in selection else null,
                onToggle = { selection.toggle(row.id) },
                onLongPress = { selection.toggle(row.id) },
                onOpen = { vm.openInViewer(row) },
                onOptimise = { rvm.optimiseFirst(listOf(row.id)) },
                onNever = { rvm.setNeverOptimise(row.id, true) },
                onAllowAgain = { rvm.setNeverOptimise(row.id, false) },
                onRemove = {
                    // Straight into Reclaim with this one ticked: that is
                    // where the eligibility gate and trash-first rule are.
                    rvm.selectOnly(row.id)
                    nav.goTo(Routes.FREE_UP)
                }
            )
        }
    }
}

/** A stored row as the shared filters need to see it. */
private fun app.cloudsaver.data.db.ItemRow.toCandidate() = ListFilters.Candidate(
    id = id,
    name = displayName,
    album = bucket,
    sizeBytes = sizeBytes,
    isVideo = isVideo
)

private enum class BiggestSort { LARGEST, OLDEST, SAVED }

/**
 * How long Biggest files waits before it believes a gallery is really empty.
 *
 * Reading the fifty largest rows out of the local database takes a few
 * milliseconds, so in practice the rows are there long before this elapses and
 * nobody sees the skeletons for more than a blink. It exists only so that a
 * phone with genuinely nothing stored does not sit on skeleton rows forever
 * waiting for a list that is never coming.
 */
private const val LARGEST_SETTLE_MS = 1_200L

private enum class DupeSort { SPACE, COPIES, OLDEST }

/**
 * What optimising this one file would save, through the shared projection so
 * it agrees with every other screen - including when nothing is measured yet.
 */
private fun savingFor(
    row: app.cloudsaver.data.db.ItemRow,
    profile: app.cloudsaver.core.logic.MediaProfile.Profile
): Long {
    row.outputBytes?.let { return (row.sizeBytes - it).coerceAtLeast(0L) }
    val measured = if (row.isVideo) profile.videos.ratio else profile.photos.ratio
    return Projection.forItem(row.sizeBytes, row.isVideo, measured)
}

@Composable
private fun BiggestRow(
    row: app.cloudsaver.data.db.ItemRow,
    saving: Long,
    selected: Boolean?,
    onToggle: (Boolean) -> Unit,
    onLongPress: () -> Unit,
    onOpen: () -> Boolean,
    onOptimise: () -> Unit,
    onNever: () -> Unit,
    onAllowAgain: () -> Unit,
    onRemove: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val couldNotOpen = stringResource(R.string.biggest_cannot_open)
    val openLabel = stringResource(R.string.list_open)
    val optimiseLabel = stringResource(R.string.list_optimise_first)
    val tryAgainLabel = stringResource(R.string.detail_try_again)
    val neverLabel = stringResource(R.string.list_never)
    val allowLabel = stringResource(R.string.detail_optimise_again)
    val removeLabel = stringResource(R.string.detail_remove_from_phone)
    val proofKind = ProofLine.forItem(
        app.cloudsaver.core.logic.Evidence.parse(row.evidence),
        isDuplicateExtra = row.duplicateOf != null
    )
    val open: () -> Unit = {
        if (!onOpen()) {
            android.widget.Toast
                .makeText(context, couldNotOpen, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }
    // The same rule Files obeys, so "never optimise" cannot appear here on a
    // file that has already been optimised while Files correctly hides it.
    val actions = RowActions.forItem(row.toActionRow()).mapNotNull { action ->
        when (action) {
            RowActions.Action.OPEN -> openLabel to open
            RowActions.Action.OPTIMISE_FIRST -> optimiseLabel to onOptimise
            RowActions.Action.TRY_AGAIN -> tryAgainLabel to onOptimise
            RowActions.Action.NEVER_OPTIMISE -> neverLabel to onNever
            RowActions.Action.ALLOW_AGAIN -> allowLabel to onAllowAgain
            RowActions.Action.REMOVE_FROM_PHONE -> removeLabel to onRemove
            else -> null
        }
    }
    FileRow(
        name = row.displayName,
        context = detailLine(row),
        size = Formats.bytes(row.sizeBytes),
        proof = proofLabel(proofKind),
        thumbnail = { Thumbnail(row) },
        actions = actions,
        selected = selected,
        onSelectedChange = if (selected != null) onToggle else null,
        onLongPress = onLongPress,
        trailingNote = if (saving > 0) {
            stringResource(
                R.string.biggest_after,
                Formats.bytes((row.sizeBytes - saving).coerceAtLeast(0L))
            )
        } else {
            null
        },
        onClick = { if (selected != null) onToggle(!selected) else open() }
    )
}

/** The one plain "how we know" line, in the same words on every screen. */
@Composable
fun proofLabel(kind: ProofLine.Kind): String = stringResource(
    when (kind) {
        ProofLine.Kind.CLOUD_REMOVED_COPY -> R.string.proof_cloud_removed
        ProofLine.Kind.UPLOAD_SIZE_MATCHED -> R.string.proof_size_matched
        ProofLine.Kind.IDENTICAL_COPY_KEPT -> R.string.proof_identical
        ProofLine.Kind.TIME_ONLY -> R.string.proof_time_only
        ProofLine.Kind.WAITING -> R.string.proof_waiting
    }
)

/** Kind, when it was taken, its album, and how long it runs if it runs at all. */
@Composable
private fun detailLine(row: app.cloudsaver.data.db.ItemRow): String {
    val kind = stringResource(if (row.isVideo) R.string.kind_video else R.string.kind_photo)
    // Seconds from MediaStore, milliseconds everywhere else in the app: the
    // fallback printed a date in January 1970 rather than the file's own.
    val taken = Formats.date(
        if (row.captureAt > 0) row.captureAt else row.dateModified * 1000L
    )
    val album = row.bucket ?: stringResource(R.string.biggest_no_album)
    return if (row.isVideo && row.durationMs > 0) {
        stringResource(
            R.string.biggest_detail_video, kind, Formats.duration(row.durationMs), taken
        ) + " \u00b7 " + album
    } else {
        stringResource(R.string.biggest_detail, kind, taken) + " \u00b7 " + album
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
            // Nothing here scrolled before. The mark, the heading and the
            // body with their padding are taller than a phone held sideways
            // at a large font, so the last line sat off the bottom of the
            // screen with no way to reach it.
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                EmptyState(
                    title = stringResource(R.string.history_empty_title),
                    body = stringResource(R.string.history_empty_body)
                )
            }
            return@Page
        }
        // Tagged like every other list in the app, so a test can scroll it with
        // performScrollToNode. performScrollTo does not support lazy lists
        // (issuetracker 178483889) and fails outright on one.
        LazyColumn(
            Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
                .testTag(ListTags.ROWS)
        ) {
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
                    // Every visible card filtered the whole loaded batch list
                    // again on every recomposition; it changes only when the
                    // loaded items do.
                    val shown = remember(items, batch.id) {
                        items.filter { it.batchId == batch.id }
                    }
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
                            // Full width, so the label has the whole card to
                            // sit in: at a 200% font scale a button sized to
                            // its own text is wider than a 320 dp phone and
                            // the words ran past the edge of the card.
                            OutlinedButton(
                                onClick = { rvm.restore(restorable) },
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .fillMaxWidth()
                            ) {
                                Text(
                                    stringResource(R.string.history_restore),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
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
