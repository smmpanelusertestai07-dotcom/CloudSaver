package app.cloudsaver.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cloudsaver.R
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.theme.TabularFigures
import app.cloudsaver.util.Formats

/**
 * "Try it on a few photos first", in one place so setup and Home cannot drift.
 *
 * It lives on Home as well as in setup because skipping it during setup used
 * to lose it for good, and the trial is the cheapest possible answer to "what
 * will this actually do to my photos". It stops being offered once there are
 * real results to look at instead: at that point the Files screen shows every
 * before-and-after there is, and a trial would be proving something already
 * proven.
 *
 * [size] is how many photos are genuinely waiting, capped at the trial size.
 * The button used to promise three whatever was there.
 *
 * [albumsChosen] decides which of three things the card says, and the same
 * rule holds in setup and on Home. During setup nothing has been scanned yet,
 * so [size] is zero there for a perfectly healthy phone: the card used to read
 * "no photos are waiting" and hide its own button, which made the trial look
 * broken exactly where it is most useful. The run scans the chosen albums
 * itself, so the offer only depends on an album being ticked.
 */
@Composable
fun TrialCard(
    size: Int,
    running: Boolean,
    results: List<AppViewModel.TestItem>?,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
    albumsChosen: Boolean = true,
    onChooseAlbums: (() -> Unit)? = null,
    accessFull: Boolean = true
) {
    AppCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Science,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.trial_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            when {
                // The run refuses under partial access, so offering the button
                // there would be a button that does nothing - the one thing a
                // screen must never do.
                !accessFull -> stringResource(R.string.trial_needs_access)
                !albumsChosen -> stringResource(R.string.trial_needs_albums)
                size > 0 -> pluralStringResource(R.plurals.trial_body, size, size)
                else -> stringResource(R.string.trial_ready)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (!accessFull) {
            // Nothing to offer: Home already carries the card that fixes this.
        } else if (!albumsChosen) {
            onChooseAlbums?.let { choose ->
                TextButton(onClick = choose, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.trial_choose_albums))
                }
            }
        } else {
            OutlinedButton(
                enabled = !running,
                onClick = onRun,
                modifier = Modifier.padding(top = 10.dp)
            ) {
                if (running) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.onb6_running))
                } else if (size > 0) {
                    Text(pluralStringResource(R.plurals.trial_action, size, size))
                } else {
                    Text(stringResource(R.string.trial_run))
                }
            }
        }
        results?.takeIf { it.isNotEmpty() }?.let { list ->
            Column(Modifier.padding(top = 10.dp)) {
                Text(
                    stringResource(R.string.trial_done),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                for (item in list) {
                    KeyValueRow(
                        item.name,
                        stringResource(
                            R.string.files_size_saving,
                            Formats.bytes(item.before),
                            Formats.bytes(item.after),
                            Formats.percentOf(
                                (item.before - item.after).coerceAtLeast(0), item.before
                            )
                        )
                    )
                }
                // What the trial is really for: the quality that survived it.
                val before = list.sumOf { it.before }
                val after = list.sumOf { it.after }
                if (before > 0) {
                    Text(
                        stringResource(
                            R.string.trial_kept,
                            Formats.bytes(before - after),
                            Formats.percentOf(before - after, before)
                        ),
                        style = MaterialTheme.typography.bodyMedium.merge(TabularFigures),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                // The pixels the encoder really kept on these very files. It
                // replaces a sentence that used to promise they "still look
                // the same", which was a claim nobody could check.
                val measured = list.mapNotNull { it.keptPercent }
                if (measured.isNotEmpty()) {
                    Text(
                        stringResource(R.string.trial_kept_detail, measured.average().toInt()),
                        style = MaterialTheme.typography.bodyMedium.merge(TabularFigures),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                // The trial makes the copies but does not publish them: that
                // is the release step, which is paced. Without this line the
                // app reports three optimised photos and the gallery shows no
                // CloudSaver album, which reads as the trial having failed.
                Text(
                    stringResource(R.string.trial_next),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
