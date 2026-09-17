package app.cloudsaver.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.R
import app.cloudsaver.ui.components.selectionSummary
import app.cloudsaver.ui.components.ListActionBar
import app.cloudsaver.ui.components.typeFilter
import app.cloudsaver.ui.components.rememberListSelection
import app.cloudsaver.ui.components.albumFilter
import app.cloudsaver.ui.components.SearchEmptyState
import app.cloudsaver.ui.components.ListScreenScaffold
import app.cloudsaver.ui.components.FilteredEmptyState
import app.cloudsaver.ui.components.FileRow
import app.cloudsaver.core.logic.ListFilters
import app.cloudsaver.core.logic.Defaults
import app.cloudsaver.core.logic.OutputPaths
import app.cloudsaver.data.db.ItemRow
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.EmptyState
import app.cloudsaver.ui.components.KeyValueRow
import app.cloudsaver.util.Formats

/**
 * The light copies the user chose to keep.
 *
 * These belong to them, not to CloudSaver: they live in their own album, the
 * app never deletes them on its own, and removing one here needs no Android
 * dialog only because the app created the file - which is exactly why the
 * confirm sheet has to say what is being given up.
 */
@Composable
fun KeptCopiesScreen(vm: AppViewModel, nav: NavHostController) {
    val rows by vm.keptCopies.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<ItemRow?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(ListFilters.Type.ALL) }
    var album by rememberSaveable { mutableStateOf<String?>(null) }
    val selection = rememberListSelection()
    LaunchedEffect(Unit) { vm.loadKeptCopies() }

    val state = ListFilters.State(type, ListFilters.Size.ANY, album, query)
    val shown = remember(rows, state) {
        rows.filter { ListFilters.matches(it.toKeptCandidate(), state) }
    }
    val albums = remember(rows) { ListFilters.albumCounts(rows.map { it.toKeptCandidate() }) }
    val removeLabel = stringResource(R.string.kept_remove)
    // Walked the whole list on every recomposition - every keystroke in the
    // search box, every tick of a checkbox - to answer a question that only
    // changes when the rows or the selection do.
    val chosen = remember(shown, selection.ids) { shown.filter { it.id in selection } }
    var confirmMany by remember { mutableStateOf(false) }

    ListScreenScaffold(
        title = stringResource(R.string.kept_title),
        onBack = { nav.popBackStack() },
        query = query,
        onQuery = { query = it },
        filters = listOf(
            typeFilter(type) { type = it },
            albumFilter(album, albums) { album = it }
        ),
        sort = null,
        selection = selection,
        matchingCount = shown.size,
        onSelectAll = { selection.selectAll(shown.map { it.id }) },
        onResetFilters = {
            type = ListFilters.Type.ALL
            album = null
        },
        actionBar = {
            ListActionBar(
                summary = selectionSummary(
                    selection.size,
                    Formats.bytes(chosen.sumOf { it.outputBytes ?: 0L })
                ),
                actionLabel = stringResource(R.string.kept_remove),
                onAction = { confirmMany = true }
            )
        },
        loading = false,
        isEmpty = shown.isEmpty(),
        emptyContent = {
            // An empty list is the one state with no list to scroll, and the
            // mark, the heading and the sentence under it are taller than a
            // phone on its side at a large font. It scrolls in its own right
            // so the offer under the message - clear the search, reset the
            // filters - is reachable rather than past the bottom edge.
            // The scroll belongs to the scaffold's empty branch, which is the
            // only place that knows how much height the title, the search box
            // and the chips above have already taken. A second one here is
            // measured by the first with no ceiling at all, and a scrolling
            // container asked how tall it would like to be throws rather than
            // answers - which is what crashed Files and Free up space the
            // moment a filter or a search left nothing to show.
            when {
                rows.isNotEmpty() && query.isNotBlank() ->
                    SearchEmptyState(term = query, onClear = { query = "" })
                rows.isNotEmpty() -> FilteredEmptyState(
                    onReset = {
                        type = ListFilters.Type.ALL
                        album = null
                    }
                )
                else -> EmptyState(
                    title = stringResource(R.string.kept_empty_title),
                    body = stringResource(R.string.kept_empty_body)
                )
            }
        
        },
        intro = {
            // This is drawn above the search box and outside the list, so
            // whatever height it asks for, the rows get what is left. Two
            // cards of explanation at the largest accessibility font are
            // taller than a phone lying on its side, which left the list with
            // no room at all and the files unreachable. Measuring the space
            // first keeps the explanation to half of it and lets it scroll
            // inside that, so the explanation and the files both survive.
            BoxWithConstraints {
                Column(
                    Modifier
                        .heightIn(max = maxHeight / 2)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Once, the first time there is anything here to explain:
                    // what these files are, and the one way to accidentally pay
                    // for them twice.
                    if (rows.isNotEmpty() && !options.keptCardSeen) {
                        AppCard(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            tonal = true
                        ) {
                            Text(
                                stringResource(R.string.kept_card_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(R.string.kept_card_body),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            TextButton(onClick = { vm.acknowledgeKeptCard() }) {
                                Text(stringResource(R.string.dismiss))
                            }
                        }
                    }
                    AppCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            stringResource(R.string.kept_intro),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            Defaults.KEPT_DIR,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            stringResource(R.string.kept_intro_backup),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    ) {
        items(shown, key = { it.id }) { row ->
            // The action sits in the overflow like every other list. A
            // full-width button inside each row turned a list of files into a
            // wall of controls with the files squeezed between them.
            FileRow(
                name = row.displayName,
                context = row.bucket ?: stringResource(R.string.biggest_no_album),
                size = Formats.bytes(row.outputBytes ?: 0L),
                proof = null,
                thumbnail = { Thumbnail(row) },
                actions = listOf(removeLabel to { confirm = row }),
                selected = if (selection.active) row.id in selection else null,
                onSelectedChange = { selection.toggle(row.id) },
                onLongPress = { selection.toggle(row.id) },
                onClick = {
                    if (selection.active) selection.toggle(row.id) else confirm = row
                }
            )
        }
    }

    if (confirmMany && chosen.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { confirmMany = false },
            title = { Text(stringResource(R.string.kept_remove_title)) },
            text = {
                // A dialog's text slot does not scroll. On a small screen at
                // a large font its lower half simply sits past the edge, and
                // the buttons are pushed off with it.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // This is the sheet that removes a whole selection, and it
                    // was handed the one-file wording verbatim: someone about
                    // to remove forty light copies read "this file" and "the
                    // light copy", in the singular, on the last screen before
                    // it happened. It reads as a warning about one thing, and
                    // the whole point of it is to say what is being given up.
                    // Same promise, same limits, worded for the number of
                    // files actually going.
                    Text(
                        pluralStringResource(
                            R.plurals.kept_remove_body_many,
                            chosen.size
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    for (row in chosen) vm.removeKeptCopy(row)
                    selection.clear()
                    confirmMany = false
                }) { Text(stringResource(R.string.kept_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmMany = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    confirm?.let { row ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.kept_remove_title)) },
            text = {
                // A dialog's text slot does not scroll. On a small screen at
                // a large font its lower half simply sits past the edge, and
                // the buttons are pushed off with it.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.kept_remove_body))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeKeptCopy(row)
                    confirm = null
                }) { Text(stringResource(R.string.kept_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/** A kept copy as the shared filters need to see it. */
private fun ItemRow.toKeptCandidate() = ListFilters.Candidate(
    id = id,
    name = displayName,
    album = bucket,
    sizeBytes = outputBytes ?: sizeBytes,
    isVideo = isVideo
)
