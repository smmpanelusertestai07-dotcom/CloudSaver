package app.cloudsaver.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cloudsaver.R
import app.cloudsaver.core.logic.ListFilters
import app.cloudsaver.ui.theme.TabularFigures

/**
 * Handles the instrumented tests use to address parts of the framework.
 *
 * A test that reached for the list by "whatever scrolls on this screen" was
 * one horizontal filter row away from measuring the wrong thing, and rows
 * below the fold are never composed, so the only trustworthy statement about
 * what a list holds comes from the list itself rather than from what happens
 * to be on screen. Tagging the container is what makes that statement
 * reachable.
 */
object ListTags {
    const val ROWS = "list:rows"
}

/**
 * The one list framework, used by every screen that shows files.
 *
 * Files, Exact duplicates, Biggest files, Reclaim space and Kept light copies
 * all answer the same question - "which of my files, and what can I do about
 * them" - and each used to answer it its own way: different filters, different
 * selection rules, three spellings of the same action. Learning one taught you
 * nothing about the next.
 *
 * Everything below is deliberately dumb: it holds no data and knows no rules.
 * Screens pass in what to show and what a choice means, so behaviour stays
 * identical everywhere while the meaning stays local to the screen.
 */

/** One option inside a filter or sort sheet. */
data class ListOption(
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit
)

/**
 * One filter chip and the sheet behind it.
 *
 * [valueLabel] is null while the filter is at its default, which is what keeps
 * the chip reading "Type" rather than "Type: All" - a chip that always states
 * a value makes an untouched filter look like an applied one.
 */
data class ListFilter(
    val name: String,
    val valueLabel: String?,
    val options: List<ListOption>
) {
    val isActive: Boolean get() = valueLabel != null
    val chipLabel: String get() = valueLabel?.let { "$name: $it" } ?: name
}

/**
 * Which rows are selected, surviving everything the system can throw at it.
 *
 * Rotation and process death both used to clear a selection of forty files
 * with no warning, which is the kind of thing that makes people stop trusting
 * a screen halfway through a job. The ids are saved, so the selection comes
 * back exactly as it was.
 */
@Stable
class ListSelection internal constructor(initial: Set<Long>) {
    var ids by mutableStateOf(initial)
        private set

    /** Selection mode is on whenever something is selected. */
    val active: Boolean get() = ids.isNotEmpty()
    val size: Int get() = ids.size

    operator fun contains(id: Long): Boolean = id in ids

    fun toggle(id: Long) {
        ids = if (id in ids) ids - id else ids + id
    }

    fun selectAll(all: Collection<Long>) {
        ids = ids + all
    }

    /** Replace the selection outright, for "select only the eligible ones". */
    fun replaceWith(only: Collection<Long>) {
        ids = only.toSet()
    }

    fun clear() {
        ids = emptySet()
    }

    companion object {
        val Saver = listSaver<ListSelection, Long>(
            save = { it.ids.toList() },
            restore = { ListSelection(it.toSet()) }
        )
    }
}

@Composable
fun rememberListSelection(): ListSelection =
    rememberSaveable(saver = ListSelection.Saver) { ListSelection(emptySet()) }

/**
 * The filter and sort chips, on one line that scrolls rather than wraps.
 *
 * A wrapping filter row changes the height of the screen as filters are
 * applied, which shifts the list under the reader's finger mid-tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListFilterRow(
    filters: List<ListFilter>,
    sort: ListFilter?,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf<ListFilter?>(null) }
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (filter in filters) {
            FilterChip(
                selected = filter.isActive,
                onClick = { open = filter },
                label = { Text(filter.chipLabel) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.FilterList,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
        sort?.let {
            FilterChip(
                selected = false,
                onClick = { open = it },
                label = { Text(it.chipLabel) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Sort,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }

    open?.let { filter ->
        val state = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { open = null }, sheetState = state) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(
                    filter.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                )
                for (option in filter.options) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                option.onSelect()
                                open = null
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        RadioButton(selected = option.selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * The bar that replaces the title while rows are selected.
 *
 * "Select all" says how many it will take, because on a filtered list "all" is
 * genuinely ambiguous and the difference can be several hundred files.
 */
@Composable
fun SelectionTopBar(
    selectedCount: Int,
    matchingCount: Int,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.list_exit_selection)
            )
        }
        Text(
            stringResource(R.string.list_selected_count, selectedCount),
            style = MaterialTheme.typography.titleMedium.merge(TabularFigures),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (selectedCount < matchingCount) {
            TextButton(onClick = onSelectAll) {
                Text(stringResource(R.string.list_select_all_matching, matchingCount))
            }
        } else {
            TextButton(onClick = onDeselectAll) {
                Text(stringResource(R.string.list_deselect_all))
            }
        }
    }
}

/**
 * The pinned bar carrying what is selected and the one thing to do with it.
 *
 * [blockedReason] is for the case where the action cannot apply to everything
 * chosen - "3 of 12 are not backed up yet". The button is not disabled and
 * left to be puzzled over; the bar says why and offers the way forward.
 */
@Composable
fun ListActionBar(
    summary: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    blockedReason: String? = null,
    narrowLabel: String? = null,
    onNarrow: (() -> Unit)? = null,
    note: String? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            blockedReason?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            // A neutral fact about the selection - "2 already optimised,
            // skipped" - which is the app being precise, not a problem, and
            // must not wear the error colour.
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium.merge(TabularFigures),
                    modifier = Modifier.weight(1f)
                )
                if (blockedReason != null && narrowLabel != null && onNarrow != null) {
                    TextButton(onClick = onNarrow) { Text(narrowLabel) }
                } else {
                    Button(onClick = onAction) { Text(actionLabel) }
                }
            }
        }
    }
}

/**
 * Placeholder rows while the real ones load.
 *
 * A spinner says "wait"; these say "a list is coming, and roughly this shape",
 * so the screen does not jump when the rows arrive.
 */
@Composable
fun ListSkeleton(rows: Int = 6, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha"
    )
    Column(modifier) {
        repeat(rows) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = alpha)
                        )
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    SkeletonBar(alpha, 0.7f)
                    Spacer(Modifier.height(6.dp))
                    SkeletonBar(alpha, 0.4f)
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(alpha: Float, widthFraction: Float) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = alpha))
    )
}

/**
 * Nothing matched the filters, as distinct from nothing existing.
 *
 * The two need different words and different offers: one is "you have no
 * duplicates", the other is "your filters exclude everything", and only the
 * second one has an obvious fix.
 */
@Composable
fun FilteredEmptyState(onReset: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.list_filtered_empty),
            style = MaterialTheme.typography.bodyLarge
        )
        TextButton(onClick = onReset, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.list_reset_filters))
        }
    }
}

/** No matches for a search term, with the term quoted back. */
@Composable
fun SearchEmptyState(term: String, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.list_no_matches_for, term),
            style = MaterialTheme.typography.bodyLarge
        )
        TextButton(onClick = onClear, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.list_clear_search_action))
        }
    }
}

/**
 * A whole list screen: header, search, filters, rows, and the selection bars.
 *
 * Screens supply their rows through [content] and nothing else about layout,
 * which is the point - the framework decides where things go so that all five
 * lists agree, and they cannot drift apart again by accident.
 */
@Composable
fun ListScreenScaffold(
    title: String,
    onBack: () -> Unit,
    showBack: Boolean = true,
    query: String,
    onQuery: (String) -> Unit,
    filters: List<ListFilter>,
    sort: ListFilter?,
    selection: ListSelection,
    matchingCount: Int,
    onSelectAll: () -> Unit,
    onResetFilters: () -> Unit,
    loading: Boolean,
    isEmpty: Boolean,
    emptyContent: @Composable () -> Unit,
    actionBar: (@Composable () -> Unit)? = null,
    intro: (@Composable () -> Unit)? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            if (selection.active) {
                SelectionTopBar(
                    selectedCount = selection.size,
                    matchingCount = matchingCount,
                    onSelectAll = onSelectAll,
                    onDeselectAll = { selection.clear() },
                    onClose = { selection.clear() }
                )
            } else {
                Row(
                    Modifier.padding(
                        top = if (showBack) 4.dp else 12.dp,
                        start = if (showBack) 4.dp else 16.dp,
                        end = 16.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // A tab has nowhere to go back to, so it gets no arrow.
                    // An arrow that pops the whole tab stack is worse than
                    // none: it looks like a way out and behaves like an exit.
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            intro?.invoke()

            Column(Modifier.padding(horizontal = 16.dp)) {
                ListSearchField(query, onQuery, Modifier.padding(top = 4.dp))
                if (filters.isNotEmpty() || sort != null) {
                    ListFilterRow(filters, sort, Modifier.padding(top = 10.dp))
                }
            }

            when {
                loading -> ListSkeleton(modifier = Modifier.padding(16.dp))
                isEmpty -> emptyContent()
                else -> LazyColumn(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .testTag(ListTags.ROWS)
                ) {
                    content()
                    item("tail") { ListTail(extra = selection.active) }
                }
            }
        }

        androidx.compose.foundation.layout.Box(
            Modifier.align(Alignment.BottomCenter)
        ) {
            AnimatedVisibility(
                visible = selection.active && actionBar != null,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it }
            ) {
                actionBar?.invoke()
            }
        }
    }
}

/**
 * Plural-aware "N selected - X", saying "frees X" only where it is true.
 *
 * [bytes] is the size of what is selected, which is all any caller has. On a
 * bar whose action removes those files from the phone that size is what gets
 * freed. On a bar whose action optimises them it is not: optimising writes a
 * smaller copy beside the original and frees nothing at all until the user
 * later chooses to remove the original through Android's own dialog. The Files
 * bar read "1 selected - frees 597 KB" above a button saying "Optimise these
 * first", which overstated both the amount and the moment.
 */
@Composable
fun selectionSummary(count: Int, bytes: String, frees: Boolean = true): String =
    pluralStringResource(
        if (frees) R.plurals.list_selection_summary else R.plurals.list_selection_size,
        count,
        count,
        bytes
    )

/**
 * The Type filter, present on every list screen and always first.
 *
 * Built here rather than in each screen so the labels, the order and the
 * "which value counts as default" decision are made once.
 */
@Composable
fun typeFilter(selected: ListFilters.Type, onSelect: (ListFilters.Type) -> Unit): ListFilter {
    val all = stringResource(R.string.filter_all)
    val photos = stringResource(R.string.scope_photos)
    val videos = stringResource(R.string.scope_videos)
    return ListFilter(
        name = stringResource(R.string.filter_type),
        valueLabel = when (selected) {
            ListFilters.Type.ALL -> null
            ListFilters.Type.PHOTOS -> photos
            ListFilters.Type.VIDEOS -> videos
        },
        options = listOf(
            ListOption(all, selected == ListFilters.Type.ALL) { onSelect(ListFilters.Type.ALL) },
            ListOption(photos, selected == ListFilters.Type.PHOTOS) {
                onSelect(ListFilters.Type.PHOTOS)
            },
            ListOption(videos, selected == ListFilters.Type.VIDEOS) {
                onSelect(ListFilters.Type.VIDEOS)
            }
        )
    )
}

/** The Size filter, in the bands people actually use. */
@Composable
fun sizeFilter(selected: ListFilters.Size, onSelect: (ListFilters.Size) -> Unit): ListFilter {
    val labels = listOf(
        ListFilters.Size.ANY to stringResource(R.string.filter_any),
        ListFilters.Size.OVER_10MB to stringResource(R.string.filter_over_10mb),
        ListFilters.Size.OVER_100MB to stringResource(R.string.filter_over_100mb),
        ListFilters.Size.OVER_1GB to stringResource(R.string.filter_over_1gb)
    )
    return ListFilter(
        name = stringResource(R.string.filter_size),
        valueLabel = if (selected == ListFilters.Size.ANY) {
            null
        } else {
            labels.first { it.first == selected }.second
        },
        options = labels.map { (value, label) ->
            ListOption(label, value == selected) { onSelect(value) }
        }
    )
}

/**
 * The Album filter, with a count beside each album.
 *
 * The counts are the point: "Camera (1,204)" tells someone whether narrowing
 * to it will help before they tap, which a bare list of folder names cannot.
 */
@Composable
fun albumFilter(
    selected: String?,
    albums: List<Pair<String, Int>>,
    onSelect: (String?) -> Unit
): ListFilter {
    val allLabel = stringResource(R.string.filter_all_albums)
    return ListFilter(
        name = stringResource(R.string.filter_album),
        valueLabel = selected,
        options = buildList {
            add(ListOption(allLabel, selected == null) { onSelect(null) })
            for ((album, count) in albums) {
                add(
                    ListOption(
                        stringResource(R.string.filter_album_count, album, count),
                        album == selected
                    ) { onSelect(album) }
                )
            }
        }
    )
}

/**
 * The removal warning, identical wherever files can be removed (Z1.3).
 *
 * Exactly three lines, always visible, never behind a tap: what is removed
 * from, what is never touched and why that is guaranteed, and where removed
 * files go. It explains; Android's own dialog still authorises. On Android 10
 * the third line changes because there is no gallery trash to restore from.
 */
@Composable
fun RemovalWarningCard(modifier: Modifier = Modifier) {
    val legacy = android.os.Build.VERSION.SDK_INT <
        app.cloudsaver.core.logic.Platform.TRASH_SDK
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp)) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    stringResource(R.string.warn_phone_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    stringResource(R.string.warn_cloud_untouched),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    stringResource(
                        if (legacy) R.string.warn_trash_none else R.string.warn_trash_30
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
