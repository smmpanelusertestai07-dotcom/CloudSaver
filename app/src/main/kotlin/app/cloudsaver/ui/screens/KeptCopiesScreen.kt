package app.cloudsaver.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.R
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

@Composable
private fun Page(
    nav: NavHostController,
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
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
        loading = false,
        isEmpty = shown.isEmpty(),
        emptyContent = {
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
                onClick = { confirm = row }
            )
        }
    }

    confirm?.let { row ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.kept_remove_title)) },
            text = { Text(stringResource(R.string.kept_remove_body)) },
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
