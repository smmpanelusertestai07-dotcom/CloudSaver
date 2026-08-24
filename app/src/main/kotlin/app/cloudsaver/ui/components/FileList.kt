package app.cloudsaver.ui.components

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cloudsaver.R
import app.cloudsaver.ui.theme.TabularFigures

/**
 * The parts every "find space" list shares.
 *
 * Duplicates, biggest files and reclaim all answer the same question and used
 * to answer it three different ways - three row layouts, three ideas of what
 * a size looks like, and only one of them able to do anything. They now use
 * these pieces, so a person who learns one list has learned all three.
 */

/** Search box: name or album, with a clear button once there is something in it. */
@Composable
fun ListSearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.list_search)
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.list_clear_search)
                    )
                }
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

/** A scrolling row of single-choice chips. */
@Composable
fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for ((value, label) in options) {
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) }
            )
        }
    }
}

/**
 * One file in a find-space list.
 *
 * Thumbnail, name ellipsised at the end so the extension survives, one line of
 * context, the size on the right, and the actions behind an overflow. Actions
 * are not full-width buttons inside the row: two of those per row turned a
 * list of files into a wall of controls with the files squeezed between them.
 */
@Composable
fun FileRow(
    name: String,
    context: String,
    size: String,
    proof: String?,
    thumbnail: @Composable () -> Unit,
    actions: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    onSelectedChange: ((Boolean) -> Unit)? = null,
    trailingNote: String? = null,
    onClick: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    AppCard(modifier = modifier.padding(vertical = 4.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected != null && onSelectedChange != null) {
                Checkbox(checked = selected, onCheckedChange = onSelectedChange)
                Spacer(Modifier.width(4.dp))
            }
            thumbnail()
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    context,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                proof?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    size,
                    style = MaterialTheme.typography.titleMedium.merge(TabularFigures)
                )
                trailingNote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall.merge(TabularFigures),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (actions.isNotEmpty()) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.list_more_actions)
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        for ((label, action) in actions) {
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    menuOpen = false
                                    action()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The bar that appears once something is selected.
 *
 * It states what will be freed before the action, not after: a person about to
 * remove forty files should not have to add up the list themselves.
 */
@Composable
fun SelectionBar(
    countLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.list_clear_selection))
            }
            Text(
                countLabel,
                style = MaterialTheme.typography.bodyMedium.merge(TabularFigures),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            Button(onClick = onAction, enabled = enabled) { Text(actionLabel) }
        }
    }
}

/** Filler so a pinned bottom bar never covers the last row. */
@Composable
fun ListTail(extra: Boolean = false) {
    Spacer(Modifier.height(if (extra) 96.dp else 24.dp))
}
