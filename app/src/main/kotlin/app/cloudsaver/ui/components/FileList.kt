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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.style.TextAlign
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

/**
 * One file in a find-space list.
 *
 * Thumbnail, name ellipsised at the end so the extension survives, one line of
 * context, the size on the right, and the actions behind an overflow. Actions
 * are not full-width buttons inside the row: two of those per row turned a
 * list of files into a wall of controls with the files squeezed between them.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Long-press starts a selection, exactly as it does on Files. Without it
    // a screen can show a checkbox once a selection exists but offer no way to
    // create one, which leaves "Select all" and the action bar unreachable.
    val card = if (onLongPress != null) {
        modifier
            .padding(vertical = 4.dp)
            .combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = onLongPress
            )
    } else {
        modifier.padding(vertical = 4.dp)
    }
    AppCard(modifier = card, onClick = if (onLongPress != null) null else onClick) {
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
                        // Bounded on purpose. This column carries no weight, so
                        // it measures at whatever its longest line wants and the
                        // name's weight(1f) is left with the remainder - which a
                        // long note takes down to nothing. "about 459 KB after
                        // optimising" is long enough to have emptied the name off
                        // every row of Largest files, a screen whose whole job is
                        // telling you which file is which. The name comes first.
                        modifier = Modifier.widthIn(max = 120.dp),
                        style = MaterialTheme.typography.labelSmall.merge(TabularFigures),
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
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

/** Filler so a pinned bottom bar never covers the last row. */
@Composable
fun ListTail(extra: Boolean = false) {
    Spacer(Modifier.height(if (extra) 96.dp else 24.dp))
}
